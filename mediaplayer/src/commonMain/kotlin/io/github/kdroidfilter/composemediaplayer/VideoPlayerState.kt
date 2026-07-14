package io.github.kdroidfilter.composemediaplayer

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import io.github.kdroidfilter.composemediaplayer.util.PipResult
import io.github.vinceglb.filekit.PlatformFile
import kotlinx.coroutines.flow.SharedFlow
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
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
        get() {
            val platformCapabilities = platformPlayerCapabilities()
            return platformCapabilities.copy(
                supportsPiP = isPipSupported,
            )
        }

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
     * Returns the current playback position as a formatted string.
     */
    val positionText: String

    /**
     * Returns the total duration of the video as a formatted string.
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
     * Seeks to a specific playback time in milliseconds.
     */
    fun seekToMs(timeMs: Long) {
        seekTo(timeMs.coerceAtLeast(0L).milliseconds)
    }

    /**
     * Seeks by a signed time delta.
     */
    fun seekBy(delta: Duration) {
        seekTo(preciseCurrentTime + delta)
    }

    /**
     * Seeks by a signed millisecond delta.
     */
    fun seekByMs(deltaMs: Long) {
        seekBy(deltaMs.milliseconds)
    }

    /**
     * Seeks to a normalized progress value in the 0.0..1.0 range.
     */
    @Suppress("DEPRECATION")
    fun seekToProgress(progress: Float) {
        seekTo(progress.coerceIn(0f, 1f) * SLIDER_SCALE)
    }

    /**
     * Legacy slider-scale seek. The value should be between 0.0 and 1000.0.
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
        initializePlayerState: InitialPlayerState = InitialPlayerState.PLAY,
        requestHeaders: Map<String, String> = emptyMap(),
    )

    fun prepare(
        uri: String,
        initializePlayerState: InitialPlayerState = InitialPlayerState.PLAY,
        requestHeaders: Map<String, String> = emptyMap(),
    ) {
        openUri(uri, initializePlayerState, requestHeaders)
    }

    fun openFile(
        file: PlatformFile,
        initializePlayerState: InitialPlayerState = InitialPlayerState.PLAY,
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
        initializePlayerState: InitialPlayerState = InitialPlayerState.PLAY,
    ): Unit = throw UnsupportedOperationException("openAsset is not supported on this platform")

    // Error handling
    val error: VideoPlayerError?

    fun clearError()

    // Metadata
    val metadata: VideoMetadata
    val renderingInfo: VideoRenderingInfo
        get() = VideoRenderingInfo()
    var projection: VideoProjectionSettings
        get() = VideoProjectionSettings()
        set(_) = Unit
    var projectionView: VideoProjectionViewSettings
        get() = VideoProjectionViewSettings()
        set(_) = Unit
    var projectionViewControlMode: VideoProjectionViewControlMode
        get() = VideoProjectionViewControlMode.AUTO
        set(_) = Unit
    var projectionTextureCrop: VideoTextureCrop
        get() = VideoTextureCrop()
        set(_) = Unit

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
    val availableAudioTracks: List<AudioTrack>

    /**
     * Requests an audio track. The result reports whether the backend accepted the request;
     * observe [currentAudioTrack] or [playbackEvents] for asynchronous confirmation.
     */
    fun selectAudioTrack(track: AudioTrack?): TrackSelectionResult

    fun selectAudioTrack(trackId: String?): TrackSelectionResult =
        trackId
            ?.let { id ->
                availableAudioTracks
                    .firstOrNull { it.id == id }
                    ?.let { track -> selectAudioTrack(track) }
                    ?: TrackSelectionResult.NotFound(id)
            }
            ?: selectAudioTrack(null as AudioTrack?)

    // Subtitle management
    var subtitlesEnabled: Boolean
    var currentSubtitleTrack: SubtitleTrack?
    val availableSubtitleTracks: List<SubtitleTrack>
    var subtitleTextStyle: TextStyle
    var subtitleBackgroundColor: Color
    var subtitleOffset: Duration
        get() = Duration.ZERO
        set(value) {}

    /**
     * Requests a subtitle track. The result reports whether the backend accepted the request;
     * observe [currentSubtitleTrack] or [playbackEvents] for asynchronous confirmation.
     */
    fun selectSubtitleTrack(track: SubtitleTrack?): TrackSelectionResult

    fun selectSubtitleTrack(trackId: String?): TrackSelectionResult =
        trackId
            ?.let { id ->
                availableSubtitleTracks
                    .firstOrNull { it.id == id }
                    ?.let { track -> selectSubtitleTrack(track) }
                    ?: TrackSelectionResult.NotFound(id)
            }
            ?: selectSubtitleTrack(null as SubtitleTrack?)

    fun addSubtitleTrack(track: SubtitleTrack)

    fun removeSubtitleTrack(trackId: String)

    fun removeSubtitleTrack(track: SubtitleTrack) {
        removeSubtitleTrack(track.id)
    }

    fun clearExternalSubtitleTracks()

    /**
     * Replaces only app-provided subtitle tracks while preserving embedded tracks discovered by the platform backend.
     *
     * Tracks passed here are treated as external even if their [SubtitleTrack.isEmbedded] flag is set. If the
     * currently selected external track is present in the replacement list with the same id, it is selected again.
     * Otherwise external subtitle selection is cleared by [clearExternalSubtitleTracks].
     */
    fun replaceExternalSubtitleTracks(tracks: List<SubtitleTrack>) {
        val previouslySelectedExternalTrackId = currentSubtitleTrack?.takeIf { it.isExternal }?.id
        clearExternalSubtitleTracks()
        tracks.forEach(::addSubtitleTrack)
        if (previouslySelectedExternalTrackId != null) {
            val replacementTrack =
                availableSubtitleTracks.firstOrNull { track ->
                    track.id == previouslySelectedExternalTrackId && track.isExternal
                }
            if (replacementTrack != null) {
                selectSubtitleTrack(replacementTrack)
            }
        }
    }

    fun disableSubtitles(): TrackSelectionResult

    // HLS quality management
    val availableHlsQualities: List<HlsQualityVariant>
        get() = emptyList()
    val currentHlsQuality: HlsQualityVariant?
        get() = null
    val hlsQualityMode: HlsQualityMode
        get() = HlsQualityMode.AUTO

    /**
     * Selects a concrete HLS variant by id, or automatic variant selection when [variantId] is null.
     */
    fun selectHlsQuality(variantId: String?): HlsQualitySelectionResult = HlsQualitySelectionResult.NotSupported

    fun selectAutoHlsQuality(): HlsQualitySelectionResult = selectHlsQuality(null)

    // Cache management

    /**
     * Clears the shared video cache, removing all cached media data from disk.
     *
     * Returns [CacheClearResult.NotSupported] on platforms without a shared video cache and
     * [CacheClearResult.Disabled] when the platform supports caching but this player was created with caching disabled.
     */
    fun clearCache(): CacheClearResult = CacheClearResult.NotSupported

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
    val events: SharedFlow<PlaybackEvent> = PlaybackEventDispatcher().events
}

/**
 * A library-issued handle for a [VideoPlayerState] that can be passed to the strict
 * [VideoPlayerSurface] overload.
 *
 * The constructor is internal so applications cannot mark an arbitrary custom state as platform-renderable.
 */
@Stable
class RenderableVideoPlayerState internal constructor(
    internal val platformState: VideoPlayerState,
) : VideoPlayerState by platformState

/**
 *  Create platform-specific video player state. Supported platforms include Windows,
 *  macOS, and Linux.
 *
 * @param audioMode The audio mode configuration for the player.
 * @param cacheConfig Optional caching configuration. When [CacheConfig.enabled] is `true`,
 *   video data fetched via [VideoPlayerState.openUri] is cached on disk so that subsequent
 *   plays of the same URI avoid a full re-download. Currently only effective on Android.
 */
expect fun createVideoPlayerState(
    audioMode: AudioMode = AudioMode(),
    cacheConfig: CacheConfig = CacheConfig(),
    playbackOptions: VideoPlaybackOptions = VideoPlaybackOptions(),
): VideoPlayerState

/**
 * Creates a platform-backed player state with a type that is accepted by the strict
 * [VideoPlayerSurface] overload.
 */
fun createRenderableVideoPlayerState(
    audioMode: AudioMode = AudioMode(),
    cacheConfig: CacheConfig = CacheConfig(),
    playbackOptions: VideoPlaybackOptions = VideoPlaybackOptions(),
): RenderableVideoPlayerState =
    createVideoPlayerState(audioMode, cacheConfig, playbackOptions).asRenderableVideoPlayerState()

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
 *   plays of the same URI avoid a full re-download. Currently only effective on Android.
 * @return The remembered, platform-renderable player state.
 */
@Composable
fun rememberVideoPlayerState(
    audioMode: AudioMode = AudioMode(),
    cacheConfig: CacheConfig = CacheConfig(),
    playbackOptions: VideoPlaybackOptions = VideoPlaybackOptions(),
): VideoPlayerState {
    val playerState =
        remember(audioMode, cacheConfig, playbackOptions) {
            createVideoPlayerState(audioMode, cacheConfig, playbackOptions)
        }
    DisposableEffect(playerState) {
        onDispose {
            playerState.dispose()
        }
    }
    return playerState
}

/**
 * Remembers a platform-backed player state with a type that is accepted by the strict
 * [VideoPlayerSurface] overload.
 */
@Composable
fun rememberRenderableVideoPlayerState(
    audioMode: AudioMode = AudioMode(),
    cacheConfig: CacheConfig = CacheConfig(),
    playbackOptions: VideoPlaybackOptions = VideoPlaybackOptions(),
): RenderableVideoPlayerState {
    val playerState = rememberVideoPlayerState(audioMode, cacheConfig, playbackOptions)
    return remember(playerState) { playerState.asRenderableVideoPlayerState() }
}

internal fun VideoPlayerState.asRenderableVideoPlayerState(): RenderableVideoPlayerState =
    this as? RenderableVideoPlayerState ?: RenderableVideoPlayerState(this)

/**
 * Renderable state for previews, screenshots, and UI tests that do not create a native player.
 *
 * The mutable properties in the primary constructor are retained for binary compatibility with the published data
 * class API. Consequently their generated setters, [copy], and mutable [availableSubtitleTracks] cannot participate
 * in the runtime disposal guard without breaking that ABI. All player commands and properties implemented in the
 * class body do reject use after [dispose].
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
    override val positionText: String = "00:05",
    override val durationText: String = "00:10",
    override val currentTime: Duration = 5.seconds,
    override val preciseCurrentTime: Duration = currentTime,
    override val duration: Duration = 10.seconds,
    override var isFullscreen: Boolean = false,
    override val aspectRatio: Float = 1.7f,
    override val error: VideoPlayerError? = null,
    override val metadata: VideoMetadata = VideoMetadata(),
    override val renderingInfo: VideoRenderingInfo = VideoRenderingInfo(),
    override var projection: VideoProjectionSettings = VideoProjectionSettings(),
    override var projectionView: VideoProjectionViewSettings = VideoProjectionViewSettings(),
    override var projectionViewControlMode: VideoProjectionViewControlMode = VideoProjectionViewControlMode.AUTO,
    override var projectionTextureCrop: VideoTextureCrop = VideoTextureCrop(),
    override var currentAudioTrack: AudioTrack? = null,
    override val availableAudioTracks: List<AudioTrack> = emptyList(),
    override var subtitlesEnabled: Boolean = false,
    override var currentSubtitleTrack: SubtitleTrack? = null,
    override val availableSubtitleTracks: MutableList<SubtitleTrack> = mutableListOf(),
    override var subtitleTextStyle: TextStyle = TextStyle.Default,
    override var subtitleBackgroundColor: Color = Color.Transparent,
    override val isPipSupported: Boolean = false,
    override var isPipActive: Boolean = false,
    override var isPipEnabled: Boolean = false,
    override var onPlaybackEnded: (() -> Unit)? = null,
    override var onRestart: (() -> Unit)? = null,
) : VideoPlayerState {
    private var disposed = false
    private var previewSubtitleOffset = Duration.ZERO

    override var subtitleOffset: Duration
        get() = previewSubtitleOffset
        set(value) {
            ensureNotDisposed()
            previewSubtitleOffset = value
        }

    private fun ensureNotDisposed() {
        check(!disposed) { "VideoPlayerState has been disposed" }
    }

    override fun play() {
        ensureNotDisposed()
    }

    override fun pause() {
        ensureNotDisposed()
    }

    override fun stop() {
        ensureNotDisposed()
    }

    override fun releaseSource() {
        ensureNotDisposed()
    }

    override suspend fun enterPip(): PipResult {
        ensureNotDisposed()
        return PipResult.NotSupported
    }

    override fun seekTo(time: Duration) {
        ensureNotDisposed()
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun seekTo(value: Float) {
        ensureNotDisposed()
    }

    override fun seekStart(value: Float) {
        ensureNotDisposed()
        userDragging = true
        sliderPos = value
    }

    override fun seekFinished() {
        ensureNotDisposed()
        userDragging = false
    }

    override fun toggleFullscreen() {
        ensureNotDisposed()
    }

    override fun openUri(
        uri: String,
        initializePlayerState: InitialPlayerState,
        requestHeaders: Map<String, String>,
    ) {
        ensureNotDisposed()
    }

    override fun openFile(
        file: PlatformFile,
        initializePlayerState: InitialPlayerState,
    ) {
        ensureNotDisposed()
    }

    override fun openAsset(
        fileName: String,
        initializePlayerState: InitialPlayerState,
    ) {
        ensureNotDisposed()
        throw UnsupportedOperationException("openAsset is not supported on this platform")
    }

    override fun clearError() {
        ensureNotDisposed()
    }

    override fun selectAudioTrack(track: AudioTrack?): TrackSelectionResult {
        ensureNotDisposed()
        if (track != null && availableAudioTracks.none { it.id == track.id }) {
            return TrackSelectionResult.NotFound(track.id)
        }
        currentAudioTrack = track
        return track.audioTrackSelectionResult()
    }

    override fun selectAudioTrack(trackId: String?): TrackSelectionResult {
        ensureNotDisposed()
        return trackId
            ?.let { id ->
                availableAudioTracks
                    .firstOrNull { it.id == id }
                    ?.let(::selectAudioTrack)
                    ?: TrackSelectionResult.NotFound(id)
            }
            ?: selectAudioTrack(null as AudioTrack?)
    }

    override fun selectSubtitleTrack(track: SubtitleTrack?): TrackSelectionResult {
        ensureNotDisposed()
        if (track != null && track.isEmbedded && availableSubtitleTracks.none { it.id == track.id }) {
            return TrackSelectionResult.NotFound(track.id)
        }
        currentSubtitleTrack = track
        subtitlesEnabled = track != null
        return track.subtitleTrackSelectionResult()
    }

    override fun selectSubtitleTrack(trackId: String?): TrackSelectionResult {
        ensureNotDisposed()
        return trackId
            ?.let { id ->
                availableSubtitleTracks
                    .firstOrNull { it.id == id }
                    ?.let(::selectSubtitleTrack)
                    ?: TrackSelectionResult.NotFound(id)
            }
            ?: selectSubtitleTrack(null as SubtitleTrack?)
    }

    override fun addSubtitleTrack(track: SubtitleTrack) {
        ensureNotDisposed()
        val externalTrack = track.copy(isEmbedded = false)
        availableSubtitleTracks.removeAll { it.id == externalTrack.id }
        availableSubtitleTracks.add(externalTrack)
    }

    override fun removeSubtitleTrack(trackId: String) {
        ensureNotDisposed()
        val selectedTrack = currentSubtitleTrack
        availableSubtitleTracks.removeAll { it.id == trackId && it.isExternal }
        if (selectedTrack?.id == trackId && selectedTrack.isExternal) {
            disableSubtitles()
        }
    }

    override fun clearExternalSubtitleTracks() {
        ensureNotDisposed()
        val selectedTrack = currentSubtitleTrack
        availableSubtitleTracks.removeAll { it.isExternal }
        if (selectedTrack?.isExternal == true) {
            disableSubtitles()
        }
    }

    override fun disableSubtitles(): TrackSelectionResult {
        ensureNotDisposed()
        currentSubtitleTrack = null
        subtitlesEnabled = false
        return TrackSelectionResult.Disabled
    }

    override fun selectHlsQuality(variantId: String?): HlsQualitySelectionResult {
        ensureNotDisposed()
        return HlsQualitySelectionResult.NotSupported
    }

    override fun clearCache(): CacheClearResult {
        ensureNotDisposed()
        return CacheClearResult.NotSupported
    }

    override fun dispose() {
        if (disposed) return
        disposed = true
    }
}

internal fun String.normalizedAssetPath(): String {
    val normalized = trim().trimStart('/', '\\')
    require(normalized.isNotEmpty()) { "Asset file name must not be blank." }
    return normalized
}

internal fun String.normalizedAssetUri(): String = "asset:///${normalizedAssetPath()}"

internal fun Map<String, String>.sanitizedRequestHeaders(): Map<String, String> =
    mapNotNull { (name, value) ->
        val headerName = name.trim()
        val headerValue = value.trim()
        when {
            headerName.isEmpty() || headerValue.contains('\r') || headerValue.contains('\n') -> null
            !headerName.isValidHeaderName() -> null
            headerName.isForbiddenRequestHeaderName() -> null
            else -> headerName to headerValue
        }
    }.toMap()

internal fun Map<String, String>.requestHeadersLineString(lineSeparator: String = "\r\n"): String =
    sanitizedRequestHeaders()
        .entries
        .joinToString(lineSeparator) { (name, value) -> "$name: $value" }

internal fun Map<String, String>.requestHeadersJsonObjectString(): String =
    sanitizedRequestHeaders()
        .entries
        .joinToString(prefix = "{", postfix = "}") { (name, value) ->
            "\"${name.jsonEscaped()}\":\"${value.jsonEscaped()}\""
        }

private fun String.jsonEscaped(): String =
    buildString(length + JSON_ESCAPE_EXTRA_CAPACITY) {
        for (char in this@jsonEscaped) {
            when (char) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> {
                    if (char.code < JSON_CONTROL_CHAR_LIMIT) {
                        append("\\u")
                        append(
                            char.code
                                .toString(JSON_HEX_RADIX)
                                .padStart(JSON_UNICODE_ESCAPE_LENGTH, '0'),
                        )
                    } else {
                        append(char)
                    }
                }
            }
        }
    }

private fun String.isValidHeaderName(): Boolean =
    all { char ->
        char.code in HEADER_NAME_MIN_CHAR_CODE..HEADER_NAME_MAX_CHAR_CODE && char !in HEADER_NAME_SEPARATORS
    }

private fun String.isForbiddenRequestHeaderName(): Boolean {
    val normalized = lowercase()
    return normalized in FORBIDDEN_REQUEST_HEADER_NAMES || normalized.startsWith("proxy-")
}

private const val JSON_ESCAPE_EXTRA_CAPACITY = 8
private const val JSON_CONTROL_CHAR_LIMIT = 0x20
private const val JSON_HEX_RADIX = 16
private const val JSON_UNICODE_ESCAPE_LENGTH = 4
private const val HEADER_NAME_MIN_CHAR_CODE = 33
private const val HEADER_NAME_MAX_CHAR_CODE = 126
private const val HEADER_NAME_SEPARATORS = "()<>@,;:\\\"/[]?={} \t"

private val FORBIDDEN_REQUEST_HEADER_NAMES =
    setOf(
        "connection",
        "content-length",
        "host",
        "keep-alive",
        "te",
        "trailer",
        "transfer-encoding",
        "upgrade",
    )
