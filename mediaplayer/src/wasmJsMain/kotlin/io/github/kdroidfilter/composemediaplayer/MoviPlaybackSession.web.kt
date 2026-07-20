@file:OptIn(ExperimentalWasmJsInterop::class)
@file:Suppress("MagicNumber", "TooManyFunctions")

package io.github.kdroidfilter.composemediaplayer

import androidx.compose.ui.layout.ContentScale
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.WebFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import org.w3c.dom.HTMLCanvasElement
import org.w3c.dom.HTMLVideoElement
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.JsAny
import kotlin.js.js
import kotlin.time.Duration.Companion.seconds

internal const val MOVI_AUDIO_TRACK_ID_PREFIX = "movi:audio:"
internal const val MOVI_SUBTITLE_TRACK_ID_PREFIX = "movi:subtitle:"
internal const val MOVI_VIDEO_TRACK_ID_PREFIX = "movi:video:"

internal data class MoviVideoTrackSnapshot(
    val id: Int,
    val codec: String?,
    val width: Int?,
    val height: Int?,
    val frameRate: Float?,
    val bitrate: Int?,
    val pixelFormat: String?,
    val colorPrimaries: String?,
    val colorTransfer: String?,
    val colorMatrix: String?,
    val colorRange: String?,
    val isHdr: Boolean,
    val isActive: Boolean,
)

internal data class MoviMediaSnapshot(
    val formatName: String?,
    val durationSeconds: Double?,
    val bitrate: Long?,
    val title: String?,
    val audioTracks: List<AudioTrack>,
    val activeAudioTrackId: String?,
    val subtitleTracks: List<SubtitleTrack>,
    val activeSubtitleTrackId: String?,
    val videoTracks: List<MoviVideoTrackSnapshot>,
    val chapters: List<MediaChapter>,
) {
    val activeVideoTrack: MoviVideoTrackSnapshot?
        get() = videoTracks.firstOrNull(MoviVideoTrackSnapshot::isActive) ?: videoTracks.firstOrNull()
}

/**
 * Internal facade around `movi-player/player`.
 *
 * All JavaScript values stay in this file. The state and public API only see KMediaPlayer models.
 */
internal class MoviPlaybackSession(
    private val playerState: DefaultVideoPlayerState,
    private val mediaSessionId: Long,
    private val canvas: HTMLCanvasElement,
    private val onNativeVideoElement: (HTMLVideoElement?) -> Unit,
    private val onVideoRatio: (Float?) -> Unit,
    private val moduleLoader: suspend () -> JsAny = ::loadMoviPlayerModule,
) {
    private var player: JsAny? = null
    private var destroyed = false
    private var defaultAudioTrackId: String? = null
    private var lastDurationSeconds = 0.0
    private var requestedContentScale: ContentScale = ContentScale.Fit
    private var requestedProjection: VideoProjectionSettings = VideoProjectionSettings()

    private val playbackCallback: (Boolean) -> Unit = { shouldPlay ->
        currentPlayer()?.let { activePlayer ->
            if (shouldPlay) {
                playMovi(activePlayer) { message -> reportError(message) }
            } else {
                pauseMovi(activePlayer)
            }
        }
    }
    private val volumeCallback: (Float) -> Unit = { value ->
        currentPlayer()?.let { activePlayer -> setMoviVolume(activePlayer, value) }
    }
    private val speedCallback: (Float) -> Unit = { value ->
        currentPlayer()?.let { activePlayer -> setMoviPlaybackRate(activePlayer, value) }
    }
    private val resetCallback: () -> Unit = {
        currentPlayer()?.let { activePlayer ->
            pauseMovi(activePlayer)
            seekMovi(activePlayer, 0.0) { message -> reportError(message) }
        }
    }
    private val audioSelectionCallback: (AudioTrack?) -> TrackSelectionResult = { requestedTrack ->
        selectAudioTrack(requestedTrack)
    }
    private val subtitleCallback: (SubtitleTrack?) -> Unit = { requestedTrack ->
        currentPlayer()?.let { activePlayer ->
            val numericId = requestedTrack?.id?.removePrefix(MOVI_SUBTITLE_TRACK_ID_PREFIX)?.toIntOrNull()
            selectMoviSubtitleTrack(activePlayer, numericId) { success, message ->
                if (!isCurrent()) return@selectMoviSubtitleTrack
                if (!success && message != null) {
                    reportError(message)
                } else {
                    synchronizeSnapshot()
                }
            }
        }
    }
    private val qualityCallback: (String?) -> Unit = { variantId ->
        currentPlayer()?.let { activePlayer ->
            val numericId = variantId?.removePrefix(MOVI_VIDEO_TRACK_ID_PREFIX)?.toIntOrNull() ?: MOVI_AUTO_TRACK_ID
            if (selectMoviVideoTrack(activePlayer, numericId)) {
                synchronizeSnapshot()
            }
        }
    }
    private val currentTimeProvider: () -> kotlin.time.Duration = {
        currentPlayer()?.let(::getMoviCurrentTime)?.seconds ?: kotlin.time.Duration.ZERO
    }
    private val durationProvider: () -> kotlin.time.Duration = {
        currentPlayer()?.let(::getMoviDuration)?.seconds ?: kotlin.time.Duration.ZERO
    }

    @Suppress("CyclomaticComplexMethod", "TooGenericExceptionCaught")
    suspend fun load(
        sourceUri: String,
        sourceFile: PlatformFile?,
        mediaHeaders: Map<String, String>,
        drmConfiguration: WebDrmConfiguration?,
    ) {
        if (destroyed || !isCurrent()) return
        if (
            drmConfiguration != null &&
            (sourceFile != null || sourceUri.isInlineBrowserMediaUri())
        ) {
            playerState.onMoviError(
                VideoPlayerError.DrmError("DRM playback requires an adaptive URL source on WebAssembly."),
            )
            return
        }

        try {
            val module = moduleLoader()
            if (destroyed || !isCurrent()) return
            val browserFile =
                sourceFile?.browserFileOrNull()
                    ?: sourceUri
                        .takeIf(String::isInlineBrowserMediaUri)
                        ?.let { inlineUri -> loadInlineBrowserFile(inlineUri) }
            if (destroyed || !isCurrent()) return

            val createdPlayer =
                createMoviPlayer(
                    module = module,
                    canvas = canvas,
                    sourceUri = sourceUri,
                    browserFile = browserFile,
                    mediaHeadersJson = mediaHeaders.browserRequestHeadersJsonObjectString(),
                    drmEnabled = drmConfiguration != null,
                    licenseUrl = drmConfiguration?.licenseUrl,
                    licenseHeadersJson =
                        drmConfiguration
                            ?.licenseRequestHeaders
                            ?.browserRequestHeadersJsonObjectString()
                            .orEmpty(),
                )
            if (destroyed || !isCurrent()) {
                destroyMoviPlayer(createdPlayer)
                return
            }

            player = createdPlayer
            bindStateCallbacks()
            bindMoviPlayerEvents(
                player = createdPlayer,
                onStateChanged = ::handleStateChanged,
                onTimeChanged = ::handleTimeChanged,
                onDurationChanged = ::handleDurationChanged,
                onTracksChanged = { synchronizeSnapshot() },
                onError = ::reportError,
                onSeeking = {
                    if (isCurrent()) playerState.onWebSeeking()
                },
                onSeeked = {
                    if (isCurrent()) {
                        synchronizeTime(forceUpdate = true)
                        playerState.onWebSeeked()
                    }
                },
                onBufferChanged = { rows ->
                    if (isCurrent()) playerState.updateBufferedRanges(rows)
                },
                onEnded = ::handleEnded,
            )
            observeMoviCanvas(createdPlayer, canvas)
            awaitMoviLoad(createdPlayer)
            if (destroyed || !isCurrent()) return

            setMoviVolume(createdPlayer, playerState.volume)
            setMoviPlaybackRate(createdPlayer, playerState.playbackSpeed)
            applyContentScaleToPlayer(createdPlayer, requestedContentScale)
            applyProjectionToPlayer(createdPlayer, requestedProjection)
            synchronizeSnapshot()
            synchronizeTime(forceUpdate = true)
            playerState.updateBufferedRanges(readMoviBufferedRows(createdPlayer))
            playerState.onWebPlaybackReady()
            playerState.onWebSourceLoaded(getMoviDuration(createdPlayer).seconds)
            attachNativeDrmElement(createdPlayer, drmConfiguration != null)

            playerState.consumePendingSeekTime(getMoviDuration(createdPlayer).seconds)?.let { target ->
                seek(target.inWholeMilliseconds / MILLIS_PER_SECOND)
            }
            if (playerState.isPlaying) {
                playMovi(createdPlayer) { message -> reportError(message) }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            if (isCurrent()) {
                reportError(error.message ?: "Movi player initialization failed.")
            }
        }
    }

    fun applyContentScale(contentScale: ContentScale) {
        requestedContentScale = contentScale
        currentPlayer()?.let { activePlayer -> applyContentScaleToPlayer(activePlayer, contentScale) }
    }

    fun applyProjection(projection: VideoProjectionSettings) {
        requestedProjection = projection
        currentPlayer()?.let { activePlayer -> applyProjectionToPlayer(activePlayer, projection) }
    }

    fun seekPending() {
        val activePlayer = currentPlayer() ?: return
        val duration = getMoviDuration(activePlayer).seconds
        playerState.consumePendingSeekTime(duration)?.let { target ->
            seek(target.inWholeMilliseconds / MILLIS_PER_SECOND)
        }
    }

    fun destroy() {
        if (destroyed) return
        destroyed = true
        unbindStateCallbacks()
        onNativeVideoElement(null)
        player?.let(::destroyMoviPlayer)
        player = null
    }

    private fun bindStateCallbacks() {
        playerState.applyPlaybackCallback = playbackCallback
        playerState.applyVolumeCallback = volumeCallback
        playerState.applyPlaybackSpeedCallback = speedCallback
        playerState.resetPlaybackCallback = resetCallback
        playerState.applyAudioTrackSelectionCallback = audioSelectionCallback
        playerState.applySubtitleTrackCallback = subtitleCallback
        playerState.applyHlsQualityCallback = qualityCallback
        playerState.preciseCurrentTimeProvider = currentTimeProvider
        playerState.durationProvider = durationProvider
    }

    private fun unbindStateCallbacks() {
        if (playerState.applyPlaybackCallback === playbackCallback) playerState.applyPlaybackCallback = null
        if (playerState.applyVolumeCallback === volumeCallback) playerState.applyVolumeCallback = null
        if (playerState.applyPlaybackSpeedCallback === speedCallback) playerState.applyPlaybackSpeedCallback = null
        if (playerState.resetPlaybackCallback === resetCallback) playerState.resetPlaybackCallback = null
        if (playerState.applyAudioTrackSelectionCallback === audioSelectionCallback) {
            playerState.applyAudioTrackSelectionCallback = null
        }
        if (playerState.applySubtitleTrackCallback === subtitleCallback) playerState.applySubtitleTrackCallback = null
        if (playerState.applyHlsQualityCallback === qualityCallback) playerState.applyHlsQualityCallback = null
        if (playerState.preciseCurrentTimeProvider === currentTimeProvider) {
            playerState.preciseCurrentTimeProvider = null
        }
        if (playerState.durationProvider === durationProvider) playerState.durationProvider = null
    }

    private fun selectAudioTrack(requestedTrack: AudioTrack?): TrackSelectionResult {
        val activePlayer = currentPlayer() ?: return TrackSelectionResult.Failed("Movi is not ready.")
        val targetId =
            requestedTrack?.id
                ?: defaultAudioTrackId
                ?: return TrackSelectionResult.Failed("No default Movi audio track is available.")
        val numericId =
            targetId
                .removePrefix(MOVI_AUDIO_TRACK_ID_PREFIX)
                .toIntOrNull()
                ?: return TrackSelectionResult.Failed("The Movi audio track id is invalid.")
        if (!selectMoviAudioTrack(activePlayer, numericId)) {
            return TrackSelectionResult.Failed("Movi rejected the audio track change.")
        }
        synchronizeSnapshot()
        return requestedTrack?.let { TrackSelectionResult.Selected(it.id) } ?: TrackSelectionResult.Auto
    }

    private fun applyContentScaleToPlayer(
        activePlayer: JsAny,
        contentScale: ContentScale,
    ) {
        setMoviFitMode(
            activePlayer,
            when (contentScale) {
                ContentScale.Crop -> "cover"
                ContentScale.FillBounds -> "fill"
                else -> "contain"
            },
        )
    }

    private fun applyProjectionToPlayer(
        activePlayer: JsAny,
        projection: VideoProjectionSettings,
    ) {
        val normalized = projection.normalized()
        val enabled = normalized.requiresProjectionRenderer
        setMoviVrProjection(
            player = activePlayer,
            enabled = enabled,
            half = normalized.projectionType == VideoProjectionType.Equirect180,
            fisheye =
                normalized.projectionType == VideoProjectionType.Fisheye180 ||
                    normalized.projectionType == VideoProjectionType.Fisheye190 ||
                    normalized.projectionType == VideoProjectionType.Fisheye200 ||
                    normalized.projectionType == VideoProjectionType.Fisheye220,
            stereoSbs = normalized.stereoLayout == VideoStereoLayout.SideBySide,
        )
    }

    private fun seek(seconds: Double) {
        currentPlayer()?.let { activePlayer ->
            seekMovi(activePlayer, seconds.coerceAtLeast(0.0)) { message -> reportError(message) }
        }
    }

    private fun handleStateChanged(state: String) {
        if (!isCurrent()) return
        when (state) {
            "loading", "buffering" -> playerState.onWebWaiting()
            "seeking" -> playerState.onWebSeeking()
            "ready", "playing", "paused" -> playerState.onWebPlaybackReady()
            "ended" -> Unit
            "error" -> Unit
        }
        playerState.onMoviPlaybackState(state)
    }

    private fun handleTimeChanged(seconds: Double) {
        if (!isCurrent()) return
        val safeSeconds = seconds.takeIf { it.isFinite() && it >= 0.0 } ?: return
        val duration =
            currentPlayer()
                ?.let(::getMoviDuration)
                ?.takeIf { it.isFinite() && it >= 0.0 }
                ?: lastDurationSeconds
        lastDurationSeconds = duration
        playerState.onTimeUpdate(safeSeconds.seconds, duration.seconds)
    }

    private fun handleDurationChanged(seconds: Double) {
        if (!isCurrent() || !seconds.isFinite() || seconds < 0.0) return
        lastDurationSeconds = seconds
        synchronizeTime(forceUpdate = true)
    }

    private fun synchronizeTime(forceUpdate: Boolean) {
        val activePlayer = currentPlayer() ?: return
        val current = getMoviCurrentTime(activePlayer).takeIf { it.isFinite() && it >= 0.0 } ?: 0.0
        val duration = getMoviDuration(activePlayer).takeIf { it.isFinite() && it >= 0.0 } ?: 0.0
        lastDurationSeconds = duration
        playerState.onTimeUpdate(current.seconds, duration.seconds, forceUpdate)
    }

    private fun synchronizeSnapshot() {
        val activePlayer = currentPlayer() ?: return
        val snapshot = parseMoviSnapshotRows(readMoviSnapshotRows(activePlayer))
        defaultAudioTrackId =
            defaultAudioTrackId
                ?.takeIf { savedId -> snapshot.audioTracks.any { it.id == savedId } }
                ?: snapshot.activeAudioTrackId
                ?: snapshot.audioTracks.firstOrNull(AudioTrack::isDefault)?.id
                ?: snapshot.audioTracks.firstOrNull()?.id
        playerState.applyMoviSnapshot(snapshot)
        snapshot.activeVideoTrack?.let { track ->
            val ratio =
                track.width?.toFloat()?.let { width ->
                    track.height?.takeIf { it > 0 }?.let { height -> width / height.toFloat() }
                }
            onVideoRatio(ratio)
        }
    }

    private fun attachNativeDrmElement(
        activePlayer: JsAny,
        drmEnabled: Boolean,
    ) {
        if (!drmEnabled) {
            onNativeVideoElement(null)
            return
        }

        val nativeVideo = getMoviNativeVideoElement(activePlayer)
        if (nativeVideo == null) {
            playerState.onMoviError(
                VideoPlayerError.DrmError("Movi/Shaka did not provide a DRM video surface."),
            )
            return
        }
        onNativeVideoElement(nativeVideo)
    }

    private fun handleEnded() {
        if (!isCurrent()) return
        if (playerState.loop) {
            val activePlayer = currentPlayer() ?: return
            seekMovi(activePlayer, 0.0) { message -> reportError(message) }
            playMovi(activePlayer) { message -> reportError(message) }
            playerState.sliderPos = 0f
            playerState.emitPlaybackEvent { sessionId, sampledAtMs ->
                PlaybackEvent.PlaybackRestarted(
                    mediaSessionId = sessionId,
                    sampledAtMs = sampledAtMs,
                )
            }
            playerState.onRestart?.invoke()
        } else {
            playerState.onMoviPlaybackState("ended")
            playerState.emitPlaybackEvent { sessionId, sampledAtMs ->
                PlaybackEvent.PlaybackEnded(
                    mediaSessionId = sessionId,
                    sampledAtMs = sampledAtMs,
                )
            }
            playerState.onPlaybackEnded?.invoke()
        }
    }

    private fun reportError(rawMessage: String) {
        if (!isCurrent()) return
        val message =
            redactMoviError(
                message = rawMessage,
                drmConfiguration = playerState.playbackOptions.webDrmConfiguration,
                mediaRequestHeaders = playerState.requestHeaders,
            )
        playerState.onMoviError(classifyMoviError(message, playerState.playbackOptions.webDrmConfiguration != null))
    }

    private fun currentPlayer(): JsAny? = player?.takeIf { isCurrent() }

    private fun isCurrent(): Boolean = !destroyed && playerState.isCurrentMediaSession(mediaSessionId)
}

internal fun parseMoviSnapshotRows(rows: String): MoviMediaSnapshot {
    var formatName: String? = null
    var durationSeconds: Double? = null
    var bitrate: Long? = null
    var title: String? = null
    var activeAudioTrackId: String? = null
    var activeSubtitleTrackId: String? = null
    val audioTracks = mutableListOf<AudioTrack>()
    val subtitleTracks = mutableListOf<SubtitleTrack>()
    val videoTracks = mutableListOf<MoviVideoTrackSnapshot>()
    val chapters = mutableListOf<MediaChapter>()

    rows.lineSequence().filter(String::isNotBlank).forEach { row ->
        val columns = row.split('|').map(::decodeMoviColumn)
        when (columns.firstOrNull()) {
            "M" -> {
                formatName = columns.getOrNull(1).orEmpty().ifBlank { null }
                durationSeconds = columns.getOrNull(2)?.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0.0 }
                bitrate = columns.getOrNull(3)?.toLongOrNull()?.takeIf { it > 0L }
                title = columns.getOrNull(4).orEmpty().ifBlank { null }
            }
            "A" -> {
                val numericId = columns.getOrNull(1)?.toIntOrNull() ?: return@forEach
                val id = "$MOVI_AUDIO_TRACK_ID_PREFIX$numericId"
                val isActive = columns.getOrNull(8) == "1"
                audioTracks +=
                    AudioTrack(
                        id = id,
                        label = columns.getOrNull(2).orEmpty().ifBlank { "Audio ${audioTracks.size + 1}" },
                        language = columns.getOrNull(3).orEmpty(),
                        channels = columns.getOrNull(4)?.toIntOrNull()?.takeIf { it > 0 },
                        sampleRate = columns.getOrNull(5)?.toIntOrNull()?.takeIf { it > 0 },
                        bitrate = columns.getOrNull(6)?.toIntOrNull()?.takeIf { it > 0 },
                        isDefault = columns.getOrNull(7) == "1",
                    )
                if (isActive) activeAudioTrackId = id
            }
            "S" -> {
                val numericId = columns.getOrNull(1)?.toIntOrNull() ?: return@forEach
                val id = "$MOVI_SUBTITLE_TRACK_ID_PREFIX$numericId"
                subtitleTracks +=
                    SubtitleTrack(
                        id = id,
                        label = columns.getOrNull(2).orEmpty().ifBlank { "Subtitle ${subtitleTracks.size + 1}" },
                        language = columns.getOrNull(3).orEmpty(),
                        src = id,
                        format = SubtitleFormat.AUTO,
                        isEmbedded = true,
                    )
                if (columns.getOrNull(4) == "1") activeSubtitleTrackId = id
            }
            "V" -> {
                videoTracks +=
                    MoviVideoTrackSnapshot(
                        id = columns.getOrNull(1)?.toIntOrNull() ?: return@forEach,
                        codec = columns.getOrNull(2).orEmpty().ifBlank { null },
                        width = columns.getOrNull(3)?.toIntOrNull()?.takeIf { it > 0 },
                        height = columns.getOrNull(4)?.toIntOrNull()?.takeIf { it > 0 },
                        frameRate = columns.getOrNull(5)?.toFloatOrNull()?.takeIf { it.isFinite() && it > 0f },
                        bitrate = columns.getOrNull(6)?.toIntOrNull()?.takeIf { it > 0 },
                        pixelFormat = columns.getOrNull(7).orEmpty().ifBlank { null },
                        colorPrimaries = columns.getOrNull(8).orEmpty().ifBlank { null },
                        colorTransfer = columns.getOrNull(9).orEmpty().ifBlank { null },
                        colorMatrix = columns.getOrNull(10).orEmpty().ifBlank { null },
                        colorRange = columns.getOrNull(11).orEmpty().ifBlank { null },
                        isHdr = columns.getOrNull(12) == "1",
                        isActive = columns.getOrNull(13) == "1",
                    )
            }
            "C" -> {
                val start =
                    columns.getOrNull(1)?.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0.0 }
                        ?: return@forEach
                val end = columns.getOrNull(2)?.toDoubleOrNull()?.takeIf { it.isFinite() && it > start }
                chapters +=
                    MediaChapter(
                        start = start.seconds,
                        end = end?.seconds,
                        title = columns.getOrNull(3).orEmpty().ifBlank { null },
                    )
            }
        }
    }

    return MoviMediaSnapshot(
        formatName = formatName,
        durationSeconds = durationSeconds,
        bitrate = bitrate,
        title = title,
        audioTracks = audioTracks,
        activeAudioTrackId = activeAudioTrackId,
        subtitleTracks = subtitleTracks,
        activeSubtitleTrackId = activeSubtitleTrackId,
        videoTracks = videoTracks,
        chapters = chapters,
    )
}

@Suppress("CyclomaticComplexMethod")
internal fun MoviVideoTrackSnapshot.toVideoColorInfo(): VideoColorInfo {
    val normalizedTransfer = colorTransfer.orEmpty().lowercase()
    val transfer =
        when {
            normalizedTransfer.contains("2084") || normalizedTransfer == "pq" ->
                VideoColorTransfer.PQ
            normalizedTransfer.contains("hlg") || normalizedTransfer.contains("arib") ->
                VideoColorTransfer.HLG
            normalizedTransfer.contains("srgb") ->
                VideoColorTransfer.SRGB
            normalizedTransfer.isNotBlank() ->
                VideoColorTransfer.SDR
            else ->
                VideoColorTransfer.UNKNOWN
        }
    val dynamicRange =
        when (transfer) {
            VideoColorTransfer.PQ -> VideoDynamicRange.HDR10
            VideoColorTransfer.HLG -> VideoDynamicRange.HLG
            VideoColorTransfer.SDR, VideoColorTransfer.SRGB, VideoColorTransfer.LINEAR -> VideoDynamicRange.SDR
            VideoColorTransfer.UNKNOWN -> if (isHdr) VideoDynamicRange.HDR10 else VideoDynamicRange.UNKNOWN
        }
    return VideoColorInfo(
        dynamicRange = dynamicRange,
        bitDepth = pixelFormat?.extractMoviBitDepth(),
        primaries =
            when {
                colorPrimaries.orEmpty().contains("2020", ignoreCase = true) -> VideoColorPrimaries.BT2020
                colorPrimaries.orEmpty().contains("709", ignoreCase = true) -> VideoColorPrimaries.BT709
                colorPrimaries.orEmpty().contains("p3", ignoreCase = true) -> VideoColorPrimaries.DISPLAY_P3
                else -> VideoColorPrimaries.UNKNOWN
            },
        transfer = transfer,
        matrix =
            when {
                colorMatrix.orEmpty().contains("2020", ignoreCase = true) -> VideoColorMatrix.BT2020_NCL
                colorMatrix.orEmpty().contains("709", ignoreCase = true) -> VideoColorMatrix.BT709
                colorMatrix.orEmpty().contains("rgb", ignoreCase = true) -> VideoColorMatrix.RGB
                else -> VideoColorMatrix.UNKNOWN
            },
        range =
            when {
                colorRange.equals("full", ignoreCase = true) ||
                    colorRange == "2" -> VideoColorRange.FULL
                colorRange.equals("limited", ignoreCase = true) ||
                    colorRange.equals("tv", ignoreCase = true) ||
                    colorRange == "1" -> VideoColorRange.LIMITED
                else -> VideoColorRange.UNKNOWN
            },
    )
}

private fun String.extractMoviBitDepth(): Int? =
    when {
        contains("16", ignoreCase = true) -> 16
        contains("12", ignoreCase = true) -> 12
        contains("10", ignoreCase = true) || contains("p010", ignoreCase = true) -> 10
        contains("9", ignoreCase = true) -> 9
        isNotBlank() -> 8
        else -> null
    }

private fun classifyMoviError(
    message: String,
    isDrm: Boolean,
): VideoPlayerError {
    if (isDrm) return VideoPlayerError.DrmError(message)
    val normalized = message.lowercase()
    return when {
        "codec" in normalized || "decode" in normalized || "decoder" in normalized ->
            VideoPlayerError.CodecError(message)
        "network" in normalized || "fetch" in normalized || "http" in normalized || "range" in normalized ->
            VideoPlayerError.NetworkError(message)
        "source" in normalized || "demux" in normalized || "format" in normalized ->
            VideoPlayerError.SourceError(message)
        else -> VideoPlayerError.UnknownError(message)
    }
}

internal fun redactMoviError(
    message: String,
    drmConfiguration: WebDrmConfiguration?,
    mediaRequestHeaders: Map<String, String> = emptyMap(),
): String {
    if (drmConfiguration != null) {
        // Upstream/browser errors can serialize or otherwise transform request data. Returning a
        // fixed message is the only way to guarantee that runtime-only DRM values never reach
        // application diagnostics or logs.
        return "DRM playback failed in Movi/Shaka; license details were redacted."
    }
    if (mediaRequestHeaders.isNotEmpty()) {
        return "Movi playback failed; media request details were redacted."
    }

    return message
}

private fun PlatformFile.browserFileOrNull(): JsAny? =
    when (val source = webFile) {
        is WebFile.FileWrapper -> source.file
        is WebFile.DirectoryWrapper -> null
    }

private fun String.isInlineBrowserMediaUri(): Boolean =
    startsWith("blob:", ignoreCase = true) || startsWith("data:", ignoreCase = true)

private suspend fun loadInlineBrowserFile(uri: String): JsAny {
    val deferred = CompletableDeferred<JsAny>()
    fetchInlineBrowserFile(
        uri = uri,
        onLoaded = { file -> deferred.complete(file) },
        onError = { message -> deferred.completeExceptionally(IllegalStateException(message)) },
    )
    return deferred.await()
}

@Suppress("UNUSED_PARAMETER")
private fun fetchInlineBrowserFile(
    uri: String,
    onLoaded: (JsAny) -> Unit,
    onError: (String) -> Unit,
): Unit =
    js(
        """
        {
            fetch(uri)
                .then(function(response) {
                    if (!response.ok) {
                        throw new Error("Inline media fetch failed with HTTP " + response.status + ".");
                    }
                    return response.blob();
                })
                .then(function(blob) {
                    onLoaded(new File([blob], "inline-media", {
                        type: blob.type || "application/octet-stream"
                    }));
                })
                .catch(function(error) {
                    const message = error && error.message ? error.message : String(error);
                    onError(String(message || "Unable to read the inline browser media source."));
                });
        }
        """,
    )

private suspend fun loadMoviPlayerModule(): JsAny {
    val moduleUrl = WebMediaDependencyConfig.moviPlayerModuleUrl.trim()
    require(moduleUrl.isNotEmpty()) {
        "WebMediaDependencyConfig.moviPlayerModuleUrl must identify a MoviPlayer ES module."
    }
    val deferred = CompletableDeferred<JsAny>()
    importMoviPlayerModule(
        moduleUrl = moduleUrl,
        onLoaded = { module -> deferred.complete(module) },
        onError = { message -> deferred.completeExceptionally(IllegalStateException(message)) },
    )
    return deferred.await()
}

private suspend fun awaitMoviLoad(player: JsAny) {
    val deferred = CompletableDeferred<Unit>()
    loadMovi(
        player = player,
        onLoaded = { deferred.complete(Unit) },
        onError = { message -> deferred.completeExceptionally(IllegalStateException(message)) },
    )
    deferred.await()
}

@Suppress("UNUSED_PARAMETER")
private fun importMoviPlayerModule(
    moduleUrl: String,
    onLoaded: (JsAny) -> Unit,
    onError: (String) -> Unit,
): Unit =
    js(
        """
        {
            const url = String(moduleUrl);
            if (!globalThis.__composeMediaPlayerMoviModulePromises) {
                globalThis.__composeMediaPlayerMoviModulePromises = new Map();
            }
            if (!globalThis.__composeMediaPlayerMoviModulePromises.has(url)) {
                globalThis.__composeMediaPlayerMoviModulePromises.set(
                    url,
                    import(/* webpackIgnore: true */ url)
                );
            }
            globalThis.__composeMediaPlayerMoviModulePromises.get(url)
                .then(function(module) { onLoaded(module); })
                .catch(function(error) {
                    const message = error && error.message ? error.message : String(error);
                    onError(String(message || "Unable to import the external MoviPlayer module."));
                });
        }
        """,
    )

@Suppress("UNUSED_PARAMETER")
internal fun createMoviPlayer(
    module: JsAny,
    canvas: HTMLCanvasElement,
    sourceUri: String,
    browserFile: JsAny?,
    mediaHeadersJson: String,
    drmEnabled: Boolean,
    licenseUrl: String?,
    licenseHeadersJson: String,
): JsAny =
    js(
        """
        (function() {
            const MoviPlayer =
                module && (module.MoviPlayer || (module.default && module.default.MoviPlayer) || module.default);
            if (typeof MoviPlayer !== "function") {
                throw new Error("movi-player/player does not export MoviPlayer.");
            }
            // Keep the dependency logger silent even if another bundle changed its process-wide
            // log level. Upstream error payloads can contain request URLs or headers; KMediaPlayer
            // exposes only the redacted, typed error emitted through the adapter below.
            if (typeof MoviPlayer.setLogLevel === "function") {
                MoviPlayer.setLogLevel(0);
            }
            const parseHeaders = function(value) {
                try { return JSON.parse(value || "{}") || {}; } catch (_) { return {}; }
            };
            const mediaHeaders = parseHeaders(mediaHeadersJson);
            const source = browserFile
                ? { type: "file", file: browserFile }
                : { type: "url", url: sourceUri, headers: mediaHeaders };
            const config = {
                source: source,
                renderer: "canvas",
                decoder: "auto",
                canvas: canvas,
                headers: mediaHeaders
            };
            if (drmEnabled) {
                config.drm = true;
                config.licenseUrl = licenseUrl;
                config.licenseHeaders = parseHeaders(licenseHeadersJson);
            }
            return new MoviPlayer(config);
        })()
        """,
    )

@Suppress("UNUSED_PARAMETER")
private fun loadMovi(
    player: JsAny,
    onLoaded: () -> Unit,
    onError: (String) -> Unit,
): Unit =
    js(
        """
        {
            Promise.resolve(player.load())
                .then(function() { onLoaded(); })
                .catch(function(error) {
                    const message = error && error.message ? error.message : String(error);
                    onError(String(message || "Movi failed to load the source."));
                });
        }
        """,
    )

@Suppress("UNUSED_PARAMETER", "LongParameterList")
internal fun bindMoviPlayerEvents(
    player: JsAny,
    onStateChanged: (String) -> Unit,
    onTimeChanged: (Double) -> Unit,
    onDurationChanged: (Double) -> Unit,
    onTracksChanged: () -> Unit,
    onError: (String) -> Unit,
    onSeeking: () -> Unit,
    onSeeked: () -> Unit,
    onBufferChanged: (String) -> Unit,
    onEnded: () -> Unit,
): Unit =
    js(
        """
        {
            const subscriptions = [];
            const listen = function(event, callback) {
                const dispose = player.on(event, callback);
                if (typeof dispose === "function") subscriptions.push(dispose);
            };
            const bufferRows = function(ranges) {
                return (Array.isArray(ranges) ? ranges : []).map(function(range) {
                    return String(Number(range && range.start || 0)) + "|" +
                        String(Number(range && range.end || 0));
                }).join("\n");
            };
            listen("stateChange", function(value) { onStateChanged(String(value || "")); });
            listen("timeUpdate", function(value) { onTimeChanged(Number(value || 0)); });
            listen("durationChange", function(value) { onDurationChanged(Number(value || 0)); });
            listen("tracksChange", function() { onTracksChanged(); });
            listen("error", function(error) {
                const message = error && error.message ? error.message : String(error);
                onError(String(message || "Movi playback failed."));
            });
            listen("seeking", function() { onSeeking(); });
            listen("seeked", function() { onSeeked(); });
            listen("bufferUpdate", function(ranges) { onBufferChanged(bufferRows(ranges)); });
            listen("ended", function() { onEnded(); });
            player.__composeMediaPlayerSubscriptions = subscriptions;
        }
        """,
    )

@Suppress("UNUSED_PARAMETER")
private fun readMoviSnapshotRows(player: JsAny): String =
    js(
        """
        (function() {
            const encode = function(value) { return encodeURIComponent(value == null ? "" : String(value)); };
            const row = function(values) { return values.map(encode).join("|"); };
            const rows = [];
            let info = null;
            try { info = player.getMediaInfo ? player.getMediaInfo() : null; } catch (_) {}
            const metadata = info && info.metadata ? info.metadata : {};
            rows.push(row([
                "M",
                info && info.formatName,
                info && info.duration,
                info && info.bitRate,
                metadata.title || metadata.TITLE || metadata.name
            ]));

            const manager = player.trackManager;
            const activeAudio = manager && manager.getActiveAudioTrack ? manager.getActiveAudioTrack() : null;
            const activeSubtitle = manager && manager.getActiveSubtitleTrack ? manager.getActiveSubtitleTrack() : null;
            const activeVideo = manager && manager.getActiveVideoTrack ? manager.getActiveVideoTrack() : null;

            const audioTracks = player.getAudioTracks ? player.getAudioTracks() : [];
            (Array.isArray(audioTracks) ? audioTracks : []).forEach(function(track, index) {
                rows.push(row([
                    "A",
                    track.id,
                    track.label,
                    track.language,
                    track.channels,
                    track.sampleRate,
                    track.bitRate,
                    activeAudio ? activeAudio.id === track.id : index === 0,
                    activeAudio && activeAudio.id === track.id
                ]));
            });

            const subtitleTracks = player.getSubtitleTracks ? player.getSubtitleTracks() : [];
            (Array.isArray(subtitleTracks) ? subtitleTracks : []).forEach(function(track) {
                rows.push(row([
                    "S",
                    track.id,
                    track.label,
                    track.language,
                    activeSubtitle && activeSubtitle.id === track.id
                ]));
            });

            const videoTracks = player.getVideoTracks ? player.getVideoTracks() : [];
            (Array.isArray(videoTracks) ? videoTracks : []).forEach(function(track, index) {
                rows.push(row([
                    "V",
                    track.id,
                    track.codecString || track.codec,
                    track.width,
                    track.height,
                    track.frameRate,
                    track.bitRate,
                    track.pixelFormat,
                    track.colorPrimaries,
                    track.colorTransfer,
                    track.colorSpace,
                    track.colorRange,
                    track.isHDR,
                    activeVideo ? activeVideo.id === track.id : index === 0
                ]));
            });

            let chapters = [];
            try { chapters = player.getChapters ? player.getChapters() : []; } catch (_) {}
            (Array.isArray(chapters) ? chapters : []).forEach(function(chapter) {
                rows.push(row(["C", chapter.start, chapter.end, chapter.title]));
            });
            return rows.join("\n");
        })()
        """,
    )

@Suppress("UNUSED_PARAMETER")
private fun readMoviBufferedRows(player: JsAny): String =
    js(
        """
        (function() {
            let ranges = [];
            try { ranges = player.getCachedTimeRanges ? player.getCachedTimeRanges() : []; } catch (_) {}
            if (!Array.isArray(ranges) || ranges.length === 0) {
                let start = 0;
                let end = 0;
                try { start = Number(player.getBufferStartTime ? player.getBufferStartTime() : 0); } catch (_) {}
                try { end = Number(player.getBufferEndTime ? player.getBufferEndTime() : 0); } catch (_) {}
                ranges = end > start ? [{ start: start, end: end }] : [];
            }
            return ranges.map(function(range) {
                return String(Number(range && range.start || 0)) + "|" +
                    String(Number(range && range.end || 0));
            }).join("\n");
        })()
        """,
    )

@Suppress("UNUSED_PARAMETER")
private fun playMovi(
    player: JsAny,
    onError: (String) -> Unit,
): Unit =
    js(
        """
        {
            Promise.resolve(player.play()).catch(function(error) {
                const message = error && error.message ? error.message : String(error);
                onError(String(message || "Movi could not start playback."));
            });
        }
        """,
    )

@Suppress("UNUSED_PARAMETER")
private fun pauseMovi(player: JsAny): Unit = js("player.pause()")

@Suppress("UNUSED_PARAMETER")
private fun seekMovi(
    player: JsAny,
    seconds: Double,
    onError: (String) -> Unit,
): Unit =
    js(
        """
        {
            Promise.resolve(player.seek(seconds)).catch(function(error) {
                const message = error && error.message ? error.message : String(error);
                onError(String(message || "Movi seek failed."));
            });
        }
        """,
    )

@Suppress("UNUSED_PARAMETER")
private fun selectMoviAudioTrack(
    player: JsAny,
    trackId: Int,
): Boolean = js("!!player.selectAudioTrack(trackId)")

@Suppress("UNUSED_PARAMETER")
private fun selectMoviSubtitleTrack(
    player: JsAny,
    trackId: Int?,
    onResult: (Boolean, String?) -> Unit,
): Unit =
    js(
        """
        {
            Promise.resolve(player.selectSubtitleTrack(trackId))
                .then(function(value) { onResult(value === true, null); })
                .catch(function(error) {
                    const message = error && error.message ? error.message : String(error);
                    onResult(false, String(message || "Movi rejected the subtitle track change."));
                });
        }
        """,
    )

@Suppress("UNUSED_PARAMETER")
private fun selectMoviVideoTrack(
    player: JsAny,
    trackId: Int,
): Boolean =
    js(
        """
        !!(player.trackManager &&
            typeof player.trackManager.selectVideoTrack === "function" &&
            player.trackManager.selectVideoTrack(trackId))
        """,
    )

@Suppress("UNUSED_PARAMETER")
private fun setMoviVolume(
    player: JsAny,
    value: Float,
): Unit = js("player.setVolume(value)")

@Suppress("UNUSED_PARAMETER")
private fun setMoviPlaybackRate(
    player: JsAny,
    value: Float,
): Unit = js("player.setPlaybackRate(value)")

@Suppress("UNUSED_PARAMETER")
private fun setMoviFitMode(
    player: JsAny,
    mode: String,
): Unit = js("player.setFitMode(mode)")

@Suppress("UNUSED_PARAMETER")
private fun setMoviVrProjection(
    player: JsAny,
    enabled: Boolean,
    half: Boolean,
    fisheye: Boolean,
    stereoSbs: Boolean,
): Unit =
    js(
        """
        {
            if (typeof player.setVR360 === "function") player.setVR360(enabled);
            if (enabled && typeof player.setVRProjection === "function") {
                player.setVRProjection(half, fisheye, stereoSbs, false);
            }
        }
        """,
    )

@Suppress("UNUSED_PARAMETER")
private fun getMoviCurrentTime(player: JsAny): Double = js("Number(player.getCurrentTime() || 0)")

@Suppress("UNUSED_PARAMETER")
private fun getMoviDuration(player: JsAny): Double = js("Number(player.getDuration() || 0)")

@Suppress("UNUSED_PARAMETER")
private fun getMoviNativeVideoElement(player: JsAny): HTMLVideoElement? =
    js("player.getHLSVideoElement ? player.getHLSVideoElement() : null")

@Suppress("UNUSED_PARAMETER")
private fun observeMoviCanvas(
    player: JsAny,
    canvas: HTMLCanvasElement,
): Unit =
    js(
        """
        {
            const resize = function() {
                const rect = canvas.getBoundingClientRect();
                const ratio = globalThis.devicePixelRatio || 1;
                const width = Math.max(1, Math.round(rect.width * ratio));
                const height = Math.max(1, Math.round(rect.height * ratio));
                if (canvas.width === width && canvas.height === height) return;
                try { player.resizeCanvas(width, height); } catch (_) {}
            };
            resize();
            if (typeof ResizeObserver === "function") {
                let pendingFrame = 0;
                const observer = new ResizeObserver(function() {
                    if (pendingFrame) return;
                    pendingFrame = requestAnimationFrame(function() {
                        pendingFrame = 0;
                        resize();
                    });
                });
                observer.observe(canvas);
                player.__composeMediaPlayerResizeObserver = observer;
                player.__composeMediaPlayerResizeFrame = function() {
                    if (pendingFrame) cancelAnimationFrame(pendingFrame);
                    pendingFrame = 0;
                };
            }
        }
        """,
    )

@Suppress("UNUSED_PARAMETER")
private fun destroyMoviPlayer(player: JsAny): Unit =
    js(
        """
        {
            if (player.__composeMediaPlayerDestroyed) return;
            player.__composeMediaPlayerDestroyed = true;
            const subscriptions = player.__composeMediaPlayerSubscriptions || [];
            subscriptions.forEach(function(dispose) {
                try { dispose(); } catch (_) {}
            });
            player.__composeMediaPlayerSubscriptions = [];
            if (player.__composeMediaPlayerResizeObserver) {
                try { player.__composeMediaPlayerResizeObserver.disconnect(); } catch (_) {}
                player.__composeMediaPlayerResizeObserver = null;
            }
            if (player.__composeMediaPlayerResizeFrame) {
                try { player.__composeMediaPlayerResizeFrame(); } catch (_) {}
                player.__composeMediaPlayerResizeFrame = null;
            }
            try { player.destroy(); } catch (_) {}
        }
        """,
    )

@Suppress("UNUSED_PARAMETER")
private fun decodeMoviColumn(value: String): String = js("decodeURIComponent(value)")

private const val MOVI_AUTO_TRACK_ID = -1
private const val MILLIS_PER_SECOND = 1000.0
