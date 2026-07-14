@file:OptIn(ExperimentalForeignApi::class)

package io.github.kdroidfilter.composemediaplayer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import io.github.kdroidfilter.composemediaplayer.util.FullScreenLayout
import kotlinx.cinterop.ExperimentalForeignApi

/**
 * Opens a fullscreen view for the video player on iOS.
 * This function is called when the user toggles fullscreen mode.
 *
 * @param playerState The player state to use in the fullscreen view
 * @param renderSurface A composable function that renders the video player surface
 */
@Composable
fun openFullscreenView(
    playerState: VideoPlayerState,
    renderSurface: @Composable (VideoPlayerState, Modifier, Boolean) -> Unit,
) {
    FullscreenVideoPlayerView(playerState, renderSurface)
}

/**
 * A composable function that creates a fullscreen view for the video player on iOS.
 *
 * @param renderSurface A composable function that renders the video player surface
 */
@Composable
private fun FullscreenVideoPlayerView(
    playerState: VideoPlayerState,
    renderSurface: @Composable (VideoPlayerState, Modifier, Boolean) -> Unit,
) {
    fun exitFullScreen() {
        playerState.isFullscreen = false
    }

    // Create a fullscreen view using FullScreenLayout
    FullScreenLayout(
        onDismissRequest = { exitFullScreen() },
    ) {
        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            renderSurface(playerState, Modifier.fillMaxSize(), true)
        }
    }
}
