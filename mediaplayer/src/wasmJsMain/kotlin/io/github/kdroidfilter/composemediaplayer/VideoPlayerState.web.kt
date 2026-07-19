@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package io.github.kdroidfilter.composemediaplayer

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import io.github.kdroidfilter.composemediaplayer.util.PipResult
import io.github.kdroidfilter.composemediaplayer.util.formatTime
import io.github.kdroidfilter.composemediaplayer.util.getUri
import io.github.kdroidfilter.composemediaplayer.util.secondsAsDuration
import io.github.kdroidfilter.composemediaplayer.util.toSecondsDouble
import io.github.vinceglb.filekit.PlatformFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.io.IOException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

actual fun createVideoPlayerState(
    audioMode: AudioMode,
    cacheConfig: CacheConfig,
    playbackOptions: VideoPlaybackOptions,
): VideoPlayerState = DefaultVideoPlayerState(playbackOptions)

internal actual fun platformPlayerCapabilities(playbackOptions: VideoPlaybackOptions): PlayerCapabilities {
    val usesMovi =
        playbackOptions.webPlaybackDecision().route.let { route ->
            route == WebPlaybackRoute.MOVI || route == WebPlaybackRoute.MOVI_DRM
        }
    return PlayerCapabilities(
        supportsMkv =
            usesMovi ||
                canPlayWebMimeType(MATROSKA_MIME_TYPE),
        supportsHls =
            usesMovi ||
                isWebHlsPlaybackSupported(),
        supportsPiP = isWebPictureInPictureSupported(),
        displayColorCapabilities = queryWebDisplayColorCapabilities(),
        rendererColorCapabilities = queryWebRendererColorCapabilities(),
        supportedUriSchemes = WEB_SUPPORTED_URI_SCHEMES,
    )
}

internal actual fun platformQueryCanPlaySource(source: MediaSourceSpec): Boolean =
    canPlayWebSource(
        uri = source.uri,
        mimeType = source.mimeType,
        capabilities = platformPlayerCapabilities(),
        useMovi = true,
    )

/**
 * Implementation of VideoPlayerState for WebAssembly.
 * Manages the state of a video player including playback controls, media information,
 * and error handling.
 */
@Stable
@Suppress("LargeClass", "TooManyFunctions")
open class DefaultVideoPlayerState(
    internal val playbackOptions: VideoPlaybackOptions = VideoPlaybackOptions(),
) : VideoPlayerState {
    internal val webPlaybackDecision: WebPlaybackDecision
        get() =
            playbackOptions.webPlaybackDecision(
                projection = projection,
                textureCrop = projectionTextureCrop,
            )
    internal val webSubtitlePipelineExtensions: List<WebSubtitlePipelineExtension> =
        playbackOptions.extensions
            .filterIsInstance<WebSubtitlePipelineExtension>()
            .filter { extension -> extension.availability.canContribute }
    private val colorPipelineController =
        VideoColorPipelineController(playbackOptions, platformPlayerCapabilities(playbackOptions))
    override val colorPipelineStatus: StateFlow<VideoColorPipelineStatus> = colorPipelineController.status
    private var webControlledColorSurfaceActive = false
    private var webProjectionSurfaceActive = false
    private var webColorRendererConfigured = false
    private var webColorRendererOutputDynamicRange = VideoDynamicRange.UNKNOWN
    private var webProjectionSurfaceKind = VideoSurfaceKind.UNKNOWN
    private var webGpuHdrUnavailable = false
    private var webGpuHdrFailureDetail: String? = null
    private var disposed = false
    internal val isDisposed: Boolean get() = disposed
    private var projectionAutoDetectionEnabled = playbackOptions.usesAutoProjectionDetection()
    private var _projection by mutableStateOf(playbackOptions.projection.normalized())
    override var projection: VideoProjectionSettings
        get() = _projection
        set(value) {
            checkNotDisposed()
            projectionAutoDetectionEnabled = false
            applyProjectionSettings(value)
            refreshWebColorPipelineOutput()
        }
    private var _projectionView by mutableStateOf(playbackOptions.projectionView.normalized())
    override var projectionView: VideoProjectionViewSettings
        get() = _projectionView
        set(value) {
            checkNotDisposed()
            _projectionView = value.normalized()
        }
    private var _projectionViewControlMode by mutableStateOf(playbackOptions.projectionViewControlMode)
    override var projectionViewControlMode: VideoProjectionViewControlMode
        get() = _projectionViewControlMode
        set(value) {
            checkNotDisposed()
            _projectionViewControlMode = value
        }
    private var _projectionTextureCrop by mutableStateOf(playbackOptions.projectionTextureCrop.normalized())
    override var projectionTextureCrop: VideoTextureCrop
        get() = _projectionTextureCrop
        set(value) {
            checkNotDisposed()
            _projectionTextureCrop = value.normalized()
            refreshWebColorPipelineOutput()
        }

    // Coroutine scope for managing async operations
    private val playerScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var lastUpdateTime = TimeSource.Monotonic.markNow()
    private val playbackEventDispatcher = PlaybackEventDispatcher()
    private var observableMediaSessionId by mutableStateOf(playbackEventDispatcher.mediaSessionId)
    override val mediaSessionId: Long get() = observableMediaSessionId
    override val playbackEvents = playbackEventDispatcher.events

    // Throttling for control changes
    private var lastVolumeChangeTime = TimeSource.Monotonic.markNow()
    private var lastSpeedChangeTime = TimeSource.Monotonic.markNow()
    private var pendingVolumeChange: Job? = null
    private var pendingSpeedChange: Job? = null

    // Source URI of the current media
    private var _sourceUri by mutableStateOf<String?>(null)
    val sourceUri: String? get() = _sourceUri
    private var _sourceFile by mutableStateOf<PlatformFile?>(null)
    internal val sourceFile: PlatformFile? get() = _sourceFile
    private var _requestHeaders by mutableStateOf<Map<String, String>>(emptyMap())
    val requestHeaders: Map<String, String> get() = _requestHeaders
    private var preparedPipelineSource: PreparedVideoPipelineSource? = null
    private var preparedPipelineOriginalColorInfo: VideoColorInfo? = null

    // Playback state properties
    private var _isPlaying by mutableStateOf(false)
    override val isPlaying: Boolean get() = _isPlaying

    private var _hasMedia by mutableStateOf(false)
    override val hasMedia: Boolean get() = _hasMedia

    internal var _isLoading by mutableStateOf(false)
    override val isLoading: Boolean get() = _isLoading
    internal var seekingState by mutableStateOf(false)
    override val isSeeking: Boolean get() = seekingState

    // Error handling
    private var playbackEndedCallback: (() -> Unit)? = null
    override var onPlaybackEnded: (() -> Unit)?
        get() = playbackEndedCallback
        set(value) {
            checkNotDisposed()
            playbackEndedCallback = value
        }
    private var restartCallback: (() -> Unit)? = null
    override var onRestart: (() -> Unit)?
        get() = restartCallback
        set(value) {
            checkNotDisposed()
            restartCallback = value
        }

    private var _error by mutableStateOf<VideoPlayerError?>(null)
    override val error: VideoPlayerError? get() = _error

    // Media metadata
    override val metadata = VideoMetadata()
    override val renderingInfo =
        VideoRenderingInfo(
            backend =
                if (playbackOptions.webPlaybackEngine == WebPlaybackEngine.MOVI) {
                    MOVI_RENDERING_BACKEND
                } else {
                    LEGACY_RENDERING_BACKEND
                },
            videoDecoder =
                if (playbackOptions.webPlaybackEngine == WebPlaybackEngine.MOVI) {
                    "Movi auto decoder"
                } else {
                    "Browser native decoder"
                },
            videoRenderer =
                if (playbackOptions.webPlaybackEngine == WebPlaybackEngine.MOVI) {
                    "Movi canvas"
                } else {
                    "HTMLVideoElement"
                },
            audioRenderer =
                if (playbackOptions.webPlaybackEngine == WebPlaybackEngine.MOVI) {
                    "Movi audio renderer"
                } else {
                    "Browser native audio"
                },
            videoProjection = projection.renderingInfoLabel(),
        )
    private var _diagnostics by mutableStateOf(PlaybackDiagnostics())
    override val diagnostics: PlaybackDiagnostics get() = _diagnostics
    private val _availableHlsQualities = mutableStateListOf<HlsQualityVariant>()
    override val availableHlsQualities: List<HlsQualityVariant> get() = _availableHlsQualities
    private var _currentHlsQuality by mutableStateOf<HlsQualityVariant?>(null)
    override val currentHlsQuality: HlsQualityVariant? get() = _currentHlsQuality
    private var _hlsQualityMode by mutableStateOf(HlsQualityMode.AUTO)
    override val hlsQualityMode: HlsQualityMode get() = _hlsQualityMode
    internal var applyHlsQualityCallback: ((String?) -> Unit)? = null
    override val capabilities: PlayerCapabilities
        get() = platformPlayerCapabilities(playbackOptions).withPipelineExtensions(playbackOptions)
    private var _aspectRatio by mutableStateOf(DEFAULT_ASPECT_RATIO)
    override val aspectRatio: Float get() = _aspectRatio

    // Subtitle management
    private var _subtitlesEnabled by mutableStateOf(false)
    override var subtitlesEnabled: Boolean
        get() = _subtitlesEnabled
        set(value) {
            checkNotDisposed()
            _subtitlesEnabled = value
        }
    private var _currentSubtitleTrack by mutableStateOf<SubtitleTrack?>(null)
    override var currentSubtitleTrack: SubtitleTrack?
        get() = _currentSubtitleTrack
        set(value) {
            checkNotDisposed()
            _currentSubtitleTrack = value
        }
    private val _availableSubtitleTracks = mutableStateListOf<SubtitleTrack>()
    override val availableSubtitleTracks: List<SubtitleTrack>
        get() = _availableSubtitleTracks
    private var _subtitleTextStyle by mutableStateOf(
        TextStyle(
            color = Color.White,
            fontSize = 18.sp,
            fontWeight = FontWeight.Normal,
            textAlign = TextAlign.Center,
        ),
    )
    override var subtitleTextStyle: TextStyle
        get() = _subtitleTextStyle
        set(value) {
            checkNotDisposed()
            _subtitleTextStyle = value
        }
    private var _subtitleBackgroundColor by mutableStateOf(Color.Black.copy(alpha = 0.5f))
    override var subtitleBackgroundColor: Color
        get() = _subtitleBackgroundColor
        set(value) {
            checkNotDisposed()
            _subtitleBackgroundColor = value
        }
    private var _subtitleOffset by mutableStateOf(Duration.ZERO)
    override var subtitleOffset: Duration
        get() = _subtitleOffset
        set(value) {
            checkNotDisposed()
            _subtitleOffset = value
        }

    // Audio track management
    private var _currentAudioTrack by mutableStateOf<AudioTrack?>(null)
    override var currentAudioTrack: AudioTrack?
        get() = _currentAudioTrack
        set(value) {
            checkNotDisposed()
            _currentAudioTrack = value
        }
    private val _availableAudioTracks = mutableStateListOf<AudioTrack>()
    override val availableAudioTracks: List<AudioTrack>
        get() = _availableAudioTracks

    internal var applyAudioTrackCallback: ((AudioTrack?) -> Unit)? = null
    internal var applyAudioTrackSelectionCallback: ((AudioTrack?) -> TrackSelectionResult)? = null
    internal var applySubtitleTrackCallback: ((SubtitleTrack?) -> Unit)? = null

    // Playback control properties
    private var _volume by mutableStateOf(1.0f)
    override var volume: Float
        get() = _volume
        set(value) {
            checkNotDisposed()
            val newValue = value.coerceIn(0f, 1f)
            if (_volume != newValue) {
                _volume = newValue
                applyVolumeChangeWithThrottle(newValue)
            }
        }

    private var _sliderPos by mutableStateOf(0.0f)
    override var sliderPos: Float
        get() = _sliderPos
        set(value) {
            checkNotDisposed()
            _sliderPos = value
        }
    private var _userDragging by mutableStateOf(false)
    override var userDragging: Boolean
        get() = _userDragging
        set(value) {
            checkNotDisposed()
            _userDragging = value
        }
    private var _loop by mutableStateOf(false)
    override var loop: Boolean
        get() = _loop
        set(value) {
            checkNotDisposed()
            _loop = value
        }

    private var _playbackSpeed by mutableStateOf(1.0f)
    override var playbackSpeed: Float
        get() = _playbackSpeed
        set(value) {
            checkNotDisposed()
            val newValue = value.coerceIn(VideoPlayerState.MIN_PLAYBACK_SPEED, VideoPlayerState.MAX_PLAYBACK_SPEED)
            if (_playbackSpeed != newValue) {
                _playbackSpeed = newValue
                applyPlaybackSpeedWithThrottle(newValue)
            }
        }

    private var _isFullscreen by mutableStateOf(false)
    override var isFullscreen: Boolean
        get() = _isFullscreen
        set(value) {
            checkNotDisposed()
            _isFullscreen = value
            refreshWebColorPipelineOutput()
        }
    override var isPipActive: Boolean
        get() = false
        set(value) {
            checkNotDisposed()
        }
    override var isPipEnabled: Boolean
        get() = false
        set(value) {
            checkNotDisposed()
        }

    // Time display properties
    private var _positionText by mutableStateOf("00:00")
    private var _durationText by mutableStateOf("00:00")
    override val positionText: String get() = _positionText
    override val durationText: String get() = _durationText

    private var _currentDuration by mutableStateOf(Duration.ZERO)
    private var _currentTime by mutableStateOf(Duration.ZERO)
    private var _chapters by mutableStateOf(emptyList<MediaChapter>())
    private var webTextTrackChapterRows = emptyList<RawMediaChapter>()
    private var webHlsChapterRows = emptyList<RawMediaChapter>()
    private val _bufferedRanges = mutableStateListOf<BufferedRange>()
    internal var preciseCurrentTimeProvider: (() -> Duration)? = null
    internal var durationProvider: (() -> Duration)? = null
    override val currentTime: Duration get() = _currentTime
    override val preciseCurrentTime: Duration get() = preciseCurrentTimeProvider?.invoke() ?: _currentTime
    override val duration: Duration
        get() {
            val observedDuration = _currentDuration
            return durationProvider?.invoke() ?: observedDuration
        }
    override val chapters: List<MediaChapter> get() = _chapters
    override val bufferedRanges: List<BufferedRange> get() = _bufferedRanges

    // Job for handling seek operations
    internal var seekJob: Job? = null
    internal var seekRequestId by mutableStateOf(0)
        private set
    private var pendingSeekRequest = false
    private var pendingSeekTime: Duration? = null
    private var seekEventActive = false
    private var stallEventActive = false
    private var sourceLoadedForSession = false
    private var suppressNextSeekEvents = false

    /**
     * Callback function to force recalculation of the HTML view position.
     * This is set by the VideoPlayerSurface when the HTML view is created.
     */
    internal var positionRecalculationCallback: (() -> Unit)? = null

    /**
     * Callback to apply volume changes to the underlying media player
     */
    internal var applyVolumeCallback: ((Float) -> Unit)? = null

    /**
     * Callback to apply playback speed changes to the underlying media player
     */
    internal var applyPlaybackSpeedCallback: ((Float) -> Unit)? = null

    /**
     * Applies transport changes synchronously when a browser user gesture is still active.
     * This matters for browsers that reject a deferred `HTMLVideoElement.play()` call.
     */
    internal var applyPlaybackCallback: ((Boolean) -> Unit)? = null

    internal var resetPlaybackCallback: (() -> Unit)? = null

    /**
     * Forces recalculation of the HTML view position.
     * This is useful when the layout changes and the HTML view needs to be repositioned.
     */
    fun forcePositionRecalculation() {
        checkNotDisposed()
        positionRecalculationCallback?.invoke()
    }

    internal fun bindWebColorSurface(
        usesControlledColorRenderer: Boolean,
        isProjection: Boolean,
    ) {
        webControlledColorSurfaceActive = usesControlledColorRenderer
        webProjectionSurfaceActive = isProjection
        resetWebColorRendererRuntime(resetHdrFailure = true)
        refreshWebColorPipelineOutput()
    }

    internal fun unbindWebColorSurface() {
        webControlledColorSurfaceActive = false
        webProjectionSurfaceActive = false
        resetWebColorRendererRuntime(resetHdrFailure = true)
        colorPipelineController.updateOutput(
            surfaceKind = VideoSurfaceKind.UNKNOWN,
            nativeSurfaceAvailable = false,
            isProjection = false,
            verification = ColorPipelineVerification.NONE,
        )
    }

    internal fun attachPreparedPipelineSource(video: org.w3c.dom.HTMLVideoElement) {
        val source = preparedPipelineSource as? WebPreparedVideoPipelineSource ?: return
        val expectedSessionId = mediaSessionId
        source.attach(video) { detail ->
            if (!isCurrentMediaSession(expectedSessionId) || disposed) return@attach
            _isPlaying = false
            _isLoading = false
            setError(
                VideoPlayerError.ColorPipelineError(
                    ColorPipelineFallbackReason.DOLBY_VISION_CONVERTER_UNAVAILABLE,
                    detail,
                ),
            )
        }
    }

    internal fun detachPreparedPipelineSource(video: org.w3c.dom.HTMLVideoElement) {
        (preparedPipelineSource as? WebPreparedVideoPipelineSource)?.detach(video)
    }

    private fun closePreparedPipelineSource() {
        val prepared = preparedPipelineSource
        preparedPipelineSource = null
        preparedPipelineOriginalColorInfo = null
        prepared?.close()
    }

    internal fun refreshWebVideoColorInfo(video: org.w3c.dom.HTMLVideoElement) {
        val decodedSource = video.toWebVideoColorInfo()
        val source = preparedPipelineOriginalColorInfo ?: decodedSource
        if (colorPipelineStatus.value.source == source) {
            refreshWebColorPipelineOutput()
            return
        }
        val decoderSource = preparedPipelineSource?.outputColorInfo ?: decodedSource
        val decoderRanges =
            buildSet {
                if (decoderSource.dynamicRange != VideoDynamicRange.UNKNOWN) add(decoderSource.dynamicRange)
                if (decoderSource.dynamicRange == VideoDynamicRange.HDR10_PLUS) add(VideoDynamicRange.HDR10)
                if (decoderSource.dolbyVision?.hasHdr10CompatibleBaseLayer == true) add(VideoDynamicRange.HDR10)
            }
        colorPipelineController.updateSource(
            source = source,
            decoderName =
                if (preparedPipelineSource != null) {
                    "libdovi Profile 7 to 8.1 MediaSource bridge -> browser decoder"
                } else {
                    "Browser decoder / WebCodecs VideoFrame"
                },
            decoderCapabilities =
                if (decoderRanges.isEmpty()) {
                    DecoderColorCapabilities()
                } else {
                    DecoderColorCapabilities(
                        isKnown = true,
                        supportedDynamicRanges = decoderRanges,
                        maxBitDepth = decoderSource.bitDepth,
                    )
                },
            isLive = sourceUri?.substringBefore('?')?.endsWith(".m3u8", ignoreCase = true) == true,
        )
        resetWebColorRendererRuntime(resetHdrFailure = true)
        refreshWebColorPipelineOutput()
    }

    internal fun onWebColorRendererConfigured(
        outputDynamicRange: VideoDynamicRange,
        surfaceKind: VideoSurfaceKind,
    ) {
        if (!webControlledColorSurfaceActive) return
        if (
            webColorRendererConfigured &&
            webColorRendererOutputDynamicRange == outputDynamicRange &&
            webProjectionSurfaceKind == surfaceKind
        ) {
            return
        }
        webColorRendererConfigured = true
        webColorRendererOutputDynamicRange = outputDynamicRange
        webProjectionSurfaceKind = surfaceKind
        if (confirmsWebGpuHdrOutput(outputDynamicRange, surfaceKind)) {
            webGpuHdrUnavailable = false
            webGpuHdrFailureDetail = null
        }
        refreshWebColorPipelineOutput()
    }

    internal fun onWebDisplayColorCapabilitiesChanged() {
        if (disposed) return
        resetWebColorRendererRuntime(resetHdrFailure = true)
        refreshWebColorPipelineOutput()
    }

    internal fun onWebHdrRendererUnavailable(message: String) {
        if (!webControlledColorSurfaceActive) return
        webGpuHdrUnavailable = true
        webGpuHdrFailureDetail = message
        webColorRendererConfigured = false
        webColorRendererOutputDynamicRange = VideoDynamicRange.UNKNOWN
        webProjectionSurfaceKind = VideoSurfaceKind.UNKNOWN
        refreshWebColorPipelineOutput()
    }

    internal fun onWebColorRendererFailed(message: String) {
        webColorRendererConfigured = false
        webColorRendererOutputDynamicRange = VideoDynamicRange.UNKNOWN
        refreshWebColorPipelineOutput()
        setError(
            VideoPlayerError.ColorPipelineError(
                reason = ColorPipelineFallbackReason.RENDERER_CONFIGURATION_FAILED,
                message = message,
            ),
        )
    }

    @Suppress("CyclomaticComplexMethod")
    private fun refreshWebColorPipelineOutput() {
        val display = queryWebDisplayColorCapabilities()
        val runtimeSupport = queryWebHdrCanvasRuntimeSupport()
        val renderer =
            if (webControlledColorSurfaceActive) {
                queryWebProjectionRendererColorCapabilities(
                    display = display,
                    runtimeSupport = runtimeSupport,
                    hdrDisabledAfterRuntimeFailure = webGpuHdrUnavailable,
                )
            } else {
                queryWebRendererColorCapabilities(display)
            }
        val source = colorPipelineStatus.value.source
        val compatibleHdrOutput = source.webCompatibleHdrCanvasOutput
        val runtimeFallbackApplies =
            webProjectionSurfaceActive &&
                source.isHdr &&
                compatibleHdrOutput != null &&
                display.supports(compatibleHdrOutput) &&
                renderer.controlledHdrDynamicRanges.isEmpty()
        val intendedSurface =
            when {
                !webControlledColorSurfaceActive -> VideoSurfaceKind.WEB_VIDEO
                webColorRendererConfigured -> webProjectionSurfaceKind
                source.isHdr -> VideoSurfaceKind.WEB_GPU_CANVAS
                else -> VideoSurfaceKind.WEB_GL_CANVAS
            }
        val plan =
            colorPipelineController.updateOutput(
                displayCapabilities = display,
                rendererCapabilities = renderer,
                surfaceKind = intendedSurface,
                nativeSurfaceAvailable = !webControlledColorSurfaceActive,
                isProjection = webProjectionSurfaceActive,
                verification = ColorPipelineVerification.INFERRED,
                platformRuntimeFallbackReason =
                    ColorPipelineFallbackReason.HDR_PROJECTION_UNAVAILABLE.takeIf { runtimeFallbackApplies },
                platformRuntimeDetail =
                    webProjectionHdrFallbackDetail(runtimeSupport).takeIf { runtimeFallbackApplies },
            )
        if (
            webControlledColorSurfaceActive &&
            webColorRendererConfigured &&
            plan?.outputDynamicRange == webColorRendererOutputDynamicRange &&
            intendedSurface == webProjectionSurfaceKind
        ) {
            colorPipelineController.updateOutput(
                displayCapabilities = display,
                rendererCapabilities = renderer,
                surfaceKind = intendedSurface,
                nativeSurfaceAvailable = false,
                isProjection = webProjectionSurfaceActive,
                verification = ColorPipelineVerification.RENDERER_CONFIGURED,
                platformRuntimeFallbackReason =
                    ColorPipelineFallbackReason.HDR_PROJECTION_UNAVAILABLE.takeIf { runtimeFallbackApplies },
                platformRuntimeDetail =
                    webProjectionHdrFallbackDetail(runtimeSupport).takeIf { runtimeFallbackApplies },
            )
        }
        val pipelineError = colorPipelineController.pipelineErrorOrNull()
        val sourceColorPending =
            colorPipelineStatus.value.source.dynamicRange == VideoDynamicRange.UNKNOWN && _isLoading
        val waitingForWebGpuConfirmation =
            webControlledColorSurfaceActive &&
                !webGpuHdrUnavailable &&
                !webColorRendererConfigured &&
                colorPipelineStatus.value.plannedOutputDynamicRange.isHdrOutput
        val unconfirmedRequiredHdr =
            playbackOptions.dynamicRangePolicy == DynamicRangePolicy.REQUIRE_HDR &&
                colorPipelineStatus.value.source.isHdr &&
                colorPipelineStatus.value.outputDynamicRange == VideoDynamicRange.UNKNOWN &&
                !waitingForWebGpuConfirmation
        if ((!sourceColorPending && pipelineError != null) || unconfirmedRequiredHdr) {
            val fatalError =
                pipelineError
                    ?: VideoPlayerError.ColorPipelineError(
                        reason = ColorPipelineFallbackReason.HDR_SURFACE_UNAVAILABLE,
                        message = "The browser cannot confirm an HDR output surface for REQUIRE_HDR.",
                    )
            if (_error != fatalError) setError(fatalError)
            _isPlaying = false
        } else if (!sourceColorPending && _error is VideoPlayerError.ColorPipelineError) {
            _error = null
        }
    }

    private fun resetWebColorRendererRuntime(resetHdrFailure: Boolean) {
        webColorRendererConfigured = false
        webColorRendererOutputDynamicRange = VideoDynamicRange.UNKNOWN
        webProjectionSurfaceKind = VideoSurfaceKind.UNKNOWN
        if (resetHdrFailure) {
            webGpuHdrUnavailable = false
            webGpuHdrFailureDetail = null
        }
    }

    private fun webProjectionHdrFallbackDetail(runtimeSupport: WebHdrCanvasRuntimeSupport): String =
        webGpuHdrFailureDetail
            ?: when {
                !runtimeSupport.displayReportsHighDynamicRange ->
                    "The active browser display does not report `(dynamic-range: high)`; " +
                        "projection uses controlled SDR WebGPU."
                !runtimeSupport.hasWebGpu ->
                    "WebGPU is unavailable in this browsing context; no controlled HDR-to-SDR canvas route exists."
                !runtimeSupport.hasCanvasConfigurationReadback ->
                    "The browser cannot read back an extended-range WebGPU canvas configuration; " +
                        "projection uses controlled SDR WebGPU."
                else ->
                    "The browser could not retain a confirmed FP16 extended-tone-mapping canvas; " +
                        "projection uses controlled SDR WebGPU."
            }

    internal fun isCurrentMediaSession(sessionId: Long): Boolean = !disposed && sessionId == mediaSessionId

    internal fun emitPlaybackEvent(factory: (Long, Long) -> PlaybackEvent) {
        if (disposed) return
        playbackEventDispatcher.emit(factory)
    }

    private fun emitPlaybackEventForSession(
        sessionId: Long,
        factory: (Long, Long) -> PlaybackEvent,
    ) {
        playbackEventDispatcher.emitForSession(sessionId, factory)
    }

    private fun emitSourceReleasedForSession(sessionId: Long) {
        if (sessionId == 0L) return
        emitPlaybackEventForSession(sessionId) { eventSessionId, sampledAtMs ->
            PlaybackEvent.SourceReleased(
                mediaSessionId = eventSessionId,
                sampledAtMs = sampledAtMs,
            )
        }
    }

    private fun nextMediaSessionId(): Long =
        playbackEventDispatcher.nextMediaSessionId().also { observableMediaSessionId = it }

    private fun checkNotDisposed() {
        check(!disposed) { "VideoPlayerState has been disposed" }
    }

    private fun beginSeekEvent(target: Duration) {
        suppressNextSeekEvents = false
        if (!_hasMedia || seekEventActive) return
        seekEventActive = true
        emitPlaybackEvent { sessionId, sampledAtMs ->
            PlaybackEvent.SeekStarted(
                mediaSessionId = sessionId,
                sampledAtMs = sampledAtMs,
                target = target,
            )
        }
    }

    internal fun onWebSeeking() {
        if (disposed || !_hasMedia) return
        if (suppressNextSeekEvents) return
        seekingState = true
        _isLoading = true
        beginSeekEvent(preciseCurrentTime)
    }

    internal fun onWebSeeked() {
        if (disposed || !_hasMedia) return
        if (suppressNextSeekEvents) {
            suppressNextSeekEvents = false
            seekingState = false
            _isLoading = false
            return
        }
        seekingState = false
        _isLoading = false
        if (seekEventActive) {
            seekEventActive = false
            emitPlaybackEvent { sessionId, sampledAtMs ->
                PlaybackEvent.SeekCompleted(
                    mediaSessionId = sessionId,
                    sampledAtMs = sampledAtMs,
                    position = preciseCurrentTime,
                )
            }
        }
    }

    internal fun onWebWaiting() {
        if (disposed || !_hasMedia) return
        _isLoading = true
        if (stallEventActive) return
        stallEventActive = true
        emitPlaybackEvent { sessionId, sampledAtMs ->
            PlaybackEvent.Stalled(
                mediaSessionId = sessionId,
                sampledAtMs = sampledAtMs,
            )
        }
    }

    internal fun onWebPlaybackReady() {
        if (disposed || !_hasMedia) return
        _isLoading = false
        seekingState = false
        if (!stallEventActive) return
        stallEventActive = false
        emitPlaybackEvent { sessionId, sampledAtMs ->
            PlaybackEvent.Recovered(
                mediaSessionId = sessionId,
                sampledAtMs = sampledAtMs,
            )
        }
    }

    internal fun onWebSourceLoaded(duration: Duration) {
        if (disposed || !_hasMedia || sourceLoadedForSession) return
        sourceLoadedForSession = true
        emitPlaybackEvent { sessionId, sampledAtMs ->
            PlaybackEvent.SourceLoaded(
                mediaSessionId = sessionId,
                sampledAtMs = sampledAtMs,
                duration = duration,
            )
        }
    }

    internal fun onMoviPlaybackState(state: String) {
        if (disposed || !_hasMedia) return
        when (state) {
            "playing" -> _isPlaying = true
            "paused", "ended" -> _isPlaying = false
            "loading", "buffering", "seeking" -> _isLoading = true
            "ready" -> _isLoading = false
            "error" -> {
                _isPlaying = false
                _isLoading = false
            }
        }
    }

    internal fun onMoviError(error: VideoPlayerError) {
        if (disposed || !_hasMedia) return
        _isPlaying = false
        _isLoading = false
        setError(error)
    }

    @Suppress("CyclomaticComplexMethod")
    internal fun applyMoviSnapshot(snapshot: MoviMediaSnapshot) {
        if (disposed || !_hasMedia) return

        _availableAudioTracks.clear()
        _availableAudioTracks.addAll(snapshot.audioTracks)
        _currentAudioTrack =
            snapshot.activeAudioTrackId
                ?.let { activeId -> snapshot.audioTracks.firstOrNull { it.id == activeId } }
                ?: snapshot.audioTracks.firstOrNull(AudioTrack::isDefault)
                ?: snapshot.audioTracks.firstOrNull()

        val externalSubtitles = _availableSubtitleTracks.filter(SubtitleTrack::isExternal)
        _availableSubtitleTracks.clear()
        _availableSubtitleTracks.addAll(externalSubtitles)
        _availableSubtitleTracks.addAll(snapshot.subtitleTracks)
        val activeEmbeddedSubtitle =
            snapshot.activeSubtitleTrackId
                ?.let { activeId -> snapshot.subtitleTracks.firstOrNull { it.id == activeId } }
        if (activeEmbeddedSubtitle != null) {
            _currentSubtitleTrack = activeEmbeddedSubtitle
            _subtitlesEnabled = true
        } else if (_currentSubtitleTrack?.isEmbedded == true) {
            _currentSubtitleTrack = null
            _subtitlesEnabled = false
        }

        val qualityVariants =
            snapshot.videoTracks
                .filter { it.id != MOVI_AUTO_VIDEO_TRACK_ID }
                .map { track ->
                    HlsQualityVariant(
                        id = "$MOVI_VIDEO_TRACK_ID_PREFIX${track.id}",
                        label =
                            when {
                                track.height != null -> "${track.height}p"
                                track.bitrate != null -> "${track.bitrate / BITS_PER_KILOBIT} kbps"
                                else -> "Video ${track.id}"
                            },
                        width = track.width,
                        height = track.height,
                        bitrate = track.bitrate,
                        codecs = track.codec,
                    )
                }
        val activeVideoId = snapshot.activeVideoTrack?.id
        replaceHlsQualities(
            variants = qualityVariants,
            selectedId =
                activeVideoId
                    ?.takeUnless { it == MOVI_AUTO_VIDEO_TRACK_ID }
                    ?.let { "$MOVI_VIDEO_TRACK_ID_PREFIX$it" },
            autoMode = activeVideoId == null || activeVideoId == MOVI_AUTO_VIDEO_TRACK_ID,
        )

        _chapters = snapshot.chapters.sortedBy(MediaChapter::start)
        val activeVideo = snapshot.activeVideoTrack
        metadata.title = snapshot.title
        metadata.duration = snapshot.durationSeconds?.secondsAsDuration()
        metadata.width = activeVideo?.width
        metadata.height = activeVideo?.height
        metadata.bitrate = snapshot.bitrate ?: activeVideo?.bitrate?.toLong()
        metadata.frameRate = activeVideo?.frameRate
        metadata.mimeType = snapshot.formatName
        metadata.audioChannels = _currentAudioTrack?.channels
        metadata.audioSampleRate = _currentAudioTrack?.sampleRate
        activeVideo?.width?.let { width ->
            activeVideo.height?.takeIf { it > 0 }?.let { height ->
                updateAspectRatio(width.toFloat() / height.toFloat())
            }
        }
        updateAutoDetectedProjectionFromMetadata()

        colorPipelineController.updateOutput(
            rendererCapabilities = RendererColorCapabilities(),
            surfaceKind = VideoSurfaceKind.UNKNOWN,
            nativeSurfaceAvailable = false,
            isProjection = false,
            verification = ColorPipelineVerification.NONE,
        )
        activeVideo?.let { track ->
            colorPipelineController.updateSource(
                source = track.toVideoColorInfo(),
                decoderName = null,
                decoderCapabilities = DecoderColorCapabilities(),
                isLive = sourceUri?.substringBefore('?')?.endsWith(".m3u8", ignoreCase = true) == true,
                isDrmProtected = playbackOptions.webDrmConfiguration != null,
                allowAutomaticDolbyVisionConversion = false,
            )
        }

        renderingInfo.update(
            backend = MOVI_RENDERING_BACKEND,
            container = snapshot.formatName,
            videoDecoder = activeVideo?.codec?.let { "Movi auto ($it)" } ?: "Movi auto decoder",
            videoRenderer =
                if (playbackOptions.webDrmConfiguration != null) {
                    "Movi/Shaka HTMLVideoElement"
                } else if (projection.requiresProjectionRenderer) {
                    "Movi canvas projection"
                } else {
                    "Movi canvas"
                },
            audioRenderer = "Movi audio renderer",
            subtitleRenderer = "Movi embedded subtitle renderer",
            subtitleSource = if (snapshot.subtitleTracks.isEmpty()) null else "Embedded",
            videoProjection = projection.renderingInfoLabel(),
        )
        updateDiagnostics(
            PlaybackDiagnostics(
                videoWidth = activeVideo?.width,
                videoHeight = activeVideo?.height,
                bitrate = activeVideo?.bitrate,
                currentHlsQuality = currentHlsQuality,
                bufferedRanges = bufferedRanges,
                notes = "MoviPlayer 0.3.5",
            ),
        )

        webPlaybackDecision.error?.let(::onMoviError)
    }

    private fun resetSourceTracks() {
        _currentAudioTrack = null
        _availableAudioTracks.clear()
        if (_currentSubtitleTrack?.isEmbedded != false) {
            _currentSubtitleTrack = null
            _subtitlesEnabled = false
        }
        _availableSubtitleTracks.removeAll { it.isEmbedded }
    }

    private fun clearAllTracks() {
        _currentAudioTrack = null
        _availableAudioTracks.clear()
        _currentSubtitleTrack = null
        _subtitlesEnabled = false
        _availableSubtitleTracks.clear()
    }

    private fun clearMetadata() {
        metadata.title = null
        metadata.duration = null
        metadata.width = null
        metadata.height = null
        metadata.bitrate = null
        metadata.frameRate = null
        metadata.mimeType = null
        metadata.audioChannels = null
        metadata.audioSampleRate = null
    }

    internal fun replaceWebTextTrackChapters(rows: List<RawMediaChapter>) {
        webTextTrackChapterRows = rows
        publishWebMediaChapters()
    }

    internal fun replaceWebHlsChapters(rows: List<RawMediaChapter>) {
        webHlsChapterRows = rows
        publishWebMediaChapters()
    }

    private fun publishWebMediaChapters() {
        _chapters =
            normalizeMediaChapters(
                rows = webHlsChapterRows + webTextTrackChapterRows,
                mediaDuration = duration,
            )
    }

    private fun clearWebMediaChapters() {
        webTextTrackChapterRows = emptyList()
        webHlsChapterRows = emptyList()
        _chapters = emptyList()
    }

    /**
     * Applies volume changes with throttling to prevent performance issues
     */
    private fun applyVolumeChangeWithThrottle(value: Float) {
        val now = TimeSource.Monotonic.markNow()
        val timeSinceLastChange = now - lastVolumeChangeTime

        // Cancel any pending volume change
        pendingVolumeChange?.cancel()

        if (timeSinceLastChange < 100.milliseconds) {
            // If changes are coming too rapidly, schedule them with a delay
            pendingVolumeChange =
                playerScope.launch {
                    delay(100.milliseconds - timeSinceLastChange)
                    applyVolumeCallback?.invoke(value)
                    lastVolumeChangeTime = TimeSource.Monotonic.markNow()
                }
        } else {
            // Apply immediately if we're not throttling
            applyVolumeCallback?.invoke(value)
            lastVolumeChangeTime = now
        }
    }

    /**
     * Applies playback speed changes with throttling to prevent performance issues
     */
    private fun applyPlaybackSpeedWithThrottle(value: Float) {
        val now = TimeSource.Monotonic.markNow()
        val timeSinceLastChange = now - lastSpeedChangeTime

        // Cancel any pending speed change
        pendingSpeedChange?.cancel()

        if (timeSinceLastChange < 100.milliseconds) {
            // If changes are coming too rapidly, schedule them with a delay
            pendingSpeedChange =
                playerScope.launch {
                    delay(100.milliseconds - timeSinceLastChange)
                    applyPlaybackSpeedCallback?.invoke(value)
                    lastSpeedChangeTime = TimeSource.Monotonic.markNow()
                }
        } else {
            // Apply immediately if we're not throttling
            applyPlaybackSpeedCallback?.invoke(value)
            lastSpeedChangeTime = now
        }
    }

    /**
     * Selects a subtitle track and enables subtitles.
     *
     * @param track The subtitle track to select, or null to disable subtitles
     */
    override fun selectSubtitleTrack(track: SubtitleTrack?): TrackSelectionResult {
        checkNotDisposed()
        if (track == null) return disableSubtitles()
        if (track.isEmbedded && availableSubtitleTracks.none { it.id == track.id }) {
            return TrackSelectionResult.NotFound(track.id)
        }

        currentSubtitleTrack = track
        subtitlesEnabled = true
        applySubtitleTrackCallback?.invoke(track)
        emitPlaybackEvent { sessionId, sampledAtMs ->
            PlaybackEvent.TrackChanged(
                mediaSessionId = sessionId,
                sampledAtMs = sampledAtMs,
                kind = TrackKind.SUBTITLE,
                trackId = track.id,
            )
        }
        return TrackSelectionResult.Selected(track.id)
    }

    override fun selectSubtitleTrack(trackId: String?): TrackSelectionResult {
        checkNotDisposed()
        return trackId
            ?.let { id ->
                availableSubtitleTracks
                    .firstOrNull { it.id == id }
                    ?.let(::selectSubtitleTrack)
                    ?: TrackSelectionResult.NotFound(id)
            }
            ?: selectSubtitleTrack(null as SubtitleTrack?)
    }

    /**
     * Disables subtitles by clearing the current track and setting subtitlesEnabled to false.
     */
    override fun disableSubtitles(): TrackSelectionResult {
        checkNotDisposed()
        currentSubtitleTrack = null
        subtitlesEnabled = false
        applySubtitleTrackCallback?.invoke(null)
        emitPlaybackEvent { sessionId, sampledAtMs ->
            PlaybackEvent.TrackChanged(
                mediaSessionId = sessionId,
                sampledAtMs = sampledAtMs,
                kind = TrackKind.SUBTITLE,
                trackId = null,
            )
        }
        return TrackSelectionResult.Disabled
    }

    override fun addSubtitleTrack(track: SubtitleTrack) {
        checkNotDisposed()
        val externalTrack = track.copy(isEmbedded = false)
        _availableSubtitleTracks.removeAll { it.id == externalTrack.id }
        _availableSubtitleTracks.add(externalTrack)
    }

    override fun removeSubtitleTrack(trackId: String) {
        checkNotDisposed()
        val selectedTrack = currentSubtitleTrack
        _availableSubtitleTracks.removeAll { it.id == trackId && it.isExternal }
        if (selectedTrack?.id == trackId && selectedTrack.isExternal) {
            disableSubtitles()
        }
    }

    override fun clearExternalSubtitleTracks() {
        checkNotDisposed()
        val selectedTrack = currentSubtitleTrack
        _availableSubtitleTracks.removeAll { it.isExternal }
        if (selectedTrack?.isExternal == true) {
            disableSubtitles()
        }
    }

    override fun selectAudioTrack(track: AudioTrack?): TrackSelectionResult {
        checkNotDisposed()
        if (track != null && availableAudioTracks.none { it.id == track.id }) {
            return TrackSelectionResult.NotFound(track.id)
        }

        val explicitSelection = applyAudioTrackSelectionCallback
        if (explicitSelection != null) {
            val result = explicitSelection(track)
            if (!result.isApplied) return result

            val appliedTrack =
                when (result) {
                    is TrackSelectionResult.Selected ->
                        availableAudioTracks.firstOrNull { it.id == result.trackId }
                    TrackSelectionResult.Auto ->
                        availableAudioTracks.firstOrNull(AudioTrack::isDefault)
                            ?: availableAudioTracks.firstOrNull()
                    else -> null
                }
            _currentAudioTrack = appliedTrack
            emitPlaybackEvent { sessionId, sampledAtMs ->
                PlaybackEvent.TrackChanged(
                    mediaSessionId = sessionId,
                    sampledAtMs = sampledAtMs,
                    kind = TrackKind.AUDIO,
                    trackId = appliedTrack?.id,
                )
            }
            return result
        }

        currentAudioTrack = track
        applyAudioTrackCallback?.invoke(track)
        emitPlaybackEvent { sessionId, sampledAtMs ->
            PlaybackEvent.TrackChanged(
                mediaSessionId = sessionId,
                sampledAtMs = sampledAtMs,
                kind = TrackKind.AUDIO,
                trackId = track?.id,
            )
        }
        return track.audioTrackSelectionResult()
    }

    override fun selectAudioTrack(trackId: String?): TrackSelectionResult {
        checkNotDisposed()
        return trackId
            ?.let { id ->
                availableAudioTracks
                    .firstOrNull { it.id == id }
                    ?.let(::selectAudioTrack)
                    ?: TrackSelectionResult.NotFound(id)
            }
            ?: selectAudioTrack(null as AudioTrack?)
    }

    override fun selectHlsQuality(variantId: String?): HlsQualitySelectionResult {
        checkNotDisposed()
        if (_availableHlsQualities.isEmpty() && applyHlsQualityCallback == null) {
            return HlsQualitySelectionResult.NotSupported
        }
        val selectedQuality =
            variantId?.let { id ->
                _availableHlsQualities.firstOrNull { it.id == id }
                    ?: return HlsQualitySelectionResult.NotFound(id)
            }
        _hlsQualityMode = if (variantId == null) HlsQualityMode.AUTO else HlsQualityMode.MANUAL
        _currentHlsQuality = selectedQuality
        applyHlsQualityCallback?.invoke(variantId)
        emitPlaybackEvent { sessionId, sampledAtMs ->
            PlaybackEvent.TrackChanged(
                mediaSessionId = sessionId,
                sampledAtMs = sampledAtMs,
                kind = TrackKind.HLS_QUALITY,
                trackId = variantId,
            )
        }
        return selectedQuality?.let(HlsQualitySelectionResult::Selected) ?: HlsQualitySelectionResult.Auto
    }

    internal fun replaceAvailableAudioTracks(tracks: List<AudioTrack>) {
        _availableAudioTracks.clear()
        _availableAudioTracks.addAll(tracks)

        currentAudioTrack =
            currentAudioTrack
                ?.let { current -> tracks.firstOrNull { it.id == current.id } }
                ?: tracks.firstOrNull()
    }

    internal fun replaceEmbeddedSubtitleTracks(tracks: List<SubtitleTrack>) {
        val externalTracks = _availableSubtitleTracks.filterNot { it.isEmbedded }
        _availableSubtitleTracks.clear()
        _availableSubtitleTracks.addAll(externalTracks)
        _availableSubtitleTracks.addAll(tracks)

        if (currentSubtitleTrack?.isEmbedded == true) {
            val refreshedTrack = tracks.firstOrNull { it.id == currentSubtitleTrack?.id }
            if (refreshedTrack == null) {
                disableSubtitles()
            } else {
                currentSubtitleTrack = refreshedTrack
            }
        }
    }

    internal fun replaceHlsQualities(
        variants: List<HlsQualityVariant>,
        selectedId: String?,
        autoMode: Boolean,
    ) {
        _availableHlsQualities.clear()
        _availableHlsQualities.addAll(variants)
        _hlsQualityMode = if (autoMode) HlsQualityMode.AUTO else HlsQualityMode.MANUAL
        _currentHlsQuality =
            selectedId
                ?.let { id -> variants.firstOrNull { it.id == id } }
                ?: currentHlsQuality?.let { current -> variants.firstOrNull { it.id == current.id } }
        _diagnostics =
            _diagnostics.copy(
                currentHlsQuality = currentHlsQuality,
                bitrate = currentHlsQuality?.bitrate,
            )
    }

    private fun clearHlsQualityState() {
        applyHlsQualityCallback = null
        _availableHlsQualities.clear()
        _currentHlsQuality = null
        _hlsQualityMode = HlsQualityMode.AUTO
    }

    private fun requiresExplicitWebDolbyVisionBridge(): Boolean =
        playbackOptions.dolbyVisionPolicy == DolbyVisionPolicy.CONVERT_PROFILE_7_TO_8_1

    private suspend fun prepareExplicitWebDolbyVisionSource(
        uri: String,
        requestHeaders: Map<String, String>,
        sessionId: Long,
    ) {
        val assumedSource =
            VideoColorInfo(
                dynamicRange = VideoDynamicRange.DOLBY_VISION,
                bitDepth = 10,
                primaries = VideoColorPrimaries.BT2020,
                transfer = VideoColorTransfer.PQ,
                matrix = VideoColorMatrix.BT2020_NCL,
                range = VideoColorRange.LIMITED,
                dolbyVision =
                    DolbyVisionInfo(
                        profile = DOLBY_VISION_PROFILE_7,
                        hasRpu = true,
                        enhancementLayer = DolbyVisionEnhancementLayer.UNKNOWN,
                        hasHdr10CompatibleBaseLayer = true,
                    ),
            )
        val preparation =
            playbackOptions.prepareSourceWithExtensions(
                VideoPipelineSourceRequest(
                    uri = uri,
                    requestHeaders = requestHeaders,
                    source = assumedSource,
                    dynamicRangePolicy = playbackOptions.dynamicRangePolicy,
                    dolbyVisionPolicy = playbackOptions.dolbyVisionPolicy,
                ),
            )
        if (!isCurrentMediaSession(sessionId)) {
            (preparation as? VideoPipelineSourcePreparation.Ready)?.source?.close()
            return
        }
        when (preparation) {
            is VideoPipelineSourcePreparation.Ready -> {
                val webSource = preparation.source as? WebPreparedVideoPipelineSource
                if (webSource == null) {
                    preparation.source.close()
                    rejectExplicitWebDolbyVisionSource(
                        "The installed converter did not provide a browser MediaSource transport.",
                    )
                    return
                }
                preparedPipelineSource = webSource
                preparedPipelineOriginalColorInfo = assumedSource
                _requestHeaders = webSource.requestHeaders
                colorPipelineController.updateCapabilities(capabilities)
                colorPipelineController.updateSource(
                    source = assumedSource,
                    decoderInput = webSource.outputColorInfo,
                    appliedDolbyVisionProfileMapping =
                        assumedSource.profile7To81MappingOrNull(webSource.outputColorInfo),
                    decoderName = "libdovi Profile 7 to 8.1 MediaSource bridge -> browser decoder",
                    decoderCapabilities =
                        DecoderColorCapabilities(
                            isKnown = true,
                            supportedDynamicRanges = setOf(webSource.outputColorInfo.dynamicRange),
                            maxBitDepth = webSource.outputColorInfo.bitDepth,
                            supportedDolbyVisionProfiles =
                                webSource.outputColorInfo.dolbyVision
                                    ?.profile
                                    ?.let { setOf(it) }
                                    .orEmpty(),
                            isDolbyVisionProfileSupportKnown = true,
                        ),
                )
                _sourceUri = webSource.uri
                refreshWebColorPipelineOutput()
            }
            is VideoPipelineSourcePreparation.Rejected -> {
                _isPlaying = false
                _isLoading = false
                setError(VideoPlayerError.ColorPipelineError(preparation.reason, preparation.detail))
            }
            VideoPipelineSourcePreparation.NotApplicable ->
                rejectExplicitWebDolbyVisionSource(
                    "No installed browser source extension can perform the requested Profile 7 conversion.",
                )
        }
    }

    private fun rejectExplicitWebDolbyVisionSource(detail: String) {
        _sourceUri = null
        _isPlaying = false
        _isLoading = false
        setError(
            VideoPlayerError.ColorPipelineError(
                ColorPipelineFallbackReason.DOLBY_VISION_CONVERTER_UNAVAILABLE,
                detail,
            ),
        )
    }

    /**
     * Opens a media source from the given URI.
     *
     * @param uri The URI of the media to open
     * @param initializePlayerState Controls whether playback should start automatically after opening
     */
    override fun openUri(
        uri: String,
        initializePlayerState: InitialPlayerState,
        requestHeaders: Map<String, String>,
    ) {
        openWebSource(
            uri = uri,
            initializePlayerState = initializePlayerState,
            requestHeaders = requestHeaders,
            sourceFile = null,
        )
    }

    private fun openWebSource(
        uri: String,
        initializePlayerState: InitialPlayerState,
        requestHeaders: Map<String, String>,
        sourceFile: PlatformFile?,
    ) {
        checkNotDisposed()
        playerScope.coroutineContext.cancelChildren()
        closePreparedPipelineSource()
        val previousSessionId = mediaSessionId
        val hadPreviousSource = _hasMedia || _sourceUri != null
        val sessionId = nextMediaSessionId()
        val sanitizedHeaders = requestHeaders.sanitizedRequestHeaders()

        _sourceFile = sourceFile
        _sourceUri = uri
        _requestHeaders = sanitizedHeaders
        resetProjectionForSource(uri)
        val playbackDecision = webPlaybackDecision
        val requiresDolbyVisionBridge =
            playbackDecision.route == WebPlaybackRoute.LEGACY &&
                requiresExplicitWebDolbyVisionBridge()
        if (requiresDolbyVisionBridge) {
            _sourceUri = null
        }
        _hasMedia = true
        _isLoading = true // Set initial loading state
        _error = null
        _isPlaying = initializePlayerState == InitialPlayerState.PLAY
        seekingState = false
        seekEventActive = false
        stallEventActive = false
        sourceLoadedForSession = false
        suppressNextSeekEvents = false
        clearPendingSeekRequest()
        _sliderPos = 0f
        _positionText = "00:00"
        _durationText = "00:00"
        _currentTime = Duration.ZERO
        _currentDuration = Duration.ZERO
        _bufferedRanges.clear()
        _diagnostics = PlaybackDiagnostics()
        _aspectRatio = DEFAULT_ASPECT_RATIO
        colorPipelineController.resetSource()
        resetWebColorRendererRuntime(resetHdrFailure = true)
        clearHlsQualityState()
        resetSourceTracks()
        clearWebMediaChapters()
        clearMetadata()
        when (playbackDecision.route) {
            WebPlaybackRoute.MOVI,
            WebPlaybackRoute.MOVI_DRM,
            -> {
                renderingInfo.update(
                    backend = MOVI_RENDERING_BACKEND,
                    container = null,
                    videoDecoder = "Movi auto decoder",
                    videoRenderer =
                        if (playbackDecision.route == WebPlaybackRoute.MOVI_DRM) {
                            "Movi/Shaka HTMLVideoElement"
                        } else {
                            "Movi canvas"
                        },
                    audioRenderer = "Movi audio renderer",
                    subtitleRenderer = "Movi embedded subtitle renderer",
                    subtitleSource = null,
                    videoProjection = projection.renderingInfoLabel(),
                    notes = null,
                )
            }
            WebPlaybackRoute.LEGACY -> {
                renderingInfo.update(
                    backend = LEGACY_RENDERING_BACKEND,
                    container = null,
                    videoDecoder = "Browser native decoder",
                    videoRenderer = "HTMLVideoElement",
                    audioRenderer = "Browser native audio",
                    subtitleRenderer = null,
                    subtitleSource = null,
                    videoProjection = projection.renderingInfoLabel(),
                    notes = null,
                )
            }
            WebPlaybackRoute.REJECTED -> {
                renderingInfo.update(
                    backend = MOVI_RENDERING_BACKEND,
                    container = null,
                    videoDecoder = null,
                    videoRenderer = null,
                    audioRenderer = null,
                    subtitleRenderer = null,
                    subtitleSource = null,
                    videoProjection = projection.renderingInfoLabel(),
                    notes = "Playback route rejected before initialization.",
                )
            }
        }
        if (hadPreviousSource) {
            emitSourceReleasedForSession(previousSessionId)
        }
        emitPlaybackEventForSession(sessionId) { eventSessionId, sampledAtMs ->
            PlaybackEvent.SourcePreparing(
                mediaSessionId = eventSessionId,
                sampledAtMs = sampledAtMs,
                uri = uri,
            )
        }

        playbackDecision.error?.let { routeError ->
            _isPlaying = false
            _isLoading = false
            setError(routeError)
            return
        }

        // Don't set isLoading to false here - let the video events handle it
        playerScope.launch {
            try {
                if (requiresDolbyVisionBridge) {
                    prepareExplicitWebDolbyVisionSource(
                        uri = uri,
                        requestHeaders = sanitizedHeaders,
                        sessionId = sessionId,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                if (isCurrentMediaSession(sessionId)) {
                    _isLoading = false
                    setError(
                        when (e) {
                            is IOException -> VideoPlayerError.NetworkError(e.message ?: "Network error")
                            else -> VideoPlayerError.UnknownError(e.message ?: "Unknown error")
                        },
                    )
                }
            }
        }
    }

    /**
     * Opens a media file.
     *
     * @param file The file to open
     * @param initializePlayerState Controls whether playback should start automatically after opening
     */
    override fun openFile(
        file: PlatformFile,
        initializePlayerState: InitialPlayerState,
    ) {
        checkNotDisposed()
        val fileUri = file.getUri()
        openWebSource(
            uri = fileUri,
            initializePlayerState = initializePlayerState,
            requestHeaders = emptyMap(),
            sourceFile = file,
        )
    }

    override fun openAsset(
        fileName: String,
        initializePlayerState: InitialPlayerState,
    ) {
        checkNotDisposed()
        throw UnsupportedOperationException("openAsset is not supported on this platform")
    }

    internal fun updateAutoDetectedProjectionFromMetadata() {
        if (!projectionAutoDetectionEnabled) return
        applyProjectionSettings(
            playbackOptions.detectProjectionForSource(
                uri = sourceUri.orEmpty(),
                title = metadata.title,
                metadata = listOfNotNull(metadata.mimeType),
                videoSizes =
                    listOfNotNull(
                        metadata.width?.let { width ->
                            metadata.height?.let { height ->
                                VideoProjectionVideoSize(width, height)
                            }
                        },
                    ),
            ),
        )
    }

    private fun resetProjectionForSource(uri: String) {
        projectionAutoDetectionEnabled = playbackOptions.usesAutoProjectionDetection()
        applyProjectionSettings(playbackOptions.detectProjectionForSource(uri))
    }

    private fun applyProjectionSettings(value: VideoProjectionSettings) {
        _projection = value.normalized()
        renderingInfo.videoProjection = projection.renderingInfoLabel()
    }

    /**
     * Starts or resumes playback of the current media.
     */
    override fun play() {
        checkNotDisposed()
        if (_hasMedia && !_isPlaying) {
            _isPlaying = true
            applyPlaybackCallback?.invoke(true)
        }
    }

    override fun restart() {
        checkNotDisposed()
        val hadSource = _hasMedia || _sourceUri != null
        if (!hadSource) return
        seekTo(Duration.ZERO)
        play()
        emitPlaybackEvent { sessionId, sampledAtMs ->
            PlaybackEvent.PlaybackRestarted(
                mediaSessionId = sessionId,
                sampledAtMs = sampledAtMs,
            )
        }
    }

    /**
     * Pauses playback of the current media.
     */
    override fun pause() {
        checkNotDisposed()
        if (_isPlaying) {
            _isPlaying = false
            applyPlaybackCallback?.invoke(false)
        }
    }

    /**
     * Stops playback and resets the position while keeping the current source reusable.
     */
    override fun stop() {
        checkNotDisposed()
        suppressNextSeekEvents = preciseCurrentTime > Duration.ZERO
        _isPlaying = false
        _isLoading = false
        sliderPos = 0.0f
        _positionText = "00:00"
        _durationText = "00:00"
        _currentTime = Duration.ZERO
        _currentDuration = Duration.ZERO
        _bufferedRanges.clear()
        seekingState = false
        seekEventActive = false
        stallEventActive = false
        clearPendingSeekRequest()
        resetPlaybackCallback?.invoke()
    }

    override fun releaseSource() {
        checkNotDisposed()
        val releasedSessionId = mediaSessionId
        val hadSource = _hasMedia || _sourceUri != null
        playerScope.coroutineContext.cancelChildren()
        closePreparedPipelineSource()
        _isPlaying = false
        _sourceUri = null
        _sourceFile = null
        _hasMedia = false
        _isLoading = false
        sliderPos = 0f
        _positionText = "00:00"
        _durationText = "00:00"
        _currentTime = Duration.ZERO
        _currentDuration = Duration.ZERO
        _bufferedRanges.clear()
        _diagnostics = PlaybackDiagnostics()
        seekingState = false
        seekEventActive = false
        stallEventActive = false
        sourceLoadedForSession = false
        suppressNextSeekEvents = false
        clearPendingSeekRequest()
        clearHlsQualityState()
        resetSourceTracks()
        clearWebMediaChapters()
        clearMetadata()
        resetPlaybackCallback?.invoke()
        _requestHeaders = emptyMap()
        colorPipelineController.resetSource()
        resetWebColorRendererRuntime(resetHdrFailure = true)
        if (hadSource) {
            emitSourceReleasedForSession(releasedSessionId)
            nextMediaSessionId()
        }
    }

    /**
     * Seeks to a specific position in the media.
     *
     * @param time The absolute media position to seek to.
     */
    override fun seekTo(time: Duration) {
        checkNotDisposed()
        val targetTime =
            when {
                time < Duration.ZERO -> Duration.ZERO
                duration > Duration.ZERO && time > duration -> duration
                else -> time
            }
        pendingSeekRequest = true
        pendingSeekTime = targetTime
        seekRequestId++
        if (duration > Duration.ZERO) {
            sliderPos =
                (targetTime.toSecondsDouble() / duration.toSecondsDouble() * PERCENTAGE_MULTIPLIER)
                    .toFloat()
                    .coerceIn(0f, PERCENTAGE_MULTIPLIER)
        }
        seekJob?.cancel()
        beginSeekEvent(targetTime)
    }

    override fun seekToProgress(progress: Float) {
        checkNotDisposed()
        val safeProgress = progress.coerceIn(0f, 1f)
        sliderPos = safeProgress * PERCENTAGE_MULTIPLIER
        val targetTime = duration.takeIf { it > Duration.ZERO }?.let { it * safeProgress.toDouble() }
        pendingSeekRequest = true
        pendingSeekTime = targetTime
        seekRequestId++
        seekJob?.cancel()
        targetTime?.let(::beginSeekEvent)
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun seekTo(value: Float) {
        checkNotDisposed()
        val targetTime =
            duration.takeIf { it > Duration.ZERO }?.let {
                it * (value / PERCENTAGE_MULTIPLIER).toDouble().coerceIn(0.0, 1.0)
            }
        pendingSeekRequest = true
        pendingSeekTime = targetTime
        seekRequestId++
        sliderPos = value
        seekJob?.cancel()
        targetTime?.let(::beginSeekEvent)
    }

    internal fun consumePendingSeekTime(videoDuration: Duration): Duration? {
        if (!pendingSeekRequest) return null
        val requestedTime =
            pendingSeekTime
                ?: videoDuration.takeIf { it > Duration.ZERO }?.let {
                    it * (sliderPos / PERCENTAGE_MULTIPLIER).toDouble().coerceIn(0.0, 1.0)
                }
        pendingSeekRequest = false
        pendingSeekTime = null
        return requestedTime
    }

    internal fun hasPendingSeekRequest(): Boolean = pendingSeekRequest

    private fun clearPendingSeekRequest() {
        pendingSeekRequest = false
        pendingSeekTime = null
        seekJob?.cancel()
        seekJob = null
    }

    /**
     * Clears any error state.
     */
    override fun clearError() {
        checkNotDisposed()
        _error = null
    }

    override fun canPlaySource(
        uri: String,
        mimeType: String?,
    ): Boolean {
        checkNotDisposed()
        return canPlayWebSource(
            uri = uri,
            mimeType = mimeType,
            capabilities = capabilities,
            useMovi = playbackOptions.webPlaybackEngine == WebPlaybackEngine.MOVI,
        )
    }

    /**
     * Toggles the fullscreen state of the video player
     */
    override fun toggleFullscreen() {
        checkNotDisposed()
        FullscreenManager.toggleFullscreen(isFullscreen) { newFullscreenState ->
            if (!disposed) {
                _isFullscreen = newFullscreenState
                refreshWebColorPipelineOutput()
            }
        }
    }

    override suspend fun enterPip(): PipResult {
        checkNotDisposed()
        return PipResult.NotSupported
    }

    override fun clearCache(): CacheClearResult {
        checkNotDisposed()
        return CacheClearResult.NotSupported
    }

    /**
     * Sets the error state.
     *
     * @param error The error to set
     */
    fun setError(error: VideoPlayerError) {
        checkNotDisposed()
        _error = error
        emitPlaybackEvent { sessionId, sampledAtMs ->
            PlaybackEvent.Error(
                mediaSessionId = sessionId,
                sampledAtMs = sampledAtMs,
                error = error,
            )
        }
    }

    internal fun updateBufferedRanges(ranges: List<BufferedRange>) {
        _bufferedRanges.clear()
        _bufferedRanges.addAll(ranges)
        _diagnostics = _diagnostics.copy(bufferedRanges = ranges)
    }

    internal fun updateBufferedRanges(rows: String) {
        updateBufferedRanges(
            rows
                .lineSequence()
                .filter { it.isNotBlank() }
                .mapNotNull { row -> row.toBufferedRangeOrNull() }
                .toList(),
        )
    }

    internal fun updateDiagnostics(diagnostics: PlaybackDiagnostics) {
        _diagnostics =
            diagnostics.copy(
                currentHlsQuality = diagnostics.currentHlsQuality ?: currentHlsQuality,
                bufferedRanges = diagnostics.bufferedRanges.ifEmpty { bufferedRanges },
            )
    }

    internal fun updateAspectRatio(value: Float) {
        if (value > 0f && !value.isNaN() && value.isFinite()) {
            _aspectRatio = value
        }
    }

    /**
     * Updates current media time immediately and throttles only display-related state.
     *
     * @param currentTime The current playback position.
     * @param duration The total duration of the media.
     * @param forceUpdate If true, bypasses the display rate limiting check (useful for tests)
     */
    fun updatePosition(
        currentTime: Duration,
        duration: Duration,
        forceUpdate: Boolean = false,
    ) {
        checkNotDisposed()
        val durationChanged = _currentDuration != duration
        _currentTime = currentTime
        _currentDuration = duration
        if (durationChanged && (webTextTrackChapterRows.isNotEmpty() || webHlsChapterRows.isNotEmpty())) {
            publishWebMediaChapters()
        }

        val now = TimeSource.Monotonic.markNow()
        if (forceUpdate || now - lastUpdateTime >= 250.milliseconds) {
            _positionText = formatTime(currentTime)
            _durationText = formatTime(duration)

            if (!userDragging && duration > Duration.ZERO && !_isLoading) {
                sliderPos =
                    (currentTime.toSecondsDouble() / duration.toSecondsDouble() * PERCENTAGE_MULTIPLIER)
                        .toFloat()
            }
            lastUpdateTime = now
        }
    }

    /**
     * Callback for time update events from the media player.
     *
     * @param currentTime The current playback position.
     * @param duration The total duration of the media.
     * @param forceUpdate If true, bypasses the display rate limiting check (useful for tests)
     */
    fun onTimeUpdate(
        currentTime: Duration,
        duration: Duration,
        forceUpdate: Boolean = false,
    ) {
        checkNotDisposed()
        updatePosition(currentTime, duration, forceUpdate)
    }

    /**
     * Disposes of resources used by the player.
     */
    override fun dispose() {
        if (disposed) return
        val releasedSessionId = mediaSessionId
        val hadMedia = _hasMedia || _sourceUri != null
        disposed = true
        preciseCurrentTimeProvider = null
        durationProvider = null
        positionRecalculationCallback = null
        applyVolumeCallback = null
        applyPlaybackSpeedCallback = null
        applyPlaybackCallback = null
        applyAudioTrackCallback = null
        applyAudioTrackSelectionCallback = null
        applySubtitleTrackCallback = null
        resetPlaybackCallback = null
        playbackEndedCallback = null
        restartCallback = null
        clearHlsQualityState()
        closePreparedPipelineSource()
        _sourceUri = null
        _sourceFile = null
        _hasMedia = false
        _isPlaying = false
        _isLoading = false
        _sliderPos = 0f
        _userDragging = false
        _isFullscreen = false
        _positionText = "00:00"
        _durationText = "00:00"
        _currentTime = Duration.ZERO
        _currentDuration = Duration.ZERO
        _bufferedRanges.clear()
        _diagnostics = PlaybackDiagnostics()
        clearWebMediaChapters()
        seekingState = false
        seekEventActive = false
        stallEventActive = false
        sourceLoadedForSession = false
        suppressNextSeekEvents = false
        clearPendingSeekRequest()
        pendingVolumeChange?.cancel()
        pendingSpeedChange?.cancel()
        if (hadMedia) {
            emitSourceReleasedForSession(releasedSessionId)
        }
        _requestHeaders = emptyMap()
        clearAllTracks()
        clearMetadata()
        colorPipelineController.resetSource()
        resetWebColorRendererRuntime(resetHdrFailure = true)
        webControlledColorSurfaceActive = false
        webProjectionSurfaceActive = false
        nextMediaSessionId()
        playerScope.cancel()
    }

    companion object {
        internal const val PERCENTAGE_MULTIPLIER = 1000f
    }
}

private fun String.toBufferedRangeOrNull(): BufferedRange? {
    val parts = split('|')
    val start =
        parts
            .getOrNull(0)
            ?.toDoubleOrNull()
            ?.takeIf { it.isFinite() && it >= 0.0 }
            ?: return null
    val end =
        parts
            .getOrNull(1)
            ?.toDoubleOrNull()
            ?.takeIf { it.isFinite() && it >= start }
            ?: return null
    return BufferedRange(start = start.secondsAsDuration(), end = end.secondsAsDuration())
}

private const val MATROSKA_MIME_TYPE = "video/x-matroska"
private const val DEFAULT_ASPECT_RATIO = 16f / 9f
private const val DOLBY_VISION_PROFILE_7 = 7
private const val MOVI_AUTO_VIDEO_TRACK_ID = -1
private const val BITS_PER_KILOBIT = 1000
internal const val MOVI_RENDERING_BACKEND = "MoviPlayer 0.3.5"
internal const val LEGACY_RENDERING_BACKEND = "HTML5 video (legacy)"
private val WEB_SUPPORTED_URI_SCHEMES = setOf("blob", "data", "http", "https")
private val MOVI_MEDIA_EXTENSIONS =
    setOf("mp4", "m4v", "webm", "mkv", "avi", "ts", "m2ts", "mov", "m3u8", "mpd", "ism")
private val MOVI_MEDIA_MIME_TYPES =
    setOf(
        "video/mp4",
        "video/webm",
        "video/x-matroska",
        "video/matroska",
        "video/x-msvideo",
        "video/mp2t",
        "application/vnd.apple.mpegurl",
        "application/x-mpegurl",
        "application/dash+xml",
        "application/vnd.ms-sstr+xml",
    )

private val VideoColorInfo.webCompatibleHdrCanvasOutput: VideoDynamicRange?
    get() =
        when (dynamicRange) {
            VideoDynamicRange.HDR10, VideoDynamicRange.HDR10_PLUS -> VideoDynamicRange.HDR10
            VideoDynamicRange.HLG -> VideoDynamicRange.HLG
            else -> null
        }

private val VideoDynamicRange.isHdrOutput: Boolean
    get() = this == VideoDynamicRange.HDR10 || this == VideoDynamicRange.HLG

private fun canPlayWebSource(
    uri: String,
    mimeType: String?,
    capabilities: PlayerCapabilities,
    useMovi: Boolean,
): Boolean {
    if (!capabilities.canPlaySource(uri = uri, mimeType = mimeType)) return false

    val normalizedMimeType = mimeType?.substringBefore(';')?.trim()?.lowercase()
    val cleanUri = uri.substringBefore('?').substringBefore('#').lowercase()
    if (useMovi && (normalizedMimeType in MOVI_MEDIA_MIME_TYPES || cleanUri.hasMoviMediaExtension())) {
        return true
    }
    if (normalizedMimeType.isHlsMimeType() || cleanUri.endsWith(".m3u8")) return true
    if (normalizedMimeType != null) return canPlayWebMimeType(normalizedMimeType)

    val extension = cleanUri.substringAfterLast('.', "")
    val inferredMimeType =
        when (extension.lowercase()) {
            "mp4" -> "video/mp4"
            "webm" -> "video/webm"
            "ogg", "ogv" -> "video/ogg"
            "mov" -> "video/quicktime"
            "mkv" -> MATROSKA_MIME_TYPE
            "m3u8" -> "application/vnd.apple.mpegurl"
            else -> null
        }
    return inferredMimeType?.let(::canPlayWebMimeType) ?: true
}

private fun String.hasMoviMediaExtension(): Boolean = MOVI_MEDIA_EXTENSIONS.any { extension -> endsWith(".$extension") }

@Suppress("UNUSED_PARAMETER")
private fun canPlayWebMimeType(mimeType: String): Boolean =
    js(
        """
        (function() {
            const video = document.createElement("video");
            if (!video || typeof video.canPlayType !== "function") return true;
            const value = video.canPlayType(mimeType);
            return value === "probably" || value === "maybe";
        })()
        """,
    )

private fun isWebPictureInPictureSupported(): Boolean =
    js("""!!(document.pictureInPictureEnabled || document.webkitPictureInPictureEnabled)""")

private fun String?.isHlsMimeType(): Boolean =
    this == "application/vnd.apple.mpegurl" ||
        this == "application/x-mpegurl" ||
        this == "audio/mpegurl" ||
        this == "audio/x-mpegurl"
