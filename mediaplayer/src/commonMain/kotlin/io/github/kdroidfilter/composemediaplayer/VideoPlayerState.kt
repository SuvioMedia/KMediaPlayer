package io.github.kdroidfilter.composemediaplayer

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import io.github.kdroidfilter.composemediaplayer.util.PipResult
import io.github.vinceglb.filekit.PlatformFile
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Represents the state and controls for a video player. This class provides properties
 * and methods to manage video playback, including play, pause, stop, seeking, and more.
 * It maintains information about the playback state, such as whether the video is
 * currently playing, volume levels, and playback position.
 *
 * Functions of this class are tied to managing and interacting with the underlying
 * video player implementation.
 *
 * @constructor Initializes an instance of the video player state.
 */
@Stable
interface VideoPlayerState {
    // Properties related to media state
    val mediaSessionId: Long
        get() = 0L
    val hasMedia: Boolean

    /**
     * Indicates whether the video is currently playing.
     */
    val isPlaying: Boolean
    val isLoading: Boolean
    val isSeeking: Boolean
        get() = false
    val isBuffering: Boolean
        get() = isLoading && !isSeeking
    val loadingState: PlaybackLoadingState
        get() =
            when {
                isSeeking -> PlaybackLoadingState.SEEKING
                isBuffering -> PlaybackLoadingState.BUFFERING
                isLoading -> PlaybackLoadingState.LOADING
                else -> PlaybackLoadingState.IDLE
            }
    val playbackEvents: SharedFlow<PlaybackEvent>
        get() = EmptyPlaybackEvents.events
    val diagnostics: PlaybackDiagnostics
        get() =
            PlaybackDiagnostics(
                currentHlsQuality = currentHlsQuality,
                bufferedRanges = bufferedRanges,
                notes = renderingInfo.notes,
            )
    val capabilities: PlayerCapabilities
        get() =
            PlayerCapabilities(
                supportsExternalSubtitles = true,
                supportsPiP = isPipSupported,
                supportsPlaybackDiagnostics = diagnostics != PlaybackDiagnostics(),
            )

    /**
     * Controls the playback volume. Valid values are within the range of 0.0 (muted) to 1.0 (maximum volume).
     */
    var volume: Float

    /**
     * Represents the current playback position as a value between 0.0 and 1000.0.
     */
    var sliderPos: Float

    /**
     * Denotes whether the user is manually adjusting the playback position.
     */
    var userDragging: Boolean

    /**
     * Specifies if the video should loop when it reaches the end.
     */
    var loop: Boolean
    var playbackSpeed: Float

    /**
     * Callback invoked when playback reaches the end of the media.
     * Only called when [loop] is false. May be invoked from a background thread.
     */
    var onPlaybackEnded: (() -> Unit)?

    /**
     * Callback invoked when playback restarts from the beginning due to looping.
     * Only called when [loop] is true. May be invoked from a background thread.
     */
    var onRestart: (() -> Unit)?

    /**
     * Returns the current playback position as a formatted string with millisecond precision.
     */
    val positionText: String

    /**
     * Returns the total duration of the video as a formatted string with millisecond precision.
     */
    val durationText: String

    /**
     * Returns the last observed playback position.
     *
     * This value is intended for UI state and may be updated at the platform display/update cadence.
     * Use [preciseCurrentTime] for external actions that need the freshest available player position.
     */
    val currentTime: Duration

    /**
     * Returns the freshest playback position available from the platform backend.
     *
     * Prefer this for external synchronization or millisecond-level actions. It is an on-demand value and should not
     * be used as a high-frequency Compose state source.
     */
    val preciseCurrentTime: Duration

    /**
     * Returns the total duration of the media.
     */
    val duration: Duration
    val currentTimeMs: Long
        get() = currentTime.inWholeMilliseconds
    val preciseCurrentTimeMs: Long
        get() = preciseCurrentTime.inWholeMilliseconds
    val durationMs: Long
        get() = duration.inWholeMilliseconds
    val bufferedRanges: List<BufferedRange>
        get() = emptyList()
    val bufferedPercent: Float
        get() {
            val totalMs = duration.inWholeMilliseconds
            if (totalMs <= 0L) return 0f
            val maxBufferedMs = bufferedRanges.maxOfOrNull { it.end.inWholeMilliseconds } ?: 0L
            return (maxBufferedMs.toDouble() / totalMs.toDouble() * PERCENT_SCALE)
                .toFloat()
                .coerceIn(0f, PERCENT_SCALE.toFloat())
        }
    var isFullscreen: Boolean
    val aspectRatio: Float

    val isPipSupported: Boolean get() = false
    var isPipActive: Boolean get() = false
        set(value) {}
    var isPipEnabled: Boolean get() = false
        set(value) {}

    suspend fun enterPip(): PipResult = PipResult.NotSupported

    // Functions to control playback

    fun canPlaySource(
        uri: String,
        mimeType: String? = null,
    ): Boolean = capabilities.canPlaySource(uri = uri, mimeType = mimeType)

    /**
     * Starts or resumes video playback.
     */
    fun play()

    /**
     * Pauses video playback.
     */
    fun pause()

    /**
     * Stops playback and resets the player state.
     */
    fun stop()

    /**
     * Seeks to a specific playback time.
     */
    fun seekTo(time: Duration) {
        val totalMs = duration.inWholeMilliseconds
        if (totalMs <= 0L) return
        seekToProgress((time.inWholeMilliseconds.toDouble() / totalMs.toDouble()).toFloat())
    }

    /**
     * Seeks by a signed time delta.
     */
    fun seekBy(delta: Duration) {
        seekTo(preciseCurrentTime + delta)
    }

    /**
     * Seeks to a normalized progress value in the 0.0..1.0 range.
     */
    @Suppress("DEPRECATION")
    fun seekToProgress(progress: Float) {
        seekTo(progress.coerceIn(0f, 1f) * SLIDER_SCALE)
    }

    /**
     * Legacy slider-scale seek. The [value] should be between 0.0 and 1000.0.
     * Prefer [seekTo] for time-based seeking or [seekToProgress] for normalized progress.
     */
    @Deprecated(
        message = "Use seekTo(Duration) or seekToProgress(Float) instead.",
        replaceWith = ReplaceWith("seekToProgress(value / VideoPlayerState.SLIDER_SCALE)"),
    )
    fun seekTo(value: Float)

    /**
     * Begins a user-driven seek interaction (e.g. slider drag).
     * Updates the visual slider position without performing the actual seek on the player.
     * Must be followed by [seekFinished] to commit the seek.
     */
    fun seekStart(value: Float) {
        userDragging = true
        sliderPos = value
    }

    /**
     * Commits the seek after a user-driven seek interaction.
     * Performs the actual seek on the player and ends the dragging state.
     */
    fun seekFinished() {
        seekToProgress(sliderPos / SLIDER_SCALE)
        userDragging = false
    }

    /**
     * Restarts playback from the beginning. Works reliably from any state,
     * including when playback has ended.
     */
    fun restart() {
        seekTo(Duration.ZERO)
        play()
    }

    fun toggleFullscreen()

    // Functions to manage media sources

    /**
     * Opens a video file or URL for playback.
     */
    fun openUri(
        uri: String,
        initializeplayerState: InitialPlayerState = InitialPlayerState.PLAY,
    )

    fun prepare(
        uri: String,
        initializeplayerState: InitialPlayerState = InitialPlayerState.PLAY,
    ) {
        openUri(uri, initializeplayerState)
    }

    fun openFile(
        file: PlatformFile,
        initializeplayerState: InitialPlayerState = InitialPlayerState.PLAY,
    )

    /**
     * Opens a media file bundled with the application.
     *
     * On Android, plays directly from the APK's `assets/` directory via the `asset:///` URI scheme (zero-copy).
     * On iOS, resolves the file from the app bundle via `NSBundle.mainBundle`.
     *
     * @param fileName the file name or relative path (e.g. `"video.mp4"` or `"videos/intro.mp4"`)
     * @throws UnsupportedOperationException on platforms where asset loading is not yet supported
     */
    fun openAsset(
        fileName: String,
        initializeplayerState: InitialPlayerState = InitialPlayerState.PLAY,
    ): Unit = throw UnsupportedOperationException("openAsset is not supported on this platform")

    // Error handling
    val error: VideoPlayerError?

    fun clearError()

    // Metadata
    val metadata: VideoMetadata
    val renderingInfo: VideoRenderingInfo
        get() = VideoRenderingInfo()

    fun playbackSnapshot(): PlaybackSnapshot =
        PlaybackSnapshot(
            position = preciseCurrentTime,
            duration = duration,
            isPlaying = isPlaying,
            isLoading = isLoading,
            isBuffering = isBuffering,
            isSeeking = isSeeking,
            playbackSpeed = playbackSpeed,
            sampledAtMs = Clock.System.now().toEpochMilliseconds(),
            bufferedRanges = bufferedRanges,
            bufferedPercent = bufferedPercent,
            loadingState = loadingState,
            mediaSessionId = mediaSessionId,
            diagnostics = diagnostics,
        )

    // Audio track management
    var currentAudioTrack: AudioTrack?
    val availableAudioTracks: MutableList<AudioTrack>

    fun selectAudioTrack(track: AudioTrack?)

    fun selectAudioTrack(trackId: String?) {
        selectAudioTrack(trackId?.let { id -> availableAudioTracks.firstOrNull { it.id == id } })
    }

    // Subtitle management
    var subtitlesEnabled: Boolean
    var currentSubtitleTrack: SubtitleTrack?
    val availableSubtitleTracks: MutableList<SubtitleTrack>
    var subtitleTextStyle: TextStyle
    var subtitleBackgroundColor: Color
    var subtitleOffset: Duration
        get() = Duration.ZERO
        set(value) {}

    fun selectSubtitleTrack(track: SubtitleTrack?)

    fun selectSubtitleTrack(trackId: String?) {
        selectSubtitleTrack(trackId?.let { id -> availableSubtitleTracks.firstOrNull { it.id == id } })
    }

    fun disableSubtitles()

    // HLS quality management
    val availableHlsQualities: List<HlsQualityVariant>
        get() = emptyList()
    val currentHlsQuality: HlsQualityVariant?
        get() = null
    val hlsQualityMode: HlsQualityMode
        get() = HlsQualityMode.AUTO

    fun selectHlsQuality(variantId: String?) {}

    fun selectAutoHlsQuality() {
        selectHlsQuality(null)
    }

    // Cache management

    /**
     * Clears the shared video cache, removing all cached media data from disk.
     *
     * This is a no-op on platforms that do not support caching or when caching
     * is not enabled.
     */
    fun clearCache() {}

    // Cleanup

    /**
     * Releases the current media source and invalidates callbacks tied to it.
     * Unlike [dispose], the player state object can be reused after calling this method.
     */
    fun releaseSource() {
        stop()
    }

    /**
     * Releases all resources used by the video player (native players, coroutines, observers, etc.).
     *
     * **You do not need to call this manually** when using [rememberVideoPlayerState], which is the
     * recommended way to create a player state in a composable. It automatically calls [dispose]
     * via a [DisposableEffect] when the composable leaves the composition.
     *
     * Only call this directly if you create the state manually via [createVideoPlayerState] outside
     * of a composable lifecycle:
     * ```
     * val state = createVideoPlayerState()
     * try {
     *     // use the player...
     * } finally {
     *     state.dispose()
     * }
     * ```
     *
     * After calling [dispose], the state should not be reused.
     */
    fun dispose()

    companion object {
        const val MIN_PLAYBACK_SPEED = 0.25f
        const val MAX_PLAYBACK_SPEED = 2.0f
        const val SLIDER_SCALE = 1000f
        private const val PERCENT_SCALE = 100.0
    }
}

private object EmptyPlaybackEvents {
    val events: SharedFlow<PlaybackEvent> =
        MutableSharedFlow(
            replay = 0,
            extraBufferCapacity = 1,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
}

/**
 *  Create platform-specific video player state. Supported platforms include Windows,
 *  macOS, and Linux.
 *
 * @param audioMode The audio mode configuration for the player.
 * @param cacheConfig Optional caching configuration. When [CacheConfig.enabled] is `true`,
 *   video data fetched via [VideoPlayerState.openUri] is cached on disk so that subsequent
 *   plays of the same URI avoid a full re-download. Currently only effective on Android and iOS.
 */
expect fun createVideoPlayerState(
    audioMode: AudioMode = AudioMode(),
    cacheConfig: CacheConfig = CacheConfig(),
): VideoPlayerState

/**
 * Creates and remembers a [VideoPlayerState], automatically releasing all player resources
 * when the composable leaves the composition.
 *
 * This is the **recommended** way to obtain a [VideoPlayerState]. You do not need to call
 * [VideoPlayerState.dispose] yourself — cleanup is handled via [DisposableEffect].
 *
 * ```
 * @Composable
 * fun MyPlayer() {
 *     val playerState = rememberVideoPlayerState()
 *     // use playerState — resources are freed automatically on removal
 * }
 * ```
 *
 * @param audioMode The audio mode configuration for the player.
 * @param cacheConfig Optional caching configuration. When [CacheConfig.enabled] is `true`,
 *   video data fetched via [VideoPlayerState.openUri] is cached on disk so that subsequent
 *   plays of the same URI avoid a full re-download. Currently only effective on Android and iOS.
 * @return The remembered instance of [VideoPlayerState].
 */
@Composable
fun rememberVideoPlayerState(
    audioMode: AudioMode = AudioMode(),
    cacheConfig: CacheConfig = CacheConfig(),
): VideoPlayerState {
    val playerState = remember(audioMode, cacheConfig) { createVideoPlayerState(audioMode, cacheConfig) }
    DisposableEffect(playerState) {
        onDispose {
            playerState.dispose()
        }
    }
    return playerState
}

/**
 * Helper to mock the [VideoPlayerState].
 */
data class PreviewableVideoPlayerState(
    override val hasMedia: Boolean = true,
    override val isPlaying: Boolean = true,
    override val isLoading: Boolean = false,
    override var volume: Float = 1f,
    override var sliderPos: Float = 500f,
    override var userDragging: Boolean = false,
    override var loop: Boolean = true,
    override var playbackSpeed: Float = 1f,
    override val positionText: String = "00:05.000",
    override val durationText: String = "00:10.000",
    override val currentTime: Duration = 5.seconds,
    override val preciseCurrentTime: Duration = currentTime,
    override val duration: Duration = 10.seconds,
    override var isFullscreen: Boolean = false,
    override val aspectRatio: Float = 1.7f,
    override val error: VideoPlayerError? = null,
    override val metadata: VideoMetadata = VideoMetadata(),
    override val renderingInfo: VideoRenderingInfo = VideoRenderingInfo(),
    override var currentAudioTrack: AudioTrack? = null,
    override val availableAudioTracks: MutableList<AudioTrack> = emptyList<AudioTrack>().toMutableList(),
    override var subtitlesEnabled: Boolean = false,
    override var currentSubtitleTrack: SubtitleTrack? = null,
    override val availableSubtitleTracks: MutableList<SubtitleTrack> = emptyList<SubtitleTrack>().toMutableList(),
    override var subtitleTextStyle: TextStyle = TextStyle.Default,
    override var subtitleBackgroundColor: Color = Color.Transparent,
    override val isPipSupported: Boolean = false,
    override var isPipActive: Boolean = false,
    override var isPipEnabled: Boolean = false,
    override var onPlaybackEnded: (() -> Unit)? = null,
    override var onRestart: (() -> Unit)? = null,
) : VideoPlayerState {
    override fun play() {}

    override fun pause() {}

    override fun stop() {}

    @Suppress("OVERRIDE_DEPRECATION")
    override fun seekTo(value: Float) {}

    override fun toggleFullscreen() {}

    override fun openUri(
        uri: String,
        initializeplayerState: InitialPlayerState,
    ) {}

    override fun openFile(
        file: PlatformFile,
        initializeplayerState: InitialPlayerState,
    ) {}

    override fun clearError() {}

    override fun selectAudioTrack(track: AudioTrack?) {}

    override fun selectSubtitleTrack(track: SubtitleTrack?) {}

    override fun disableSubtitles() {}

    override fun dispose() {}
}
