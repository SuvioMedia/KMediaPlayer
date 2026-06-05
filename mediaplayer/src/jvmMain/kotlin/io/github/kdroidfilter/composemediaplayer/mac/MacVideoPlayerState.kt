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
import io.github.kdroidfilter.composemediaplayer.EXTERNAL_FFMPEG_AUDIO_TRACK_ID_PREFIX
import io.github.kdroidfilter.composemediaplayer.EXTERNAL_FFMPEG_SUBTITLE_TRACK_ID_PREFIX
import io.github.kdroidfilter.composemediaplayer.EXTERNAL_VLC_AUDIO_TRACK_ID_PREFIX
import io.github.kdroidfilter.composemediaplayer.EXTERNAL_VLC_SUBTITLE_TRACK_ID_PREFIX
import io.github.kdroidfilter.composemediaplayer.ExternalFfmpegHlsFallback
import io.github.kdroidfilter.composemediaplayer.ExternalFfmpegLocator
import io.github.kdroidfilter.composemediaplayer.ExternalHlsFallbackBackend
import io.github.kdroidfilter.composemediaplayer.ExternalVlcHlsFallback
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
import io.github.kdroidfilter.composemediaplayer.VideoOutputMode
import io.github.kdroidfilter.composemediaplayer.VideoPlaybackOptions
import io.github.kdroidfilter.composemediaplayer.VideoPlayerError
import io.github.kdroidfilter.composemediaplayer.VideoPlayerState
import io.github.kdroidfilter.composemediaplayer.VideoRenderingInfo
import io.github.kdroidfilter.composemediaplayer.platformPlayerCapabilities
import io.github.kdroidfilter.composemediaplayer.requestHeadersJsonObjectString
import io.github.kdroidfilter.composemediaplayer.requestHeadersLineString
import io.github.kdroidfilter.composemediaplayer.sanitizedRequestHeaders
import io.github.kdroidfilter.composemediaplayer.subtitle.loadSubtitleContent
import io.github.kdroidfilter.composemediaplayer.util.TaggedLogger
import io.github.kdroidfilter.composemediaplayer.util.formatTime
import io.github.kdroidfilter.composemediaplayer.util.secondsAsDuration
import io.github.vinceglb.filekit.PlatformFile
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.debounce
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import java.awt.Component
import java.io.Closeable
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

internal val macLogger = TaggedLogger("MacVideoPlayerState")

private enum class MacLibVlcRenderMode {
    MEMORY,
}

private fun isMacHdrMetalPocEnabled(): Boolean =
    listOf(
        System.getProperty("composemediaplayer.macos.hdrMetal"),
        System.getenv("COMPOSE_MEDIA_PLAYER_MACOS_HDR_METAL"),
    ).any { value ->
        value.equals("true", ignoreCase = true) || value == "1"
    }

private data class MacResolvedLibVlcBackend(
    val installation: JvmLibVlcInstallation,
    val renderMode: MacLibVlcRenderMode,
)

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
    playbackOptions: VideoPlaybackOptions = VideoPlaybackOptions(),
) : VideoPlayerState {
    // Main state variables
    // AtomicLong allows lock-free reads of the native pointer from the frame hot path
    private val playerPtrAtomic = AtomicLong(0L)
    private val playerPtr: Long get() = playerPtrAtomic.get()

    // Serial dispatcher for frame processing — ensures only one frame is processed at a time
    private val frameDispatcher = Dispatchers.Default.limitedParallelism(1)
    private val _currentFrameState = MutableStateFlow<ImageBitmap?>(null)
    internal val currentFrameState: State<ImageBitmap?> = mutableStateOf(null)
    private var skiaBitmapWidth: Int = 0
    private var skiaBitmapHeight: Int = 0
    private var skiaBitmapA: Bitmap? = null
    private var skiaBitmapB: Bitmap? = null
    private var nextSkiaBitmapA: Boolean = true

    // Surface display size (pixels) — used to scale native output resolution
    private var surfaceWidth = 0
    private var surfaceHeight = 0
    private val isResizing = AtomicBoolean(false)
    private var resizeJob: Job? = null

    // Background worker threads and jobs
    private val ioScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var playerScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var frameUpdateJob: Job? = null
    private var positionUpdateJob: Job? = null
    private var bufferingCheckJob: Job? = null
    private var uiUpdateJob: Job? = null
    private var ffmpegHlsFallback: Closeable? = null
    private var ffmpegHlsFallbackDurationSeconds: Double? = null
    private var ffmpegHlsSourceUri: String? = null
    private var ffmpegHlsSelectedAudioStreamIndex: Int? = null
    private var ffmpegHlsSelectedSubtitleStreamIndex: Int? = null
    private var ffmpegHlsPlaybackOffsetSeconds: Double = 0.0
    private var libVlcBackendActive: Boolean = false
    private var libVlcSourceUri: String? = null
    private var libVlcTrackInfo: JvmLibVlcTrackInfo? = null
    private var libVlcSelectedAudioStreamIndex: Int? = null
    private var libVlcSelectedSubtitleStreamIndex: Int? = null
    private var libVlcRenderMode: MacLibVlcRenderMode? = null
    private var nativeBackendUsesLibVlc: Boolean = false
    private val libAssLock = Any()
    private val libAssSelectionToken = AtomicLong(0L)
    private var libAssRendererHandle: Long = 0L
    private var libAssSubtitleKey: String? = null
    private var libAssSubtitleSource: String? = null
    private val videoOutputMode: VideoOutputMode =
        if (isMacHdrMetalPocEnabled()) {
            VideoOutputMode.NATIVE_HDR
        } else {
            playbackOptions.videoOutputMode
        }
    private val hdrMetalRequested: Boolean = videoOutputMode == VideoOutputMode.NATIVE_HDR
    private val hdrToneMappingRequested: Boolean =
        videoOutputMode == VideoOutputMode.AUTO ||
            videoOutputMode == VideoOutputMode.TONE_MAPPED_SDR
    private var hdrMetalSurfaceAllowed: Boolean = false

    // State tracking
    private var lastFrameUpdateTime: Long = 0
    private var seekInProgress = false
    private var targetSeekTime: Double? = null
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
    override val availableSubtitleTracks: MutableList<SubtitleTrack> = mutableStateListOf()
    override var currentAudioTrack: AudioTrack? by mutableStateOf(null)
    override val availableAudioTracks: MutableList<AudioTrack> = mutableStateListOf()
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
        get() = platformPlayerCapabilities()
    override val renderingInfo: VideoRenderingInfo = VideoRenderingInfo()
    override var isFullscreen: Boolean by mutableStateOf(false)
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
    override val currentTime: Duration get() = _currentTime.value
    override val preciseCurrentTime: Duration get() = _currentTime.value
    override val duration: Duration get() = _duration.value

    // Non-blocking aspect ratio property
    private val _aspectRatio = mutableStateOf(16f / 9f)
    override val aspectRatio: Float get() = _aspectRatio.value

    // Player settings
    // Volume variable is stored independently so it can always be modified.
    private val _volumeState = mutableStateOf(1.0f)
    override var volume: Float
        get() = _volumeState.value
        set(value) {
            val newValue = value.coerceIn(0f, 1f)
            if (_volumeState.value != newValue) {
                _volumeState.value = newValue
                // Launch a coroutine to apply the volume if the native player is available.
                ioScope.launch {
                    applyVolume()
                }
            }
        }

    // Playback speed control
    private val _playbackSpeedState = mutableStateOf(1.0f)
    override var playbackSpeed: Float
        get() = _playbackSpeedState.value
        set(value) {
            val newValue = value.coerceIn(VideoPlayerState.MIN_PLAYBACK_SPEED, VideoPlayerState.MAX_PLAYBACK_SPEED)
            if (_playbackSpeedState.value != newValue) {
                _playbackSpeedState.value = newValue
                // Launch a coroutine to apply the playback speed if the native player is available.
                ioScope.launch {
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
        ioScope.launch {
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
                _currentFrameState.debounce(1).collect { newFrame ->
                    ensureActive() // Checks that the coroutine is still active
                    withContext(Dispatchers.Main) {
                        (currentFrameState as MutableState).value = newFrame
                    }
                }
            }
    }

    /** Initializes the native video player on the IO thread. */
    private suspend fun initPlayer() =
        ioScope
            .launch {
                macLogger.d { "initPlayer() - Creating native player" }
                try {
                    val ptr = MacNativeBridge.nCreatePlayer()
                    if (ptr != 0L) {
                        if (!playerPtrAtomic.compareAndSet(0L, ptr)) {
                            MacNativeBridge.nDisposePlayer(ptr)
                            return@launch
                        }
                        nativeBackendUsesLibVlc = false
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
            }.join()

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

    private fun normalizeLocalFileUriForPlayback(uri: String): String =
        when {
            uri.startsWith("file://localhost/") -> "/" + uri.removePrefix("file://localhost/")
            uri.startsWith("file://") -> uri.removePrefix("file://")
            uri.startsWith("file:") -> uri.removePrefix("file:")
            else -> uri
        }

    override fun openUri(
        uri: String,
        initializeplayerState: InitialPlayerState,
        requestHeaders: Map<String, String>,
    ) {
        macLogger.d { "openUri() - Opening URI: $uri, initializeplayerState: $initializeplayerState" }

        val sanitizedHeaders = requestHeaders.sanitizedRequestHeaders()
        lastUri = uri
        lastRequestHeaders = sanitizedHeaders

        // Check if this is a local file that doesn't exist
        if (!checkExistsIfLocalFile(uri)) {
            macLogger.e { "File does not exist: $uri" }
            setPlayerError(VideoPlayerError.SourceError("File not found: $uri"))
            return
        }

        // Update UI state first
        ioScope.launch {
            withContext(Dispatchers.Main) {
                isLoading = true
                error = null // Clear any previous errors only if we got this far
                playbackSpeed = 1.0f
            }

            // Ensure heavy operations are performed in the background
            try {
                // Stop and clean up any existing playback
                if (hasMedia || ffmpegHlsFallback != null) {
                    cleanupCurrentPlayback()
                }

                clearFfmpegFallbackTrackState()
                ffmpegHlsSourceUri = null
                ffmpegHlsSelectedAudioStreamIndex = null
                ffmpegHlsSelectedSubtitleStreamIndex = null
                ffmpegHlsPlaybackOffsetSeconds = 0.0
                clearLibVlcTrackState()

                // Ensure player is initialized in the background
                val libVlcBackend = resolveLibVlcBackendForUri(uri)
                ensurePlayerInitialized(libVlcBackend)

                val playableUri =
                    if (libVlcBackend != null) {
                        prepareUriForLibVlcPlayback(uri, sanitizedHeaders, libVlcBackend.renderMode)
                    } else {
                        prepareUriForMacPlayback(uri, sanitizedHeaders)
                    }

                // Open URI on IO thread and capture result
                val result = openMediaUri(playableUri, sanitizedHeaders)

                if (result) {
                    // Launch parallel background tasks
                    coroutineScope {
                        launch { updateFrameRateInfo() }
                        launch { updateMetadata() }
                    }
                    if (libVlcBackend != null) {
                        refreshLibVlcRuntimeTracksIfNeeded()
                    }
                    applyLibVlcSelectedTracks()

                    // Scale output to match display surface if size is already known
                    if (surfaceWidth > 0 && surfaceHeight > 0) {
                        applyOutputScaling()
                    }

                    // Update UI state on main thread
                    withContext(Dispatchers.Main) {
                        hasMedia = true
                        isLoading = false
                        // Set isPlaying based on the initializeplayerState parameter
                        isPlaying = initializeplayerState == InitialPlayerState.PLAY
                    }

                    // Start background processes for frame updates
                    if (shouldUseHdrMetalSurface()) {
                        stopFrameUpdates()
                    } else {
                        startFrameUpdates()
                    }
                    startPositionUpdates()

                    // First frame update in the background
                    if (!shouldUseHdrMetalSurface()) {
                        updateFrameAsync()
                    }

                    // Start buffering check in the background
                    startBufferingCheck()

                    // Start playback if needed - in the background
                    if (isPlaying) {
                        playInBackground()
                    } else if (libVlcBackendActive) {
                        pauseInBackground()
                    }
                } else {
                    macLogger.e { "Failed to open URI" }
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
                closeFfmpegHlsFallback()
                clearFfmpegFallbackTrackState()
                clearLibVlcTrackState()
                handleError(e)
            }
        }
    }

    override fun openFile(
        file: PlatformFile,
        initializeplayerState: InitialPlayerState,
    ) {
        openUri(file.file.path, initializeplayerState)
    }

    /** Cleans up current playback state. */
    private suspend fun cleanupCurrentPlayback() {
        macLogger.d { "cleanupCurrentPlayback() - Cleaning up current playback" }
        pauseInBackground()
        stopFrameUpdates()
        stopPositionUpdates()
        stopBufferingCheck()

        val ptrToDispose =
            withContext(frameDispatcher) {
                playerPtrAtomic.getAndSet(0L)
            }

        // Release resources outside of the mutex lock
        if (ptrToDispose != 0L) {
            try {
                MacNativeBridge.nDisposePlayer(ptrToDispose)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                macLogger.e { "Error disposing player: ${e.message}" }
            }
        }

        nativeBackendUsesLibVlc = false
        hdrMetalSurfaceAllowed = false
        setNativeHdrToneMappingEnabled(false)
        clearLibAssSubtitleRenderer()
        closeFfmpegHlsFallback()
        clearLibVlcTrackState()
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

    internal fun shouldUseHdrMetalSurface(): Boolean =
        hdrMetalRequested &&
            hdrMetalSurfaceAllowed &&
            !nativeBackendUsesLibVlc &&
            !usesLibAssSubtitleOverlay

    internal fun attachHdrMetalComponent(
        component: Component,
        contentScaleMode: Int,
    ): Boolean {
        if (!shouldUseHdrMetalSurface()) return false
        val ptr = playerPtr
        if (ptr == 0L || !MacNativeBridge.nIsHdrMetalAvailable(ptr)) return false

        return runCatching {
            MacNativeBridge.nAttachHdrMetalView(ptr, component).also { attached ->
                if (attached) {
                    MacNativeBridge.nSetHdrMetalContentScaleMode(ptr, contentScaleMode)
                }
            }
        }.getOrElse { e ->
            macLogger.e { "Failed to attach HDR Metal surface: ${e.message}" }
            false
        }
    }

    internal fun detachHdrMetalComponent(component: Component) {
        val ptr = playerPtr
        if (ptr == 0L) return
        runCatching {
            MacNativeBridge.nDetachHdrMetalView(ptr, component)
        }.onFailure { e ->
            macLogger.e { "Failed to detach HDR Metal surface: ${e.message}" }
        }
    }

    private suspend fun prepareUriForMacPlayback(
        uri: String,
        requestHeaders: Map<String, String> = emptyMap(),
    ): String {
        if (!JvmExternalFallbackContainerSupport.needsContainerFallback(uri, requestHeaders)) {
            hdrMetalSurfaceAllowed = hdrMetalRequested
            setNativeHdrMetalPreferred(hdrMetalSurfaceAllowed)
            setNativeHdrToneMappingEnabled(hdrToneMappingRequested && !hdrMetalSurfaceAllowed)
            withContext(Dispatchers.Main) {
                renderingInfo.update(
                    backend = "AVFoundation",
                    container = "AVFoundation-supported source",
                    videoDecoder = "AVFoundation",
                    videoRenderer =
                        when {
                            hdrMetalRequested -> "AVPlayerLayer native HDR/EDR"
                            hdrToneMappingRequested -> "AVPlayerItemVideoOutput tone-mapped BT.709 -> Compose Canvas (Skia)"
                            else -> "CVPixelBuffer -> Compose Canvas (Skia)"
                        },
                    audioRenderer = "AVFoundation / CoreAudio",
                    subtitleRenderer = null,
                    subtitleSource = null,
                    notes =
                        when {
                            hdrMetalRequested ->
                                "Native macOS HDR path; Compose overlay is rendered in a separate transparent window."
                            hdrToneMappingRequested ->
                                "HDR sources are tone-mapped to SDR for stable Compose rendering."
                            else -> "No external GPL components are bundled or linked."
                        },
                )
            }
            return normalizeLocalFileUriForPlayback(uri)
        }

        hdrMetalSurfaceAllowed = false
        setNativeHdrMetalPreferred(false)
        setNativeHdrToneMappingEnabled(false)
        if (isFfmpegFallbackDisabled()) {
            val disabledFallbackMessage =
                "Matroska/WebM is not supported by AVPlayer on macOS. " +
                    "Enable the optional external ffmpeg fallback with " +
                    "composemediaplayer.hlsFallback=true or COMPOSE_MEDIA_PLAYER_HLS_FALLBACK=true."
            throw UnsupportedOperationException(disabledFallbackMessage)
        }
        val backend = selectHlsFallbackBackend(requiresSubtitleRendering = ffmpegHlsSelectedSubtitleStreamIndex != null)
        val fallback =
            when (backend) {
                ExternalHlsFallbackBackend.VLC -> {
                    val vlcPath =
                        ExternalVlcLocator.findVlc()
                            ?: throw UnsupportedOperationException(
                                "Matroska/WebM fallback backend is set to VLC, but VLC was not found. " +
                                    "Install VLC or set composemediaplayer.vlc=/path/to/vlc " +
                                    "or COMPOSE_MEDIA_PLAYER_VLC. " +
                                    "ComposeMediaPlayer does not bundle or link VLC.",
                            )
                    macLogger.d { "Using external VLC fallback for AVPlayer unsupported container: $vlcPath" }
                    ExternalVlcHlsFallback(vlcPath)
                }
                ExternalHlsFallbackBackend.FFMPEG -> {
                    val requiresSubtitleFilter = ffmpegHlsSelectedSubtitleStreamIndex != null
                    val ffmpegPath =
                        if (requiresSubtitleFilter) {
                            ExternalFfmpegLocator.findFfmpegWithSubtitles()
                        } else {
                            ExternalFfmpegLocator.findFfmpeg()
                        }
                            ?: throw UnsupportedOperationException(
                                if (requiresSubtitleFilter) {
                                    "Embedded subtitle rendering through the external HLS fallback requires " +
                                        "an ffmpeg build " +
                                        "with libass and the subtitles filter enabled, or VLC fallback. " +
                                        "Install ffmpeg-full/VLC or set " +
                                        "composemediaplayer.ffmpeg=/path/to/ffmpeg " +
                                        "or COMPOSE_MEDIA_PLAYER_FFMPEG. " +
                                        "ComposeMediaPlayer does not bundle or link ffmpeg or VLC."
                                } else {
                                    "Matroska/WebM is not supported by AVPlayer on macOS. Install ffmpeg or VLC, " +
                                        "or set composemediaplayer.ffmpeg=/path/to/ffmpeg " +
                                        "or COMPOSE_MEDIA_PLAYER_FFMPEG. " +
                                        "ComposeMediaPlayer does not bundle or link ffmpeg or VLC."
                                },
                            )
                    macLogger.d { "Using external ffmpeg fallback for AVPlayer unsupported container: $ffmpegPath" }
                    ExternalFfmpegHlsFallback(ffmpegPath)
                }
            }
        return try {
            val hlsSource =
                when (fallback) {
                    is ExternalVlcHlsFallback ->
                        fallback.start(
                            uri = uri,
                            requestHeaders = requestHeaders,
                            selectedAudioStreamIndex = ffmpegHlsSelectedAudioStreamIndex,
                            selectedSubtitleStreamIndex = ffmpegHlsSelectedSubtitleStreamIndex,
                            startTimeSeconds = ffmpegHlsPlaybackOffsetSeconds,
                        )
                    is ExternalFfmpegHlsFallback ->
                        fallback.start(
                            uri = uri,
                            requestHeaders = requestHeaders,
                            selectedAudioStreamIndex = ffmpegHlsSelectedAudioStreamIndex,
                            selectedSubtitleStreamIndex = ffmpegHlsSelectedSubtitleStreamIndex,
                            startTimeSeconds = ffmpegHlsPlaybackOffsetSeconds,
                        )
                    else -> error("Unsupported external HLS fallback backend")
                }
            ffmpegHlsFallback = fallback
            ffmpegHlsFallbackDurationSeconds = hlsSource.durationSeconds
            ffmpegHlsSourceUri = uri
            ffmpegHlsSelectedAudioStreamIndex = hlsSource.selectedAudioStreamIndex
            ffmpegHlsSelectedSubtitleStreamIndex = hlsSource.selectedSubtitleStreamIndex
            ffmpegHlsPlaybackOffsetSeconds = hlsSource.playbackOffsetSeconds
            updateFfmpegFallbackTracks(hlsSource)
            withContext(Dispatchers.Main) {
                renderingInfo.update(
                    backend = "${backend.displayName} HLS fallback",
                    container = "Matroska/WebM remuxed by external ${backend.displayName}",
                    videoDecoder = "AVFoundation from generated HLS",
                    videoRenderer = "CVPixelBuffer -> Compose Canvas (Skia)",
                    audioRenderer = "AVFoundation / CoreAudio",
                    subtitleRenderer =
                        if (hlsSource.selectedSubtitleStreamIndex != null) {
                            "burned into HLS by external ${backend.displayName}"
                        } else {
                            null
                        },
                    subtitleSource =
                        hlsSource.selectedSubtitleStreamIndex?.let { "embedded stream $it" },
                    notes = "External ${backend.displayName} process only; not bundled or linked.",
                )
            }
            hlsSource.playlistUrl
        } catch (e: Exception) {
            fallback.close()
            throw UnsupportedOperationException(
                "Failed to prepare Matroska/WebM through external ${backend.displayName} fallback: ${e.message}",
                e,
            )
        }
    }

    private suspend fun prepareUriForLibVlcPlayback(
        uri: String,
        requestHeaders: Map<String, String> = emptyMap(),
        renderMode: MacLibVlcRenderMode,
    ): String {
        libVlcBackendActive = true
        hdrMetalSurfaceAllowed = false
        setNativeHdrMetalPreferred(false)
        setNativeHdrToneMappingEnabled(false)
        libVlcSourceUri = uri
        libVlcRenderMode = renderMode
        withContext(Dispatchers.Main) {
            renderingInfo.update(
                backend = "libVLC memory backend",
                container = "Matroska/WebM through user-installed libVLC",
                videoDecoder = "libVLC",
                videoRenderer = "libVLC vmem -> Compose Canvas (Skia)",
                audioRenderer = "libVLC / AUHAL",
                subtitleRenderer = null,
                subtitleSource = null,
                notes = "VLC is loaded dynamically from the user's installation; not bundled or linked.",
            )
        }
        val trackInfo = withContext(Dispatchers.IO) { JvmLibVlcMediaProbe.probe(uri, requestHeaders) }
        libVlcTrackInfo = trackInfo
        updateLibVlcTracks(trackInfo)
        return uri
    }

    private suspend fun updateLibVlcTracks(trackInfo: JvmLibVlcTrackInfo) {
        withContext(Dispatchers.Main) {
            availableAudioTracks.removeAll { isMacLibVlcAudioTrackId(it.id) }
            availableAudioTracks.addAll(trackInfo.audioStreams.map { it.track })
            currentAudioTrack =
                libVlcSelectedAudioStreamIndex
                    ?.let { streamIndex -> trackInfo.audioStreams.firstOrNull { it.streamIndex == streamIndex }?.track }
                    ?: trackInfo.audioStreams.firstOrNull { it.track.isDefault }?.track
                    ?: trackInfo.audioStreams.firstOrNull()?.track
            libVlcSelectedAudioStreamIndex =
                currentAudioTrack?.id?.let(::libVlcTrackStreamIndex)

            availableSubtitleTracks.removeAll { isMacLibVlcSubtitleTrackId(it.id) }
            availableSubtitleTracks.addAll(trackInfo.subtitleStreams.map { it.track })
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
        libVlcRenderMode = null
        withContext(Dispatchers.Main) {
            availableAudioTracks.removeAll { isMacLibVlcAudioTrackId(it.id) }
            if (currentAudioTrack?.id?.let(::isMacLibVlcAudioTrackId) == true) {
                currentAudioTrack = null
            }
            availableSubtitleTracks.removeAll { isMacLibVlcSubtitleTrackId(it.id) }
            if (currentSubtitleTrack?.id?.let(::isMacLibVlcSubtitleTrackId) == true) {
                currentSubtitleTrack = null
                subtitlesEnabled = false
            }
        }
    }

    private fun isCurrentLibAssSelection(selectionToken: Long): Boolean = libAssSelectionToken.get() == selectionToken

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
            currentSubtitleTrack = track
            subtitlesEnabled = true
            usesLibAssSubtitleOverlay = false
            libAssSubtitleSource = sourceLabel
            renderingInfo.subtitleRenderer = "libass dynamic overlay (preparing)"
            renderingInfo.subtitleSource = sourceLabel
            error = null
        }
    }

    private suspend fun clearLibAssSubtitleRenderer(selectionToken: Long? = null) {
        withContext(Dispatchers.Main) {
            if (selectionToken != null && !isCurrentLibAssSelection(selectionToken)) return@withContext
            usesLibAssSubtitleOverlay = false
            libAssSubtitleSource = null
            renderingInfo.subtitleRenderer = null
            renderingInfo.subtitleSource = null
        }
        withContext(frameDispatcher) {
            if (selectionToken != null && !isCurrentLibAssSelection(selectionToken)) return@withContext
            synchronized(libAssLock) {
                val handle = libAssRendererHandle
                libAssRendererHandle = 0L
                libAssSubtitleKey = null
                if (handle != 0L) {
                    MacNativeBridge.nDisposeLibAssRenderer(handle)
                }
            }
        }
    }

    private suspend fun configureLibAssSubtitleRenderer(
        track: SubtitleTrack,
        streamIndex: Int?,
        selectionToken: Long,
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

        if (!isCurrentLibAssSelection(selectionToken)) return false
        val configured = installLibAssSubtitleData(track, streamIndex, subtitleData, selectionToken)
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
            )
        }
        return configured
    }

    private suspend fun installLibAssSubtitleData(
        track: SubtitleTrack,
        streamIndex: Int?,
        subtitleData: MacAssSubtitleData,
        selectionToken: Long,
    ): Boolean {
        if (subtitleData.content.isBlank()) {
            throw UnsupportedOperationException("The selected subtitle track could not be loaded or is empty.")
        }
        if (!subtitleData.content.contains("[Events]", ignoreCase = true)) {
            throw UnsupportedOperationException("The selected subtitle track is not valid ASS/SSA content.")
        }

        val libAssPath =
            MacLibAssLocator.findLibAss()
                ?: throw UnsupportedOperationException(
                    "Full ASS/SSA rendering requires a user-installed libass dynamic library. " +
                        "Install libass or set composemediaplayer.macos.libass=/path/to/libass.dylib. " +
                        "ComposeMediaPlayer does not bundle or link libass.",
                )

        val key = "${track.id}|${track.src}|${streamIndex ?: -1}|${subtitleData.content.hashCode()}"
        val handle =
            synchronized(libAssLock) {
                if (!isCurrentLibAssSelection(selectionToken)) return@synchronized 0L
                var currentHandle = libAssRendererHandle
                if (currentHandle == 0L) {
                    currentHandle = MacNativeBridge.nCreateLibAssRenderer(libAssPath)
                    if (currentHandle == 0L) {
                        throw UnsupportedOperationException("Failed to initialize libass from $libAssPath")
                    }
                    libAssRendererHandle = currentHandle
                    libAssSubtitleKey = null
                }

                if (libAssSubtitleKey != key) {
                    subtitleData.fonts.forEach { font ->
                        MacNativeBridge.nAddLibAssFont(currentHandle, font.name, font.data)
                    }
                    if (!MacNativeBridge.nSetLibAssTrack(currentHandle, subtitleData.content)) {
                        throw UnsupportedOperationException("Failed to load ASS/SSA subtitle data into libass.")
                    }
                    libAssSubtitleKey = key
                }
                currentHandle
            }

        if (handle == 0L || !isCurrentLibAssSelection(selectionToken)) return false

        val sourceLabel = libAssSubtitleSourceLabel(track, streamIndex)
        withContext(Dispatchers.Main) {
            if (!isCurrentLibAssSelection(selectionToken)) return@withContext
            currentSubtitleTrack = track
            subtitlesEnabled = true
            usesLibAssSubtitleOverlay = handle != 0L
            libAssSubtitleSource = sourceLabel
            renderingInfo.subtitleRenderer =
                if (subtitleData.isPartial) {
                    "libass dynamic overlay (fast range, completing)"
                } else {
                    "libass dynamic overlay"
                }
            renderingInfo.subtitleSource = sourceLabel
        }
        return handle != 0L
    }

    private fun startCompleteLibAssSubtitleExtraction(
        track: SubtitleTrack,
        streamIndex: Int,
        sourceUri: String,
        requestHeaders: Map<String, String>,
        selectionToken: Long,
    ) {
        ioScope.launch {
            try {
                val completeData =
                    withContext(Dispatchers.IO) {
                        MacEmbeddedAssExtractor.extractComplete(sourceUri, streamIndex, requestHeaders)
                    }
                if (!isCurrentLibAssSelection(selectionToken)) return@launch
                installLibAssSubtitleData(track, streamIndex, completeData, selectionToken)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                macLogger.e { "Complete ASS subtitle extraction failed: ${e.message}" }
            }
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

    private suspend fun resolveLibVlcBackendForUri(uri: String): MacResolvedLibVlcBackend? {
        if (!JvmExternalFallbackContainerSupport.needsContainerFallback(uri)) return null

        val configured =
            (
                System.getProperty("composemediaplayer.macos.fallbackBackend")
                    ?: System.getProperty("composemediaplayer.fallbackBackend")
                    ?: System.getenv("COMPOSE_MEDIA_PLAYER_FALLBACK_BACKEND")
                    ?: System.getenv("COMPOSE_MEDIA_PLAYER_MACOS_FALLBACK_BACKEND")
                    ?: System.getProperty("composemediaplayer.hlsFallbackBackend")
                    ?: System.getenv("COMPOSE_MEDIA_PLAYER_HLS_FALLBACK_BACKEND")
                    ?: System.getProperty("composemediaplayer.macos.hlsFallbackBackend")
                    ?: System.getenv("COMPOSE_MEDIA_PLAYER_MACOS_HLS_FALLBACK_BACKEND")
                    ?: "auto"
            ).lowercase()

        return when (configured) {
            "libvlc" ->
                ExternalVlcLocator.findLibVlc()?.let { MacResolvedLibVlcBackend(it, MacLibVlcRenderMode.MEMORY) }
                    ?: throw missingLibVlcBackendException()
            "auto" ->
                ExternalVlcLocator.findLibVlc()?.let { MacResolvedLibVlcBackend(it, MacLibVlcRenderMode.MEMORY) }
            "libvlc-native-view", "libvlc-native", "libvlc-view", "libvlc-nsview" ->
                throw UnsupportedOperationException(
                    "The macOS libVLC native-view backend has been removed because it is unstable " +
                        "with Compose overlays. " +
                        "Use fallbackBackend=libvlc for the Compose canvas backend.",
                )
            "ffmpeg", "vlc" -> null
            else -> null
        }
    }

    private fun missingLibVlcBackendException(): UnsupportedOperationException =
        UnsupportedOperationException(
            "The macOS libVLC backend was requested, but no compatible VLC.app libVLC was found for " +
                "${ExternalVlcLocator.currentProcessArchitecture() ?: "the current"} JVM architecture. " +
                "Install a VLC build matching the app/JVM architecture or set " +
                "composemediaplayer.macos.libvlc and composemediaplayer.macos.libvlc.plugins to compatible paths. " +
                "ComposeMediaPlayer does not bundle or link VLC.",
        )

    private fun selectHlsFallbackBackend(requiresSubtitleRendering: Boolean): ExternalHlsFallbackBackend {
        val configured =
            (
                System.getProperty("composemediaplayer.macos.hlsFallbackBackend")
                    ?: System.getProperty("composemediaplayer.hlsFallbackBackend")
                    ?: System.getenv("COMPOSE_MEDIA_PLAYER_HLS_FALLBACK_BACKEND")
                    ?: System.getenv("COMPOSE_MEDIA_PLAYER_MACOS_HLS_FALLBACK_BACKEND")
                    ?: "auto"
            ).lowercase()

        return when (configured) {
            "vlc" -> ExternalHlsFallbackBackend.VLC
            "ffmpeg" -> ExternalHlsFallbackBackend.FFMPEG
            else -> {
                val vlcPath = ExternalVlcLocator.findVlc()
                val ffmpegPath =
                    if (requiresSubtitleRendering) {
                        ExternalFfmpegLocator.findFfmpegWithSubtitles()
                    } else {
                        ExternalFfmpegLocator.findFfmpeg()
                    }

                when {
                    vlcPath != null -> ExternalHlsFallbackBackend.VLC
                    ffmpegPath != null -> ExternalHlsFallbackBackend.FFMPEG
                    else -> ExternalHlsFallbackBackend.FFMPEG
                }
            }
        }
    }

    private fun isFfmpegFallbackDisabled(): Boolean {
        val configured =
            System.getProperty("composemediaplayer.hlsFallback")
                ?: System.getenv("COMPOSE_MEDIA_PLAYER_HLS_FALLBACK")
                ?: System.getProperty("composemediaplayer.macos.ffmpegFallback")
                ?: System.getenv("COMPOSE_MEDIA_PLAYER_MACOS_FFMPEG_FALLBACK")
                ?: "true"
        return configured.equals("false", ignoreCase = true) ||
            configured == "0" ||
            configured.equals("off", ignoreCase = true)
    }

    private fun closeFfmpegHlsFallback() {
        val fallback = ffmpegHlsFallback
        ffmpegHlsFallback = null
        ffmpegHlsFallbackDurationSeconds = null
        ffmpegHlsSourceUri = null
        ffmpegHlsSelectedAudioStreamIndex = null
        ffmpegHlsSelectedSubtitleStreamIndex = null
        ffmpegHlsPlaybackOffsetSeconds = 0.0
        fallback?.close()
    }

    private suspend fun clearFfmpegFallbackTrackState() {
        ffmpegHlsSelectedAudioStreamIndex = null
        ffmpegHlsSelectedSubtitleStreamIndex = null
        ffmpegHlsPlaybackOffsetSeconds = 0.0
        withContext(Dispatchers.Main) {
            availableAudioTracks.removeAll { isMacExternalHlsAudioTrackId(it.id) }
            if (currentAudioTrack?.id?.let(::isMacExternalHlsAudioTrackId) == true) {
                currentAudioTrack = null
            }

            availableSubtitleTracks.removeAll { isMacExternalHlsSubtitleTrackId(it.id) }
            if (currentSubtitleTrack?.id?.let(::isMacExternalHlsSubtitleTrackId) == true) {
                currentSubtitleTrack = null
                subtitlesEnabled = false
            }
        }
    }

    private suspend fun updateFfmpegFallbackTracks(hlsSource: HlsFallbackSource) {
        val previousSubtitleId = currentSubtitleTrack?.id
        withContext(Dispatchers.Main) {
            availableAudioTracks.removeAll { isMacExternalHlsAudioTrackId(it.id) }
            availableAudioTracks.addAll(hlsSource.audioTracks)
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

            availableSubtitleTracks.removeAll { isMacExternalHlsSubtitleTrackId(it.id) }
            availableSubtitleTracks.addAll(hlsSource.subtitleTracks)
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
        val existingPtr = playerPtr
        if (existingPtr != 0L && nativeBackendUsesLibVlc != wantsLibVlc) {
            val ptrToDispose = playerPtrAtomic.getAndSet(0L)
            if (ptrToDispose != 0L) {
                MacNativeBridge.nDisposePlayer(ptrToDispose)
            }
            nativeBackendUsesLibVlc = false
        }

        if (playerPtr == 0L) {
            val ptr =
                if (libVlcBackend != null) {
                    MacNativeBridge.nCreateLibVlcPlayer(
                        libVlcPath = libVlcBackend.installation.libVlcPath,
                        pluginPath = libVlcBackend.installation.pluginPath,
                    )
                } else {
                    MacNativeBridge.nCreatePlayer()
                }
            if (ptr != 0L) {
                if (!playerPtrAtomic.compareAndSet(0L, ptr)) {
                    // Another coroutine already initialized the player; discard ours
                    MacNativeBridge.nDisposePlayer(ptr)
                } else {
                    nativeBackendUsesLibVlc = wantsLibVlc
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

            // Instead of directly calling `updateMetadata()`,
            // we poll until valid dimensions are available
            pollDimensionsUntilReady(ptr)

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

            // Calculate aspect ratio
            val newAspectRatio =
                if (width > 0 && height > 0) {
                    width.toFloat() / height.toFloat()
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

    /** Starts periodic frame updates on a background thread. */
    private fun startFrameUpdates() {
        macLogger.d { "startFrameUpdates() - Starting frame updates" }
        stopFrameUpdates()
        frameUpdateJob =
            ioScope.launch {
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
            ioScope.launch {
                while (isActive) {
                    ensureActive()
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
            ioScope.launch {
                while (isActive) {
                    ensureActive() // Check if coroutine is still active
                    checkBufferingState()
                    delay(bufferingCheckInterval)
                }
            }
    }

    /** Checks if the media is currently buffering. */
    private suspend fun checkBufferingState() {
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

    /** Updates the current video frame on a background thread. */
    private suspend fun updateFrameAsync() {
        withContext(frameDispatcher) {
            try {
                val ptr = playerPtr
                if (ptr == 0L) return@withContext

                // Lock the CVPixelBuffer directly — eliminates the Swift-side memcpy.
                // outInfo = [width, height, bytesPerRow]
                val outInfo = IntArray(3)
                val frameAddress = MacNativeBridge.nLockFrame(ptr, outInfo)
                if (frameAddress == 0L) return@withContext

                val width = outInfo[0]
                val height = outInfo[1]
                val srcBytesPerRow = outInfo[2]

                if (width <= 0 || height <= 0) {
                    MacNativeBridge.nUnlockFrame(ptr)
                    return@withContext
                }

                val frameSizeBytes = srcBytesPerRow.toLong() * height.toLong()
                var framePublished = false

                try {
                    withContext(Dispatchers.Default) {
                        val srcBuf =
                            MacNativeBridge.nWrapPointer(frameAddress, frameSizeBytes)
                                ?: return@withContext

                        // Allocate/reuse two bitmaps (double-buffering) to avoid writing while the UI draws.
                        if (skiaBitmapA == null || skiaBitmapWidth != width || skiaBitmapHeight != height) {
                            skiaBitmapA?.close()
                            skiaBitmapB?.close()

                            val imageInfo = ImageInfo(width, height, ColorType.BGRA_8888, ColorAlphaType.OPAQUE)
                            skiaBitmapA = Bitmap().apply { allocPixels(imageInfo) }
                            skiaBitmapB = Bitmap().apply { allocPixels(imageInfo) }
                            skiaBitmapWidth = width
                            skiaBitmapHeight = height
                            nextSkiaBitmapA = true
                        }

                        val targetBitmap = if (nextSkiaBitmapA) skiaBitmapA!! else skiaBitmapB!!
                        nextSkiaBitmapA = !nextSkiaBitmapA

                        val pixmap = targetBitmap.peekPixels() ?: return@withContext
                        val pixelsAddr = pixmap.addr
                        if (pixelsAddr == 0L) return@withContext

                        // Single copy: CVPixelBuffer → Skia bitmap pixels (no intermediate buffer)
                        srcBuf.rewind()
                        val dstRowBytes = pixmap.rowBytes
                        val dstSizeBytes = dstRowBytes.toLong() * height.toLong()
                        val destBuf =
                            MacNativeBridge.nWrapPointer(pixelsAddr, dstSizeBytes)
                                ?: return@withContext
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
                                val assHandle = libAssRendererHandle
                                if (assHandle != 0L) {
                                    MacNativeBridge.nBlendLibAssFrame(
                                        handle = assHandle,
                                        pixelsAddress = pixelsAddr,
                                        rowBytes = dstRowBytes,
                                        width = width,
                                        height = height,
                                        timeMs = subtitleTimeMs,
                                    )
                                }
                            }
                        }

                        _currentFrameState.value = targetBitmap.asComposeImageBitmap()
                        framePublished = true
                    }
                } finally {
                    MacNativeBridge.nUnlockFrame(ptr)
                }

                if (framePublished) {
                    lastFrameUpdateTime = System.currentTimeMillis()

                    // Update loading state if needed on the main thread
                    if (isLoading && !seekInProgress) {
                        withContext(Dispatchers.Main) {
                            isLoading = false
                        }
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                macLogger.e { "updateFrameAsync() - Exception: ${e.message}" }
            }
        }
    }

    /**
     * Updates the playback position, slider, and audio levels on a background
     * thread.
     */
    private suspend fun updatePositionAsync() {
        if (!hasMedia || userDragging) return

        try {
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
            if (seekInProgress && targetSeekTime != null) {
                if (abs(current - targetSeekTime!!) < 0.3) {
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

    /** Checks if playback has ended and triggers loop or stop accordingly. */
    private suspend fun checkLoopingAsync() {
        val ptr = playerPtr
        if (ptr == 0L) return

        // Trust AVPlayerItemDidPlayToEndTime: it fires reliably on macOS for both
        // file and HLS playback. A position-based fallback (current >= duration - x)
        // is dangerous because it stops playback x seconds early — the slider
        // freezes at (duration - x) / duration instead of reaching 100%.
        if (!MacNativeBridge.nConsumeDidPlayToEnd(ptr)) return

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
        macLogger.d { "play() - Starting playback" }
        ioScope.launch {
            if (!hasMedia && lastUri != null) {
                // Reload the media using the saved URI
                openUri(lastUri!!, requestHeaders = lastRequestHeaders)
                // The openUri method will start reading if the opening is successful
            } else if (hasMedia) {
                // If the media is already loaded, start playing in the background
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

            if (shouldUseHdrMetalSurface()) {
                stopFrameUpdates()
            } else {
                startFrameUpdates()
            }
            startPositionUpdates()
            startBufferingCheck()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            macLogger.e { "Error in playInBackground: ${e.message}" }
            handleError(e)
        }
    }

    override fun pause() {
        macLogger.d { "pause() - Pausing playback" }
        ioScope.launch {
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

            updateFrameAsync()
            stopFrameUpdates()
            stopPositionUpdates()
            stopBufferingCheck()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            macLogger.e { "Error in pauseInBackground: ${e.message}" }
        }
    }

    override fun stop() {
        macLogger.d { "stop() - Stopping playback" }
        ioScope.launch {
            if (ffmpegHlsFallback != null) {
                cleanupCurrentPlayback()
            } else {
                pauseInBackground()
                if (hasMedia) {
                    seekToAsync(0f)
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

    override fun seekTo(time: Duration) {
        macLogger.d { "seekTo() - Seeking to time: $time" }
        ioScope.launch {
            delay(10.milliseconds) // Small delay to coalesce rapid seek events
            seekToTimeAsync(time)
        }
    }

    override fun seekToProgress(progress: Float) {
        seekTo(progress.coerceIn(0f, 1f) * VideoPlayerState.SLIDER_SCALE)
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun seekTo(value: Float) {
        macLogger.d { "seekTo() - Seeking with slider value: $value" }
        ioScope.launch {
            delay(10.milliseconds) // Small delay to coalesce rapid seek events
            val duration = getDurationSafely()
            if (duration <= 0) {
                withContext(Dispatchers.Main) {
                    isLoading = false
                }
                return@launch
            }
            seekToSecondsAsync(
                ((value / VideoPlayerState.SLIDER_SCALE).toDouble() * duration).coerceIn(0.0, duration),
            )
        }
    }

    /** Seeks to a position on a background thread. */
    private suspend fun seekToAsync(value: Float) {
        val duration = getDurationSafely()
        if (duration <= 0) {
            withContext(Dispatchers.Main) {
                isLoading = false
            }
            return
        }
        seekToSecondsAsync(
            ((value / VideoPlayerState.SLIDER_SCALE).toDouble() * duration).coerceIn(0.0, duration),
        )
    }

    private suspend fun seekToTimeAsync(time: Duration) {
        val duration = getDurationSafely()
        if (duration <= 0) {
            withContext(Dispatchers.Main) {
                isLoading = false
            }
            return
        }
        seekToSecondsAsync(time.toDouble(kotlin.time.DurationUnit.SECONDS).coerceIn(0.0, duration))
    }

    private suspend fun seekToSecondsAsync(seekTime: Double) {
        withContext(Dispatchers.Main) {
            isLoading = true
        }

        try {
            val duration = getDurationSafely()
            if (duration <= 0) {
                withContext(Dispatchers.Main) {
                    isLoading = false
                }
                return
            }

            withContext(Dispatchers.Main) {
                seekInProgress = true
                targetSeekTime = seekTime
                sliderPos =
                    (seekTime / duration * VideoPlayerState.SLIDER_SCALE)
                        .toFloat()
                        .coerceIn(0f, VideoPlayerState.SLIDER_SCALE)
            }

            lastFrameUpdateTime = System.currentTimeMillis()

            val ptr = playerPtr
            if (ptr == 0L) return
            MacNativeBridge.nSeekTo(ptr, seekTime)

            if (isPlaying) {
                MacNativeBridge.nPlay(ptr)
                // Reduce delay to update frame faster for local videos
                delay(10.milliseconds)
                updateFrameAsync()
                // Reduced timeout delay from 2000ms to 300ms
                ioScope.launch {
                    delay(300.milliseconds)
                    if (seekInProgress) {
                        macLogger.d { "seekToAsync() - Forcing end of seek after timeout" }
                        seekInProgress = false
                        targetSeekTime = null
                        withContext(Dispatchers.Main) {
                            isLoading = false
                        }
                    }
                }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            macLogger.e { "Error in seekToAsync: ${e.message}" }
            withContext(Dispatchers.Main) {
                isLoading = false
                seekInProgress = false
                targetSeekTime = null
            }
        }
    }

    override fun dispose() {
        macLogger.d { "dispose() - Releasing resources" }
        // Cancel all background tasks first
        stopFrameUpdates()
        stopPositionUpdates()
        stopBufferingCheck()
        uiUpdateJob?.cancel()
        playerScope.cancel()

        val fallbackToClose = ffmpegHlsFallback
        ffmpegHlsFallback = null
        ffmpegHlsFallbackDurationSeconds = null
        ffmpegHlsSourceUri = null
        ffmpegHlsSelectedAudioStreamIndex = null
        ffmpegHlsSelectedSubtitleStreamIndex = null
        ffmpegHlsPlaybackOffsetSeconds = 0.0
        libVlcBackendActive = false
        libVlcSourceUri = null
        libVlcTrackInfo = null
        libVlcSelectedAudioStreamIndex = null
        libVlcSelectedSubtitleStreamIndex = null
        libVlcRenderMode = null
        nativeBackendUsesLibVlc = false

        // Clear the pointer atomically so no background task can use it
        val ptrToDispose = playerPtrAtomic.getAndSet(0L)

        // Release bitmaps on the frame dispatcher (rendering accesses them there)
        // then dispose the native player — all on a background thread to avoid
        // blocking the main/UI thread.
        Thread {
            try {
                // Close bitmaps (not thread-safe with rendering, but frame updates
                // are already cancelled above and playerPtr is zeroed)
                skiaBitmapA?.close()
                skiaBitmapB?.close()
                skiaBitmapA = null
                skiaBitmapB = null
                skiaBitmapWidth = 0
                skiaBitmapHeight = 0
                nextSkiaBitmapA = true
            } catch (e: Exception) {
                macLogger.e { "Error releasing bitmaps: ${e.message}" }
            }

            if (ptrToDispose != 0L) {
                macLogger.d { "dispose() - Disposing native player" }
                try {
                    MacNativeBridge.nDisposePlayer(ptrToDispose)
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    macLogger.e { "Error disposing player: ${e.message}" }
                }
            }

            fallbackToClose?.close()
        }.start()

        ioScope.cancel()
    }

    /** Resets the player's state. */
    private suspend fun resetState() {
        withContext(Dispatchers.Main) {
            hasMedia = false
            isPlaying = false
            isLoading = false
            _currentTime.value = Duration.ZERO
            _duration.value = Duration.ZERO
            _positionText.value = "00:00"
            _durationText.value = "00:00"
            _aspectRatio.value = 16f / 9f
            error = null
        }
        _currentFrameState.value = null
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
                if (e is UnsupportedOperationException) {
                    VideoPlayerError.CodecError("Error: ${e.message}")
                } else {
                    VideoPlayerError.SourceError("Error: ${e.message}")
                }
        }
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

    override fun selectAudioTrack(track: AudioTrack?) {
        val selectedLibVlcStreamIndex =
            track
                ?.id
                ?.takeIf(::isMacLibVlcAudioTrackId)
                ?.let(::libVlcTrackStreamIndex)

        if (track != null && selectedLibVlcStreamIndex != null) {
            ioScope.launch {
                selectLibVlcAudioTrack(track, selectedLibVlcStreamIndex)
            }
            return
        }

        val selectedStreamIndex =
            track
                ?.id
                ?.takeIf(::isMacExternalHlsAudioTrackId)
                ?.let(::externalHlsTrackStreamIndex)

        if (track != null && selectedStreamIndex != null) {
            ioScope.launch {
                switchFfmpegAudioTrack(track, selectedStreamIndex)
            }
            return
        }

        ioScope.launch {
            withContext(Dispatchers.Main) {
                currentAudioTrack = track
            }
        }
    }

    private suspend fun selectLibVlcAudioTrack(
        track: AudioTrack,
        streamIndex: Int,
    ) {
        libVlcSelectedAudioStreamIndex = streamIndex
        withContext(Dispatchers.Main) {
            currentAudioTrack = track
        }

        val ordinal =
            libVlcTrackInfo
                ?.audioStreams
                ?.firstOrNull { it.streamIndex == streamIndex }
                ?.ordinal
                ?: return
        val ptr = playerPtr
        if (ptr != 0L) {
            MacNativeBridge.nSelectLibVlcAudioTrack(ptr, ordinal)
        }
    }

    private suspend fun switchFfmpegAudioTrack(
        track: AudioTrack,
        streamIndex: Int,
    ) {
        val sourceUri = ffmpegHlsSourceUri
        if (sourceUri == null) {
            withContext(Dispatchers.Main) {
                currentAudioTrack = track
            }
            return
        }

        if (ffmpegHlsSelectedAudioStreamIndex == streamIndex) {
            withContext(Dispatchers.Main) {
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
            withContext(Dispatchers.Main) {
                isLoading = true
                error = null
                currentAudioTrack = track
                sliderPos = restartSliderPos
                _positionText.value = formatTime(restartPositionSeconds.secondsAsDuration())
            }

            cleanupCurrentPlayback()
            ensurePlayerInitialized()

            ffmpegHlsSelectedAudioStreamIndex = streamIndex
            ffmpegHlsSelectedSubtitleStreamIndex = selectedSubtitleStreamIndex
            ffmpegHlsPlaybackOffsetSeconds = restartPositionSeconds
            val playableUri = prepareUriForMacPlayback(sourceUri, lastRequestHeaders)
            val opened = openMediaUri(playableUri, lastRequestHeaders)
            if (!opened) {
                closeFfmpegHlsFallback()
                clearFfmpegFallbackTrackState()
                withContext(Dispatchers.Main) {
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

            withContext(Dispatchers.Main) {
                hasMedia = true
                isLoading = false
                isPlaying = shouldResumePlayback
            }

            if (shouldUseHdrMetalSurface()) {
                stopFrameUpdates()
            } else {
                startFrameUpdates()
            }
            startPositionUpdates()
            if (!shouldUseHdrMetalSurface()) {
                updateFrameAsync()
            }
            startBufferingCheck()

            if (shouldResumePlayback) {
                playInBackground()
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            macLogger.e { "switchFfmpegAudioTrack() - Exception: ${e.message}" }
            closeFfmpegHlsFallback()
            clearFfmpegFallbackTrackState()
            handleError(e)
        }
    }

    override fun selectSubtitleTrack(track: SubtitleTrack?) {
        if (track == null && libVlcBackendActive) {
            val selectionToken = libAssSelectionToken.incrementAndGet()
            ioScope.launch {
                clearLibAssSubtitleRenderer(selectionToken)
                disableLibVlcSubtitles()
            }
            return
        }

        val selectedLibVlcStreamIndex =
            track
                ?.id
                ?.takeIf(::isMacLibVlcSubtitleTrackId)
                ?.let(::libVlcTrackStreamIndex)

        if (track != null && selectedLibVlcStreamIndex != null) {
            ioScope.launch {
                selectLibVlcSubtitleTrack(track, selectedLibVlcStreamIndex)
            }
            return
        }

        val selectedStreamIndex =
            track
                ?.id
                ?.takeIf(::isMacExternalHlsSubtitleTrackId)
                ?.let(::externalHlsTrackStreamIndex)

        if (track != null && selectedStreamIndex != null) {
            libAssSelectionToken.incrementAndGet()
            ioScope.launch {
                switchFfmpegSubtitleTrack(track, selectedStreamIndex)
            }
            return
        }

        if (track != null && isAssLikeTrack(track)) {
            val selectionToken = libAssSelectionToken.incrementAndGet()
            ioScope.launch {
                try {
                    markLibAssSubtitlePreparing(track, streamIndex = null, selectionToken)
                    configureLibAssSubtitleRenderer(track, streamIndex = null, selectionToken = selectionToken)
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    if (isCurrentLibAssSelection(selectionToken)) {
                        clearLibAssSubtitleRenderer(selectionToken)
                        withContext(Dispatchers.Main) {
                            if (!isCurrentLibAssSelection(selectionToken)) return@withContext
                            currentSubtitleTrack = null
                            subtitlesEnabled = false
                            error = VideoPlayerError.CodecError("ASS subtitle rendering failed: ${e.message}")
                        }
                    }
                }
            }
            return
        }

        val selectionToken = libAssSelectionToken.incrementAndGet()
        ioScope.launch {
            clearLibAssSubtitleRenderer(selectionToken)
            withContext(Dispatchers.Main) {
                currentSubtitleTrack = track
                subtitlesEnabled = track != null
            }
        }
    }

    private suspend fun selectLibVlcSubtitleTrack(
        track: SubtitleTrack,
        streamIndex: Int,
    ) {
        if (isAssLikeTrack(track)) {
            val selectionToken = libAssSelectionToken.incrementAndGet()
            try {
                libVlcSelectedSubtitleStreamIndex = streamIndex
                markLibAssSubtitlePreparing(track, streamIndex, selectionToken)
                if (!configureLibAssSubtitleRenderer(track, streamIndex, selectionToken)) return
                libVlcSelectedSubtitleStreamIndex = streamIndex
                val ptr = playerPtr
                if (ptr != 0L) {
                    MacNativeBridge.nDisableLibVlcSubtitles(ptr)
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (isCurrentLibAssSelection(selectionToken)) {
                    clearLibAssSubtitleRenderer(selectionToken)
                    libVlcSelectedSubtitleStreamIndex = null
                    withContext(Dispatchers.Main) {
                        if (!isCurrentLibAssSelection(selectionToken)) return@withContext
                        currentSubtitleTrack = null
                        subtitlesEnabled = false
                        error = VideoPlayerError.CodecError("ASS subtitle rendering failed: ${e.message}")
                    }
                }
            }
            return
        }

        libAssSelectionToken.incrementAndGet()
        val sourceUri = libVlcSourceUri
        if (sourceUri == null) {
            withContext(Dispatchers.Main) {
                currentSubtitleTrack = null
                subtitlesEnabled = false
                error = VideoPlayerError.SourceError("No libVLC source is available for subtitle rendering")
            }
            return
        }

        macLogger.d {
            "libVLC memory callbacks do not expose rendered subtitle pixels; switching to HLS renderer"
        }
        switchMacHlsSubtitleTrack(
            sourceUri = sourceUri,
            track = track,
            streamIndex = streamIndex,
            selectedAudioStreamIndex = libVlcSelectedAudioStreamIndex,
            failureMessage = "Failed to switch subtitle track through the external HLS renderer",
        )
    }

    private suspend fun disableLibVlcSubtitles() {
        libVlcSelectedSubtitleStreamIndex = null
        val ptr = playerPtr
        if (ptr != 0L) {
            MacNativeBridge.nDisableLibVlcSubtitles(ptr)
        }
        withContext(Dispatchers.Main) {
            subtitlesEnabled = false
            currentSubtitleTrack = null
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
    ) {
        val sourceUri = ffmpegHlsSourceUri
        if (sourceUri == null) {
            withContext(Dispatchers.Main) {
                currentSubtitleTrack = track
                subtitlesEnabled = track != null
            }
            return
        }

        if (ffmpegHlsSelectedSubtitleStreamIndex == streamIndex) {
            withContext(Dispatchers.Main) {
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
        )
    }

    private suspend fun switchMacHlsSubtitleTrack(
        sourceUri: String,
        track: SubtitleTrack?,
        streamIndex: Int?,
        selectedAudioStreamIndex: Int?,
        failureMessage: String,
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
                val hasSubtitleRenderer =
                    when (selectHlsFallbackBackend(requiresSubtitleRendering = true)) {
                        ExternalHlsFallbackBackend.VLC -> ExternalVlcLocator.findVlc() != null
                        ExternalHlsFallbackBackend.FFMPEG -> ExternalFfmpegLocator.findFfmpegWithSubtitles() != null
                    }

                if (!hasSubtitleRenderer) {
                    withContext(Dispatchers.Main) {
                        isLoading = false
                        currentSubtitleTrack = null
                        subtitlesEnabled = false
                        error =
                            VideoPlayerError.CodecError(
                                "Full ASS/SSA rendering through the external HLS fallback requires VLC " +
                                    "or an ffmpeg build " +
                                    "with libass and the subtitles filter enabled. No suitable external renderer was found.",
                            )
                    }
                    return
                }
            }

            withContext(Dispatchers.Main) {
                isLoading = true
                error = null
                currentSubtitleTrack = track
                subtitlesEnabled = track != null
                sliderPos = restartSliderPos
                _positionText.value = formatTime(restartPositionSeconds.secondsAsDuration())
            }

            cleanupCurrentPlayback()
            ensurePlayerInitialized()

            ffmpegHlsSelectedAudioStreamIndex = selectedAudioStreamIndex
            ffmpegHlsSelectedSubtitleStreamIndex = streamIndex
            ffmpegHlsPlaybackOffsetSeconds = restartPositionSeconds
            val playableUri = prepareUriForMacPlayback(sourceUri, lastRequestHeaders)
            val opened = openMediaUri(playableUri, lastRequestHeaders)
            if (!opened) {
                closeFfmpegHlsFallback()
                clearFfmpegFallbackTrackState()
                withContext(Dispatchers.Main) {
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

            withContext(Dispatchers.Main) {
                hasMedia = true
                isLoading = false
                isPlaying = shouldResumePlayback
            }

            if (shouldUseHdrMetalSurface()) {
                stopFrameUpdates()
            } else {
                startFrameUpdates()
            }
            startPositionUpdates()
            if (!shouldUseHdrMetalSurface()) {
                updateFrameAsync()
            }
            startBufferingCheck()

            if (shouldResumePlayback) {
                playInBackground()
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            macLogger.e { "switchMacHlsSubtitleTrack() - Exception: ${e.message}" }
            closeFfmpegHlsFallback()
            clearFfmpegFallbackTrackState()
            handleError(e)
        }
    }

    override fun disableSubtitles() {
        val selectionToken = libAssSelectionToken.incrementAndGet()
        if (usesLibAssSubtitleOverlay || libAssSubtitleSource != null) {
            ioScope.launch {
                withContext(Dispatchers.Main) {
                    if (!isCurrentLibAssSelection(selectionToken)) return@withContext
                    subtitlesEnabled = false
                    currentSubtitleTrack = null
                    usesLibAssSubtitleOverlay = false
                    renderingInfo.subtitleRenderer = null
                    renderingInfo.subtitleSource = null
                }
                clearLibAssSubtitleRenderer(selectionToken)
                if (libVlcBackendActive) {
                    libVlcSelectedSubtitleStreamIndex = null
                    val ptr = playerPtr
                    if (ptr != 0L) {
                        MacNativeBridge.nDisableLibVlcSubtitles(ptr)
                    }
                }
            }
            return
        }

        if (libVlcBackendActive && currentSubtitleTrack?.id?.let(::isMacLibVlcSubtitleTrackId) == true) {
            ioScope.launch {
                clearLibAssSubtitleRenderer(selectionToken)
                disableLibVlcSubtitles()
            }
            return
        }

        if (ffmpegHlsSourceUri != null && ffmpegHlsSelectedSubtitleStreamIndex != null) {
            ioScope.launch {
                switchFfmpegSubtitleTrack(track = null, streamIndex = null)
            }
            return
        }

        ioScope.launch {
            withContext(Dispatchers.Main) {
                subtitlesEnabled = false
                currentSubtitleTrack = null
            }
        }
    }

    override fun clearError() {
        macLogger.d { "clearError() - Clearing error" }

        // Use runBlocking to ensure the error is cleared immediately
        // This is important for tests that expect the error to be cleared synchronously
        runBlocking {
            withContext(Dispatchers.Main) {
                error = null
            }
        }
    }

    /**
     * Toggles the fullscreen state of the video player
     */
    override fun toggleFullscreen() {
        // Update the state immediately for test synchronization
        isFullscreen = !isFullscreen

        // Launch any additional background work if needed
        ioScope.launch {
            // Any additional work related to fullscreen toggle can go here
        }
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
        if (width <= 0 || height <= 0) return
        if (width == surfaceWidth && height == surfaceHeight) return

        surfaceWidth = width
        surfaceHeight = height

        isResizing.set(true)
        resizeJob?.cancel()
        resizeJob =
            ioScope.launch {
                delay(120.milliseconds)
                try {
                    applyOutputScaling()
                } finally {
                    isResizing.set(false)
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

        MacNativeBridge.nSetOutputSize(ptr, sw, sh)
    }
}
