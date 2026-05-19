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
import io.github.kdroidfilter.composemediaplayer.util.formatTime
import io.github.kdroidfilter.composemediaplayer.util.getUri
import io.github.kdroidfilter.composemediaplayer.util.toSecondsDouble
import io.github.vinceglb.filekit.PlatformFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.io.IOException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

actual fun createVideoPlayerState(
    audioMode: AudioMode,
    cacheConfig: CacheConfig,
): VideoPlayerState = DefaultVideoPlayerState()

/**
 * Implementation of VideoPlayerState for WebAssembly.
 * Manages the state of a video player including playback controls, media information,
 * and error handling.
 */
@Stable
open class DefaultVideoPlayerState : VideoPlayerState {
    // Variable to store the last opened URI for potential replay
    private var lastUri: String? = null

    // Coroutine scope for managing async operations
    private val playerScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var lastUpdateTime = TimeSource.Monotonic.markNow()

    // Throttling for control changes
    private var lastVolumeChangeTime = TimeSource.Monotonic.markNow()
    private var lastSpeedChangeTime = TimeSource.Monotonic.markNow()
    private var pendingVolumeChange: Job? = null
    private var pendingSpeedChange: Job? = null

    // Source URI of the current media
    private var _sourceUri by mutableStateOf<String?>(null)
    val sourceUri: String? get() = _sourceUri

    // Playback state properties
    private var _isPlaying by mutableStateOf(false)
    override val isPlaying: Boolean get() = _isPlaying

    private var _hasMedia by mutableStateOf(false)
    override val hasMedia: Boolean get() = _hasMedia

    internal var _isLoading by mutableStateOf(false)
    override val isLoading: Boolean get() = _isLoading

    // Error handling
    override var onPlaybackEnded: (() -> Unit)? = null
    override var onRestart: (() -> Unit)? = null

    private var _error by mutableStateOf<VideoPlayerError?>(null)
    override val error: VideoPlayerError? get() = _error

    // Media metadata
    override val metadata = VideoMetadata()
    override val renderingInfo =
        VideoRenderingInfo(
            backend = "HTML5 video",
            videoDecoder = "Browser native decoder",
            videoRenderer = "HTMLVideoElement",
            audioRenderer = "Browser native audio",
        )
    override val aspectRatio: Float = 16f / 9f // TO DO: Get from video source

    // Subtitle management
    override var subtitlesEnabled by mutableStateOf(false)
    override var currentSubtitleTrack by mutableStateOf<SubtitleTrack?>(null)
    override val availableSubtitleTracks = mutableStateListOf<SubtitleTrack>()
    override var subtitleTextStyle by mutableStateOf(
        TextStyle(
            color = Color.White,
            fontSize = 18.sp,
            fontWeight = FontWeight.Normal,
            textAlign = TextAlign.Center,
        ),
    )
    override var subtitleBackgroundColor by mutableStateOf(Color.Black.copy(alpha = 0.5f))

    // Audio track management
    override var currentAudioTrack by mutableStateOf<AudioTrack?>(null)
    override val availableAudioTracks = mutableStateListOf<AudioTrack>()

    var applyAudioTrackCallback: ((AudioTrack?) -> Unit)? = null
    var applySubtitleTrackCallback: ((SubtitleTrack?) -> Unit)? = null

    // Playback control properties
    private var _volume by mutableStateOf(1.0f)
    override var volume: Float
        get() = _volume
        set(value) {
            val newValue = value.coerceIn(0f, 1f)
            if (_volume != newValue) {
                _volume = newValue
                applyVolumeChangeWithThrottle(newValue)
            }
        }

    override var sliderPos by mutableStateOf(0.0f)
    override var userDragging by mutableStateOf(false)
    override var loop by mutableStateOf(false)

    private var _playbackSpeed by mutableStateOf(1.0f)
    override var playbackSpeed: Float
        get() = _playbackSpeed
        set(value) {
            val newValue = value.coerceIn(VideoPlayerState.MIN_PLAYBACK_SPEED, VideoPlayerState.MAX_PLAYBACK_SPEED)
            if (_playbackSpeed != newValue) {
                _playbackSpeed = newValue
                applyPlaybackSpeedWithThrottle(newValue)
            }
        }

    override var isFullscreen by mutableStateOf(false)

    // Time display properties
    private var _positionText by mutableStateOf("00:00.000")
    private var _durationText by mutableStateOf("00:00.000")
    override val positionText: String get() = _positionText
    override val durationText: String get() = _durationText

    private var _currentDuration by mutableStateOf(Duration.ZERO)
    private var _currentTime by mutableStateOf(Duration.ZERO)
    internal var preciseCurrentTimeProvider: (() -> Duration)? = null
    internal var durationProvider: (() -> Duration)? = null
    override val currentTime: Duration get() = _currentTime
    override val preciseCurrentTime: Duration get() = preciseCurrentTimeProvider?.invoke() ?: _currentTime
    override val duration: Duration
        get() {
            val observedDuration = _currentDuration
            return durationProvider?.invoke() ?: observedDuration
        }

    // Job for handling seek operations
    internal var seekJob: Job? = null

    /**
     * Callback function to force recalculation of the HTML view position.
     * This is set by the VideoPlayerSurface when the HTML view is created.
     */
    var positionRecalculationCallback: (() -> Unit)? = null

    /**
     * Callback to apply volume changes to the underlying media player
     */
    var applyVolumeCallback: ((Float) -> Unit)? = null

    /**
     * Callback to apply playback speed changes to the underlying media player
     */
    var applyPlaybackSpeedCallback: ((Float) -> Unit)? = null

    /**
     * Forces recalculation of the HTML view position.
     * This is useful when the layout changes and the HTML view needs to be repositioned.
     */
    fun forcePositionRecalculation() {
        positionRecalculationCallback?.invoke()
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
    override fun selectSubtitleTrack(track: SubtitleTrack?) {
        currentSubtitleTrack = track
        subtitlesEnabled = (track != null)
        applySubtitleTrackCallback?.invoke(track)
    }

    /**
     * Disables subtitles by clearing the current track and setting subtitlesEnabled to false.
     */
    override fun disableSubtitles() {
        currentSubtitleTrack = null
        subtitlesEnabled = false
        applySubtitleTrackCallback?.invoke(null)
    }

    override fun selectAudioTrack(track: AudioTrack?) {
        currentAudioTrack = track
        applyAudioTrackCallback?.invoke(track)
    }

    internal fun replaceAvailableAudioTracks(tracks: List<AudioTrack>) {
        availableAudioTracks.clear()
        availableAudioTracks.addAll(tracks)

        currentAudioTrack =
            currentAudioTrack
                ?.let { current -> tracks.firstOrNull { it.id == current.id } }
                ?: tracks.firstOrNull()
    }

    internal fun replaceEmbeddedSubtitleTracks(tracks: List<SubtitleTrack>) {
        val externalTracks = availableSubtitleTracks.filterNot { it.isEmbedded }
        availableSubtitleTracks.clear()
        availableSubtitleTracks.addAll(externalTracks)
        availableSubtitleTracks.addAll(tracks)

        if (currentSubtitleTrack?.isEmbedded == true) {
            val refreshedTrack = tracks.firstOrNull { it.id == currentSubtitleTrack?.id }
            if (refreshedTrack == null) {
                disableSubtitles()
            } else {
                currentSubtitleTrack = refreshedTrack
            }
        }
    }

    /**
     * Opens a media source from the given URI.
     *
     * @param uri The URI of the media to open
     * @param initializeplayerState Controls whether playback should start automatically after opening
     */
    override fun openUri(
        uri: String,
        initializeplayerState: InitialPlayerState,
    ) {
        playerScope.coroutineContext.cancelChildren()

        // Store the URI for potential replay after stop
        lastUri = uri

        _sourceUri = uri
        _hasMedia = true
        _isLoading = true // Set initial loading state
        _error = null
        _isPlaying = false
        renderingInfo.update(
            backend = "HTML5 video",
            container = null,
            videoDecoder = "Browser native decoder",
            videoRenderer = "HTMLVideoElement",
            audioRenderer = "Browser native audio",
            subtitleRenderer = null,
            subtitleSource = null,
            notes = null,
        )
        currentAudioTrack = null
        availableAudioTracks.clear()
        if (currentSubtitleTrack?.isEmbedded == true) {
            currentSubtitleTrack = null
            subtitlesEnabled = false
        }
        availableSubtitleTracks.removeAll { it.isEmbedded }

        // Don't set isLoading to false here - let the video events handle it
        playerScope.launch {
            try {
                // Set isPlaying based on the initializeplayerState parameter
                _isPlaying = initializeplayerState == InitialPlayerState.PLAY
            } catch (e: Exception) {
                _isLoading = false
                _error =
                    when (e) {
                        is IOException -> VideoPlayerError.NetworkError(e.message ?: "Network error")
                        else -> VideoPlayerError.UnknownError(e.message ?: "Unknown error")
                    }
            }
        }
    }

    /**
     * Opens a media file.
     *
     * @param file The file to open
     * @param initializeplayerState Controls whether playback should start automatically after opening
     */
    override fun openFile(
        file: PlatformFile,
        initializeplayerState: InitialPlayerState,
    ) {
        val fileUri = file.getUri()
        openUri(fileUri, initializeplayerState)
    }

    /**
     * Starts or resumes playback of the current media.
     * If no media is loaded but a previous URI exists, reopens that media.
     */
    override fun play() {
        if (_hasMedia && !_isPlaying) {
            _isPlaying = true
        } else if (!_hasMedia && lastUri != null) {
            // If we have a stored URI but no media, reopen the media
            openUri(lastUri!!)
        }
    }

    /**
     * Pauses playback of the current media.
     */
    override fun pause() {
        if (_isPlaying) {
            _isPlaying = false
        }
    }

    /**
     * Stops playback and resets the player state.
     * Note: lastUri is preserved for potential replay.
     */
    override fun stop() {
        _isPlaying = false
        _sourceUri = null
        _hasMedia = false
        _isLoading = false
        sliderPos = 0.0f
        _positionText = "00:00.000"
        _durationText = "00:00.000"
        _currentTime = Duration.ZERO
        _currentDuration = Duration.ZERO
        // Note: We don't clear lastUri, so it can be used to replay the video
    }

    /**
     * Seeks to a specific position in the media.
     *
     * @param value The position to seek to, as a percentage (0-1000)
     */
    override fun seekTo(value: Float) {
        sliderPos = value
        seekJob?.cancel()
    }

    /**
     * Clears any error state.
     */
    override fun clearError() {
        _error = null
    }

    /**
     * Toggles the fullscreen state of the video player
     */
    override fun toggleFullscreen() {
        FullscreenManager.toggleFullscreen(isFullscreen) { newFullscreenState ->
            isFullscreen = newFullscreenState
        }
    }

    /**
     * Sets the error state.
     *
     * @param error The error to set
     */
    fun setError(error: VideoPlayerError) {
        _error = error
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
        _currentTime = currentTime
        _currentDuration = duration

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
        updatePosition(currentTime, duration, forceUpdate)
    }

    /**
     * Disposes of resources used by the player.
     */
    override fun dispose() {
        preciseCurrentTimeProvider = null
        durationProvider = null
        pendingVolumeChange?.cancel()
        pendingSpeedChange?.cancel()
        playerScope.cancel()
    }

    companion object {
        internal const val PERCENTAGE_MULTIPLIER = 1000f
    }
}
