package sample.app.player

import androidx.compose.runtime.Composable
import io.github.kdroidfilter.composemediaplayer.VideoPlaybackOptions
import io.github.kdroidfilter.composemediaplayer.VideoPlayerState
import io.github.kdroidfilter.composemediaplayer.rememberVideoPlayerState

internal actual val desktopMkvPlaybackBackendSelectionAvailable: Boolean = false

internal actual fun desktopMkvPlaybackBackendOptions(): List<DesktopMkvPlaybackBackendOption> = emptyList()

internal actual fun desktopMediaSourceAdapterOptions(): List<DesktopMediaSourceAdapterOption> = emptyList()

internal actual fun applyDesktopPlaybackSelection(
    backend: DesktopMkvPlaybackBackend,
    sourceAdapter: DesktopMediaSourceAdapter,
) {
}

internal actual fun restoreDesktopMkvPlaybackBackend() {
}

@Composable
internal actual fun rememberSampleVideoPlayer(
    backend: DesktopMkvPlaybackBackend,
    sourceAdapter: DesktopMediaSourceAdapter,
    playbackOptions: VideoPlaybackOptions,
): SampleVideoPlayerHandle {
    val state = rememberVideoPlayerState(playbackOptions = playbackOptions)
    return androidx.compose.runtime.remember(state) {
        SampleVideoPlayerHandle(
            playerState = state,
            openUriAction = { uri, initial -> state.openUri(uri, initial) },
            openFileAction = { file, initial -> state.openFile(file, initial) },
        )
    }
}
