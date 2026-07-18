package io.github.kdroidfilter.composemediaplayer.windows

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import io.github.kdroidfilter.composemediaplayer.AudioTrack
import io.github.kdroidfilter.composemediaplayer.CacheClearResult
import io.github.kdroidfilter.composemediaplayer.ColorPipelineFallbackReason
import io.github.kdroidfilter.composemediaplayer.ColorPipelineVerification
import io.github.kdroidfilter.composemediaplayer.DecoderColorCapabilities
import io.github.kdroidfilter.composemediaplayer.DesktopPlayerLifecycle
import io.github.kdroidfilter.composemediaplayer.DesktopVideoBackend
import io.github.kdroidfilter.composemediaplayer.DisplayColorCapabilities
import io.github.kdroidfilter.composemediaplayer.DolbyVisionPolicy
import io.github.kdroidfilter.composemediaplayer.DynamicMetadataHandling
import io.github.kdroidfilter.composemediaplayer.DynamicRangePolicy
import io.github.kdroidfilter.composemediaplayer.ExternalHlsFallbackSupport
import io.github.kdroidfilter.composemediaplayer.ExternalVlcLocator
import io.github.kdroidfilter.composemediaplayer.HlsFallbackSource
import io.github.kdroidfilter.composemediaplayer.HlsQualitySelectionResult
import io.github.kdroidfilter.composemediaplayer.InitialPlayerState
import io.github.kdroidfilter.composemediaplayer.JvmDecodedVideoColorSignalCodec
import io.github.kdroidfilter.composemediaplayer.JvmExternalFallbackContainerSupport
import io.github.kdroidfilter.composemediaplayer.JvmLibVlcAudioStream
import io.github.kdroidfilter.composemediaplayer.JvmLibVlcInstallation
import io.github.kdroidfilter.composemediaplayer.JvmLibVlcMediaProbe
import io.github.kdroidfilter.composemediaplayer.JvmLibVlcSubtitleStream
import io.github.kdroidfilter.composemediaplayer.JvmLibVlcTrackInfo
import io.github.kdroidfilter.composemediaplayer.LIBVLC_CANVAS_AUDIO_TRACK_ID_PREFIX
import io.github.kdroidfilter.composemediaplayer.LIBVLC_CANVAS_SUBTITLE_TRACK_ID_PREFIX
import io.github.kdroidfilter.composemediaplayer.PlaybackDiagnostics
import io.github.kdroidfilter.composemediaplayer.PlayerCapabilities
import io.github.kdroidfilter.composemediaplayer.RendererColorCapabilities
import io.github.kdroidfilter.composemediaplayer.SubtitleFormat
import io.github.kdroidfilter.composemediaplayer.SubtitleTrack
import io.github.kdroidfilter.composemediaplayer.TrackSelectionResult
import io.github.kdroidfilter.composemediaplayer.VideoColorInfo
import io.github.kdroidfilter.composemediaplayer.VideoColorPipelineController
import io.github.kdroidfilter.composemediaplayer.VideoColorPipelineStatus
import io.github.kdroidfilter.composemediaplayer.VideoDynamicRange
import io.github.kdroidfilter.composemediaplayer.VideoMetadata
import io.github.kdroidfilter.composemediaplayer.VideoPlaybackOptions
import io.github.kdroidfilter.composemediaplayer.VideoPlayerError
import io.github.kdroidfilter.composemediaplayer.VideoPlayerState
import io.github.kdroidfilter.composemediaplayer.VideoProjectionSettings
import io.github.kdroidfilter.composemediaplayer.VideoProjectionViewControlMode
import io.github.kdroidfilter.composemediaplayer.VideoProjectionViewSettings
import io.github.kdroidfilter.composemediaplayer.VideoRenderingInfo
import io.github.kdroidfilter.composemediaplayer.VideoSurfaceKind
import io.github.kdroidfilter.composemediaplayer.VideoTextureCrop
import io.github.kdroidfilter.composemediaplayer.audioTrackSelectionResult
import io.github.kdroidfilter.composemediaplayer.externalHlsTrackStreamIndex
import io.github.kdroidfilter.composemediaplayer.forcedJvmDesktopBackend
import io.github.kdroidfilter.composemediaplayer.isExternalHlsAudioTrackId
import io.github.kdroidfilter.composemediaplayer.isExternalHlsSubtitleTrackId
import io.github.kdroidfilter.composemediaplayer.isSafeForUnmanagedSdrFallback
import io.github.kdroidfilter.composemediaplayer.jvmCanvasRendererLabel
import io.github.kdroidfilter.composemediaplayer.jvmPlayerCapabilities
import io.github.kdroidfilter.composemediaplayer.renderingInfoLabel
import io.github.kdroidfilter.composemediaplayer.requestHeadersLineString
import io.github.kdroidfilter.composemediaplayer.requiresProjectionRenderer
import io.github.kdroidfilter.composemediaplayer.sanitizedRequestHeaders
import io.github.kdroidfilter.composemediaplayer.subtitle.DesktopAssBlendResult
import io.github.kdroidfilter.composemediaplayer.subtitle.DesktopAssSubtitleSession
import io.github.kdroidfilter.composemediaplayer.subtitle.extractEmbeddedDesktopAssSubtitle
import io.github.kdroidfilter.composemediaplayer.subtitle.isAssLikeDesktopTrack
import io.github.kdroidfilter.composemediaplayer.subtitle.loadSubtitleContent
import io.github.kdroidfilter.composemediaplayer.subtitleTrackSelectionResult
import io.github.kdroidfilter.composemediaplayer.toConfirmedDecoderCapabilities
import io.github.kdroidfilter.composemediaplayer.util.TaggedLogger
import io.github.kdroidfilter.composemediaplayer.util.formatTime
import io.github.kdroidfilter.composemediaplayer.util.hundredNanosecondsAsDuration
import io.github.kdroidfilter.composemediaplayer.util.inWhole100NanosecondTicks
import io.github.kdroidfilter.composemediaplayer.util.secondsAsDuration
import io.github.kdroidfilter.composemediaplayer.util.toSecondsDouble
import io.github.vinceglb.filekit.PlatformFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import java.awt.Component
import java.io.Closeable
import java.io.File
import java.net.URI
import java.nio.ByteBuffer
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.write
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

internal val windowsLogger = TaggedLogger("WindowsVideoPlayerState")
private val windowsHdr10PlusMetadataUnavailableHresult = 0x80000004.toInt()
private const val WINDOWS_PLAYBACK_DIAGNOSTICS_VALUE_COUNT = 5
private const val WINDOWS_PLAYBACK_DIAGNOSTICS_MAX_AV_SYNC_MICROS_INDEX = 3
private const val WINDOWS_PLAYBACK_DIAGNOSTICS_HAS_AUDIO_INDEX = 4
private const val MICROSECONDS_PER_MILLISECOND = 1_000f

internal fun maximumWindowsAvSyncOffsetMs(nativeDiagnostics: LongArray?): Float? =
    nativeDiagnostics
        ?.takeIf { diagnostics ->
            diagnostics.size >= WINDOWS_PLAYBACK_DIAGNOSTICS_VALUE_COUNT &&
                diagnostics[WINDOWS_PLAYBACK_DIAGNOSTICS_HAS_AUDIO_INDEX] != 0L
        }?.get(WINDOWS_PLAYBACK_DIAGNOSTICS_MAX_AV_SYNC_MICROS_INDEX)
        ?.div(MICROSECONDS_PER_MILLISECOND)

private sealed interface WindowsSeekRequest {
    data class Time(
        val value: Duration,
    ) : WindowsSeekRequest

    data class Slider(
        val value: Float,
    ) : WindowsSeekRequest
}

internal fun normalizeWindowsLocalFileUriForPlayback(uri: String): String {
    if (!uri.startsWith("file:", ignoreCase = true)) return uri

    runCatching { URI(uri) }
        .getOrNull()
        ?.takeIf { it.scheme.equals("file", ignoreCase = true) }
        ?.let { parsedUri ->
            val authority = parsedUri.authority
            val path = parsedUri.path
            if (!authority.isNullOrBlank() && !authority.equals("localhost", ignoreCase = true)) {
                return "\\\\$authority${path.orEmpty().replace('/', '\\')}"
            }
            val localUri =
                if (authority.equals("localhost", ignoreCase = true)) {
                    URI(parsedUri.scheme, null, path, parsedUri.query, parsedUri.fragment)
                } else {
                    parsedUri
                }
            runCatching { Path.of(localUri).toString() }.getOrNull()?.let { return it }
            if (!path.isNullOrEmpty()) return File(path).path
        }

    return when {
        uri.startsWith("file://localhost/", ignoreCase = true) ->
            File.separator + uri.substring("file://localhost/".length)
        uri.startsWith("file://", ignoreCase = true) -> uri.substring("file://".length)
        else -> uri.substring("file:".length)
    }
}

private enum class WindowsLibVlcRenderMode {
    MEMORY,
    NATIVE_VIEW,
}

private data class WindowsResolvedLibVlcBackend(
    val installation: JvmLibVlcInstallation,
    val renderMode: WindowsLibVlcRenderMode,
)

private data class WindowsLibVlcRuntimeTrackDescription(
    val ordinal: Int,
    val label: String,
)

internal data class WindowsDesktopBridgeColorRequest(
    val allowHdrCmafPassthrough: Boolean,
    val requireHdrCmafPassthrough: Boolean,
    val forceSdrOutput: Boolean,
)

internal fun windowsDesktopBridgeColorRequest(
    source: VideoColorInfo,
    dynamicRangePolicy: DynamicRangePolicy,
    hdrSurfaceRequested: Boolean,
): WindowsDesktopBridgeColorRequest {
    val forceSdrOutput =
        dynamicRangePolicy == DynamicRangePolicy.FORCE_SDR ||
            (source.isHdr && !hdrSurfaceRequested)
    val preserveHdr = source.isHdr && hdrSurfaceRequested && !forceSdrOutput
    return WindowsDesktopBridgeColorRequest(
        allowHdrCmafPassthrough = preserveHdr,
        requireHdrCmafPassthrough = preserveHdr && dynamicRangePolicy == DynamicRangePolicy.REQUIRE_HDR,
        forceSdrOutput = forceSdrOutput,
    )
}

/**
 * Windows implementation of the video player state.
 * Handles media playback using Media Foundation on Windows platform.
 */
@Suppress("MagicNumber", "TooManyFunctions")
class WindowsVideoPlayerState(
    private val playbackOptions: VideoPlaybackOptions = VideoPlaybackOptions(),
) : VideoPlayerState {
    private val colorPipelineController =
        VideoColorPipelineController(playbackOptions, jvmPlayerCapabilities(playbackOptions))
    override val colorPipelineStatus: StateFlow<VideoColorPipelineStatus> = colorPipelineController.status
    private var _projection by mutableStateOf(playbackOptions.projection.normalized())
    override var projection: VideoProjectionSettings
        get() = _projection
        set(value) {
            lifecycle.ensureUsable()
            _projection = value.normalized()
            updateProjectionRenderingInfo()
            updateWindowsHdrNativeConfiguration()
            refreshWindowsColorPipeline()
        }
    private var _projectionView by mutableStateOf(playbackOptions.projectionView.normalized())
    override var projectionView: VideoProjectionViewSettings
        get() = _projectionView
        set(value) {
            lifecycle.ensureUsable()
            _projectionView = value.normalized()
            updateWindowsHdrNativeConfiguration()
        }
    private var _projectionViewControlMode by mutableStateOf(playbackOptions.projectionViewControlMode)
    override var projectionViewControlMode: VideoProjectionViewControlMode
        get() = _projectionViewControlMode
        set(value) {
            lifecycle.ensureUsable()
            _projectionViewControlMode = value
        }
    private var _projectionTextureCrop by mutableStateOf(playbackOptions.projectionTextureCrop.normalized())
    override var projectionTextureCrop: VideoTextureCrop
        get() = _projectionTextureCrop
        set(value) {
            lifecycle.ensureUsable()
            _projectionTextureCrop = value.normalized()
            updateProjectionRenderingInfo()
            updateWindowsHdrNativeConfiguration()
            refreshWindowsColorPipeline()
        }

    companion object {
        private const val HUNDRED_NANOSECOND_TICKS_PER_SECOND = 10_000_000.0
        private val isMfBootstrapped = AtomicBoolean(false)

        /** Map to store volume settings for each player instance */
        private val instanceVolumes = ConcurrentHashMap<Long, Float>()

        /**
         * Initialize Media Foundation only once for all instances.
         * This is called automatically when the class is loaded.
         */
        private fun ensureMfInitialized() {
            if (!isMfBootstrapped.getAndSet(true)) {
                val hr = WindowsNativeBridge.InitMediaFoundation()
                if (hr < 0) {
                    windowsLogger.e { "Media Foundation initialization failed (hr=0x${hr.toString(16)})" }
                    return
                }
                // Tear MF down on JVM exit — otherwise MF worker threads stay
                // alive while the DLL is unloaded, corrupting KERNELBASE
                // internals on shutdown (crash 0x87A).
                try {
                    Runtime.getRuntime().addShutdownHook(
                        Thread {
                            try {
                                WindowsNativeBridge.ShutdownMediaFoundation()
                            } catch (_: Throwable) {
                            }
                        },
                    )
                } catch (_: Throwable) {
                    // best effort
                }
            }
        }

        init {
            // Initialize Media Foundation when class is loaded
            ensureMfInitialized()
        }
    }

    /** Instance of the native Media Foundation player */
    private val player = WindowsNativeBridge

    /** Coroutine scope for all async operations */
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val cleanupScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val callbackScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val lifecycle = DesktopPlayerLifecycle(scope, cleanupScope)
    private val disposeLock = Any()
    private var disposalJob: Job? = null

    /** Whether media has been loaded */
    private var _hasMedia by mutableStateOf(false)
    override val hasMedia get() = _hasMedia

    /** Whether media is currently playing */
    private var _isPlaying by mutableStateOf(false)
    override val isPlaying get() = _isPlaying

    /** Whether the user has intentionally paused the video */
    private var userPaused = false

    /** Video player instance handle. Atomic reads are used by the frame hot path. */
    private val videoPlayerInstanceAtomic = AtomicLong(0L)
    private var videoPlayerInstance: Long
        get() = videoPlayerInstanceAtomic.get()
        set(value) {
            videoPlayerInstanceAtomic.set(value)
        }

    /** Serializes handle replacement/destruction with native-surface attachment. */
    private val nativeInstanceLock = Any()

    /** Deferred completed when initialization is ready */
    private val initReady = CompletableDeferred<Unit>()

    /** Current volume level (0.0 to 1.0) */
    private var _volume by mutableStateOf(1f)

    /**
     * Volume control for the player (0.0 to 1.0)
     * Any modification triggers the native call SetAudioVolume
     */
    override var volume: Float
        get() = _volume
        set(value) {
            lifecycle.ensureUsable()
            val newVolume = value.coerceIn(0f, 1f)
            if (_volume != newVolume) {
                _volume = newVolume
                executeMediaOperation("update volume") {
                    videoPlayerInstance.takeIf { it != 0L }?.let { instance ->
                        instanceVolumes[instance] = newVolume
                        val hr = nativeSetAudioVolume(instance, newVolume)
                        if (hr < 0) {
                            setError("Error updating volume (hr=0x${hr.toString(16)})")
                        }
                    }
                }
            }
        }

    private var _currentTime by mutableStateOf(Duration.ZERO)
    private var _duration by mutableStateOf(Duration.ZERO)
    private var _progress by mutableStateOf(0f)
    override var sliderPos: Float
        get() = _progress * VideoPlayerState.SLIDER_SCALE
        set(value) {
            lifecycle.ensureUsable()
            _progress = (value / VideoPlayerState.SLIDER_SCALE).coerceIn(0f, 1f)
        }
    private var _userDragging by mutableStateOf(false)
    override var userDragging: Boolean
        get() = _userDragging
        set(value) {
            lifecycle.ensureUsable()
            _userDragging = value
        }
    private var _loop by mutableStateOf(false)
    override var loop: Boolean
        get() = _loop
        set(value) {
            lifecycle.ensureUsable()
            _loop = value
        }

    private var _onPlaybackEnded: (() -> Unit)? = null
    override var onPlaybackEnded: (() -> Unit)?
        get() = _onPlaybackEnded
        set(value) {
            lifecycle.ensureUsable()
            _onPlaybackEnded = value
        }
    private var _onRestart: (() -> Unit)? = null
    override var onRestart: (() -> Unit)?
        get() = _onRestart
        set(value) {
            lifecycle.ensureUsable()
            _onRestart = value
        }

    private var _playbackSpeed by mutableStateOf(1.0f)
    override var playbackSpeed: Float
        get() = _playbackSpeed
        set(value) {
            lifecycle.ensureUsable()
            val newSpeed = value.coerceIn(VideoPlayerState.MIN_PLAYBACK_SPEED, VideoPlayerState.MAX_PLAYBACK_SPEED)
            if (_playbackSpeed != newSpeed) {
                _playbackSpeed = newSpeed
                executeMediaOperation("update playback speed") {
                    videoPlayerInstance.takeIf { it != 0L }?.let { instance ->
                        val hr = nativeSetPlaybackSpeed(instance, newSpeed)
                        if (hr < 0) {
                            setError("Error updating playback speed (hr=0x${hr.toString(16)})")
                        }
                    }
                }
            }
        }

    private var _error: VideoPlayerError? = null
    override val error get() = _error
    override val capabilities: PlayerCapabilities
        get() = jvmPlayerCapabilities(playbackOptions)

    override fun clearError() {
        lifecycle.ensureUsable()
        _error = null
        errorMessage = null
    }

    // Current frame management
    private var _currentFrame: Bitmap? by mutableStateOf(null)
    private val bitmapLock = ReentrantReadWriteLock()
    internal val currentFrameState = mutableStateOf<ImageBitmap?>(null)

    // Aspect ratio property
    override val aspectRatio: Float
        get() =
            if (videoWidth > 0 && videoHeight > 0) {
                videoWidth.toFloat() / videoHeight.toFloat()
            } else {
                16f / 9f
            }

    // Metadata and UI state
    private var _metadata by mutableStateOf(VideoMetadata())
    override val metadata: VideoMetadata get() = _metadata
    override val renderingInfo: VideoRenderingInfo =
        VideoRenderingInfo(
            videoProjection = projection.renderingInfoLabel(),
        )
    override val diagnostics: PlaybackDiagnostics
        get() {
            val nativeDiagnostics =
                videoPlayerInstance
                    .takeIf { it != 0L && !nativeBackendUsesLibVlc }
                    ?.let { instance ->
                        runCatching { WindowsNativeBridge.nGetVideoPlaybackDiagnostics(instance) }
                            .getOrNull()
                            ?.takeIf { it.size >= WINDOWS_PLAYBACK_DIAGNOSTICS_VALUE_COUNT }
                    }
            return PlaybackDiagnostics(
                totalVideoFrames = nativeDiagnostics?.get(0),
                renderedVideoFrames = nativeDiagnostics?.get(1),
                droppedVideoFrames = nativeDiagnostics?.get(2),
                maximumAvSyncOffsetMs = maximumWindowsAvSyncOffsetMs(nativeDiagnostics),
                videoWidth = metadata.width?.takeIf { it > 0 },
                videoHeight = metadata.height?.takeIf { it > 0 },
                bitrate = metadata.bitrate?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt(),
                currentHlsQuality = currentHlsQuality,
                bufferedRanges = bufferedRanges,
                notes = renderingInfo.notes,
            )
        }

    private fun updateProjectionRenderingInfo() {
        renderingInfo.videoProjection = projection.renderingInfoLabel()
        if (!shouldUseLibVlcNativeSurface()) {
            renderingInfo.videoRenderer =
                if (libVlcBackendActive && nativeBackendLibVlcRenderMode == WindowsLibVlcRenderMode.MEMORY) {
                    libVlcVideoRenderer(WindowsLibVlcRenderMode.MEMORY)
                } else {
                    projection.jvmCanvasRendererLabel(projectionTextureCrop)
                }
        }
    }

    override var subtitlesEnabled by mutableStateOf(false)
    override var currentSubtitleTrack: SubtitleTrack? by mutableStateOf(null)
    private val desktopAssSubtitleSession = DesktopAssSubtitleSession(playbackOptions.extensions)
    private val desktopAssSelectionToken = AtomicLong(0L)
    internal var usesLibAssSubtitleOverlay: Boolean by mutableStateOf(false)
        private set
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
    override var isLoading by mutableStateOf(false)
        private set
    override val positionText: String get() = formatTime(_currentTime)
    override val durationText: String get() = formatTime(_duration)
    override val currentTime: Duration get() = _currentTime
    override val preciseCurrentTime: Duration get() = _currentTime
    override val duration: Duration get() = _duration
    private var errorMessage: String? by mutableStateOf(null)

    // Fullscreen state
    private var _isFullscreen by mutableStateOf(false)
    override var isFullscreen: Boolean
        get() = _isFullscreen
        set(value) {
            lifecycle.ensureUsable()
            if (_isFullscreen == value) return
            _isFullscreen = value
            colorOutputVerified = false
            refreshWindowsColorPipeline()
        }

    // Video properties
    var videoWidth by mutableStateOf(0)
    var videoHeight by mutableStateOf(0)

    // Surface display size (pixels) — used to scale native output resolution
    private var surfaceWidth = 0
    private var surfaceHeight = 0

    // Synchronization
    private val mediaOperationMutex = Mutex()
    private val isResizing = AtomicBoolean(false)
    private var videoJob: Job? = null
    private var resizeJob: Job? = null
    private val resizeRequestToken = AtomicLong(0L)

    // Rapid seeks overwrite the pending request, while each request remains tagged with the source
    // generation for which it was scheduled.
    private val pendingSeek = LatestSourceBoundRequestSlot<WindowsSeekRequest>()

// Serializes the native video reader: ReadVideoFrame / UnlockVideoFrame
    // (held by the producer coroutine) and SeekMedia (held by the seek flow).
    // This lets us seek *without* cancelling & restarting the producer — a
    // pattern that turned out to behave inconsistently under GraalVM native
    // image, leaving the video frozen after the first seek.
    private val videoReaderMutex = Mutex()
    private val seekInProgress = AtomicBoolean(false)
    override val isSeeking: Boolean get() = seekInProgress.get()

    // Frame channel: one slot, drop-oldest. With triple-buffering on the
    // producer side, overflow simply means the consumer was slow — safe to
    // drop. Capacity >1 would just let the pipeline pile up.
    private val frameChannel =
        Channel<FrameData>(
            capacity = 1,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

    // Data structure for a frame
    private data class FrameData(
        val bitmap: Bitmap,
        val timestamp: Duration,
    )

    // Triple-buffering for zero-copy frame rendering: the consumer may still
    // be driving a frame onto Compose (via currentFrameState) when the
    // producer writes the next frame. Two bitmaps is racy — with three, the
    // buffer the producer writes is guaranteed to be distinct from both the
    // one currently bound to ImageBitmap and the one Compose just finished.
    private val skiaBitmaps = arrayOfNulls<Bitmap>(3)
    private var nextBitmapIndex: Int = 0

    @Volatile
    private var lastFrameHash: Int = Int.MIN_VALUE
    private var skiaBitmapWidth: Int = 0
    private var skiaBitmapHeight: Int = 0

    // Bitmaps awaiting safe closure. When the video resolution changes mid-stream
    // (HLS adaptive bitrate) the old double-buffer bitmaps may still be read by
    // Compose on the AWT thread via currentFrameState. We defer close() by a few
    // consumed frames so Compose has swapped to the new bitmap first.
    private data class PendingCloseBitmap(
        val bitmap: Bitmap,
        var framesLeft: Int,
    )

    private val pendingCloseBitmaps = ArrayDeque<PendingCloseBitmap>()
    private val pendingCloseGraceFrames: Int = 4

    // Adaptive frame interval (ms) based on the video's native frame rate.
    // Mirrors macOS approach: poll at the video frame rate, not faster.
    // This prevents starving the audio thread on the shared SourceReader.
    private var frameIntervalMs: Long = 16L // Default ~60fps, updated after open

    // Variable to store the last opened URI
    private var lastUri: String? = null
    private var lastRequestHeaders: Map<String, String> = emptyMap()
    private var externalHlsFallback: Closeable? = null
    private var externalHlsFallbackDurationSeconds: Double? = null
    private var externalHlsSourceUri: String? = null
    private var externalHlsSelectedAudioStreamIndex: Int? = null
    private var externalHlsSelectedSubtitleStreamIndex: Int? = null
    private var externalHlsPlaybackOffsetSeconds: Double = 0.0
    private var externalFallbackToneMappedHdrToSdr = false
    private var activeSourceColorInfo = VideoColorInfo()
    private var nativeDecodedColorGeneration = 0
    private var activeDecoderName: String? = null
    private var colorOutputVerified = false
    private var windowsHdrNativeConfiguration: WindowsHdrNativeConfiguration? = null
    private var windowsNativeHdrOutputStatus: WindowsNativeHdrOutputStatus? = null
    private var windowsHdr10PlusApplicationUnavailable = false
    private var windowsSdrToneMappingRequested = false
    private var windowsHdrFallbackSourceUri: String? = null
    private val windowsHdrFailureRecoveryScheduled = AtomicBoolean(false)
    internal var windowsHdrSurfaceRequested: Boolean by mutableStateOf(false)
        private set
    private var windowsHdrSurfaceAttached: Boolean = false
    private var libVlcBackendActive: Boolean = false
    private var libVlcSourceUri: String? = null
    private var libVlcTrackInfo: JvmLibVlcTrackInfo? = null
    private var libVlcSelectedAudioStreamIndex: Int? = null
    private var libVlcSelectedSubtitleStreamIndex: Int? = null
    private var nativeBackendUsesLibVlc: Boolean = false
    private var nativeBackendLibVlcRenderMode: WindowsLibVlcRenderMode? = null
    internal var libVlcNativeSurfaceRequested: Boolean by mutableStateOf(false)
        private set
    private var libVlcNativeSurfaceAttached: Boolean = false

    init {
        // Kick off native initialization immediately
        scope.launch {
            try {
                mediaOperationMutex.withLock {
                    val handle =
                        synchronized(nativeInstanceLock) {
                            WindowsNativeBridge.createInstance()
                        }
                    if (handle == 0L) {
                        if (!lifecycle.isDisposed) {
                            setError("Failed to create video player instance")
                            initReady.completeExceptionally(
                                IllegalStateException("Failed to create video player instance"),
                            )
                        }
                        return@withLock
                    }

                    if (lifecycle.isDisposed) {
                        synchronized(nativeInstanceLock) {
                            WindowsNativeBridge.destroyInstance(handle)
                        }
                        return@withLock
                    }

                    videoPlayerInstance = handle

                    // Store default volume so that later instances inherit it
                    instanceVolumes[handle] = _volume
                    initReady.complete(Unit)
                }
            } catch (e: CancellationException) {
                initReady.cancel(e)
                throw e
            } catch (e: Exception) {
                initReady.completeExceptionally(e)
                if (!lifecycle.isDisposed) {
                    setError("Exception during initialization: ${e.message}")
                }
            }
        }
    }

    override fun dispose() {
        val cleanupJob =
            synchronized(disposeLock) {
                disposalJob ?: lifecycle
                    .dispose(
                        cleanup = {
                            // DesktopPlayerLifecycle has cancelled and joined every scope child here,
                            // including the frame reader. No JNI caller can still hold the handle.
                            mediaOperationMutex.withLock {
                                videoJob = null
                                resizeJob = null
                                _isPlaying = false
                                _hasMedia = false
                                isLoading = false
                                releaseAllResources()

                                val wasLibVlc = nativeBackendUsesLibVlc
                                synchronized(nativeInstanceLock) {
                                    val instance = videoPlayerInstanceAtomic.getAndSet(0L)
                                    if (instance != 0L) {
                                        runCatching {
                                            nativeSetPlaybackState(instance, false, stop = true, usesLibVlc = wasLibVlc)
                                        }.onFailure { e ->
                                            windowsLogger.e { "Exception stopping playback: ${e.message}" }
                                        }
                                        runCatching {
                                            nativeCloseMedia(instance, usesLibVlc = wasLibVlc)
                                        }.onFailure { e ->
                                            windowsLogger.e { "Exception closing media: ${e.message}" }
                                        }
                                        instanceVolumes.remove(instance)
                                        runCatching {
                                            destroyNativeInstance(instance, usesLibVlc = wasLibVlc)
                                        }.onFailure { e ->
                                            windowsLogger.e { "Exception destroying instance: ${e.message}" }
                                        }
                                    }
                                }

                                closeExternalHlsFallback()
                                clearExternalHlsFallbackTrackState()
                                clearLibVlcTrackState()
                                clearDesktopAssSubtitleRenderer()
                                lastUri = null
                                lastRequestHeaders = emptyMap()
                                nativeBackendUsesLibVlc = false
                                nativeBackendLibVlcRenderMode = null
                                resetWindowsColorPipeline()
                                callbackScope.cancel()
                                _onPlaybackEnded = null
                                _onRestart = null
                            }
                        },
                    ).also { disposalJob = it }
            }

        if (cleanupJob != null) {
            // Windows native shutdown must finish before dispose returns. Otherwise an
            // immediate JVM exit can unload the DLL while its audio thread is alive.
            runBlocking { cleanupJob.join() }
            cleanupScope.cancel()
        }
    }

    private fun releaseAllResources() {
        // Cancel any remaining jobs related to video processing
        videoJob?.cancel()
        resizeJob?.cancel()

        clearFrameChannel()

        // Free bitmaps and frame buffers.
        // Do NOT close the triple-buffer bitmaps here: the ImageBitmap exposed
        // via currentFrameState shares the same native pixel memory
        // (asComposeImageBitmap is zero-copy). Compose may still be rendering
        // the last frame on the AWT-EventQueue thread. Closing now would free
        // the native memory while Skia reads it, causing an access violation.
        // Nullifying the references lets the Skia Managed cleaner release them
        // once Compose (and any other holder) drops its reference.
        bitmapLock.write {
            _currentFrame = null
            currentFrameState.value = null

            for (i in skiaBitmaps.indices) skiaBitmaps[i] = null
            skiaBitmapWidth = 0
            skiaBitmapHeight = 0
            nextBitmapIndex = 0
            lastFrameHash = Int.MIN_VALUE
            pendingCloseBitmaps.clear()
        }

        initialFrameRead.set(false)
    }

    private fun clearFrameChannel() {
        while (frameChannel.tryReceive().isSuccess) { /* drain */ }
    }

    /**
     * Opens a media file or URL for playback
     *
     * @param uri The path to the media file or URL to open
     * @param initializePlayerState Controls whether playback should start automatically after opening
     */
    override fun openUri(
        uri: String,
        initializePlayerState: InitialPlayerState,
        requestHeaders: Map<String, String>,
    ) {
        lifecycle.ensureUsable()
        val sanitizedHeaders = requestHeaders.sanitizedRequestHeaders()
        lifecycle.launchSourceOperation(
            onScheduled = {
                clearDesktopAssSubtitleRenderer()
                lastUri = uri
                lastRequestHeaders = sanitizedHeaders
                _playbackSpeed = 1.0f
                _hasMedia = false
                _isPlaying = false
                isLoading = true
                _error = null
                errorMessage = null
                resetWindowsColorPipeline()
            },
        ) { generation ->
            try {
                // Wait for initialization to complete with a timeout
                withTimeout(10_000) { initReady.await() }
                lifecycle.ensureCurrentSource(generation)
                openUriInternal(uri, initializePlayerState, sanitizedHeaders, generation)
            } catch (_: TimeoutCancellationException) {
                lifecycle.commitCurrentSource(generation) {
                    setError("Player initialization timed out after 10 s.")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lifecycle.commitCurrentSource(generation) {
                    setError("Error while waiting for initialization: ${e.message}")
                }
            }
        }
    }

    override fun openFile(
        file: PlatformFile,
        initializePlayerState: InitialPlayerState,
    ) {
        openUri(file.file.path, initializePlayerState)
    }

    /**
     * Internal implementation of openUri that assumes the player is initialized
     *
     * @param uri The path to the media file or URL to open
     * @param initializePlayerState Controls whether playback should start automatically after opening
     */
    @Suppress("CyclomaticComplexMethod", "LongMethod")
    private suspend fun openUriInternal(
        uri: String,
        initializePlayerState: InitialPlayerState,
        requestHeaders: Map<String, String>,
        sourceGeneration: Long? = null,
    ) {
        suspend fun ensureSourceIsCurrent() {
            if (sourceGeneration != null) {
                lifecycle.ensureCurrentSource(sourceGeneration)
            } else if (lifecycle.isDisposed) {
                throw CancellationException("Media source operation was disposed")
            }
        }

        fun commitSourceUpdate(block: () -> Unit): Boolean =
            if (sourceGeneration != null) {
                lifecycle.commitCurrentSource(sourceGeneration, block)
            } else if (!lifecycle.isDisposed) {
                block()
                true
            } else {
                false
            }

        fun commitSourceError(message: String) {
            commitSourceUpdate { setError(message) }
        }

        mediaOperationMutex.withLock {
            try {
                ensureSourceIsCurrent()
                isLoading = true

                val normalizedUri = normalizeWindowsLocalFileUriForPlayback(uri)
                val containerProbe =
                    withContext(Dispatchers.IO) {
                        JvmLibVlcMediaProbe.probe(normalizedUri, requestHeaders)
                    }
                val sourceProbe =
                    if (containerProbe.videoColorInfo.dynamicRange == VideoDynamicRange.UNKNOWN) {
                        val nativeColorSignal =
                            withContext(Dispatchers.IO) {
                                JvmDecodedVideoColorSignalCodec.decode(
                                    runCatching {
                                        WindowsNativeBridge.nProbeVideoColorInfo(
                                            normalizedUri,
                                            requestHeaders.requestHeadersLineString(),
                                        )
                                    }.getOrNull(),
                                )
                            }
                        containerProbe.copy(
                            videoColorInfo =
                                nativeColorSignal?.mergeInto(containerProbe.videoColorInfo)
                                    ?: containerProbe.videoColorInfo,
                        )
                    } else {
                        containerProbe
                    }
                val isLiveSource = normalizedUri.substringBefore('?').endsWith(".m3u8", ignoreCase = true)
                val strictHdrRequest =
                    playbackOptions.dynamicRangePolicy == DynamicRangePolicy.REQUIRE_HDR ||
                        (
                            sourceProbe.videoColorInfo.dynamicRange == VideoDynamicRange.DOLBY_VISION &&
                                playbackOptions.dolbyVisionPolicy == DolbyVisionPolicy.REQUIRE_NATIVE
                        )
                val skipFailedHdrRoute = windowsHdrFallbackSourceUri == normalizedUri
                if (skipFailedHdrRoute) windowsHdrFallbackSourceUri = null
                windowsHdr10PlusApplicationUnavailable = false
                val requestNativeSdrToneMapping =
                    sourceProbe.videoColorInfo.isHdr &&
                        (
                            playbackOptions.dynamicRangePolicy == DynamicRangePolicy.FORCE_SDR ||
                                (skipFailedHdrRoute && !strictHdrRequest)
                        )
                val nativeHdrConfiguration =
                    buildWindowsHdrNativeConfiguration(
                        source = sourceProbe.videoColorInfo,
                        dolbyVisionPolicy = playbackOptions.dolbyVisionPolicy,
                        projection = projection,
                        projectionView = projectionView,
                        textureCrop = projectionTextureCrop,
                        metadataHandling =
                            if (sourceProbe.videoColorInfo.dynamicRange == VideoDynamicRange.HDR10_PLUS) {
                                DynamicMetadataHandling.APPLIED_BY_RENDERER
                            } else {
                                DynamicMetadataHandling.NONE
                            },
                        forceSdrOutput = requestNativeSdrToneMapping,
                    )
                val canAttemptNativeHdr =
                    !isLiveSource &&
                        (!skipFailedHdrRoute || requestNativeSdrToneMapping) &&
                        nativeHdrConfiguration != null
                var needsManagedSdrFallback =
                    !strictHdrRequest &&
                        !canAttemptNativeHdr &&
                        !sourceProbe.videoColorInfo.isSafeForUnmanagedSdrFallback()
                val libVlcBackend =
                    if (needsManagedSdrFallback || strictHdrRequest) {
                        null
                    } else {
                        resolveLibVlcBackendForUri(normalizedUri, requestHeaders)
                    }
                ensureSourceIsCurrent()

                // Stop playback and release existing resources
                val wasPlaying = _isPlaying
                val oldInstance = videoPlayerInstance

                if (oldInstance != 0L && wasPlaying) {
                    nativeSetPlaybackState(oldInstance, false, stop = false)
                    _isPlaying = false
                    delay(50.milliseconds)
                    ensureSourceIsCurrent()
                }

                val preserveExternalHlsSelection = normalizedUri == externalHlsSourceUri
                val requestedExternalAudioStreamIndex =
                    externalHlsSelectedAudioStreamIndex.takeIf { preserveExternalHlsSelection }
                val requestedExternalSubtitleStreamIndex =
                    externalHlsSelectedSubtitleStreamIndex.takeIf { preserveExternalHlsSelection }
                val requestedExternalPlaybackOffset =
                    externalHlsPlaybackOffsetSeconds.takeIf { preserveExternalHlsSelection } ?: 0.0

                videoJob?.cancelAndJoin()
                ensureSourceIsCurrent()
                releaseAllResources()
                if (oldInstance != 0L) {
                    nativeCloseMedia(oldInstance)
                }
                closeExternalHlsFallback()
                clearExternalHlsFallbackTrackState()
                clearLibVlcTrackState()
                externalHlsSelectedAudioStreamIndex = requestedExternalAudioStreamIndex
                externalHlsSelectedSubtitleStreamIndex = requestedExternalSubtitleStreamIndex
                externalHlsPlaybackOffsetSeconds = requestedExternalPlaybackOffset

                activeSourceColorInfo = sourceProbe.videoColorInfo
                activeDecoderName = "Media Foundation (decoder component not reported)"
                colorOutputVerified = false
                windowsSdrToneMappingRequested = requestNativeSdrToneMapping
                colorPipelineController.updateSource(
                    source = activeSourceColorInfo,
                    decoderName = activeDecoderName,
                    decoderCapabilities = DecoderColorCapabilities(),
                    isLive = isLiveSource,
                )
                windowsHdrNativeConfiguration = nativeHdrConfiguration.takeIf { canAttemptNativeHdr }
                windowsHdrSurfaceRequested = canAttemptNativeHdr
                windowsHdrSurfaceAttached = false
                windowsNativeHdrOutputStatus = null
                refreshWindowsColorPipeline()
                if (strictHdrRequest && !canAttemptNativeHdr && colorPipelineController.pipelineErrorOrNull() != null) {
                    publishWindowsColorPipelineError()
                    return@withLock
                }

                ensureNativeInstance(libVlcBackend)
                ensureSourceIsCurrent()
                val instance = videoPlayerInstance
                if (instance == 0L) {
                    commitSourceError("Video player instance is null")
                    return@withLock
                }

                if (!nativeBackendUsesLibVlc) {
                    val configuration = windowsHdrNativeConfiguration
                    val configureHr =
                        if (configuration != null) {
                            WindowsNativeBridge.configureHdrOutput(instance, configuration)
                        } else {
                            WindowsNativeBridge.disableHdrOutput(instance)
                        }
                    if (configureHr < 0) {
                        WindowsNativeBridge.disableHdrOutput(instance)
                        windowsHdrSurfaceRequested = false
                        windowsHdrNativeConfiguration = null
                        if (strictHdrRequest) {
                            publishWindowsColorPipelineError(
                                "The native P010/D3D11 presenter rejected its configuration " +
                                    "(hr=0x${configureHr.toUInt().toString(16)}).",
                            )
                            return@withLock
                        }
                        needsManagedSdrFallback = true
                    }
                }

                _currentTime = Duration.ZERO
                _progress = 0f
                _duration = Duration.ZERO
                _metadata = VideoMetadata()
                _hasMedia = false
                userPaused = false

                // Reset initialFrameRead flag to ensure we read an initial frame for the new video
                initialFrameRead.set(false)

                // Check if the file or URL is valid
                if (!normalizedUri.startsWith("http", ignoreCase = true) && !File(normalizedUri).exists()) {
                    commitSourceError("File not found: $uri")
                    return@withLock
                }

                var playbackUri =
                    if (needsManagedSdrFallback) {
                        try {
                            prepareExternalHlsPlayback(normalizedUri, requestHeaders)
                        } catch (pipelineFailure: UnsupportedOperationException) {
                            refreshWindowsColorPipeline()
                            publishWindowsColorPipelineError(pipelineFailure.message)
                            return@withLock
                        }
                    } else if (libVlcBackend != null) {
                        prepareLibVlcPlayback(normalizedUri, requestHeaders, libVlcBackend.renderMode)
                    } else if (shouldUseExternalHlsFallback(normalizedUri, requestHeaders)) {
                        prepareExternalHlsPlayback(normalizedUri, requestHeaders)
                    } else {
                        normalizedUri
                    }
                ensureSourceIsCurrent()
                var playbackRequestHeaders = if (playbackUri == normalizedUri) requestHeaders else emptyMap()

                // Always open media in paused state to avoid starting the native
                // playback clock before we've finished setup (SetOutputSize, metadata, etc.).
                // We explicitly call SetPlaybackState(true) later, right before starting
                // the frame-reading coroutine, so the wall-clock is in sync with frame production.
                val startPlayback = initializePlayerState == InitialPlayerState.PLAY
                var requestHeaderLines = playbackRequestHeaders.requestHeadersLineString()
                var hrOpen =
                    if (nativeBackendUsesLibVlc) {
                        WindowsNativeBridge.nOpenLibVlcMediaWithHeaders(
                            instance,
                            playbackUri,
                            requestHeaderLines,
                            false,
                        )
                    } else if (requestHeaderLines.isBlank()) {
                        player.OpenMedia(instance, playbackUri, false)
                    } else {
                        player.nOpenMediaWithHeaders(instance, playbackUri, requestHeaderLines, false)
                    }
                ensureSourceIsCurrent()
                if (hrOpen < 0 && windowsHdrSurfaceRequested && !strictHdrRequest) {
                    windowsLogger.w {
                        "P010 Media Foundation open failed (hr=0x${hrOpen.toUInt().toString(16)}); " +
                            "retrying through the verified HDR-to-SDR bridge."
                    }
                    WindowsNativeBridge.disableHdrOutput(instance)
                    windowsHdrSurfaceRequested = false
                    windowsHdrSurfaceAttached = false
                    windowsHdrNativeConfiguration = null
                    playbackUri = prepareExternalHlsPlayback(normalizedUri, requestHeaders)
                    playbackRequestHeaders = emptyMap()
                    requestHeaderLines = playbackRequestHeaders.requestHeadersLineString()
                    hrOpen =
                        if (requestHeaderLines.isBlank()) {
                            player.OpenMedia(instance, playbackUri, false)
                        } else {
                            player.nOpenMediaWithHeaders(instance, playbackUri, requestHeaderLines, false)
                        }
                    ensureSourceIsCurrent()
                }
                if (hrOpen < 0) {
                    commitSourceError("Failed to open media (hr=0x${hrOpen.toString(16)}): $uri")
                    return@withLock
                }

                // Get the video dimensions
                val sizeArr = IntArray(2)
                nativeGetVideoSize(instance, sizeArr)
                if (sizeArr[0] <= 0 || sizeArr[1] <= 0) {
                    val probedInfo = libVlcTrackInfo
                    if (nativeBackendUsesLibVlc &&
                        probedInfo?.videoWidth != null &&
                        probedInfo.videoHeight != null
                    ) {
                        sizeArr[0] = probedInfo.videoWidth
                        sizeArr[1] = probedInfo.videoHeight
                    }
                }
                if (sizeArr[0] <= 0 || sizeArr[1] <= 0) {
                    commitSourceError("Failed to retrieve video size")
                    nativeCloseMedia(instance)
                    return@withLock
                }
                videoWidth = sizeArr[0]
                videoHeight = sizeArr[1]

                // Scale output to match display surface (saves memory for 4K+ video)
                if (surfaceWidth > 0 && surfaceHeight > 0) {
                    val hrScale = nativeSetOutputSize(instance, surfaceWidth, surfaceHeight)
                    if (hrScale >= 0) {
                        nativeGetVideoSize(instance, sizeArr)
                        if (sizeArr[0] > 0 && sizeArr[1] > 0) {
                            videoWidth = sizeArr[0]
                            videoHeight = sizeArr[1]
                        }
                    }
                }

                // Get the media duration (may be 0 for live HLS streams)
                val durArr = LongArray(1)
                val hrDuration = nativeGetMediaDuration(instance, durArr)
                if (hrDuration < 0) {
                    // Only fail for non-network sources; network/HLS may lack duration
                    if (!uri.startsWith("http", ignoreCase = true)) {
                        commitSourceError("Failed to retrieve duration (hr=0x${hrDuration.toString(16)})")
                        nativeCloseMedia(instance)
                        return@withLock
                    }
                }
                _duration =
                    externalHlsFallbackDurationSeconds
                        ?.takeIf { it > 0.0 }
                        ?.secondsAsDuration()
                        ?: durArr[0].hundredNanosecondsAsDuration()

                // Retrieve metadata using the native function
                val retrievedMetadata = nativeVideoMetadata(instance)
                if (retrievedMetadata != null) {
                    _metadata = retrievedMetadata
                } else {
                    // If metadata retrieval failed, create a basic metadata object with what we know
                    _metadata =
                        VideoMetadata(
                            width = videoWidth,
                            height = videoHeight,
                            duration = _duration,
                        )
                }

                // Query the native frame rate to compute an adaptive polling interval
                // like macOS does with captureFrameRate.
                val rateArr = IntArray(2)
                val frameRate = nativeVideoFrameRate(instance, rateArr)
                if (frameRate > 0.0f) {
                    val fps = rateArr[0].toDouble() / rateArr[1].coerceAtLeast(1).toDouble()
                    frameIntervalMs = (1000.0 / fps).toLong().coerceIn(8L, 50L)
                } else {
                    frameIntervalMs = 16L // fallback ~60fps
                }

                if (libVlcBackend != null) {
                    refreshLibVlcRuntimeTracksIfNeeded()
                    ensureSourceIsCurrent()
                    applyLibVlcSelectedTracks()
                }

                ensureSourceIsCurrent()
                val sourceCommitted =
                    commitSourceUpdate {
                        // Publish the requested playback state before _hasMedia. Creating the
                        // native child surface is driven by _hasMedia and may happen immediately;
                        // the attach callback must already know whether it should start playback.
                        _isPlaying = startPlayback

                        // Set _hasMedia to true only if everything succeeded.
                        _hasMedia = true
                        updateProjectionRenderingInfo()

                        if (!lifecycle.isDisposed) {
                            // Restore the volume setting BEFORE starting playback
                            val storedVolume = instanceVolumes[instance]
                            if (storedVolume != null) {
                                val volArr = FloatArray(1)
                                val hr = nativeGetAudioVolume(instance, volArr)
                                if (hr >= 0 && storedVolume != volArr[0]) {
                                    val setHr = nativeSetAudioVolume(instance, storedVolume)
                                    if (setHr < 0) {
                                        windowsLogger.e { "Error restoring volume (hr=0x${setHr.toString(16)})" }
                                    }
                                }
                            }

                            if (!startPlayback) {
                                userPaused = true
                                initialFrameRead.set(false)
                                isLoading = false
                            }

                            // Start native playback as late as possible — this sets
                            // the wall-clock origin (llPlaybackStartTime) to NOW,
                            // minimising the gap before produceFrames reads its first frame.
                            val shouldDeferNativePlayback =
                                startPlayback &&
                                    (
                                        (shouldUseLibVlcNativeSurface() && !libVlcNativeSurfaceAttached) ||
                                            (shouldUseWindowsHdrSurface() && !windowsHdrSurfaceAttached)
                                    )
                            if (startPlayback && !shouldDeferNativePlayback) {
                                val hrPlay = nativeSetPlaybackState(instance, true, stop = false)
                                if (hrPlay < 0) {
                                    windowsLogger.e { "Failed to start playback (hr=0x${hrPlay.toString(16)})" }
                                }
                            }

                            // Start video processing
                            videoJob = startVideoPipeline()
                        }
                    }
                if (!sourceCommitted) {
                    throw CancellationException("Media source operation was superseded before commit")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                commitSourceUpdate {
                    setError("Error while opening media: ${e.message}")
                    _hasMedia = false
                }
            } finally {
                commitSourceUpdate {
                    if (!_hasMedia) {
                        isLoading = false
                    }
                }
            }
        }
    }

    private suspend fun shouldUseExternalHlsFallback(
        uri: String,
        requestHeaders: Map<String, String>,
    ): Boolean =
        playbackOptions.desktopVideoBackend == DesktopVideoBackend.AUTO &&
            !ExternalHlsFallbackSupport.isDisabled() &&
            ExternalHlsFallbackSupport.needsContainerFallback(uri, requestHeaders)

    private suspend fun resolveLibVlcBackendForUri(
        uri: String,
        requestHeaders: Map<String, String>,
    ): WindowsResolvedLibVlcBackend? {
        val forcedDesktopBackend = playbackOptions.forcedJvmDesktopBackend()
        val shouldUsePlatformBackend =
            forcedDesktopBackend == null &&
                !JvmExternalFallbackContainerSupport.needsContainerFallback(
                    uri = uri,
                    requestHeaders = requestHeaders,
                )
        if (shouldUsePlatformBackend) {
            return null
        }

        val configured =
            (forcedDesktopBackend ?: windowsFallbackBackendProperty()).lowercase()

        return when (configured) {
            "platform", "mediafoundation" -> null
            "libvlc" ->
                ExternalVlcLocator.findLibVlc()?.let {
                    WindowsResolvedLibVlcBackend(it, WindowsLibVlcRenderMode.MEMORY)
                }
                    ?: throw missingLibVlcBackendException()
            "auto" ->
                ExternalVlcLocator.findLibVlc()?.let {
                    WindowsResolvedLibVlcBackend(it, WindowsLibVlcRenderMode.MEMORY)
                }
            "libvlc-native-view", "libvlc-native", "libvlc-view", "libvlc-hwnd" ->
                ExternalVlcLocator.findLibVlc()?.let {
                    WindowsResolvedLibVlcBackend(it, WindowsLibVlcRenderMode.NATIVE_VIEW)
                } ?: throw missingLibVlcBackendException()
            "ffmpeg", "kmediabridge", "bridge", "vlc" -> null
            else -> null
        }
    }

    private fun windowsFallbackBackendProperty(): String =
        System.getProperty("composemediaplayer.windows.fallbackBackend")
            ?: System.getProperty("composemediaplayer.fallbackBackend")
            ?: System.getenv("COMPOSE_MEDIA_PLAYER_WINDOWS_FALLBACK_BACKEND")
            ?: System.getenv("COMPOSE_MEDIA_PLAYER_FALLBACK_BACKEND")
            ?: System.getProperty("composemediaplayer.hlsFallbackBackend")
            ?: System.getenv("COMPOSE_MEDIA_PLAYER_HLS_FALLBACK_BACKEND")
            ?: "auto"

    private fun missingLibVlcBackendException(): UnsupportedOperationException =
        UnsupportedOperationException(
            "The Windows libVLC backend was requested, but no compatible libVLC installation was found. " +
                "Install VLC for " +
                "${ExternalVlcLocator.currentProcessArchitecture() ?: "the current"} JVM architecture " +
                "or set composemediaplayer.libvlc and composemediaplayer.libvlc.plugins. " +
                "ComposeMediaPlayer does not bundle or link VLC.",
        )

    private fun ensureNativeInstance(libVlcBackend: WindowsResolvedLibVlcBackend?) {
        synchronized(nativeInstanceLock) {
            val wantsLibVlc = libVlcBackend != null
            val wantsLibVlcRenderMode = libVlcBackend?.renderMode
            val existingInstance = videoPlayerInstance
            if (existingInstance != 0L &&
                (
                    nativeBackendUsesLibVlc != wantsLibVlc ||
                        (wantsLibVlc && nativeBackendLibVlcRenderMode != wantsLibVlcRenderMode)
                )
            ) {
                nativeSetPlaybackState(existingInstance, false, stop = true)
                nativeCloseMedia(existingInstance)
                destroyNativeInstance(existingInstance)
                instanceVolumes.remove(existingInstance)
                videoPlayerInstance = 0L
                nativeBackendUsesLibVlc = false
                nativeBackendLibVlcRenderMode = null
            }

            if (videoPlayerInstance == 0L) {
                val handle =
                    if (libVlcBackend != null) {
                        WindowsNativeBridge.createLibVlcInstance(
                            libVlcBackend.installation.libVlcPath,
                            libVlcBackend.installation.pluginPath,
                            libVlcBackend.renderMode == WindowsLibVlcRenderMode.NATIVE_VIEW,
                        )
                    } else {
                        WindowsNativeBridge.createInstance()
                    }
                if (handle == 0L) {
                    throw IllegalStateException("Failed to create video player instance")
                }
                videoPlayerInstance = handle
                nativeBackendUsesLibVlc = wantsLibVlc
                nativeBackendLibVlcRenderMode = wantsLibVlcRenderMode
                instanceVolumes[handle] = _volume
                nativeSetAudioVolume(handle, _volume)
                nativeSetPlaybackSpeed(handle, _playbackSpeed)
            }
        }
    }

    private suspend fun prepareLibVlcPlayback(
        uri: String,
        requestHeaders: Map<String, String>,
        renderMode: WindowsLibVlcRenderMode,
    ): String {
        libVlcBackendActive = true
        libVlcSourceUri = uri
        libVlcNativeSurfaceRequested = renderMode == WindowsLibVlcRenderMode.NATIVE_VIEW
        libVlcNativeSurfaceAttached = false
        renderingInfo.update(
            backend = libVlcBackendLabel(renderMode),
            container = "Source through user-installed libVLC",
            videoDecoder = "libVLC",
            videoRenderer = libVlcVideoRenderer(renderMode),
            audioRenderer = "libVLC / Windows audio output",
            subtitleRenderer = null,
            subtitleSource = null,
            notes = libVlcRenderingNotes(renderMode),
        )
        val trackInfo = withContext(Dispatchers.IO) { JvmLibVlcMediaProbe.probe(uri, requestHeaders) }
        libVlcTrackInfo = trackInfo
        updateLibVlcTracks(trackInfo)
        return uri
    }

    private fun libVlcBackendLabel(renderMode: WindowsLibVlcRenderMode): String =
        when (renderMode) {
            WindowsLibVlcRenderMode.MEMORY -> "libVLC memory backend"
            WindowsLibVlcRenderMode.NATIVE_VIEW -> "libVLC native-view backend"
        }

    private fun libVlcVideoRenderer(renderMode: WindowsLibVlcRenderMode): String =
        when (renderMode) {
            WindowsLibVlcRenderMode.MEMORY ->
                projection.jvmCanvasRendererLabel(
                    baseRenderer = "libVLC vmem -> Compose Canvas (Skia)",
                    textureCrop = projectionTextureCrop,
                )
            WindowsLibVlcRenderMode.NATIVE_VIEW -> "libVLC native HWND"
        }

    private fun libVlcRenderingNotes(renderMode: WindowsLibVlcRenderMode): String =
        when (renderMode) {
            WindowsLibVlcRenderMode.MEMORY ->
                "VLC is loaded dynamically from the user's installation; frames are copied into Compose SDR."
            WindowsLibVlcRenderMode.NATIVE_VIEW ->
                "Native child-window rendering for container compatibility; this path is not accepted as confirmed HDR. Compose controls use a separate overlay window."
        }

    private fun updateLibVlcTracks(trackInfo: JvmLibVlcTrackInfo) {
        _availableAudioTracks.removeAll { isLibVlcAudioTrackId(it.id) }
        _availableAudioTracks.addAll(trackInfo.audioStreams.map { it.track })
        currentAudioTrack =
            libVlcSelectedAudioStreamIndex
                ?.let { streamIndex -> trackInfo.audioStreams.firstOrNull { it.streamIndex == streamIndex }?.track }
                ?: trackInfo.audioStreams.firstOrNull { it.track.isDefault }?.track
                ?: trackInfo.audioStreams.firstOrNull()?.track
        libVlcSelectedAudioStreamIndex = currentAudioTrack?.id?.let(::libVlcTrackStreamIndex)

        _availableSubtitleTracks.removeAll { isLibVlcSubtitleTrackId(it.id) }
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
        } else if (currentSubtitleTrack?.id?.let(::isLibVlcSubtitleTrackId) == true) {
            currentSubtitleTrack = null
            subtitlesEnabled = false
        }
    }

    private suspend fun refreshLibVlcRuntimeTracksIfNeeded() {
        val instance = videoPlayerInstance
        val currentInfo = libVlcTrackInfo ?: JvmLibVlcTrackInfo()
        if (instance == 0L || currentInfo.audioStreams.isNotEmpty() || currentInfo.subtitleStreams.isNotEmpty()) return

        repeat(12) {
            val runtimeAudioTracks =
                parseLibVlcRuntimeTrackDescriptions(WindowsNativeBridge.nGetLibVlcAudioTrackDescriptions(instance))
            val runtimeSubtitleTracks =
                parseLibVlcRuntimeTrackDescriptions(WindowsNativeBridge.nGetLibVlcSubtitleTrackDescriptions(instance))

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

    private fun parseLibVlcRuntimeTrackDescriptions(raw: String?): List<WindowsLibVlcRuntimeTrackDescription> =
        raw
            ?.lineSequence()
            ?.mapNotNull { line ->
                val ordinal = line.substringBefore('\t').toIntOrNull() ?: return@mapNotNull null
                val label = line.substringAfter('\t', missingDelimiterValue = "").trim()
                WindowsLibVlcRuntimeTrackDescription(ordinal = ordinal, label = label)
            }?.toList()
            ?: emptyList()

    private fun clearLibVlcTrackState() {
        libVlcBackendActive = false
        libVlcNativeSurfaceRequested = false
        libVlcNativeSurfaceAttached = false
        libVlcSourceUri = null
        libVlcTrackInfo = null
        libVlcSelectedAudioStreamIndex = null
        libVlcSelectedSubtitleStreamIndex = null
        _availableAudioTracks.removeAll { isLibVlcAudioTrackId(it.id) }
        if (currentAudioTrack?.id?.let(::isLibVlcAudioTrackId) == true) {
            currentAudioTrack = null
        }
        _availableSubtitleTracks.removeAll { isLibVlcSubtitleTrackId(it.id) }
        if (currentSubtitleTrack?.id?.let(::isLibVlcSubtitleTrackId) == true) {
            currentSubtitleTrack = null
            subtitlesEnabled = false
        }
    }

    internal fun shouldUseLibVlcNativeSurface(): Boolean =
        !lifecycle.isDisposed &&
            libVlcNativeSurfaceRequested &&
            libVlcBackendActive &&
            nativeBackendUsesLibVlc &&
            nativeBackendLibVlcRenderMode == WindowsLibVlcRenderMode.NATIVE_VIEW

    internal fun shouldUseWindowsHdrSurface(): Boolean =
        !lifecycle.isDisposed &&
            windowsHdrSurfaceRequested &&
            !nativeBackendUsesLibVlc &&
            windowsHdrNativeConfiguration != null

    internal fun attachWindowsHdrNativeComponent(component: Component): Boolean {
        synchronized(nativeInstanceLock) {
            if (!shouldUseWindowsHdrSurface()) return false
            val instance = videoPlayerInstance
            if (instance == 0L) return false
            updateWindowsHdrNativeConfiguration()
            val attached =
                runCatching { WindowsNativeBridge.nAttachHdrOutput(instance, component) }
                    .getOrElse { error ->
                        windowsLogger.e { "Failed to attach Windows HDR output: ${error.message}" }
                        false
                    }
            windowsHdrSurfaceAttached = attached
            windowsNativeHdrOutputStatus = WindowsNativeBridge.hdrOutputStatus(instance)
            refreshWindowsColorPipeline()
            if (!attached) {
                handleWindowsHdrRouteFailure(
                    windowsNativeHdrOutputStatus?.let { status ->
                        "The active monitor rejected the HDR swapchain " +
                            "(hr=0x${status.lastError.toUInt().toString(16)})."
                    } ?: "The active monitor rejected the HDR swapchain.",
                )
                return false
            }
            renderingInfo.update(
                backend = "Media Foundation + D3D11",
                videoDecoder = "Media Foundation decoder (P010 GPU surface; component not exposed)",
                videoRenderer =
                    if (windowsSdrToneMappingRequested) {
                        if (projection.requiresProjectionRenderer) {
                            "D3D11 P010 projection shader + BT.2390 tone mapper -> G22/BT.709 flip-model swapchain"
                        } else {
                            "D3D11 P010 BT.2390 tone mapper -> G22/BT.709 flip-model swapchain"
                        }
                    } else if (projection.requiresProjectionRenderer) {
                        "D3D11 P010 projection shader -> Advanced Color flip-model swapchain"
                    } else {
                        "D3D11 P010 shader -> Advanced Color flip-model swapchain"
                    },
                notes =
                    if (windowsSdrToneMappingRequested) {
                        "SDR output is confirmed only after P010, G22/BT.709 color space and the first Present succeed."
                    } else {
                        "HDR output is confirmed only after P010, DXGI color space and the first Present succeed."
                    },
            )
            if (_isPlaying) {
                val playHr = nativeSetPlaybackState(instance, true, stop = false)
                if (playHr < 0) {
                    handleWindowsHdrRouteFailure(
                        "Media Foundation could not start the attached HDR route " +
                            "(hr=0x${playHr.toUInt().toString(16)}).",
                    )
                    return false
                }
            }
            return true
        }
    }

    internal fun detachWindowsHdrNativeComponent(component: Component) {
        synchronized(nativeInstanceLock) {
            val instance = videoPlayerInstance
            if (instance != 0L && !nativeBackendUsesLibVlc) {
                runCatching { WindowsNativeBridge.nDetachHdrOutput(instance, component) }
                    .onFailure { error ->
                        windowsLogger.e { "Failed to detach Windows HDR output: ${error.message}" }
                    }
            }
            windowsHdrSurfaceAttached = false
            colorOutputVerified = false
            refreshWindowsColorPipeline()
        }
    }

    private fun updateWindowsHdrNativeConfiguration() {
        if (!windowsHdrSurfaceRequested) return
        val configuration =
            buildWindowsHdrNativeConfiguration(
                source = activeSourceColorInfo,
                dolbyVisionPolicy = playbackOptions.dolbyVisionPolicy,
                projection = projection,
                projectionView = projectionView,
                textureCrop = projectionTextureCrop,
                metadataHandling =
                    colorPipelineStatus.value.plannedMetadataHandling.takeUnless {
                        windowsHdr10PlusApplicationUnavailable
                    } ?: DynamicMetadataHandling.DROPPED,
                forceSdrOutput = windowsSdrToneMappingRequested,
            ) ?: return
        windowsHdrNativeConfiguration = configuration
        val instance = videoPlayerInstance
        if (instance == 0L || nativeBackendUsesLibVlc) return
        val hr = WindowsNativeBridge.configureHdrOutput(instance, configuration)
        if (hr < 0) {
            handleWindowsHdrRouteFailure(
                "The D3D11 presenter rejected an updated projection/color configuration " +
                    "(hr=0x${hr.toUInt().toString(16)}).",
            )
        }
    }

    private fun handleWindowsHdrRouteFailure(detail: String) {
        if (!windowsHdrFailureRecoveryScheduled.compareAndSet(false, true)) return
        windowsLogger.w { detail }
        windowsHdrSurfaceAttached = false
        colorOutputVerified = false
        refreshWindowsColorPipeline()
        val strict =
            playbackOptions.dynamicRangePolicy == DynamicRangePolicy.REQUIRE_HDR ||
                (
                    activeSourceColorInfo.dynamicRange == VideoDynamicRange.DOLBY_VISION &&
                        playbackOptions.dolbyVisionPolicy == DolbyVisionPolicy.REQUIRE_NATIVE
                )
        if (strict) {
            scope.launch {
                try {
                    publishWindowsColorPipelineError(detail)
                } finally {
                    windowsHdrFailureRecoveryScheduled.set(false)
                }
            }
            return
        }
        val source = lastUri
        if (source.isNullOrBlank()) {
            windowsHdrFailureRecoveryScheduled.set(false)
            return
        }
        val restartState = if (_isPlaying) InitialPlayerState.PLAY else InitialPlayerState.PAUSE
        windowsHdrFallbackSourceUri = normalizeWindowsLocalFileUriForPlayback(source)
        windowsHdrSurfaceRequested = false
        windowsHdrNativeConfiguration = null
        scope.launch {
            try {
                openUri(source, restartState, lastRequestHeaders)
            } finally {
                windowsHdrFailureRecoveryScheduled.set(false)
            }
        }
    }

    internal fun attachLibVlcNativeComponent(component: Component): Boolean {
        synchronized(nativeInstanceLock) {
            if (!shouldUseLibVlcNativeSurface()) return false
            val instance = videoPlayerInstance
            if (instance == 0L) return false

            return runCatching {
                val attached = WindowsNativeBridge.nAttachLibVlcNativeView(instance, component)
                libVlcNativeSurfaceAttached = attached
                if (attached && _isPlaying) {
                    val hrPlay = nativeSetPlaybackState(instance, true, stop = false)
                    if (hrPlay < 0) {
                        windowsLogger.e {
                            "Failed to start Windows libVLC native playback after attach: 0x${hrPlay.toString(16)}"
                        }
                    }
                }
                attached
            }.getOrElse { e ->
                windowsLogger.e { "Failed to attach Windows libVLC native surface: ${e.message}" }
                false
            }
        }
    }

    internal fun detachLibVlcNativeComponent(component: Component) {
        synchronized(nativeInstanceLock) {
            val instance = videoPlayerInstance
            if (instance == 0L) return
            runCatching {
                WindowsNativeBridge.nDetachLibVlcNativeView(instance, component)
                libVlcNativeSurfaceAttached = false
            }.onFailure { e ->
                windowsLogger.e { "Failed to detach Windows libVLC native surface: ${e.message}" }
            }
        }
    }

    private fun isLibVlcAudioTrackId(id: String): Boolean = id.startsWith(LIBVLC_CANVAS_AUDIO_TRACK_ID_PREFIX)

    private fun isLibVlcSubtitleTrackId(id: String): Boolean = id.startsWith(LIBVLC_CANVAS_SUBTITLE_TRACK_ID_PREFIX)

    private fun libVlcTrackStreamIndex(id: String): Int? =
        when {
            id.startsWith(LIBVLC_CANVAS_AUDIO_TRACK_ID_PREFIX) -> id.removePrefix(LIBVLC_CANVAS_AUDIO_TRACK_ID_PREFIX)
            id.startsWith(
                LIBVLC_CANVAS_SUBTITLE_TRACK_ID_PREFIX,
            ) -> id.removePrefix(LIBVLC_CANVAS_SUBTITLE_TRACK_ID_PREFIX)
            else -> null
        }?.toIntOrNull()

    private fun applyLibVlcSelectedTracks() {
        if (!libVlcBackendActive) return
        val info = libVlcTrackInfo ?: return
        val instance = videoPlayerInstance
        if (instance == 0L) return

        libVlcSelectedAudioStreamIndex
            ?.let { streamIndex -> info.audioStreams.firstOrNull { it.streamIndex == streamIndex }?.ordinal }
            ?.let { ordinal -> WindowsNativeBridge.nSelectLibVlcAudioTrack(instance, ordinal) }

        val selectedSubtitleOrdinal =
            libVlcSelectedSubtitleStreamIndex
                ?.let { streamIndex -> info.subtitleStreams.firstOrNull { it.streamIndex == streamIndex }?.ordinal }

        if (selectedSubtitleOrdinal != null) {
            WindowsNativeBridge.nSelectLibVlcSubtitleTrack(instance, selectedSubtitleOrdinal)
        }
    }

    private suspend fun prepareExternalHlsPlayback(
        uri: String,
        requestHeaders: Map<String, String>,
    ): String {
        val colorRequest =
            windowsDesktopBridgeColorRequest(
                source = activeSourceColorInfo,
                dynamicRangePolicy = playbackOptions.dynamicRangePolicy,
                hdrSurfaceRequested = windowsHdrSurfaceRequested,
            )
        val started =
            ExternalHlsFallbackSupport.start(
                uri = uri,
                requestHeaders = requestHeaders,
                selectedAudioStreamIndex = externalHlsSelectedAudioStreamIndex,
                selectedSubtitleStreamIndex = externalHlsSelectedSubtitleStreamIndex,
                startTimeSeconds = externalHlsPlaybackOffsetSeconds,
                allowHdrCmafPassthrough = colorRequest.allowHdrCmafPassthrough,
                requireHdrCmafPassthrough = colorRequest.requireHdrCmafPassthrough,
                forceSdrOutput = colorRequest.forceSdrOutput,
                extensions = playbackOptions.extensions,
            )
        externalHlsFallback = started.fallback
        externalHlsFallbackDurationSeconds = started.source.durationSeconds
        externalHlsSourceUri = uri
        externalHlsSelectedAudioStreamIndex = started.source.selectedAudioStreamIndex
        externalHlsSelectedSubtitleStreamIndex = started.source.selectedSubtitleStreamIndex
        externalHlsPlaybackOffsetSeconds = started.source.playbackOffsetSeconds
        externalFallbackToneMappedHdrToSdr = started.source.toneMappedHdrToSdr
        if (started.source.inputColorInfo.dynamicRange != VideoDynamicRange.UNKNOWN) {
            activeSourceColorInfo = started.source.inputColorInfo
        }
        activeDecoderName =
            when {
                started.source.videoCopiedWithoutReencoding -> "KMediaBridge sample copy -> Media Foundation"
                externalFallbackToneMappedHdrToSdr -> "Color-managed SDR bridge -> Media Foundation"
                else -> activeDecoderName
            }
        colorPipelineController.updateSource(
            source = activeSourceColorInfo,
            decoderName = activeDecoderName,
            decoderCapabilities = activeSourceColorInfo.toConfirmedDecoderCapabilities(),
            isLive = false,
        )
        refreshWindowsColorPipeline()
        updateExternalHlsFallbackTracks(started.source)
        return started.source.playlistUrl
    }

    private fun closeExternalHlsFallback() {
        val fallback = externalHlsFallback
        externalHlsFallback = null
        externalHlsFallbackDurationSeconds = null
        externalHlsSourceUri = null
        externalHlsSelectedAudioStreamIndex = null
        externalHlsSelectedSubtitleStreamIndex = null
        externalHlsPlaybackOffsetSeconds = 0.0
        externalFallbackToneMappedHdrToSdr = false
        fallback?.close()
    }

    private fun clearExternalHlsFallbackTrackState() {
        externalHlsSelectedAudioStreamIndex = null
        externalHlsSelectedSubtitleStreamIndex = null
        externalHlsPlaybackOffsetSeconds = 0.0
        _availableAudioTracks.removeAll { isExternalHlsAudioTrackId(it.id) }
        if (currentAudioTrack?.id?.let(::isExternalHlsAudioTrackId) == true) {
            currentAudioTrack = null
        }

        _availableSubtitleTracks.removeAll { isExternalHlsSubtitleTrackId(it.id) }
        if (currentSubtitleTrack?.id?.let(::isExternalHlsSubtitleTrackId) == true) {
            currentSubtitleTrack = null
            subtitlesEnabled = false
        }
    }

    private fun updateExternalHlsFallbackTracks(hlsSource: HlsFallbackSource) {
        val previousSubtitleId = currentSubtitleTrack?.id
        _availableAudioTracks.removeAll { isExternalHlsAudioTrackId(it.id) }
        _availableAudioTracks.addAll(hlsSource.audioTracks)
        currentAudioTrack =
            hlsSource.selectedAudioStreamIndex
                ?.let { streamIndex ->
                    hlsSource.audioTracks.firstOrNull {
                        externalHlsTrackStreamIndex(it.id) == streamIndex
                    }
                }
                ?: hlsSource.audioTracks.firstOrNull { it.isDefault }
                ?: hlsSource.audioTracks.firstOrNull()

        _availableSubtitleTracks.removeAll { isExternalHlsSubtitleTrackId(it.id) }
        _availableSubtitleTracks.addAll(hlsSource.subtitleTracks)
        val selectedSubtitleTrack =
            hlsSource.selectedSubtitleStreamIndex
                ?.let { streamIndex ->
                    hlsSource.subtitleTracks.firstOrNull {
                        externalHlsTrackStreamIndex(it.id) == streamIndex
                    }
                }
                ?: previousSubtitleId
                    ?.takeIf(::isExternalHlsSubtitleTrackId)
                    ?.let { previousId -> hlsSource.subtitleTracks.firstOrNull { it.id == previousId } }

        if (selectedSubtitleTrack != null) {
            currentSubtitleTrack = selectedSubtitleTrack
            subtitlesEnabled = true
        } else if (previousSubtitleId?.let(::isExternalHlsSubtitleTrackId) == true) {
            currentSubtitleTrack = null
            subtitlesEnabled = false
        }
    }

    private fun adjustedExternalHlsPosition(position: Duration): Duration {
        val fallbackDuration = externalHlsFallbackDurationSeconds
        if (fallbackDuration == null || fallbackDuration <= 0.0) return position
        return (externalHlsPlaybackOffsetSeconds + position.toSecondsDouble())
            .coerceIn(0.0, fallbackDuration)
            .secondsAsDuration()
    }

    private fun dispatchCallback(callback: (() -> Unit)?) {
        if (callback == null || lifecycle.isDisposed) return
        callbackScope.launch {
            runCatching(callback)
                .onFailure { e -> windowsLogger.e { "Playback callback failed: ${e.message}" } }
        }
    }

    private fun externalHlsLocalSeekTicks(targetTicks: Long): Long {
        val fallbackDuration = externalHlsFallbackDurationSeconds
        if (fallbackDuration == null || fallbackDuration <= 0.0) return targetTicks
        val targetSeconds = targetTicks / HUNDRED_NANOSECOND_TICKS_PER_SECOND
        val localSeconds = (targetSeconds - externalHlsPlaybackOffsetSeconds).coerceAtLeast(0.0)
        return (localSeconds * HUNDRED_NANOSECOND_TICKS_PER_SECOND).toLong()
    }

    /**
     * Launches the producer/consumer coroutine pair that reads frames from
     * the native side and pushes them to Compose.
     */
    private fun startVideoPipeline(): Job =
        if (shouldUseWindowsHdrSurface()) {
            startWindowsHdrPipeline()
        } else if (shouldUseLibVlcNativeSurface()) {
            startNativeTimelinePipeline()
        } else {
            scope.launch {
                launch { produceFrames() }
                launch { consumeFrames() }
            }
        }

    @Suppress("CyclomaticComplexMethod", "TooGenericExceptionCaught")
    private fun startWindowsHdrPipeline(): Job =
        scope.launch {
            while (scope.isActive && _hasMedia && !lifecycle.isDisposed && shouldUseWindowsHdrSurface()) {
                val instance = videoPlayerInstance
                if (instance == 0L) break
                if (!windowsHdrSurfaceAttached) {
                    delay(16.milliseconds)
                    continue
                }
                if (nativeIsEOF(instance)) {
                    if (_duration <= Duration.ZERO) {
                        delay(1000.milliseconds)
                        continue
                    }
                    if (loop) {
                        userPaused = false
                        initialFrameRead.set(false)
                        seekTo(Duration.ZERO)
                        dispatchCallback(onRestart)
                        delay(frameIntervalMs.milliseconds)
                        continue
                    }
                    _currentTime = _duration
                    _progress = 1f
                    pause()
                    dispatchCallback(onPlaybackEnded)
                    break
                }
                if (!waitForPlaybackState(allowInitialFrame = true)) continue
                if (seekInProgress.get()) {
                    delay(5.milliseconds)
                    continue
                }

                val renderHr =
                    try {
                        videoReaderMutex.withLock {
                            WindowsNativeBridge.nRenderHdrFrame(instance)
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        windowsLogger.e { "Windows HDR frame failed: ${error.message}" }
                        -1
                    }
                windowsNativeHdrOutputStatus = WindowsNativeBridge.hdrOutputStatus(instance)
                val status = windowsNativeHdrOutputStatus
                if (refreshWindowsDecodedColorInfo(instance)) {
                    if (!shouldUseWindowsHdrSurface()) break
                    delay(2.milliseconds)
                    continue
                }
                if (
                    renderHr == windowsHdr10PlusMetadataUnavailableHresult &&
                    activeSourceColorInfo.dynamicRange == VideoDynamicRange.HDR10_PLUS &&
                    !windowsHdr10PlusApplicationUnavailable
                ) {
                    windowsHdr10PlusApplicationUnavailable = true
                    colorOutputVerified = false
                    refreshWindowsColorPipeline()
                    updateWindowsHdrNativeConfiguration()
                    delay(2.milliseconds)
                    continue
                }
                if (renderHr < 0) {
                    handleWindowsHdrRouteFailure(
                        "The P010/D3D11 render route failed " +
                            "(hr=0x${renderHr.toUInt().toString(16)}).",
                    )
                    break
                }
                val nativeOutputConfirmed =
                    if (windowsSdrToneMappingRequested) {
                        status?.isConfirmedSdrOutput == true
                    } else {
                        status?.isConfirmedHdrOutput == true
                    }
                if (nativeOutputConfirmed && !colorOutputVerified) {
                    colorOutputVerified = true
                    activeDecoderName = "Media Foundation decoder (P010 GPU surface; component not exposed)"
                    colorPipelineController.updateSource(
                        source = activeSourceColorInfo,
                        decoderName = activeDecoderName,
                        decoderCapabilities = confirmedWindowsHdrDecoderCapabilities(),
                        isLive = false,
                    )
                    refreshWindowsColorPipeline()
                    isLoading = false
                }
                val position = LongArray(1)
                if (nativeGetMediaPosition(instance, position) >= 0) {
                    _currentTime = position[0].hundredNanosecondsAsDuration()
                    if (!_userDragging && _duration > Duration.ZERO) {
                        _progress =
                            (_currentTime.toSecondsDouble() / _duration.toSecondsDouble())
                                .toFloat()
                                .coerceIn(0f, 1f)
                    }
                }
                // AcquireNextSample and DXGI Present(1) already pace this route. Adding a full
                // frame interval here makes 59.94/60 fps playback systematically under-run.
                delay(2.milliseconds)
            }
        }

    private fun confirmedWindowsHdrDecoderCapabilities(): DecoderColorCapabilities {
        val ranges =
            when (activeSourceColorInfo.dynamicRange) {
                VideoDynamicRange.HDR10_PLUS -> setOf(VideoDynamicRange.HDR10_PLUS, VideoDynamicRange.HDR10)
                VideoDynamicRange.DOLBY_VISION -> setOf(VideoDynamicRange.HDR10)
                VideoDynamicRange.HDR10 -> setOf(VideoDynamicRange.HDR10)
                VideoDynamicRange.HLG -> setOf(VideoDynamicRange.HLG)
                VideoDynamicRange.UNKNOWN,
                VideoDynamicRange.SDR,
                -> emptySet()
            }
        return DecoderColorCapabilities(
            isKnown = true,
            supportedDynamicRanges = ranges,
            maxBitDepth = 10,
        )
    }

    private fun refreshWindowsDecodedColorInfo(instance: Long): Boolean {
        val decoded =
            JvmDecodedVideoColorSignalCodec.decode(
                runCatching { WindowsNativeBridge.nGetDecodedVideoColorInfo(instance) }.getOrNull(),
            ) ?: return false
        if (decoded.generation == nativeDecodedColorGeneration) return false
        nativeDecodedColorGeneration = decoded.generation

        val previous = activeSourceColorInfo
        val updated = decoded.mergeInto(previous)
        if (updated == previous) return false

        activeSourceColorInfo = updated
        colorOutputVerified = false
        if (updated.dynamicRange != previous.dynamicRange) {
            windowsHdr10PlusApplicationUnavailable = false
        }
        activeDecoderName = "Media Foundation decoder (decoded GPU surface; component not exposed)"
        colorPipelineController.updateSource(
            source = updated,
            decoderName = activeDecoderName,
            decoderCapabilities = confirmedWindowsHdrDecoderCapabilities(),
            isLive = false,
        )

        val configuration =
            buildWindowsHdrNativeConfiguration(
                source = updated,
                dolbyVisionPolicy = playbackOptions.dolbyVisionPolicy,
                projection = projection,
                projectionView = projectionView,
                textureCrop = projectionTextureCrop,
                metadataHandling =
                    if (
                        updated.dynamicRange == VideoDynamicRange.HDR10_PLUS &&
                        !windowsHdr10PlusApplicationUnavailable
                    ) {
                        DynamicMetadataHandling.APPLIED_BY_RENDERER
                    } else {
                        DynamicMetadataHandling.NONE
                    },
                forceSdrOutput = windowsSdrToneMappingRequested,
            )
        if (configuration == null) {
            refreshWindowsColorPipeline()
            handleWindowsHdrRouteFailure(
                "The active Media Foundation stream changed to a signal unsupported by the D3D11 HDR route.",
            )
            return true
        }

        windowsHdrNativeConfiguration = configuration
        val configureHr = WindowsNativeBridge.configureHdrOutput(instance, configuration)
        if (configureHr < 0) {
            refreshWindowsColorPipeline()
            handleWindowsHdrRouteFailure(
                "The D3D11 presenter rejected the active media-type color change " +
                    "(hr=0x${configureHr.toUInt().toString(16)}).",
            )
            return true
        }
        refreshWindowsColorPipeline()
        return true
    }

    private fun startNativeTimelinePipeline(): Job =
        scope.launch {
            while (scope.isActive && _hasMedia && !lifecycle.isDisposed) {
                val instance = videoPlayerInstance
                if (instance == 0L) break

                if (nativeIsEOF(instance)) {
                    if (_duration <= Duration.ZERO) {
                        delay(1000.milliseconds)
                        continue
                    } else if (loop) {
                        try {
                            userPaused = false
                            seekTo(Duration.ZERO)
                            play()
                            dispatchCallback(onRestart)
                        } catch (e: Exception) {
                            setError("Error during SeekMedia for loop: ${e.message}")
                        }
                    } else {
                        if (_duration > Duration.ZERO) {
                            _currentTime = _duration
                            _progress = 1f
                        }
                        pause()
                        dispatchCallback(onPlaybackEnded)
                        break
                    }
                }

                val posArr = LongArray(1)
                if (nativeGetMediaPosition(instance, posArr) >= 0) {
                    _currentTime = adjustedExternalHlsPosition(posArr[0].hundredNanosecondsAsDuration())
                    if (!_userDragging) {
                        _progress =
                            if (_duration > Duration.ZERO) {
                                (_currentTime.toSecondsDouble() / _duration.toSecondsDouble())
                                    .toFloat()
                                    .coerceIn(0f, 1f)
                            } else {
                                0f
                            }
                    }
                }
                isLoading = false
                delay(100.milliseconds)
            }
        }

    /**
     * Zero-copy optimized frame producer using double-buffering and direct memory access.
     *
     * Optimizations applied:
     * 1. Double-buffering: Reuses two Bitmap objects, alternating between them.
     * 2. Frame hashing: Skips processing if the frame content hasn't changed.
     * 3. peekPixels(): Direct access to Skia bitmap memory, no ByteArray allocation.
     * 4. Single memory copy: Native buffer → Skia bitmap pixels.
     */
    private suspend fun produceFrames() {
        while (scope.isActive && _hasMedia && !lifecycle.isDisposed) {
            val instance = videoPlayerInstance
            if (instance == 0L) break

            if (nativeIsEOF(instance)) {
                if (_duration <= Duration.ZERO) {
                    // Live HLS stream — EOF means the live window ended,
                    // wait and continue (new segments may become available)
                    delay(1000.milliseconds)
                    continue
                } else if (loop) {
                    try {
                        userPaused = false // Reset userPaused when looping
                        initialFrameRead.set(false) // Reset initialFrameRead flag
                        lastFrameHash = Int.MIN_VALUE // Reset hash for new loop
                        seekTo(Duration.ZERO)
                        play()
                        dispatchCallback(onRestart)
                    } catch (e: Exception) {
                        setError("Error during SeekMedia for loop: ${e.message}")
                    }
                } else {
                    // The last decoded frame's timestamp is always slightly before the
                    // total duration (duration = last_frame_pts + frame_duration), so
                    // snap currentTime/progress to the end when playback completes.
                    if (_duration > Duration.ZERO) {
                        _currentTime = _duration
                        _progress = 1f
                    }
                    pause()
                    dispatchCallback(onPlaybackEnded)
                    break
                }
            }

            try {
                // Wait for playback state, allowing initial frame when paused
                // If the return value is false, we should wait and not process frames
                if (!waitForPlaybackState(allowInitialFrame = true)) {
                    delay(100.milliseconds) // Add a small delay to prevent busy waiting
                    continue
                }
            } catch (e: CancellationException) {
                break
            }

            if (waitIfResizing()) {
                continue
            }

            // Short-circuit while a seek is in progress — avoids contending
            // on videoReaderMutex which the seek flow is holding.
            if (seekInProgress.get()) {
                delay(5.milliseconds)
                continue
            }

            val produced =
                try {
                    videoReaderMutex.withLock {
                        processOneFrame(instance)
                    }
                } catch (e: CancellationException) {
                    break
                } catch (e: Exception) {
                    if (scope.isActive && _hasMedia && !lifecycle.isDisposed) {
                        setError("Error while reading a frame: ${e.message}")
                    }
                    delay(100.milliseconds)
                    null
                }

            when (produced) {
                ProduceOutcome.NotReady -> delay(2.milliseconds)
                ProduceOutcome.SkipIteration -> yield()
                is ProduceOutcome.Frame -> {
                    frameChannel.trySend(FrameData(produced.bitmap, produced.timestamp))
                    delay(frameIntervalMs.milliseconds)
                }
                null -> { /* exception already handled */ }
            }
        }
    }

    /**
     * Outcome of a single frame-read pass, consumed by the produceFrames loop.
     */
    private sealed interface ProduceOutcome {
        data object NotReady : ProduceOutcome

        data object SkipIteration : ProduceOutcome

        data class Frame(
            val bitmap: Bitmap,
            val timestamp: Duration,
        ) : ProduceOutcome
    }

    /**
     * Reads one frame from the native reader, copies it to the next Skia
     * bitmap, and returns the outcome. Must be called under
     * [videoReaderMutex] — this method calls ReadVideoFrame / UnlockVideoFrame.
     */
    private fun processOneFrame(instance: Long): ProduceOutcome {
        val hrArr = IntArray(1)
        val srcBuffer = nativeReadVideoFrame(instance, hrArr) ?: return ProduceOutcome.NotReady
        var frameUnlocked = false

        fun unlockFrame() {
            if (!frameUnlocked) {
                nativeUnlockVideoFrame(instance)
                frameUnlocked = true
            }
        }

        try {
            if (hrArr[0] < 0) return ProduceOutcome.NotReady

            // HLS adaptive bitrate may change the decoded size mid-stream.
            val sizeArr = IntArray(2)
            nativeGetVideoSize(instance, sizeArr)
            if (sizeArr[0] > 0 &&
                sizeArr[1] > 0 &&
                (sizeArr[0] != videoWidth || sizeArr[1] != videoHeight)
            ) {
                videoWidth = sizeArr[0]
                videoHeight = sizeArr[1]
            }

            val width = videoWidth
            val height = videoHeight
            if (width <= 0 || height <= 0) {
                return ProduceOutcome.SkipIteration
            }

            srcBuffer.rewind()
            val pixelCount = width * height
            val newHash =
                if (usesLibAssSubtitleOverlay) {
                    null
                } else {
                    calculateFrameHash(srcBuffer, pixelCount)
                }
            if (newHash != null && newHash == lastFrameHash) {
                return ProduceOutcome.SkipIteration
            }
            lastFrameHash = newHash ?: Int.MIN_VALUE

            if (skiaBitmaps[0] == null || skiaBitmapWidth != width || skiaBitmapHeight != height) {
                bitmapLock.write {
                    // Queue previous bitmaps for deferred close instead of leaking them
                    // to the Skia managed cleaner: closing now would race with Compose
                    // still drawing the last frame on the AWT thread.
                    for (i in skiaBitmaps.indices) {
                        skiaBitmaps[i]?.let {
                            pendingCloseBitmaps.addLast(PendingCloseBitmap(it, pendingCloseGraceFrames))
                        }
                        skiaBitmaps[i] = null
                    }
                    val imageInfo = createVideoImageInfo()
                    for (i in skiaBitmaps.indices) {
                        skiaBitmaps[i] = Bitmap().apply { allocPixels(imageInfo) }
                    }
                    skiaBitmapWidth = width
                    skiaBitmapHeight = height
                    nextBitmapIndex = 0
                }
            }

            drainPendingCloseBitmaps()

            val targetBitmap =
                skiaBitmaps[nextBitmapIndex]
                    ?: return ProduceOutcome.SkipIteration
            nextBitmapIndex = (nextBitmapIndex + 1) % skiaBitmaps.size

            val pixmap = targetBitmap.peekPixels()
            if (pixmap == null) {
                windowsLogger.e { "Failed to get pixmap from bitmap" }
                return ProduceOutcome.SkipIteration
            }
            val pixelsAddr = pixmap.addr
            if (pixelsAddr == 0L) {
                windowsLogger.e { "Invalid pixel address" }
                return ProduceOutcome.SkipIteration
            }

            val dstRowBytes = pixmap.rowBytes
            val dstSizeBytes = dstRowBytes.toLong() * height.toLong()
            val dstBuffer =
                WindowsNativeBridge.nWrapPointer(pixelsAddr, dstSizeBytes)
                    ?: return ProduceOutcome.SkipIteration

            srcBuffer.rewind()
            copyBgraFrame(srcBuffer, dstBuffer, width, height, dstRowBytes)
            unlockFrame()

            val posArr = LongArray(1)
            val frameTime =
                if (nativeGetMediaPosition(instance, posArr) >= 0) {
                    posArr[0].hundredNanosecondsAsDuration()
                } else {
                    Duration.ZERO
                }
            if (usesLibAssSubtitleOverlay) {
                when (
                    desktopAssSubtitleSession.blend(
                        pixels = dstBuffer,
                        rowBytes = dstRowBytes,
                        width = width,
                        height = height,
                        timeMs =
                            (
                                adjustedExternalHlsPosition(frameTime) +
                                    subtitleOffset
                            ).inWholeMilliseconds.coerceAtLeast(0L),
                    )
                ) {
                    DesktopAssBlendResult.Failed -> {
                        scope.launch(Dispatchers.Main) {
                            handleDesktopAssRendererFailure()
                        }
                    }
                    DesktopAssBlendResult.Inactive,
                    DesktopAssBlendResult.Rendered,
                    -> Unit
                }
            }

            return ProduceOutcome.Frame(targetBitmap, frameTime)
        } finally {
            unlockFrame()
        }
    }

    /**
     * Consumes frames from the channel and updates the UI.
     * With zero-copy optimization, bitmaps are reused from the double-buffer pool
     * and should not be closed here.
     */
    private suspend fun consumeFrames() {
        var frameReceived = false
        var loadingTimeout = 0

        while (scope.isActive && _hasMedia && !lifecycle.isDisposed) {
            if (waitIfResizing()) continue

            try {
                val frameData =
                    frameChannel.tryReceive().getOrNull() ?: run {
                        if (isLoading && !frameReceived) {
                            loadingTimeout++
                            if (loadingTimeout > 200) {
                                windowsLogger.w { "No frames received for 3 seconds, forcing isLoading to false" }
                                isLoading = false
                                loadingTimeout = 0
                            }
                        }
                        delay(16.milliseconds)
                        return@run null
                    } ?: continue

                loadingTimeout = 0
                frameReceived = true

                bitmapLock.write {
                    _currentFrame = frameData.bitmap
                    currentFrameState.value = frameData.bitmap.asComposeImageBitmap()
                }
                if (!colorOutputVerified) {
                    colorOutputVerified = true
                    refreshWindowsColorPipeline()
                }

                _currentTime = adjustedExternalHlsPosition(frameData.timestamp)
                // Don't clobber _progress while the user is dragging the
                // slider: sliderPos is backed by _progress, and seekFinished()
                // reads sliderPos to decide where to seek. Overwriting it with
                // the current playback position would make the drag seek land
                // wherever the video happened to be, not where the user
                // released.
                if (!_userDragging) {
                    _progress =
                        if (_duration > Duration.ZERO) {
                            (_currentTime.toSecondsDouble() / _duration.toSecondsDouble())
                                .toFloat()
                                .coerceIn(0f, 1f)
                        } else {
                            0f // Live stream — no meaningful progress
                        }
                }
                isLoading = false

                delay(1.milliseconds)
            } catch (e: CancellationException) {
                break
            } catch (e: Exception) {
                if (scope.isActive && _hasMedia && !lifecycle.isDisposed) {
                    setError("Error while processing a frame: ${e.message}")
                }
                delay(100.milliseconds)
            }
        }
    }

    override fun canPlaySource(
        uri: String,
        mimeType: String?,
    ): Boolean {
        lifecycle.ensureUsable()
        return capabilities.canPlaySource(uri, mimeType)
    }

    /** Starts or resumes playback, reopening the retained source after [stop]. */
    override fun play() {
        lifecycle.ensureUsable()

        if (readyForPlayback()) {
            executeMediaOperation(operation = "play") {
                resumePlayback()
            }
            return
        }

        val sourceUri = lastUri
        if (!sourceUri.isNullOrEmpty()) {
            openUri(sourceUri, InitialPlayerState.PLAY, lastRequestHeaders)
        }
    }

    override fun restart() {
        lifecycle.ensureUsable()
        seekTo(Duration.ZERO)
        play()
    }

    /**
     * Resumes playback — must be called under mediaOperationMutex.
     */
    private fun resumePlayback() {
        userPaused = false
        initialFrameRead.set(false)

        if (!_isPlaying) {
            setPlaybackState(true, "Error while starting playback")
        }

        if (_hasMedia && (videoJob == null || videoJob?.isActive == false)) {
            videoJob = startVideoPipeline()
        }
    }

    /**
     * Pauses playback if currently playing
     */
    override fun pause() {
        lifecycle.ensureUsable()
        executeMediaOperation(operation = "pause") {
            if (!_hasMedia) return@executeMediaOperation
            userPaused = true
            // Reset initialFrameRead flag when switching to pause state
            // This ensures that we'll read a new initial frame to display
            initialFrameRead.set(false)

            setPlaybackState(false, "Error while pausing playback")
        }
    }

    /** Stops playback while retaining the source specification for a later [play]. */
    override fun stop() {
        lifecycle.ensureUsable()
        lifecycle.launchSourceOperation(
            onScheduled = {
                _hasMedia = false
                _isPlaying = false
                isLoading = false
            },
        ) {
            mediaOperationMutex.withLock {
                releaseCurrentMedia()
            }
        }
    }

    /** Detaches the current source while keeping this state reusable. */
    override fun releaseSource() {
        lifecycle.ensureUsable()
        lifecycle.launchSourceOperation(
            onScheduled = {
                clearDesktopAssSubtitleRenderer()
                lastUri = null
                lastRequestHeaders = emptyMap()
                _hasMedia = false
                _isPlaying = false
                isLoading = false
            },
        ) {
            mediaOperationMutex.withLock {
                releaseCurrentMedia()
            }
        }
    }

    private suspend fun releaseCurrentMedia() {
        val instance = videoPlayerInstance
        if (instance != 0L) {
            runCatching {
                nativeSetPlaybackState(instance, false, stop = true)
            }.onFailure { e ->
                windowsLogger.e { "Exception stopping the current source: ${e.message}" }
            }
        }

        videoJob?.cancelAndJoin()
        videoJob = null
        releaseAllResources()
        if (instance != 0L) {
            runCatching { nativeCloseMedia(instance) }
                .onFailure { e -> windowsLogger.e { "Exception closing the current source: ${e.message}" } }
        }
        closeExternalHlsFallback()
        clearExternalHlsFallbackTrackState()
        clearLibVlcTrackState()
        clearDesktopAssSubtitleRenderer()
        _hasMedia = false
        _isPlaying = false
        _progress = 0f
        _currentTime = Duration.ZERO
        _duration = Duration.ZERO
        _metadata = VideoMetadata()
        isLoading = false
        errorMessage = null
        _error = null
        userPaused = false
        initialFrameRead.set(false)
        resetWindowsColorPipeline()
    }

    @Suppress("CyclomaticComplexMethod")
    private fun refreshWindowsColorPipeline() {
        val nativeStatus = windowsNativeHdrOutputStatus.takeIf { windowsHdrSurfaceRequested }
        val nativeRoute = windowsHdrSurfaceRequested && windowsHdrNativeConfiguration != null
        val nativeConfirmed =
            if (windowsSdrToneMappingRequested) {
                nativeStatus?.isConfirmedSdrOutput == true
            } else {
                nativeStatus?.isConfirmedHdrOutput == true
            }
        val supportsHdr10PlusApplication =
            nativeRoute && !windowsHdr10PlusApplicationUnavailable
        colorOutputVerified = nativeConfirmed || (externalFallbackToneMappedHdrToSdr && colorOutputVerified)
        val runtimeDetail =
            when {
                !activeSourceColorInfo.isHdr -> null
                nativeRoute && nativeStatus == null ->
                    "Windows controlled color output is waiting for the D3D11 child surface and active-output query."
                nativeRoute &&
                    !windowsSdrToneMappingRequested &&
                    nativeStatus?.displayQueried == true &&
                    nativeStatus.advancedColorEnabled == false ->
                    "HDR/Advanced Color is disabled on the monitor containing the player window " +
                        "(bitsPerColor=${nativeStatus.bitsPerColor}, " +
                        "colorSpace=${nativeStatus.displayColorSpace})."
                nativeRoute && (nativeStatus?.lastError ?: 0) < 0 ->
                    "The native P010/D3D11 route failed with " +
                        "hr=0x${nativeStatus?.lastError?.toUInt()?.toString(16)}."
                nativeRoute && !nativeConfirmed && windowsSdrToneMappingRequested ->
                    "Windows controlled SDR output is pending the first P010 frame and successful G22/BT.709 Present."
                nativeRoute && !nativeConfirmed ->
                    "Windows controlled HDR output is pending the first P010 frame and successful flip-model Present."
                !nativeRoute && !externalFallbackToneMappedHdrToSdr -> WindowsHdrRuntimeProbe.query().detail
                else -> null
            }
        colorPipelineController.updateOutput(
            displayCapabilities = nativeStatus?.displayCapabilities() ?: DisplayColorCapabilities(),
            rendererCapabilities =
                RendererColorCapabilities(
                    controlledHdrDynamicRanges =
                        if (nativeRoute && !windowsSdrToneMappingRequested) {
                            setOf(VideoDynamicRange.HDR10, VideoDynamicRange.HLG) +
                                setOfNotNull(
                                    VideoDynamicRange.HDR10_PLUS.takeIf {
                                        supportsHdr10PlusApplication
                                    },
                                )
                        } else {
                            emptySet()
                        },
                    supportsToneMappingToSdr = nativeRoute && windowsSdrToneMappingRequested,
                    supportsHdrProjection = nativeRoute,
                    supportsHdr10PlusApplication = supportsHdr10PlusApplication,
                ),
            surfaceKind =
                if (nativeRoute) {
                    VideoSurfaceKind.CONTROLLED_GPU_SURFACE
                } else {
                    VideoSurfaceKind.COMPOSE_CANVAS
                },
            nativeSurfaceAvailable = false,
            isProjection = projection.requiresProjectionRenderer,
            verification =
                if (nativeConfirmed || colorOutputVerified) {
                    ColorPipelineVerification.RENDERER_CONFIGURED
                } else {
                    ColorPipelineVerification.NONE
                },
            platformRuntimeFallbackReason =
                ColorPipelineFallbackReason.PLATFORM_RUNTIME_UNAVAILABLE.takeIf {
                    activeSourceColorInfo.isHdr && !nativeConfirmed && !externalFallbackToneMappedHdrToSdr
                },
            platformRuntimeDetail = runtimeDetail,
        )
    }

    private fun resetWindowsColorPipeline() {
        activeSourceColorInfo = VideoColorInfo()
        nativeDecodedColorGeneration = 0
        activeDecoderName = null
        colorOutputVerified = false
        externalFallbackToneMappedHdrToSdr = false
        windowsHdrSurfaceRequested = false
        windowsHdrSurfaceAttached = false
        windowsHdrNativeConfiguration = null
        windowsNativeHdrOutputStatus = null
        windowsHdr10PlusApplicationUnavailable = false
        windowsSdrToneMappingRequested = false
        colorPipelineController.resetSource()
    }

    private suspend fun publishWindowsColorPipelineError(extraDetail: String? = null) {
        val pipelineError =
            colorPipelineController.pipelineErrorOrNull()
                ?: VideoPlayerError.ColorPipelineError(
                    reason =
                        if (activeSourceColorInfo.dynamicRange == VideoDynamicRange.UNKNOWN) {
                            ColorPipelineFallbackReason.SOURCE_COLOR_UNKNOWN
                        } else {
                            ColorPipelineFallbackReason.PLATFORM_RUNTIME_UNAVAILABLE
                        },
                    message =
                        if (activeSourceColorInfo.dynamicRange == VideoDynamicRange.UNKNOWN) {
                            "The source color transfer cannot be verified safely."
                        } else {
                            "No verified Windows color pipeline is available."
                        },
                )
        withContext(Dispatchers.Main) {
            isLoading = false
            _error =
                if (extraDetail.isNullOrBlank()) {
                    pipelineError
                } else {
                    pipelineError.copy(message = "${pipelineError.message} $extraDetail")
                }
            errorMessage = (_error as? VideoPlayerError.ColorPipelineError)?.message
        }
    }

    override fun seekTo(time: Duration) {
        lifecycle.ensureUsable()
        scheduleSeek(WindowsSeekRequest.Time(time))
    }

    override fun seekToProgress(progress: Float) {
        seekTo(progress.coerceIn(0f, 1f) * VideoPlayerState.SLIDER_SCALE)
    }

    override fun seekStart(value: Float) {
        lifecycle.ensureUsable()
        userDragging = true
        sliderPos = value
    }

    override fun seekFinished() {
        lifecycle.ensureUsable()
        seekToProgress(sliderPos / VideoPlayerState.SLIDER_SCALE)
        userDragging = false
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun seekTo(value: Float) {
        lifecycle.ensureUsable()
        scheduleSeek(WindowsSeekRequest.Slider(value.coerceIn(0f, VideoPlayerState.SLIDER_SCALE)))
    }

    /**
     * Binds the request to the source current at scheduling time. The lifecycle mutex prevents a
     * source replacement from overlapping the native call, while the generation tag prevents a
     * queued or delayed request from being applied to the replacement source.
     */
    private fun scheduleSeek(request: WindowsSeekRequest) {
        lateinit var publication: LatestSourceBoundRequestSlot.Publication<WindowsSeekRequest>
        lifecycle.launchSourceBoundControlOperation(
            onScheduled = { generation ->
                publication = pendingSeek.publish(generation, request)
            },
        ) { generation ->
            try {
                while (true) {
                    lifecycle.ensureCurrentSource(generation)
                    val pendingRequest = pendingSeek.take(generation) ?: break

                    val sourceDuration = _duration
                    if (!_hasMedia || sourceDuration <= Duration.ZERO) continue
                    val target =
                        when (pendingRequest) {
                            is WindowsSeekRequest.Time ->
                                pendingRequest.value.coerceIn(Duration.ZERO, sourceDuration)
                            is WindowsSeekRequest.Slider ->
                                sourceDuration *
                                    (pendingRequest.value / VideoPlayerState.SLIDER_SCALE)
                                        .toDouble()
                                        .coerceIn(0.0, 1.0)
                        }
                    val targetProgress =
                        (target.toSecondsDouble() / sourceDuration.toSecondsDouble())
                            .toFloat()
                            .coerceIn(0f, 1f)
                    lifecycle.commitCurrentSource(generation) {
                        _progress = targetProgress
                        _currentTime = target
                    }
                    performSeek(target.inWhole100NanosecondTicks(), generation)
                }
            } finally {
                pendingSeek.clear(publication)
            }
        }
    }

    /**
     * Executes a single native seek.
     *
     * Strategy: keep the producer/consumer coroutines alive and instead
     * serialize native reader access with [videoReaderMutex] + [seekInProgress].
     * Cancelling & relaunching `videoJob` on every seek proved fragile
     * under GraalVM native-image (the relaunched job sometimes never ran,
     * leaving audio but no video).
     */
    private suspend fun performSeek(
        targetPos: Long,
        sourceGeneration: Long,
    ) {
        val loadingTrigger =
            scope.launch {
                delay(200.milliseconds)
                lifecycle.commitCurrentSource(sourceGeneration) { isLoading = true }
            }

        try {
            mediaOperationMutex.withLock {
                lifecycle.ensureCurrentSource(sourceGeneration)
                val instance = videoPlayerInstance
                if (instance == 0L || !_hasMedia) return@withLock

                seekInProgress.set(true)
                try {
                    videoReaderMutex.withLock {
                        // Inside the reader mutex: no concurrent ReadVideoFrame.
                        initialFrameRead.set(false)
                        lastFrameHash = Int.MIN_VALUE
                        clearFrameChannel()

                        val nativeTargetPos = externalHlsLocalSeekTicks(targetPos)
                        var hr = nativeSeekMedia(instance, nativeTargetPos)
                        if (hr < 0) {
                            delay(30.milliseconds)
                            lifecycle.ensureCurrentSource(sourceGeneration)
                            hr = nativeSeekMedia(instance, nativeTargetPos)
                        }
                        if (hr < 0) {
                            lifecycle.commitCurrentSource(sourceGeneration) {
                                setError("Seek failed (hr=0x${hr.toString(16)})")
                            }
                            return@withLock
                        }

                        val posArr = LongArray(1)
                        if (nativeGetMediaPosition(instance, posArr) >= 0) {
                            val updatedTime = adjustedExternalHlsPosition(posArr[0].hundredNanosecondsAsDuration())
                            lifecycle.commitCurrentSource(sourceGeneration) {
                                _currentTime = updatedTime
                                _progress =
                                    if (_duration > Duration.ZERO) {
                                        (_currentTime.toSecondsDouble() / _duration.toSecondsDouble())
                                            .toFloat()
                                            .coerceIn(0f, 1f)
                                    } else {
                                        0f
                                    }
                            }
                        }
                    }
                } finally {
                    seekInProgress.set(false)
                }

                // If the producer was never started (e.g. stop() was called
                // before the first play), start it now so the new frame shows.
                if (lifecycle.isCurrentSource(sourceGeneration) && (videoJob == null || videoJob?.isActive == false)) {
                    videoJob = startVideoPipeline()
                }
            }
        } finally {
            loadingTrigger.cancel()
            lifecycle.commitCurrentSource(sourceGeneration) { isLoading = false }
        }
    }

    /**
     * Called when the player surface is resized
     * Temporarily pauses frame processing to avoid artifacts during resize
     * For 4K videos, we need a longer delay to prevent memory pressure
     */
    fun onResized(
        width: Int = 0,
        height: Int = 0,
    ) {
        lifecycle.ensureUsable()

        if (width <= 0 || height <= 0) return

        if (width == surfaceWidth && height == surfaceHeight) return

        surfaceWidth = width
        surfaceHeight = height

        // Mark resizing in progress and debounce rapid events
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
     * Asks Media Foundation to produce frames at the display surface size
     * instead of full native resolution. Saves significant memory for 4K+ video.
     */
    private suspend fun applyOutputScaling() {
        if (lifecycle.isDisposed || !_hasMedia) return
        val sw = surfaceWidth
        val sh = surfaceHeight
        if (sw <= 0 || sh <= 0) return

        val instance = videoPlayerInstance
        if (instance == 0L) return

        mediaOperationMutex.withLock {
            val hr = nativeSetOutputSize(instance, sw, sh)
            if (hr >= 0) {
                // Update dimensions from native side
                val sizeArr = IntArray(2)
                nativeGetVideoSize(instance, sizeArr)
                if (sizeArr[0] > 0 && sizeArr[1] > 0) {
                    videoWidth = sizeArr[0]
                    videoHeight = sizeArr[1]
                    // Reset bitmaps so they are reallocated at the new size
                    lastFrameHash = Int.MIN_VALUE
                }
            }
        }
    }

    private fun destroyNativeInstance(
        instance: Long,
        usesLibVlc: Boolean = nativeBackendUsesLibVlc,
    ) {
        if (usesLibVlc) {
            WindowsNativeBridge.destroyLibVlcInstance(instance)
        } else {
            WindowsNativeBridge.destroyInstance(instance)
        }
    }

    private fun nativeCloseMedia(
        instance: Long,
        usesLibVlc: Boolean = nativeBackendUsesLibVlc,
    ) {
        if (usesLibVlc) {
            WindowsNativeBridge.nCloseLibVlcMedia(instance)
        } else {
            player.CloseMedia(instance)
        }
    }

    private fun nativeSetPlaybackState(
        instance: Long,
        playing: Boolean,
        stop: Boolean,
        usesLibVlc: Boolean = nativeBackendUsesLibVlc,
    ): Int =
        if (usesLibVlc) {
            WindowsNativeBridge.nSetLibVlcPlaybackState(instance, playing, stop)
        } else {
            player.SetPlaybackState(instance, playing, stop)
        }

    private fun nativeSetAudioVolume(
        instance: Long,
        volume: Float,
    ): Int =
        if (nativeBackendUsesLibVlc) {
            WindowsNativeBridge.nSetLibVlcAudioVolume(instance, volume)
        } else {
            player.SetAudioVolume(instance, volume)
        }

    private fun nativeGetAudioVolume(
        instance: Long,
        outVolume: FloatArray,
    ): Int =
        if (nativeBackendUsesLibVlc) {
            WindowsNativeBridge.nGetLibVlcAudioVolume(instance, outVolume)
        } else {
            player.GetAudioVolume(instance, outVolume)
        }

    private fun nativeSetPlaybackSpeed(
        instance: Long,
        speed: Float,
    ): Int =
        if (nativeBackendUsesLibVlc) {
            WindowsNativeBridge.nSetLibVlcPlaybackSpeed(instance, speed)
        } else {
            player.SetPlaybackSpeed(instance, speed)
        }

    private fun nativeReadVideoFrame(
        instance: Long,
        outResult: IntArray,
    ): ByteBuffer? =
        if (nativeBackendUsesLibVlc) {
            WindowsNativeBridge.nReadLibVlcVideoFrame(instance, outResult)
        } else {
            player.ReadVideoFrame(instance, outResult)
        }

    private fun nativeUnlockVideoFrame(instance: Long): Int =
        if (nativeBackendUsesLibVlc) {
            WindowsNativeBridge.nUnlockLibVlcVideoFrame(instance)
        } else {
            player.UnlockVideoFrame(instance)
        }

    private fun nativeIsEOF(instance: Long): Boolean =
        if (nativeBackendUsesLibVlc) {
            WindowsNativeBridge.nIsLibVlcEOF(instance)
        } else {
            player.IsEOF(instance)
        }

    private fun nativeGetVideoSize(
        instance: Long,
        outSize: IntArray,
    ) {
        if (nativeBackendUsesLibVlc) {
            WindowsNativeBridge.nGetLibVlcVideoSize(instance, outSize)
        } else {
            player.GetVideoSize(instance, outSize)
        }
    }

    private fun nativeVideoFrameRate(
        instance: Long,
        outRate: IntArray,
    ): Float =
        if (nativeBackendUsesLibVlc) {
            val fps = WindowsNativeBridge.nGetLibVlcVideoFrameRate(instance)
            if (fps > 0.0f) {
                outRate[0] = (fps * 1000.0f).toInt()
                outRate[1] = 1000
            }
            fps
        } else if (player.nGetVideoFrameRate(instance, outRate) >= 0 && outRate[0] > 0) {
            outRate[0].toFloat() / outRate[1].coerceAtLeast(1).toFloat()
        } else {
            0.0f
        }

    private fun nativeSeekMedia(
        instance: Long,
        position: Long,
    ): Int =
        if (nativeBackendUsesLibVlc) {
            WindowsNativeBridge.nSeekLibVlcMedia(instance, position)
        } else {
            player.SeekMedia(instance, position)
        }

    private fun nativeGetMediaDuration(
        instance: Long,
        outDuration: LongArray,
    ): Int =
        if (nativeBackendUsesLibVlc) {
            WindowsNativeBridge.nGetLibVlcMediaDuration(instance, outDuration)
        } else {
            player.GetMediaDuration(instance, outDuration)
        }

    private fun nativeGetMediaPosition(
        instance: Long,
        outPosition: LongArray,
    ): Int =
        if (nativeBackendUsesLibVlc) {
            WindowsNativeBridge.nGetLibVlcMediaPosition(instance, outPosition)
        } else {
            player.GetMediaPosition(instance, outPosition)
        }

    private fun nativeSetOutputSize(
        instance: Long,
        width: Int,
        height: Int,
    ): Int =
        if (nativeBackendUsesLibVlc) {
            0
        } else {
            player.SetOutputSize(instance, width, height)
        }

    private fun nativeVideoMetadata(instance: Long): VideoMetadata? {
        if (!nativeBackendUsesLibVlc) return WindowsNativeBridge.getVideoMetadata(instance)

        val durationArr = LongArray(1)
        nativeGetMediaDuration(instance, durationArr)
        val rateArr = IntArray(2)
        val frameRate = nativeVideoFrameRate(instance, rateArr).takeIf { it > 0.0f }
        return VideoMetadata(
            width = videoWidth,
            height = videoHeight,
            duration = durationArr[0].hundredNanosecondsAsDuration(),
            frameRate = frameRate,
        )
    }

    /**
     * Sets an error state with the given message
     *
     * @param msg The error message
     */
    private fun setError(msg: String) {
        _error = VideoPlayerError.UnknownError(msg)
        errorMessage = msg
        isLoading = false
        windowsLogger.e { msg }
    }

    /**
     * Creates an ImageInfo object for the current video dimensions
     *
     * @return ImageInfo configured for the current video frame
     */
    private fun createVideoImageInfo() = ImageInfo(videoWidth, videoHeight, ColorType.BGRA_8888, ColorAlphaType.OPAQUE)

    private fun drainPendingCloseBitmaps() {
        if (pendingCloseBitmaps.isEmpty()) return
        bitmapLock.write {
            val iterator = pendingCloseBitmaps.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                entry.framesLeft -= 1
                if (entry.framesLeft <= 0) {
                    try {
                        entry.bitmap.close()
                    } catch (_: Throwable) {
                        // Ignore: bitmap may already be released by Skia cleaner.
                    }
                    iterator.remove()
                }
            }
        }
    }

    /**
     * Sets the playback state (playing or paused)
     *
     * @param playing True to start playback, false to pause
     * @param errorMessage Error message to display if the operation fails
     * @param bStop True to stop playback completely, false to pause
     * @return True if the operation succeeded, false otherwise
     */
    private fun setPlaybackState(
        playing: Boolean,
        errorMessage: String,
        bStop: Boolean = false,
    ): Boolean {
        return videoPlayerInstance.takeIf { it != 0L }?.let { instance ->
            val nativeSurfacePending =
                (shouldUseLibVlcNativeSurface() && !libVlcNativeSurfaceAttached) ||
                    (shouldUseWindowsHdrSurface() && !windowsHdrSurfaceAttached)
            if (nativeSurfacePending && !bStop) {
                _isPlaying = playing
                _error?.let { clearError() }
                return true
            }

            for (attempt in 1..3) {
                val res = nativeSetPlaybackState(instance, playing, stop = bStop)
                if (res >= 0) {
                    _isPlaying = playing
                    _error?.let { clearError() }
                    return true
                }
                if (attempt == 3) {
                    setError("$errorMessage (hr=0x${res.toString(16)}) after $attempt attempts")
                }
            }
            false
        } ?: run {
            setError("$errorMessage: No player instance")
            false
        }
    }

    /**
     * Flag to track if we've read at least one frame when paused.
     * Initialize to false to ensure we read an initial frame when the player is first loaded.
     */
    private val initialFrameRead = AtomicBoolean(false)

    /**
     * Waits for the playback state to become active
     * If playback doesn't start within 5 seconds, attempts to restart it
     * unless the user has intentionally paused the video
     *
     * @param allowInitialFrame If true, allows reading one frame even when paused (for thumbnail)
     * @return True if the method should continue processing frames, false if it should wait
     */
    private suspend fun waitForPlaybackState(allowInitialFrame: Boolean = false): Boolean {
        if (_isPlaying) return true

        // When paused, allow the producer to read exactly one frame for display.
        if (userPaused && allowInitialFrame && !initialFrameRead.getAndSet(true)) {
            return true
        }

        if (isLoading) isLoading = false

        // Polling wait — wakes up on either _isPlaying turning true OR
        // initialFrameRead being reset (e.g. after a paused seek, where the
        // producer must fetch & display the new frame without needing
        // cancellation/restart of its coroutine).
        while (scope.isActive && _hasMedia && !lifecycle.isDisposed) {
            if (_isPlaying) return true
            if (userPaused && allowInitialFrame && !initialFrameRead.getAndSet(true)) return true
            try {
                delay(40.milliseconds)
            } catch (e: CancellationException) {
                throw e
            }
        }
        return false
    }

    /** Tracks how many consecutive iterations we've been waiting for resize */
    private var resizeWaitCount = 0

    /**
     * Waits if the player is currently resizing.
     * Has a safety timeout to prevent infinite blocking.
     *
     * @return True if resizing is in progress and we waited, false otherwise
     */
    private suspend fun waitIfResizing(): Boolean {
        if (isResizing.get()) {
            resizeWaitCount++
            if (resizeWaitCount > 200) { // ~1.6s max wait
                windowsLogger.w {
                    "waitIfResizing: timeout after $resizeWaitCount iterations, forcing isResizing=false"
                }
                isResizing.set(false)
                resizeWaitCount = 0
                return false
            }
            try {
                yield()
                delay(8.milliseconds)
            } catch (e: CancellationException) {
                throw e
            }
            return true
        }
        resizeWaitCount = 0
        return false
    }

    /**
     * Checks if the player is ready for playback
     *
     * @return True if the player is initialized and has media loaded, false otherwise
     */
    private fun readyForPlayback(): Boolean =
        initReady.isCompleted && videoPlayerInstance != 0L && _hasMedia && !lifecycle.isDisposed

    /**
     * Executes a media operation with proper error handling and mutex locking
     *
     * @param operation Name of the operation for error reporting
     * @param precondition Condition that must be true for the operation to execute
     * @param block The operation to execute
     */
    private fun executeMediaOperation(
        operation: String,
        precondition: Boolean = true,
        block: suspend () -> Unit,
    ) {
        lifecycle.ensureUsable()
        if (!precondition) return

        lifecycle.launchControlOperation {
            mediaOperationMutex.withLock {
                try {
                    block()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (!lifecycle.isDisposed) {
                        setError("Error during $operation: ${e.message}")
                    }
                }
            }
        }
    }

    override fun selectSubtitleTrack(trackId: String?): TrackSelectionResult {
        lifecycle.ensureUsable()
        return trackId
            ?.let { id ->
                availableSubtitleTracks
                    .firstOrNull { it.id == id }
                    ?.let(::selectSubtitleTrack)
                    ?: TrackSelectionResult.NotFound(id)
            } ?: selectSubtitleTrack(null as SubtitleTrack?)
    }

    override fun selectSubtitleTrack(track: SubtitleTrack?): TrackSelectionResult {
        lifecycle.ensureUsable()
        if (track != null && track.isEmbedded && availableSubtitleTracks.none { it.id == track.id }) {
            return TrackSelectionResult.NotFound(track.id)
        }
        val assSelectionToken = desktopAssSelectionToken.incrementAndGet()
        if (track == null) {
            clearDesktopAssSubtitleRenderer(assSelectionToken)
        }
        if (track == null && libVlcBackendActive) {
            lifecycle.launchSourceBoundControlOperation { generation ->
                mediaOperationMutex.withLock { disableLibVlcSubtitles(generation) }
            }
            return TrackSelectionResult.Disabled
        }

        val selectedLibVlcStreamIndex =
            track
                ?.id
                ?.takeIf(::isLibVlcSubtitleTrackId)
                ?.let(::libVlcTrackStreamIndex)

        if (track != null && selectedLibVlcStreamIndex != null) {
            clearDesktopAssSubtitleRenderer(assSelectionToken)
            lifecycle.launchSourceBoundControlOperation { generation ->
                selectLibVlcSubtitleTrack(
                    track = track,
                    streamIndex = selectedLibVlcStreamIndex,
                    assSelectionToken = assSelectionToken,
                    generation = generation,
                )
            }
            return TrackSelectionResult.Selected(track.id)
        }

        val selectedStreamIndex =
            track
                ?.id
                ?.takeIf(::isExternalHlsSubtitleTrackId)
                ?.let(::externalHlsTrackStreamIndex)

        if (track != null && selectedStreamIndex != null) {
            clearDesktopAssSubtitleRenderer(assSelectionToken)
            lifecycle.launchSourceBoundControlOperation { generation ->
                switchExternalHlsSubtitleTrack(track, selectedStreamIndex, generation)
            }
            return TrackSelectionResult.Selected(track.id)
        }

        if (track != null && canUseDesktopAssSubtitleOverlay(track)) {
            clearDesktopAssSubtitleRenderer(assSelectionToken)
            lifecycle.launchSourceBoundControlOperation { generation ->
                try {
                    val content = withContext(Dispatchers.IO) { loadSubtitleContent(track.src) }
                    if (desktopAssSelectionToken.get() != assSelectionToken) {
                        return@launchSourceBoundControlOperation
                    }
                    val backend =
                        withContext(Dispatchers.Default) {
                            desktopAssSubtitleSession.configure(
                                track = track,
                                content = content,
                                ownerToken = assSelectionToken,
                            )
                        }
                    lifecycle.ensureCurrentSource(generation)
                    if (desktopAssSelectionToken.get() != assSelectionToken) {
                        desktopAssSubtitleSession.clear(assSelectionToken)
                        return@launchSourceBoundControlOperation
                    }
                    lifecycle.commitCurrentSource(generation) {
                        if (desktopAssSelectionToken.get() != assSelectionToken) return@commitCurrentSource
                        currentSubtitleTrack = track
                        subtitlesEnabled = true
                        usesLibAssSubtitleOverlay = true
                        renderingInfo.subtitleRenderer = "$backend dynamic overlay"
                        renderingInfo.subtitleSource = track.src
                        _error = null
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) {
                        desktopAssSubtitleSession.clear(assSelectionToken)
                        throw e
                    }
                    windowsLogger.w { "ASS subtitle renderer unavailable; using Compose fallback: ${e.message}" }
                    if (desktopAssSelectionToken.get() == assSelectionToken) {
                        desktopAssSubtitleSession.clear(assSelectionToken)
                        lifecycle.commitCurrentSource(generation) {
                            if (desktopAssSelectionToken.get() != assSelectionToken) return@commitCurrentSource
                            currentSubtitleTrack = track
                            subtitlesEnabled = true
                            usesLibAssSubtitleOverlay = false
                            renderingInfo.subtitleRenderer =
                                "Compose dialogue fallback (libass unavailable)"
                            renderingInfo.subtitleSource = track.src
                        }
                    }
                }
            }
            return TrackSelectionResult.Selected(track.id)
        }

        clearDesktopAssSubtitleRenderer(assSelectionToken)
        currentSubtitleTrack = track
        subtitlesEnabled = track != null
        return track.subtitleTrackSelectionResult()
    }

    private fun canUseDesktopAssSubtitleOverlay(track: SubtitleTrack): Boolean =
        isAssLikeDesktopTrack(track) &&
            !shouldUseWindowsHdrSurface() &&
            !shouldUseLibVlcNativeSurface()

    private fun handleDesktopAssRendererFailure() {
        usesLibAssSubtitleOverlay = false
        val track = currentSubtitleTrack
        val streamIndex =
            track
                ?.id
                ?.takeIf(::isLibVlcSubtitleTrackId)
                ?.let(::libVlcTrackStreamIndex)
        if (track == null || streamIndex == null || !libVlcBackendActive) {
            renderingInfo.subtitleRenderer =
                "Compose dialogue fallback (libass renderer failed)"
            return
        }

        val assSelectionToken = desktopAssSelectionToken.get()
        renderingInfo.subtitleRenderer =
            "libVLC subtitle fallback (libass renderer failed)"
        lifecycle.launchSourceBoundControlOperation { generation ->
            selectNativeLibVlcSubtitleTrack(
                track = track,
                streamIndex = streamIndex,
                assSelectionToken = assSelectionToken,
                generation = generation,
            )
        }
    }

    private fun clearDesktopAssSubtitleRenderer(selectionToken: Long = desktopAssSelectionToken.incrementAndGet()) {
        if (desktopAssSelectionToken.get() != selectionToken) return
        desktopAssSubtitleSession.clear()
        usesLibAssSubtitleOverlay = false
        renderingInfo.subtitleRenderer = null
        renderingInfo.subtitleSource = null
    }

    override fun selectAudioTrack(trackId: String?): TrackSelectionResult {
        lifecycle.ensureUsable()
        return trackId
            ?.let { id ->
                availableAudioTracks
                    .firstOrNull { it.id == id }
                    ?.let(::selectAudioTrack)
                    ?: TrackSelectionResult.NotFound(id)
            } ?: selectAudioTrack(null as AudioTrack?)
    }

    override fun selectAudioTrack(track: AudioTrack?): TrackSelectionResult {
        lifecycle.ensureUsable()
        if (track != null && availableAudioTracks.none { it.id == track.id }) {
            return TrackSelectionResult.NotFound(track.id)
        }

        val selectedLibVlcStreamIndex =
            track
                ?.id
                ?.takeIf(::isLibVlcAudioTrackId)
                ?.let(::libVlcTrackStreamIndex)

        if (track != null && selectedLibVlcStreamIndex != null) {
            lifecycle.launchSourceBoundControlOperation { generation ->
                mediaOperationMutex.withLock {
                    selectLibVlcAudioTrack(track, selectedLibVlcStreamIndex, generation)
                }
            }
            return TrackSelectionResult.Selected(track.id)
        }

        val selectedStreamIndex =
            track
                ?.id
                ?.takeIf(::isExternalHlsAudioTrackId)
                ?.let(::externalHlsTrackStreamIndex)

        if (track != null && selectedStreamIndex != null) {
            lifecycle.launchSourceBoundControlOperation { generation ->
                switchExternalHlsAudioTrack(track, selectedStreamIndex, generation)
            }
            return TrackSelectionResult.Selected(track.id)
        }

        currentAudioTrack = track
        return track.audioTrackSelectionResult()
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
        clearDesktopAssSubtitleRenderer()
        val selectedTrack = currentSubtitleTrack
        if (libVlcBackendActive && selectedTrack?.id?.let(::isLibVlcSubtitleTrackId) == true) {
            lifecycle.launchSourceBoundControlOperation { generation ->
                mediaOperationMutex.withLock { disableLibVlcSubtitles(generation) }
            }
            return TrackSelectionResult.Disabled
        }

        if (externalHlsSourceUri != null && externalHlsSelectedSubtitleStreamIndex != null) {
            lifecycle.launchSourceBoundControlOperation { generation ->
                switchExternalHlsSubtitleTrack(track = null, streamIndex = null, generation = generation)
            }
            return TrackSelectionResult.Disabled
        }
        subtitlesEnabled = false
        currentSubtitleTrack = null
        return TrackSelectionResult.Disabled
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
        val instance = videoPlayerInstance
        val applied =
            ordinal != null &&
                instance != 0L &&
                WindowsNativeBridge.nSelectLibVlcAudioTrack(instance, ordinal)
        lifecycle.ensureCurrentSource(generation)
        lifecycle.commitCurrentSource(generation) {
            if (applied) {
                libVlcSelectedAudioStreamIndex = streamIndex
                currentAudioTrack = track
            } else {
                _error = VideoPlayerError.CodecError("Failed to select libVLC audio track: ${track.id}")
            }
        }
    }

    private suspend fun selectLibVlcSubtitleTrack(
        track: SubtitleTrack,
        streamIndex: Int,
        assSelectionToken: Long,
        generation: Long,
    ) {
        if (trySelectEmbeddedLibAssSubtitleTrack(track, streamIndex, assSelectionToken, generation)) {
            return
        }
        selectNativeLibVlcSubtitleTrack(track, streamIndex, assSelectionToken, generation)
    }

    private suspend fun selectNativeLibVlcSubtitleTrack(
        track: SubtitleTrack,
        streamIndex: Int,
        assSelectionToken: Long,
        generation: Long,
    ) {
        lifecycle.ensureCurrentSource(generation)
        if (desktopAssSelectionToken.get() != assSelectionToken) return

        val ordinal =
            libVlcTrackInfo
                ?.subtitleStreams
                ?.firstOrNull { it.streamIndex == streamIndex }
                ?.ordinal
        val instance = videoPlayerInstance
        val applied =
            mediaOperationMutex.withLock {
                ordinal != null &&
                    instance != 0L &&
                    WindowsNativeBridge.nSelectLibVlcSubtitleTrack(instance, ordinal)
            }
        lifecycle.ensureCurrentSource(generation)
        lifecycle.commitCurrentSource(generation) {
            if (desktopAssSelectionToken.get() != assSelectionToken) return@commitCurrentSource
            if (applied) {
                libVlcSelectedSubtitleStreamIndex = streamIndex
                currentSubtitleTrack = track
                subtitlesEnabled = true
                usesLibAssSubtitleOverlay = false
                renderingInfo.subtitleRenderer = "libVLC subtitle renderer"
                renderingInfo.subtitleSource = track.src
            } else {
                _error = VideoPlayerError.CodecError("Failed to select libVLC subtitle track: ${track.id}")
            }
        }
    }

    private suspend fun trySelectEmbeddedLibAssSubtitleTrack(
        track: SubtitleTrack,
        streamIndex: Int,
        assSelectionToken: Long,
        generation: Long,
    ): Boolean {
        if (!canUseDesktopAssSubtitleOverlay(track)) return false
        val sourceUri = lastUri ?: libVlcSourceUri ?: return false

        try {
            val payload =
                withContext(Dispatchers.IO) {
                    extractEmbeddedDesktopAssSubtitle(
                        uri = sourceUri,
                        streamIndex = streamIndex,
                        requestHeaders = lastRequestHeaders,
                    )
                }
            lifecycle.ensureCurrentSource(generation)
            if (desktopAssSelectionToken.get() != assSelectionToken) return true

            val backend =
                withContext(Dispatchers.Default) {
                    desktopAssSubtitleSession.configure(
                        track = track,
                        content = payload.content,
                        fonts = payload.fonts,
                        streamIndex = streamIndex,
                        ownerToken = assSelectionToken,
                    )
                }
            lifecycle.ensureCurrentSource(generation)
            if (desktopAssSelectionToken.get() != assSelectionToken) {
                desktopAssSubtitleSession.clear(assSelectionToken)
                return true
            }

            val instance = videoPlayerInstance
            val nativeSubtitlesDisabled =
                mediaOperationMutex.withLock {
                    instance != 0L && WindowsNativeBridge.nDisableLibVlcSubtitles(instance)
                }
            check(nativeSubtitlesDisabled) {
                "libVLC could not release the embedded subtitle overlay."
            }
            lifecycle.ensureCurrentSource(generation)
            if (desktopAssSelectionToken.get() != assSelectionToken) {
                desktopAssSubtitleSession.clear(assSelectionToken)
                return true
            }

            lifecycle.commitCurrentSource(generation) {
                if (desktopAssSelectionToken.get() != assSelectionToken) return@commitCurrentSource
                libVlcSelectedSubtitleStreamIndex = streamIndex
                currentSubtitleTrack = track
                subtitlesEnabled = true
                usesLibAssSubtitleOverlay = true
                renderingInfo.subtitleRenderer = "$backend dynamic overlay"
                renderingInfo.subtitleSource = track.src
                _error = null
            }
            return true
        } catch (e: CancellationException) {
            desktopAssSubtitleSession.clear(assSelectionToken)
            throw e
        } catch (e: Exception) {
            if (desktopAssSelectionToken.get() != assSelectionToken) return true
            desktopAssSubtitleSession.clear(assSelectionToken)
            windowsLogger.w {
                "Embedded ASS extraction/rendering unavailable; using libVLC subtitles: ${e.message}"
            }
            lifecycle.ensureCurrentSource(generation)
            return false
        }
    }

    private suspend fun disableLibVlcSubtitles(generation: Long) {
        val instance = videoPlayerInstance
        val applied = instance != 0L && WindowsNativeBridge.nDisableLibVlcSubtitles(instance)
        lifecycle.ensureCurrentSource(generation)
        lifecycle.commitCurrentSource(generation) {
            if (applied) {
                libVlcSelectedSubtitleStreamIndex = null
                subtitlesEnabled = false
                currentSubtitleTrack = null
            } else {
                _error = VideoPlayerError.CodecError("Failed to disable libVLC subtitles")
            }
        }
    }

    private suspend fun switchExternalHlsAudioTrack(
        track: AudioTrack,
        streamIndex: Int,
        generation: Long,
    ) {
        val sourceUri = externalHlsSourceUri
        if (sourceUri == null) {
            lifecycle.commitCurrentSource(generation) { currentAudioTrack = track }
            return
        }
        if (externalHlsSelectedAudioStreamIndex == streamIndex) {
            lifecycle.commitCurrentSource(generation) { currentAudioTrack = track }
            return
        }

        restartExternalHlsPlayback(
            sourceUri = sourceUri,
            selectedAudioStreamIndex = streamIndex,
            selectedSubtitleStreamIndex = externalHlsSelectedSubtitleStreamIndex,
            onRestarted = {
                currentAudioTrack = track
            },
            failureMessage = "Failed to switch audio track",
            generation = generation,
        )
    }

    private suspend fun switchExternalHlsSubtitleTrack(
        track: SubtitleTrack?,
        streamIndex: Int?,
        generation: Long,
    ) {
        val sourceUri = externalHlsSourceUri
        if (sourceUri == null) {
            lifecycle.commitCurrentSource(generation) {
                currentSubtitleTrack = track
                subtitlesEnabled = track != null
            }
            return
        }
        if (externalHlsSelectedSubtitleStreamIndex == streamIndex) {
            lifecycle.commitCurrentSource(generation) {
                currentSubtitleTrack = track
                subtitlesEnabled = track != null
            }
            return
        }
        if (track != null && !ExternalHlsFallbackSupport.hasSubtitleRenderer(playbackOptions.extensions)) {
            lifecycle.commitCurrentSource(generation) {
                isLoading = false
                currentSubtitleTrack = null
                subtitlesEnabled = false
                _error =
                    VideoPlayerError.CodecError(
                        "Full embedded subtitle rendering through the external HLS fallback requires VLC " +
                            "or ffmpeg with libass and the subtitles filter enabled.",
                    )
            }
            return
        }

        restartExternalHlsPlayback(
            sourceUri = sourceUri,
            selectedAudioStreamIndex = externalHlsSelectedAudioStreamIndex,
            selectedSubtitleStreamIndex = streamIndex,
            onRestarted = {
                currentSubtitleTrack = track
                subtitlesEnabled = track != null
            },
            failureMessage = "Failed to switch subtitle track",
            generation = generation,
        )
    }

    private suspend fun restartExternalHlsPlayback(
        sourceUri: String,
        selectedAudioStreamIndex: Int?,
        selectedSubtitleStreamIndex: Int?,
        onRestarted: () -> Unit,
        failureMessage: String,
        generation: Long,
    ) {
        val shouldResumePlayback = _isPlaying
        val restartPosition = _currentTime
        val duration = _duration
        val loadingCommitted =
            lifecycle.commitCurrentSource(generation) {
                _progress =
                    if (duration > Duration.ZERO) {
                        (restartPosition.toSecondsDouble() / duration.toSecondsDouble()).toFloat().coerceIn(0f, 1f)
                    } else {
                        _progress
                    }
                isLoading = true
                _error = null
                errorMessage = null
                externalHlsSelectedAudioStreamIndex = selectedAudioStreamIndex
                externalHlsSelectedSubtitleStreamIndex = selectedSubtitleStreamIndex
                externalHlsPlaybackOffsetSeconds = restartPosition.toSecondsDouble()
            }
        if (!loadingCommitted) {
            return
        }

        openUriInternal(
            uri = sourceUri,
            initializePlayerState = if (shouldResumePlayback) InitialPlayerState.PLAY else InitialPlayerState.PAUSE,
            requestHeaders = lastRequestHeaders,
            sourceGeneration = generation,
        )
        lifecycle.ensureCurrentSource(generation)

        lifecycle.commitCurrentSource(generation) {
            if (_hasMedia) {
                onRestarted()
            } else {
                _error = VideoPlayerError.SourceError(failureMessage)
            }
        }
    }

    override fun selectHlsQuality(variantId: String?): HlsQualitySelectionResult {
        lifecycle.ensureUsable()
        return HlsQualitySelectionResult.NotSupported
    }

    override fun clearCache(): CacheClearResult {
        lifecycle.ensureUsable()
        return CacheClearResult.NotSupported
    }

    /**
     * Toggles the fullscreen state of the video player
     */
    override fun toggleFullscreen() {
        lifecycle.ensureUsable()
        isFullscreen = !isFullscreen
    }
}
