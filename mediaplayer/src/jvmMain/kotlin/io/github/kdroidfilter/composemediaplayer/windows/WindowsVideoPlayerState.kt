package io.github.kdroidfilter.composemediaplayer.windows

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import io.github.kdroidfilter.composemediaplayer.AudioTrack
import io.github.kdroidfilter.composemediaplayer.DesktopVideoBackend
import io.github.kdroidfilter.composemediaplayer.ExternalHlsFallbackSupport
import io.github.kdroidfilter.composemediaplayer.ExternalVlcLocator
import io.github.kdroidfilter.composemediaplayer.HlsFallbackSource
import io.github.kdroidfilter.composemediaplayer.InitialPlayerState
import io.github.kdroidfilter.composemediaplayer.JvmExternalFallbackContainerSupport
import io.github.kdroidfilter.composemediaplayer.JvmLibVlcAudioStream
import io.github.kdroidfilter.composemediaplayer.JvmLibVlcInstallation
import io.github.kdroidfilter.composemediaplayer.JvmLibVlcMediaProbe
import io.github.kdroidfilter.composemediaplayer.JvmLibVlcSubtitleStream
import io.github.kdroidfilter.composemediaplayer.JvmLibVlcTrackInfo
import io.github.kdroidfilter.composemediaplayer.LIBVLC_CANVAS_AUDIO_TRACK_ID_PREFIX
import io.github.kdroidfilter.composemediaplayer.LIBVLC_CANVAS_SUBTITLE_TRACK_ID_PREFIX
import io.github.kdroidfilter.composemediaplayer.PlayerCapabilities
import io.github.kdroidfilter.composemediaplayer.SubtitleFormat
import io.github.kdroidfilter.composemediaplayer.SubtitleTrack
import io.github.kdroidfilter.composemediaplayer.VideoMetadata
import io.github.kdroidfilter.composemediaplayer.VideoPlaybackOptions
import io.github.kdroidfilter.composemediaplayer.VideoPlayerError
import io.github.kdroidfilter.composemediaplayer.VideoPlayerState
import io.github.kdroidfilter.composemediaplayer.jvmPlayerCapabilities
import io.github.kdroidfilter.composemediaplayer.requestHeadersLineString
import io.github.kdroidfilter.composemediaplayer.sanitizedRequestHeaders
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
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import java.io.Closeable
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.write
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

internal val windowsLogger = TaggedLogger("WindowsVideoPlayerState")

private data class WindowsResolvedLibVlcBackend(
    val installation: JvmLibVlcInstallation,
)

private data class WindowsLibVlcRuntimeTrackDescription(
    val ordinal: Int,
    val label: String,
)

/**
 * Windows implementation of the video player state.
 * Handles media playback using Media Foundation on Windows platform.
 */
@Suppress("MagicNumber", "TooManyFunctions")
class WindowsVideoPlayerState(
    private val playbackOptions: VideoPlaybackOptions = VideoPlaybackOptions(),
) : VideoPlayerState {
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

    /** Whether media has been loaded */
    private var _hasMedia by mutableStateOf(false)
    override val hasMedia get() = _hasMedia

    /** Whether media is currently playing */
    private var _isPlaying by mutableStateOf(false)
    override val isPlaying get() = _isPlaying

    /** Whether the user has intentionally paused the video */
    private var userPaused = false

    /** Video player instance handle */
    private var videoPlayerInstance: Long = 0L

    /** Deferred completed when initialization is ready */
    private val initReady = CompletableDeferred<Unit>()

    /** Flag to track if the player is being disposed */
    private val isDisposing = AtomicBoolean(false)

    /** Current volume level (0.0 to 1.0) */
    private var _volume by mutableStateOf(1f)

    /**
     * Volume control for the player (0.0 to 1.0)
     * Any modification triggers the native call SetAudioVolume
     */
    override var volume: Float
        get() = _volume
        set(value) {
            val newVolume = value.coerceIn(0f, 1f)
            if (_volume != newVolume) {
                _volume = newVolume
                scope.launch {
                    mediaOperationMutex.withLock {
                        videoPlayerInstance.takeIf { it != 0L }?.let { instance ->
                            // Store the volume setting for this instance
                            instanceVolumes[instance] = newVolume

                            // Apply the volume setting to the native player
                            val hr = nativeSetAudioVolume(instance, newVolume)
                            if (hr < 0) {
                                setError("Error updating volume (hr=0x${hr.toString(16)})")
                            }
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
            _progress = (value / VideoPlayerState.SLIDER_SCALE).coerceIn(0f, 1f)
        }
    private var _userDragging by mutableStateOf(false)
    override var userDragging: Boolean
        get() = _userDragging
        set(value) {
            _userDragging = value
        }
    private var _loop by mutableStateOf(false)
    override var loop: Boolean
        get() = _loop
        set(value) {
            _loop = value
        }

    override var onPlaybackEnded: (() -> Unit)? = null
    override var onRestart: (() -> Unit)? = null

    private var _playbackSpeed by mutableStateOf(1.0f)
    override var playbackSpeed: Float
        get() = _playbackSpeed
        set(value) {
            val newSpeed = value.coerceIn(VideoPlayerState.MIN_PLAYBACK_SPEED, VideoPlayerState.MAX_PLAYBACK_SPEED)
            if (_playbackSpeed != newSpeed) {
                _playbackSpeed = newSpeed
                scope.launch {
                    mediaOperationMutex.withLock {
                        videoPlayerInstance.takeIf { it != 0L }?.let { instance ->
                            val hr = nativeSetPlaybackSpeed(instance, newSpeed)
                            if (hr < 0) {
                                setError("Error updating playback speed (hr=0x${hr.toString(16)})")
                            }
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
    override var subtitlesEnabled by mutableStateOf(false)
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
    override var isLoading by mutableStateOf(false)
        private set
    override val positionText: String get() = formatTime(_currentTime)
    override val durationText: String get() = formatTime(_duration)
    override val currentTime: Duration get() = _currentTime
    override val preciseCurrentTime: Duration get() = _currentTime
    override val duration: Duration get() = _duration
    private var errorMessage: String? by mutableStateOf(null)

    // Fullscreen state
    override var isFullscreen by mutableStateOf(false)

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

    // Seek coalescing: rapid slider drags overwrite the target; only the
    // latest value is actually seeked. seekInFlight acts as the "a loop is
    // draining the target" claim.
    private val pendingSeekTarget = AtomicLong(Long.MIN_VALUE)
    private val seekInFlight = AtomicBoolean(false)

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
    private var libVlcBackendActive: Boolean = false
    private var libVlcSourceUri: String? = null
    private var libVlcTrackInfo: JvmLibVlcTrackInfo? = null
    private var libVlcSelectedAudioStreamIndex: Int? = null
    private var libVlcSelectedSubtitleStreamIndex: Int? = null
    private var nativeBackendUsesLibVlc: Boolean = false

    init {
        // Kick off native initialization immediately
        scope.launch {
            try {
                val handle = WindowsNativeBridge.createInstance()
                if (handle == 0L) {
                    setError("Failed to create video player instance")
                    return@launch
                }
                videoPlayerInstance = handle

                // Store default volume so that later instances inherit it
                instanceVolumes[handle] = _volume
                initReady.complete(Unit)
            } catch (e: Exception) {
                initReady.completeExceptionally(e)
                setError("Exception during initialization: ${e.message}")
            }
        }
    }

    override fun dispose() {
        if (isDisposing.getAndSet(true)) {
            return // Already disposing
        }

        // Stop coroutines first. The producer reads native state under
        // videoReaderMutex; we must wait for it to exit its critical section
        // before tearing down the native reader, otherwise CloseMedia can
        // free memory the producer is still dereferencing (exit 2170).
        val jobToJoin = videoJob
        videoJob = null
        jobToJoin?.cancel()
        resizeJob?.cancel()
        _isPlaying = false
        _hasMedia = false

        releaseAllResources()

        val instance = videoPlayerInstance
        val wasLibVlc = nativeBackendUsesLibVlc
        videoPlayerInstance = 0L
        lastUri = null
        lastRequestHeaders = emptyMap()

        // Native cleanup must run SYNCHRONOUSLY. Compose Desktop's window close
        // ultimately calls System.exit, which will not wait for an arbitrary
        // background thread: the DLL gets unloaded while the native audio
        // thread is still running against freed globals, crashing the process
        // (exit 2170). Doing it here blocks the caller briefly (<500 ms for
        // StopAudioThread + MF teardown) but guarantees a clean shutdown.
        if (instance != 0L) {
            if (jobToJoin != null) {
                // Avoid runBlocking on the AWT Event Dispatch Thread: if any
                // child coroutine of `scope` ever chains on Dispatchers.Main
                // (even indirectly, e.g. Compose effects), joining here would
                // deadlock. Fall back to a plain Thread.join on EDT — the
                // 500 ms cap keeps the UI from hanging if the native side
                // stalls.
                val deadlineNs = System.nanoTime() + 500_000_000L
                if (java.awt.EventQueue.isDispatchThread()) {
                    while (jobToJoin.isActive && System.nanoTime() < deadlineNs) {
                        try {
                            Thread.sleep(10)
                        } catch (_: InterruptedException) {
                            break
                        }
                    }
                } else {
                    try {
                        kotlinx.coroutines.runBlocking {
                            kotlinx.coroutines.withTimeoutOrNull(500) {
                                jobToJoin.join()
                            }
                        }
                    } catch (_: Exception) {
                        // ignore
                    }
                }
            }

            try {
                nativeSetPlaybackState(instance, false, stop = true, usesLibVlc = wasLibVlc)
            } catch (e: Exception) {
                windowsLogger.e { "Exception stopping playback: ${e.message}" }
            }
            try {
                nativeCloseMedia(instance, usesLibVlc = wasLibVlc)
            } catch (e: Exception) {
                windowsLogger.e { "Exception closing media: ${e.message}" }
            }
            closeExternalHlsFallback()
            clearExternalHlsFallbackTrackState()
            clearLibVlcTrackState()
            instanceVolumes.remove(instance)
            try {
                destroyNativeInstance(instance, usesLibVlc = wasLibVlc)
            } catch (e: Exception) {
                windowsLogger.e { "Exception destroying instance: ${e.message}" }
            }
        }

        scope.cancel()
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
        if (isDisposing.get()) {
            windowsLogger.w { "Ignoring openUri call - player is being disposed" }
            return
        }

        val sanitizedHeaders = requestHeaders.sanitizedRequestHeaders()
        lastUri = uri
        lastRequestHeaders = sanitizedHeaders
        playbackSpeed = 1.0f

        scope.launch {
            try {
                // Wait for initialization to complete with a timeout
                withTimeout(10_000) { initReady.await() }

                // Here the native instance is guaranteed to be non-null
                openUriInternal(uri, initializePlayerState, sanitizedHeaders)
            } catch (_: TimeoutCancellationException) {
                setError("Player initialization timed out after 10 s.")
            } catch (e: Exception) {
                setError("Error while waiting for initialization: ${e.message}")
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
    private fun openUriInternal(
        uri: String,
        initializePlayerState: InitialPlayerState,
        requestHeaders: Map<String, String>,
    ) {
        scope.launch {
            if (isDisposing.get()) {
                return@launch
            }

            mediaOperationMutex.withLock {
                try {
                    isLoading = true

                    val libVlcBackend = resolveLibVlcBackendForUri(uri, requestHeaders)

                    // Stop playback and release existing resources
                    val wasPlaying = _isPlaying
                    val oldInstance = videoPlayerInstance

                    if (oldInstance != 0L && wasPlaying) {
                        nativeSetPlaybackState(oldInstance, false, stop = false)
                        _isPlaying = false
                        delay(50.milliseconds)
                    }

                    val preserveExternalHlsSelection = uri == externalHlsSourceUri
                    val requestedExternalAudioStreamIndex =
                        externalHlsSelectedAudioStreamIndex.takeIf { preserveExternalHlsSelection }
                    val requestedExternalSubtitleStreamIndex =
                        externalHlsSelectedSubtitleStreamIndex.takeIf { preserveExternalHlsSelection }
                    val requestedExternalPlaybackOffset =
                        externalHlsPlaybackOffsetSeconds.takeIf { preserveExternalHlsSelection } ?: 0.0

                    videoJob?.cancelAndJoin()
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

                    ensureNativeInstance(libVlcBackend)
                    val instance = videoPlayerInstance
                    if (instance == 0L) {
                        setError("Video player instance is null")
                        return@withLock
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
                    if (!uri.startsWith("http", ignoreCase = true) && !File(uri).exists()) {
                        setError("File not found: $uri")
                        return@withLock
                    }

                    val playbackUri =
                        if (libVlcBackend != null) {
                            prepareLibVlcPlayback(uri, requestHeaders)
                        } else if (shouldUseExternalHlsFallback(uri, requestHeaders)) {
                            prepareExternalHlsPlayback(uri, requestHeaders)
                        } else {
                            uri
                        }
                    val playbackRequestHeaders = if (playbackUri == uri) requestHeaders else emptyMap()

                    // Always open media in paused state to avoid starting the native
                    // playback clock before we've finished setup (SetOutputSize, metadata, etc.).
                    // We explicitly call SetPlaybackState(true) later, right before starting
                    // the frame-reading coroutine, so the wall-clock is in sync with frame production.
                    val startPlayback = initializePlayerState == InitialPlayerState.PLAY
                    val requestHeaderLines = playbackRequestHeaders.requestHeadersLineString()
                    val hrOpen =
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
                    if (hrOpen < 0) {
                        setError("Failed to open media (hr=0x${hrOpen.toString(16)}): $uri")
                        return@withLock
                    }

                    // Get the video dimensions
                    val sizeArr = IntArray(2)
                    nativeGetVideoSize(instance, sizeArr)
                    if (sizeArr[0] <= 0 || sizeArr[1] <= 0) {
                        setError("Failed to retrieve video size")
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
                            setError("Failed to retrieve duration (hr=0x${hrDuration.toString(16)})")
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
                        applyLibVlcSelectedTracks()
                    }

                    // Set _hasMedia to true only if everything succeeded
                    _hasMedia = true

                    if (!isDisposing.get()) {
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
                        if (startPlayback) {
                            val hrPlay = nativeSetPlaybackState(instance, true, stop = false)
                            if (hrPlay < 0) {
                                windowsLogger.e { "Failed to start playback (hr=0x${hrPlay.toString(16)})" }
                            }
                        }
                        _isPlaying = startPlayback

                        // Start video processing
                        videoJob = startVideoPipeline()
                    }
                } catch (e: Exception) {
                    setError("Error while opening media: ${e.message}")
                    _hasMedia = false
                } finally {
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
        val forcedDesktopBackend =
            when (playbackOptions.desktopVideoBackend) {
                DesktopVideoBackend.LIBVLC -> "libvlc"
                DesktopVideoBackend.LIBVLC_NATIVE ->
                    throw UnsupportedOperationException(
                        "LIBVLC_NATIVE is only available on macOS. Use LIBVLC for the Windows canvas backend.",
                    )
                DesktopVideoBackend.PLATFORM -> "platform"
                DesktopVideoBackend.AUTO -> null
            }
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
                ExternalVlcLocator.findLibVlc()?.let(::WindowsResolvedLibVlcBackend)
                    ?: throw missingLibVlcBackendException()
            "auto" -> ExternalVlcLocator.findLibVlc()?.let(::WindowsResolvedLibVlcBackend)
            "ffmpeg", "vlc" -> null
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
            "The Windows libVLC canvas backend was requested, but no compatible libVLC installation was found. " +
                "Install VLC for " +
                "${ExternalVlcLocator.currentProcessArchitecture() ?: "the current"} JVM architecture " +
                "or set composemediaplayer.libvlc and composemediaplayer.libvlc.plugins. " +
                "ComposeMediaPlayer does not bundle or link VLC.",
        )

    private suspend fun ensureNativeInstance(libVlcBackend: WindowsResolvedLibVlcBackend?) {
        val wantsLibVlc = libVlcBackend != null
        val existingInstance = videoPlayerInstance
        if (existingInstance != 0L && nativeBackendUsesLibVlc != wantsLibVlc) {
            nativeSetPlaybackState(existingInstance, false, stop = true)
            nativeCloseMedia(existingInstance)
            destroyNativeInstance(existingInstance)
            instanceVolumes.remove(existingInstance)
            videoPlayerInstance = 0L
            nativeBackendUsesLibVlc = false
        }

        if (videoPlayerInstance == 0L) {
            val handle =
                if (libVlcBackend != null) {
                    WindowsNativeBridge.createLibVlcInstance(
                        libVlcBackend.installation.libVlcPath,
                        libVlcBackend.installation.pluginPath,
                    )
                } else {
                    WindowsNativeBridge.createInstance()
                }
            if (handle == 0L) {
                throw IllegalStateException("Failed to create video player instance")
            }
            videoPlayerInstance = handle
            nativeBackendUsesLibVlc = wantsLibVlc
            instanceVolumes[handle] = _volume
            nativeSetAudioVolume(handle, _volume)
            nativeSetPlaybackSpeed(handle, _playbackSpeed)
        }
    }

    private suspend fun prepareLibVlcPlayback(
        uri: String,
        requestHeaders: Map<String, String>,
    ): String {
        libVlcBackendActive = true
        libVlcSourceUri = uri
        val trackInfo = withContext(Dispatchers.IO) { JvmLibVlcMediaProbe.probe(uri, requestHeaders) }
        libVlcTrackInfo = trackInfo
        updateLibVlcTracks(trackInfo)
        return uri
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
        val started =
            ExternalHlsFallbackSupport.start(
                uri = uri,
                requestHeaders = requestHeaders,
                selectedAudioStreamIndex = externalHlsSelectedAudioStreamIndex,
                selectedSubtitleStreamIndex = externalHlsSelectedSubtitleStreamIndex,
                startTimeSeconds = externalHlsPlaybackOffsetSeconds,
            )
        externalHlsFallback = started.fallback
        externalHlsFallbackDurationSeconds = started.source.durationSeconds
        externalHlsSourceUri = uri
        externalHlsSelectedAudioStreamIndex = started.source.selectedAudioStreamIndex
        externalHlsSelectedSubtitleStreamIndex = started.source.selectedSubtitleStreamIndex
        externalHlsPlaybackOffsetSeconds = started.source.playbackOffsetSeconds
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
        fallback?.close()
    }

    private fun clearExternalHlsFallbackTrackState() {
        externalHlsSelectedAudioStreamIndex = null
        externalHlsSelectedSubtitleStreamIndex = null
        externalHlsPlaybackOffsetSeconds = 0.0
        _availableAudioTracks.removeAll { ExternalHlsFallbackSupport.isExternalHlsAudioTrackId(it.id) }
        if (currentAudioTrack?.id?.let(ExternalHlsFallbackSupport::isExternalHlsAudioTrackId) == true) {
            currentAudioTrack = null
        }

        _availableSubtitleTracks.removeAll { ExternalHlsFallbackSupport.isExternalHlsSubtitleTrackId(it.id) }
        if (currentSubtitleTrack?.id?.let(ExternalHlsFallbackSupport::isExternalHlsSubtitleTrackId) == true) {
            currentSubtitleTrack = null
            subtitlesEnabled = false
        }
    }

    private fun updateExternalHlsFallbackTracks(hlsSource: HlsFallbackSource) {
        val previousSubtitleId = currentSubtitleTrack?.id
        _availableAudioTracks.removeAll { ExternalHlsFallbackSupport.isExternalHlsAudioTrackId(it.id) }
        _availableAudioTracks.addAll(hlsSource.audioTracks)
        currentAudioTrack =
            hlsSource.selectedAudioStreamIndex
                ?.let { streamIndex ->
                    hlsSource.audioTracks.firstOrNull {
                        ExternalHlsFallbackSupport.externalHlsTrackStreamIndex(it.id) == streamIndex
                    }
                }
                ?: hlsSource.audioTracks.firstOrNull { it.isDefault }
                ?: hlsSource.audioTracks.firstOrNull()

        _availableSubtitleTracks.removeAll { ExternalHlsFallbackSupport.isExternalHlsSubtitleTrackId(it.id) }
        _availableSubtitleTracks.addAll(hlsSource.subtitleTracks)
        val selectedSubtitleTrack =
            hlsSource.selectedSubtitleStreamIndex
                ?.let { streamIndex ->
                    hlsSource.subtitleTracks.firstOrNull {
                        ExternalHlsFallbackSupport.externalHlsTrackStreamIndex(it.id) == streamIndex
                    }
                }
                ?: previousSubtitleId
                    ?.takeIf(ExternalHlsFallbackSupport::isExternalHlsSubtitleTrackId)
                    ?.let { previousId -> hlsSource.subtitleTracks.firstOrNull { it.id == previousId } }

        if (selectedSubtitleTrack != null) {
            currentSubtitleTrack = selectedSubtitleTrack
            subtitlesEnabled = true
        } else if (previousSubtitleId?.let(ExternalHlsFallbackSupport::isExternalHlsSubtitleTrackId) == true) {
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
        scope.launch {
            launch { produceFrames() }
            launch { consumeFrames() }
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
        while (scope.isActive && _hasMedia && !isDisposing.get()) {
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
                        onRestart?.invoke()
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
                    onPlaybackEnded?.invoke()
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
                    if (scope.isActive && _hasMedia && !isDisposing.get()) {
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
            val newHash = calculateFrameHash(srcBuffer, pixelCount)
            if (newHash == lastFrameHash) {
                return ProduceOutcome.SkipIteration
            }
            lastFrameHash = newHash

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

            val targetBitmap = skiaBitmaps[nextBitmapIndex]!!
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

        while (scope.isActive && _hasMedia && !isDisposing.get()) {
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
                if (scope.isActive && _hasMedia && !isDisposing.get()) {
                    setError("Error while processing a frame: ${e.message}")
                }
                delay(100.milliseconds)
            }
        }
    }

    /**
     * Starts or resumes playback.
     * If media is not loaded yet (openUri in progress), waits for it to finish
     * instead of triggering a second open which would race with the first.
     */
    override fun play() {
        if (isDisposing.get()) return

        if (readyForPlayback()) {
            // Fast path: media is loaded, just resume
            executeMediaOperation(operation = "play") {
                resumePlayback()
            }
            return
        }

        // Slow path: wait for any in-progress openUri to complete, then resume
        scope.launch {
            try {
                withTimeout(10_000) { initReady.await() }
                // Wait for _hasMedia to become true (set by openUriInternal)
                withTimeout(10_000) {
                    snapshotFlow { _hasMedia }.filter { it }.first()
                }
            } catch (_: Exception) {
                // Timeout or cancellation — if we still have a URI, try a fresh open
                if (!_hasMedia) {
                    lastUri?.takeIf { it.isNotEmpty() }?.let { uri ->
                        openUriInternal(uri, InitialPlayerState.PLAY, lastRequestHeaders)
                    }
                }
                return@launch
            }

            // Media is loaded — resume playback
            mediaOperationMutex.withLock {
                if (!isDisposing.get()) resumePlayback()
            }
        }
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
        if (isDisposing.get()) return

        executeMediaOperation(
            operation = "pause",
            precondition = _isPlaying,
        ) {
            userPaused = true
            // Reset initialFrameRead flag when switching to pause state
            // This ensures that we'll read a new initial frame to display
            initialFrameRead.set(false)

            setPlaybackState(false, "Error while pausing playback")
        }
    }

    /**
     * Stops playback and releases media resources
     * This will close the media file but keep the player instance
     */
    override fun stop() {
        if (isDisposing.get()) return

        executeMediaOperation(
            operation = "stop",
        ) {
            setPlaybackState(false, "Error while stopping playback", true)
            delay(50.milliseconds)
            videoJob?.cancelAndJoin()
            releaseAllResources()
            _hasMedia = false
            _progress = 0f
            _currentTime = Duration.ZERO
            _duration = Duration.ZERO
            isLoading = false
            errorMessage = null
            _error = null
            userPaused = false

            // Reset initialFrameRead flag to ensure we read a new frame when playing again
            initialFrameRead.set(false)

            videoPlayerInstance.takeIf { it != 0L }?.let { instance ->
                nativeCloseMedia(instance)
            }
            closeExternalHlsFallback()
            clearExternalHlsFallbackTrackState()
            clearLibVlcTrackState()
        }
    }

    override fun seekTo(time: Duration) {
        if (_duration <= Duration.ZERO) return
        val progress =
            (time.inWholeMilliseconds.toDouble() / _duration.inWholeMilliseconds.toDouble())
                .toFloat()
                .coerceIn(0f, 1f)
        seekToProgress(progress)
    }

    override fun seekToProgress(progress: Float) {
        seekTo(progress.coerceIn(0f, 1f) * VideoPlayerState.SLIDER_SCALE)
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun seekTo(value: Float) {
        if (isDisposing.get()) return
        if (_duration <= Duration.ZERO) return // Live stream — seeking not supported

        val clamped = value.coerceIn(0f, VideoPlayerState.SLIDER_SCALE)
        val targetPos = (_duration * (clamped / VideoPlayerState.SLIDER_SCALE).toDouble()).inWhole100NanosecondTicks()

        // Latch the newest target; whoever is running the seek loop will see it.
        pendingSeekTarget.set(targetPos)

        // Optimistic UI so the slider tracks the drag smoothly even while the
        // native seek is still settling.
        _progress = (clamped / VideoPlayerState.SLIDER_SCALE).coerceIn(0f, 1f)
        _currentTime = _duration * _progress.toDouble()

        scheduleSeek()
    }

    /**
     * Launches the seek loop if no other loop is currently draining the target.
     * If multiple `seekTo` calls arrive in quick succession, only the latest
     * target is actually processed — intermediate values are coalesced.
     */
    private fun scheduleSeek() {
        if (!seekInFlight.compareAndSet(false, true)) return

        scope.launch {
            try {
                while (true) {
                    val target = pendingSeekTarget.getAndSet(Long.MIN_VALUE)
                    if (target == Long.MIN_VALUE) break
                    performSeek(target)
                }
            } finally {
                seekInFlight.set(false)
                // Tiny race: a caller may have enqueued a target between our
                // last getAndSet and releasing the claim. Re-check & re-launch.
                if (pendingSeekTarget.get() != Long.MIN_VALUE) scheduleSeek()
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
    private suspend fun performSeek(targetPos: Long) {
        val loadingTrigger =
            scope.launch {
                delay(200.milliseconds)
                if (!isDisposing.get()) isLoading = true
            }

        try {
            mediaOperationMutex.withLock {
                if (isDisposing.get()) return@withLock
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
                            hr = nativeSeekMedia(instance, nativeTargetPos)
                        }
                        if (hr < 0) {
                            setError("Seek failed (hr=0x${hr.toString(16)})")
                            return@withLock
                        }

                        val posArr = LongArray(1)
                        if (nativeGetMediaPosition(instance, posArr) >= 0) {
                            _currentTime = adjustedExternalHlsPosition(posArr[0].hundredNanosecondsAsDuration())
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
                } finally {
                    seekInProgress.set(false)
                }

                // If the producer was never started (e.g. stop() was called
                // before the first play), start it now so the new frame shows.
                if (!isDisposing.get() && (videoJob == null || videoJob?.isActive == false)) {
                    videoJob = startVideoPipeline()
                }
            }
        } finally {
            loadingTrigger.cancel()
            isLoading = false
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
        if (isDisposing.get()) return

        if (width <= 0 || height <= 0) return

        if (width == surfaceWidth && height == surfaceHeight) return

        surfaceWidth = width
        surfaceHeight = height

        // Mark resizing in progress and debounce rapid events
        isResizing.set(true)
        resizeJob?.cancel()
        resizeJob =
            scope.launch {
                delay(120.milliseconds)
                try {
                    applyOutputScaling()
                } finally {
                    isResizing.set(false)
                }
            }
    }

    /**
     * Asks Media Foundation to produce frames at the display surface size
     * instead of full native resolution. Saves significant memory for 4K+ video.
     */
    private suspend fun applyOutputScaling() {
        if (isDisposing.get() || !_hasMedia) return
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
        while (scope.isActive && _hasMedia && !isDisposing.get()) {
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
        initReady.isCompleted && videoPlayerInstance != 0L && _hasMedia && !isDisposing.get()

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
        if (!precondition || isDisposing.get()) return

        scope.launch {
            mediaOperationMutex.withLock {
                try {
                    if (!isDisposing.get()) {
                        block()
                    }
                } catch (e: Exception) {
                    setError("Error during $operation: ${e.message}")
                }
            }
        }
    }

    override fun selectSubtitleTrack(track: SubtitleTrack?) {
        if (track == null && libVlcBackendActive) {
            scope.launch {
                disableLibVlcSubtitles()
            }
            return
        }

        val selectedLibVlcStreamIndex =
            track
                ?.id
                ?.takeIf(::isLibVlcSubtitleTrackId)
                ?.let(::libVlcTrackStreamIndex)

        if (track != null && selectedLibVlcStreamIndex != null) {
            scope.launch {
                selectLibVlcSubtitleTrack(track, selectedLibVlcStreamIndex)
            }
            return
        }

        val selectedStreamIndex =
            track
                ?.id
                ?.takeIf(ExternalHlsFallbackSupport::isExternalHlsSubtitleTrackId)
                ?.let(ExternalHlsFallbackSupport::externalHlsTrackStreamIndex)

        if (track != null && selectedStreamIndex != null) {
            scope.launch {
                switchExternalHlsSubtitleTrack(track, selectedStreamIndex)
            }
            return
        }

        scope.launch {
            withContext(Dispatchers.Main) {
                currentSubtitleTrack = track
                subtitlesEnabled = track != null
            }
        }
    }

    override fun selectAudioTrack(track: AudioTrack?) {
        val selectedLibVlcStreamIndex =
            track
                ?.id
                ?.takeIf(::isLibVlcAudioTrackId)
                ?.let(::libVlcTrackStreamIndex)

        if (track != null && selectedLibVlcStreamIndex != null) {
            scope.launch {
                selectLibVlcAudioTrack(track, selectedLibVlcStreamIndex)
            }
            return
        }

        val selectedStreamIndex =
            track
                ?.id
                ?.takeIf(ExternalHlsFallbackSupport::isExternalHlsAudioTrackId)
                ?.let(ExternalHlsFallbackSupport::externalHlsTrackStreamIndex)

        if (track != null && selectedStreamIndex != null) {
            scope.launch {
                switchExternalHlsAudioTrack(track, selectedStreamIndex)
            }
            return
        }

        scope.launch {
            withContext(Dispatchers.Main) {
                currentAudioTrack = track
            }
        }
    }

    override fun addSubtitleTrack(track: SubtitleTrack) {
        val externalTrack = track.copy(isEmbedded = false)
        _availableSubtitleTracks.removeAll { it.id == externalTrack.id }
        _availableSubtitleTracks.add(externalTrack)
    }

    override fun removeSubtitleTrack(trackId: String) {
        val selectedTrack = currentSubtitleTrack
        _availableSubtitleTracks.removeAll { it.id == trackId && it.isExternal }
        if (selectedTrack?.id == trackId && selectedTrack.isExternal) {
            disableSubtitles()
        }
    }

    override fun clearExternalSubtitleTracks() {
        val selectedTrack = currentSubtitleTrack
        _availableSubtitleTracks.removeAll { it.isExternal }
        if (selectedTrack?.isExternal == true) {
            disableSubtitles()
        }
    }

    override fun disableSubtitles() {
        val selectedTrack = currentSubtitleTrack
        subtitlesEnabled = false
        currentSubtitleTrack = null
        if (libVlcBackendActive && selectedTrack?.id?.let(::isLibVlcSubtitleTrackId) == true) {
            scope.launch {
                disableLibVlcSubtitles()
            }
            return
        }

        if (externalHlsSourceUri != null && externalHlsSelectedSubtitleStreamIndex != null) {
            scope.launch {
                switchExternalHlsSubtitleTrack(track = null, streamIndex = null)
            }
            return
        }
    }

    private suspend fun selectLibVlcAudioTrack(
        track: AudioTrack,
        streamIndex: Int,
    ) {
        libVlcSelectedAudioStreamIndex = streamIndex
        currentAudioTrack = track

        val ordinal =
            libVlcTrackInfo
                ?.audioStreams
                ?.firstOrNull { it.streamIndex == streamIndex }
                ?.ordinal
                ?: return
        val instance = videoPlayerInstance
        if (instance != 0L) {
            WindowsNativeBridge.nSelectLibVlcAudioTrack(instance, ordinal)
        }
    }

    private suspend fun selectLibVlcSubtitleTrack(
        track: SubtitleTrack,
        streamIndex: Int,
    ) {
        libVlcSelectedSubtitleStreamIndex = streamIndex
        currentSubtitleTrack = track
        subtitlesEnabled = true

        val ordinal =
            libVlcTrackInfo
                ?.subtitleStreams
                ?.firstOrNull { it.streamIndex == streamIndex }
                ?.ordinal
                ?: return
        val instance = videoPlayerInstance
        if (instance != 0L) {
            WindowsNativeBridge.nSelectLibVlcSubtitleTrack(instance, ordinal)
        }
    }

    private suspend fun disableLibVlcSubtitles() {
        libVlcSelectedSubtitleStreamIndex = null
        val instance = videoPlayerInstance
        if (instance != 0L) {
            WindowsNativeBridge.nDisableLibVlcSubtitles(instance)
        }
        subtitlesEnabled = false
        currentSubtitleTrack = null
    }

    private suspend fun switchExternalHlsAudioTrack(
        track: AudioTrack,
        streamIndex: Int,
    ) {
        val sourceUri = externalHlsSourceUri
        if (sourceUri == null) {
            currentAudioTrack = track
            return
        }
        if (externalHlsSelectedAudioStreamIndex == streamIndex) {
            currentAudioTrack = track
            return
        }

        restartExternalHlsPlayback(
            sourceUri = sourceUri,
            selectedAudioStreamIndex = streamIndex,
            selectedSubtitleStreamIndex = externalHlsSelectedSubtitleStreamIndex,
            onBeforeRestart = {
                currentAudioTrack = track
            },
            failureMessage = "Failed to switch audio track",
        )
    }

    private suspend fun switchExternalHlsSubtitleTrack(
        track: SubtitleTrack?,
        streamIndex: Int?,
    ) {
        val sourceUri = externalHlsSourceUri
        if (sourceUri == null) {
            currentSubtitleTrack = track
            subtitlesEnabled = track != null
            return
        }
        if (externalHlsSelectedSubtitleStreamIndex == streamIndex) {
            currentSubtitleTrack = track
            subtitlesEnabled = track != null
            return
        }
        if (track != null && !ExternalHlsFallbackSupport.hasSubtitleRenderer()) {
            isLoading = false
            currentSubtitleTrack = null
            subtitlesEnabled = false
            _error =
                VideoPlayerError.CodecError(
                    "Full embedded subtitle rendering through the external HLS fallback requires VLC " +
                        "or ffmpeg with libass and the subtitles filter enabled.",
                )
            return
        }

        restartExternalHlsPlayback(
            sourceUri = sourceUri,
            selectedAudioStreamIndex = externalHlsSelectedAudioStreamIndex,
            selectedSubtitleStreamIndex = streamIndex,
            onBeforeRestart = {
                currentSubtitleTrack = track
                subtitlesEnabled = track != null
            },
            failureMessage = "Failed to switch subtitle track",
        )
    }

    private suspend fun restartExternalHlsPlayback(
        sourceUri: String,
        selectedAudioStreamIndex: Int?,
        selectedSubtitleStreamIndex: Int?,
        onBeforeRestart: () -> Unit,
        failureMessage: String,
    ) {
        val shouldResumePlayback = _isPlaying
        val restartPosition = _currentTime
        val duration = _duration
        _progress =
            if (duration > Duration.ZERO) {
                (restartPosition.toSecondsDouble() / duration.toSecondsDouble()).toFloat().coerceIn(0f, 1f)
            } else {
                _progress
            }
        isLoading = true
        _error = null
        errorMessage = null
        onBeforeRestart()

        externalHlsSelectedAudioStreamIndex = selectedAudioStreamIndex
        externalHlsSelectedSubtitleStreamIndex = selectedSubtitleStreamIndex
        externalHlsPlaybackOffsetSeconds = restartPosition.toSecondsDouble()

        openUriInternal(
            uri = sourceUri,
            initializePlayerState = if (shouldResumePlayback) InitialPlayerState.PLAY else InitialPlayerState.PAUSE,
            requestHeaders = lastRequestHeaders,
        )

        if (isDisposing.get()) {
            _error = VideoPlayerError.SourceError(failureMessage)
        }
    }

    /**
     * Toggles the fullscreen state of the video player
     */
    override fun toggleFullscreen() {
        isFullscreen = !isFullscreen
    }
}
