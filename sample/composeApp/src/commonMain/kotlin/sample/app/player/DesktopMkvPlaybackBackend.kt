package sample.app.player

import androidx.compose.runtime.Composable
import io.github.kdroidfilter.composemediaplayer.VideoPlaybackOptions
import io.github.kdroidfilter.composemediaplayer.VideoPlayerState

internal enum class DesktopMkvPlaybackBackend(val label: String) {
    AUTO("Auto"),
    PLATFORM("Platform"),
    LIBVLC_NATIVE("libVLC native"),
    KMEDIA_BRIDGE_HLS("KMediaBridge HLS"),
    VLC_HLS("VLC HLS"),
    MPV("MPV"),
}

internal data class DesktopMkvPlaybackBackendOption(
    val backend: DesktopMkvPlaybackBackend,
    val enabled: Boolean,
    val status: String,
    val installHint: String? = null,
)

internal expect val desktopMkvPlaybackBackendSelectionAvailable: Boolean

internal expect fun desktopMkvPlaybackBackendOptions(): List<DesktopMkvPlaybackBackendOption>

internal expect fun applyDesktopMkvPlaybackBackend(backend: DesktopMkvPlaybackBackend)

internal expect fun restoreDesktopMkvPlaybackBackend()

@Composable
internal expect fun rememberSampleVideoPlayerState(
    backend: DesktopMkvPlaybackBackend,
    playbackOptions: VideoPlaybackOptions,
): VideoPlayerState
