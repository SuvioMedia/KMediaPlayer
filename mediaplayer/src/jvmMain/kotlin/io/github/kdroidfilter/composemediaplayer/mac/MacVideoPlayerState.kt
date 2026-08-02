package io.github.kdroidfilter.composemediaplayer.mac

import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import io.github.kdroidfilter.composemediaplayer.AudioTrack
import io.github.kdroidfilter.composemediaplayer.ColorPipelineFallbackReason
import io.github.kdroidfilter.composemediaplayer.ColorPipelineVerification
import io.github.kdroidfilter.composemediaplayer.DecoderColorCapabilities
import io.github.kdroidfilter.composemediaplayer.DesktopMediaSourcePolicy
import io.github.kdroidfilter.composemediaplayer.DesktopPlayerLifecycle
import io.github.kdroidfilter.composemediaplayer.DesktopSubtitleFont
import io.github.kdroidfilter.composemediaplayer.DesktopSubtitlePipelineExtension
import io.github.kdroidfilter.composemediaplayer.DesktopSubtitleRenderer
import io.github.kdroidfilter.composemediaplayer.DesktopVideoBackend
import io.github.kdroidfilter.composemediaplayer.DesktopVideoSurfaceMode
import io.github.kdroidfilter.composemediaplayer.DisplayColorCapabilities
import io.github.kdroidfilter.composemediaplayer.DolbyVisionPolicy
import io.github.kdroidfilter.composemediaplayer.DolbyVisionProfileMapping
import io.github.kdroidfilter.composemediaplayer.DynamicMetadataHandling
import io.github.kdroidfilter.composemediaplayer.DynamicRangePolicy
import io.github.kdroidfilter.composemediaplayer.EXTERNAL_FFMPEG_AUDIO_TRACK_ID_PREFIX
import io.github.kdroidfilter.composemediaplayer.EXTERNAL_FFMPEG_SUBTITLE_TRACK_ID_PREFIX
import io.github.kdroidfilter.composemediaplayer.EXTERNAL_VLC_AUDIO_TRACK_ID_PREFIX
import io.github.kdroidfilter.composemediaplayer.EXTERNAL_VLC_SUBTITLE_TRACK_ID_PREFIX
import io.github.kdroidfilter.composemediaplayer.ExternalHlsFallbackBackend
import io.github.kdroidfilter.composemediaplayer.ExternalHlsFallbackSupport
import io.github.kdroidfilter.composemediaplayer.ExternalVlcLocator
import io.github.kdroidfilter.composemediaplayer.HlsFallbackSource
import io.github.kdroidfilter.composemediaplayer.InitialPlayerState
import io.github.kdroidfilter.composemediaplayer.JvmExternalFallbackContainerSupport
import io.github.kdroidfilter.composemediaplayer.JvmLegacyVideoContainerSupport
import io.github.kdroidfilter.composemediaplayer.JvmLibVlcAudioStream
import io.github.kdroidfilter.composemediaplayer.JvmLibVlcInstallation
import io.github.kdroidfilter.composemediaplayer.JvmLibVlcMediaProbe
import io.github.kdroidfilter.composemediaplayer.JvmLibVlcSubtitleStream
import io.github.kdroidfilter.composemediaplayer.JvmLibVlcTrackInfo
import io.github.kdroidfilter.composemediaplayer.LIBVLC_CANVAS_AUDIO_TRACK_ID_PREFIX
import io.github.kdroidfilter.composemediaplayer.LIBVLC_CANVAS_SUBTITLE_TRACK_ID_PREFIX
import io.github.kdroidfilter.composemediaplayer.MediaChapter
import io.github.kdroidfilter.composemediaplayer.PlaybackDiagnostics
import io.github.kdroidfilter.composemediaplayer.PlayerCapabilities
import io.github.kdroidfilter.composemediaplayer.PreparedVideoPipelineSource
import io.github.kdroidfilter.composemediaplayer.RendererColorCapabilities
import io.github.kdroidfilter.composemediaplayer.SubtitleFormat
import io.github.kdroidfilter.composemediaplayer.SubtitleTrack
import io.github.kdroidfilter.composemediaplayer.TrackSelectionResult
import io.github.kdroidfilter.composemediaplayer.VideoColorInfo
import io.github.kdroidfilter.composemediaplayer.VideoColorPipelineController
import io.github.kdroidfilter.composemediaplayer.VideoColorPipelineStatus
import io.github.kdroidfilter.composemediaplayer.VideoDynamicRange
import io.github.kdroidfilter.composemediaplayer.VideoMetadata
import io.github.kdroidfilter.composemediaplayer.VideoPipelineSourcePreparation
import io.github.kdroidfilter.composemediaplayer.VideoPipelineSourceRequest
import io.github.kdroidfilter.composemediaplayer.VideoPlaybackOptions
import io.github.kdroidfilter.composemediaplayer.VideoPlayerError
import io.github.kdroidfilter.composemediaplayer.VideoPlayerState
import io.github.kdroidfilter.composemediaplayer.VideoProjectionSettings
import io.github.kdroidfilter.composemediaplayer.VideoProjectionViewControlMode
import io.github.kdroidfilter.composemediaplayer.VideoProjectionViewSettings
import io.github.kdroidfilter.composemediaplayer.VideoRenderingInfo
import io.github.kdroidfilter.composemediaplayer.VideoSourcePipelineExtension
import io.github.kdroidfilter.composemediaplayer.VideoSurfaceKind
import io.github.kdroidfilter.composemediaplayer.VideoTextureCrop
import io.github.kdroidfilter.composemediaplayer.allowsExternalSourceAdapter
import io.github.kdroidfilter.composemediaplayer.audioTrackSelectionResult
import io.github.kdroidfilter.composemediaplayer.explicitFallbackBackend
import io.github.kdroidfilter.composemediaplayer.isSafeForUnmanagedSdrFallback
import io.github.kdroidfilter.composemediaplayer.jvmCanvasRendererLabel
import io.github.kdroidfilter.composemediaplayer.jvmPlayerCapabilities
import io.github.kdroidfilter.composemediaplayer.normalizeUnixLocalFileUriForPlayback
import io.github.kdroidfilter.composemediaplayer.prepareSourceWithExtensions
import io.github.kdroidfilter.composemediaplayer.profile7To81MappingOrNull
import io.github.kdroidfilter.composemediaplayer.renderingInfoLabel
import io.github.kdroidfilter.composemediaplayer.requestHeadersJsonObjectString
import io.github.kdroidfilter.composemediaplayer.requestHeadersLineString
import io.github.kdroidfilter.composemediaplayer.sanitizedRequestHeaders
import io.github.kdroidfilter.composemediaplayer.subtitle.loadSubtitleContent
import io.github.kdroidfilter.composemediaplayer.subtitleTrackSelectionResult
import io.github.kdroidfilter.composemediaplayer.toConfirmedDecoderCapabilities
import io.github.kdroidfilter.composemediaplayer.usesJvmCanvasProjectionRenderer
import io.github.kdroidfilter.composemediaplayer.util.TaggedLogger
import io.github.kdroidfilter.composemediaplayer.util.formatTime
import io.github.kdroidfilter.composemediaplayer.util.secondsAsDuration
import io.github.vinceglb.filekit.PlatformFile
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import java.awt.Component
import java.awt.Window
import java.io.Closeable
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

internal val macLogger = TaggedLogger("MacVideoPlayerState")

private const val SKIA_FRAME_BUFFER_COUNT = 3
private const val PENDING_BITMAP_CLOSE_GRACE_FRAMES = 4
private const val BGRA_BYTES_PER_PIXEL = 4
private const val ASPECT_RATIO_CHANGE_EPSILON = 0.001f
private const val PAUSED_SEEK_FRAME_ATTEMPTS = 10
private const val PAUSED_SEEK_FRAME_RETRY_DELAY_MS = 25L

private data class MacResolvedLibVlcBackend(
    val installation: JvmLibVlcInstallation,
)

/** AVI/ASF need either a decoder backend or the KMediaBridge AVC/AAC compatibility pipeline. */
internal fun resolveMacConfiguredFallbackBackend(
    configured: String,
    isLegacyContainer: Boolean,
): String {
    val normalized = configured.trim().lowercase()
    if (!isLegacyContainer) return normalized
    return when (normalized) {
        "platform", "avfoundation",
        "libvlc",
        "libvlc-native-view", "libvlc-native", "libvlc-view", "libvlc-nsview",
        "ffmpeg", "kmediabridge", "bridge", "vlc",
        -> normalized
        else -> "auto"
    }
}

internal fun shouldUseMacLibVlcCandidate(
    sourceColorInfo: VideoColorInfo,
    explicitlyRequested: Boolean,
): Boolean = explicitlyRequested || sourceColorInfo.isSafeForUnmanagedSdrFallback()

internal fun visibleLibVlcFrameDimension(
    decodedDimension: Int,
    probedDimension: Int?,
): Int = probedDimension?.takeIf { it in 1..decodedDimension } ?: decodedDimension

internal fun shouldUseMacNativeAvFoundationPresentation(
    surfaceMode: DesktopVideoSurfaceMode,
    dynamicRangePolicy: DynamicRangePolicy,
    usesMetalProjection: Boolean,
    sourceAlreadyConvertedForAvFoundation: Boolean,
): Boolean =
    surfaceMode == DesktopVideoSurfaceMode.PREFER_NATIVE &&
        (
            dynamicRangePolicy != DynamicRangePolicy.FORCE_SDR ||
                usesMetalProjection ||
                sourceAlreadyConvertedForAvFoundation
        )

private fun DesktopVideoBackend.requestsLibVlcExplicitly(): Boolean =
    this == DesktopVideoBackend.LIBVLC || this == DesktopVideoBackend.LIBVLC_NATIVE

private fun DesktopVideoBackend.forcedMacFallbackBackend(): String? =
    when (this) {
        // Preserve the public legacy enum value while removing the duplicate frame-copy backend
        // from the macOS runtime model.
        DesktopVideoBackend.LIBVLC -> "libvlc-native-view"
        DesktopVideoBackend.LIBVLC_NATIVE -> "libvlc-native-view"
        DesktopVideoBackend.PLATFORM -> "platform"
        DesktopVideoBackend.AUTO -> null
    }

private data class MacLibVlcRuntimeTrackDescription(
    val ordinal: Int,
    val label: String,
)

/**
 * MacVideoPlayerState handles the native Mac video player state.
 *
 * This implementation uses a native video player via MacNativeBridge.
 */
class MacVideoPlayerState(
    private val playbackOptions: VideoPlaybackOptions = VideoPlaybackOptions(),
) : VideoPlayerState {
    private val platformCapabilities = jvmPlayerCapabilities(playbackOptions)
    private val colorPipelineController = VideoColorPipelineController(playbackOptions, platformCapabilities)
    override val colorPipelineStatus: StateFlow<VideoColorPipelineStatus> = colorPipelineController.status
    private var activeDisplayColorCapabilities = DisplayColorCapabilities()
    private var activeDecoderColorCapabilities = DecoderColorCapabilities()
    private var activeSourceColorInfo = VideoColorInfo()
    private var colorOutputVerification = ColorPipelineVerification.NONE
    private var _projection by mutableStateOf(playbackOptions.projection.normalized())
    override var projection: VideoProjectionSettings
        get() = _projection
        set(value) {
            _projection = value.normalized()
            applyProjectionColorRoute()
            updateProjectionRenderingInfo()
        }
    private var _projectionView by mutableStateOf(playbackOptions.projectionView.normalized())
    override var projectionView: VideoProjectionViewSettings
        get() = _projectionView
        set(value) {
            _projectionView = value.normalized()
            configureNativeMetalProjection()
        }
    private var _projectionViewControlMode by mutableStateOf(playbackOptions.projectionViewControlMode)
    override var projectionViewControlMode: VideoProjectionViewControlMode
        get() = _projectionViewControlMode
        set(value) {
            _projectionViewControlMode = value
        }
    private var _projectionTextureCrop by mutableStateOf(playbackOptions.projectionTextureCrop.normalized())
    override var projectionTextureCrop: VideoTextureCrop
        get() = _projectionTextureCrop
        set(value) {
            _projectionTextureCrop = value.normalized()
            applyProjectionColorRoute()
            updateProjectionRenderingInfo()
        }

    // Main state variables
    // AtomicLong allows lock-free reads of the native pointer from the frame hot path
    private val playerPtrAtomic = AtomicLong(0L)
    private val playerPtr: Long get() = playerPtrAtomic.get()

    /** Serializes native-player replacement/destruction with AWT surface attachment. */
    private val nativeInstanceLock = Any()

    // Serial dispatcher for frame processing — ensures only one frame is processed at a time.
    private val frameDispatcher = Dispatchers.Default.limitedParallelism(1)

    private data class RenderedFrame(
        val bitmap: Bitmap,
        val imageBitmap: ImageBitmap,
        val displayAspectRatio: Float,
        val width: Int,
        val height: Int,
        val epoch: Long,
    )

    // A StateFlow is a one-slot conflated producer/consumer hand-off: slow UI consumers simply
    // skip stale frames instead of making frame capture wait for the main thread.
    private val _currentFrameState = MutableStateFlow<RenderedFrame?>(null)
    internal val currentFrameState: State<ImageBitmap?> = mutableStateOf(null)
    private val frameEpoch = AtomicLong(0L)

    // Three buffers are required because the UI can still be drawing one bitmap while another is
    // waiting in the conflated hand-off. The producer excludes both and writes to the third.
    private val skiaBitmaps = arrayOfNulls<Bitmap>(SKIA_FRAME_BUFFER_COUNT)
    private var skiaBitmapWidth: Int = 0
    private var skiaBitmapHeight: Int = 0
    private var nextSkiaBitmapIndex: Int = 0
    private var lastSentSkiaBitmap: Bitmap? = null
    private val displayedSkiaBitmap = AtomicReference<Bitmap?>(null)
    private val pendingUiSkiaBitmap = AtomicReference<Bitmap?>(null)

    @Volatile
    private var lastFrameHash: Int = Int.MIN_VALUE

    private data class PendingCloseBitmap(
        val bitmap: Bitmap,
        var framesLeft: Int,
    )

    private val pendingCloseBitmaps = ArrayDeque<PendingCloseBitmap>()

    // A seek/output resize must never race a native CVPixelBuffer read.
    private val videoReaderMutex = Mutex()

    // Surface display size (pixels) — used to scale native output resolution
    private var surfaceWidth = 0
    private var surfaceHeight = 0
    private val isResizing = AtomicBoolean(false)
    private var resizeJob: Job? = null
    private val resizeRequestToken = AtomicLong(0L)

    // Background worker threads and jobs
    private val ioScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val cleanupScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val lifecycle = DesktopPlayerLifecycle(ioScope, cleanupScope)
    private var playerScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var frameUpdateJob: Job? = null
    private var positionUpdateJob: Job? = null
    private var bufferingCheckJob: Job? = null
    private var uiUpdateJob: Job? = null
    private var ffmpegHlsFallback: Closeable? = null
    private var ffmpegHlsFallbackDurationSeconds: Double? = null
    private var ffmpegHlsInputColorInfo: VideoColorInfo? = null
    private var ffmpegHlsOutputColorInfo: VideoColorInfo? = null
    private var ffmpegHlsHdrCmafPassthrough: Boolean = false
    private var ffmpegHlsToneMappedHdrToSdr: Boolean = false
    private var ffmpegHlsAvFoundationCompatibleTranscode: Boolean = false
    private var preparedPipelineSource: PreparedVideoPipelineSource? = null
    private var preparedPipelineOriginalColorInfo: VideoColorInfo? = null
    private var ffmpegHlsSourceUri: String? = null
    private var ffmpegHlsSelectedAudioStreamIndex: Int? = null
    private var ffmpegHlsSelectedSubtitleStreamIndex: Int? = null
    private var ffmpegHlsPlaybackOffsetSeconds: Double = 0.0
    private var libVlcBackendActive: Boolean = false
    private var libVlcSourceUri: String? = null
    private var libVlcTrackInfo: JvmLibVlcTrackInfo? = null
    private var libVlcSelectedAudioStreamIndex: Int? = null
    private var libVlcSelectedSubtitleStreamIndex: Int? = null
    private var nativeBackendUsesLibVlc: Boolean = false
    internal var libVlcNativeSurfaceRequested: Boolean by mutableStateOf(false)
        private set
    private val libAssLock = Any()
    private val libAssSelectionToken = AtomicLong(0L)
    private val completeLibAssExtractionJob = AtomicReference<Job?>(null)
    private var libAssRenderer: DesktopSubtitleRenderer? = null
    private var libAssSubtitleKey: String? = null
    private var libAssSubtitleSource: String? = null
    private val dynamicRangePolicy: DynamicRangePolicy = playbackOptions.dynamicRangePolicy
    private val desktopVideoBackend: DesktopVideoBackend = playbackOptions.desktopVideoBackend
    private val nativeAvFoundationSurfaceRequested: Boolean =
        playbackOptions.desktopVideoSurfaceMode == DesktopVideoSurfaceMode.PREFER_NATIVE
    private val hdrMetalRequested: Boolean =
        nativeAvFoundationSurfaceRequested && dynamicRangePolicy != DynamicRangePolicy.FORCE_SDR
    private val hdrToneMappingRequested: Boolean =
        dynamicRangePolicy == DynamicRangePolicy.AUTO ||
            dynamicRangePolicy == DynamicRangePolicy.PREFER_HDR ||
            dynamicRangePolicy == DynamicRangePolicy.FORCE_SDR
    private var hdrMetalSurfaceAllowed: Boolean by mutableStateOf(false)
    private var nativeHdrSurfaceAttached: Boolean = false
    private val unavailableMetalProjectionHdrRanges = mutableSetOf<VideoDynamicRange>()
    private var metalProjectionFallbackDetail: String? = null
    private var metalProjectionRendererDisabled: Boolean = false
    private var lastMetalProjectionConfiguration: String? = null

    // State tracking
    private var lastFrameUpdateTime: Long = 0
    private var seekInProgress = false
    private var targetSeekTime: Double? = null
    private val seekCompletionToken = AtomicLong(0L)

    private sealed interface PendingSeekRequest {
        data class Time(
            val time: Duration,
        ) : PendingSeekRequest

        data class Slider(
            val value: Float,
        ) : PendingSeekRequest
    }

    private val pendingSeekRequest = AtomicReference<PendingSeekRequest?>(null)
    private val seekDrainActive = AtomicBoolean(false)
    private var videoFrameRate: Float = 0.0f
    private var screenRefreshRate: Float = 0.0f
    private var captureFrameRate: Float = 0.0f

    // UI State (Main thread)
    override var hasMedia: Boolean by mutableStateOf(false)
    override var isPlaying: Boolean by mutableStateOf(false)
    override var sliderPos: Float by mutableStateOf(0.0f)
    override var userDragging: Boolean by mutableStateOf(false)
    override var loop: Boolean by mutableStateOf(false)
    override var isLoading: Boolean by mutableStateOf(false)
    override val isSeeking: Boolean get() = seekInProgress
    override var onPlaybackEnded: (() -> Unit)? = null
    override var onRestart: (() -> Unit)? = null
    override var error: VideoPlayerError? by mutableStateOf(null)
    override var subtitlesEnabled: Boolean by mutableStateOf(false)
    override var currentSubtitleTrack: SubtitleTrack? by mutableStateOf(null)
    private val _availableSubtitleTracks = mutableStateListOf<SubtitleTrack>()
    override val availableSubtitleTracks: List<SubtitleTrack>
        get() = _availableSubtitleTracks
    override var currentAudioTrack: AudioTrack? by mutableStateOf(null)
    private val _availableAudioTracks = mutableStateListOf<AudioTrack>()
    override val availableAudioTracks: List<AudioTrack>
        get() = _availableAudioTracks
    override var subtitleTextStyle: TextStyle by mutableStateOf(
        TextStyle(
            color = Color.White,
            fontSize = 18.sp,
            fontWeight = FontWeight.Normal,
            textAlign = TextAlign.Center,
        ),
    )
    override var subtitleBackgroundColor: Color by mutableStateOf(Color.Black.copy(alpha = 0.5f))
    override var subtitleOffset: Duration by mutableStateOf(Duration.ZERO)
    override val metadata: VideoMetadata = VideoMetadata()
    override val capabilities: PlayerCapabilities
        get() =
            platformCapabilities.copy(
                decoderColorCapabilities = activeDecoderColorCapabilities,
                displayColorCapabilities = activeDisplayColorCapabilities,
            )
    override val renderingInfo: VideoRenderingInfo =
        VideoRenderingInfo(
            videoProjection = projection.renderingInfoLabel(),
        )
    override val diagnostics: PlaybackDiagnostics
        get() {
            val nativeDiagnostics =
                playerPtr
                    .takeIf { it != 0L }
                    ?.let { ptr ->
                        runCatching { MacNativeBridge.nGetPlaybackDiagnostics(ptr) }
                            .getOrNull()
                            ?.toMacNativePlaybackDiagnostics()
                    }
            return PlaybackDiagnostics(
                totalVideoFrames = nativeDiagnostics?.totalFrames,
                renderedVideoFrames = nativeDiagnostics?.renderedFrames,
                droppedVideoFrames = nativeDiagnostics?.droppedFrames,
                maximumAvSyncOffsetMs = nativeDiagnostics?.maximumAvSyncOffsetMs,
                videoWidth = metadata.width?.takeIf { it > 0 },
                videoHeight = metadata.height?.takeIf { it > 0 },
                bitrate = metadata.bitrate?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt(),
                currentHlsQuality = currentHlsQuality,
                bufferedRanges = bufferedRanges,
                notes = renderingInfo.notes,
            )
        }
    private var _isFullscreen by mutableStateOf(false)
    override var isFullscreen: Boolean
        get() = _isFullscreen
        set(value) {
            lifecycle.ensureUsable()
            if (_isFullscreen == value) return
            _isFullscreen = value
            colorOutputVerification = ColorPipelineVerification.NONE
            refreshColorPipelineOutput(ColorPipelineVerification.NONE)
        }
    internal var usesLibAssSubtitleOverlay: Boolean by mutableStateOf(false)
        private set
    private var lastUri: String? = null
    private var lastRequestHeaders: Map<String, String> = emptyMap()

    // Non-blocking text properties
    private val _positionText = mutableStateOf("00:00")
    override val positionText: String get() = _positionText.value

    private val _durationText = mutableStateOf("00:00")
    override val durationText: String get() = _durationText.value

    private val _currentTime = mutableStateOf(Duration.ZERO)
    private val _duration = mutableStateOf(Duration.ZERO)
    private var _chapters by mutableStateOf(emptyList<MediaChapter>())
    override val currentTime: Duration get() = _currentTime.value
    override val preciseCurrentTime: Duration get() = _currentTime.value
    override val duration: Duration get() = _duration.value
    override val chapters: List<MediaChapter> get() = _chapters

    // Non-blocking aspect ratio property
    private val _aspectRatio = mutableStateOf(16f / 9f)
    override val aspectRatio: Float get() = _aspectRatio.value

    // Player settings
    // Volume variable is stored independently so it can always be modified.
    private val _volumeState = mutableStateOf(1.0f)
    override var volume: Float
        get() = _volumeState.value
        set(value) {
            lifecycle.ensureUsable()
            val newValue = value.coerceIn(0f, 1f)
            if (_volumeState.value != newValue) {
                _volumeState.value = newValue
                lifecycle.launchSourceBoundControlOperation {
                    applyVolume()
                }
            }
        }

    // Playback speed control
    private val _playbackSpeedState = mutableStateOf(1.0f)
    override var playbackSpeed: Float
        get() = _playbackSpeedState.value
        set(value) {
            lifecycle.ensureUsable()
            val newValue = value.coerceIn(VideoPlayerState.MIN_PLAYBACK_SPEED, VideoPlayerState.MAX_PLAYBACK_SPEED)
            if (_playbackSpeedState.value != newValue) {
                _playbackSpeedState.value = newValue
                lifecycle.launchSourceBoundControlOperation {
                    applyPlaybackSpeed()
                }
            }
        }

    private val updateInterval: Long
        get() =
            if (captureFrameRate > 0) {
                (1000.0f / captureFrameRate).toLong()
            } else {
                33L // Default value (in ms) if no valid capture rate is provided
            }

    // Buffering detection constants
    private val positionUpdateInterval = 250L
    private val bufferingCheckInterval = 200L // Increased from 100ms to reduce CPU usage
    private val bufferingTimeoutThreshold = 500L

    init {
        if (hdrMetalRequested && System.getProperty("compose.interop.blending").isNullOrBlank()) {
            System.setProperty("compose.interop.blending", "true")
        }
        macLogger.d { "Initializing video player" }
        lifecycle.launchControlOperation {
            initPlayer()
            startUIUpdateJob()
        }
    }

    /**
     * Starts a job to update UI state based on frame updates. This is the only
     * job that touches the main thread.
     */
    @OptIn(FlowPreview::class)
    private fun startUIUpdateJob() {
        uiUpdateJob?.cancel()
        uiUpdateJob =
            ioScope.launch {
                _currentFrameState.debounce(1).collectLatest { newFrame ->
                    ensureActive() // Checks that the coroutine is still active
                    if (newFrame != null && newFrame.epoch != frameEpoch.get()) {
                        return@collectLatest
                    }
                    val pendingBitmap = newFrame?.bitmap
                    pendingUiSkiaBitmap.set(pendingBitmap)
                    try {
                        withContext(Dispatchers.Main) {
                            if (newFrame != null && newFrame.epoch != frameEpoch.get()) {
                                return@withContext
                            }
                            (currentFrameState as MutableState).value = newFrame?.imageBitmap
                            displayedSkiaBitmap.set(pendingBitmap)
                            newFrame?.displayAspectRatio?.let { frameAspectRatio ->
                                if (abs(_aspectRatio.value - frameAspectRatio) > ASPECT_RATIO_CHANGE_EPSILON) {
                                    _aspectRatio.value = frameAspectRatio
                                }
                            }
                            newFrame?.let { frame ->
                                if (metadata.width != frame.width) metadata.width = frame.width
                                if (metadata.height != frame.height) metadata.height = frame.height
                            }
                        }
                    } finally {
                        pendingUiSkiaBitmap.compareAndSet(pendingBitmap, null)
                    }
                }
            }
    }

    /** Initializes the native video player on the IO thread. */
    private suspend fun initPlayer() {
        macLogger.d { "initPlayer() - Creating native player" }
        try {
            val ptr = MacNativeBridge.nCreatePlayer()
            if (ptr != 0L) {
                val coroutineIsActive = currentCoroutineContext().isActive
                val installed =
                    synchronized(nativeInstanceLock) {
                        if (lifecycle.isDisposed || !coroutineIsActive) {
                            false
                        } else if (playerPtrAtomic.compareAndSet(0L, ptr)) {
                            nativeBackendUsesLibVlc = false
                            true
                        } else {
                            false
                        }
                    }
                if (!installed) {
                    MacNativeBridge.nDisposePlayer(ptr)
                    return
                }
                macLogger.d { "Native player created successfully" }
                applyVolume()
                applyPlaybackSpeed()
            } else {
                macLogger.e { "Error: Failed to create native player" }
                withContext(Dispatchers.Main) {
                    error = VideoPlayerError.UnknownError("Failed to create native player")
                }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            macLogger.e { "Exception in initPlayer: ${e.message}" }
            withContext(Dispatchers.Main) {
                error = VideoPlayerError.UnknownError("Failed to initialize player: ${e.message}")
            }
        }
    }

    /** Updates the frame rate information from the native player. */
    private suspend fun updateFrameRateInfo() {
        macLogger.d { "updateFrameRateInfo()" }
        val ptr = playerPtr
        if (ptr == 0L) return

        try {
            videoFrameRate = MacNativeBridge.nGetVideoFrameRate(ptr)
            screenRefreshRate = MacNativeBridge.nGetScreenRefreshRate(ptr)
            captureFrameRate = MacNativeBridge.nGetCaptureFrameRate(ptr)
            macLogger.d {
                "Frame Rates - Video: $videoFrameRate, Screen: $screenRefreshRate, Capture: $captureFrameRate"
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            macLogger.e { "Error updating frame rate info: ${e.message}" }
        }
    }

    // Check if this is a local file that doesn't exist
    // This handles both URIs with a "file:" scheme and simple filenames without a scheme, with or without authority.
    // Uses File directly to support paths with spaces or non-ASCII characters that URI.create() rejects.
    private fun checkExistsIfLocalFile(uri: String): Boolean {
        val schemeDelimiter = uri.indexOf("://")
        val scheme =
            when {
                uri.startsWith("file:") -> "file"
                schemeDelimiter >= 0 -> uri.substring(0, schemeDelimiter)
                else -> ""
            }
        return when (scheme) {
            "", "file" -> {
                val path = if (scheme == "file") normalizeLocalFileUriForPlayback(uri) else uri
                File(path).exists()
            }
            else -> true // Network URI — assume reachable
        }
    }

    private fun normalizeLocalFileUriForPlayback(uri: String): String = normalizeUnixLocalFileUriForPlayback(uri)

    private data class MacPlaybackSourceResolution(
        val uri: String,
        val requestHeaders: Map<String, String>,
        val sourceWasPrepared: Boolean = false,
        val libVlcBackend: MacResolvedLibVlcBackend? = null,
        val error: VideoPlayerError.ColorPipelineError? = null,
    )

    private suspend fun resolveMacPlaybackSource(
        uri: String,
        requestHeaders: Map<String, String>,
    ): MacPlaybackSourceResolution {
        val candidateLibVlcBackend = resolveLibVlcBackendForUri(uri, requestHeaders)
        val hasSourcePipelineExtension =
            playbackOptions.extensions.any { extension ->
                extension is VideoSourcePipelineExtension && extension.availability.canContribute
            }
        val sourceProbe = withContext(Dispatchers.IO) { JvmLibVlcMediaProbe.probe(uri, requestHeaders) }
        withContext(Dispatchers.Main) {
            _chapters = sourceProbe.chapters
        }
        if (candidateLibVlcBackend == null && !hasSourcePipelineExtension) {
            return MacPlaybackSourceResolution(uri, requestHeaders)
        }

        activeSourceColorInfo = sourceProbe.videoColorInfo
        activeDecoderColorCapabilities = DecoderColorCapabilities()
        val hlsSource = uri.substringBefore('?').endsWith(".m3u8", ignoreCase = true)
        val isLiveSource = hlsSource && sourceProbe.durationSeconds == null
        synchronized(colorPipelineController) {
            colorPipelineController.updateSource(
                source = activeSourceColorInfo,
                decoderName = "Desktop source probe (decoder not selected)",
                decoderCapabilities = activeDecoderColorCapabilities,
                isLive = isLiveSource,
            )
        }

        return when (
            val preparation =
                playbackOptions.prepareSourceWithExtensions(
                    VideoPipelineSourceRequest(
                        uri = uri,
                        requestHeaders = requestHeaders,
                        source = sourceProbe.videoColorInfo,
                        dynamicRangePolicy = playbackOptions.dynamicRangePolicy,
                        dolbyVisionPolicy = playbackOptions.dolbyVisionPolicy,
                        isLive = isLiveSource,
                        automaticDolbyVisionConversionAllowed =
                            playbackOptions.dolbyVisionPolicy == DolbyVisionPolicy.AUTO &&
                                candidateLibVlcBackend != null,
                    ),
                )
        ) {
            is VideoPipelineSourcePreparation.Ready -> {
                preparedPipelineSource = preparation.source
                preparedPipelineOriginalColorInfo = sourceProbe.videoColorInfo
                MacPlaybackSourceResolution(
                    uri = preparation.source.uri,
                    requestHeaders = preparation.source.requestHeaders,
                    sourceWasPrepared = true,
                )
            }
            is VideoPipelineSourcePreparation.Rejected ->
                MacPlaybackSourceResolution(
                    uri = uri,
                    requestHeaders = requestHeaders,
                    error = VideoPlayerError.ColorPipelineError(preparation.reason, preparation.detail),
                )
            VideoPipelineSourcePreparation.NotApplicable ->
                MacPlaybackSourceResolution(
                    uri = uri,
                    requestHeaders = requestHeaders,
                    libVlcBackend =
                        candidateLibVlcBackend?.takeIf {
                            shouldUseMacLibVlcCandidate(
                                sourceColorInfo = sourceProbe.videoColorInfo,
                                explicitlyRequested = desktopVideoBackend.requestsLibVlcExplicitly(),
                            )
                        },
                )
        }
    }

    override fun openUri(
        uri: String,
        initializePlayerState: InitialPlayerState,
        requestHeaders: Map<String, String>,
    ) {
        lifecycle.ensureUsable()
        macLogger.d { "openUri() - Opening URI: $uri, initializePlayerState: $initializePlayerState" }

        val sanitizedHeaders = requestHeaders.sanitizedRequestHeaders()
        lifecycle.launchSourceOperation(
            onScheduled = {
                clearPendingSeekRequests()
                _chapters = emptyList()
                frameEpoch.incrementAndGet()
                lastUri = uri
                lastRequestHeaders = sanitizedHeaders
                invalidateLibAssSelection()
                activeSourceColorInfo = VideoColorInfo()
                activeDecoderColorCapabilities = DecoderColorCapabilities()
                activeDisplayColorCapabilities = DisplayColorCapabilities()
                nativeHdrSurfaceAttached = false
                colorOutputVerification = ColorPipelineVerification.NONE
                unavailableMetalProjectionHdrRanges.clear()
                metalProjectionFallbackDetail = null
                metalProjectionRendererDisabled = false
                lastMetalProjectionConfiguration = null
                synchronized(colorPipelineController) {
                    colorPipelineController.resetSource()
                }
            },
        ) { generation ->
            if (!checkExistsIfLocalFile(uri)) {
                macLogger.e { "File does not exist: $uri" }
                setPlayerError(VideoPlayerError.SourceError("File not found: $uri"))
                return@launchSourceOperation
            }
            lifecycle.ensureCurrentSource(generation)
            withContext(Dispatchers.Main) {
                isLoading = true
                error = null // Clear any previous errors only if we got this far
                playbackSpeed = 1.0f
            }
            lifecycle.ensureCurrentSource(generation)

            // Ensure heavy operations are performed in the background
            try {
                // Stop and clean up any existing playback
                if (hasMedia || ffmpegHlsFallback != null) {
                    cleanupCurrentPlayback()
                }
                closePreparedPipelineSource()
                lifecycle.ensureCurrentSource(generation)

                clearFfmpegFallbackTrackState()
                ffmpegHlsSourceUri = null
                ffmpegHlsSelectedAudioStreamIndex = null
                ffmpegHlsSelectedSubtitleStreamIndex = null
                ffmpegHlsPlaybackOffsetSeconds = 0.0
                clearLibVlcTrackState()

                // The native libVLC view is not a verified color path. Probe before backend selection so
                // HDR and ambiguous high-precision input can only use AVFoundation or the controlled bridge.
                val resolvedSource = resolveMacPlaybackSource(uri, sanitizedHeaders)
                resolvedSource.error?.let { pipelineError ->
                    setPlayerError(pipelineError)
                    return@launchSourceOperation
                }
                val libVlcBackend = resolvedSource.libVlcBackend
                ensurePlayerInitialized(libVlcBackend)
                lifecycle.ensureCurrentSource(generation)

                val playableUri =
                    if (resolvedSource.sourceWasPrepared) {
                        prepareUriForAvFoundationPlayback(resolvedSource.uri)
                    } else if (libVlcBackend != null) {
                        prepareUriForLibVlcPlayback(
                            resolvedSource.uri,
                            resolvedSource.requestHeaders,
                        )
                    } else {
                        prepareUriForMacPlayback(resolvedSource.uri, resolvedSource.requestHeaders)
                    }

                // Open URI on IO thread and capture result
                val result = openMediaUri(playableUri, resolvedSource.requestHeaders)
                lifecycle.ensureCurrentSource(generation)

                if (result) {
                    // Launch parallel background tasks
                    coroutineScope {
                        launch { updateFrameRateInfo() }
                        launch { updateMetadata() }
                    }
                    lifecycle.ensureCurrentSource(generation)
                    // Scale output to match display surface if size is already known
                    if (surfaceWidth > 0 && surfaceHeight > 0) {
                        applyOutputScaling()
                    }

                    // Update UI state on main thread
                    withContext(Dispatchers.Main) {
                        if (!lifecycle.isCurrentSource(generation)) return@withContext
                        hasMedia = true
                        isLoading = false
                        // Set isPlaying based on the initializePlayerState parameter
                        isPlaying = initializePlayerState == InitialPlayerState.PLAY
                    }
                    lifecycle.ensureCurrentSource(generation)

                    // Start background processes for frame updates
                    if (shouldUseNativeVideoSurface()) {
                        stopFrameUpdates()
                    } else {
                        startFrameUpdates()
                    }
                    startPositionUpdates()

                    // First frame update in the background
                    if (!shouldUseNativeVideoSurface()) {
                        updateFrameAsync()
                    }

                    // Start buffering check in the background
                    if (!shouldUseNativeVideoSurface()) {
                        startBufferingCheck()
                    }

                    // Start playback if needed - in the background
                    if (libVlcBackend != null) {
                        // libVLC exposes its runtime track descriptions only after playback has
                        // started. Populate the model from the live player, then restore a paused
                        // initial state when requested.
                        playInBackground()
                        delay(75.milliseconds)
                        refreshLibVlcRuntimeTracksIfNeeded()
                        applyLibVlcSelectedTracks()
                        if (initializePlayerState != InitialPlayerState.PLAY) {
                            pauseInBackground()
                        }
                    } else if (isPlaying) {
                        playInBackground()
                    }
                } else {
                    macLogger.e { "Failed to open URI" }
                    closePreparedPipelineSource()
                    closeFfmpegHlsFallback()
                    clearFfmpegFallbackTrackState()
                    clearLibVlcTrackState()
                    // Use withContext directly since we're already in a suspend function
                    withContext(Dispatchers.Main) {
                        isLoading = false
                        error = VideoPlayerError.SourceError("Failed to open media source")
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                macLogger.e { "openUri() - Exception: ${e.message}" }
                closePreparedPipelineSource()
                closeFfmpegHlsFallback()
                clearFfmpegFallbackTrackState()
                clearLibVlcTrackState()
                handleError(e)
            }
        }
    }

    override fun openFile(
        file: PlatformFile,
        initializePlayerState: InitialPlayerState,
    ) {
        lifecycle.ensureUsable()
        openUri(file.file.path, initializePlayerState)
    }

    /** Cleans up current playback state. */
    private suspend fun cleanupCurrentPlayback() {
        macLogger.d { "cleanupCurrentPlayback() - Cleaning up current playback" }
        pauseInBackground()
        cancelAndResetPlayerScope(recreate = true)
        invalidateLibAssSelection()

        withContext(frameDispatcher) {
            try {
                synchronized(nativeInstanceLock) {
                    val ptrToDispose = playerPtrAtomic.getAndSet(0L)
                    if (ptrToDispose != 0L) {
                        MacNativeBridge.nDisposePlayer(ptrToDispose)
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                macLogger.e { "Error disposing player: ${e.message}" }
            }
        }

        nativeBackendUsesLibVlc = false
        hdrMetalSurfaceAllowed = false
        nativeHdrSurfaceAttached = false
        lastMetalProjectionConfiguration = null
        activeDisplayColorCapabilities = DisplayColorCapabilities()
        colorOutputVerification = ColorPipelineVerification.NONE
        setNativeHdrToneMappingEnabled(false)
        clearLibAssSubtitleRenderer()
        closeFfmpegHlsFallback()
        closePreparedPipelineSource()
        clearLibVlcTrackState()
    }

    private suspend fun cancelAndResetPlayerScope(recreate: Boolean) {
        playerScope.coroutineContext[Job]?.cancelAndJoin()
        frameUpdateJob = null
        positionUpdateJob = null
        bufferingCheckJob = null
        seekInProgress = false
        targetSeekTime = null
        lastFrameHash = Int.MIN_VALUE
        if (recreate && !lifecycle.isDisposed) {
            playerScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        }
    }

    private fun setNativeHdrMetalPreferred(preferred: Boolean) {
        val ptr = playerPtr
        if (ptr == 0L || nativeBackendUsesLibVlc) return
        runCatching {
            MacNativeBridge.nSetHdrMetalPreferred(ptr, preferred)
        }.onFailure { e ->
            macLogger.e { "Failed to set HDR Metal preference: ${e.message}" }
        }
    }

    private fun setNativeHdrToneMappingEnabled(enabled: Boolean) {
        val ptr = playerPtr
        if (ptr == 0L || nativeBackendUsesLibVlc) return
        runCatching {
            MacNativeBridge.nSetHdrToneMappingEnabled(ptr, enabled)
        }.onFailure { e ->
            macLogger.e { "Failed to set HDR tone mapping preference: ${e.message}" }
        }
    }

    private fun usesMacMetalProjectionRenderer(): Boolean =
        projection.usesJvmCanvasProjectionRenderer(projectionTextureCrop)

    private fun activeMacRendererColorCapabilities(): RendererColorCapabilities {
        val base = platformCapabilities.rendererColorCapabilities
        if (!usesMacMetalProjectionRenderer()) {
            return base.copy(
                controlledHdrDynamicRanges = emptySet(),
                supportsToneMappingToSdr = false,
                supportsHdrProjection = false,
                supportsHdr10PlusApplication = false,
                supportsDolbyVisionToneMappingToSdr = false,
            )
        }
        val controlled = base.controlledHdrDynamicRanges - unavailableMetalProjectionHdrRanges
        return base.copy(
            controlledHdrDynamicRanges = controlled,
            supportsHdrProjection = controlled.isNotEmpty() && !metalProjectionRendererDisabled,
            supportsDolbyVisionMetadata = false,
            supportsDolbyVisionToneMappingToSdr = false,
        )
    }

    private fun configureNativeMetalProjection(
        plannedOutput: VideoDynamicRange = colorPipelineStatus.value.plannedOutputDynamicRange,
        plannedMetadataHandling: DynamicMetadataHandling = colorPipelineStatus.value.plannedMetadataHandling,
        forceDisabled: Boolean = false,
    ): Boolean {
        val ptr = playerPtr
        val configuration =
            if (
                !forceDisabled &&
                nativeAvFoundationSurfaceRequested &&
                usesMacMetalProjectionRenderer() &&
                !metalProjectionRendererDisabled
            ) {
                macMetalProjectionConfiguration(
                    projection = projection,
                    projectionView = projectionView,
                    textureCrop = projectionTextureCrop,
                    source = activeSourceColorInfo,
                    outputDynamicRange = plannedOutput,
                    metadataHandling = plannedMetadataHandling,
                    displayPeakLuminanceNits = activeDisplayColorCapabilities.maxLuminanceNits,
                )
            } else {
                MAC_METAL_PROJECTION_DISABLED_CONFIGURATION
            }
        if (ptr == 0L || nativeBackendUsesLibVlc) return true
        if (configuration == lastMetalProjectionConfiguration) return true
        return runCatching {
            MacNativeBridge.nSetHdrMetalProjectionConfiguration(ptr, configuration)
        }.onSuccess { configured ->
            if (configured) lastMetalProjectionConfiguration = configuration
        }.onFailure { failure ->
            macLogger.e { "Failed to configure the macOS Metal projection renderer: ${failure.message}" }
        }.getOrDefault(false)
    }

    private fun applyProjectionColorRoute() {
        val projectedSurface = usesMacMetalProjectionRenderer() && !metalProjectionRendererDisabled
        val nativeSurfaceAllowed =
            shouldUseMacNativeAvFoundationPresentation(
                surfaceMode = playbackOptions.desktopVideoSurfaceMode,
                dynamicRangePolicy = dynamicRangePolicy,
                usesMetalProjection = projectedSurface,
                sourceAlreadyConvertedForAvFoundation = ffmpegHlsFallback != null,
            ) &&
                !nativeBackendUsesLibVlc &&
                !usesLibAssSubtitleOverlay
        hdrMetalSurfaceAllowed = nativeSurfaceAllowed
        nativeHdrSurfaceAttached = false
        colorOutputVerification = ColorPipelineVerification.NONE
        if (!configureNativeMetalProjection()) {
            metalProjectionRendererDisabled = projectedSurface
            hdrMetalSurfaceAllowed =
                nativeAvFoundationSurfaceRequested &&
                !nativeBackendUsesLibVlc &&
                !usesLibAssSubtitleOverlay &&
                hdrMetalRequested &&
                !usesMacMetalProjectionRenderer()
            metalProjectionFallbackDetail = "The macOS Metal projection shader or pipeline could not be configured."
        }
        setNativeHdrMetalPreferred(hdrMetalSurfaceAllowed)
        setNativeHdrToneMappingEnabled(hdrToneMappingRequested && !hdrMetalSurfaceAllowed)
        refreshColorPipelineOutput()

        if (hasMedia) {
            if (hdrMetalSurfaceAllowed) {
                stopFrameUpdates()
            } else {
                startFrameUpdates()
                playerScope.launch { updateFrameAsync() }
            }
        }
    }

    private fun refreshColorPipelineOutput(verification: ColorPipelineVerification = colorOutputVerification) {
        colorOutputVerification = verification
        val wantsNativeSurface = shouldUseHdrMetalSurface()
        val surfaceKind =
            when {
                wantsNativeSurface && nativeHdrSurfaceAttached && usesMacMetalProjectionRenderer() ->
                    VideoSurfaceKind.CONTROLLED_GPU_SURFACE
                wantsNativeSurface && nativeHdrSurfaceAttached -> VideoSurfaceKind.NATIVE_LAYER
                wantsNativeSurface -> VideoSurfaceKind.UNKNOWN
                shouldUseLibVlcNativeSurface() -> VideoSurfaceKind.NATIVE_LAYER
                else -> VideoSurfaceKind.COMPOSE_CANVAS
            }
        val plan =
            synchronized(colorPipelineController) {
                colorPipelineController.updateOutput(
                    displayCapabilities = activeDisplayColorCapabilities,
                    rendererCapabilities = activeMacRendererColorCapabilities(),
                    conversionCapabilities = platformCapabilities.colorConversionCapabilities,
                    surfaceKind = surfaceKind,
                    nativeSurfaceAvailable = wantsNativeSurface && nativeHdrSurfaceAttached,
                    isProjection = usesMacMetalProjectionRenderer(),
                    verification = verification,
                    platformRuntimeFallbackReason =
                        metalProjectionFallbackDetail.takeIf { usesMacMetalProjectionRenderer() }?.let {
                            ColorPipelineFallbackReason.RENDERER_CONFIGURATION_FAILED
                        },
                    platformRuntimeDetail = metalProjectionFallbackDetail.takeIf { usesMacMetalProjectionRenderer() },
                )
            }
        if (wantsNativeSurface && usesMacMetalProjectionRenderer()) {
            configureNativeMetalProjection(
                plannedOutput = plan?.outputDynamicRange ?: VideoDynamicRange.SDR,
                plannedMetadataHandling = plan?.metadataHandling ?: DynamicMetadataHandling.NONE,
            )
        }
        reportRequiredColorPipelineError()
    }

    private fun reportRequiredColorPipelineError() {
        val pipelineError =
            synchronized(colorPipelineController) {
                colorPipelineController.pipelineErrorOrNull()
            }
        if (pipelineError == null) {
            if (error is VideoPlayerError.ColorPipelineError) {
                ioScope.launch {
                    withContext(Dispatchers.Main) {
                        if (colorPipelineStatus.value.requestHonored) error = null
                    }
                }
            }
            return
        }
        if (
            hdrMetalSurfaceAllowed &&
            !nativeHdrSurfaceAttached
        ) {
            // The AWT/NSView host attaches after source preparation for every policy. Missing
            // display capabilities are a pending state until that attempt succeeds or explicitly
            // disables the native surface; reporting an error here races normal Compose layout.
            return
        }
        if (error == pipelineError) return
        ioScope.launch {
            val ptr = playerPtr
            if (ptr != 0L) runCatching { MacNativeBridge.nPause(ptr) }
            withContext(Dispatchers.Main) {
                isPlaying = false
                isLoading = false
                error = pipelineError
            }
        }
    }

    internal fun shouldUseHdrMetalSurface(): Boolean =
        !lifecycle.isDisposed &&
            hdrMetalSurfaceAllowed &&
            !nativeBackendUsesLibVlc &&
            !usesLibAssSubtitleOverlay

    internal fun shouldUseLibVlcNativeSurface(): Boolean =
        !lifecycle.isDisposed &&
            libVlcNativeSurfaceRequested &&
            libVlcBackendActive &&
            nativeBackendUsesLibVlc

    private fun shouldUseNativeVideoSurface(): Boolean = shouldUseHdrMetalSurface() || shouldUseLibVlcNativeSurface()

    internal fun attachHdrMetalComponent(
        component: Component,
        contentScaleMode: Int,
    ): Boolean {
        synchronized(nativeInstanceLock) {
            if (!shouldUseHdrMetalSurface()) return false
            val ptr = playerPtr
            if (ptr == 0L || !MacNativeBridge.nIsHdrMetalAvailable(ptr)) return false

            val attached =
                runCatching {
                    MacNativeBridge.nAttachHdrMetalView(ptr, component).also { attached ->
                        if (attached) {
                            MacNativeBridge.nSetHdrMetalContentScaleMode(ptr, contentScaleMode)
                            nativeHdrSurfaceAttached = true
                            refreshAttachedHdrColorPipeline()
                        }
                    }
                }.getOrElse { e ->
                    macLogger.e { "Failed to attach HDR Metal surface: ${e.message}" }
                    false
                }
            if (!attached) {
                handleNativeHdrAttachmentFailure(
                    "The macOS Metal projection layer could not be attached to the JBR/AWT host.",
                )
            }
            return attached
        }
    }

    private fun handleNativeHdrAttachmentFailure(detail: String) {
        if (usesMacMetalProjectionRenderer()) {
            metalProjectionRendererDisabled = true
            metalProjectionFallbackDetail = detail
        }
        hdrMetalSurfaceAllowed = false
        nativeHdrSurfaceAttached = false
        activeDisplayColorCapabilities = DisplayColorCapabilities()
        setNativeHdrMetalPreferred(false)
        setNativeHdrToneMappingEnabled(hdrToneMappingRequested)
        refreshColorPipelineOutput(ColorPipelineVerification.NONE)
        if (hdrToneMappingRequested) {
            startFrameUpdates()
            playerScope.launch { updateFrameAsync() }
        }
    }

    internal fun detachHdrMetalComponent(component: Component) {
        synchronized(nativeInstanceLock) {
            val ptr = playerPtr
            if (ptr == 0L) return
            runCatching {
                MacNativeBridge.nDetachHdrMetalView(ptr, component)
                nativeHdrSurfaceAttached = false
                activeDisplayColorCapabilities = DisplayColorCapabilities()
                refreshColorPipelineOutput(ColorPipelineVerification.NONE)
            }.onFailure { e ->
                macLogger.e { "Failed to detach HDR Metal surface: ${e.message}" }
            }
        }
    }

    internal fun attachHdrMetalWindow(
        window: Window,
        contentScaleMode: Int,
    ): Boolean {
        synchronized(nativeInstanceLock) {
            if (!shouldUseHdrMetalSurface()) return false
            val ptr = playerPtr
            if (ptr == 0L || !MacNativeBridge.nIsHdrMetalAvailable(ptr)) return false

            val attached =
                runCatching {
                    MacNativeBridge.nAttachHdrMetalWindow(ptr, window).also { didAttach ->
                        if (didAttach) {
                            MacNativeBridge.nSetHdrMetalContentScaleMode(ptr, contentScaleMode)
                            nativeHdrSurfaceAttached = true
                            refreshAttachedHdrColorPipeline()
                        }
                    }
                }.getOrElse { error ->
                    macLogger.e { "Failed to attach the dedicated HDR/Metal window: ${error.message}" }
                    false
                }
            if (!attached) {
                handleNativeHdrAttachmentFailure(
                    "The macOS Metal layer could not be attached below the dedicated Compose window.",
                )
            }
            return attached
        }
    }

    /** Re-evaluates the NSScreen and frame readiness after attachment, fullscreen, or monitor movement. */
    internal fun refreshAttachedHdrColorPipeline() {
        synchronized(nativeInstanceLock) {
            val ptr = playerPtr
            if (ptr == 0L || !nativeHdrSurfaceAttached) return
            val observedDisplayCapabilities =
                MacNativeBridge.nGetDisplayColorCapabilities(ptr).toMacDisplayColorCapabilities()
            val displayChanged = observedDisplayCapabilities != activeDisplayColorCapabilities
            activeDisplayColorCapabilities = observedDisplayCapabilities

            if (usesMacMetalProjectionRenderer()) {
                val failure = runCatching { MacNativeBridge.nGetHdrRendererFailure(ptr) }.getOrNull()
                if (!failure.isNullOrBlank()) {
                    handleMetalProjectionRendererFailure(failure)
                    return
                }
            }

            val nextVerification =
                if (runCatching { MacNativeBridge.nIsHdrOutputReady(ptr) }.getOrDefault(false)) {
                    if (usesMacMetalProjectionRenderer()) {
                        ColorPipelineVerification.RENDERER_CONFIGURED
                    } else {
                        ColorPipelineVerification.SYSTEM_REPORTED
                    }
                } else {
                    ColorPipelineVerification.NONE
                }
            if (displayChanged || nextVerification != colorOutputVerification) {
                refreshColorPipelineOutput(nextVerification)
            }
        }
    }

    private fun handleMetalProjectionRendererFailure(detail: String) {
        if (detail.startsWith(MAC_HDR10_PLUS_METADATA_FAILURE_PREFIX)) {
            unavailableMetalProjectionHdrRanges += VideoDynamicRange.HDR10_PLUS
            metalProjectionFallbackDetail = detail.removePrefix(MAC_HDR10_PLUS_METADATA_FAILURE_PREFIX).trim()
            colorOutputVerification = ColorPipelineVerification.NONE
            lastMetalProjectionConfiguration = null
            refreshColorPipelineOutput(ColorPipelineVerification.NONE)
            return
        }
        val failedOutput = colorPipelineStatus.value.plannedOutputDynamicRange
        metalProjectionFallbackDetail = detail
        colorOutputVerification = ColorPipelineVerification.NONE
        lastMetalProjectionConfiguration = null
        if (failedOutput == VideoDynamicRange.HDR10 || failedOutput == VideoDynamicRange.HLG) {
            unavailableMetalProjectionHdrRanges += failedOutput
            refreshColorPipelineOutput(ColorPipelineVerification.NONE)
            return
        }

        metalProjectionRendererDisabled = true
        hdrMetalSurfaceAllowed = false
        nativeHdrSurfaceAttached = false
        setNativeHdrMetalPreferred(false)
        setNativeHdrToneMappingEnabled(hdrToneMappingRequested)
        refreshColorPipelineOutput(ColorPipelineVerification.NONE)
        if (hdrToneMappingRequested) {
            startFrameUpdates()
            playerScope.launch { updateFrameAsync() }
        }
    }

    internal fun attachLibVlcNativeComponent(component: Component): Boolean {
        synchronized(nativeInstanceLock) {
            if (!shouldUseLibVlcNativeSurface()) return false
            val ptr = playerPtr
            if (ptr == 0L) return false

            return runCatching {
                MacNativeBridge.nAttachLibVlcNativeView(ptr, component)
            }.getOrElse { e ->
                macLogger.e { "Failed to attach libVLC native surface: ${e.message}" }
                false
            }
        }
    }

    internal fun detachLibVlcNativeComponent(component: Component) {
        synchronized(nativeInstanceLock) {
            val ptr = playerPtr
            if (ptr == 0L) return
            runCatching {
                MacNativeBridge.nDetachLibVlcNativeView(ptr, component)
            }.onFailure { e ->
                macLogger.e { "Failed to detach libVLC native surface: ${e.message}" }
            }
        }
    }

    internal fun attachLibVlcNativeWindow(window: Window): Boolean {
        synchronized(nativeInstanceLock) {
            if (!shouldUseLibVlcNativeSurface()) return false
            val ptr = playerPtr
            if (ptr == 0L) return false

            return runCatching {
                MacNativeBridge.nAttachLibVlcNativeWindow(ptr, window)
            }.getOrElse { error ->
                macLogger.e { "Failed to attach the dedicated libVLC window: ${error.message}" }
                false
            }
        }
    }

    private suspend fun prepareUriForMacPlayback(
        uri: String,
        requestHeaders: Map<String, String> = emptyMap(),
    ): String {
        val legacyContainer = JvmLegacyVideoContainerSupport.containerFor(uri, requestHeaders)
        if (legacyContainer != null) {
            val configured =
                playbackOptions.desktopMediaSourcePolicy.explicitFallbackBackend()
                    ?: desktopVideoBackend.forcedMacFallbackBackend()
                    ?: resolveMacConfiguredFallbackBackend(
                        configured = macFallbackBackendProperty(),
                        isLegacyContainer = true,
                    )
            if (configured !in setOf("platform", "avfoundation")) {
                return prepareUriForExternalHlsPlayback(
                    uri = uri,
                    requestHeaders = requestHeaders,
                    forceAvFoundationCompatibility = true,
                )
            }
        }
        if (!JvmExternalFallbackContainerSupport.needsContainerFallback(uri, requestHeaders) ||
            !playbackOptions.allowsExternalSourceAdapter()
        ) {
            return prepareUriForAvFoundationPlayback(uri)
        }

        return prepareUriForExternalHlsPlayback(uri = uri, requestHeaders = requestHeaders)
    }

    private suspend fun prepareUriForAvFoundationPlayback(uri: String): String {
        unavailableMetalProjectionHdrRanges.clear()
        metalProjectionFallbackDetail = null
        metalProjectionRendererDisabled = false
        lastMetalProjectionConfiguration = null
        hdrMetalSurfaceAllowed =
            shouldUseMacNativeAvFoundationPresentation(
                surfaceMode = playbackOptions.desktopVideoSurfaceMode,
                dynamicRangePolicy = dynamicRangePolicy,
                usesMetalProjection = usesMacMetalProjectionRenderer(),
                sourceAlreadyConvertedForAvFoundation = false,
            )
        nativeHdrSurfaceAttached = false
        configureNativeMetalProjection(forceDisabled = !nativeAvFoundationSurfaceRequested)
        setNativeHdrMetalPreferred(hdrMetalSurfaceAllowed)
        setNativeHdrToneMappingEnabled(hdrToneMappingRequested && !hdrMetalSurfaceAllowed)
        withContext(Dispatchers.Main) {
            renderingInfo.update(
                backend = "AVFoundation",
                container = "AVFoundation-supported source",
                videoDecoder = "AVFoundation",
                videoRenderer = avFoundationVideoRenderer(),
                audioRenderer = "AVFoundation / CoreAudio",
                subtitleRenderer = null,
                subtitleSource = null,
                notes = avFoundationNotes(),
            )
        }
        return normalizeLocalFileUriForPlayback(uri)
    }

    private fun updateProjectionRenderingInfo() {
        renderingInfo.videoProjection = projection.renderingInfoLabel()
        macJvmCanvasVideoRenderer()?.let { renderer ->
            renderingInfo.videoRenderer = renderer
        }
    }

    private fun macJvmCanvasVideoRenderer(): String? =
        when {
            shouldUseNativeVideoSurface() -> null
            ffmpegHlsFallback != null ->
                projection.jvmCanvasRendererLabel(
                    baseRenderer = "CVPixelBuffer -> Compose Canvas (Skia)",
                    textureCrop = projectionTextureCrop,
                )
            hasMedia -> avFoundationVideoRenderer()
            else -> null
        }

    private fun avFoundationVideoRenderer(): String =
        when {
            shouldUseHdrMetalSurface() && usesMacMetalProjectionRenderer() ->
                "AVPlayerItemVideoOutput P010/NV12 -> FP16 Metal projection"
            shouldUseHdrMetalSurface() -> "AVPlayerLayer native AppKit surface (HDR/EDR capable)"
            hdrToneMappingRequested ->
                projection.jvmCanvasRendererLabel(
                    baseRenderer = "AVPlayerItemVideoOutput tone-mapped BT.709 -> Compose Canvas",
                    textureCrop = projectionTextureCrop,
                )
            else ->
                projection.jvmCanvasRendererLabel(
                    baseRenderer = "CVPixelBuffer -> Compose Canvas (Skia)",
                    textureCrop = projectionTextureCrop,
                )
        }

    private fun avFoundationNotes(): String =
        when {
            shouldUseHdrMetalSurface() && usesMacMetalProjectionRenderer() ->
                "Projected video uses a per-player FP16 Metal layer in a dedicated native player window; Compose controls share that window."
            shouldUseHdrMetalSurface() ->
                "Native macOS HDR path uses a dedicated player window shared with Compose controls."
            hdrToneMappingRequested -> "HDR sources are tone-mapped to SDR for stable Compose rendering."
            else -> "No external GPL components are bundled or linked."
        }

    private suspend fun prepareUriForExternalHlsPlayback(
        uri: String,
        requestHeaders: Map<String, String>,
        forceAvFoundationCompatibility: Boolean = false,
    ): String {
        ffmpegHlsHdrCmafPassthrough = false
        ffmpegHlsToneMappedHdrToSdr = false
        ffmpegHlsAvFoundationCompatibleTranscode = false
        hdrMetalSurfaceAllowed = false
        configureNativeMetalProjection(forceDisabled = true)
        setNativeHdrMetalPreferred(false)
        setNativeHdrToneMappingEnabled(false)

        val startedFallback =
            ExternalHlsFallbackSupport.start(
                uri = uri,
                requestHeaders = requestHeaders,
                selectedAudioStreamIndex = ffmpegHlsSelectedAudioStreamIndex,
                selectedSubtitleStreamIndex = ffmpegHlsSelectedSubtitleStreamIndex,
                startTimeSeconds = ffmpegHlsPlaybackOffsetSeconds,
                allowHdrCmafPassthrough =
                    !forceAvFoundationCompatibility &&
                        dynamicRangePolicy != DynamicRangePolicy.FORCE_SDR,
                requireHdrCmafPassthrough =
                    !forceAvFoundationCompatibility &&
                        dynamicRangePolicy == DynamicRangePolicy.REQUIRE_HDR,
                forceSdrOutput =
                    !forceAvFoundationCompatibility &&
                        dynamicRangePolicy == DynamicRangePolicy.FORCE_SDR,
                forceAvFoundationCompatibility = forceAvFoundationCompatibility,
                extensions = playbackOptions.extensions,
                sourcePolicy = playbackOptions.desktopMediaSourcePolicy,
            )
        val hlsSource = startedFallback.source
        ffmpegHlsFallback = startedFallback.fallback
        ffmpegHlsFallbackDurationSeconds = hlsSource.durationSeconds
        ffmpegHlsInputColorInfo = hlsSource.inputColorInfo
        ffmpegHlsOutputColorInfo = hlsSource.outputColorInfo
        ffmpegHlsHdrCmafPassthrough = hlsSource.hdrCmafPassthrough
        ffmpegHlsToneMappedHdrToSdr = hlsSource.toneMappedHdrToSdr
        ffmpegHlsAvFoundationCompatibleTranscode = hlsSource.avFoundationCompatibleTranscode
        ffmpegHlsSourceUri = uri
        ffmpegHlsSelectedAudioStreamIndex = hlsSource.selectedAudioStreamIndex
        ffmpegHlsSelectedSubtitleStreamIndex = hlsSource.selectedSubtitleStreamIndex
        ffmpegHlsPlaybackOffsetSeconds = hlsSource.playbackOffsetSeconds
        activeSourceColorInfo = hlsSource.inputColorInfo
        activeDecoderColorCapabilities = hlsSource.outputColorInfo.toConfirmedDecoderCapabilities()
        synchronized(colorPipelineController) {
            colorPipelineController.updateSource(
                source = activeSourceColorInfo,
                decoderInput = hlsSource.outputColorInfo,
                decoderName =
                    when {
                        hlsSource.hdrCmafPassthrough ->
                            "KMediaBridge HDR sample copy -> AVFoundation system decoder"
                        hlsSource.toneMappedHdrToSdr -> "Color-managed SDR bridge -> AVFoundation"
                        hlsSource.avFoundationCompatibleTranscode ->
                            "FFmpeg legacy decode -> AVC/AAC -> AVFoundation"
                        else -> "KMediaBridge CMAF adapter -> AVFoundation"
                    },
                decoderCapabilities = activeDecoderColorCapabilities,
                isLive = false,
            )
        }

        // The bridge adapts the source, but AVFoundation still owns presentation. SDR legacy
        // transcodes, tone-mapped output, and HDR passthrough all use the same native AppKit
        // window unless this player was explicitly created for embedded Compose rendering.
        hdrMetalSurfaceAllowed =
            shouldUseMacNativeAvFoundationPresentation(
                surfaceMode = playbackOptions.desktopVideoSurfaceMode,
                dynamicRangePolicy = dynamicRangePolicy,
                usesMetalProjection = usesMacMetalProjectionRenderer(),
                sourceAlreadyConvertedForAvFoundation = true,
            )
        nativeHdrSurfaceAttached = false
        lastMetalProjectionConfiguration = null
        if (
            !configureNativeMetalProjection(
                forceDisabled = !nativeAvFoundationSurfaceRequested || !hlsSource.hdrCmafPassthrough,
            )
        ) {
            metalProjectionRendererDisabled = usesMacMetalProjectionRenderer()
            metalProjectionFallbackDetail =
                "The macOS Metal projection shader or pipeline could not be configured for remuxed HDR."
            hdrMetalSurfaceAllowed = nativeAvFoundationSurfaceRequested && !usesMacMetalProjectionRenderer()
        }
        setNativeHdrMetalPreferred(hdrMetalSurfaceAllowed)
        setNativeHdrToneMappingEnabled(hdrToneMappingRequested && !hdrMetalSurfaceAllowed)
        refreshColorPipelineOutput(
            if (hlsSource.toneMappedHdrToSdr || hlsSource.avFoundationCompatibleTranscode) {
                ColorPipelineVerification.RENDERER_CONFIGURED
            } else {
                ColorPipelineVerification.NONE
            },
        )
        updateFfmpegFallbackTracks(hlsSource)
        updateExternalHlsRenderingInfo(backend = startedFallback.backend, hlsSource = hlsSource)
        return hlsSource.playlistUrl
    }

    private suspend fun updateExternalHlsRenderingInfo(
        backend: ExternalHlsFallbackBackend,
        hlsSource: HlsFallbackSource,
    ) {
        withContext(Dispatchers.Main) {
            renderingInfo.update(
                backend =
                    if (hlsSource.hdrCmafPassthrough) {
                        "${backend.displayName} HDR CMAF bridge"
                    } else {
                        "${backend.displayName} CMAF bridge"
                    },
                container =
                    when {
                        hlsSource.videoCopiedWithoutReencoding ->
                            "Matroska/WebM video samples copied to CMAF/fMP4"
                        hlsSource.avFoundationCompatibleTranscode ->
                            "AVI/ASF decoded to AVC/AAC CMAF/fMP4"
                        else -> "Container adapted to HLS"
                    },
                videoDecoder =
                    when {
                        hlsSource.hdrCmafPassthrough -> "AVFoundation HEVC Main 10 from CMAF"
                        hlsSource.avFoundationCompatibleTranscode ->
                            "FFmpeg legacy decoder -> H.264 VideoToolbox -> AVFoundation"
                        else -> "AVFoundation system decoder from CMAF"
                    },
                videoRenderer = avFoundationVideoRenderer(),
                audioRenderer = "AVFoundation / CoreAudio",
                subtitleRenderer =
                    hlsSource.selectedSubtitleStreamIndex?.let {
                        "burned into HLS by external ${backend.displayName}"
                    },
                subtitleSource = hlsSource.selectedSubtitleStreamIndex?.let { "embedded stream $it" },
                notes =
                    when {
                        hlsSource.usesMediaBridge && hlsSource.toneMappedHdrToSdr ->
                            "The selected KMediaBridge runtime uses dynamically linked FFmpeg. " +
                                "The verified HDR source was " +
                                "tone-mapped to explicitly tagged limited-range BT.709 before AVFoundation decoding."
                        hlsSource.usesMediaBridge && hlsSource.videoCopiedWithoutReencoding ->
                            "The selected KMediaBridge runtime uses dynamically linked FFmpeg. Compressed video " +
                                "and compatible audio are remuxed without re-encoding; " +
                                "display output is reported separately."
                        hlsSource.usesMediaBridge && hlsSource.avFoundationCompatibleTranscode ->
                            "The selected KMediaBridge runtime decoded legacy video/audio with dynamically linked " +
                                "FFmpeg, encoded H.264 through VideoToolbox and AAC, then delivered CMAF to AVFoundation."
                        hlsSource.usesMediaBridge ->
                            "The selected KMediaBridge runtime used dynamically linked FFmpeg to emit a bounded SDR " +
                                "CMAF stream."
                        else -> "Optional external ${backend.displayName} fallback."
                    },
            )
        }
    }

    private suspend fun prepareUriForLibVlcPlayback(
        uri: String,
        requestHeaders: Map<String, String> = emptyMap(),
    ): String {
        libVlcBackendActive = true
        hdrMetalSurfaceAllowed = false
        configureNativeMetalProjection(forceDisabled = true)
        setNativeHdrMetalPreferred(false)
        setNativeHdrToneMappingEnabled(false)
        libVlcSourceUri = uri
        withContext(Dispatchers.Main) {
            libVlcNativeSurfaceRequested = true
            renderingInfo.update(
                backend = "libVLC native-view backend",
                container = "Source through user-installed libVLC",
                videoDecoder = "libVLC",
                videoRenderer = "libVLC native NSView",
                audioRenderer = "libVLC / AUHAL",
                subtitleRenderer = null,
                subtitleSource = null,
                notes =
                    "VLC renders into a native macOS view in a dedicated player window shared with Compose controls.",
            )
        }
        val trackInfo = withContext(Dispatchers.IO) { JvmLibVlcMediaProbe.probe(uri, requestHeaders) }
        libVlcTrackInfo = trackInfo
        updateLibVlcTracks(trackInfo)
        return uri
    }

    private suspend fun updateLibVlcTracks(trackInfo: JvmLibVlcTrackInfo) {
        withContext(Dispatchers.Main) {
            _chapters = trackInfo.chapters
            _availableAudioTracks.removeAll { isMacLibVlcAudioTrackId(it.id) }
            _availableAudioTracks.addAll(trackInfo.audioStreams.map { it.track })
            currentAudioTrack =
                libVlcSelectedAudioStreamIndex
                    ?.let { streamIndex -> trackInfo.audioStreams.firstOrNull { it.streamIndex == streamIndex }?.track }
                    ?: trackInfo.audioStreams.firstOrNull { it.track.isDefault }?.track
                    ?: trackInfo.audioStreams.firstOrNull()?.track
            libVlcSelectedAudioStreamIndex =
                currentAudioTrack?.id?.let(::libVlcTrackStreamIndex)

            _availableSubtitleTracks.removeAll { isMacLibVlcSubtitleTrackId(it.id) }
            _availableSubtitleTracks.addAll(trackInfo.subtitleStreams.map { it.track })
            val selectedSubtitle =
                libVlcSelectedSubtitleStreamIndex
                    ?.let { streamIndex ->
                        trackInfo.subtitleStreams.firstOrNull { it.streamIndex == streamIndex }?.track
                    }
            if (selectedSubtitle != null) {
                currentSubtitleTrack = selectedSubtitle
                subtitlesEnabled = true
                libVlcSelectedSubtitleStreamIndex = selectedSubtitle.id.let(::libVlcTrackStreamIndex)
            } else if (currentSubtitleTrack?.id?.let(::isMacLibVlcSubtitleTrackId) == true) {
                currentSubtitleTrack = null
                subtitlesEnabled = false
            }
        }
    }

    private suspend fun refreshLibVlcRuntimeTracksIfNeeded() {
        val ptr = playerPtr
        val currentInfo = libVlcTrackInfo ?: JvmLibVlcTrackInfo()
        if (ptr == 0L || currentInfo.audioStreams.isNotEmpty() || currentInfo.subtitleStreams.isNotEmpty()) return

        repeat(12) {
            val runtimeAudioTracks =
                parseLibVlcRuntimeTrackDescriptions(MacNativeBridge.nGetLibVlcAudioTrackDescriptions(ptr))
            val runtimeSubtitleTracks =
                parseLibVlcRuntimeTrackDescriptions(MacNativeBridge.nGetLibVlcSubtitleTrackDescriptions(ptr))

            if (runtimeAudioTracks.isNotEmpty() || runtimeSubtitleTracks.isNotEmpty()) {
                val mergedInfo =
                    currentInfo.copy(
                        audioStreams =
                            currentInfo.audioStreams.ifEmpty {
                                runtimeAudioTracks.map { description ->
                                    JvmLibVlcAudioStream(
                                        streamIndex = description.ordinal,
                                        ordinal = description.ordinal,
                                        track =
                                            AudioTrack(
                                                id = "$LIBVLC_CANVAS_AUDIO_TRACK_ID_PREFIX${description.ordinal}",
                                                label =
                                                    description.label.ifBlank {
                                                        "Audio ${description.ordinal + 1}"
                                                    },
                                            ),
                                    )
                                }
                            },
                        subtitleStreams =
                            currentInfo.subtitleStreams.ifEmpty {
                                runtimeSubtitleTracks.map { description ->
                                    JvmLibVlcSubtitleStream(
                                        streamIndex = description.ordinal,
                                        ordinal = description.ordinal,
                                        track =
                                            SubtitleTrack(
                                                id = "$LIBVLC_CANVAS_SUBTITLE_TRACK_ID_PREFIX${description.ordinal}",
                                                label =
                                                    description.label.ifBlank {
                                                        "Subtitle ${description.ordinal + 1}"
                                                    },
                                                language = "",
                                                src = "",
                                                format = SubtitleFormat.AUTO,
                                                isEmbedded = true,
                                            ),
                                    )
                                }
                            },
                    )
                libVlcTrackInfo = mergedInfo
                updateLibVlcTracks(mergedInfo)
                return
            }

            delay(250.milliseconds)
        }
    }

    private fun parseLibVlcRuntimeTrackDescriptions(raw: String?): List<MacLibVlcRuntimeTrackDescription> =
        raw
            ?.lineSequence()
            ?.mapNotNull { line ->
                val ordinal = line.substringBefore('\t').toIntOrNull() ?: return@mapNotNull null
                val label = line.substringAfter('\t', missingDelimiterValue = "").trim()
                MacLibVlcRuntimeTrackDescription(ordinal = ordinal, label = label)
            }?.toList()
            ?: emptyList()

    private suspend fun clearLibVlcTrackState() {
        libVlcBackendActive = false
        libVlcSourceUri = null
        libVlcTrackInfo = null
        libVlcSelectedAudioStreamIndex = null
        libVlcSelectedSubtitleStreamIndex = null
        withContext(Dispatchers.Main) {
            libVlcNativeSurfaceRequested = false
            _availableAudioTracks.removeAll { isMacLibVlcAudioTrackId(it.id) }
            if (currentAudioTrack?.id?.let(::isMacLibVlcAudioTrackId) == true) {
                currentAudioTrack = null
            }
            _availableSubtitleTracks.removeAll { isMacLibVlcSubtitleTrackId(it.id) }
            if (currentSubtitleTrack?.id?.let(::isMacLibVlcSubtitleTrackId) == true) {
                currentSubtitleTrack = null
                subtitlesEnabled = false
            }
        }
    }

    private fun isCurrentLibAssSelection(selectionToken: Long): Boolean = libAssSelectionToken.get() == selectionToken

    private fun invalidateLibAssSelection(): Long {
        completeLibAssExtractionJob.getAndSet(null)?.cancel()
        return libAssSelectionToken.incrementAndGet()
    }

    private fun libAssSubtitleSourceLabel(
        track: SubtitleTrack,
        streamIndex: Int?,
    ): String =
        if (track.isEmbedded) {
            "embedded stream ${streamIndex ?: "?"}"
        } else {
            track.src
        }

    private suspend fun markLibAssSubtitlePreparing(
        track: SubtitleTrack,
        streamIndex: Int?,
        selectionToken: Long,
    ) {
        val sourceLabel = libAssSubtitleSourceLabel(track, streamIndex)
        withContext(Dispatchers.Main) {
            if (!isCurrentLibAssSelection(selectionToken)) return@withContext
            val routeChanged = usesLibAssSubtitleOverlay
            usesLibAssSubtitleOverlay = false
            if (routeChanged) applyProjectionColorRoute()
            libAssSubtitleSource = sourceLabel
            renderingInfo.subtitleRenderer = "libass dynamic overlay (preparing)"
            renderingInfo.subtitleSource = sourceLabel
            error = null
        }
    }

    private suspend fun clearLibAssSubtitleRenderer(selectionToken: Long? = null) {
        withContext(Dispatchers.Main) {
            if (selectionToken != null && !isCurrentLibAssSelection(selectionToken)) return@withContext
            val routeChanged = usesLibAssSubtitleOverlay
            usesLibAssSubtitleOverlay = false
            if (routeChanged) applyProjectionColorRoute()
            libAssSubtitleSource = null
            renderingInfo.subtitleRenderer = null
            renderingInfo.subtitleSource = null
        }
        withContext(frameDispatcher) {
            if (selectionToken != null && !isCurrentLibAssSelection(selectionToken)) return@withContext
            synchronized(libAssLock) {
                val renderer = libAssRenderer
                libAssRenderer = null
                libAssSubtitleKey = null
                renderer?.close()
            }
        }
    }

    private suspend fun configureLibAssSubtitleRenderer(
        track: SubtitleTrack,
        streamIndex: Int?,
        selectionToken: Long,
        sourceGeneration: Long,
    ): Boolean {
        val embeddedSourceUri =
            if (track.isEmbedded) {
                libVlcSourceUri ?: lastUri
            } else {
                null
            }
        val playbackTimeMs =
            if (track.isEmbedded) {
                getPositionSafely().secondsAsDuration().inWholeMilliseconds
            } else {
                0L
            }
        val subtitleData =
            withContext(Dispatchers.IO) {
                if (track.isEmbedded) {
                    if (embeddedSourceUri.isNullOrBlank()) {
                        throw UnsupportedOperationException("No source URI is available for embedded ASS extraction.")
                    }
                    MacEmbeddedAssExtractor.extract(
                        uri = embeddedSourceUri,
                        streamIndex =
                            streamIndex
                                ?: throw UnsupportedOperationException(
                                    "No embedded subtitle stream index is available.",
                                ),
                        playbackTimeMs = playbackTimeMs,
                        requestHeaders = lastRequestHeaders,
                    )
                } else {
                    MacAssSubtitleData(content = loadSubtitleContent(track.src))
                }
            }

        if (!lifecycle.isCurrentSource(sourceGeneration) || !isCurrentLibAssSelection(selectionToken)) return false
        val configured =
            installLibAssSubtitleData(
                track = track,
                streamIndex = streamIndex,
                subtitleData = subtitleData,
                selectionToken = selectionToken,
                sourceGeneration = sourceGeneration,
            )
        if (configured &&
            subtitleData.isPartial &&
            track.isEmbedded &&
            streamIndex != null &&
            embeddedSourceUri != null
        ) {
            startCompleteLibAssSubtitleExtraction(
                track = track,
                streamIndex = streamIndex,
                sourceUri = embeddedSourceUri,
                requestHeaders = lastRequestHeaders,
                selectionToken = selectionToken,
                sourceGeneration = sourceGeneration,
            )
        }
        return configured
    }

    private suspend fun installLibAssSubtitleData(
        track: SubtitleTrack,
        streamIndex: Int?,
        subtitleData: MacAssSubtitleData,
        selectionToken: Long,
        sourceGeneration: Long,
    ): Boolean {
        if (subtitleData.content.isBlank()) {
            throw UnsupportedOperationException("The selected subtitle track could not be loaded or is empty.")
        }
        if (!subtitleData.content.contains("[Events]", ignoreCase = true)) {
            throw UnsupportedOperationException("The selected subtitle track is not valid ASS/SSA content.")
        }

        val subtitleExtension =
            playbackOptions.extensions
                .filterIsInstance<DesktopSubtitlePipelineExtension>()
                .firstOrNull { extension -> extension.supportsSubtitleFormat(track.resolvedFormat()) }
                ?: throw UnsupportedOperationException(
                    "Full ASS/SSA rendering requires composemediaplayer-ass in " +
                        "VideoPlaybackOptions.extensions. The base player does not bundle or link libass.",
                )

        val key = "${track.id}|${track.src}|${streamIndex ?: -1}|${subtitleData.content.hashCode()}"
        val renderer =
            synchronized(libAssLock) {
                if (!lifecycle.isCurrentSource(sourceGeneration) || !isCurrentLibAssSelection(selectionToken)) {
                    return@synchronized null
                }
                libAssRenderer?.takeIf { libAssSubtitleKey == key }
                    ?: run {
                        val replacement =
                            subtitleExtension.createRenderer()
                                ?: throw UnsupportedOperationException(
                                    "The configured desktop subtitle extension could not create its renderer.",
                                )
                        runCatching {
                            subtitleData.fonts.forEach { font ->
                                if (
                                    !replacement.addFont(
                                        DesktopSubtitleFont(name = font.name, data = font.data),
                                    )
                                ) {
                                    throw UnsupportedOperationException(
                                        "Failed to load the embedded subtitle font '${font.name}'.",
                                    )
                                }
                            }
                            if (!replacement.setTrack(subtitleData.content.encodeToByteArray())) {
                                throw UnsupportedOperationException(
                                    "Failed to load ASS/SSA subtitle data into libass.",
                                )
                            }
                        }.onFailure {
                            runCatching { replacement.close() }
                        }.getOrThrow()

                        val previous = libAssRenderer
                        libAssRenderer = replacement
                        libAssSubtitleKey = key
                        previous?.close()
                        replacement
                    }
            }

        if (renderer == null ||
            !lifecycle.isCurrentSource(sourceGeneration) ||
            !isCurrentLibAssSelection(selectionToken)
        ) {
            return false
        }

        val sourceLabel = libAssSubtitleSourceLabel(track, streamIndex)
        val stateCommitted =
            withContext(Dispatchers.Main) {
                lifecycle.commitCurrentSource(sourceGeneration) {
                    if (!isCurrentLibAssSelection(selectionToken)) return@commitCurrentSource
                    currentSubtitleTrack = track
                    subtitlesEnabled = true
                    usesLibAssSubtitleOverlay = true
                    applyProjectionColorRoute()
                    libAssSubtitleSource = sourceLabel
                    renderingInfo.subtitleRenderer =
                        if (subtitleData.isPartial) {
                            "${renderer.backendDescription} dynamic overlay (fast range, completing)"
                        } else {
                            "${renderer.backendDescription} dynamic overlay"
                        }
                    renderingInfo.subtitleSource = sourceLabel
                }
            }
        return stateCommitted
    }

    private fun startCompleteLibAssSubtitleExtraction(
        track: SubtitleTrack,
        streamIndex: Int,
        sourceUri: String,
        requestHeaders: Map<String, String>,
        selectionToken: Long,
        sourceGeneration: Long,
    ) {
        val extractionJob =
            lifecycle.launchSourceBoundControlOperation { completeGeneration ->
                if (completeGeneration != sourceGeneration) return@launchSourceBoundControlOperation
                if (!isCurrentLibAssSelection(selectionToken)) return@launchSourceBoundControlOperation
                try {
                    val completeData =
                        withContext(Dispatchers.IO) {
                            MacEmbeddedAssExtractor.extractComplete(sourceUri, streamIndex, requestHeaders)
                        }
                    if (!lifecycle.isCurrentSource(sourceGeneration) || !isCurrentLibAssSelection(selectionToken)) {
                        return@launchSourceBoundControlOperation
                    }
                    installLibAssSubtitleData(
                        track = track,
                        streamIndex = streamIndex,
                        subtitleData = completeData,
                        selectionToken = selectionToken,
                        sourceGeneration = sourceGeneration,
                    )
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    macLogger.e { "Complete ASS subtitle extraction failed: ${e.message}" }
                }
            }
        completeLibAssExtractionJob.getAndSet(extractionJob)?.cancel()
        extractionJob.invokeOnCompletion {
            completeLibAssExtractionJob.compareAndSet(extractionJob, null)
        }
    }

    private fun isAssLikeTrack(track: SubtitleTrack): Boolean =
        when (track.resolvedFormat()) {
            SubtitleFormat.ASS,
            SubtitleFormat.SSA,
            -> true
            SubtitleFormat.AUTO ->
                track.label.endsWith(".ass", ignoreCase = true) ||
                    track.label.endsWith(".ssa", ignoreCase = true) ||
                    track.src.endsWith(".ass", ignoreCase = true) ||
                    track.src.endsWith(".ssa", ignoreCase = true)
            else -> false
        }

    private suspend fun resolveLibVlcBackendForUri(
        uri: String,
        requestHeaders: Map<String, String>,
    ): MacResolvedLibVlcBackend? {
        val forcedDesktopBackend = desktopVideoBackend.forcedMacFallbackBackend()
        if (playbackOptions.desktopMediaSourcePolicy != DesktopMediaSourcePolicy.INHERIT &&
            !desktopVideoBackend.requestsLibVlcExplicitly()
        ) {
            return null
        }
        val legacyContainer = JvmLegacyVideoContainerSupport.containerFor(uri, requestHeaders)
        val needsExternalContainerFallback =
            legacyContainer == null &&
                JvmExternalFallbackContainerSupport.needsContainerFallback(uri, requestHeaders)
        if (forcedDesktopBackend == null && legacyContainer == null && !needsExternalContainerFallback) {
            return null
        }

        val configured =
            forcedDesktopBackend
                ?: playbackOptions.desktopMediaSourcePolicy.explicitFallbackBackend()
                ?: resolveMacConfiguredFallbackBackend(
                    configured = macFallbackBackendProperty(),
                    isLegacyContainer = legacyContainer != null,
                )

        return when (configured) {
            "platform", "avfoundation" -> null
            "libvlc" ->
                ExternalVlcLocator.findLibVlc()?.let(::MacResolvedLibVlcBackend)
                    ?: throw missingLibVlcBackendException()
            "auto" ->
                ExternalVlcLocator.findLibVlc()?.let(::MacResolvedLibVlcBackend)
            "libvlc-native-view", "libvlc-native", "libvlc-view", "libvlc-nsview" ->
                ExternalVlcLocator.findLibVlc()?.let(::MacResolvedLibVlcBackend)
                    ?: throw missingLibVlcBackendException()
            "ffmpeg", "kmediabridge", "bridge", "vlc" -> null
            else -> null
        }
    }

    private fun macFallbackBackendProperty(): String =
        System.getProperty("composemediaplayer.macos.fallbackBackend")
            ?: System.getProperty("composemediaplayer.fallbackBackend")
            ?: System.getenv("COMPOSE_MEDIA_PLAYER_FALLBACK_BACKEND")
            ?: System.getenv("COMPOSE_MEDIA_PLAYER_MACOS_FALLBACK_BACKEND")
            ?: System.getProperty("composemediaplayer.hlsFallbackBackend")
            ?: System.getenv("COMPOSE_MEDIA_PLAYER_HLS_FALLBACK_BACKEND")
            ?: System.getProperty("composemediaplayer.macos.hlsFallbackBackend")
            ?: System.getenv("COMPOSE_MEDIA_PLAYER_MACOS_HLS_FALLBACK_BACKEND")
            ?: "auto"

    private fun missingLibVlcBackendException(): UnsupportedOperationException =
        UnsupportedOperationException(
            "The macOS libVLC backend was requested, but no compatible VLC.app libVLC was found for " +
                "${ExternalVlcLocator.currentProcessArchitecture() ?: "the current"} JVM architecture. " +
                "Install a VLC build matching the app/JVM architecture or set " +
                "composemediaplayer.macos.libvlc and composemediaplayer.macos.libvlc.plugins to compatible paths. " +
                "ComposeMediaPlayer does not bundle or link VLC.",
        )

    private fun closeFfmpegHlsFallback() {
        val fallback = ffmpegHlsFallback
        ffmpegHlsFallback = null
        ffmpegHlsFallbackDurationSeconds = null
        ffmpegHlsInputColorInfo = null
        ffmpegHlsOutputColorInfo = null
        ffmpegHlsHdrCmafPassthrough = false
        ffmpegHlsToneMappedHdrToSdr = false
        ffmpegHlsAvFoundationCompatibleTranscode = false
        ffmpegHlsSourceUri = null
        ffmpegHlsSelectedAudioStreamIndex = null
        ffmpegHlsSelectedSubtitleStreamIndex = null
        ffmpegHlsPlaybackOffsetSeconds = 0.0
        fallback?.close()
    }

    private fun closePreparedPipelineSource() {
        val source = preparedPipelineSource
        preparedPipelineSource = null
        preparedPipelineOriginalColorInfo = null
        source?.close()
    }

    private suspend fun clearFfmpegFallbackTrackState() {
        ffmpegHlsSelectedAudioStreamIndex = null
        ffmpegHlsSelectedSubtitleStreamIndex = null
        ffmpegHlsPlaybackOffsetSeconds = 0.0
        withContext(Dispatchers.Main) {
            _availableAudioTracks.removeAll { isMacExternalHlsAudioTrackId(it.id) }
            if (currentAudioTrack?.id?.let(::isMacExternalHlsAudioTrackId) == true) {
                currentAudioTrack = null
            }

            _availableSubtitleTracks.removeAll { isMacExternalHlsSubtitleTrackId(it.id) }
            if (currentSubtitleTrack?.id?.let(::isMacExternalHlsSubtitleTrackId) == true) {
                currentSubtitleTrack = null
                subtitlesEnabled = false
            }
        }
    }

    private suspend fun updateFfmpegFallbackTracks(hlsSource: HlsFallbackSource) {
        val previousSubtitleId = currentSubtitleTrack?.id
        withContext(Dispatchers.Main) {
            _availableAudioTracks.removeAll { isMacExternalHlsAudioTrackId(it.id) }
            _availableAudioTracks.addAll(hlsSource.audioTracks)
            currentAudioTrack =
                hlsSource.selectedAudioStreamIndex
                    ?.let { streamIndex ->
                        hlsSource.audioTracks.firstOrNull {
                            externalHlsTrackStreamIndex(it.id) ==
                                streamIndex
                        }
                    }
                    ?: hlsSource.audioTracks.firstOrNull { it.isDefault }
                    ?: hlsSource.audioTracks.firstOrNull()

            _availableSubtitleTracks.removeAll { isMacExternalHlsSubtitleTrackId(it.id) }
            _availableSubtitleTracks.addAll(hlsSource.subtitleTracks)
            val selectedSubtitleTrack =
                hlsSource.selectedSubtitleStreamIndex
                    ?.let { streamIndex ->
                        hlsSource.subtitleTracks.firstOrNull {
                            externalHlsTrackStreamIndex(it.id) ==
                                streamIndex
                        }
                    }
                    ?: previousSubtitleId
                        ?.takeIf(::isMacExternalHlsSubtitleTrackId)
                        ?.let { previousId -> hlsSource.subtitleTracks.firstOrNull { it.id == previousId } }

            if (selectedSubtitleTrack != null) {
                currentSubtitleTrack = selectedSubtitleTrack
                subtitlesEnabled = true
            } else if (previousSubtitleId?.let(::isMacExternalHlsSubtitleTrackId) == true) {
                currentSubtitleTrack = null
                subtitlesEnabled = false
            }
        }
    }

    private fun isMacExternalHlsAudioTrackId(id: String): Boolean =
        id.startsWith(EXTERNAL_FFMPEG_AUDIO_TRACK_ID_PREFIX) || id.startsWith(EXTERNAL_VLC_AUDIO_TRACK_ID_PREFIX)

    private fun isMacExternalHlsSubtitleTrackId(id: String): Boolean =
        id.startsWith(EXTERNAL_FFMPEG_SUBTITLE_TRACK_ID_PREFIX) || id.startsWith(EXTERNAL_VLC_SUBTITLE_TRACK_ID_PREFIX)

    private fun isMacLibVlcAudioTrackId(id: String): Boolean = id.startsWith(LIBVLC_CANVAS_AUDIO_TRACK_ID_PREFIX)

    private fun isMacLibVlcSubtitleTrackId(id: String): Boolean = id.startsWith(LIBVLC_CANVAS_SUBTITLE_TRACK_ID_PREFIX)

    private fun libVlcTrackStreamIndex(id: String): Int? =
        when {
            id.startsWith(LIBVLC_CANVAS_AUDIO_TRACK_ID_PREFIX) -> id.removePrefix(LIBVLC_CANVAS_AUDIO_TRACK_ID_PREFIX)
            id.startsWith(
                LIBVLC_CANVAS_SUBTITLE_TRACK_ID_PREFIX,
            ) -> id.removePrefix(LIBVLC_CANVAS_SUBTITLE_TRACK_ID_PREFIX)
            else -> null
        }?.toIntOrNull()

    private fun externalHlsTrackStreamIndex(id: String): Int? =
        when {
            id.startsWith(
                EXTERNAL_FFMPEG_AUDIO_TRACK_ID_PREFIX,
            ) -> id.removePrefix(EXTERNAL_FFMPEG_AUDIO_TRACK_ID_PREFIX)
            id.startsWith(
                EXTERNAL_FFMPEG_SUBTITLE_TRACK_ID_PREFIX,
            ) -> id.removePrefix(EXTERNAL_FFMPEG_SUBTITLE_TRACK_ID_PREFIX)
            id.startsWith(EXTERNAL_VLC_AUDIO_TRACK_ID_PREFIX) -> id.removePrefix(EXTERNAL_VLC_AUDIO_TRACK_ID_PREFIX)
            id.startsWith(
                EXTERNAL_VLC_SUBTITLE_TRACK_ID_PREFIX,
            ) -> id.removePrefix(EXTERNAL_VLC_SUBTITLE_TRACK_ID_PREFIX)
            else -> null
        }?.toIntOrNull()

    /** Ensures the player is initialized. */
    private suspend fun ensurePlayerInitialized(libVlcBackend: MacResolvedLibVlcBackend? = null) {
        macLogger.d { "ensurePlayerInitialized() - Ensuring player is initialized" }
        if (!playerScope.isActive) {
            playerScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        }

        val wantsLibVlc = libVlcBackend != null
        val needsBackendReplacement =
            synchronized(nativeInstanceLock) {
                playerPtr != 0L && nativeBackendUsesLibVlc != wantsLibVlc
            }
        if (needsBackendReplacement) {
            cancelAndResetPlayerScope(recreate = true)
            withContext(frameDispatcher) {
                synchronized(nativeInstanceLock) {
                    val ptrToDispose = playerPtrAtomic.getAndSet(0L)
                    if (ptrToDispose != 0L) {
                        MacNativeBridge.nDisposePlayer(ptrToDispose)
                    }
                    nativeBackendUsesLibVlc = false
                }
            }
        }

        if (synchronized(nativeInstanceLock) { playerPtr == 0L }) {
            val ptr =
                if (libVlcBackend != null) {
                    MacNativeBridge.nCreateLibVlcPlayer(
                        libVlcPath = libVlcBackend.installation.libVlcPath,
                        pluginPath = libVlcBackend.installation.pluginPath,
                        nativeVideoOutput = true,
                    )
                } else {
                    MacNativeBridge.nCreatePlayer()
                }
            if (ptr != 0L) {
                val coroutineIsActive = currentCoroutineContext().isActive
                val installed =
                    synchronized(nativeInstanceLock) {
                        if (lifecycle.isDisposed || !coroutineIsActive) {
                            false
                        } else if (playerPtrAtomic.compareAndSet(0L, ptr)) {
                            nativeBackendUsesLibVlc = wantsLibVlc
                            true
                        } else {
                            false
                        }
                    }
                if (!installed) {
                    MacNativeBridge.nDisposePlayer(ptr)
                } else {
                    applyVolume()
                    applyPlaybackSpeed()
                }
            } else {
                throw IllegalStateException("Failed to create native player")
            }
        }
    }

    /** Opens media URI and returns a success flag. */
    private suspend fun openMediaUri(
        uri: String,
        requestHeaders: Map<String, String>,
    ): Boolean {
        macLogger.d { "openMediaUri() - Opening URI: $uri" }
        val ptr = playerPtr
        if (ptr == 0L) return false

        // Check if file exists (for local files)
        // This handles both URIs with file:// scheme and simple filenames without a scheme
        if (!checkExistsIfLocalFile(uri)) {
            macLogger.e { "File does not exist: $uri" }
            // Use setPlayerError to ensure the error is set synchronously
            setPlayerError(VideoPlayerError.SourceError("File not found: $uri"))
            return false
        }

        return try {
            // Open video asynchronously
            val sanitizedHeaders = requestHeaders.sanitizedRequestHeaders()
            if (sanitizedHeaders.isEmpty()) {
                MacNativeBridge.nOpenUri(ptr, uri)
            } else if (nativeBackendUsesLibVlc) {
                MacNativeBridge.nOpenUriWithHeaderLines(ptr, uri, sanitizedHeaders.requestHeadersLineString())
            } else {
                MacNativeBridge.nOpenUriWithHeaders(ptr, uri, sanitizedHeaders.requestHeadersJsonObjectString())
            }

            if (!shouldUseLibVlcNativeSurface()) {
                // Instead of directly calling `updateMetadata()`,
                // we poll until valid dimensions are available.
                pollDimensionsUntilReady(ptr)
            }

            if (!nativeBackendUsesLibVlc) {
                pollVideoColorInfoUntilReady(ptr)
            }

            // Once dimensions are retrieved, call updateMetadata()
            updateMetadata()

            true
        } catch (e: Exception) {
            macLogger.e { "Failed to open URI: ${e.message}" }
            // Use setPlayerError to ensure the error is set synchronously
            setPlayerError(VideoPlayerError.SourceError("Error opening media: ${e.message}"))
            false
        }
    }

    /**
     * Loops several times (every 250 ms) until width/height
     * are no longer zero. If dimensions are still zero after
     * a specified number of attempts, stop waiting.
     */
    private suspend fun pollDimensionsUntilReady(
        ptr: Long,
        maxAttempts: Int = 20,
    ) {
        for (attempt in 1..maxAttempts) {
            val width = MacNativeBridge.nGetFrameWidth(ptr)
            val height = MacNativeBridge.nGetFrameHeight(ptr)

            if (width > 0 && height > 0) {
                macLogger.d { "Dimensions validated (w=$width, h=$height) after $attempt attempts" }
                return
            }
            macLogger.d { "Dimensions not ready yet (attempt $attempt/$maxAttempts), waiting..." }
            delay(250.milliseconds)
        }
        macLogger.e { "Unable to retrieve valid dimensions after $maxAttempts attempts" }
    }

    private suspend fun pollVideoColorInfoUntilReady(
        ptr: Long,
        maxAttempts: Int = 20,
    ) {
        repeat(maxAttempts) {
            val colorInfo = MacNativeBridge.nGetVideoColorInfo(ptr).toMacVideoColorInfo()
            if (colorInfo.dynamicRange != VideoDynamicRange.UNKNOWN) return
            delay(100.milliseconds)
        }
        macLogger.d { "Selected AVFoundation track did not expose a complete color description." }
    }

    private data class MacSourceColorResolution(
        val colorInfo: VideoColorInfo,
        val decoderInput: VideoColorInfo,
        val appliedDolbyVisionProfileMapping: DolbyVisionProfileMapping?,
        val decoderCapabilities: DecoderColorCapabilities,
        val decoderName: String,
    )

    private fun resolveMacSourceColor(ptr: Long): MacSourceColorResolution {
        val preparedColorInfo = preparedPipelineOriginalColorInfo
        val bridgedColorInfo = ffmpegHlsInputColorInfo
        val colorInfo =
            when {
                preparedColorInfo != null -> preparedColorInfo
                bridgedColorInfo != null -> bridgedColorInfo
                nativeBackendUsesLibVlc -> libVlcTrackInfo?.videoColorInfo ?: VideoColorInfo()
                else -> MacNativeBridge.nGetVideoColorInfo(ptr).toMacVideoColorInfo()
            }
        val decoderInput = preparedPipelineSource?.outputColorInfo ?: ffmpegHlsOutputColorInfo ?: colorInfo
        val appliedDolbyVisionProfileMapping = colorInfo.profile7To81MappingOrNull(decoderInput)
        val hasPreparedOrBridgedColor = preparedColorInfo != null || bridgedColorInfo != null
        val decoderCapabilities = resolveMacDecoderCapabilities(decoderInput, hasPreparedOrBridgedColor)
        val decoderName = resolveMacDecoderName(preparedColorInfo != null, bridgedColorInfo != null)
        return MacSourceColorResolution(
            colorInfo = colorInfo,
            decoderInput = decoderInput,
            appliedDolbyVisionProfileMapping = appliedDolbyVisionProfileMapping,
            decoderCapabilities = decoderCapabilities,
            decoderName = decoderName,
        )
    }

    private fun resolveMacDecoderCapabilities(
        decoderInput: VideoColorInfo,
        hasPreparedOrBridgedColor: Boolean,
    ): DecoderColorCapabilities =
        when {
            hasPreparedOrBridgedColor -> decoderInput.toConfirmedDecoderCapabilities()
            decoderInput.dynamicRange != VideoDynamicRange.UNKNOWN ->
                DecoderColorCapabilities(
                    isKnown = true,
                    supportedDynamicRanges = setOf(decoderInput.dynamicRange),
                    maxBitDepth = decoderInput.bitDepth,
                    supportedDolbyVisionProfiles =
                        decoderInput.dolbyVision
                            ?.profile
                            ?.let { setOf(it) }
                            .orEmpty(),
                    isDolbyVisionProfileSupportKnown =
                        decoderInput.dynamicRange == VideoDynamicRange.DOLBY_VISION,
                )
            else -> DecoderColorCapabilities()
        }

    private fun resolveMacDecoderName(
        hasPreparedColor: Boolean,
        hasBridgedColor: Boolean,
    ): String =
        when {
            hasPreparedColor -> "libdovi Profile 7 to 8.1 bridge -> AVFoundation"
            hasBridgedColor && ffmpegHlsHdrCmafPassthrough ->
                "FFmpeg MKV to CMAF video copy -> AVFoundation system decoder"
            hasBridgedColor && ffmpegHlsToneMappedHdrToSdr ->
                "Color-managed SDR bridge -> AVFoundation"
            hasBridgedColor && ffmpegHlsAvFoundationCompatibleTranscode ->
                "FFmpeg legacy decode -> AVC/AAC -> AVFoundation"
            hasBridgedColor -> "External color-managed bridge -> AVFoundation"
            nativeBackendUsesLibVlc -> "libVLC (unverified color path)"
            else -> "AVFoundation system decoder"
        }

    /**
     * AVFoundation can move an HLS player between SDR and HDR variants without replacing the
     * AVPlayerItem. The native bridge updates its selected-variant description from the access
     * log; mirror that change into the public pipeline independently of the slower metadata load.
     */
    private fun refreshNativeAdaptiveColorPipeline(ptr: Long) {
        if (nativeBackendUsesLibVlc || preparedPipelineOriginalColorInfo != null || ffmpegHlsInputColorInfo != null) {
            return
        }
        val sourceColor = resolveMacSourceColor(ptr)
        val currentStatus = colorPipelineStatus.value
        if (
            sourceColor.colorInfo == activeSourceColorInfo &&
            sourceColor.decoderCapabilities == activeDecoderColorCapabilities &&
            sourceColor.decoderName == currentStatus.decoderName
        ) {
            return
        }
        activeSourceColorInfo = sourceColor.colorInfo
        activeDecoderColorCapabilities = sourceColor.decoderCapabilities
        synchronized(colorPipelineController) {
            colorPipelineController.updateSource(
                source = sourceColor.colorInfo,
                decoderInput = sourceColor.decoderInput,
                appliedDolbyVisionProfileMapping = sourceColor.appliedDolbyVisionProfileMapping,
                decoderName = sourceColor.decoderName,
                decoderCapabilities = sourceColor.decoderCapabilities,
                isLive = MacNativeBridge.nGetVideoDuration(ptr) < 0.0,
                isDrmProtected = false,
            )
        }
        lastMetalProjectionConfiguration = null
        configureNativeMetalProjection()
        refreshColorPipelineOutput(ColorPipelineVerification.NONE)
    }

    /** Updates the metadata from the native player. */
    private suspend fun updateMetadata() {
        macLogger.d { "updateMetadata()" }
        val ptr = playerPtr
        if (ptr == 0L) return

        try {
            val probedVideoWidth = libVlcTrackInfo?.videoWidth
            val probedVideoHeight = libVlcTrackInfo?.videoHeight
            val width = probedVideoWidth ?: MacNativeBridge.nGetFrameWidth(ptr)
            val height = probedVideoHeight ?: MacNativeBridge.nGetFrameHeight(ptr)
            val durationSeconds =
                ffmpegHlsFallbackDurationSeconds
                    ?: libVlcTrackInfo?.durationSeconds
                    ?: MacNativeBridge.nGetVideoDuration(ptr)
            val duration = durationSeconds.secondsAsDuration()
            val frameRate = MacNativeBridge.nGetVideoFrameRate(ptr)

            // AVFoundation's presentationSize includes clean-aperture and pixel-aspect-ratio
            // corrections. Raw CVPixelBuffer dimensions do not, so anamorphic/HLS video would
            // otherwise be displayed with the wrong geometry.
            val newAspectRatio =
                if (width > 0 && height > 0) {
                    displayAspectRatio(ptr, width, height)
                } else {
                    // Instead of forcing 16f/9f, don’t change the aspect if the video is not ready yet.
                    // For example, we can keep the previous aspect ratio:
                    _aspectRatio.value
                }

            // Get additional metadata
            val title = MacNativeBridge.nGetVideoTitle(ptr)
            val bitrate = MacNativeBridge.nGetVideoBitrate(ptr)
            val mimeType = MacNativeBridge.nGetVideoMimeType(ptr)
            val audioChannels = MacNativeBridge.nGetAudioChannels(ptr)
            val audioSampleRate = MacNativeBridge.nGetAudioSampleRate(ptr)
            val sourceColor = resolveMacSourceColor(ptr)

            activeSourceColorInfo = sourceColor.colorInfo
            activeDecoderColorCapabilities = sourceColor.decoderCapabilities
            val currentStatus = colorPipelineStatus.value
            if (
                currentStatus.source != sourceColor.colorInfo ||
                currentStatus.decoderName != sourceColor.decoderName ||
                currentStatus.decoderCapabilities != sourceColor.decoderCapabilities
            ) {
                synchronized(colorPipelineController) {
                    colorPipelineController.updateSource(
                        source = sourceColor.colorInfo,
                        decoderInput = sourceColor.decoderInput,
                        appliedDolbyVisionProfileMapping = sourceColor.appliedDolbyVisionProfileMapping,
                        decoderName = sourceColor.decoderName,
                        decoderCapabilities = sourceColor.decoderCapabilities,
                        isLive = durationSeconds < 0.0,
                        isDrmProtected = false,
                    )
                }
            }
            refreshColorPipelineOutput()

            withContext(Dispatchers.Main) {
                // Update metadata
                metadata.duration = duration
                _duration.value = duration
                metadata.width = width
                metadata.height = height
                metadata.frameRate = frameRate
                metadata.title = title
                metadata.bitrate = bitrate
                metadata.mimeType = mimeType
                metadata.audioChannels = if (audioChannels == 0) null else audioChannels
                metadata.audioSampleRate = if (audioSampleRate == 0) null else audioSampleRate

                // Update the aspect ratio only if width/height are valid
                _aspectRatio.value = newAspectRatio
            }

            macLogger.d { "Metadata updated: $metadata" }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            macLogger.e { "Error updating metadata: ${e.message}" }
        }
    }

    private fun displayAspectRatio(
        ptr: Long,
        width: Int,
        height: Int,
    ): Float {
        val rawAspectRatio = width.toFloat() / height.toFloat()
        if (nativeBackendUsesLibVlc) return rawAspectRatio

        val nativeAspectRatio =
            runCatching { MacNativeBridge.nGetDisplayAspectRatio(ptr) }
                .getOrDefault(0.0)
        return nativeAspectRatio
            .takeIf { it.isFinite() && it > 0.0 }
            ?.toFloat()
            ?: rawAspectRatio
    }

    /** Starts periodic frame updates on a background thread. */
    private fun startFrameUpdates() {
        macLogger.d { "startFrameUpdates() - Starting frame updates" }
        stopFrameUpdates()
        frameUpdateJob =
            playerScope.launch {
                while (isActive) {
                    ensureActive() // Check if coroutine is still active
                    updateFrameAsync()
                    delay(updateInterval)
                }
            }
    }

    /** Stops periodic frame updates. */
    private fun stopFrameUpdates() {
        macLogger.d { "stopFrameUpdates() - Stopping frame updates" }
        frameUpdateJob?.cancel()
        frameUpdateJob = null
    }

    /** Starts playback position updates independently from frame capture. */
    private fun startPositionUpdates() {
        macLogger.d { "startPositionUpdates() - Starting position updates" }
        stopPositionUpdates()
        positionUpdateJob =
            playerScope.launch {
                while (isActive) {
                    ensureActive()
                    val ptr = playerPtr
                    if (ptr != 0L) {
                        refreshNativeAdaptiveColorPipeline(ptr)
                    }
                    if (shouldUseHdrMetalSurface()) {
                        refreshAttachedHdrColorPipeline()
                    }
                    if (!userDragging) {
                        updatePositionAsync()
                    }
                    delay(positionUpdateInterval)
                }
            }
    }

    /** Stops periodic playback position updates. */
    private fun stopPositionUpdates() {
        macLogger.d { "stopPositionUpdates() - Stopping position updates" }
        positionUpdateJob?.cancel()
        positionUpdateJob = null
    }

    /** Starts periodic buffering detection on a background thread. */
    private fun startBufferingCheck() {
        macLogger.d { "startBufferingCheck() - Starting buffering detection" }
        stopBufferingCheck()
        bufferingCheckJob =
            playerScope.launch {
                while (isActive) {
                    ensureActive() // Check if coroutine is still active
                    checkBufferingState()
                    delay(bufferingCheckInterval)
                }
            }
    }

    /** Checks if the media is currently buffering. */
    private suspend fun checkBufferingState() {
        if (shouldUseNativeVideoSurface()) return
        if (isPlaying && !isLoading) {
            val currentTime = System.currentTimeMillis()
            val timeSinceLastFrame = currentTime - lastFrameUpdateTime

            if (timeSinceLastFrame > bufferingTimeoutThreshold) {
                macLogger.d { "Buffering detected: $timeSinceLastFrame ms since last frame update" }
                withContext(Dispatchers.Main) {
                    isLoading = true
                }
            }
        }
    }

    /** Stops the buffering detection job. */
    private fun stopBufferingCheck() {
        macLogger.d { "stopBufferingCheck() - Stopping buffering detection" }
        bufferingCheckJob?.cancel()
        bufferingCheckJob = null
    }

    private fun visibleNativeFrameDimension(
        decodedDimension: Int,
        probedDimension: Int?,
    ): Int =
        if (nativeBackendUsesLibVlc) {
            visibleLibVlcFrameDimension(decodedDimension, probedDimension)
        } else {
            decodedDimension
        }

    /** Updates the current video frame on a background thread. */
    private suspend fun updateFrameAsync() {
        withContext(frameDispatcher) {
            try {
                if (shouldUseNativeVideoSurface()) return@withContext
                val ptr = playerPtr
                if (ptr == 0L) return@withContext
                val publicationEpoch = frameEpoch.get()
                var framePublished = false
                var failedSubtitleRenderer: DesktopSubtitleRenderer? = null
                var subtitleRendererFailed = false
                var subtitleFailureSelectionToken = 0L
                var publishedFrameGeometry: Triple<Int, Int, Float>? = null

                videoReaderMutex.withLock {
                    // Lock the CVPixelBuffer directly — eliminates the Swift-side memcpy.
                    // outInfo = [width, height, bytesPerRow]
                    val outInfo = IntArray(3)
                    val frameAddress = MacNativeBridge.nLockFrame(ptr, outInfo)
                    if (frameAddress == 0L) return@withLock

                    try {
                        val decodedWidth = outInfo[0]
                        val decodedHeight = outInfo[1]
                        val width = visibleNativeFrameDimension(decodedWidth, libVlcTrackInfo?.videoWidth)
                        val height = visibleNativeFrameDimension(decodedHeight, libVlcTrackInfo?.videoHeight)
                        val srcBytesPerRow = outInfo[2]
                        if (
                            width <= 0 ||
                            height <= 0 ||
                            srcBytesPerRow.toLong() < width.toLong() * BGRA_BYTES_PER_PIXEL
                        ) {
                            return@withLock
                        }

                        val frameSizeBytes = srcBytesPerRow.toLong() * height.toLong()
                        val srcBuf =
                            MacNativeBridge.nWrapPointer(frameAddress, frameSizeBytes)
                                ?: return@withLock

                        val frameBuffersChanged =
                            skiaBitmaps[0] == null || skiaBitmapWidth != width || skiaBitmapHeight != height
                        if (frameBuffersChanged) ensureSkiaFrameBuffers(width, height)
                        drainPendingCloseBitmaps()

                        val frameHash =
                            if (usesLibAssSubtitleOverlay && subtitlesEnabled) {
                                // The video pixels can be unchanged while the subtitle overlay
                                // changes. Force publication and make the first subtitle-free frame
                                // publish too.
                                null
                            } else {
                                srcBuf.rewind()
                                calculateFrameHash(srcBuf, width, height, srcBytesPerRow)
                            }
                        if (!frameBuffersChanged && frameHash != null && frameHash == lastFrameHash) {
                            return@withLock
                        }

                        val targetBitmap = selectWritableSkiaBitmap() ?: return@withLock

                        val pixmap = targetBitmap.peekPixels() ?: return@withLock
                        val pixelsAddr = pixmap.addr
                        if (pixelsAddr == 0L) return@withLock

                        // Single copy: CVPixelBuffer → Skia bitmap pixels (no intermediate buffer)
                        srcBuf.rewind()
                        val dstRowBytes = pixmap.rowBytes
                        val dstSizeBytes = dstRowBytes.toLong() * height.toLong()
                        val destBuf =
                            MacNativeBridge.nWrapPointer(pixelsAddr, dstSizeBytes)
                                ?: return@withLock
                        copyBgraFrame(srcBuf, destBuf, width, height, srcBytesPerRow, dstRowBytes)

                        if (usesLibAssSubtitleOverlay && subtitlesEnabled) {
                            val subtitleTimeMs =
                                (
                                    (
                                        MacNativeBridge.nGetCurrentTime(ptr) +
                                            (
                                                ffmpegHlsPlaybackOffsetSeconds.takeIf {
                                                    ffmpegHlsFallbackDurationSeconds != null
                                                }
                                                    ?: 0.0
                                            )
                                    ) * 1000.0
                                ).toLong()
                                    .coerceAtLeast(0L)
                            synchronized(libAssLock) {
                                val renderer = libAssRenderer
                                if (
                                    renderer == null ||
                                    !renderer.blendBgraFrame(
                                        pixels = destBuf,
                                        rowBytes = dstRowBytes,
                                        width = width,
                                        height = height,
                                        timeMs = subtitleTimeMs,
                                    )
                                ) {
                                    failedSubtitleRenderer = renderer
                                    subtitleRendererFailed = true
                                    subtitleFailureSelectionToken = libAssSelectionToken.get()
                                }
                            }
                        }

                        lastSentSkiaBitmap = targetBitmap
                        lastFrameHash = frameHash ?: Int.MIN_VALUE
                        val frameAspectRatio = displayAspectRatio(ptr, width, height)
                        _currentFrameState.value =
                            RenderedFrame(
                                bitmap = targetBitmap,
                                imageBitmap = targetBitmap.asComposeImageBitmap(),
                                displayAspectRatio = frameAspectRatio,
                                width = width,
                                height = height,
                                epoch = publicationEpoch,
                            )
                        if (frameBuffersChanged) {
                            publishedFrameGeometry = Triple(width, height, frameAspectRatio)
                        }
                        framePublished = true
                    } finally {
                        MacNativeBridge.nUnlockFrame(ptr)
                    }
                }

                if (subtitleRendererFailed) {
                    val rendererDisabled =
                        synchronized(libAssLock) {
                            if (libAssRenderer === failedSubtitleRenderer) {
                                libAssRenderer?.close()
                                libAssRenderer = null
                                libAssSubtitleKey = null
                                true
                            } else {
                                false
                            }
                        }
                    if (rendererDisabled) {
                        withContext(Dispatchers.Main) {
                            if (isCurrentLibAssSelection(subtitleFailureSelectionToken)) {
                                usesLibAssSubtitleOverlay = false
                                applyProjectionColorRoute()
                                renderingInfo.subtitleRenderer =
                                    "Compose dialogue fallback (authored renderer failed)"
                            }
                        }
                    }
                }

                if (framePublished) {
                    handlePublishedFrame(publishedFrameGeometry)
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                macLogger.e { "updateFrameAsync() - Exception: ${e.message}" }
            }
        }
    }

    private suspend fun handlePublishedFrame(publishedFrameGeometry: Triple<Int, Int, Float>?) {
        lastFrameUpdateTime = System.currentTimeMillis()
        publishedFrameGeometry?.let { (width, height, aspectRatio) ->
            withContext(Dispatchers.Main) {
                metadata.width = width
                metadata.height = height
                _aspectRatio.value = aspectRatio
            }
            macLogger.d { "Decoded frame geometry updated: ${width}x$height" }
        }
        if (colorOutputVerification != ColorPipelineVerification.RENDERER_CONFIGURED) {
            refreshColorPipelineOutput(ColorPipelineVerification.RENDERER_CONFIGURED)
        }

        // Update loading state if needed on the main thread
        if (isLoading && !seekInProgress) {
            withContext(Dispatchers.Main) {
                isLoading = false
            }
        }
    }

    private fun ensureSkiaFrameBuffers(
        width: Int,
        height: Int,
    ) {
        if (skiaBitmaps[0] != null && skiaBitmapWidth == width && skiaBitmapHeight == height) return

        skiaBitmaps.indices.forEach { index ->
            skiaBitmaps[index]?.let { bitmap ->
                pendingCloseBitmaps.addLast(PendingCloseBitmap(bitmap, PENDING_BITMAP_CLOSE_GRACE_FRAMES))
            }
            skiaBitmaps[index] = null
        }

        val imageInfo = ImageInfo(width, height, ColorType.BGRA_8888, ColorAlphaType.OPAQUE)
        skiaBitmaps.indices.forEach { index ->
            skiaBitmaps[index] = Bitmap().apply { allocPixels(imageInfo) }
        }
        skiaBitmapWidth = width
        skiaBitmapHeight = height
        nextSkiaBitmapIndex = 0
        lastSentSkiaBitmap = null
    }

    private fun selectWritableSkiaBitmap(): Bitmap? {
        val displayedBitmap = displayedSkiaBitmap.get()
        val pendingUiBitmap = pendingUiSkiaBitmap.get()

        repeat(skiaBitmaps.size) {
            val candidateIndex = nextSkiaBitmapIndex
            nextSkiaBitmapIndex = (nextSkiaBitmapIndex + 1) % skiaBitmaps.size
            val candidate = skiaBitmaps[candidateIndex]
            if (
                candidate != null &&
                candidate !== displayedBitmap &&
                candidate !== pendingUiBitmap &&
                candidate !== lastSentSkiaBitmap
            ) {
                return candidate
            }
        }
        return null
    }

    private fun drainPendingCloseBitmaps() {
        val displayedBitmap = displayedSkiaBitmap.get()
        val pendingUiBitmap = pendingUiSkiaBitmap.get()
        val queuedBitmap = _currentFrameState.value?.bitmap

        repeat(pendingCloseBitmaps.size) {
            val pending = pendingCloseBitmaps.removeFirst()
            pending.framesLeft--
            if (
                pending.framesLeft <= 0 &&
                pending.bitmap !== displayedBitmap &&
                pending.bitmap !== pendingUiBitmap &&
                pending.bitmap !== queuedBitmap
            ) {
                runCatching { pending.bitmap.close() }
                    .onFailure { error -> macLogger.e { "Error releasing old frame bitmap: ${error.message}" } }
            } else {
                pendingCloseBitmaps.addLast(pending)
            }
        }
    }

    private fun abandonSkiaFrameBuffers() {
        // ImageBitmap is a zero-copy view over Bitmap pixels. Compose may still retain the last
        // image during teardown, so closing here can free memory while the renderer reads it.
        // Dropping our references lets Skia clean each bitmap after all ImageBitmap holders do.
        skiaBitmaps.indices.forEach { index -> skiaBitmaps[index] = null }
        pendingCloseBitmaps.clear()
        skiaBitmapWidth = 0
        skiaBitmapHeight = 0
        nextSkiaBitmapIndex = 0
        lastSentSkiaBitmap = null
        lastFrameHash = Int.MIN_VALUE
    }

    /**
     * Updates the playback position, slider, and audio levels on a background
     * thread.
     */
    private suspend fun updatePositionAsync() {
        if (!hasMedia || userDragging) return

        try {
            if (shouldUseNativeVideoSurface()) refreshNativeSurfaceAspectRatio()
            val duration = getDurationSafely()
            if (duration <= 0) return

            val current = getPositionSafely()
            val currentDuration = current.secondsAsDuration()
            val totalDuration = duration.secondsAsDuration()

            // Update time text display on the main thread
            withContext(Dispatchers.Main) {
                _currentTime.value = currentDuration
                _duration.value = totalDuration
                _positionText.value = formatTime(currentDuration)
                _durationText.value = formatTime(totalDuration)
            }

            // Handle seek in progress
            val seekTarget = targetSeekTime
            if (seekInProgress && seekTarget != null) {
                if (abs(current - seekTarget) < 0.3) {
                    seekInProgress = false
                    targetSeekTime = null
                    withContext(Dispatchers.Main) {
                        isLoading = false
                    }
                    macLogger.d { "Seek completed, resetting loading state" }
                }
            } else {
                // Update slider position, batched with other UI updates to reduce main thread calls
                val newSliderPos =
                    if (duration > 0) {
                        (current / duration * VideoPlayerState.SLIDER_SCALE)
                            .toFloat()
                            .coerceIn(0f, VideoPlayerState.SLIDER_SCALE)
                    } else {
                        0f
                    }
                withContext(Dispatchers.Main) {
                    sliderPos = newSliderPos
                }
            }

            // Check for looping
            checkLoopingAsync()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            macLogger.e { "Error in updatePositionAsync: ${e.message}" }
        }
    }

    private suspend fun refreshNativeSurfaceAspectRatio() {
        val ptr = playerPtr
        if (ptr == 0L || nativeBackendUsesLibVlc) return
        val width = MacNativeBridge.nGetFrameWidth(ptr)
        val height = MacNativeBridge.nGetFrameHeight(ptr)
        if (width <= 0 || height <= 0) return

        val refreshedAspectRatio = displayAspectRatio(ptr, width, height)
        if (abs(_aspectRatio.value - refreshedAspectRatio) <= ASPECT_RATIO_CHANGE_EPSILON) return
        withContext(Dispatchers.Main) {
            _aspectRatio.value = refreshedAspectRatio
        }
    }

    /** Checks if playback has ended and triggers loop or stop accordingly. */
    private suspend fun checkLoopingAsync() {
        if (
            userDragging ||
            seekInProgress ||
            seekDrainActive.get() ||
            pendingSeekRequest.get() != null
        ) {
            return
        }
        val ptr = playerPtr
        if (ptr == 0L) return

        // Trust AVPlayerItemDidPlayToEndTime: it fires reliably on macOS for both
        // file and HLS playback. A position-based fallback (current >= duration - x)
        // is dangerous because it stops playback x seconds early — the slider
        // freezes at (duration - x) / duration instead of reaching 100%.
        val didPlayToEnd =
            videoReaderMutex.withLock {
                ptr == playerPtr && MacNativeBridge.nConsumeDidPlayToEnd(ptr)
            }
        if (!didPlayToEnd) return

        if (loop) {
            macLogger.d { "checkLoopingAsync() - Loop enabled, restarting video" }
            seekToAsync(0f)
            onRestart?.invoke()
        } else {
            macLogger.d { "checkLoopingAsync() - Video completed, updating state" }
            withContext(Dispatchers.Main) {
                isPlaying = false
            }
            pauseInBackground()
            onPlaybackEnded?.invoke()
        }
    }

    override fun play() {
        lifecycle.ensureUsable()
        macLogger.d { "play() - Starting playback" }
        val uri = lastUri
        if (!hasMedia && uri != null) {
            openUri(uri, requestHeaders = lastRequestHeaders)
            return
        }
        lifecycle.launchControlOperation {
            if (hasMedia) {
                playInBackground()
            } else {
                withContext(Dispatchers.Main) {
                    isPlaying = false
                    isLoading = false
                }
            }
        }
    }

    /** Plays video on a background thread. */
    private suspend fun playInBackground() {
        val ptr = playerPtr
        if (ptr == 0L) return

        try {
            MacNativeBridge.nPlay(ptr)

            withContext(Dispatchers.Main) {
                isPlaying = true
            }

            if (shouldUseNativeVideoSurface()) {
                stopFrameUpdates()
            } else {
                startFrameUpdates()
            }
            startPositionUpdates()
            if (!shouldUseNativeVideoSurface()) {
                startBufferingCheck()
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            macLogger.e { "Error in playInBackground: ${e.message}" }
            handleError(e)
        }
    }

    override fun pause() {
        lifecycle.ensureUsable()
        macLogger.d { "pause() - Pausing playback" }
        lifecycle.launchControlOperation {
            pauseInBackground()
        }
    }

    /** Pauses video on a background thread. */
    private suspend fun pauseInBackground() {
        val ptr = playerPtr
        if (ptr == 0L) return

        try {
            MacNativeBridge.nPause(ptr)

            withContext(Dispatchers.Main) {
                isPlaying = false
                isLoading = false
            }

            if (!shouldUseNativeVideoSurface()) {
                updateFrameAsync()
            }
            stopFrameUpdates()
            stopPositionUpdates()
            stopBufferingCheck()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            macLogger.e { "Error in pauseInBackground: ${e.message}" }
        }
    }

    override fun stop() {
        lifecycle.ensureUsable()
        macLogger.d { "stop() - Stopping playback" }
        lifecycle.launchSourceOperation(
            onScheduled = {
                clearPendingSeekRequests()
                frameEpoch.incrementAndGet()
                invalidateLibAssSelection()
            },
        ) { generation ->
            if (ffmpegHlsFallback != null) {
                cleanupCurrentPlayback()
            } else {
                pauseInBackground()
                if (hasMedia) {
                    seekToAsync(0f, generation)
                }
            }
            withContext(Dispatchers.Main) {
                hasMedia = false
                isLoading = false
                resetState()
            }
            clearFfmpegFallbackTrackState()
            clearLibVlcTrackState()
        }
    }

    override fun releaseSource() {
        lifecycle.ensureUsable()
        lifecycle.launchSourceOperation(
            onScheduled = {
                clearPendingSeekRequests()
                frameEpoch.incrementAndGet()
                lastUri = null
                lastRequestHeaders = emptyMap()
                invalidateLibAssSelection()
            },
        ) {
            cleanupCurrentPlayback()
            clearFfmpegFallbackTrackState()
            clearLibVlcTrackState()
            resetState()
        }
    }

    override fun seekTo(time: Duration) {
        lifecycle.ensureUsable()
        macLogger.d { "seekTo() - Seeking to time: $time" }
        enqueueSeek(PendingSeekRequest.Time(time))
    }

    override fun seekToProgress(progress: Float) {
        seekTo(progress.coerceIn(0f, 1f) * VideoPlayerState.SLIDER_SCALE)
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun seekTo(value: Float) {
        lifecycle.ensureUsable()
        macLogger.d { "seekTo() - Seeking with slider value: $value" }
        enqueueSeek(PendingSeekRequest.Slider(value))
    }

    private fun enqueueSeek(request: PendingSeekRequest) {
        pendingSeekRequest.set(request)
        startSeekDrainIfNeeded()
    }

    private fun startSeekDrainIfNeeded() {
        if (pendingSeekRequest.get() == null || lifecycle.isDisposed) return
        if (!seekDrainActive.compareAndSet(false, true)) return

        try {
            val drainJob =
                lifecycle.launchSourceBoundControlOperation { generation ->
                    // Let slider bursts collapse into one native seek. Calls arriving while this
                    // seek executes overwrite the slot and become at most one follow-up job.
                    delay(10.milliseconds)
                    val request = pendingSeekRequest.getAndSet(null) ?: return@launchSourceBoundControlOperation
                    lifecycle.ensureCurrentSource(generation)
                    when (request) {
                        is PendingSeekRequest.Time -> seekToTimeAsync(request.time, generation)
                        is PendingSeekRequest.Slider -> seekToAsync(request.value, generation)
                    }
                }
            // A source change can cancel a lazy lifecycle job before its block starts, so cleanup
            // must be attached to the Job rather than living only inside that block.
            drainJob.invokeOnCompletion {
                seekDrainActive.set(false)
                if (pendingSeekRequest.get() != null && !lifecycle.isDisposed) {
                    startSeekDrainIfNeeded()
                }
            }
        } catch (error: IllegalStateException) {
            seekDrainActive.set(false)
            if (!lifecycle.isDisposed) throw error
        }
    }

    private fun clearPendingSeekRequests() {
        pendingSeekRequest.set(null)
        seekCompletionToken.incrementAndGet()
    }

    /** Seeks to a position on a background thread. */
    private suspend fun seekToAsync(
        value: Float,
        sourceGeneration: Long? = null,
    ) {
        val duration = getDurationSafely()
        if (duration <= 0) {
            commitSeekStateOnMain(sourceGeneration) { isLoading = false }
            return
        }
        seekToSecondsAsync(
            ((value / VideoPlayerState.SLIDER_SCALE).toDouble() * duration).coerceIn(0.0, duration),
            sourceGeneration,
        )
    }

    private suspend fun seekToTimeAsync(
        time: Duration,
        sourceGeneration: Long? = null,
    ) {
        val duration = getDurationSafely()
        if (duration <= 0) {
            commitSeekStateOnMain(sourceGeneration) { isLoading = false }
            return
        }
        seekToSecondsAsync(
            time.toDouble(kotlin.time.DurationUnit.SECONDS).coerceIn(0.0, duration),
            sourceGeneration,
        )
    }

    private suspend fun seekToSecondsAsync(
        seekTime: Double,
        sourceGeneration: Long? = null,
    ) {
        val completionToken = seekCompletionToken.incrementAndGet()
        if (!commitSeekStateOnMain(sourceGeneration) { isLoading = true }) return

        try {
            sourceGeneration?.let { lifecycle.ensureCurrentSource(it) }
            val duration = getDurationSafely()
            if (duration <= 0) {
                commitSeekStateOnMain(sourceGeneration) { isLoading = false }
                return
            }

            if (!commitSeekStateOnMain(sourceGeneration) {
                    seekInProgress = true
                    targetSeekTime = seekTime
                    _currentTime.value = seekTime.secondsAsDuration()
                    _positionText.value = formatTime(_currentTime.value)
                    sliderPos =
                        (seekTime / duration * VideoPlayerState.SLIDER_SCALE)
                            .toFloat()
                            .coerceIn(0f, VideoPlayerState.SLIDER_SCALE)
                }
            ) {
                return
            }

            lastFrameUpdateTime = System.currentTimeMillis()

            sourceGeneration?.let { lifecycle.ensureCurrentSource(it) }
            val ptr = playerPtr
            if (ptr == 0L) {
                commitSeekStateOnMain(sourceGeneration) {
                    isLoading = false
                    seekInProgress = false
                    targetSeekTime = null
                }
                return
            }
            val resumePlayback = isPlaying
            videoReaderMutex.withLock {
                lastFrameHash = Int.MIN_VALUE
                MacNativeBridge.nSeekTo(ptr, seekTime)
                // AVPlayer does not clear this one-shot flag when seeking away from the end.
                // Any flag observed here belongs to the pre-seek timeline and must be drained.
                MacNativeBridge.nConsumeDidPlayToEnd(ptr)
                if (resumePlayback) MacNativeBridge.nPlay(ptr)
            }
            sourceGeneration?.let { lifecycle.ensureCurrentSource(it) }

            refreshFrameAfterSeek(resumePlayback, sourceGeneration)

            if (!resumePlayback) {
                commitSeekStateOnMain(sourceGeneration) {
                    if (seekCompletionToken.get() == completionToken) {
                        seekInProgress = false
                        targetSeekTime = null
                        isLoading = false
                    }
                }
                return
            }

            // The position poll normally completes the seek. This bounded fallback prevents a
            // stalled/native-delayed seek from leaving loading state stuck; tokens stop an older
            // timeout from completing a newer coalesced seek.
            playerScope.launch {
                delay(300.milliseconds)
                commitSeekStateOnMain(sourceGeneration) {
                    if (seekCompletionToken.get() == completionToken && seekInProgress) {
                        macLogger.d { "seekToAsync() - Forcing end of seek after timeout" }
                        seekInProgress = false
                        targetSeekTime = null
                        isLoading = false
                    }
                }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            macLogger.e { "Error in seekToAsync: ${e.message}" }
            commitSeekStateOnMain(sourceGeneration) {
                if (seekCompletionToken.get() == completionToken) {
                    isLoading = false
                    seekInProgress = false
                    targetSeekTime = null
                }
            }
        }
    }

    private suspend fun refreshFrameAfterSeek(
        resumePlayback: Boolean,
        sourceGeneration: Long?,
    ) {
        // AVPlayer completes seeks asynchronously. Active playback only needs one eager refresh
        // because its regular producer keeps polling; paused playback gets a short bounded retry
        // window so the native completion callback can publish the destination thumbnail.
        delay(10.milliseconds)
        sourceGeneration?.let { lifecycle.ensureCurrentSource(it) }
        if (shouldUseNativeVideoSurface()) return

        val frameAttempts = if (resumePlayback) 1 else PAUSED_SEEK_FRAME_ATTEMPTS
        repeat(frameAttempts) { attempt ->
            if (attempt > 0) delay(PAUSED_SEEK_FRAME_RETRY_DELAY_MS.milliseconds)
            sourceGeneration?.let { lifecycle.ensureCurrentSource(it) }
            updateFrameAsync()
        }
    }

    private suspend fun commitSeekStateOnMain(
        sourceGeneration: Long?,
        block: () -> Unit,
    ): Boolean =
        if (sourceGeneration == null) {
            withContext(Dispatchers.Main) { block() }
            true
        } else {
            commitCurrentSourceOnMain(sourceGeneration, block)
        }

    override fun dispose() {
        macLogger.d { "dispose() - Releasing resources" }
        clearPendingSeekRequests()
        frameEpoch.incrementAndGet()
        val cleanupJob =
            lifecycle.dispose {
                cancelAndResetPlayerScope(recreate = false)
                clearLibAssSubtitleRenderer()

                val fallbackToClose = ffmpegHlsFallback
                val preparedSourceToClose = preparedPipelineSource
                ffmpegHlsFallback = null
                ffmpegHlsFallbackDurationSeconds = null
                ffmpegHlsInputColorInfo = null
                ffmpegHlsOutputColorInfo = null
                ffmpegHlsHdrCmafPassthrough = false
                ffmpegHlsToneMappedHdrToSdr = false
                ffmpegHlsAvFoundationCompatibleTranscode = false
                ffmpegHlsSourceUri = null
                ffmpegHlsSelectedAudioStreamIndex = null
                ffmpegHlsSelectedSubtitleStreamIndex = null
                ffmpegHlsPlaybackOffsetSeconds = 0.0
                preparedPipelineSource = null
                preparedPipelineOriginalColorInfo = null
                libVlcBackendActive = false
                libVlcSourceUri = null
                libVlcTrackInfo = null
                libVlcSelectedAudioStreamIndex = null
                libVlcSelectedSubtitleStreamIndex = null
                libVlcNativeSurfaceRequested = false

                val ptrToDispose =
                    withContext(frameDispatcher) {
                        val ptr = synchronized(nativeInstanceLock) { playerPtrAtomic.getAndSet(0L) }
                        abandonSkiaFrameBuffers()
                        ptr
                    }

                if (ptrToDispose != 0L) {
                    macLogger.d { "dispose() - Disposing native player" }
                    try {
                        synchronized(nativeInstanceLock) {
                            MacNativeBridge.nDisposePlayer(ptrToDispose)
                        }
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        macLogger.e { "Error disposing player: ${e.message}" }
                    }
                }

                nativeBackendUsesLibVlc = false
                fallbackToClose?.close()
                preparedSourceToClose?.close()
                resetState()
                onPlaybackEnded = null
                onRestart = null
            }
        cleanupJob?.invokeOnCompletion {
            try {
                cleanupScope.cancel()
            } catch (_: Exception) {
                // Cleanup has already completed; cancellation is best-effort.
            }
        }
    }

    /** Resets the player's state. */
    private suspend fun resetState() {
        frameEpoch.incrementAndGet()
        _currentFrameState.value = null
        withContext(Dispatchers.Main) {
            hasMedia = false
            isPlaying = false
            isLoading = false
            _currentTime.value = Duration.ZERO
            _duration.value = Duration.ZERO
            _chapters = emptyList()
            _positionText.value = "00:00"
            _durationText.value = "00:00"
            _aspectRatio.value = 16f / 9f
            error = null
            (currentFrameState as MutableState).value = null
            displayedSkiaBitmap.set(null)
        }
        pendingUiSkiaBitmap.set(null)
    }

    /**
     * Sets an error in a consistent way, ensuring it's always set on the main thread.
     * For synchronous calls, this will block until the error is set.
     */
    private fun setPlayerError(error: VideoPlayerError) {
        macLogger.e { "setPlayerError() - Setting error: $error" }

        // For properties that need to be updated on the main thread,
        // use runBlocking to ensure the update happens immediately
        runBlocking {
            withContext(Dispatchers.Main) {
                isLoading = false
                this@MacVideoPlayerState.error = error
            }
        }
    }

    /** Handles errors by updating the state and logging the error. */
    private suspend fun handleError(e: Exception) {
        macLogger.e { "handleError() - Player error: ${e.message}" }

        // Since this is called from a suspend function, we can use withContext directly
        withContext(Dispatchers.Main) {
            isLoading = false
            error =
                colorPipelineFailureOrNull(e)
                    ?: if (e is UnsupportedOperationException) {
                        VideoPlayerError.CodecError("Error: ${e.message}")
                    } else {
                        VideoPlayerError.SourceError("Error: ${e.message}")
                    }
        }
    }

    private fun colorPipelineFailureOrNull(error: Exception): VideoPlayerError.ColorPipelineError? {
        if (error !is UnsupportedOperationException) return null
        val message = error.message.orEmpty()
        val describesColorFailure =
            activeSourceColorInfo.isHdr ||
                message.contains("HDR", ignoreCase = true) ||
                message.contains("Dolby Vision", ignoreCase = true) ||
                message.contains("color transfer", ignoreCase = true) ||
                message.contains("cannot prove that this input is SDR", ignoreCase = true)
        if (!describesColorFailure) return null

        synchronized(colorPipelineController) {
            colorPipelineController.pipelineErrorOrNull()
        }?.let { pipelineError ->
            return pipelineError.copy(message = message.ifBlank { pipelineError.message })
        }
        return VideoPlayerError.ColorPipelineError(
            reason =
                if (activeSourceColorInfo.dynamicRange == VideoDynamicRange.UNKNOWN) {
                    ColorPipelineFallbackReason.SOURCE_COLOR_UNKNOWN
                } else {
                    ColorPipelineFallbackReason.TONE_MAPPER_UNAVAILABLE
                },
            message = message.ifBlank { "No verified macOS color fallback is available." },
        )
    }

    /** Retrieves the current playback time from the native player. */
    private suspend fun getPositionSafely(): Double {
        val ptr = playerPtr
        if (ptr == 0L) return 0.0
        return try {
            val current = MacNativeBridge.nGetCurrentTime(ptr)
            val fallbackDuration = ffmpegHlsFallbackDurationSeconds
            if (fallbackDuration != null && fallbackDuration > 0.0) {
                (ffmpegHlsPlaybackOffsetSeconds + current).coerceIn(0.0, fallbackDuration)
            } else {
                current
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            macLogger.e { "Error getting position: ${e.message}" }
            0.0
        }
    }

    /** Retrieves the total duration of the video from the native player. */
    private suspend fun getDurationSafely(): Double {
        ffmpegHlsFallbackDurationSeconds?.let { duration ->
            if (duration > 0.0) return duration
        }
        libVlcTrackInfo?.durationSeconds?.let { duration ->
            if (duration > 0.0) return duration
        }

        val ptr = playerPtr
        if (ptr == 0L) return 0.0
        return try {
            MacNativeBridge.nGetVideoDuration(ptr)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            macLogger.e { "Error getting duration: ${e.message}" }
            0.0
        }
    }

    /**
     * Applies the current volume setting to the native player. If no player
     * is available, the volume is simply stored in _volumeState and will be
     * applied when the player is initialized.
     */
    private suspend fun applyVolume() {
        val ptr = playerPtr
        if (ptr != 0L) {
            try {
                MacNativeBridge.nSetVolume(ptr, _volumeState.value)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                macLogger.e { "Error applying volume: ${e.message}" }
            }
        }
    }

    /**
     * Applies the current playback speed setting to the native player. If no player
     * is available, the speed is simply stored in _playbackSpeedState and will be
     * applied when the player is initialized.
     */
    private suspend fun applyPlaybackSpeed() {
        val ptr = playerPtr
        if (ptr != 0L) {
            try {
                MacNativeBridge.nSetPlaybackSpeed(ptr, _playbackSpeedState.value)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                macLogger.e { "Error applying playback speed: ${e.message}" }
            }
        }
    }

    private suspend fun commitCurrentSourceOnMain(
        generation: Long,
        block: () -> Unit,
    ): Boolean =
        withContext(Dispatchers.Main) {
            lifecycle.commitCurrentSource(generation, block)
        }

    private fun errorForTrackOperation(e: Exception): VideoPlayerError =
        if (e is UnsupportedOperationException) {
            VideoPlayerError.CodecError("Error: ${e.message}")
        } else {
            VideoPlayerError.SourceError("Error: ${e.message}")
        }

    override fun selectAudioTrack(track: AudioTrack?): TrackSelectionResult {
        lifecycle.ensureUsable()
        if (track != null && availableAudioTracks.none { it.id == track.id }) {
            return TrackSelectionResult.NotFound(track.id)
        }

        val selectedLibVlcStreamIndex =
            track
                ?.id
                ?.takeIf(::isMacLibVlcAudioTrackId)
                ?.let(::libVlcTrackStreamIndex)

        if (track != null && selectedLibVlcStreamIndex != null) {
            lifecycle.launchSourceBoundControlOperation { generation ->
                selectLibVlcAudioTrack(track, selectedLibVlcStreamIndex, generation)
            }
            return TrackSelectionResult.Selected(track.id)
        }

        val selectedStreamIndex =
            track
                ?.id
                ?.takeIf(::isMacExternalHlsAudioTrackId)
                ?.let(::externalHlsTrackStreamIndex)

        if (track != null && selectedStreamIndex != null) {
            lifecycle.launchSourceBoundControlOperation { generation ->
                switchFfmpegAudioTrack(track, selectedStreamIndex, generation)
            }
            return TrackSelectionResult.Selected(track.id)
        }

        lifecycle.launchSourceBoundControlOperation { generation ->
            commitCurrentSourceOnMain(generation) {
                currentAudioTrack = track
            }
        }
        return track.audioTrackSelectionResult()
    }

    private suspend fun selectLibVlcAudioTrack(
        track: AudioTrack,
        streamIndex: Int,
        generation: Long,
    ) {
        val ordinal =
            libVlcTrackInfo
                ?.audioStreams
                ?.firstOrNull { it.streamIndex == streamIndex }
                ?.ordinal
        val ptr = playerPtr
        val applied = ordinal != null && ptr != 0L && MacNativeBridge.nSelectLibVlcAudioTrack(ptr, ordinal)
        lifecycle.ensureCurrentSource(generation)
        commitCurrentSourceOnMain(generation) {
            if (applied) {
                libVlcSelectedAudioStreamIndex = streamIndex
                currentAudioTrack = track
            } else {
                error = VideoPlayerError.CodecError("Failed to select libVLC audio track: ${track.id}")
            }
        }
    }

    private suspend fun switchFfmpegAudioTrack(
        track: AudioTrack,
        streamIndex: Int,
        generation: Long,
    ) {
        val sourceUri = ffmpegHlsSourceUri
        if (sourceUri == null) {
            commitCurrentSourceOnMain(generation) {
                currentAudioTrack = track
            }
            return
        }

        if (ffmpegHlsSelectedAudioStreamIndex == streamIndex) {
            commitCurrentSourceOnMain(generation) {
                currentAudioTrack = track
            }
            return
        }

        val shouldResumePlayback = isPlaying
        val selectedSubtitleStreamIndex = ffmpegHlsSelectedSubtitleStreamIndex
        val restartPositionSeconds = getPositionSafely()
        val durationSeconds = getDurationSafely()
        val restartSliderPos =
            if (durationSeconds > 0.0) {
                (restartPositionSeconds / durationSeconds * VideoPlayerState.SLIDER_SCALE)
                    .toFloat()
                    .coerceIn(0f, VideoPlayerState.SLIDER_SCALE)
            } else {
                sliderPos
            }
        try {
            val loadingCommitted =
                commitCurrentSourceOnMain(generation) {
                    isLoading = true
                    error = null
                    sliderPos = restartSliderPos
                    _positionText.value = formatTime(restartPositionSeconds.secondsAsDuration())
                }
            if (!loadingCommitted) {
                return
            }

            cleanupCurrentPlayback()
            lifecycle.ensureCurrentSource(generation)
            ensurePlayerInitialized()
            lifecycle.ensureCurrentSource(generation)

            ffmpegHlsSelectedAudioStreamIndex = streamIndex
            ffmpegHlsSelectedSubtitleStreamIndex = selectedSubtitleStreamIndex
            ffmpegHlsPlaybackOffsetSeconds = restartPositionSeconds
            val playableUri = prepareUriForMacPlayback(sourceUri, lastRequestHeaders)
            val opened = openMediaUri(playableUri, lastRequestHeaders)
            lifecycle.ensureCurrentSource(generation)
            if (!opened) {
                closeFfmpegHlsFallback()
                clearFfmpegFallbackTrackState()
                commitCurrentSourceOnMain(generation) {
                    isLoading = false
                    error = VideoPlayerError.SourceError("Failed to switch audio track")
                }
                return
            }

            coroutineScope {
                launch { updateFrameRateInfo() }
                launch { updateMetadata() }
            }

            if (surfaceWidth > 0 && surfaceHeight > 0) {
                applyOutputScaling()
            }

            val selectionCommitted =
                commitCurrentSourceOnMain(generation) {
                    currentAudioTrack = track
                    hasMedia = true
                    isLoading = false
                    isPlaying = shouldResumePlayback
                }
            if (!selectionCommitted) {
                return
            }

            if (shouldUseNativeVideoSurface()) {
                stopFrameUpdates()
            } else {
                startFrameUpdates()
            }
            startPositionUpdates()
            if (!shouldUseNativeVideoSurface()) {
                updateFrameAsync()
            }
            if (!shouldUseNativeVideoSurface()) {
                startBufferingCheck()
            }

            if (shouldResumePlayback) {
                playInBackground()
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            macLogger.e { "switchFfmpegAudioTrack() - Exception: ${e.message}" }
            closeFfmpegHlsFallback()
            clearFfmpegFallbackTrackState()
            commitCurrentSourceOnMain(generation) {
                isLoading = false
                error = errorForTrackOperation(e)
            }
        }
    }

    override fun selectSubtitleTrack(track: SubtitleTrack?): TrackSelectionResult {
        lifecycle.ensureUsable()
        if (track == null && libVlcBackendActive) {
            var selectionToken = 0L
            lifecycle.launchSourceBoundControlOperation(
                onScheduled = { selectionToken = invalidateLibAssSelection() },
            ) { generation ->
                clearLibAssSubtitleRenderer(selectionToken)
                disableLibVlcSubtitles(generation)
            }
            return TrackSelectionResult.Disabled
        }
        if (track != null && track.isEmbedded && availableSubtitleTracks.none { it.id == track.id }) {
            return TrackSelectionResult.NotFound(track.id)
        }

        val selectedLibVlcStreamIndex =
            track
                ?.id
                ?.takeIf(::isMacLibVlcSubtitleTrackId)
                ?.let(::libVlcTrackStreamIndex)

        if (track != null && selectedLibVlcStreamIndex != null) {
            var selectionToken = 0L
            lifecycle.launchSourceBoundControlOperation(
                onScheduled = { selectionToken = invalidateLibAssSelection() },
            ) { generation ->
                selectLibVlcSubtitleTrack(track, selectedLibVlcStreamIndex, selectionToken, generation)
            }
            return TrackSelectionResult.Selected(track.id)
        }

        val selectedStreamIndex =
            track
                ?.id
                ?.takeIf(::isMacExternalHlsSubtitleTrackId)
                ?.let(::externalHlsTrackStreamIndex)

        if (track != null && selectedStreamIndex != null) {
            lifecycle.launchSourceBoundControlOperation(
                onScheduled = { invalidateLibAssSelection() },
            ) { generation ->
                switchFfmpegSubtitleTrack(track, selectedStreamIndex, generation)
            }
            return TrackSelectionResult.Selected(track.id)
        }

        if (track != null && isAssLikeTrack(track)) {
            var selectionToken = 0L
            lifecycle.launchSourceBoundControlOperation(
                onScheduled = { selectionToken = invalidateLibAssSelection() },
            ) { generation ->
                try {
                    markLibAssSubtitlePreparing(track, streamIndex = null, selectionToken)
                    configureLibAssSubtitleRenderer(
                        track = track,
                        streamIndex = null,
                        selectionToken = selectionToken,
                        sourceGeneration = generation,
                    )
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    if (lifecycle.isCurrentSource(generation) && isCurrentLibAssSelection(selectionToken)) {
                        clearLibAssSubtitleRenderer(selectionToken)
                        commitCurrentSourceOnMain(generation) {
                            if (!isCurrentLibAssSelection(selectionToken)) return@commitCurrentSourceOnMain
                            currentSubtitleTrack = null
                            subtitlesEnabled = false
                            error = VideoPlayerError.CodecError("ASS subtitle rendering failed: ${e.message}")
                        }
                    }
                }
            }
            return TrackSelectionResult.Selected(track.id)
        }

        var selectionToken = 0L
        lifecycle.launchSourceBoundControlOperation(
            onScheduled = { selectionToken = invalidateLibAssSelection() },
        ) { generation ->
            clearLibAssSubtitleRenderer(selectionToken)
            commitCurrentSourceOnMain(generation) {
                currentSubtitleTrack = track
                subtitlesEnabled = track != null
            }
        }
        return track.subtitleTrackSelectionResult()
    }

    private suspend fun selectLibVlcSubtitleTrack(
        track: SubtitleTrack,
        streamIndex: Int,
        selectionToken: Long,
        generation: Long,
    ) {
        val subtitleOrdinal =
            libVlcTrackInfo
                ?.subtitleStreams
                ?.firstOrNull { it.streamIndex == streamIndex }
                ?.ordinal
        val ptr = playerPtr
        val applied =
            ptr != 0L &&
                subtitleOrdinal != null &&
                MacNativeBridge.nSelectLibVlcSubtitleTrack(ptr, subtitleOrdinal)
        lifecycle.ensureCurrentSource(generation)
        commitCurrentSourceOnMain(generation) {
            if (!isCurrentLibAssSelection(selectionToken)) return@commitCurrentSourceOnMain
            if (applied) {
                libVlcSelectedSubtitleStreamIndex = streamIndex
                currentSubtitleTrack = track
                subtitlesEnabled = true
                renderingInfo.subtitleRenderer = "libVLC native subtitle renderer"
                renderingInfo.subtitleSource = "embedded stream $streamIndex"
            } else {
                libVlcSelectedSubtitleStreamIndex = null
                currentSubtitleTrack = null
                subtitlesEnabled = false
                error = VideoPlayerError.CodecError("Failed to select the native libVLC subtitle track")
            }
        }
    }

    private suspend fun disableLibVlcSubtitles(generation: Long) {
        val ptr = playerPtr
        val applied = ptr != 0L && MacNativeBridge.nDisableLibVlcSubtitles(ptr)
        lifecycle.ensureCurrentSource(generation)
        commitCurrentSourceOnMain(generation) {
            if (applied) {
                libVlcSelectedSubtitleStreamIndex = null
                subtitlesEnabled = false
                currentSubtitleTrack = null
                renderingInfo.subtitleRenderer = null
                renderingInfo.subtitleSource = null
            } else {
                error = VideoPlayerError.CodecError("Failed to disable libVLC subtitles")
            }
        }
    }

    private suspend fun applyLibVlcSelectedTracks() {
        if (!libVlcBackendActive) return
        val info = libVlcTrackInfo ?: return
        val ptr = playerPtr
        if (ptr == 0L) return

        libVlcSelectedAudioStreamIndex
            ?.let { streamIndex -> info.audioStreams.firstOrNull { it.streamIndex == streamIndex }?.ordinal }
            ?.let { ordinal -> MacNativeBridge.nSelectLibVlcAudioTrack(ptr, ordinal) }

        val selectedSubtitleOrdinal =
            libVlcSelectedSubtitleStreamIndex
                ?.let { streamIndex -> info.subtitleStreams.firstOrNull { it.streamIndex == streamIndex }?.ordinal }

        if (selectedSubtitleOrdinal != null) {
            MacNativeBridge.nSelectLibVlcSubtitleTrack(ptr, selectedSubtitleOrdinal)
        }
    }

    private suspend fun switchFfmpegSubtitleTrack(
        track: SubtitleTrack?,
        streamIndex: Int?,
        generation: Long,
    ) {
        val sourceUri = ffmpegHlsSourceUri
        if (sourceUri == null) {
            commitCurrentSourceOnMain(generation) {
                currentSubtitleTrack = track
                subtitlesEnabled = track != null
            }
            return
        }

        if (ffmpegHlsSelectedSubtitleStreamIndex == streamIndex) {
            commitCurrentSourceOnMain(generation) {
                currentSubtitleTrack = track
                subtitlesEnabled = track != null
            }
            return
        }

        switchMacHlsSubtitleTrack(
            sourceUri = sourceUri,
            track = track,
            streamIndex = streamIndex,
            selectedAudioStreamIndex = ffmpegHlsSelectedAudioStreamIndex,
            failureMessage = "Failed to switch subtitle track",
            generation = generation,
        )
    }

    private suspend fun switchMacHlsSubtitleTrack(
        sourceUri: String,
        track: SubtitleTrack?,
        streamIndex: Int?,
        selectedAudioStreamIndex: Int?,
        failureMessage: String,
        generation: Long,
    ) {
        val shouldResumePlayback = isPlaying
        val restartPositionSeconds = getPositionSafely()
        val durationSeconds = getDurationSafely()
        val restartSliderPos =
            if (durationSeconds > 0.0) {
                (restartPositionSeconds / durationSeconds * VideoPlayerState.SLIDER_SCALE)
                    .toFloat()
                    .coerceIn(0f, VideoPlayerState.SLIDER_SCALE)
            } else {
                sliderPos
            }
        try {
            if (track != null) {
                if (!ExternalHlsFallbackSupport.hasSubtitleRenderer(playbackOptions.extensions)) {
                    commitCurrentSourceOnMain(generation) {
                        isLoading = false
                        currentSubtitleTrack = null
                        subtitlesEnabled = false
                        error =
                            VideoPlayerError.CodecError(
                                "Full ASS/SSA rendering through the external HLS fallback requires VLC " +
                                    "or an ffmpeg build " +
                                    "with libass and the subtitles filter enabled. " +
                                    "No suitable external renderer was found.",
                            )
                    }
                    return
                }
            }

            val loadingCommitted =
                commitCurrentSourceOnMain(generation) {
                    isLoading = true
                    error = null
                    sliderPos = restartSliderPos
                    _positionText.value = formatTime(restartPositionSeconds.secondsAsDuration())
                }
            if (!loadingCommitted) {
                return
            }

            cleanupCurrentPlayback()
            lifecycle.ensureCurrentSource(generation)
            ensurePlayerInitialized()
            lifecycle.ensureCurrentSource(generation)

            ffmpegHlsSelectedAudioStreamIndex = selectedAudioStreamIndex
            ffmpegHlsSelectedSubtitleStreamIndex = streamIndex
            ffmpegHlsPlaybackOffsetSeconds = restartPositionSeconds
            val playableUri = prepareUriForMacPlayback(sourceUri, lastRequestHeaders)
            val opened = openMediaUri(playableUri, lastRequestHeaders)
            lifecycle.ensureCurrentSource(generation)
            if (!opened) {
                closeFfmpegHlsFallback()
                clearFfmpegFallbackTrackState()
                commitCurrentSourceOnMain(generation) {
                    isLoading = false
                    error = VideoPlayerError.SourceError(failureMessage)
                }
                return
            }

            coroutineScope {
                launch { updateFrameRateInfo() }
                launch { updateMetadata() }
            }

            if (surfaceWidth > 0 && surfaceHeight > 0) {
                applyOutputScaling()
            }

            val selectionCommitted =
                commitCurrentSourceOnMain(generation) {
                    currentSubtitleTrack = track
                    subtitlesEnabled = track != null
                    hasMedia = true
                    isLoading = false
                    isPlaying = shouldResumePlayback
                }
            if (!selectionCommitted) {
                return
            }

            if (shouldUseNativeVideoSurface()) {
                stopFrameUpdates()
            } else {
                startFrameUpdates()
            }
            startPositionUpdates()
            if (!shouldUseNativeVideoSurface()) {
                updateFrameAsync()
            }
            if (!shouldUseNativeVideoSurface()) {
                startBufferingCheck()
            }

            if (shouldResumePlayback) {
                playInBackground()
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            macLogger.e { "switchMacHlsSubtitleTrack() - Exception: ${e.message}" }
            closeFfmpegHlsFallback()
            clearFfmpegFallbackTrackState()
            commitCurrentSourceOnMain(generation) {
                isLoading = false
                error = errorForTrackOperation(e)
            }
        }
    }

    override fun addSubtitleTrack(track: SubtitleTrack) {
        lifecycle.ensureUsable()
        val externalTrack = track.copy(isEmbedded = false)
        _availableSubtitleTracks.removeAll { it.id == externalTrack.id }
        _availableSubtitleTracks.add(externalTrack)
    }

    override fun removeSubtitleTrack(trackId: String) {
        lifecycle.ensureUsable()
        val selectedTrack = currentSubtitleTrack
        _availableSubtitleTracks.removeAll { it.id == trackId && it.isExternal }
        if (selectedTrack?.id == trackId && selectedTrack.isExternal) {
            disableSubtitles()
        }
    }

    override fun clearExternalSubtitleTracks() {
        lifecycle.ensureUsable()
        val selectedTrack = currentSubtitleTrack
        _availableSubtitleTracks.removeAll { it.isExternal }
        if (selectedTrack?.isExternal == true) {
            disableSubtitles()
        }
    }

    override fun disableSubtitles(): TrackSelectionResult {
        lifecycle.ensureUsable()
        val selectedTrack = currentSubtitleTrack
        if (usesLibAssSubtitleOverlay || libAssSubtitleSource != null) {
            var selectionToken = 0L
            lifecycle.launchSourceBoundControlOperation(
                onScheduled = { selectionToken = invalidateLibAssSelection() },
            ) { generation ->
                commitCurrentSourceOnMain(generation) {
                    if (!isCurrentLibAssSelection(selectionToken)) return@commitCurrentSourceOnMain
                    usesLibAssSubtitleOverlay = false
                    applyProjectionColorRoute()
                    renderingInfo.subtitleRenderer = null
                    renderingInfo.subtitleSource = null
                }
                clearLibAssSubtitleRenderer(selectionToken)
                if (libVlcBackendActive) {
                    disableLibVlcSubtitles(generation)
                } else {
                    commitCurrentSourceOnMain(generation) {
                        if (!isCurrentLibAssSelection(selectionToken)) return@commitCurrentSourceOnMain
                        subtitlesEnabled = false
                        currentSubtitleTrack = null
                    }
                }
            }
            return TrackSelectionResult.Disabled
        }

        if (libVlcBackendActive && selectedTrack?.id?.let(::isMacLibVlcSubtitleTrackId) == true) {
            var selectionToken = 0L
            lifecycle.launchSourceBoundControlOperation(
                onScheduled = { selectionToken = invalidateLibAssSelection() },
            ) { generation ->
                clearLibAssSubtitleRenderer(selectionToken)
                disableLibVlcSubtitles(generation)
            }
            return TrackSelectionResult.Disabled
        }

        if (ffmpegHlsSourceUri != null && ffmpegHlsSelectedSubtitleStreamIndex != null) {
            lifecycle.launchSourceBoundControlOperation(
                onScheduled = { invalidateLibAssSelection() },
            ) { generation ->
                switchFfmpegSubtitleTrack(track = null, streamIndex = null, generation = generation)
            }
            return TrackSelectionResult.Disabled
        }
        invalidateLibAssSelection()
        subtitlesEnabled = false
        currentSubtitleTrack = null
        return TrackSelectionResult.Disabled
    }

    override fun clearError() {
        lifecycle.ensureUsable()
        macLogger.d { "clearError() - Clearing error" }
        error = null
    }

    /**
     * Toggles the fullscreen state of the video player
     */
    override fun toggleFullscreen() {
        lifecycle.ensureUsable()
        // Update the state immediately for test synchronization
        isFullscreen = !isFullscreen
    }

    /**
     * Called when the player surface is resized. Debounces rapid events and
     * asks the native layer to decode at the surface size instead of native
     * resolution, saving significant memory for high-resolution video.
     */
    fun onResized(
        width: Int,
        height: Int,
    ) {
        lifecycle.ensureUsable()
        if (width <= 0 || height <= 0) return
        if (width == surfaceWidth && height == surfaceHeight) return

        surfaceWidth = width
        surfaceHeight = height

        val requestToken = resizeRequestToken.incrementAndGet()
        isResizing.set(true)
        resizeJob?.cancel()
        resizeJob =
            lifecycle.launchSourceBoundControlOperation {
                try {
                    delay(120.milliseconds)
                    applyOutputScaling()
                } finally {
                    if (resizeRequestToken.get() == requestToken) {
                        isResizing.set(false)
                    }
                }
            }
    }

    /**
     * Asks the native layer to produce frames at the display surface size
     * instead of full native resolution. Saves significant memory for 4K+ video.
     */
    private suspend fun applyOutputScaling() {
        val sw = surfaceWidth
        val sh = surfaceHeight
        if (sw <= 0 || sh <= 0) return
        val ptr = playerPtr
        if (ptr == 0L) return

        videoReaderMutex.withLock {
            if (ptr != playerPtr) return@withLock
            lastFrameHash = Int.MIN_VALUE
            MacNativeBridge.nSetOutputSize(ptr, sw, sh)
        }
    }
}

private data class MacNativePlaybackDiagnostics(
    val totalFrames: Long?,
    val renderedFrames: Long?,
    val droppedFrames: Long?,
    val maximumAvSyncOffsetMs: Float?,
)

private fun String.toMacNativePlaybackDiagnostics(): MacNativePlaybackDiagnostics {
    val values =
        split(';')
            .mapNotNull { entry ->
                val separatorIndex = entry.indexOf('=')
                if (separatorIndex <= 0) return@mapNotNull null
                entry.substring(0, separatorIndex) to entry.substring(separatorIndex + 1)
            }.toMap()
    return MacNativePlaybackDiagnostics(
        totalFrames = values["totalFrames"].nonNegativeLongOrNull(),
        renderedFrames = values["renderedFrames"].nonNegativeLongOrNull(),
        droppedFrames = values["droppedFrames"].nonNegativeLongOrNull(),
        maximumAvSyncOffsetMs = values["maxAvSyncMs"].nonNegativeFloatOrNull(),
    )
}

private fun String?.nonNegativeLongOrNull(): Long? = this?.toLongOrNull()?.takeIf { it >= 0L }

private fun String?.nonNegativeFloatOrNull(): Float? = this?.toFloatOrNull()?.takeIf { it >= 0f && it.isFinite() }

private const val MAC_HDR10_PLUS_METADATA_FAILURE_PREFIX = "HDR10_PLUS_METADATA:"
