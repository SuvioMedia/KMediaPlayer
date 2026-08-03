package io.github.kdroidfilter.composemediaplayer.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import dev.nucleusframework.application.LocalNucleusWindow
import io.github.kdroidfilter.composemediaplayer.VideoPlayerState
import kotlinx.coroutines.flow.collect

/**
 * Presents the current Tao window in native full screen.
 *
 * The legacy implementation opened a second Java-owned window and moved rendering between peers. Tao
 * keeps one native window and one native child surface, so resize, HDR and input state stay intact.
 */
@Composable
public fun openFullscreenWindow(
    playerState: VideoPlayerState,
    @Suppress("UNUSED_PARAMETER")
    renderSurface: @Composable (VideoPlayerState, Modifier, Boolean) -> Unit,
) {
    val window = LocalNucleusWindow.current
    LaunchedEffect(playerState.isFullscreen, window) {
        if (window.isFullscreen != playerState.isFullscreen) {
            window.setFullscreen(playerState.isFullscreen)
        }
    }
    LaunchedEffect(playerState, window) {
        window.fullscreenFlow.collect { nativeFullscreen ->
            if (playerState.isFullscreen != nativeFullscreen) {
                playerState.isFullscreen = nativeFullscreen
            }
        }
    }
}
