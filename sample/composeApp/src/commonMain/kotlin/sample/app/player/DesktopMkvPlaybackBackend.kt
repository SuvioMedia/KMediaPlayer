package sample.app.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import io.github.kdroidfilter.composemediaplayer.InitialPlayerState
import io.github.kdroidfilter.composemediaplayer.VideoPlaybackOptions
import io.github.kdroidfilter.composemediaplayer.VideoPlayerState
import io.github.vinceglb.filekit.PlatformFile

internal enum class DesktopMkvPlaybackBackend(val label: String) {
    AUTO("Auto"),
    PLATFORM("Platform"),
    LIBVLC_NATIVE("KMediaVlc TextureView"),
    MPV("MPV"),
}

internal enum class DesktopMediaSourceAdapter(val label: String) {
    AUTO("Auto"),
    DIRECT("Direct"),
    KMEDIA_BRIDGE("KMediaBridge / FFmpeg"),
    VLC_HLS("VLC HLS"),
}

internal data class DesktopMkvPlaybackBackendOption(
    val backend: DesktopMkvPlaybackBackend,
    val enabled: Boolean,
    val status: String,
    val installHint: String? = null,
)

internal data class DesktopMediaSourceAdapterOption(
    val adapter: DesktopMediaSourceAdapter,
    val enabled: Boolean,
    val status: String,
    val installHint: String? = null,
)

internal expect val desktopMkvPlaybackBackendSelectionAvailable: Boolean

internal expect fun desktopMkvPlaybackBackendOptions(): List<DesktopMkvPlaybackBackendOption>

internal expect fun desktopMediaSourceAdapterOptions(): List<DesktopMediaSourceAdapterOption>

internal expect fun applyDesktopPlaybackSelection(
    backend: DesktopMkvPlaybackBackend,
    sourceAdapter: DesktopMediaSourceAdapter,
)

internal expect fun restoreDesktopMkvPlaybackBackend()

@Composable
internal expect fun rememberSampleVideoPlayer(
    backend: DesktopMkvPlaybackBackend,
    sourceAdapter: DesktopMediaSourceAdapter,
    playbackOptions: VideoPlaybackOptions,
): SampleVideoPlayerHandle

internal interface SamplePlaybackSurfaceHost

@Stable
internal class SampleVideoPlayerHandle(
    val playerState: VideoPlayerState,
    val isPlaybackTransitioning: Boolean = false,
    val playbackTransitionError: String? = null,
    private val openUriAction: (String, InitialPlayerState) -> Unit,
    private val openFileAction: (PlatformFile, InitialPlayerState) -> Unit,
    private val surfaceAttachedAction: (VideoPlayerState) -> Unit = {},
    private val clearPlaybackTransitionErrorAction: () -> Unit = {},
    internal val playbackSurfaceHost: SamplePlaybackSurfaceHost? = null,
) {
    fun openUri(
        uri: String,
        initialPlayerState: InitialPlayerState,
    ) = openUriAction(uri, initialPlayerState)

    fun openFile(
        file: PlatformFile,
        initialPlayerState: InitialPlayerState,
    ) = openFileAction(file, initialPlayerState)

    fun notifySurfaceAttached(attachedPlayer: VideoPlayerState = playerState) = surfaceAttachedAction(attachedPlayer)

    fun clearPlaybackTransitionError() = clearPlaybackTransitionErrorAction()
}
