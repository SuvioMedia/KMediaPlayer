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
import io.github.kdroidfilter.composemediaplayer.VideoPlayerError
import io.github.kdroidfilter.composemediaplayer.VideoPlayerState
import io.github.kdroidfilter.composemediaplayer.requestHeadersLineString
import io.github.kdroidfilter.composemediaplayer.sanitizedRequestHeaders
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
import java.io.Closeable
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

internal val linuxLogger = TaggedLogger("LinuxVideoPlayerState")

private data class LinuxResolvedLibVlcBackend(
    val installation: JvmLibVlcInstallation,
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
class LinuxVideoPlayerState : VideoPlayerState {
    // Native player pointer (AtomicLong for lock-free reads from the frame hot path)
    private val playerPtrAtomic = AtomicLong(0L)
    private val playerPtr: Long get() = playerPtrAtomic.get()

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

    // Background worker scopes and jobs
    private val ioScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
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
    override val capabilities: PlayerCapabilities =
        PlayerCapabilities(
            supportsMkv = true,
        )
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
            val newValue = value.coerceIn(0f, 1f)
            if (_volumeState.value != newValue) {
                _volumeState.value = newValue
                ioScope.launch { applyVolume() }
            }
        }

    // Playback speed
    private val _playbackSpeedState = mutableStateOf(1.0f)
    override var playbackSpeed: Float
        get() = _playbackSpeedState.value
        set(value) {
            val newValue = value.coerceIn(VideoPlayerState.MIN_PLAYBACK_SPEED, VideoPlayerState.MAX_PLAYBACK_SPEED)
            if (_playbackSpeedState.value != newValue) {
                _playbackSpeedState.value = newValue
                ioScope.launch { applyPlaybackSpeed() }
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
        ioScope.launch {
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

    private suspend fun initPlayer() =
        ioScope
            .launch {
                linuxLogger.d { "initPlayer() - Creating native player" }
                try {
                    val ptr = LinuxNativeBridge.nCreatePlayer()
                    if (ptr != 0L) {
                        playerPtrAtomic.set(ptr)
                        nativeBackendUsesLibVlc = false
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
            }.join()

    private fun checkExistsIfLocalFile(uri: String): Boolean {
        val schemeDelimiter = uri.indexOf("://")
        val scheme = if (schemeDelimiter >= 0) uri.substring(0, schemeDelimiter) else ""
        return when (scheme) {
            "", "file" -> {
                val path = if (scheme == "file") uri.removePrefix("file://") else uri
                File(path).exists()
            }
            else -> true
        }
    }

    override fun openUri(
        uri: String,
        initializeplayerState: InitialPlayerState,
        requestHeaders: Map<String, String>,
    ) {
        linuxLogger.d { "openUri() - Opening URI: $uri" }
        val sanitizedHeaders = requestHeaders.sanitizedRequestHeaders()
        lastUri = uri
        lastRequestHeaders = sanitizedHeaders

        if (!checkExistsIfLocalFile(uri)) {
            linuxLogger.e { "File does not exist: $uri" }
            setPlayerError(VideoPlayerError.SourceError("File not found: $uri"))
            return
        }

        ioScope.launch {
            withContext(Dispatchers.Main) {
                isLoading = true
                error = null
                playbackSpeed = 1.0f
            }

            try {
                if (hasMedia || externalHlsFallback != null || libVlcBackendActive) {
                    cleanupCurrentPlayback()
                }

                clearExternalHlsFallbackTrackState()
                clearLibVlcTrackState()

                val libVlcBackend = resolveLibVlcBackendForUri(uri, sanitizedHeaders)
                ensurePlayerInitialized(libVlcBackend)

                var playbackUri = uri
                var playbackHeaders = sanitizedHeaders
                if (libVlcBackend != null) {
                    playbackUri = prepareLibVlcPlayback(uri, sanitizedHeaders)
                }

                var result = openMediaUri(playbackUri, playbackHeaders)
                if (libVlcBackend == null && !result && shouldRetryWithExternalHlsFallback(uri, sanitizedHeaders)) {
                    linuxLogger.d { "Native GStreamer open failed; retrying through external HLS fallback" }
                    closeExternalHlsFallback()
                    clearExternalHlsFallbackTrackState()
                    val fallbackUri = prepareExternalHlsPlayback(uri, sanitizedHeaders)
                    playbackHeaders = emptyMap()
                    withContext(Dispatchers.Main) { error = null }
                    result = openMediaUri(fallbackUri, playbackHeaders)
                }

                if (result) {
                    // Update frame rate from native layer
                    updateFrameRateInfo()
                    updateMetadata()

                    if (surfaceWidth > 0 && surfaceHeight > 0) {
                        applyOutputScaling()
                    }

                    withContext(Dispatchers.Main) {
                        hasMedia = true
                        isLoading = false
                        isPlaying = initializeplayerState == InitialPlayerState.PLAY
                    }

                    startFrameUpdates()
                    updateFrameAsync()
                    startBufferingCheck()

                    if (libVlcBackend != null) {
                        refreshLibVlcRuntimeTracksIfNeeded()
                        applyLibVlcSelectedTracks()
                    }

                    if (isPlaying) {
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

    override fun openFile(
        file: PlatformFile,
        initializeplayerState: InitialPlayerState,
    ) {
        openUri(file.file.path, initializeplayerState)
    }

    private suspend fun cleanupCurrentPlayback() {
        pauseInBackground()
        stopFrameUpdates()
        stopBufferingCheck()

        val ptrToDispose =
            withContext(frameDispatcher) {
                playerPtrAtomic.getAndSet(0L)
            }

        if (ptrToDispose != 0L) {
            try {
                disposeNativePlayer(ptrToDispose)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                linuxLogger.e { "Error disposing player: ${e.message}" }
            }
        }
        nativeBackendUsesLibVlc = false
        closeExternalHlsFallback()
        clearLibVlcTrackState()
    }

    private suspend fun ensurePlayerInitialized(libVlcBackend: LinuxResolvedLibVlcBackend? = null) {
        if (!playerScope.isActive) {
            playerScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        }

        val wantsLibVlc = libVlcBackend != null
        val existingPtr = playerPtr
        if (existingPtr != 0L && nativeBackendUsesLibVlc != wantsLibVlc) {
            val ptrToDispose = playerPtrAtomic.getAndSet(0L)
            if (ptrToDispose != 0L) {
                disposeNativePlayer(ptrToDispose, wasLibVlc = nativeBackendUsesLibVlc)
            }
            nativeBackendUsesLibVlc = false
        }

        if (playerPtr == 0L) {
            val ptr =
                if (libVlcBackend != null) {
                    LinuxNativeBridge.nCreateLibVlcPlayer(
                        libVlcBackend.installation.libVlcPath,
                        libVlcBackend.installation.pluginPath,
                    )
                } else {
                    LinuxNativeBridge.nCreatePlayer()
                }
            if (ptr != 0L) {
                if (!playerPtrAtomic.compareAndSet(0L, ptr)) {
                    disposeNativePlayer(ptr, wasLibVlc = wantsLibVlc)
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

    private suspend fun openMediaUri(
        uri: String,
        requestHeaders: Map<String, String>,
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
                if (!LinuxNativeBridge.nOpenLibVlcUriWithHeaders(ptr, uri, headerLines)) {
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
        !ExternalHlsFallbackSupport.isDisabled() &&
            ExternalHlsFallbackSupport.needsContainerFallback(uri, requestHeaders)

    private suspend fun resolveLibVlcBackendForUri(
        uri: String,
        requestHeaders: Map<String, String>,
    ): LinuxResolvedLibVlcBackend? {
        if (!JvmExternalFallbackContainerSupport.needsContainerFallback(uri, requestHeaders)) return null

        val configured =
            (
                System.getProperty("composemediaplayer.linux.fallbackBackend")
                    ?: System.getProperty("composemediaplayer.fallbackBackend")
                    ?: System.getenv("COMPOSE_MEDIA_PLAYER_LINUX_FALLBACK_BACKEND")
                    ?: System.getenv("COMPOSE_MEDIA_PLAYER_FALLBACK_BACKEND")
                    ?: System.getProperty("composemediaplayer.hlsFallbackBackend")
                    ?: System.getenv("COMPOSE_MEDIA_PLAYER_HLS_FALLBACK_BACKEND")
                    ?: "auto"
            ).lowercase()

        return when (configured) {
            "libvlc" ->
                ExternalVlcLocator.findLibVlc()?.let(::LinuxResolvedLibVlcBackend)
                    ?: throw missingLibVlcBackendException()
            "auto" -> ExternalVlcLocator.findLibVlc()?.let(::LinuxResolvedLibVlcBackend)
            "ffmpeg", "vlc" -> null
            else -> null
        }
    }

    private fun missingLibVlcBackendException(): UnsupportedOperationException =
        UnsupportedOperationException(
            "The Linux libVLC canvas backend was requested, but no compatible libVLC installation was found. " +
                "Install VLC/libVLC for ${ExternalVlcLocator.currentProcessArchitecture() ?: "the current"} " +
                "JVM architecture or set composemediaplayer.libvlc and composemediaplayer.libvlc.plugins. " +
                "ComposeMediaPlayer does not bundle or link VLC.",
        )

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

    private suspend fun updateLibVlcTracks(trackInfo: JvmLibVlcTrackInfo) {
        withContext(Dispatchers.Main) {
            availableAudioTracks.removeAll { isLibVlcAudioTrackId(it.id) }
            availableAudioTracks.addAll(trackInfo.audioStreams.map { it.track })
            currentAudioTrack =
                libVlcSelectedAudioStreamIndex
                    ?.let { streamIndex -> trackInfo.audioStreams.firstOrNull { it.streamIndex == streamIndex }?.track }
                    ?: trackInfo.audioStreams.firstOrNull { it.track.isDefault }?.track
                    ?: trackInfo.audioStreams.firstOrNull()?.track
            libVlcSelectedAudioStreamIndex = currentAudioTrack?.id?.let(::libVlcTrackStreamIndex)

            availableSubtitleTracks.removeAll { isLibVlcSubtitleTrackId(it.id) }
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
        libVlcSourceUri = null
        libVlcTrackInfo = null
        libVlcSelectedAudioStreamIndex = null
        libVlcSelectedSubtitleStreamIndex = null
        withContext(Dispatchers.Main) {
            availableAudioTracks.removeAll { isLibVlcAudioTrackId(it.id) }
            if (currentAudioTrack?.id?.let(::isLibVlcAudioTrackId) == true) {
                currentAudioTrack = null
            }
            availableSubtitleTracks.removeAll { isLibVlcSubtitleTrackId(it.id) }
            if (currentSubtitleTrack?.id?.let(::isLibVlcSubtitleTrackId) == true) {
                currentSubtitleTrack = null
                subtitlesEnabled = false
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
            availableAudioTracks.removeAll { ExternalHlsFallbackSupport.isExternalHlsAudioTrackId(it.id) }
            if (currentAudioTrack?.id?.let(ExternalHlsFallbackSupport::isExternalHlsAudioTrackId) == true) {
                currentAudioTrack = null
            }

            availableSubtitleTracks.removeAll { ExternalHlsFallbackSupport.isExternalHlsSubtitleTrackId(it.id) }
            if (currentSubtitleTrack?.id?.let(ExternalHlsFallbackSupport::isExternalHlsSubtitleTrackId) == true) {
                currentSubtitleTrack = null
                subtitlesEnabled = false
            }
        }
    }

    private suspend fun updateExternalHlsFallbackTracks(hlsSource: HlsFallbackSource) {
        val previousSubtitleId = currentSubtitleTrack?.id
        withContext(Dispatchers.Main) {
            availableAudioTracks.removeAll { ExternalHlsFallbackSupport.isExternalHlsAudioTrackId(it.id) }
            availableAudioTracks.addAll(hlsSource.audioTracks)
            currentAudioTrack =
                hlsSource.selectedAudioStreamIndex
                    ?.let { streamIndex ->
                        hlsSource.audioTracks.firstOrNull {
                            ExternalHlsFallbackSupport.externalHlsTrackStreamIndex(it.id) == streamIndex
                        }
                    }
                    ?: hlsSource.audioTracks.firstOrNull { it.isDefault }
                    ?: hlsSource.audioTracks.firstOrNull()

            availableSubtitleTracks.removeAll { ExternalHlsFallbackSupport.isExternalHlsSubtitleTrackId(it.id) }
            availableSubtitleTracks.addAll(hlsSource.subtitleTracks)
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
            ioScope.launch {
                while (isActive) {
                    ensureActive()
                    updateFrameAsync()
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
            ioScope.launch {
                while (isActive) {
                    ensureActive()
                    checkBufferingState()
                    delay(bufferingCheckInterval)
                }
            }
    }

    private suspend fun checkBufferingState() {
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

                        val targetBitmap = if (nextSkiaBitmapA) skiaBitmapA!! else skiaBitmapB!!
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

            if (seekInProgress && targetSeekTime != null) {
                if (abs(current.toSecondsDouble() - targetSeekTime!!.toSecondsDouble()) < 0.3) {
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
        ioScope.launch {
            if (!hasMedia && lastUri != null) {
                openUri(lastUri!!, requestHeaders = lastRequestHeaders)
            } else if (hasMedia) {
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
        ioScope.launch { pauseInBackground() }
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
            updateFrameAsync()
            stopFrameUpdates()
            stopBufferingCheck()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            linuxLogger.e { "Error in pauseInBackground: ${e.message}" }
        }
    }

    override fun stop() {
        ioScope.launch {
            if (externalHlsFallback != null || libVlcBackendActive) {
                cleanupCurrentPlayback()
            } else {
                pauseInBackground()
                if (hasMedia) seekToAsync(0f)
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

    override fun seekTo(time: Duration) {
        ioScope.launch {
            delay(10.milliseconds) // Coalesce rapid seek events
            seekToTimeAsync(time)
        }
    }

    override fun seekToProgress(progress: Float) {
        seekTo(progress.coerceIn(0f, 1f) * VideoPlayerState.SLIDER_SCALE)
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun seekTo(value: Float) {
        ioScope.launch {
            delay(10.milliseconds) // Coalesce rapid seek events
            val duration = getDurationSafely()
            if (duration <= Duration.ZERO) {
                withContext(Dispatchers.Main) { isLoading = false }
                return@launch
            }
            val seekTime = duration * (value / VideoPlayerState.SLIDER_SCALE).toDouble().coerceIn(0.0, 1.0)
            seekToTimeAsync(seekTime)
        }
    }

    private suspend fun seekToAsync(value: Float) {
        val duration = getDurationSafely()
        if (duration <= Duration.ZERO) {
            withContext(Dispatchers.Main) { isLoading = false }
            return
        }
        val seekTime = duration * (value / VideoPlayerState.SLIDER_SCALE).toDouble().coerceIn(0.0, 1.0)
        seekToTimeAsync(seekTime)
    }

    private suspend fun seekToTimeAsync(time: Duration) {
        withContext(Dispatchers.Main) { isLoading = true }

        try {
            val duration = getDurationSafely()
            if (duration <= Duration.ZERO) {
                withContext(Dispatchers.Main) { isLoading = false }
                return
            }

            val seekTime =
                when {
                    time < Duration.ZERO -> Duration.ZERO
                    time > duration -> duration
                    else -> time
                }

            withContext(Dispatchers.Main) {
                seekInProgress = true
                targetSeekTime = seekTime
                sliderPos =
                    (seekTime.toSecondsDouble() / duration.toSecondsDouble() * VideoPlayerState.SLIDER_SCALE)
                        .toFloat()
                        .coerceIn(0f, VideoPlayerState.SLIDER_SCALE)
            }

            lastFrameUpdateTime = System.currentTimeMillis()

            val ptr = playerPtr
            if (ptr == 0L) return
            nativeSeekTo(ptr, seekTime.toSecondsDouble())

            if (isPlaying) {
                nativePlay(ptr)
                delay(10.milliseconds)
                updateFrameAsync()
                ioScope.launch {
                    delay(300.milliseconds)
                    if (seekInProgress) {
                        seekInProgress = false
                        targetSeekTime = null
                        withContext(Dispatchers.Main) { isLoading = false }
                    }
                }
            } else {
                delay(50.milliseconds)
                updateFrameAsync()
                seekInProgress = false
                targetSeekTime = null
                withContext(Dispatchers.Main) { isLoading = false }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            linuxLogger.e { "Error in seekToAsync: ${e.message}" }
            withContext(Dispatchers.Main) {
                isLoading = false
                seekInProgress = false
                targetSeekTime = null
            }
        }
    }

    override fun clearError() {
        runBlocking {
            withContext(Dispatchers.Main) { error = null }
        }
    }

    override fun toggleFullscreen() {
        isFullscreen = !isFullscreen
    }

    override fun dispose() {
        stopFrameUpdates()
        stopBufferingCheck()
        uiUpdateJob?.cancel()
        playerScope.cancel()

        // Clear the pointer atomically so no background task can use it
        val ptrToDispose = playerPtrAtomic.getAndSet(0L)

        // Native cleanup on a background thread to avoid blocking the UI.
        Thread {
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

            if (ptrToDispose != 0L) {
                try {
                    disposeNativePlayer(ptrToDispose)
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    linuxLogger.e { "Error disposing player: ${e.message}" }
                }
            }
        }.start()

        closeExternalHlsFallback()
        ioScope.cancel()
    }

    // --- Subtitle stubs ---
    override fun selectAudioTrack(track: AudioTrack?) {
        val selectedLibVlcStreamIndex =
            track
                ?.id
                ?.takeIf(::isLibVlcAudioTrackId)
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
                ?.takeIf(ExternalHlsFallbackSupport::isExternalHlsAudioTrackId)
                ?.let(ExternalHlsFallbackSupport::externalHlsTrackStreamIndex)

        if (track != null && selectedStreamIndex != null) {
            ioScope.launch {
                switchExternalHlsAudioTrack(track, selectedStreamIndex)
            }
            return
        }

        ioScope.launch {
            withContext(Dispatchers.Main) {
                currentAudioTrack = track
            }
        }
    }

    override fun selectSubtitleTrack(track: SubtitleTrack?) {
        if (track == null && libVlcBackendActive) {
            ioScope.launch {
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
            ioScope.launch {
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
            ioScope.launch {
                switchExternalHlsSubtitleTrack(track, selectedStreamIndex)
            }
            return
        }

        ioScope.launch {
            withContext(Dispatchers.Main) {
                currentSubtitleTrack = track
                subtitlesEnabled = track != null
            }
        }
    }

    override fun disableSubtitles() {
        if (libVlcBackendActive && currentSubtitleTrack?.id?.let(::isLibVlcSubtitleTrackId) == true) {
            ioScope.launch {
                disableLibVlcSubtitles()
            }
            return
        }

        if (externalHlsSourceUri != null && externalHlsSelectedSubtitleStreamIndex != null) {
            ioScope.launch {
                switchExternalHlsSubtitleTrack(track = null, streamIndex = null)
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
            LinuxNativeBridge.nSelectLibVlcAudioTrack(ptr, ordinal)
        }
    }

    private suspend fun selectLibVlcSubtitleTrack(
        track: SubtitleTrack,
        streamIndex: Int,
    ) {
        libVlcSelectedSubtitleStreamIndex = streamIndex
        withContext(Dispatchers.Main) {
            currentSubtitleTrack = track
            subtitlesEnabled = true
        }

        val ordinal =
            libVlcTrackInfo
                ?.subtitleStreams
                ?.firstOrNull { it.streamIndex == streamIndex }
                ?.ordinal
                ?: return
        val ptr = playerPtr
        if (ptr != 0L) {
            LinuxNativeBridge.nSelectLibVlcSubtitleTrack(ptr, ordinal)
        }
    }

    private suspend fun disableLibVlcSubtitles() {
        libVlcSelectedSubtitleStreamIndex = null
        val ptr = playerPtr
        if (ptr != 0L) {
            LinuxNativeBridge.nDisableLibVlcSubtitles(ptr)
        }
        withContext(Dispatchers.Main) {
            subtitlesEnabled = false
            currentSubtitleTrack = null
        }
    }

    private suspend fun switchExternalHlsAudioTrack(
        track: AudioTrack,
        streamIndex: Int,
    ) {
        val sourceUri = externalHlsSourceUri
        if (sourceUri == null) {
            withContext(Dispatchers.Main) { currentAudioTrack = track }
            return
        }
        if (externalHlsSelectedAudioStreamIndex == streamIndex) {
            withContext(Dispatchers.Main) { currentAudioTrack = track }
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
            withContext(Dispatchers.Main) {
                currentSubtitleTrack = track
                subtitlesEnabled = track != null
            }
            return
        }
        if (externalHlsSelectedSubtitleStreamIndex == streamIndex) {
            withContext(Dispatchers.Main) {
                currentSubtitleTrack = track
                subtitlesEnabled = track != null
            }
            return
        }
        if (track != null && !ExternalHlsFallbackSupport.hasSubtitleRenderer()) {
            withContext(Dispatchers.Main) {
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
            withContext(Dispatchers.Main) {
                isLoading = true
                error = null
                onBeforeRestart()
                sliderPos = restartSliderPos
                _positionText.value = formatTime(restartPosition)
            }

            cleanupCurrentPlayback()
            ensurePlayerInitialized()

            externalHlsSelectedAudioStreamIndex = selectedAudioStreamIndex
            externalHlsSelectedSubtitleStreamIndex = selectedSubtitleStreamIndex
            externalHlsPlaybackOffsetSeconds = restartPosition.toSecondsDouble()
            val playableUri = prepareExternalHlsPlayback(sourceUri, lastRequestHeaders)
            val opened = openMediaUri(playableUri, emptyMap())
            if (!opened) {
                closeExternalHlsFallback()
                clearExternalHlsFallbackTrackState()
                withContext(Dispatchers.Main) {
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

            withContext(Dispatchers.Main) {
                hasMedia = true
                isLoading = false
                isPlaying = shouldResumePlayback
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
            handleError(e)
        }
    }

    // --- Output scaling ---

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
