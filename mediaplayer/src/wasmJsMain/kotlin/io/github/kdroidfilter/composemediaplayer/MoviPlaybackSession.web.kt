@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
@file:Suppress("MagicNumber", "TooManyFunctions")

package io.github.kdroidfilter.composemediaplayer

import androidx.compose.ui.layout.ContentScale
import io.github.shusek.moviplayer.FitMode
import io.github.shusek.moviplayer.MediaInfo
import io.github.shusek.moviplayer.MediaSource
import io.github.shusek.moviplayer.MoviError
import io.github.shusek.moviplayer.MoviErrorCategory
import io.github.shusek.moviplayer.MoviPlayer
import io.github.shusek.moviplayer.MoviPlayerConfig
import io.github.shusek.moviplayer.MoviPlayerState
import io.github.shusek.moviplayer.MoviRuntimeConfig
import io.github.shusek.moviplayer.PlayerSurface
import io.github.shusek.moviplayer.RenderingDiagnostics
import io.github.shusek.moviplayer.TrackSelectionRequest
import io.github.shusek.moviplayer.TrackSelectionStatus
import io.github.shusek.moviplayer.redactSensitiveText
import io.github.vinceglb.filekit.BrowserFile
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.WebFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import org.w3c.dom.HTMLCanvasElement
import org.w3c.dom.HTMLVideoElement
import org.w3c.files.File
import kotlin.js.js
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import io.github.shusek.moviplayer.BufferedRange as MoviBufferedRange
import io.github.shusek.moviplayer.PlaybackEvent as MoviPlaybackEvent

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
    val diagnostics: MoviRenderingDiagnosticsSnapshot? = null,
) {
    val activeVideoTrack: MoviVideoTrackSnapshot?
        get() = videoTracks.firstOrNull(MoviVideoTrackSnapshot::isActive) ?: videoTracks.firstOrNull()
}

internal data class MoviRenderingDiagnosticsSnapshot(
    val backend: String?,
    val decoder: String?,
    val renderer: String?,
    val container: String?,
)

/**
 * Direct Kotlin/Wasm adapter around movi-player.
 *
 * No JavaScript module loading or untyped player calls live here: media models,
 * state and events cross the boundary as Kotlin types.
 */
internal class MoviPlaybackSession(
    private val playerState: DefaultVideoPlayerState,
    private val mediaSessionId: Long,
    private val canvas: HTMLCanvasElement,
    private val onNativeVideoElement: (HTMLVideoElement?) -> Unit,
    private val onVideoRatio: (Float?) -> Unit,
) {
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var player: MoviPlayer? = null
    private var destroyed: Boolean = false
    private var defaultAudioTrackId: String? = null
    private var audioSelectionGeneration: Int = 0
    private var subtitleSelectionGeneration: Int = 0
    private var requestedContentScale: ContentScale = ContentScale.Fit
    private var requestedProjection: VideoProjectionSettings = VideoProjectionSettings()

    private val playbackCallback: (Boolean) -> Unit = { shouldPlay ->
        currentPlayer()?.let { activePlayer ->
            if (shouldPlay) {
                scope.launch {
                    runCatching { activePlayer.play() }
                        .onFailure(::reportError)
                }
            } else {
                activePlayer.pause()
            }
        }
    }
    private val volumeCallback: (Float) -> Unit = { value ->
        currentPlayer()?.setVolume(value)
    }
    private val speedCallback: (Float) -> Unit = { value ->
        currentPlayer()?.setPlaybackRate(value)
    }
    private val resetCallback: () -> Unit = {
        currentPlayer()?.let { activePlayer ->
            activePlayer.pause()
            scope.launch {
                runCatching { activePlayer.seek(Duration.ZERO) }
                    .onFailure(::reportError)
            }
        }
    }
    private val audioSelectionCallback: (AudioTrack?) -> TrackSelectionResult = ::selectAudioTrack
    private val subtitleCallback: (SubtitleTrack?) -> Unit = { requestedTrack ->
        val activePlayer = currentPlayer()
        if (activePlayer != null) {
            val requestedId = requestedTrack?.id?.removePrefix(MOVI_SUBTITLE_TRACK_ID_PREFIX)?.toIntOrNull()
            val selectionGeneration = ++subtitleSelectionGeneration
            scope.launch {
                val result =
                    runCatching {
                        activePlayer.selectTrack(TrackSelectionRequest.Subtitle(requestedId))
                    }.getOrElse { error ->
                        if (isCurrent() && selectionGeneration == subtitleSelectionGeneration) reportError(error)
                        return@launch
                    }
                if (!isCurrent() || selectionGeneration != subtitleSelectionGeneration) return@launch
                if (result.status.isApplied()) {
                    synchronizeSnapshot(confirmedTrackKind = TrackKind.SUBTITLE)
                } else if (result.status != TrackSelectionStatus.SUPERSEDED) {
                    synchronizeSnapshot()
                    reportError(result.error ?: IllegalStateException("Movi rejected the subtitle track change."))
                }
            }
        }
    }
    private val qualityCallback: (String?) -> Unit = { variantId ->
        val numericId = variantId?.removePrefix(MOVI_VIDEO_TRACK_ID_PREFIX)?.toIntOrNull()
        currentPlayer()?.let { activePlayer ->
            scope.launch {
                runCatching {
                    activePlayer.selectTrack(TrackSelectionRequest.Video(numericId))
                }.onSuccess { result ->
                    if (result.status.isApplied()) synchronizeSnapshot()
                }.onFailure(::reportError)
            }
        }
    }
    private val currentTimeProvider: () -> Duration = {
        currentPlayer()?.position?.value ?: Duration.ZERO
    }
    private val durationProvider: () -> Duration = {
        currentPlayer()?.duration?.value ?: Duration.ZERO
    }

    suspend fun load(
        sourceUri: String,
        sourceFile: PlatformFile?,
        mediaHeaders: Map<String, String>,
        drmConfiguration: WebDrmConfiguration?,
    ) {
        if (destroyed || !isCurrent()) return
        if (drmConfiguration != null && (sourceFile != null || sourceUri.isInlineBrowserMediaUri())) {
            playerState.onMoviError(
                VideoPlayerError.DrmError("DRM playback requires an adaptive URL source on WebAssembly."),
            )
            return
        }

        try {
            val createdPlayer =
                MoviPlayer(
                    MoviPlayerConfig(
                        canvas = canvas,
                        runtime =
                            MoviRuntimeConfig(
                                assetBaseUrl = WebMediaDependencyConfig.moviRuntimeAssetBaseUrl,
                            ),
                    ),
                )
            player = createdPlayer
            bindStateCallbacks()
            bindPlayerEvents(createdPlayer)
            playerState.webSubtitlePipelineExtensions
                .asSequence()
                .mapNotNull { extension ->
                    runCatching {
                        extension.createMoviEmbeddedSubtitleRenderer(::reportError)
                    }.onFailure(::reportError).getOrNull()
                }.firstOrNull()
                ?.let(createdPlayer::setEmbeddedSubtitleRenderer)
            createdPlayer.setSubtitleDelay(-playerState.subtitleOffset)
            createdPlayer.setVolume(playerState.volume)
            createdPlayer.setPlaybackRate(playerState.playbackSpeed)
            createdPlayer.setFitMode(requestedContentScale.toMoviFitMode())

            createdPlayer.load(
                source =
                    when {
                        drmConfiguration != null ->
                            MediaSource.Drm(
                                mediaUrl = sourceUri,
                                licenseUrl = drmConfiguration.licenseUrl,
                                mediaHeaders = mediaHeaders,
                                licenseHeaders = drmConfiguration.licenseRequestHeaders,
                            )
                        sourceFile?.browserFileOrNull() != null ->
                            MediaSource.BrowserFile(requireNotNull(sourceFile.browserFileOrNull()))
                        else -> MediaSource.Url(sourceUri, mediaHeaders)
                    },
            )
            if (!isCurrent()) {
                createdPlayer.close()
                return
            }

            synchronizeSnapshot()
            synchronizeSurface()
            synchronizeTime(forceUpdate = true)
            playerState.updateBufferedRanges(createdPlayer.bufferedRanges.value.toKMediaRanges())
            playerState.onWebPlaybackReady()
            playerState.onWebSourceLoaded(createdPlayer.duration.value)
            playerState.consumePendingSeekTime(createdPlayer.duration.value)?.let { target ->
                createdPlayer.seek(target)
            }
            if (playerState.isPlaying) createdPlayer.play()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            if (isCurrent()) reportError(error)
        }
    }

    fun applyContentScale(contentScale: ContentScale) {
        requestedContentScale = contentScale
        currentPlayer()?.setFitMode(contentScale.toMoviFitMode())
    }

    fun applyProjection(projection: VideoProjectionSettings) {
        requestedProjection = projection
    }

    fun applySubtitleOffset(offset: Duration) {
        currentPlayer()?.setSubtitleDelay(-offset)
    }

    fun seekPending() {
        val activePlayer = currentPlayer() ?: return
        playerState.consumePendingSeekTime(activePlayer.duration.value)?.let { target ->
            playerState.onWebSeeking()
            scope.launch {
                runCatching { activePlayer.seek(target) }
                    .onFailure { error ->
                        playerState.onWebSeeked()
                        reportError(error)
                    }
            }
        }
    }

    fun destroy() {
        if (destroyed) return
        destroyed = true
        audioSelectionGeneration += 1
        subtitleSelectionGeneration += 1
        unbindStateCallbacks()
        onNativeVideoElement(null)
        player?.close()
        player = null
        scope.cancel()
    }

    private fun bindPlayerEvents(activePlayer: MoviPlayer) {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            activePlayer.events.collect { event ->
                if (!isCurrent()) return@collect
                when (event) {
                    is MoviPlaybackEvent.StateChanged -> handleStateChanged(event.state)
                    is MoviPlaybackEvent.TimeChanged ->
                        playerState.onTimeUpdate(
                            event.position,
                            activePlayer.duration.value,
                        )
                    is MoviPlaybackEvent.DurationChanged -> synchronizeTime(forceUpdate = true)
                    is MoviPlaybackEvent.TracksChanged -> synchronizeSnapshot()
                    is MoviPlaybackEvent.BufferChanged ->
                        playerState.updateBufferedRanges(event.ranges.toKMediaRanges())
                    is MoviPlaybackEvent.TrackChanged -> synchronizeSnapshot()
                    MoviPlaybackEvent.Seeking -> playerState.onWebSeeking()
                    MoviPlaybackEvent.Seeked -> {
                        synchronizeTime(forceUpdate = true)
                        playerState.onWebSeeked()
                    }
                    MoviPlaybackEvent.Ended -> handleEnded()
                    is MoviPlaybackEvent.Failed -> reportError(event.error)
                }
            }
        }
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            activePlayer.surface.collect {
                if (isCurrent()) synchronizeSurface()
            }
        }
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            activePlayer.diagnostics.collect {
                if (isCurrent() && activePlayer.mediaInfo.value != null) synchronizeSnapshot()
            }
        }
    }

    private fun bindStateCallbacks() {
        playerState.applyPlaybackCallback = playbackCallback
        playerState.applyVolumeCallback = volumeCallback
        playerState.applyPlaybackSpeedCallback = speedCallback
        playerState.resetPlaybackCallback = resetCallback
        playerState.applyAudioTrackSelectionCallback = audioSelectionCallback
        playerState.applySubtitleTrackCallback = subtitleCallback
        playerState.deferMoviAudioTrackConfirmation = true
        playerState.deferMoviEmbeddedSubtitleConfirmation = true
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
            playerState.deferMoviAudioTrackConfirmation = false
        }
        if (playerState.applySubtitleTrackCallback === subtitleCallback) {
            playerState.applySubtitleTrackCallback = null
            playerState.deferMoviEmbeddedSubtitleConfirmation = false
        }
        if (playerState.applyHlsQualityCallback === qualityCallback) playerState.applyHlsQualityCallback = null
        if (playerState.preciseCurrentTimeProvider === currentTimeProvider) {
            playerState.preciseCurrentTimeProvider = null
        }
        if (playerState.durationProvider === durationProvider) playerState.durationProvider = null
    }

    private fun selectAudioTrack(requestedTrack: AudioTrack?): TrackSelectionResult {
        val activePlayer = currentPlayer() ?: return TrackSelectionResult.Failed("Movi is not ready.")
        val targetId = requestedTrack?.id ?: defaultAudioTrackId
        val numericId = targetId?.removePrefix(MOVI_AUDIO_TRACK_ID_PREFIX)?.toIntOrNull()
        val selectionGeneration = ++audioSelectionGeneration
        scope.launch {
            val result =
                runCatching {
                    activePlayer.selectTrack(TrackSelectionRequest.Audio(numericId))
                }.getOrElse { error ->
                    if (isCurrent() && selectionGeneration == audioSelectionGeneration) reportError(error)
                    return@launch
                }
            if (!isCurrent() || selectionGeneration != audioSelectionGeneration) return@launch
            if (result.status.isApplied()) {
                synchronizeSnapshot(confirmedTrackKind = TrackKind.AUDIO)
            } else if (result.status != TrackSelectionStatus.SUPERSEDED) {
                synchronizeSnapshot()
                reportError(result.error ?: IllegalStateException("Movi rejected the audio track change."))
            }
        }
        return requestedTrack?.let { TrackSelectionResult.Selected(it.id) } ?: TrackSelectionResult.Auto
    }

    private fun synchronizeSnapshot(confirmedTrackKind: TrackKind? = null) {
        val activePlayer = currentPlayer() ?: return
        val info = activePlayer.mediaInfo.value ?: return
        val previousTrackId =
            when (confirmedTrackKind) {
                TrackKind.AUDIO -> playerState.currentAudioTrack?.id
                TrackKind.SUBTITLE -> playerState.currentSubtitleTrack?.id
                TrackKind.HLS_QUALITY, null -> null
            }
        val snapshot =
            info.toKMediaSnapshot(
                diagnostics = activePlayer.diagnostics.value,
                activeAudioTrackId = activePlayer.activeAudioTrack.value?.id,
                activeSubtitleTrackId = activePlayer.activeSubtitleTrack.value?.id,
                activeVideoTrackId = activePlayer.activeVideoTrack.value?.id,
            )
        defaultAudioTrackId =
            defaultAudioTrackId
                ?.takeIf { savedId -> snapshot.audioTracks.any { it.id == savedId } }
                ?: snapshot.activeAudioTrackId
                ?: snapshot.audioTracks.firstOrNull(AudioTrack::isDefault)?.id
                ?: snapshot.audioTracks.firstOrNull()?.id
        playerState.applyMoviSnapshot(snapshot)
        val confirmedTrackId =
            when (confirmedTrackKind) {
                TrackKind.AUDIO -> playerState.currentAudioTrack?.id
                TrackKind.SUBTITLE -> playerState.currentSubtitleTrack?.id
                TrackKind.HLS_QUALITY, null -> null
            }
        if (confirmedTrackKind != null && confirmedTrackId != previousTrackId) {
            playerState.emitPlaybackEvent { sessionId, sampledAtMs ->
                PlaybackEvent.TrackChanged(
                    mediaSessionId = sessionId,
                    sampledAtMs = sampledAtMs,
                    kind = confirmedTrackKind,
                    trackId = confirmedTrackId,
                )
            }
        }
        snapshot.activeVideoTrack?.let { track ->
            val ratio =
                track.width?.toFloat()?.let { width ->
                    track.height?.takeIf { it > 0 }?.let { height -> width / height.toFloat() }
                }
            onVideoRatio(ratio)
        }
    }

    private fun synchronizeSurface() {
        val activePlayer = currentPlayer() ?: return
        val nativeVideo = (activePlayer.surface.value as? PlayerSurface.NativeVideo)?.element
        onNativeVideoElement(nativeVideo)
    }

    private fun synchronizeTime(forceUpdate: Boolean) {
        val activePlayer = currentPlayer() ?: return
        playerState.onTimeUpdate(
            currentTime = activePlayer.position.value,
            duration = activePlayer.duration.value,
            forceUpdate = forceUpdate,
        )
    }

    private fun handleStateChanged(state: MoviPlayerState) {
        when (state) {
            MoviPlayerState.LOADING, MoviPlayerState.BUFFERING -> playerState.onWebWaiting()
            MoviPlayerState.SEEKING -> playerState.onWebSeeking()
            MoviPlayerState.READY, MoviPlayerState.PLAYING, MoviPlayerState.PAUSED ->
                playerState.onWebPlaybackReady()
            MoviPlayerState.IDLE,
            MoviPlayerState.ENDED,
            MoviPlayerState.ERROR,
            MoviPlayerState.CLOSED,
            -> Unit
        }
        playerState.onMoviPlaybackState(state.name.lowercase())
    }

    private fun handleEnded() {
        if (!isCurrent()) return
        val activePlayer = currentPlayer() ?: return
        if (playerState.loop) {
            scope.launch {
                runCatching {
                    activePlayer.seek(Duration.ZERO)
                    activePlayer.play()
                }.onFailure(::reportError)
            }
            playerState.sliderPos = 0f
            playerState.emitPlaybackEvent { sessionId, sampledAtMs ->
                PlaybackEvent.PlaybackRestarted(mediaSessionId = sessionId, sampledAtMs = sampledAtMs)
            }
            playerState.onRestart?.invoke()
        } else {
            playerState.onMoviPlaybackState("ended")
            playerState.emitPlaybackEvent { sessionId, sampledAtMs ->
                PlaybackEvent.PlaybackEnded(mediaSessionId = sessionId, sampledAtMs = sampledAtMs)
            }
            playerState.onPlaybackEnded?.invoke()
        }
    }

    private fun reportError(error: Throwable) {
        val moviError = error as? MoviError
        reportError(
            rawMessage = moviError?.message ?: error.message ?: "Movi player initialization failed.",
            category = moviError?.category,
        )
    }

    private fun reportError(rawMessage: String) {
        reportError(rawMessage, null)
    }

    private fun reportError(
        rawMessage: String,
        category: MoviErrorCategory?,
    ) {
        if (!isCurrent()) return
        val message =
            redactMoviError(
                message = redactSensitiveText(rawMessage),
                drmConfiguration = playerState.playbackOptions.webDrmConfiguration,
                mediaRequestHeaders = playerState.requestHeaders,
            )
        playerState.onMoviError(
            classifyMoviError(
                message = message,
                category = category,
                isDrm = playerState.playbackOptions.webDrmConfiguration != null,
            ),
        )
    }

    private fun currentPlayer(): MoviPlayer? = player?.takeIf { isCurrent() }

    private fun isCurrent(): Boolean = !destroyed && playerState.isCurrentMediaSession(mediaSessionId)
}

private fun MediaInfo.toKMediaSnapshot(
    diagnostics: RenderingDiagnostics,
    activeAudioTrackId: Int?,
    activeSubtitleTrackId: Int?,
    activeVideoTrackId: Int?,
): MoviMediaSnapshot =
    MoviMediaSnapshot(
        formatName = formatName,
        durationSeconds = duration.inWholeMilliseconds / 1_000.0,
        bitrate = bitRate,
        title = metadata["title"],
        audioTracks =
            audioTracks.mapIndexed { index, track ->
                AudioTrack(
                    id = "$MOVI_AUDIO_TRACK_ID_PREFIX${track.id}",
                    label = track.label ?: "Audio ${index + 1}",
                    language = track.language.orEmpty(),
                    channels = track.channels.takeIf { it > 0 },
                    sampleRate = track.sampleRate.takeIf { it > 0 },
                    bitrate = track.bitRate?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt(),
                    isDefault = track.isDefault,
                )
            },
        activeAudioTrackId = activeAudioTrackId?.let { "$MOVI_AUDIO_TRACK_ID_PREFIX$it" },
        subtitleTracks =
            subtitleTracks.mapIndexed { index, track ->
                SubtitleTrack(
                    id = "$MOVI_SUBTITLE_TRACK_ID_PREFIX${track.id}",
                    label = track.label ?: "Subtitle ${index + 1}",
                    language = track.language.orEmpty(),
                    src = "$MOVI_SUBTITLE_TRACK_ID_PREFIX${track.id}",
                    format = track.codec.toMoviSubtitleFormat(track.codec),
                    isEmbedded = true,
                )
            },
        activeSubtitleTrackId = activeSubtitleTrackId?.let { "$MOVI_SUBTITLE_TRACK_ID_PREFIX$it" },
        videoTracks =
            videoTracks.map { track ->
                MoviVideoTrackSnapshot(
                    id = track.id,
                    codec = track.codecString ?: track.codec,
                    width = track.width.takeIf { it > 0 },
                    height = track.height.takeIf { it > 0 },
                    frameRate = track.frameRate.takeIf { it > 0.0 }?.toFloat(),
                    bitrate = track.bitRate?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt(),
                    pixelFormat = track.pixelFormat,
                    colorPrimaries = track.colorPrimaries,
                    colorTransfer = track.colorTransfer,
                    colorMatrix = track.colorMatrix,
                    colorRange = track.colorRange,
                    isHdr = track.isHdr,
                    isActive = activeVideoTrackId == track.id,
                )
            },
        chapters =
            chapters.map { chapter ->
                MediaChapter(
                    start = chapter.start,
                    end = chapter.end.takeIf { it > chapter.start },
                    title = chapter.title,
                    language = chapter.language,
                    isHidden = chapter.isHidden,
                )
            },
        diagnostics =
            MoviRenderingDiagnosticsSnapshot(
                backend = diagnostics.backend.name.lowercase(),
                decoder = diagnostics.decoder,
                renderer = diagnostics.renderer,
                container = diagnostics.container ?: formatName,
            ),
    )

private fun List<MoviBufferedRange>.toKMediaRanges(): List<BufferedRange> =
    map { range -> BufferedRange(start = range.start, end = range.end) }

private fun ContentScale.toMoviFitMode(): FitMode =
    when (this) {
        ContentScale.Crop -> FitMode.COVER
        ContentScale.FillBounds -> FitMode.FILL
        else -> FitMode.CONTAIN
    }

private fun TrackSelectionStatus.isApplied(): Boolean =
    this == TrackSelectionStatus.SELECTED ||
        this == TrackSelectionStatus.UNCHANGED ||
        this == TrackSelectionStatus.AUTO ||
        this == TrackSelectionStatus.DISABLED

private fun PlatformFile.browserFileOrNull(): File? =
    when (val source = webFile) {
        is WebFile.FileWrapper -> browserFileAsDomFile(source.file)
        is WebFile.DirectoryWrapper -> null
    }

@Suppress("UNUSED_PARAMETER")
private fun browserFileAsDomFile(file: BrowserFile): File = js("file")

private fun String.isInlineBrowserMediaUri(): Boolean =
    startsWith("blob:", ignoreCase = true) || startsWith("data:", ignoreCase = true)

@Suppress("CyclomaticComplexMethod")
internal fun parseMoviSnapshotRows(rows: String): MoviMediaSnapshot {
    var formatName: String? = null
    var durationSeconds: Double? = null
    var bitrate: Long? = null
    var title: String? = null
    var diagnostics: MoviRenderingDiagnosticsSnapshot? = null
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
                val isActive = columns.getOrNull(8).orEmpty().toMoviBoolean()
                audioTracks +=
                    AudioTrack(
                        id = id,
                        label = columns.getOrNull(2).orEmpty().ifBlank { "Audio ${audioTracks.size + 1}" },
                        language = columns.getOrNull(3).orEmpty(),
                        channels = columns.getOrNull(4)?.toIntOrNull()?.takeIf { it > 0 },
                        sampleRate = columns.getOrNull(5)?.toIntOrNull()?.takeIf { it > 0 },
                        bitrate = columns.getOrNull(6)?.toIntOrNull()?.takeIf { it > 0 },
                        isDefault = columns.getOrNull(7).orEmpty().toMoviBoolean(),
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
                        format = columns.getOrNull(5).orEmpty().toMoviSubtitleFormat(columns.getOrNull(5)),
                        isEmbedded = true,
                    )
                if (columns.getOrNull(4).orEmpty().toMoviBoolean()) activeSubtitleTrackId = id
            }
            "V" ->
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
                        isHdr = columns.getOrNull(12).orEmpty().toMoviBoolean(),
                        isActive = columns.getOrNull(13).orEmpty().toMoviBoolean(),
                    )
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
                        language = columns.getOrNull(4).orEmpty().ifBlank { null },
                        isHidden = columns.getOrNull(5).orEmpty().toMoviBoolean(),
                    )
            }
            "D" ->
                diagnostics =
                    MoviRenderingDiagnosticsSnapshot(
                        backend = columns.getOrNull(1).orEmpty().ifBlank { null },
                        decoder = columns.getOrNull(2).orEmpty().ifBlank { null },
                        renderer = columns.getOrNull(3).orEmpty().ifBlank { null },
                        container = columns.getOrNull(4).orEmpty().ifBlank { null },
                    )
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
        diagnostics = diagnostics,
    )
}

private fun String.toMoviSubtitleFormat(codec: String?): SubtitleFormat =
    when (lowercase()) {
        "ass", "s_text/ass" -> SubtitleFormat.ASS
        "ssa", "s_text/ssa" -> SubtitleFormat.SSA
        "srt", "text", "subrip" -> SubtitleFormat.SRT
        "webvtt", "vtt" -> SubtitleFormat.WEBVTT
        else ->
            when (codec.orEmpty().lowercase()) {
                "ass", "s_text/ass" -> SubtitleFormat.ASS
                "ssa", "s_text/ssa" -> SubtitleFormat.SSA
                "subrip", "srt" -> SubtitleFormat.SRT
                "webvtt", "vtt" -> SubtitleFormat.WEBVTT
                else -> SubtitleFormat.AUTO
            }
    }

private fun String.toMoviBoolean(): Boolean = this == "1" || equals("true", ignoreCase = true)

@Suppress("CyclomaticComplexMethod")
internal fun MoviVideoTrackSnapshot.toVideoColorInfo(): VideoColorInfo {
    val normalizedTransfer = colorTransfer.orEmpty().lowercase()
    val transfer =
        when {
            normalizedTransfer.contains("2084") || normalizedTransfer == "pq" -> VideoColorTransfer.PQ
            normalizedTransfer.contains("hlg") || normalizedTransfer.contains("arib") -> VideoColorTransfer.HLG
            normalizedTransfer.contains("srgb") -> VideoColorTransfer.SRGB
            normalizedTransfer.isNotBlank() -> VideoColorTransfer.SDR
            else -> VideoColorTransfer.UNKNOWN
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
                colorRange.equals("full", ignoreCase = true) || colorRange == "2" -> VideoColorRange.FULL
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
    category: MoviErrorCategory?,
    isDrm: Boolean,
): VideoPlayerError {
    if (isDrm || category == MoviErrorCategory.DRM) return VideoPlayerError.DrmError(message)
    return when (category) {
        MoviErrorCategory.NETWORK -> VideoPlayerError.NetworkError(message)
        MoviErrorCategory.FORMAT, MoviErrorCategory.SOURCE -> VideoPlayerError.SourceError(message)
        MoviErrorCategory.DECODER, MoviErrorCategory.RENDERER -> VideoPlayerError.CodecError(message)
        MoviErrorCategory.DRM -> VideoPlayerError.DrmError(message)
        MoviErrorCategory.STATE, MoviErrorCategory.INTERNAL, null -> {
            val normalized = message.lowercase()
            when {
                "codec" in normalized || "decode" in normalized || "decoder" in normalized ->
                    VideoPlayerError.CodecError(message)
                "network" in normalized || "fetch" in normalized || "http" in normalized || "range" in normalized ->
                    VideoPlayerError.NetworkError(message)
                "source" in normalized || "demux" in normalized || "format" in normalized ->
                    VideoPlayerError.SourceError(message)
                else -> VideoPlayerError.UnknownError(message)
            }
        }
    }
}

internal fun redactMoviError(
    message: String,
    drmConfiguration: WebDrmConfiguration?,
    mediaRequestHeaders: Map<String, String> = emptyMap(),
): String {
    if (drmConfiguration != null) {
        return "DRM playback failed in Movi/Shaka; license details were redacted."
    }
    if (mediaRequestHeaders.isNotEmpty()) {
        return "Movi playback failed; media request details were redacted."
    }
    return redactSensitiveText(message)
}

@Suppress("UNUSED_PARAMETER")
private fun decodeMoviColumn(value: String): String = js("decodeURIComponent(value)")
