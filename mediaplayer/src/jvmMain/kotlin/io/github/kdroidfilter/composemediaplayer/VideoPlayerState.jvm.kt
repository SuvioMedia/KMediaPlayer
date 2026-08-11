package io.github.kdroidfilter.composemediaplayer

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import io.github.kdroidfilter.composemediaplayer.desktop.TaoPlaybackSurfaceProvider
import io.github.kdroidfilter.composemediaplayer.linux.LinuxVideoPlayerState
import io.github.kdroidfilter.composemediaplayer.mac.MacVideoPlayerState
import io.github.kdroidfilter.composemediaplayer.util.CurrentPlatform
import io.github.kdroidfilter.composemediaplayer.windows.WindowsVideoPlayerState
import io.github.vinceglb.filekit.PlatformFile
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.time.Duration

actual fun createVideoPlayerState(
    audioMode: AudioMode,
    cacheConfig: CacheConfig,
    playbackOptions: VideoPlaybackOptions,
): VideoPlayerState = DefaultVideoPlayerState(playbackOptions)

private fun createJvmPlatformPlayerState(playbackOptions: VideoPlaybackOptions): VideoPlayerState =
    when (CurrentPlatform.os) {
        CurrentPlatform.OS.WINDOWS -> WindowsVideoPlayerState(playbackOptions)
        CurrentPlatform.OS.MAC -> {
            if (!CurrentPlatform.isSupportedMacOsArchitecture) {
                throw UnsupportedOperationException(
                    "Compose Media Player for macOS requires Apple Silicon (arm64).",
                )
            }
            MacVideoPlayerState(playbackOptions)
        }
        CurrentPlatform.OS.LINUX -> LinuxVideoPlayerState(playbackOptions)
    }

/**
 * Represents the state and behavior of a video player. This class provides properties
 * and methods to control video playback, manage the playback state, and interact with
 * platform-specific implementations.
 *
 * Properties:
 * - `isPlaying`: Indicates whether the video is currently playing.
 * - `volume`: Controls the playback volume. Valid values are within the range of 0.0 (muted) to 1.0 (maximum volume).
 * - `sliderPos`: Represents the current playback position as a normalized value between 0.0 and 1.0.
 * - `userDragging`: Denotes whether the user is manually adjusting the playback position.
 * - `loop`: Specifies if the video should loop when it reaches the end.
 * - `positionText`: Returns the current playback position as a formatted string.
 * - `durationText`: Returns the total duration of the video as a formatted string.
 *
 * Methods:
 * - `openUri(uri: String)`: Opens a video file or URL for playback.
 * - `play()`: Starts or resumes video playback.
 * - `pause()`: Pauses video playback.
 * - `stop()`: Stops playback and resets the player state.
 * - `seekTo(value: Float)`: Seeks to a specific playback position based on the provided normalized value.
 * - `dispose()`: Releases resources used by the video player and disposes of the state.
 */
@Stable
@OptIn(ExperimentalComposeMediaPlayerBackendApi::class)
open class DefaultVideoPlayerState(
    playbackOptions: VideoPlaybackOptions = VideoPlaybackOptions(),
) : VideoPlayerState,
    TaoPlaybackSurfaceProvider {
    val delegate: VideoPlayerState =
        EventingVideoPlayerState(
            SynchronizedExternalAudioVideoPlayerState(
                primaryState = createJvmPlatformPlayerState(playbackOptions),
                engineFactory = {
                    VideoPlayerStateExternalAudioPlaybackEngine(
                        createJvmPlatformPlayerState(playbackOptions),
                    )
                },
            ),
        )

    override var projection: VideoProjectionSettings
        get() = delegate.projection
        set(value) {
            delegate.projection = value
        }
    override var projectionView: VideoProjectionViewSettings
        get() = delegate.projectionView
        set(value) {
            delegate.projectionView = value
        }
    override var projectionViewControlMode: VideoProjectionViewControlMode
        get() = delegate.projectionViewControlMode
        set(value) {
            delegate.projectionViewControlMode = value
        }
    override var projectionTextureCrop: VideoTextureCrop
        get() = delegate.projectionTextureCrop
        set(value) {
            delegate.projectionTextureCrop = value
        }

    override val hasMedia: Boolean get() = delegate.hasMedia
    override val mediaSessionId: Long get() = delegate.mediaSessionId
    override val isPlaying: Boolean get() = delegate.isPlaying
    override val isLoading: Boolean get() = delegate.isLoading
    override val isSeeking: Boolean get() = delegate.isSeeking
    override val isBuffering: Boolean get() = delegate.isBuffering
    override val loadingState: PlaybackLoadingState get() = delegate.loadingState
    override val playbackEvents: SharedFlow<PlaybackEvent> get() = delegate.playbackEvents
    override val diagnostics: PlaybackDiagnostics get() = delegate.diagnostics
    override val capabilities: PlayerCapabilities get() = delegate.capabilities
    override val colorPipelineStatus: StateFlow<VideoColorPipelineStatus> get() = delegate.colorPipelineStatus
    override val error: VideoPlayerError? get() = delegate.error
    override var volume: Float
        get() = delegate.volume
        set(value) {
            delegate.volume = value
        }
    override var sliderPos: Float
        get() = delegate.sliderPos
        set(value) {
            delegate.sliderPos = value
        }
    override var userDragging: Boolean
        get() = delegate.userDragging
        set(value) {
            delegate.userDragging = value
        }
    override var loop: Boolean
        get() = delegate.loop
        set(value) {
            delegate.loop = value
        }

    override var playbackSpeed: Float
        get() = delegate.playbackSpeed
        set(value) {
            delegate.playbackSpeed = value
        }

    override var isFullscreen: Boolean
        get() = delegate.isFullscreen
        set(value) {
            delegate.isFullscreen = value
        }

    override val metadata: VideoMetadata get() = delegate.metadata
    override val renderingInfo: VideoRenderingInfo get() = delegate.renderingInfo
    override val aspectRatio: Float get() = delegate.aspectRatio

    override var currentAudioTrack: AudioTrack?
        get() = delegate.currentAudioTrack
        set(value) {
            delegate.currentAudioTrack = value
        }
    override val availableAudioTracks: List<AudioTrack>
        get() = delegate.availableAudioTracks

    override val externalAudioTracks: List<ExternalAudioTrack>
        get() = delegate.externalAudioTracks
    override val externalAudioPlaybackStatus: ExternalAudioPlaybackStatus
        get() = delegate.externalAudioPlaybackStatus

    override fun selectAudioTrack(track: AudioTrack?): TrackSelectionResult = delegate.selectAudioTrack(track)

    override fun addExternalAudioTrack(track: ExternalAudioTrack) = delegate.addExternalAudioTrack(track)

    override fun removeExternalAudioTrack(trackId: String) = delegate.removeExternalAudioTrack(trackId)

    override fun clearExternalAudioTracks() = delegate.clearExternalAudioTracks()

    override fun replaceExternalAudioTracks(tracks: List<ExternalAudioTrack>) =
        delegate.replaceExternalAudioTracks(tracks)

    override var subtitlesEnabled: Boolean
        get() = delegate.subtitlesEnabled
        set(value) {
            delegate.subtitlesEnabled = value
        }
    override var currentSubtitleTrack: SubtitleTrack?
        get() = delegate.currentSubtitleTrack
        set(value) {
            delegate.currentSubtitleTrack = value
        }
    override val availableSubtitleTracks = delegate.availableSubtitleTracks
    override var subtitleTextStyle: TextStyle
        get() = delegate.subtitleTextStyle
        set(value) {
            delegate.subtitleTextStyle = value
        }
    override var subtitleBackgroundColor: Color
        get() = delegate.subtitleBackgroundColor
        set(value) {
            delegate.subtitleBackgroundColor = value
        }

    override fun selectSubtitleTrack(track: SubtitleTrack?): TrackSelectionResult = delegate.selectSubtitleTrack(track)

    override fun addSubtitleTrack(track: SubtitleTrack) = delegate.addSubtitleTrack(track)

    override fun removeSubtitleTrack(trackId: String) = delegate.removeSubtitleTrack(trackId)

    override fun clearExternalSubtitleTracks() = delegate.clearExternalSubtitleTracks()

    override fun disableSubtitles(): TrackSelectionResult = delegate.disableSubtitles()

    override val positionText: String get() = delegate.positionText
    override val durationText: String get() = delegate.durationText
    override val currentTime: Duration get() = delegate.currentTime
    override val preciseCurrentTime: Duration get() = delegate.preciseCurrentTime
    override val duration: Duration get() = delegate.duration
    override val chapters: List<MediaChapter> get() = delegate.chapters
    override val bufferedRanges: List<BufferedRange> get() = delegate.bufferedRanges
    override val bufferedPercent: Float get() = delegate.bufferedPercent
    override val availableHlsQualities: List<HlsQualityVariant> get() = delegate.availableHlsQualities
    override val currentHlsQuality: HlsQualityVariant? get() = delegate.currentHlsQuality
    override val hlsQualityMode: HlsQualityMode get() = delegate.hlsQualityMode
    override var subtitleOffset: Duration
        get() = delegate.subtitleOffset
        set(value) {
            delegate.subtitleOffset = value
        }

    override fun openUri(
        uri: String,
        initializePlayerState: InitialPlayerState,
        requestHeaders: Map<String, String>,
    ) = delegate.openUri(uri, initializePlayerState, requestHeaders)

    override fun openSource(
        source: MediaSourceSpec,
        initializePlayerState: InitialPlayerState,
        requestHeaders: Map<String, String>,
    ) = delegate.openSource(source, initializePlayerState, requestHeaders)

    override fun prepare(
        uri: String,
        initializePlayerState: InitialPlayerState,
        requestHeaders: Map<String, String>,
    ) = delegate.prepare(uri, initializePlayerState, requestHeaders)

    override fun openFile(
        file: PlatformFile,
        initializePlayerState: InitialPlayerState,
    ) = delegate.openFile(file, initializePlayerState)

    override fun openAsset(
        fileName: String,
        initializePlayerState: InitialPlayerState,
    ) = delegate.openAsset(fileName, initializePlayerState)

    override fun play() = delegate.play()

    override fun pause() = delegate.pause()

    override fun stop() = delegate.stop()

    override fun seekTo(time: Duration) = delegate.seekTo(time)

    override fun seekBy(delta: Duration) = delegate.seekBy(delta)

    override fun seekToProgress(progress: Float) = delegate.seekToProgress(progress)

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun seekTo(value: Float) = delegate.seekTo(value)

    override fun toggleFullscreen() = delegate.toggleFullscreen()

    override fun canPlaySource(
        uri: String,
        mimeType: String?,
    ) = delegate.canPlaySource(uri, mimeType)

    override fun selectHlsQuality(variantId: String?): HlsQualitySelectionResult = delegate.selectHlsQuality(variantId)

    override fun releaseSource() = delegate.releaseSource()

    override fun dispose() = delegate.dispose()

    override var onPlaybackEnded: (() -> Unit)?
        get() = delegate.onPlaybackEnded
        set(value) {
            delegate.onPlaybackEnded = value
        }

    override var onRestart: (() -> Unit)?
        get() = delegate.onRestart
        set(value) {
            delegate.onRestart = value
        }

    override fun restart() = delegate.restart()

    override fun clearError() = delegate.clearError()

    @Composable
    override fun RenderTaoPlaybackSurface(
        modifier: Modifier,
        contentScale: ContentScale,
        overlay: @Composable () -> Unit,
        onSurfaceAttached: () -> Unit,
    ) {
        JvmTaoPlaybackSurface(
            playerState = delegate,
            modifier = modifier,
            contentScale = contentScale,
            overlay = overlay,
            onSurfaceAttached = onSurfaceAttached,
        )
    }
}
