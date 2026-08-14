@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
@file:Suppress("MagicNumber", "TooManyFunctions")

package io.github.kdroidfilter.composemediaplayer

import androidx.compose.ui.layout.ContentScale
import io.github.shusek.kmedia.engine.wasm.BrowserMediaSourceAdapter
import io.github.shusek.kmedia.engine.wasm.DecoderPreference
import io.github.shusek.kmedia.engine.wasm.EmbeddedSubtitleRenderer
import io.github.shusek.kmedia.engine.wasm.FitMode
import io.github.shusek.kmedia.engine.wasm.MediaInfo
import io.github.shusek.kmedia.engine.wasm.MediaSource
import io.github.shusek.kmedia.engine.wasm.OutputDynamicRangePolicy
import io.github.shusek.kmedia.engine.wasm.PlayerSurface
import io.github.shusek.kmedia.engine.wasm.ProjectionConfiguration
import io.github.shusek.kmedia.engine.wasm.ProjectionEyeOrder
import io.github.shusek.kmedia.engine.wasm.ProjectionMode
import io.github.shusek.kmedia.engine.wasm.ProjectionStereoLayout
import io.github.shusek.kmedia.engine.wasm.RenderingDiagnostics
import io.github.shusek.kmedia.engine.wasm.ToneMappingMode
import io.github.shusek.kmedia.engine.wasm.TrackSelectionRequest
import io.github.shusek.kmedia.engine.wasm.TrackSelectionStatus
import io.github.shusek.kmedia.engine.wasm.WasmMediaError
import io.github.shusek.kmedia.engine.wasm.WasmMediaErrorCategory
import io.github.shusek.kmedia.engine.wasm.WasmMediaPlayer
import io.github.shusek.kmedia.engine.wasm.WasmMediaPlayerConfig
import io.github.shusek.kmedia.engine.wasm.WasmMediaPlayerState
import io.github.shusek.kmedia.engine.wasm.WasmRuntimeConfig
import io.github.shusek.kmedia.engine.wasm.redactSensitiveText
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
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLVideoElement
import org.w3c.files.File
import kotlin.js.js
import kotlin.time.Duration
import io.github.shusek.kmedia.engine.wasm.BufferedRange as WasmEngineBufferedRange
import io.github.shusek.kmedia.engine.wasm.PlaybackEvent as WasmEnginePlaybackEvent
import io.github.shusek.kmedia.engine.wasm.SubtitlePacket as WasmEngineSubtitlePacket
import io.github.shusek.kmedia.engine.wasm.SubtitleRendererConfiguration as WasmEngineSubtitleRendererConfiguration

internal const val WASM_ENGINE_AUDIO_TRACK_ID_PREFIX = "wasmEngine:audio:"
internal const val WASM_ENGINE_SUBTITLE_TRACK_ID_PREFIX = "wasmEngine:subtitle:"
internal const val WASM_ENGINE_VIDEO_TRACK_ID_PREFIX = "wasmEngine:video:"

internal data class WasmEngineVideoTrackSnapshot(
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

internal data class WasmEngineMediaSnapshot(
    val formatName: String?,
    val durationSeconds: Double?,
    val bitrate: Long?,
    val title: String?,
    val audioTracks: List<AudioTrack>,
    val activeAudioTrackId: String?,
    val subtitleTracks: List<SubtitleTrack>,
    val activeSubtitleTrackId: String?,
    val videoTracks: List<WasmEngineVideoTrackSnapshot>,
    val chapters: List<MediaChapter>,
    val adaptiveQualityAutoMode: Boolean = true,
    val diagnostics: WasmEngineRenderingDiagnosticsSnapshot? = null,
) {
    val activeVideoTrack: WasmEngineVideoTrackSnapshot?
        get() = videoTracks.firstOrNull(WasmEngineVideoTrackSnapshot::isActive) ?: videoTracks.firstOrNull()
}

internal data class WasmEngineRenderingDiagnosticsSnapshot(
    val backend: String?,
    val decoder: String?,
    val renderer: String?,
    val container: String?,
    val droppedFrames: Long = 0,
    val presentedFrames: Long = 0,
    val demuxedPackets: Long = 0,
    val demuxSeekCount: Long = 0,
    val submittedVideoPackets: Long = 0,
    val decodedVideoFrames: Long = 0,
    val endOfInput: Boolean = false,
    val lastPacketTimestampMs: Long? = null,
    val lastPacketStreamIndex: Int? = null,
    val lastPacketBytes: Int = 0,
    val audioBufferAheadMs: Float = 0f,
    val audioUnderruns: Long = 0,
    val maximumAvDriftMs: Float = 0f,
    val audioTimestampCorrections: Long = 0,
    val sourceDynamicRange: VideoDynamicRange = VideoDynamicRange.UNKNOWN,
    val outputDynamicRange: VideoDynamicRange = VideoDynamicRange.UNKNOWN,
    val outputVerification: ColorPipelineVerification = ColorPipelineVerification.NONE,
)

/**
 * Direct Kotlin/Wasm adapter around kmedia-wasm-engine.
 *
 * No JavaScript module loading or untyped player calls live here: media models,
 * state and events cross the boundary as Kotlin types.
 */
internal class WasmEnginePlaybackSession(
    private val playerState: DefaultVideoPlayerState,
    private val mediaSessionId: Long,
    private val onSurface: (PlayerSurface?) -> Unit,
    private val onVideoRatio: (Float?) -> Unit,
) : WebMediaAdvancedControls {
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var player: WasmMediaPlayer? = null
    private var destroyed: Boolean = false
    private var defaultAudioTrackId: String? = null
    private var audioSelectionGeneration: Int = 0
    private var subtitleSelectionGeneration: Int = 0
    private var qualitySelectionGeneration: Int = 0
    private var adaptiveQualityAutoMode: Boolean = true
    private var requestedContentScale: ContentScale = ContentScale.Fit
    private var requestedProjection: VideoProjectionSettings = playerState.projection
    private var requestedProjectionView: VideoProjectionViewSettings = playerState.projectionView
    private var requestedTextureCrop: VideoTextureCrop = playerState.projectionTextureCrop

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
            val requestedId = requestedTrack?.id?.removePrefix(WASM_ENGINE_SUBTITLE_TRACK_ID_PREFIX)?.toIntOrNull()
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
                    reportError(result.error ?: IllegalStateException("WasmEngine rejected the subtitle track change."))
                }
            }
        }
    }
    private val qualityCallback: (String?) -> Unit = { variantId ->
        val numericId = variantId?.removePrefix(WASM_ENGINE_VIDEO_TRACK_ID_PREFIX)?.toIntOrNull()
        currentPlayer()?.let { activePlayer ->
            val selectionGeneration = ++qualitySelectionGeneration
            scope.launch {
                val result =
                    runCatching {
                        activePlayer.selectTrack(TrackSelectionRequest.Video(numericId))
                    }.getOrElse { error ->
                        if (isCurrent() && selectionGeneration == qualitySelectionGeneration) reportError(error)
                        return@launch
                    }
                if (!isCurrent() || selectionGeneration != qualitySelectionGeneration) return@launch
                if (result.status.isApplied()) {
                    adaptiveQualityAutoMode = result.status == TrackSelectionStatus.AUTO
                    synchronizeSnapshot(confirmedTrackKind = TrackKind.HLS_QUALITY)
                } else if (result.status != TrackSelectionStatus.SUPERSEDED) {
                    synchronizeSnapshot()
                    reportError(result.error ?: IllegalStateException("WasmEngine rejected the quality change."))
                }
            }
        }
    }
    private val currentTimeProvider: () -> Duration = {
        currentPlayer()?.position?.value ?: Duration.ZERO
    }
    private val durationProvider: () -> Duration = {
        currentPlayer()?.duration?.value ?: Duration.ZERO
    }

    override val subtitleCues: List<WebSubtitleCue>
        get() =
            currentPlayer()
                ?.subtitleCues
                ?.value
                .orEmpty()
                .map { it.toKMediaCue() }

    override val coverArt: org.w3c.dom.ImageBitmap?
        get() = currentPlayer()?.coverArt?.value

    override val liveWindow: WebLivePlaybackWindow?
        get() = currentPlayer()?.liveWindow?.value?.toKMediaLiveWindow()

    override val surface: WebMediaSurface?
        get() = currentPlayer()?.surface?.value?.toKMediaSurface()

    override val renderingDiagnostics: PlaybackDiagnostics?
        get() = currentPlayer()?.diagnostics?.value?.toKMediaPlaybackDiagnostics()

    @Suppress("TooGenericExceptionCaught")
    suspend fun load(
        sourceUri: String,
        sourceMimeType: String? = null,
        sourceFile: PlatformFile?,
        mediaHeaders: Map<String, String>,
        drmConfiguration: WebDrmConfiguration?,
    ) {
        if (destroyed || !isCurrent()) return
        if (drmConfiguration != null && (sourceFile != null || sourceUri.isInlineBrowserMediaUri())) {
            playerState.onWasmMediaError(
                VideoPlayerError.DrmError("DRM playback requires an adaptive URL source on WebAssembly."),
            )
            return
        }

        try {
            val createdPlayer =
                WasmMediaPlayer(
                    WasmMediaPlayerConfig(
                        decoderPreference =
                            playerState.playbackOptions.webDecoderPreference
                                .toWasmEngineDecoderPreference(),
                        outputDynamicRangePolicy =
                            playerState.playbackOptions.dynamicRangePolicy
                                .toWasmEngineOutputDynamicRangePolicy(),
                        runtime =
                            WasmRuntimeConfig(
                                assetBaseUrl = WebMediaDependencyConfig.kmediaWasmRuntimeAssetBaseUrl,
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
                        extension.createWasmMediaEmbeddedSubtitleRenderer(::reportError)
                    }.onFailure(::reportError).getOrNull()
                }.firstOrNull()
                ?.let { renderer ->
                    createdPlayer.setEmbeddedSubtitleRenderer(
                        KMediaEmbeddedSubtitleRendererAdapter(renderer),
                    )
                }
            createdPlayer.setSubtitleDelay(-playerState.subtitleOffset)
            createdPlayer.setVolume(playerState.volume)
            createdPlayer.setPlaybackRate(playerState.playbackSpeed)
            createdPlayer.setFitMode(requestedContentScale.toWasmEngineFitMode())
            createdPlayer.setRotation(requestedProjection.toWasmEngineRotationDegrees())
            createdPlayer.setProjection(
                requestedProjection.toWasmEngineProjection(requestedTextureCrop, requestedProjectionView),
            )
            createdPlayer.setToneMapping(playerState.playbackOptions.dynamicRangePolicy.toWasmEngineToneMapping())

            val preparedSource = playerState.takePreparedPipelineSourceForEngine()
            createdPlayer.load(
                source =
                    when {
                        preparedSource != null ->
                            MediaSource.BrowserMedia(
                                KMediaBrowserSourceAdapter(
                                    source = preparedSource,
                                    fallbackMimeType = sourceMimeType,
                                ),
                            )
                        drmConfiguration != null ->
                            MediaSource.Drm(
                                mediaUrl = sourceUri,
                                licenseUrl = drmConfiguration.licenseUrl,
                                mediaHeaders = mediaHeaders,
                                licenseHeaders = drmConfiguration.licenseRequestHeaders,
                                licenseServers = drmConfiguration.licenseServers,
                            )
                        sourceFile?.browserFileOrNull() != null ->
                            MediaSource.BrowserFile(requireNotNull(sourceFile.browserFileOrNull()))
                        else -> MediaSource.Url(sourceUri, mediaHeaders, sourceMimeType)
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
        } catch (error: Exception) {
            if (isCurrent()) reportError(error)
        }
    }

    fun applyContentScale(contentScale: ContentScale) {
        requestedContentScale = contentScale
        currentPlayer()?.setFitMode(contentScale.toWasmEngineFitMode())
    }

    fun applyProjection(
        projection: VideoProjectionSettings,
        projectionView: VideoProjectionViewSettings,
        textureCrop: VideoTextureCrop,
    ) {
        requestedProjection = projection
        requestedProjectionView = projectionView.normalized()
        requestedTextureCrop = textureCrop.normalized()
        currentPlayer()?.let { activePlayer ->
            activePlayer.setRotation(projection.toWasmEngineRotationDegrees())
            activePlayer.setProjection(
                projection.toWasmEngineProjection(requestedTextureCrop, requestedProjectionView),
            )
        }
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
        if (playerState.webAdvancedControls === this) playerState.webAdvancedControls = null
        onSurface(null)
        player?.close()
        player = null
        scope.cancel()
    }

    private fun bindPlayerEvents(activePlayer: WasmMediaPlayer) {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            activePlayer.events.collect { event ->
                if (!isCurrent()) return@collect
                when (event) {
                    is WasmEnginePlaybackEvent.StateChanged -> handleStateChanged(event.state)
                    is WasmEnginePlaybackEvent.TimeChanged ->
                        playerState.onTimeUpdate(
                            event.position,
                            activePlayer.duration.value,
                        )
                    is WasmEnginePlaybackEvent.DurationChanged -> synchronizeTime(forceUpdate = true)
                    is WasmEnginePlaybackEvent.TracksChanged -> synchronizeSnapshot()
                    is WasmEnginePlaybackEvent.BufferChanged ->
                        playerState.updateBufferedRanges(event.ranges.toKMediaRanges())
                    is WasmEnginePlaybackEvent.TrackChanged -> synchronizeSnapshot()
                    WasmEnginePlaybackEvent.Seeking -> playerState.onWebSeeking()
                    WasmEnginePlaybackEvent.Seeked -> {
                        synchronizeTime(forceUpdate = true)
                        playerState.onWebSeeked()
                    }
                    WasmEnginePlaybackEvent.Ended -> handleEnded()
                    is WasmEnginePlaybackEvent.Failed -> reportError(event.error)
                    is WasmEnginePlaybackEvent.FileAccessRevoked ->
                        reportError("Browser file access was revoked; select the source again.")
                    is WasmEnginePlaybackEvent.DiagnosticsChanged,
                    is WasmEnginePlaybackEvent.SourceModeChanged,
                    is WasmEnginePlaybackEvent.LiveWindowChanged,
                    is WasmEnginePlaybackEvent.CoverArtChanged,
                    -> Unit
                }
            }
        }
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            activePlayer.surface.collect {
                if (isCurrent()) synchronizeSurface()
            }
        }
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            activePlayer.position.collect { position ->
                if (isCurrent()) {
                    playerState.onTimeUpdate(
                        currentTime = position,
                        duration = activePlayer.duration.value,
                    )
                }
            }
        }
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            activePlayer.diagnostics.collect {
                if (isCurrent() && activePlayer.mediaInfo.value != null) synchronizeSnapshot()
            }
        }
    }

    private fun bindStateCallbacks() {
        playerState.webAdvancedControls = this
        playerState.applyPlaybackCallback = playbackCallback
        playerState.applyVolumeCallback = volumeCallback
        playerState.applyPlaybackSpeedCallback = speedCallback
        playerState.resetPlaybackCallback = resetCallback
        playerState.applyAudioTrackSelectionCallback = audioSelectionCallback
        playerState.applySubtitleTrackCallback = subtitleCallback
        playerState.deferWasmEngineAudioTrackConfirmation = true
        playerState.deferWasmEngineEmbeddedSubtitleConfirmation = true
        playerState.applyHlsQualityCallback = qualityCallback
        playerState.preciseCurrentTimeProvider = currentTimeProvider
        playerState.durationProvider = durationProvider
    }

    override fun setStableVolume(enabled: Boolean) {
        currentPlayer()?.setStableVolume(enabled)
    }

    override suspend fun setAudioOnly(enabled: Boolean) {
        requireCurrentPlayer().setAudioOnly(enabled)
    }

    override suspend fun listAudioOutputs(): List<WebAudioOutputDevice> =
        requireCurrentPlayer().listAudioOutputs().map { device ->
            WebAudioOutputDevice(device.id, device.label, device.isDefault)
        }

    override suspend fun setAudioOutput(deviceId: String?) {
        requireCurrentPlayer().setAudioOutput(deviceId)
    }

    override suspend fun snapshot(): WebMediaSnapshot = requireCurrentPlayer().snapshot().toKMediaSnapshot()

    override suspend fun thumbnail(position: Duration): WebMediaSnapshot =
        requireCurrentPlayer().thumbnail(position).toKMediaSnapshot()

    override suspend fun thumbnails(
        positions: List<Duration>,
        maximumWidth: Int,
    ): List<WebMediaSnapshot?> =
        requireCurrentPlayer().thumbnails(positions, maximumWidth).map { it?.toKMediaSnapshot() }

    override suspend fun prefetchSubtitleCues(): List<WebSubtitleCue> =
        requireCurrentPlayer().prefetchSubtitleCues().map { it.toKMediaCue() }

    override suspend fun seekToLive() {
        requireCurrentPlayer().seekToLive()
    }

    private fun unbindStateCallbacks() {
        if (playerState.applyPlaybackCallback === playbackCallback) playerState.applyPlaybackCallback = null
        if (playerState.applyVolumeCallback === volumeCallback) playerState.applyVolumeCallback = null
        if (playerState.applyPlaybackSpeedCallback === speedCallback) playerState.applyPlaybackSpeedCallback = null
        if (playerState.resetPlaybackCallback === resetCallback) playerState.resetPlaybackCallback = null
        if (playerState.applyAudioTrackSelectionCallback === audioSelectionCallback) {
            playerState.applyAudioTrackSelectionCallback = null
            playerState.deferWasmEngineAudioTrackConfirmation = false
        }
        if (playerState.applySubtitleTrackCallback === subtitleCallback) {
            playerState.applySubtitleTrackCallback = null
            playerState.deferWasmEngineEmbeddedSubtitleConfirmation = false
        }
        if (playerState.applyHlsQualityCallback === qualityCallback) playerState.applyHlsQualityCallback = null
        if (playerState.preciseCurrentTimeProvider === currentTimeProvider) {
            playerState.preciseCurrentTimeProvider = null
        }
        if (playerState.durationProvider === durationProvider) playerState.durationProvider = null
    }

    private fun selectAudioTrack(requestedTrack: AudioTrack?): TrackSelectionResult {
        val activePlayer = currentPlayer() ?: return TrackSelectionResult.Failed("WasmEngine is not ready.")
        val targetId = requestedTrack?.id ?: defaultAudioTrackId
        val numericId = targetId?.removePrefix(WASM_ENGINE_AUDIO_TRACK_ID_PREFIX)?.toIntOrNull()
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
                reportError(result.error ?: IllegalStateException("WasmEngine rejected the audio track change."))
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
                TrackKind.HLS_QUALITY -> playerState.currentHlsQuality?.id
                null -> null
            }
        val snapshot =
            info.toKMediaSnapshot(
                diagnostics = activePlayer.diagnostics.value,
                activeAudioTrackId = activePlayer.activeAudioTrack.value?.id,
                activeSubtitleTrackId = activePlayer.activeSubtitleTrack.value?.id,
                activeVideoTrackId = activePlayer.activeVideoTrack.value?.id,
                adaptiveQualityAutoMode = adaptiveQualityAutoMode,
            )
        defaultAudioTrackId =
            defaultAudioTrackId
                ?.takeIf { savedId -> snapshot.audioTracks.any { it.id == savedId } }
                ?: snapshot.activeAudioTrackId
                ?: snapshot.audioTracks.firstOrNull(AudioTrack::isDefault)?.id
                ?: snapshot.audioTracks.firstOrNull()?.id
        playerState.applyWasmEngineSnapshot(snapshot)
        val confirmedTrackId =
            when (confirmedTrackKind) {
                TrackKind.AUDIO -> playerState.currentAudioTrack?.id
                TrackKind.SUBTITLE -> playerState.currentSubtitleTrack?.id
                TrackKind.HLS_QUALITY -> playerState.currentHlsQuality?.id
                null -> null
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
        onSurface(activePlayer.surface.value)
    }

    private fun synchronizeTime(forceUpdate: Boolean) {
        val activePlayer = currentPlayer() ?: return
        playerState.onTimeUpdate(
            currentTime = activePlayer.position.value,
            duration = activePlayer.duration.value,
            forceUpdate = forceUpdate,
        )
    }

    private fun handleStateChanged(state: WasmMediaPlayerState) {
        when (state) {
            WasmMediaPlayerState.LOADING, WasmMediaPlayerState.BUFFERING -> playerState.onWebWaiting()
            WasmMediaPlayerState.SEEKING -> playerState.onWebSeeking()
            WasmMediaPlayerState.READY, WasmMediaPlayerState.PLAYING, WasmMediaPlayerState.PAUSED ->
                playerState.onWebPlaybackReady()
            WasmMediaPlayerState.IDLE,
            WasmMediaPlayerState.ENDED,
            WasmMediaPlayerState.ERROR,
            WasmMediaPlayerState.CLOSED,
            -> Unit
        }
        playerState.onWasmEnginePlaybackState(state.name.lowercase())
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
            playerState.onWasmEnginePlaybackState("ended")
            playerState.emitPlaybackEvent { sessionId, sampledAtMs ->
                PlaybackEvent.PlaybackEnded(mediaSessionId = sessionId, sampledAtMs = sampledAtMs)
            }
            playerState.onPlaybackEnded?.invoke()
        }
    }

    private fun reportError(error: Throwable) {
        val wasmEngineError = error as? WasmMediaError
        reportError(
            rawMessage = wasmEngineError?.message ?: error.message ?: "WasmEngine player initialization failed.",
            category = wasmEngineError?.category,
        )
    }

    private fun reportError(rawMessage: String) {
        reportError(rawMessage, null)
    }

    private fun reportError(
        rawMessage: String,
        category: WasmMediaErrorCategory?,
    ) {
        if (!isCurrent()) return
        val message =
            redactWasmMediaError(
                message = redactSensitiveText(rawMessage),
                drmConfiguration = playerState.playbackOptions.webDrmConfiguration,
                mediaRequestHeaders = playerState.requestHeaders,
            )
        playerState.onWasmMediaError(
            classifyWasmMediaError(
                message = message,
                category = category,
                isDrm = playerState.playbackOptions.webDrmConfiguration != null,
            ),
        )
    }

    private fun currentPlayer(): WasmMediaPlayer? = player?.takeIf { isCurrent() }

    private fun requireCurrentPlayer(): WasmMediaPlayer =
        currentPlayer() ?: throw IllegalStateException("WasmEngine is not ready.")

    private fun isCurrent(): Boolean = !destroyed && playerState.isCurrentMediaSession(mediaSessionId)
}

private class KMediaEmbeddedSubtitleRendererAdapter(
    private val delegate: WebEmbeddedSubtitleRenderer,
) : EmbeddedSubtitleRenderer {
    override suspend fun configure(
        configuration: WasmEngineSubtitleRendererConfiguration,
        overlay: HTMLElement,
    ) {
        delegate.configure(
            configuration =
                WebSubtitleRendererConfiguration(
                    codec = configuration.codec,
                    codecPrivate = configuration.codecPrivate,
                    width = configuration.width,
                    height = configuration.height,
                    attachments =
                        configuration.attachments.map { attachment ->
                            WebSubtitleFontAttachment(
                                name = attachment.name,
                                mimeType = attachment.mimeType,
                                data = attachment.data,
                            )
                        },
                ),
            overlay = overlay,
        )
    }

    override suspend fun pushPacket(packet: WasmEngineSubtitlePacket) {
        delegate.pushPacket(
            WebSubtitlePacket(
                data = packet.data,
                presentationTime = packet.presentationTime,
                duration = packet.duration,
            ),
        )
    }

    override fun render(position: Duration) = delegate.render(position)

    override fun setDelay(delay: Duration) = delegate.setDelay(delay)

    override fun clear() = delegate.clear()

    override suspend fun close() = delegate.close()
}

private fun MediaInfo.toKMediaSnapshot(
    diagnostics: RenderingDiagnostics,
    activeAudioTrackId: Int?,
    activeSubtitleTrackId: Int?,
    activeVideoTrackId: Int?,
    adaptiveQualityAutoMode: Boolean,
): WasmEngineMediaSnapshot =
    WasmEngineMediaSnapshot(
        formatName = formatName,
        durationSeconds = duration.inWholeMilliseconds / 1_000.0,
        bitrate = bitRate,
        title = metadata["title"],
        audioTracks =
            audioTracks.mapIndexed { index, track ->
                AudioTrack(
                    id = "$WASM_ENGINE_AUDIO_TRACK_ID_PREFIX${track.id}",
                    label = track.label ?: "Audio ${index + 1}",
                    language = track.language.orEmpty(),
                    channels = track.channels.takeIf { it > 0 },
                    sampleRate = track.sampleRate.takeIf { it > 0 },
                    bitrate =
                        track.bitRate
                            ?.takeIf { it > 0L }
                            ?.coerceAtMost(Int.MAX_VALUE.toLong())
                            ?.toInt(),
                    isDefault = track.isDefault,
                    codec = track.codec,
                )
            },
        activeAudioTrackId = activeAudioTrackId?.let { "$WASM_ENGINE_AUDIO_TRACK_ID_PREFIX$it" },
        subtitleTracks =
            subtitleTracks.mapIndexed { index, track ->
                SubtitleTrack(
                    id = "$WASM_ENGINE_SUBTITLE_TRACK_ID_PREFIX${track.id}",
                    label = track.label ?: "Subtitle ${index + 1}",
                    language = track.language.orEmpty(),
                    src = "$WASM_ENGINE_SUBTITLE_TRACK_ID_PREFIX${track.id}",
                    format = track.codec.toWasmEngineSubtitleFormat(track.codec),
                    isEmbedded = true,
                )
            },
        activeSubtitleTrackId = activeSubtitleTrackId?.let { "$WASM_ENGINE_SUBTITLE_TRACK_ID_PREFIX$it" },
        videoTracks =
            videoTracks.map { track ->
                WasmEngineVideoTrackSnapshot(
                    id = track.id,
                    codec = track.codecString ?: track.codec,
                    width = track.width.takeIf { it > 0 },
                    height = track.height.takeIf { it > 0 },
                    frameRate = track.frameRate.takeIf { it > 0.0 }?.toFloat(),
                    bitrate =
                        track.bitRate
                            ?.takeIf { it > 0L }
                            ?.coerceAtMost(Int.MAX_VALUE.toLong())
                            ?.toInt(),
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
        adaptiveQualityAutoMode = adaptiveQualityAutoMode,
        diagnostics =
            WasmEngineRenderingDiagnosticsSnapshot(
                backend = diagnostics.backend.name.lowercase(),
                decoder = diagnostics.decoder,
                renderer = diagnostics.renderer,
                container = diagnostics.container ?: formatName,
                droppedFrames = diagnostics.droppedFrames,
                presentedFrames = diagnostics.presentedFrames,
                demuxedPackets = diagnostics.demuxedPackets,
                demuxSeekCount = diagnostics.demuxSeekCount,
                submittedVideoPackets = diagnostics.submittedVideoPackets,
                decodedVideoFrames = diagnostics.decodedVideoFrames,
                endOfInput = diagnostics.endOfInput,
                lastPacketTimestampMs = diagnostics.lastPacketTimestamp?.inWholeMilliseconds,
                lastPacketStreamIndex = diagnostics.lastPacketStreamIndex,
                lastPacketBytes = diagnostics.lastPacketBytes,
                audioBufferAheadMs = diagnostics.audioBufferAhead.inWholeMicroseconds / 1_000f,
                audioUnderruns = diagnostics.audioUnderruns,
                maximumAvDriftMs = diagnostics.maximumAvDrift.inWholeMicroseconds / 1_000f,
                audioTimestampCorrections = diagnostics.audioTimestampCorrections,
                sourceDynamicRange = diagnostics.colorPipeline.sourceDynamicRange.toKMediaDynamicRange(),
                outputDynamicRange = diagnostics.colorPipeline.outputDynamicRange.toKMediaDynamicRange(),
                outputVerification = diagnostics.colorPipeline.outputVerification.toKMediaVerification(),
            ),
    )

private fun PlayerSurface.toKMediaSurface(): WebMediaSurface =
    when (this) {
        is PlayerSurface.Canvas -> WebMediaSurface.Canvas(element, mediaElement)
        is PlayerSurface.NativeVideo -> WebMediaSurface.NativeVideo(element)
    }

private fun io.github.shusek.kmedia.engine.wasm.VideoSnapshot.toKMediaSnapshot(): WebMediaSnapshot =
    WebMediaSnapshot(
        image = image,
        timestamp = timestamp,
        width = width,
        height = height,
    )

private fun io.github.shusek.kmedia.engine.wasm.LivePlaybackWindow.toKMediaLiveWindow(): WebLivePlaybackWindow =
    WebLivePlaybackWindow(start = start, end = end, liveEdge = liveEdge)

private fun io.github.shusek.kmedia.engine.wasm.SubtitleCue.toKMediaCue(): WebSubtitleCue =
    WebSubtitleCue(
        start = start,
        end = end,
        text = text,
        image = image,
        x = x,
        y = y,
    )

private fun RenderingDiagnostics.toKMediaPlaybackDiagnostics(): PlaybackDiagnostics =
    PlaybackDiagnostics(
        totalVideoFrames = (presentedFrames + droppedFrames).takeIf { it > 0L },
        renderedVideoFrames = presentedFrames.takeIf { it > 0L },
        droppedVideoFrames = droppedFrames.takeIf { it > 0L },
        maximumAvSyncOffsetMs = maximumAvDrift.inWholeMicroseconds / 1_000f,
        audioBufferAheadMs = audioBufferAhead.inWholeMicroseconds / 1_000f,
        audioUnderruns = audioUnderruns,
        audioTimestampCorrections = audioTimestampCorrections,
        bufferedRanges = emptyList(),
        notes =
            buildString {
                append("backend=")
                append(backend.name.lowercase())
                append(", video=")
                append(videoDecoderBackend.name.lowercase())
                append(", audio=")
                append(audioDecoderBackend.name.lowercase())
                renderer?.let {
                    append(", renderer=")
                    append(it)
                }
            },
    )

private fun io.github.shusek.kmedia.engine.wasm.DynamicRange.toKMediaDynamicRange(): VideoDynamicRange =
    when (this) {
        io.github.shusek.kmedia.engine.wasm.DynamicRange.SDR -> VideoDynamicRange.SDR
        io.github.shusek.kmedia.engine.wasm.DynamicRange.HDR10 -> VideoDynamicRange.HDR10
        io.github.shusek.kmedia.engine.wasm.DynamicRange.HLG -> VideoDynamicRange.HLG
        io.github.shusek.kmedia.engine.wasm.DynamicRange.DOLBY_VISION -> VideoDynamicRange.DOLBY_VISION
        io.github.shusek.kmedia.engine.wasm.DynamicRange.UNKNOWN -> VideoDynamicRange.UNKNOWN
    }

private fun io.github.shusek.kmedia.engine.wasm.OutputVerification.toKMediaVerification(): ColorPipelineVerification =
    when (this) {
        io.github.shusek.kmedia.engine.wasm.OutputVerification.CONFIRMED ->
            ColorPipelineVerification.RENDERER_CONFIGURED
        io.github.shusek.kmedia.engine.wasm.OutputVerification.INFERRED -> ColorPipelineVerification.INFERRED
        io.github.shusek.kmedia.engine.wasm.OutputVerification.UNKNOWN -> ColorPipelineVerification.NONE
    }

internal fun List<WasmEngineBufferedRange>.toKMediaRanges(): List<BufferedRange> =
    map { range ->
        val start = range.start.coerceAtLeast(Duration.ZERO)
        BufferedRange(
            start = start,
            end = range.end.coerceAtLeast(start),
        )
    }

private fun ContentScale.toWasmEngineFitMode(): FitMode =
    when (this) {
        ContentScale.Crop -> FitMode.COVER
        ContentScale.FillBounds -> FitMode.FILL
        else -> FitMode.CONTAIN
    }

internal fun WebDecoderPreference.toWasmEngineDecoderPreference(): DecoderPreference =
    when (this) {
        WebDecoderPreference.AUTO -> DecoderPreference.AUTO
        WebDecoderPreference.SOFTWARE -> DecoderPreference.SOFTWARE
    }

internal fun VideoProjectionSettings.toWasmEngineProjection(
    textureCrop: VideoTextureCrop,
    projectionView: VideoProjectionViewSettings = VideoProjectionViewSettings(),
): ProjectionConfiguration {
    val normalized = normalized()
    val normalizedView = projectionView.normalized()
    val crop = textureCrop.normalized()
    val renderPlan =
        normalized.toVideoProjectionRenderPlan(
            VideoProjectionRenderOptions(textureCrop = crop),
        )
    val monoscopicWindow = renderPlan.leftEyeTexture.takeUnless { renderPlan.stereo }
    val mode =
        when (normalized.projectionType) {
            VideoProjectionType.Flat -> ProjectionMode.FLAT
            VideoProjectionType.Equirect180 -> ProjectionMode.VR180
            VideoProjectionType.Equirect360 -> ProjectionMode.EQUIRECTANGULAR
            VideoProjectionType.Fisheye180,
            VideoProjectionType.Fisheye190,
            VideoProjectionType.Fisheye200,
            VideoProjectionType.Fisheye220,
            -> ProjectionMode.FISHEYE
            VideoProjectionType.Eac360 -> ProjectionMode.EQUIRECTANGULAR
        }
    val baseFov =
        when (normalized.projectionType) {
            VideoProjectionType.Flat -> 90f
            else -> normalized.fovDegrees
        }
    return ProjectionConfiguration(
        mode = mode,
        stereoLayout =
            if (monoscopicWindow != null) {
                ProjectionStereoLayout.MONO
            } else {
                when (normalized.stereoLayout) {
                    VideoStereoLayout.Mono -> ProjectionStereoLayout.MONO
                    VideoStereoLayout.SideBySide -> ProjectionStereoLayout.SIDE_BY_SIDE
                    VideoStereoLayout.OverUnder -> ProjectionStereoLayout.OVER_UNDER
                }
            },
        eyeOrder =
            when (normalized.eyeOrder) {
                VideoEyeOrder.LeftRight -> ProjectionEyeOrder.LEFT_FIRST
                VideoEyeOrder.RightLeft -> ProjectionEyeOrder.RIGHT_FIRST
            },
        yawDegrees = normalizedView.yawDegrees,
        pitchDegrees = normalizedView.pitchDegrees,
        fieldOfViewDegrees = (baseFov / normalizedView.zoom).coerceIn(20f, 150f),
        cropLeft = (monoscopicWindow?.left ?: crop.left).toWasmEngineCropEdge(),
        cropTop = (monoscopicWindow?.top ?: crop.top).toWasmEngineCropEdge(),
        cropRight = (monoscopicWindow?.let { 1f - it.right } ?: crop.right).toWasmEngineCropEdge(),
        cropBottom = (monoscopicWindow?.let { 1f - it.bottom } ?: crop.bottom).toWasmEngineCropEdge(),
    )
}

private fun Float.toWasmEngineCropEdge(): Float = coerceIn(0f, WASM_ENGINE_MAX_PROJECTION_CROP)

internal fun VideoProjectionSettings.toWasmEngineRotationDegrees(): Int =
    when (normalized().rotation) {
        VideoProjectionRotation.None -> 0
        VideoProjectionRotation.Rotate90 -> 90
        VideoProjectionRotation.Rotate180 -> 180
        VideoProjectionRotation.Rotate270 -> 270
    }

private const val WASM_ENGINE_MAX_PROJECTION_CROP: Float = 0.49f

private fun DynamicRangePolicy.toWasmEngineToneMapping(): ToneMappingMode =
    when (this) {
        DynamicRangePolicy.FORCE_SDR -> ToneMappingMode.SHADER
        DynamicRangePolicy.REQUIRE_HDR -> ToneMappingMode.NATIVE
        DynamicRangePolicy.AUTO,
        DynamicRangePolicy.PREFER_HDR,
        -> ToneMappingMode.AUTO
    }

internal fun DynamicRangePolicy.toWasmEngineOutputDynamicRangePolicy(): OutputDynamicRangePolicy =
    when (this) {
        DynamicRangePolicy.AUTO -> OutputDynamicRangePolicy.AUTO
        DynamicRangePolicy.PREFER_HDR -> OutputDynamicRangePolicy.PREFER_HDR
        DynamicRangePolicy.REQUIRE_HDR -> OutputDynamicRangePolicy.REQUIRE_HDR
        DynamicRangePolicy.FORCE_SDR -> OutputDynamicRangePolicy.FORCE_SDR
    }

internal class KMediaBrowserSourceAdapter(
    private val source: WebPreparedVideoPipelineSource,
    fallbackMimeType: String?,
) : BrowserMediaSourceAdapter {
    override val url: String = source.uri
    override val mimeType: String? = source.mimeType ?: fallbackMimeType

    override fun attach(
        video: HTMLVideoElement,
        onFailure: (String) -> Unit,
    ) {
        source.attach(video, onFailure)
    }

    override fun detach(video: HTMLVideoElement) {
        source.detach(video)
    }

    override fun close() {
        source.close()
    }
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

private fun String.toWasmEngineSubtitleFormat(codec: String?): SubtitleFormat =
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

@Suppress("CyclomaticComplexMethod")
internal fun WasmEngineVideoTrackSnapshot.toVideoColorInfo(): VideoColorInfo {
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
        bitDepth = pixelFormat?.extractWasmEngineBitDepth(),
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

private fun String.extractWasmEngineBitDepth(): Int? =
    when {
        contains("16", ignoreCase = true) -> 16
        contains("12", ignoreCase = true) -> 12
        contains("10", ignoreCase = true) || contains("p010", ignoreCase = true) -> 10
        contains("9", ignoreCase = true) -> 9
        isNotBlank() -> 8
        else -> null
    }

private fun classifyWasmMediaError(
    message: String,
    category: WasmMediaErrorCategory?,
    isDrm: Boolean,
): VideoPlayerError {
    if (isDrm || category == WasmMediaErrorCategory.DRM) return VideoPlayerError.DrmError(message)
    return when (category) {
        WasmMediaErrorCategory.NETWORK -> VideoPlayerError.NetworkError(message)
        WasmMediaErrorCategory.FORMAT, WasmMediaErrorCategory.SOURCE -> VideoPlayerError.SourceError(message)
        WasmMediaErrorCategory.DECODER, WasmMediaErrorCategory.RENDERER -> VideoPlayerError.CodecError(message)
        WasmMediaErrorCategory.DRM -> VideoPlayerError.DrmError(message)
        WasmMediaErrorCategory.STATE, WasmMediaErrorCategory.INTERNAL, null -> {
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

internal fun redactWasmMediaError(
    message: String,
    drmConfiguration: WebDrmConfiguration?,
    mediaRequestHeaders: Map<String, String> = emptyMap(),
): String {
    if (drmConfiguration != null) {
        return "DRM playback failed in WasmEngine/Shaka; license details were redacted."
    }
    if (mediaRequestHeaders.isNotEmpty()) {
        return "WasmEngine playback failed; media request details were redacted."
    }
    return redactSensitiveText(message)
}
