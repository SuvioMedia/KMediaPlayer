package io.github.kdroidfilter.composemediaplayer.mac

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import io.github.kdroidfilter.composemediaplayer.subtitle.ComposeSubtitleLayer
import io.github.kdroidfilter.composemediaplayer.util.drawScaledImage
import io.github.kdroidfilter.composemediaplayer.util.toCanvasModifier

/**
 * A Composable function that renders a video player surface for MacOS.
 * Fills the entire canvas area with the video frame while maintaining aspect ratio.
 *
 * @param playerState The state object that encapsulates the AVPlayer logic for MacOS.
 * @param modifier An optional Modifier for customizing the layout.
 * @param contentScale Controls how the video content should be scaled inside the surface.
 *                    This affects how the video is displayed when its dimensions don't match
 *                    the surface dimensions.
 * @param overlay Optional composable content to be displayed on top of the video surface.
 *               This can be used to add custom controls, information, or any UI elements.
 * @param isInFullscreenWindow Whether this surface is already being displayed in a fullscreen window.
 */
@Composable
fun MacVideoPlayerSurface(
    playerState: MacVideoPlayerState,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
    overlay: @Composable () -> Unit = {},
    isInFullscreenWindow: Boolean = false,
) {
    Box(
        modifier =
            modifier.onSizeChanged { size ->
                playerState.onResized(size.width, size.height)
            },
        contentAlignment = Alignment.Center,
    ) {
        // Only render video in this surface if we're not in fullscreen mode or if this is the fullscreen window.
        val shouldRenderVideo = playerState.hasMedia && (!playerState.isFullscreen || isInFullscreenWindow)
        if (shouldRenderVideo) {
            // Force recomposition when currentFrameState changes
            val currentFrame by remember(playerState) { playerState.currentFrameState }

            currentFrame?.let { frame ->
                Canvas(
                    modifier =
                        contentScale.toCanvasModifier(
                            playerState.aspectRatio,
                            playerState.metadata.width,
                            playerState.metadata.height,
                        ),
                ) {
                    drawScaledImage(
                        image = frame,
                        dstSize = IntSize(size.width.toInt(), size.height.toInt()),
                        contentScale = contentScale,
                    )
                }
            }

            // Add Compose-based subtitle layer
            if (playerState.subtitlesEnabled &&
                playerState.currentSubtitleTrack != null &&
                playerState.currentSubtitleTrack?.isEmbedded != true &&
                !playerState.usesLibAssSubtitleOverlay
            ) {
                val currentTime =
                    if (playerState.userDragging) {
                        playerState.duration * (playerState.sliderPos / 1000.0).coerceIn(0.0, 1.0)
                    } else {
                        playerState.currentTime
                    }

                ComposeSubtitleLayer(
                    currentTime = currentTime,
                    duration = playerState.duration,
                    isPlaying = playerState.isPlaying,
                    subtitleTrack = playerState.currentSubtitleTrack,
                    subtitlesEnabled = playerState.subtitlesEnabled,
                    textStyle = playerState.subtitleTextStyle,
                    backgroundColor = playerState.subtitleBackgroundColor,
                )
            }
        }

        // Render the overlay content on top of the video with fillMaxSize modifier
        // to ensure it takes the full height of the parent Box
        Box(modifier = Modifier.fillMaxSize()) {
            overlay()
        }
    }

    if (playerState.isFullscreen && !isInFullscreenWindow) {
        openFullscreenWindow(playerState, overlay = overlay, contentScale = contentScale)
    }
}
