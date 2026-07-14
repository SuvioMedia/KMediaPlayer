package io.github.kdroidfilter.composemediaplayer.linux

import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import io.github.kdroidfilter.composemediaplayer.AudioTrack
import io.github.kdroidfilter.composemediaplayer.DesktopPlayerLifecycle
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
import io.github.kdroidfilter.composemediaplayer.TrackSelectionResult
import io.github.kdroidfilter.composemediaplayer.VideoMetadata
import io.github.kdroidfilter.composemediaplayer.VideoPlaybackOptions
import io.github.kdroidfilter.composemediaplayer.VideoPlayerError
import io.github.kdroidfilter.composemediaplayer.VideoPlayerState
import io.github.kdroidfilter.composemediaplayer.VideoProjectionSettings
import io.github.kdroidfilter.composemediaplayer.VideoProjectionViewControlMode
import io.github.kdroidfilter.composemediaplayer.VideoProjectionViewSettings
import io.github.kdroidfilter.composemediaplayer.VideoRenderingInfo
import io.github.kdroidfilter.composemediaplayer.VideoTextureCrop
import io.github.kdroidfilter.composemediaplayer.audioTrackSelectionResult
import io.github.kdroidfilter.composemediaplayer.forcedJvmDesktopBackend
import io.github.kdroidfilter.composemediaplayer.jvmCanvasRendererLabel
import io.github.kdroidfilter.composemediaplayer.jvmPlayerCapabilities
import io.github.kdroidfilter.composemediaplayer.normalizeUnixLocalFileUriForPlayback
import io.github.kdroidfilter.composemediaplayer.renderingInfoLabel
import io.github.kdroidfilter.composemediaplayer.requestHeadersLineString
import io.github.kdroidfilter.composemediaplayer.sanitizedRequestHeaders
import io.github.kdroidfilter.composemediaplayer.subtitleTrackSelectionResult
import io.github.kdroidfilter.composemediaplayer.util.TaggedLogger
import io.github.kdroidfilter.composemediaplayer.util.formatTime
import io.github.kdroidfilter.composemediaplayer.util.secondsAsDuration
import io.github.kdroidfilter.composemediaplayer.util.toSecondsDouble
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

internal val linuxLogger = TaggedLogger("LinuxVideoPlayerState")

private enum class LinuxLibVlcRenderMode {
    MEMORY,
    NATIVE_VIEW,
}

private data class LinuxResolvedLibVlcBackend(
    val installation: JvmLibVlcInstallation,
    val renderMode: LinuxLibVlcRenderMode,
)

private data class LinuxLibVlcRuntimeTrackDescription(
    val ordinal: Int,
    val label: String,
)

/**
 * LinuxVideoPlayerState — JNI-based implementation using a native C GStreamer player.
 *
 * Architecture mirrors MacVideoPlayerState: coroutine-driven polling of the native
 * layer for frames, position, audio levels, and end-of-playback detection.
 */
@Suppress("LargeClass", "MagicNumber", "TooManyFunctions")
@Stable
class LinuxVideoPlayerState(
    private val playbackOptions: VideoPlaybackOptions = VideoPlaybackOptions(),
) : VideoPlayerState {
    private var _projection by mutableStateOf(playbackOptions.projection.normalized())
    override var projection: VideoProjectionSettings
        get() = _projection
        set(value) {
            _projection = value.normalized()
            updateProjectionRenderingInfo()
        }
    private var _projectionView by mutableStateOf(playbackOptions.projectionView.normalized())
    override var projectionView: VideoProjectionViewSettings
        get() = _projectionView
        set(value) {
            _projectionView = value.normalized()
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
            updateProjectionRenderingInfo()
        }

    // Native player pointer (AtomicLong for lock-free reads from the frame hot path)
    private val playerPtrAtomic = AtomicLong(0L)
    private val playerPtr: Long get() = playerPtrAtomic.get()

    /** Serializes native-player replacement/destruction with AWT surface attachment. */
    private val nativeInstanceLock = Any()

    // Serial dispatcher for frame processing
    private val frameDispatcher = Dispatchers.Default.limitedParallelism(1)
    private val _currentFrameState = MutableStateFlow<ImageBitmap?>(null)
    internal val currentFrameState: State<ImageBitmap?> = mutableStateOf(null)

    // Double-buffered Skia bitmaps
    private var skiaBitmapWidth: Int = 0
    private var skiaBitmapHeight: Int = 0
    private var skiaBitmapA: Bitmap? = null
    private var skiaBitmapB: Bitmap? = null
    private var nextSkiaBitmapA: Boolean = true

    // Surface display size (pixels) for output scaling
    private var surfaceWidth = 0
    private var surfaceHeight = 0
    private val isResizing = AtomicBoolean(false)
    private var resizeJob: Job? = null
    private val resizeRequestToken = AtomicLong(0L)

    // Background worker scopes and jobs
    private val ioScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val cleanupScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val lifecycle = DesktopPlayerLifecycle(ioScope, cleanupScope)
    private var playerScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var frameUpdateJob: Job? = null
    private var bufferingCheckJob: Job? = null
    private var uiUpdateJob: Job? = null
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
    private var nativeBackendLibVlcRenderMode: LinuxLibVlcRenderMode? = null
    internal var libVlcNativeSurfaceRequested: Boolean by mutableStateOf(false)
        private set
    private var libVlcNativeSurfaceAttached: Boolean = false

    // State tracking
    private var lastFrameUpdateTime: Long = 0
    private var seekInProgress = false
    private var targetSeekTime: Duration? = null

    // Frame rate from native layer
    private var captureFrameRate: Float = 0.0f

    // UI State
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
    override val renderingInfo: VideoRenderingInfo =
        VideoRenderingInfo(
            videoProjection = projection.renderingInfoLabel(),
        )

    private fun updateProjectionRenderingInfo() {
        renderingInfo.videoProjection = projection.renderingInfoLabel()
        if (!shouldUseLibVlcNativeSurface()) {
            renderingInfo.videoRenderer =
                if (libVlcBackendActive && nativeBackendLibVlcRenderMode == LinuxLibVlcRenderMode.MEMORY) {
                    libVlcVideoRenderer(LinuxLibVlcRenderMode.MEMORY)
                } else {
                    projection.jvmCanvasRendererLabel(projectionTextureCrop)
                }
        }
    }

    override val capabilities: PlayerCapabilities
        get() = jvmPlayerCapabilities(playbackOptions)
    override var isFullscreen: Boolean by mutableStateOf(false)
    private var lastUri: String? = null
    private var lastRequestHeaders: Map<String, String> = emptyMap()

    private val _positionText = mutableStateOf("00:00")
    override val positionText: String get() = _positionText.value

    private val _durationText = mutableStateOf("00:00")
    override val durationText: String get() = _durationText.value

    private val _currentTime = mutableStateOf(Duration.ZERO)
    private val _duration = mutableStateOf(Duration.ZERO)
    override val currentTime: Duration get() = _currentTime.value
    override val preciseCurrentTime: Duration get() = _currentTime.value
    override val duration: Duration get() = _duration.value

    private val _aspectRatio = mutableStateOf(16f / 9f)
    override val aspectRatio: Float get() = _aspectRatio.value

    // Volume
    private val _volumeState = mutableStateOf(1.0f)
    override var volume: Float
        get() = _volumeState.value
        set(value) {
            lifecycle.ensureUsable()
            val newValue = value.coerceIn(0f, 1f)
            if (_volumeState.value != newValue) {
                _volumeState.value = newValue
                lifecycle.launchSourceBoundControlOperation { applyVolume() }
            }
        }

    // Playback speed
    private val _playbackSpeedState = mutableStateOf(1.0f)
    override var playbackSpeed: Float
        get() = _playbackSpeedState.value
        set(value) {
            lifecycle.ensureUsable()
            val newValue = value.coerceIn(VideoPlayerState.MIN_PLAYBACK_SPEED, VideoPlayerState.MAX_PLAYBACK_SPEED)
            if (_playbackSpeedState.value != newValue) {
                _playbackSpeedState.value = newValue
                lifecycle.launchSourceBoundControlOperation { applyPlaybackSpeed() }
            }
        }

    private val updateInterval: Long
        get() =
            if (captureFrameRate > 0) {
                (1000.0f / captureFrameRate).toLong()
            } else {
                33L // ~30fps default
            }

    private val bufferingCheckInterval = 200L
    private val bufferingTimeoutThreshold = 500L

    init {
        linuxLogger.d { "Initializing Linux video player (JNI)" }
        lifecycle.launchControlOperation {
            initPlayer()
            startUIUpdateJob()
        }
    }

    @OptIn(FlowPreview::class)
    private fun startUIUpdateJob() {
        uiUpdateJob?.cancel()
        uiUpdateJob =
            ioScope.launch {
                _currentFrameState.debounce(1).collect { newFrame ->
                    ensureActive()
                    withContext(Dispatchers.Main) {
                        (currentFrameState as MutableState).value = newFrame
                    }
                }
            }
    }

    private suspend fun initPlayer() {
        linuxLogger.d { "initPlayer() - Creating native player" }
        try {
            val ptr = LinuxNativeBridge.nCreatePlayer()
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
                    disposeNativePlayer(ptr)
                    return
                }
                linuxLogger.d { "Native player created successfully" }
                applyVolume()
                applyPlaybackSpeed()
            } else {
                linuxLogger.e { "Failed to create native player" }
                withContext(Dispatchers.Main) {
                    error = VideoPlayerError.UnknownError("Failed to create native player")
                }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            linuxLogger.e { "Exception in initPlayer: ${e.message}" }
            withContext(Dispatchers.Main) {
                error = VideoPlayerError.UnknownError("Failed to initialize player: ${e.message}")
            }
        }
    }

    private fun checkExistsIfLocalFile(uri: String): Boolean {
        val schemeDelimiter = uri.indexOf("://")
        val scheme =
            when {
                uri.startsWith("file:", ignoreCase = true) -> "file"
                schemeDelimiter >= 0 -> uri.substring(0, schemeDelimiter)
                else -> ""
            }
        return when (scheme) {
            "", "file" -> {
                val path = if (scheme == "file") normalizeUnixLocalFileUriForPlayback(uri) else uri
                File(path).exists()
            }
            else -> true
        }
    }

    override fun openUri(
        uri: String,
        initializePlayerState: InitialPlayerState,
        requestHeaders: Map<String, String>,
    ) {
        lifecycle.ensureUsable()
        linuxLogger.d { "openUri() - Opening URI: $uri" }
        val sanitizedHeaders = requestHeaders.sanitizedRequestHeaders()
        lifecycle.launchSourceOperation(
            onScheduled = {
                lastUri = uri
                lastRequestHeaders = sanitizedHeaders
            },
        ) { generation ->
            if (!checkExistsIfLocalFile(uri)) {
                linuxLogger.e { "File does not exist: $uri" }
                setPlayerError(VideoPlayerError.SourceError("File not found: $uri"))
                return@launchSourceOperation
            }
            lifecycle.ensureCurrentSource(generation)
            withContext(Dispatchers.Main) {
                isLoading = true
                error = null
                playbackSpeed = 1.0f
            }
            lifecycle.ensureCurrentSource(generation)

            try {
                if (hasMedia || externalHlsFallback != null || libVlcBackendActive) {
                    cleanupCurrentPlayback()
                }
                lifecycle.ensureCurrentSource(generation)

                clearExternalHlsFallbackTrackState()
                clearLibVlcTrackState()

                val libVlcBackend = resolveLibVlcBackendForUri(uri, sanitizedHeaders)
                ensurePlayerInitialized(libVlcBackend)
                lifecycle.ensureCurrentSource(generation)

                var playbackUri = uri
                var playbackHeaders = sanitizedHeaders
                if (libVlcBackend != null) {
                    playbackUri = prepareLibVlcPlayback(uri, sanitizedHeaders, libVlcBackend.renderMode)
                }

                val startPlayback = initializePlayerState == InitialPlayerState.PLAY
                val shouldDeferNativePlayback = shouldDeferLibVlcNativeStart(startPlayback, libVlcBackend)
                var result =
                    openMediaUri(
                        playbackUri,
                        playbackHeaders,
                        startLibVlcPlayback = startPlayback && !shouldDeferNativePlayback,
                    )
                if (libVlcBackend == null && !result && shouldRetryWithExternalHlsFallback(uri, sanitizedHeaders)) {
                    linuxLogger.d { "Native GStreamer open failed; retrying through external HLS fallback" }
                    closeExternalHlsFallback()
                    clearExternalHlsFallbackTrackState()
                    val fallbackUri = prepareExternalHlsPlayback(uri, sanitizedHeaders)
                    playbackHeaders = emptyMap()
                    withContext(Dispatchers.Main) { error = null }
                    result = openMediaUri(fallbackUri, playbackHeaders)
                }
                lifecycle.ensureCurrentSource(generation)

                if (result) {
                    // Update frame rate from native layer
                    updateFrameRateInfo()
                    updateMetadata()
                    lifecycle.ensureCurrentSource(generation)

                    if (surfaceWidth > 0 && surfaceHeight > 0) {
                        applyOutputScaling()
                    }

                    withContext(Dispatchers.Main) {
                        if (!lifecycle.isCurrentSource(generation)) return@withContext
                        hasMedia = true
                        updateProjectionRenderingInfo()
                        isLoading = false
                        isPlaying = startPlayback
                    }
                    lifecycle.ensureCurrentSource(generation)

                    startFrameUpdates()
                    updateFrameAsync()
                    startBufferingCheck()

                    if (libVlcBackend != null) {
                        refreshLibVlcRuntimeTracksIfNeeded()
                        applyLibVlcSelectedTracks()
                    }

                    if (isPlaying && !shouldDeferNativePlayback) {
                        playInBackground()
                    } else if (libVlcBackendActive) {
                        pauseInBackground()
                    }
                } else {
                    linuxLogger.e { "Failed to open URI" }
                    closeExternalHlsFallback()
                    clearExternalHlsFallbackTrackState()
                    clearLibVlcTrackState()
                    withContext(Dispatchers.Main) {
                        isLoading = false
                        error = VideoPlayerError.SourceError("Failed to open media source")
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                linuxLogger.e { "openUri() - Exception: ${e.message}" }
                handleError(e)
            }
        }
    }

    private fun shouldDeferLibVlcNativeStart(
        startPlayback: Boolean,
        libVlcBackend: LinuxResolvedLibVlcBackend?,
    ): Boolean =
        startPlayback &&
            libVlcBackend?.renderMode == LinuxLibVlcRenderMode.NATIVE_VIEW &&
            !libVlcNativeSurfaceAttached

    override fun openFile(
        file: PlatformFile,
        initializePlayerState: InitialPlayerState,
    ) {
        lifecycle.ensureUsable()
        openUri(file.file.path, initializePlayerState)
    }

    private suspend fun cleanupCurrentPlayback() {
        pauseInBackground()
        cancelAndResetPlayerScope(recreate = true)

        withContext(frameDispatcher) {
            try {
                synchronized(nativeInstanceLock) {
                    val ptrToDispose = playerPtrAtomic.getAndSet(0L)
                    if (ptrToDispose != 0L) {
                        disposeNativePlayer(ptrToDispose)
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                linuxLogger.e { "Error disposing player: ${e.message}" }
            }
        }
        nativeBackendUsesLibVlc = false
        nativeBackendLibVlcRenderMode = null
        libVlcNativeSurfaceAttached = false
        closeExternalHlsFallback()
        clearLibVlcTrackState()
    }

    private suspend fun cancelAndResetPlayerScope(recreate: Boolean) {
        playerScope.coroutineContext[Job]?.cancelAndJoin()
        frameUpdateJob = null
        bufferingCheckJob = null
        seekInProgress = false
        targetSeekTime = null
        if (recreate && !lifecycle.isDisposed) {
            playerScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        }
    }

    private suspend fun ensurePlayerInitialized(libVlcBackend: LinuxResolvedLibVlcBackend? = null) {
        if (!playerScope.isActive) {
            playerScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        }

        val wantsLibVlc = libVlcBackend != null
        val wantsLibVlcRenderMode = libVlcBackend?.renderMode
        val needsBackendReplacement =
            synchronized(nativeInstanceLock) {
                playerPtr != 0L &&
                    (
                        nativeBackendUsesLibVlc != wantsLibVlc ||
                            (wantsLibVlc && nativeBackendLibVlcRenderMode != wantsLibVlcRenderMode)
                    )
            }
        if (needsBackendReplacement) {
            cancelAndResetPlayerScope(recreate = true)
            withContext(frameDispatcher) {
                synchronized(nativeInstanceLock) {
                    val ptrToDispose = playerPtrAtomic.getAndSet(0L)
                    if (ptrToDispose != 0L) {
                        disposeNativePlayer(ptrToDispose, wasLibVlc = nativeBackendUsesLibVlc)
                    }
                    nativeBackendUsesLibVlc = false
                    nativeBackendLibVlcRenderMode = null
                }
            }
        }

        if (synchronized(nativeInstanceLock) { playerPtr == 0L }) {
            val ptr =
                if (libVlcBackend != null) {
                    LinuxNativeBridge.nCreateLibVlcPlayer(
                        libVlcBackend.installation.libVlcPath,
                        libVlcBackend.installation.pluginPath,
                        libVlcBackend.renderMode == LinuxLibVlcRenderMode.NATIVE_VIEW,
                    )
                } else {
                    LinuxNativeBridge.nCreatePlayer()
                }
            if (ptr != 0L) {
                val coroutineIsActive = currentCoroutineContext().isActive
                val installed =
                    synchronized(nativeInstanceLock) {
                        if (lifecycle.isDisposed || !coroutineIsActive) {
                            false
                        } else if (playerPtrAtomic.compareAndSet(0L, ptr)) {
                            nativeBackendUsesLibVlc = wantsLibVlc
                            nativeBackendLibVlcRenderMode = wantsLibVlcRenderMode
                            true
                        } else {
                            false
                        }
                    }
                if (!installed) {
                    disposeNativePlayer(ptr, wasLibVlc = wantsLibVlc)
                } else {
                    applyVolume()
                    applyPlaybackSpeed()
                }
            } else {
                throw IllegalStateException("Failed to create native player")
            }
        }
    }

    private suspend fun openMediaUri(
        uri: String,
        requestHeaders: Map<String, String>,
        startLibVlcPlayback: Boolean = true,
    ): Boolean {
        val ptr = playerPtr
        if (ptr == 0L) return false

        if (!checkExistsIfLocalFile(uri)) {
            setPlayerError(VideoPlayerError.SourceError("File not found: $uri"))
            return false
        }

        return try {
            val headerLines = requestHeaders.requestHeadersLineString()
            if (nativeBackendUsesLibVlc) {
                if (!LinuxNativeBridge.nOpenLibVlcUriWithHeaders(ptr, uri, headerLines, startLibVlcPlayback)) {
                    return false
                }
            } else if (headerLines.isBlank()) {
                LinuxNativeBridge.nOpenUri(ptr, uri)
            } else {
                LinuxNativeBridge.nOpenUriWithHeaders(ptr, uri, headerLines)
            }
            pollDimensionsUntilReady(ptr)
            updateMetadata()
            true
        } catch (e: Exception) {
            linuxLogger.e { "Failed to open URI: ${e.message}" }
            setPlayerError(VideoPlayerError.SourceError("Error opening media: ${e.message}"))
            false
        }
    }

    private fun disposeNativePlayer(
        ptr: Long,
        wasLibVlc: Boolean = nativeBackendUsesLibVlc,
    ) {
        if (wasLibVlc) {
            LinuxNativeBridge.nDisposeLibVlcPlayer(ptr)
        } else {
            LinuxNativeBridge.nDisposePlayer(ptr)
        }
    }

    private suspend fun shouldRetryWithExternalHlsFallback(
        uri: String,
        requestHeaders: Map<String, String>,
    ): Boolean =
        playbackOptions.desktopVideoBackend == DesktopVideoBackend.AUTO &&
            !ExternalHlsFallbackSupport.isDisabled() &&
            ExternalHlsFallbackSupport.needsContainerFallback(uri, requestHeaders)

    private suspend fun resolveLibVlcBackendForUri(
        uri: String,
        requestHeaders: Map<String, String>,
    ): LinuxResolvedLibVlcBackend? {
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
            (forcedDesktopBackend ?: linuxFallbackBackendProperty()).lowercase()

        return when (configured) {
            "platform", "gstreamer" -> null
            "libvlc" ->
                ExternalVlcLocator.findLibVlc()?.let {
                    LinuxResolvedLibVlcBackend(it, LinuxLibVlcRenderMode.MEMORY)
                }
                    ?: throw missingLibVlcBackendException()
            "auto" ->
                ExternalVlcLocator.findLibVlc()?.let {
                    LinuxResolvedLibVlcBackend(it, LinuxLibVlcRenderMode.MEMORY)
                }
            "libvlc-native-view", "libvlc-native", "libvlc-view", "libvlc-xwindow", "libvlc-x11" -> {
                ensureLinuxNativeViewX11DisplayAvailable()
                ExternalVlcLocator.findLibVlc()?.let {
                    LinuxResolvedLibVlcBackend(it, LinuxLibVlcRenderMode.NATIVE_VIEW)
                } ?: throw missingLibVlcBackendException()
            }
            "libvlc-wayland", "wayland" -> unsupportedLibVlcWaylandBackend()
            "ffmpeg", "vlc" -> null
            else -> null
        }
    }

    private fun linuxFallbackBackendProperty(): String =
        System.getProperty("composemediaplayer.linux.fallbackBackend")
            ?: System.getProperty("composemediaplayer.fallbackBackend")
            ?: System.getenv("COMPOSE_MEDIA_PLAYER_LINUX_FALLBACK_BACKEND")
            ?: System.getenv("COMPOSE_MEDIA_PLAYER_FALLBACK_BACKEND")
            ?: System.getProperty("composemediaplayer.hlsFallbackBackend")
            ?: System.getenv("COMPOSE_MEDIA_PLAYER_HLS_FALLBACK_BACKEND")
            ?: "auto"

    private fun missingLibVlcBackendException(): UnsupportedOperationException =
        UnsupportedOperationException(
            "The Linux libVLC backend was requested, but no compatible libVLC installation was found. " +
                "Install VLC/libVLC for ${ExternalVlcLocator.currentProcessArchitecture() ?: "the current"} " +
                "JVM architecture or set composemediaplayer.libvlc and composemediaplayer.libvlc.plugins. " +
                "ComposeMediaPlayer does not bundle or link VLC.",
        )

    private fun ensureLinuxNativeViewX11DisplayAvailable() {
        if (!System.getenv("DISPLAY").isNullOrBlank()) return
        throw UnsupportedOperationException(
            "The Linux libVLC native-view backend currently requires an X11/XWayland DISPLAY. " +
                "This backend embeds VLC through an AWT/JAWT X11 drawable and libvlc_media_player_set_xwindow; " +
                "native Wayland wl_surface embedding is not available in the supported libVLC API. " +
                "DISPLAY is empty; WAYLAND_DISPLAY=${System.getenv("WAYLAND_DISPLAY") ?: "<unset>"}, " +
                "XDG_SESSION_TYPE=${System.getenv("XDG_SESSION_TYPE") ?: "<unset>"}.",
        )
    }

    private fun unsupportedLibVlcWaylandBackend(): LinuxResolvedLibVlcBackend =
        throw UnsupportedOperationException(
            "The Linux libVLC Wayland native-view backend is not implemented. " +
                "Use libvlc-native-view with XWayland/X11 for direct libVLC rendering, " +
                "or use the platform/memory backend until a stable libVLC wl_surface embedding API is available.",
        )

    private suspend fun prepareLibVlcPlayback(
        uri: String,
        requestHeaders: Map<String, String>,
        renderMode: LinuxLibVlcRenderMode,
    ): String {
        libVlcBackendActive = true
        libVlcSourceUri = uri
        withContext(Dispatchers.Main) {
            libVlcNativeSurfaceRequested = renderMode == LinuxLibVlcRenderMode.NATIVE_VIEW
            renderingInfo.update(
                backend = libVlcBackendLabel(renderMode),
                container = "Source through user-installed libVLC",
                videoDecoder = "libVLC",
                videoRenderer = libVlcVideoRenderer(renderMode),
                audioRenderer = "libVLC / Linux audio output",
                subtitleRenderer = null,
                subtitleSource = null,
                notes = libVlcRenderingNotes(renderMode),
            )
        }
        libVlcNativeSurfaceAttached = false
        val trackInfo = withContext(Dispatchers.IO) { JvmLibVlcMediaProbe.probe(uri, requestHeaders) }
        libVlcTrackInfo = trackInfo
        updateLibVlcTracks(trackInfo)
        return uri
    }

    private fun libVlcBackendLabel(renderMode: LinuxLibVlcRenderMode): String =
        when (renderMode) {
            LinuxLibVlcRenderMode.MEMORY -> "libVLC memory backend"
            LinuxLibVlcRenderMode.NATIVE_VIEW -> "libVLC native-view backend"
        }

    private fun libVlcVideoRenderer(renderMode: LinuxLibVlcRenderMode): String =
        when (renderMode) {
            LinuxLibVlcRenderMode.MEMORY ->
                projection.jvmCanvasRendererLabel(
                    baseRenderer = "libVLC vmem -> Compose Canvas (Skia)",
                    textureCrop = projectionTextureCrop,
                )
            LinuxLibVlcRenderMode.NATIVE_VIEW -> "libVLC native X11/XWayland xwindow"
        }

    private fun libVlcRenderingNotes(renderMode: LinuxLibVlcRenderMode): String =
        when (renderMode) {
            LinuxLibVlcRenderMode.MEMORY ->
                "VLC is loaded dynamically from the user's installation; frames are copied into Compose SDR."
            LinuxLibVlcRenderMode.NATIVE_VIEW ->
                "Best-effort native HDR-preserving path; actual HDR output depends on VLC, Linux compositor, GPU, and display settings. Compose controls use a separate overlay window."
        }

    private suspend fun updateLibVlcTracks(trackInfo: JvmLibVlcTrackInfo) {
        withContext(Dispatchers.Main) {
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
    }

    private suspend fun refreshLibVlcRuntimeTracksIfNeeded() {
        val ptr = playerPtr
        val currentInfo = libVlcTrackInfo ?: JvmLibVlcTrackInfo()
        if (ptr == 0L || currentInfo.audioStreams.isNotEmpty() || currentInfo.subtitleStreams.isNotEmpty()) return

        repeat(12) {
            val runtimeAudioTracks =
                parseLibVlcRuntimeTrackDescriptions(LinuxNativeBridge.nGetLibVlcAudioTrackDescriptions(ptr))
            val runtimeSubtitleTracks =
                parseLibVlcRuntimeTrackDescriptions(LinuxNativeBridge.nGetLibVlcSubtitleTrackDescriptions(ptr))

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

    private fun parseLibVlcRuntimeTrackDescriptions(raw: String?): List<LinuxLibVlcRuntimeTrackDescription> =
        raw
            ?.lineSequence()
            ?.mapNotNull { line ->
                val ordinal = line.substringBefore('\t').toIntOrNull() ?: return@mapNotNull null
                val label = line.substringAfter('\t', missingDelimiterValue = "").trim()
                LinuxLibVlcRuntimeTrackDescription(ordinal = ordinal, label = label)
            }?.toList()
            ?: emptyList()

    private suspend fun clearLibVlcTrackState() {
        libVlcBackendActive = false
        withContext(Dispatchers.Main) {
            libVlcNativeSurfaceRequested = false
        }
        libVlcNativeSurfaceAttached = false
        libVlcSourceUri = null
        libVlcTrackInfo = null
        libVlcSelectedAudioStreamIndex = null
        libVlcSelectedSubtitleStreamIndex = null
        withContext(Dispatchers.Main) {
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
    }

    internal fun shouldUseLibVlcNativeSurface(): Boolean =
        !lifecycle.isDisposed &&
            libVlcNativeSurfaceRequested &&
            libVlcBackendActive &&
            nativeBackendUsesLibVlc &&
            nativeBackendLibVlcRenderMode == LinuxLibVlcRenderMode.NATIVE_VIEW

    internal fun attachLibVlcNativeComponent(component: Component): Boolean {
        synchronized(nativeInstanceLock) {
            if (!shouldUseLibVlcNativeSurface()) return false
            val ptr = playerPtr
            if (ptr == 0L) return false

            return runCatching {
                val attached = LinuxNativeBridge.nAttachLibVlcNativeView(ptr, component)
                libVlcNativeSurfaceAttached = attached
                if (attached && isPlaying) {
                    nativePlay(ptr)
                    startFrameUpdates()
                    startBufferingCheck()
                }
                attached
            }.getOrElse { e ->
                linuxLogger.e { "Failed to attach Linux libVLC native surface: ${e.message}" }
                false
            }
        }
    }

    internal fun detachLibVlcNativeComponent(component: Component) {
        synchronized(nativeInstanceLock) {
            val ptr = playerPtr
            if (ptr == 0L) return
            runCatching {
                LinuxNativeBridge.nDetachLibVlcNativeView(ptr, component)
                libVlcNativeSurfaceAttached = false
            }.onFailure { e ->
                linuxLogger.e { "Failed to detach Linux libVLC native surface: ${e.message}" }
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
        val ptr = playerPtr
        if (ptr == 0L) return

        libVlcSelectedAudioStreamIndex
            ?.let { streamIndex -> info.audioStreams.firstOrNull { it.streamIndex == streamIndex }?.ordinal }
            ?.let { ordinal -> LinuxNativeBridge.nSelectLibVlcAudioTrack(ptr, ordinal) }

        val selectedSubtitleOrdinal =
            libVlcSelectedSubtitleStreamIndex
                ?.let { streamIndex -> info.subtitleStreams.firstOrNull { it.streamIndex == streamIndex }?.ordinal }

        if (selectedSubtitleOrdinal != null) {
            LinuxNativeBridge.nSelectLibVlcSubtitleTrack(ptr, selectedSubtitleOrdinal)
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

    private suspend fun clearExternalHlsFallbackTrackState() {
        externalHlsSelectedAudioStreamIndex = null
        externalHlsSelectedSubtitleStreamIndex = null
        externalHlsPlaybackOffsetSeconds = 0.0
        withContext(Dispatchers.Main) {
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
    }

    private suspend fun updateExternalHlsFallbackTracks(hlsSource: HlsFallbackSource) {
        val previousSubtitleId = currentSubtitleTrack?.id
        withContext(Dispatchers.Main) {
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
    }

    private suspend fun pollDimensionsUntilReady(
        ptr: Long,
        maxAttempts: Int = 20,
    ) {
        for (attempt in 1..maxAttempts) {
            val width = nativeFrameWidth(ptr)
            val height = nativeFrameHeight(ptr)
            if (width > 0 && height > 0) {
                linuxLogger.d { "Dimensions validated (w=$width, h=$height) after $attempt attempts" }
                return
            }
            linuxLogger.d { "Dimensions not ready yet (attempt $attempt/$maxAttempts)" }
            delay(250.milliseconds)
        }
        linuxLogger.e { "Unable to retrieve valid dimensions after $maxAttempts attempts" }
    }

    private suspend fun updateFrameRateInfo() {
        val ptr = playerPtr
        if (ptr == 0L) return
        try {
            captureFrameRate = nativeFrameRate(ptr)
            linuxLogger.d { "Frame rate: $captureFrameRate" }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            linuxLogger.e { "Error updating frame rate: ${e.message}" }
        }
    }

    private suspend fun updateMetadata() {
        val ptr = playerPtr
        if (ptr == 0L) return

        try {
            val probedVideoWidth = libVlcTrackInfo?.videoWidth
            val probedVideoHeight = libVlcTrackInfo?.videoHeight
            val width = probedVideoWidth ?: nativeFrameWidth(ptr)
            val height = probedVideoHeight ?: nativeFrameHeight(ptr)
            val durationSeconds =
                externalHlsFallbackDurationSeconds
                    ?.takeIf { it > 0.0 }
                    ?: libVlcTrackInfo?.durationSeconds
                    ?: nativeDuration(ptr)
            val duration = durationSeconds.secondsAsDuration()
            val frameRate = nativeFrameRate(ptr)
            val newAspectRatio =
                if (width > 0 && height > 0) {
                    width.toFloat() / height.toFloat()
                } else {
                    _aspectRatio.value
                }

            val title = if (nativeBackendUsesLibVlc) null else LinuxNativeBridge.nGetVideoTitle(ptr)
            val bitrate = if (nativeBackendUsesLibVlc) 0 else LinuxNativeBridge.nGetVideoBitrate(ptr)
            val mimeType = if (nativeBackendUsesLibVlc) null else LinuxNativeBridge.nGetVideoMimeType(ptr)
            val audioChannels = if (nativeBackendUsesLibVlc) 0 else LinuxNativeBridge.nGetAudioChannels(ptr)
            val audioSampleRate = if (nativeBackendUsesLibVlc) 0 else LinuxNativeBridge.nGetAudioSampleRate(ptr)

            withContext(Dispatchers.Main) {
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
                _aspectRatio.value = newAspectRatio
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            linuxLogger.e { "Error updating metadata: ${e.message}" }
        }
    }

    // --- Frame update loop ---

    private fun startFrameUpdates() {
        stopFrameUpdates()
        frameUpdateJob =
            playerScope.launch {
                while (isActive) {
                    ensureActive()
                    if (shouldUseLibVlcNativeSurface()) {
                        lastFrameUpdateTime = System.currentTimeMillis()
                        if (isLoading) {
                            withContext(Dispatchers.Main) { isLoading = false }
                        }
                    } else {
                        updateFrameAsync()
                    }
                    if (!userDragging) {
                        updatePositionAsync()
                    }
                    delay(updateInterval)
                }
            }
    }

    private fun stopFrameUpdates() {
        frameUpdateJob?.cancel()
        frameUpdateJob = null
    }

    private fun startBufferingCheck() {
        stopBufferingCheck()
        bufferingCheckJob =
            playerScope.launch {
                while (isActive) {
                    ensureActive()
                    checkBufferingState()
                    delay(bufferingCheckInterval)
                }
            }
    }

    private suspend fun checkBufferingState() {
        if (shouldUseLibVlcNativeSurface()) return
        if (isPlaying && !isLoading) {
            val timeSinceLastFrame = System.currentTimeMillis() - lastFrameUpdateTime
            if (timeSinceLastFrame > bufferingTimeoutThreshold) {
                withContext(Dispatchers.Main) { isLoading = true }
            }
        }
    }

    private fun stopBufferingCheck() {
        bufferingCheckJob?.cancel()
        bufferingCheckJob = null
    }

    private suspend fun updateFrameAsync() {
        if (shouldUseLibVlcNativeSurface()) return
        withContext(frameDispatcher) {
            try {
                val ptr = playerPtr
                if (ptr == 0L) return@withContext

                val outInfo = IntArray(3)
                val frameAddress = nativeLockFrame(ptr, outInfo)
                if (frameAddress == 0L) return@withContext

                var framePublished = false
                try {
                    val width = outInfo[0]
                    val height = outInfo[1]
                    val srcRowBytes = outInfo[2]

                    if (width <= 0 || height <= 0 || srcRowBytes < width * 4) {
                        return@withContext
                    }

                    withContext(Dispatchers.Default) {
                        val frameSizeBytes = srcRowBytes.toLong() * height.toLong()
                        val srcBuf =
                            LinuxNativeBridge.nWrapPointer(frameAddress, frameSizeBytes)
                                ?: return@withContext

                        // Allocate/reuse double-buffered bitmaps
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

                        val targetBitmap = (if (nextSkiaBitmapA) skiaBitmapA else skiaBitmapB) ?: return@withContext
                        nextSkiaBitmapA = !nextSkiaBitmapA

                        val pixmap = targetBitmap.peekPixels() ?: return@withContext
                        val pixelsAddr = pixmap.addr
                        if (pixelsAddr == 0L) return@withContext

                        // Native-to-native copy: frame buffer -> Skia bitmap pixels
                        srcBuf.rewind()
                        val destRowBytes = pixmap.rowBytes
                        val destSizeBytes = destRowBytes.toLong() * height.toLong()
                        val destBuf =
                            LinuxNativeBridge.nWrapPointer(pixelsAddr, destSizeBytes)
                                ?: return@withContext
                        copyBgraFrame(srcBuf, destBuf, width, height, destRowBytes)

                        _currentFrameState.value = targetBitmap.asComposeImageBitmap()
                        framePublished = true
                    }
                } finally {
                    nativeUnlockFrame(ptr)
                }

                if (framePublished) {
                    lastFrameUpdateTime = System.currentTimeMillis()
                    if (isLoading && !seekInProgress) {
                        withContext(Dispatchers.Main) { isLoading = false }
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                linuxLogger.e { "updateFrameAsync() - Exception: ${e.message}" }
            }
        }
    }

    private suspend fun updatePositionAsync() {
        if (!hasMedia || userDragging) return
        try {
            val duration = getDurationSafely()
            if (duration <= Duration.ZERO) return

            val current = getPositionSafely()

            withContext(Dispatchers.Main) {
                _currentTime.value = current
                _duration.value = duration
                _positionText.value = formatTime(current)
                _durationText.value = formatTime(duration)
            }

            val seekTarget = targetSeekTime
            if (seekInProgress && seekTarget != null) {
                if (abs(current.toSecondsDouble() - seekTarget.toSecondsDouble()) < 0.3) {
                    seekInProgress = false
                    targetSeekTime = null
                    withContext(Dispatchers.Main) { isLoading = false }
                }
            } else {
                val newSliderPos =
                    (current.toSecondsDouble() / duration.toSecondsDouble() * VideoPlayerState.SLIDER_SCALE)
                        .toFloat()
                        .coerceIn(0f, VideoPlayerState.SLIDER_SCALE)
                withContext(Dispatchers.Main) { sliderPos = newSliderPos }
            }

            checkLoopingAsync(current, duration)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            linuxLogger.e { "Error in updatePositionAsync: ${e.message}" }
        }
    }

    private suspend fun checkLoopingAsync(
        current: Duration,
        duration: Duration,
    ) {
        val ptr = playerPtr
        val ended = ptr != 0L && nativeConsumeDidPlayToEnd(ptr)
        if (!ended && (duration <= Duration.ZERO || current < duration - 500.milliseconds)) return

        if (loop) {
            seekToAsync(0f)
            onRestart?.invoke()
        } else {
            withContext(Dispatchers.Main) { isPlaying = false }
            pauseInBackground()
            onPlaybackEnded?.invoke()
        }
    }

    // --- Playback controls ---

    override fun play() {
        lifecycle.ensureUsable()
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

    private suspend fun playInBackground() {
        val ptr = playerPtr
        if (ptr == 0L) return
        try {
            if (shouldUseLibVlcNativeSurface() && !libVlcNativeSurfaceAttached) {
                withContext(Dispatchers.Main) { isPlaying = true }
                startFrameUpdates()
                startBufferingCheck()
                return
            }
            nativePlay(ptr)
            withContext(Dispatchers.Main) { isPlaying = true }
            startFrameUpdates()
            startBufferingCheck()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            linuxLogger.e { "Error in playInBackground: ${e.message}" }
            handleError(e)
        }
    }

    override fun pause() {
        lifecycle.ensureUsable()
        lifecycle.launchControlOperation { pauseInBackground() }
    }

    private suspend fun pauseInBackground() {
        val ptr = playerPtr
        if (ptr == 0L) return
        try {
            nativePause(ptr)
            withContext(Dispatchers.Main) {
                isPlaying = false
                isLoading = false
            }
            if (!shouldUseLibVlcNativeSurface()) {
                updateFrameAsync()
            }
            stopFrameUpdates()
            stopBufferingCheck()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            linuxLogger.e { "Error in pauseInBackground: ${e.message}" }
        }
    }

    override fun stop() {
        lifecycle.ensureUsable()
        lifecycle.launchSourceOperation { generation ->
            if (externalHlsFallback != null || libVlcBackendActive) {
                cleanupCurrentPlayback()
            } else {
                pauseInBackground()
                if (hasMedia) seekToAsync(0f, generation)
            }
            withContext(Dispatchers.Main) {
                hasMedia = false
                isLoading = false
                resetState()
            }
            clearExternalHlsFallbackTrackState()
            clearLibVlcTrackState()
        }
    }

    override fun releaseSource() {
        lifecycle.ensureUsable()
        lifecycle.launchSourceOperation(
            onScheduled = {
                lastUri = null
                lastRequestHeaders = emptyMap()
            },
        ) {
            cleanupCurrentPlayback()
            clearExternalHlsFallbackTrackState()
            clearLibVlcTrackState()
            resetState()
        }
    }

    override fun seekTo(time: Duration) {
        lifecycle.ensureUsable()
        lifecycle.launchSourceBoundControlOperation { generation ->
            delay(10.milliseconds) // Coalesce rapid seek events
            lifecycle.ensureCurrentSource(generation)
            seekToTimeAsync(time, generation)
        }
    }

    override fun seekToProgress(progress: Float) {
        seekTo(progress.coerceIn(0f, 1f) * VideoPlayerState.SLIDER_SCALE)
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun seekTo(value: Float) {
        lifecycle.ensureUsable()
        lifecycle.launchSourceBoundControlOperation { generation ->
            delay(10.milliseconds) // Coalesce rapid seek events
            lifecycle.ensureCurrentSource(generation)
            val duration = getDurationSafely()
            if (duration <= Duration.ZERO) {
                commitCurrentSourceOnMain(generation) { isLoading = false }
                return@launchSourceBoundControlOperation
            }
            val seekTime = duration * (value / VideoPlayerState.SLIDER_SCALE).toDouble().coerceIn(0.0, 1.0)
            seekToTimeAsync(seekTime, generation)
        }
    }

    private suspend fun seekToAsync(
        value: Float,
        sourceGeneration: Long? = null,
    ) {
        val duration = getDurationSafely()
        if (duration <= Duration.ZERO) {
            commitSeekStateOnMain(sourceGeneration) { isLoading = false }
            return
        }
        val seekTime = duration * (value / VideoPlayerState.SLIDER_SCALE).toDouble().coerceIn(0.0, 1.0)
        seekToTimeAsync(seekTime, sourceGeneration)
    }

    private suspend fun seekToTimeAsync(
        time: Duration,
        sourceGeneration: Long? = null,
    ) {
        if (!commitSeekStateOnMain(sourceGeneration) { isLoading = true }) return

        try {
            sourceGeneration?.let { lifecycle.ensureCurrentSource(it) }
            val duration = getDurationSafely()
            if (duration <= Duration.ZERO) {
                commitSeekStateOnMain(sourceGeneration) { isLoading = false }
                return
            }

            val seekTime =
                when {
                    time < Duration.ZERO -> Duration.ZERO
                    time > duration -> duration
                    else -> time
                }

            if (!commitSeekStateOnMain(sourceGeneration) {
                    seekInProgress = true
                    targetSeekTime = seekTime
                    sliderPos =
                        (seekTime.toSecondsDouble() / duration.toSecondsDouble() * VideoPlayerState.SLIDER_SCALE)
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
            nativeSeekTo(ptr, seekTime.toSecondsDouble())
            sourceGeneration?.let { lifecycle.ensureCurrentSource(it) }

            if (isPlaying) {
                nativePlay(ptr)
                delay(10.milliseconds)
                sourceGeneration?.let { lifecycle.ensureCurrentSource(it) }
                if (!shouldUseLibVlcNativeSurface()) {
                    updateFrameAsync()
                }
                playerScope.launch {
                    delay(300.milliseconds)
                    commitSeekStateOnMain(sourceGeneration) {
                        if (seekInProgress) {
                            seekInProgress = false
                            targetSeekTime = null
                            isLoading = false
                        }
                    }
                }
            } else {
                delay(50.milliseconds)
                sourceGeneration?.let { lifecycle.ensureCurrentSource(it) }
                if (!shouldUseLibVlcNativeSurface()) {
                    updateFrameAsync()
                }
                commitSeekStateOnMain(sourceGeneration) {
                    seekInProgress = false
                    targetSeekTime = null
                    isLoading = false
                }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            linuxLogger.e { "Error in seekToAsync: ${e.message}" }
            commitSeekStateOnMain(sourceGeneration) {
                isLoading = false
                seekInProgress = false
                targetSeekTime = null
            }
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

    override fun clearError() {
        lifecycle.ensureUsable()
        error = null
    }

    override fun toggleFullscreen() {
        lifecycle.ensureUsable()
        isFullscreen = !isFullscreen
    }

    override fun dispose() {
        val cleanupJob =
            lifecycle.dispose {
                cancelAndResetPlayerScope(recreate = false)
                val wasLibVlc = nativeBackendUsesLibVlc
                val ptrToDispose =
                    withContext(frameDispatcher) {
                        val ptr = synchronized(nativeInstanceLock) { playerPtrAtomic.getAndSet(0L) }
                        try {
                            skiaBitmapA?.close()
                            skiaBitmapB?.close()
                            skiaBitmapA = null
                            skiaBitmapB = null
                            skiaBitmapWidth = 0
                            skiaBitmapHeight = 0
                            nextSkiaBitmapA = true
                        } catch (e: Exception) {
                            linuxLogger.e { "Error releasing bitmaps: ${e.message}" }
                        }
                        ptr
                    }

                if (ptrToDispose != 0L) {
                    try {
                        synchronized(nativeInstanceLock) {
                            disposeNativePlayer(ptrToDispose, wasLibVlc = wasLibVlc)
                        }
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        linuxLogger.e { "Error disposing player: ${e.message}" }
                    }
                }

                closeExternalHlsFallback()
                nativeBackendUsesLibVlc = false
                nativeBackendLibVlcRenderMode = null
                libVlcBackendActive = false
                libVlcNativeSurfaceAttached = false
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

    private suspend fun commitCurrentSourceOnMain(
        generation: Long,
        block: () -> Unit,
    ): Boolean =
        withContext(Dispatchers.Main) {
            lifecycle.commitCurrentSource(generation, block)
        }

    // --- Track selection ---
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
                selectLibVlcAudioTrack(track, selectedLibVlcStreamIndex, generation)
            }
            return TrackSelectionResult.Selected(track.id)
        }

        val selectedStreamIndex =
            track
                ?.id
                ?.takeIf(ExternalHlsFallbackSupport::isExternalHlsAudioTrackId)
                ?.let(ExternalHlsFallbackSupport::externalHlsTrackStreamIndex)

        if (track != null && selectedStreamIndex != null) {
            lifecycle.launchSourceBoundControlOperation { generation ->
                switchExternalHlsAudioTrack(track, selectedStreamIndex, generation)
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

    override fun selectSubtitleTrack(track: SubtitleTrack?): TrackSelectionResult {
        lifecycle.ensureUsable()
        if (track == null && libVlcBackendActive) {
            lifecycle.launchSourceBoundControlOperation { generation ->
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
                ?.takeIf(::isLibVlcSubtitleTrackId)
                ?.let(::libVlcTrackStreamIndex)

        if (track != null && selectedLibVlcStreamIndex != null) {
            lifecycle.launchSourceBoundControlOperation { generation ->
                selectLibVlcSubtitleTrack(track, selectedLibVlcStreamIndex, generation)
            }
            return TrackSelectionResult.Selected(track.id)
        }

        val selectedStreamIndex =
            track
                ?.id
                ?.takeIf(ExternalHlsFallbackSupport::isExternalHlsSubtitleTrackId)
                ?.let(ExternalHlsFallbackSupport::externalHlsTrackStreamIndex)

        if (track != null && selectedStreamIndex != null) {
            lifecycle.launchSourceBoundControlOperation { generation ->
                switchExternalHlsSubtitleTrack(track, selectedStreamIndex, generation)
            }
            return TrackSelectionResult.Selected(track.id)
        }

        lifecycle.launchSourceBoundControlOperation { generation ->
            commitCurrentSourceOnMain(generation) {
                currentSubtitleTrack = track
                subtitlesEnabled = track != null
            }
        }
        return track.subtitleTrackSelectionResult()
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
        if (libVlcBackendActive && selectedTrack?.id?.let(::isLibVlcSubtitleTrackId) == true) {
            lifecycle.launchSourceBoundControlOperation { generation ->
                disableLibVlcSubtitles(generation)
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
        val ptr = playerPtr
        val applied = ordinal != null && ptr != 0L && LinuxNativeBridge.nSelectLibVlcAudioTrack(ptr, ordinal)
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

    private suspend fun selectLibVlcSubtitleTrack(
        track: SubtitleTrack,
        streamIndex: Int,
        generation: Long,
    ) {
        val ordinal =
            libVlcTrackInfo
                ?.subtitleStreams
                ?.firstOrNull { it.streamIndex == streamIndex }
                ?.ordinal
        val ptr = playerPtr
        val applied = ordinal != null && ptr != 0L && LinuxNativeBridge.nSelectLibVlcSubtitleTrack(ptr, ordinal)
        lifecycle.ensureCurrentSource(generation)
        commitCurrentSourceOnMain(generation) {
            if (applied) {
                libVlcSelectedSubtitleStreamIndex = streamIndex
                currentSubtitleTrack = track
                subtitlesEnabled = true
            } else {
                error = VideoPlayerError.CodecError("Failed to select libVLC subtitle track: ${track.id}")
            }
        }
    }

    private suspend fun disableLibVlcSubtitles(generation: Long) {
        val ptr = playerPtr
        val applied = ptr != 0L && LinuxNativeBridge.nDisableLibVlcSubtitles(ptr)
        lifecycle.ensureCurrentSource(generation)
        commitCurrentSourceOnMain(generation) {
            if (applied) {
                libVlcSelectedSubtitleStreamIndex = null
                subtitlesEnabled = false
                currentSubtitleTrack = null
            } else {
                error = VideoPlayerError.CodecError("Failed to disable libVLC subtitles")
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
            commitCurrentSourceOnMain(generation) { currentAudioTrack = track }
            return
        }
        if (externalHlsSelectedAudioStreamIndex == streamIndex) {
            commitCurrentSourceOnMain(generation) { currentAudioTrack = track }
            return
        }

        restartExternalHlsPlayback(
            sourceUri = sourceUri,
            selectedAudioStreamIndex = streamIndex,
            selectedSubtitleStreamIndex = externalHlsSelectedSubtitleStreamIndex,
            onSelectionApplied = {
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
            commitCurrentSourceOnMain(generation) {
                currentSubtitleTrack = track
                subtitlesEnabled = track != null
            }
            return
        }
        if (externalHlsSelectedSubtitleStreamIndex == streamIndex) {
            commitCurrentSourceOnMain(generation) {
                currentSubtitleTrack = track
                subtitlesEnabled = track != null
            }
            return
        }
        if (track != null && !ExternalHlsFallbackSupport.hasSubtitleRenderer()) {
            commitCurrentSourceOnMain(generation) {
                isLoading = false
                currentSubtitleTrack = null
                subtitlesEnabled = false
                error =
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
            onSelectionApplied = {
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
        onSelectionApplied: () -> Unit,
        failureMessage: String,
        generation: Long,
    ) {
        val shouldResumePlayback = isPlaying
        val restartPosition = getPositionSafely()
        val duration = getDurationSafely()
        val restartSliderPos =
            if (duration > Duration.ZERO) {
                (restartPosition.toSecondsDouble() / duration.toSecondsDouble() * VideoPlayerState.SLIDER_SCALE)
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
                    _positionText.value = formatTime(restartPosition)
                }
            if (!loadingCommitted) {
                return
            }

            cleanupCurrentPlayback()
            lifecycle.ensureCurrentSource(generation)
            ensurePlayerInitialized()
            lifecycle.ensureCurrentSource(generation)

            externalHlsSelectedAudioStreamIndex = selectedAudioStreamIndex
            externalHlsSelectedSubtitleStreamIndex = selectedSubtitleStreamIndex
            externalHlsPlaybackOffsetSeconds = restartPosition.toSecondsDouble()
            val playableUri = prepareExternalHlsPlayback(sourceUri, lastRequestHeaders)
            val opened = openMediaUri(playableUri, emptyMap())
            lifecycle.ensureCurrentSource(generation)
            if (!opened) {
                closeExternalHlsFallback()
                clearExternalHlsFallbackTrackState()
                commitCurrentSourceOnMain(generation) {
                    isLoading = false
                    error = VideoPlayerError.SourceError(failureMessage)
                }
                return
            }

            updateFrameRateInfo()
            updateMetadata()
            if (surfaceWidth > 0 && surfaceHeight > 0) {
                applyOutputScaling()
            }

            val selectionCommitted =
                commitCurrentSourceOnMain(generation) {
                    onSelectionApplied()
                    hasMedia = true
                    updateProjectionRenderingInfo()
                    isLoading = false
                    isPlaying = shouldResumePlayback
                }
            if (!selectionCommitted) {
                return
            }

            startFrameUpdates()
            updateFrameAsync()
            startBufferingCheck()

            if (shouldResumePlayback) {
                playInBackground()
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            linuxLogger.e { "restartExternalHlsPlayback() - Exception: ${e.message}" }
            closeExternalHlsFallback()
            clearExternalHlsFallbackTrackState()
            commitCurrentSourceOnMain(generation) {
                isLoading = false
                error = VideoPlayerError.SourceError("Error: ${e.message}")
            }
        }
    }

    // --- Output scaling ---

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

    private suspend fun applyOutputScaling() {
        val sw = surfaceWidth
        val sh = surfaceHeight
        if (sw <= 0 || sh <= 0) return
        val ptr = playerPtr
        if (ptr == 0L) return
        if (nativeBackendUsesLibVlc) return

        // Compute output dimensions that fit within the surface while preserving
        // the video's native aspect ratio. Passing the raw surface size would let
        // GStreamer stretch the frame to an arbitrary ratio.
        val videoRatio = _aspectRatio.value
        val surfaceRatio = sw.toFloat() / sh.toFloat()

        val (outW, outH) =
            if (videoRatio > surfaceRatio) {
                // Video is wider than surface → fit to width
                sw to (sw / videoRatio).toInt().coerceAtLeast(1)
            } else {
                // Video is taller than surface → fit to height
                (sh * videoRatio).toInt().coerceAtLeast(1) to sh
            }

        LinuxNativeBridge.nSetOutputSize(ptr, outW, outH)
    }

    // --- Internal helpers ---

    private fun nativePlay(ptr: Long) {
        if (nativeBackendUsesLibVlc) {
            LinuxNativeBridge.nPlayLibVlc(ptr)
        } else {
            LinuxNativeBridge.nPlay(ptr)
        }
    }

    private fun nativePause(ptr: Long) {
        if (nativeBackendUsesLibVlc) {
            LinuxNativeBridge.nPauseLibVlc(ptr)
        } else {
            LinuxNativeBridge.nPause(ptr)
        }
    }

    private fun nativeSetVolume(
        ptr: Long,
        volume: Float,
    ) {
        if (nativeBackendUsesLibVlc) {
            LinuxNativeBridge.nSetLibVlcVolume(ptr, volume)
        } else {
            LinuxNativeBridge.nSetVolume(ptr, volume)
        }
    }

    private fun nativeSetPlaybackSpeed(
        ptr: Long,
        speed: Float,
    ) {
        if (nativeBackendUsesLibVlc) {
            LinuxNativeBridge.nSetLibVlcPlaybackSpeed(ptr, speed)
        } else {
            LinuxNativeBridge.nSetPlaybackSpeed(ptr, speed)
        }
    }

    private fun nativeLockFrame(
        ptr: Long,
        outInfo: IntArray,
    ): Long =
        if (nativeBackendUsesLibVlc) {
            LinuxNativeBridge.nLockLibVlcFrame(ptr, outInfo)
        } else {
            LinuxNativeBridge.nLockFrame(ptr, outInfo)
        }

    private fun nativeUnlockFrame(ptr: Long) {
        if (nativeBackendUsesLibVlc) {
            LinuxNativeBridge.nUnlockLibVlcFrame(ptr)
        } else {
            LinuxNativeBridge.nUnlockFrame(ptr)
        }
    }

    private fun nativeFrameWidth(ptr: Long): Int =
        if (nativeBackendUsesLibVlc) {
            LinuxNativeBridge.nGetLibVlcFrameWidth(ptr)
        } else {
            LinuxNativeBridge.nGetFrameWidth(ptr)
        }

    private fun nativeFrameHeight(ptr: Long): Int =
        if (nativeBackendUsesLibVlc) {
            LinuxNativeBridge.nGetLibVlcFrameHeight(ptr)
        } else {
            LinuxNativeBridge.nGetFrameHeight(ptr)
        }

    private fun nativeFrameRate(ptr: Long): Float =
        if (nativeBackendUsesLibVlc) {
            LinuxNativeBridge.nGetLibVlcFrameRate(ptr)
        } else {
            LinuxNativeBridge.nGetFrameRate(ptr)
        }

    private fun nativeDuration(ptr: Long): Double =
        if (nativeBackendUsesLibVlc) {
            LinuxNativeBridge.nGetLibVlcVideoDuration(ptr)
        } else {
            LinuxNativeBridge.nGetVideoDuration(ptr)
        }

    private fun nativeCurrentTime(ptr: Long): Double =
        if (nativeBackendUsesLibVlc) {
            LinuxNativeBridge.nGetLibVlcCurrentTime(ptr)
        } else {
            LinuxNativeBridge.nGetCurrentTime(ptr)
        }

    private fun nativeSeekTo(
        ptr: Long,
        seconds: Double,
    ) {
        if (nativeBackendUsesLibVlc) {
            LinuxNativeBridge.nSeekLibVlcTo(ptr, seconds)
        } else {
            LinuxNativeBridge.nSeekTo(ptr, seconds)
        }
    }

    private fun nativeConsumeDidPlayToEnd(ptr: Long): Boolean =
        if (nativeBackendUsesLibVlc) {
            LinuxNativeBridge.nConsumeLibVlcDidPlayToEnd(ptr)
        } else {
            LinuxNativeBridge.nConsumeDidPlayToEnd(ptr)
        }

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

    private fun setPlayerError(error: VideoPlayerError) {
        runBlocking {
            withContext(Dispatchers.Main) {
                isLoading = false
                this@LinuxVideoPlayerState.error = error
            }
        }
    }

    private suspend fun handleError(e: Exception) {
        withContext(Dispatchers.Main) {
            isLoading = false
            error = VideoPlayerError.SourceError("Error: ${e.message}")
        }
    }

    private suspend fun getPositionSafely(): Duration {
        val ptr = playerPtr
        if (ptr == 0L) return Duration.ZERO
        return try {
            val current = nativeCurrentTime(ptr)
            val fallbackDuration = externalHlsFallbackDurationSeconds
            if (fallbackDuration != null && fallbackDuration > 0.0) {
                (externalHlsPlaybackOffsetSeconds + current)
                    .coerceIn(0.0, fallbackDuration)
                    .secondsAsDuration()
            } else {
                current.secondsAsDuration()
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Duration.ZERO
        }
    }

    private suspend fun getDurationSafely(): Duration {
        externalHlsFallbackDurationSeconds?.let { duration ->
            if (duration > 0.0) return duration.secondsAsDuration()
        }
        val ptr = playerPtr
        if (ptr == 0L) return Duration.ZERO
        return try {
            val durationSeconds = libVlcTrackInfo?.durationSeconds ?: nativeDuration(ptr)
            durationSeconds.secondsAsDuration()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Duration.ZERO
        }
    }

    private suspend fun applyVolume() {
        val ptr = playerPtr
        if (ptr != 0L) {
            try {
                nativeSetVolume(ptr, _volumeState.value)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
            }
        }
    }

    private suspend fun applyPlaybackSpeed() {
        val ptr = playerPtr
        if (ptr != 0L) {
            try {
                nativeSetPlaybackSpeed(ptr, _playbackSpeedState.value)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
            }
        }
    }
}
