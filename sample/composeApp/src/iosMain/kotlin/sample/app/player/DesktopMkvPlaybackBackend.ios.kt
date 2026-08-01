package sample.app.player

import androidx.compose.runtime.Composable
import io.github.kdroidfilter.composemediaplayer.VideoPlaybackOptions
import io.github.kdroidfilter.composemediaplayer.VideoPlayerState
import io.github.kdroidfilter.composemediaplayer.rememberVideoPlayerState

internal actual val desktopMkvPlaybackBackendSelectionAvailable: Boolean = false

internal actual fun desktopMkvPlaybackBackendOptions(): List<DesktopMkvPlaybackBackendOption> = emptyList()

internal actual fun applyDesktopMkvPlaybackBackend(backend: DesktopMkvPlaybackBackend) {
}

internal actual fun restoreDesktopMkvPlaybackBackend() {
}

@Composable
internal actual fun rememberSampleVideoPlayerState(
    backend: DesktopMkvPlaybackBackend,
    playbackOptions: VideoPlaybackOptions,
): VideoPlayerState = rememberVideoPlayerState(playbackOptions = playbackOptions)
