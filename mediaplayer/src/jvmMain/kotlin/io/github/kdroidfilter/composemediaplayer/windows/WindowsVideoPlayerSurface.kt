@file:OptIn(io.github.kdroidfilter.composemediaplayer.ExperimentalComposeMediaPlayerBackendApi::class)

package io.github.kdroidfilter.composemediaplayer.windows

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import dev.nucleusframework.window.tao.TextureColorInfo
import dev.nucleusframework.window.tao.nucleusD3D11SharedTextureSource
import dev.nucleusframework.window.tao.rememberTextureViewController
import io.github.kdroidfilter.composemediaplayer.VideoPlayerState
import io.github.kdroidfilter.composemediaplayer.desktop.tao.DesktopColorManagedTextureVideoView
import io.github.kdroidfilter.composemediaplayer.desktop.tao.DesktopProjectedVideoCanvas
import io.github.kdroidfilter.composemediaplayer.subtitle.ComposeSubtitleLayer
import io.github.kdroidfilter.composemediaplayer.util.toCanvasModifier

/** Renders Windows video as a color-managed Nucleus texture or the explicit CPU/SDR canvas. */
@Composable
internal fun WindowsVideoPlayerSurface(
    playerState: WindowsVideoPlayerState,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
    overlay: @Composable () -> Unit = {},
    onSurfaceAttached: () -> Unit = {},
) {
    val latestOnSurfaceAttached by rememberUpdatedState(onSurfaceAttached)
    val textureSurfaceRequested = playerState.shouldUseWindowsHdrSurface()
    val videoModifier =
        contentScale.toCanvasModifier(
            playerState.aspectRatio,
            playerState.metadata.width,
            playerState.metadata.height,
        )

    val hostModifier =
        modifier.onSizeChanged { size ->
            playerState.onResized(size.width, size.height)
        }

    Box(
        modifier = hostModifier,
        contentAlignment = Alignment.Center,
    ) {
        if (textureSurfaceRequested) {
            val frame by remember(playerState) { playerState.currentColorManagedTextureFrameState }
            val controller = rememberTextureViewController()
            val source =
                frame?.let { current ->
                    remember(
                        current.sharedHandle,
                        current.width,
                        current.height,
                        current.extendedLinear,
                    ) {
                        nucleusD3D11SharedTextureSource(
                            sharedHandle = current.sharedHandle,
                            widthPx = current.width,
                            heightPx = current.height,
                            colorInfo =
                                if (current.extendedLinear) {
                                    TextureColorInfo.EXTENDED_LINEAR_SRGB_PREMULTIPLIED
                                } else {
                                    TextureColorInfo.SRGB_PREMULTIPLIED
                                },
                        )
                    }
                }
            LaunchedEffect(frame?.generation, frame?.frameSerial) {
                frame?.let { current ->
                    controller.markFrameAvailable()
                    playerState.onTextureProducerFrameSubmitted(current)
                }
            }
            DesktopColorManagedTextureVideoView(
                source = source,
                controller = controller,
                modifier = Modifier.fillMaxSize(),
                contentScale = contentScale,
                onHostCapabilitiesChanged = playerState::onTextureViewHostCapabilities,
                overlay = { WindowsVideoOverlayContent(playerState, overlay) },
                onSurfaceAttached = {
                    playerState.onColorManagedTextureHostAttached()
                    latestOnSurfaceAttached()
                },
            )
        } else {
            val currentFrame by remember(playerState) { playerState.currentFrameState }
            currentFrame?.let { frame ->
                DesktopProjectedVideoCanvas(
                    frame = frame,
                    projection = playerState.projection,
                    projectionView = playerState.projectionView,
                    textureCrop = playerState.projectionTextureCrop,
                    contentScale = contentScale,
                    modifier = videoModifier,
                )
            }
            WindowsVideoOverlayContent(playerState, overlay)
            DisposableEffect(playerState) {
                latestOnSurfaceAttached()
                onDispose { }
            }
        }
    }
}

@Composable
private fun WindowsVideoOverlayContent(
    playerState: WindowsVideoPlayerState,
    overlay: @Composable () -> Unit,
) {
    if (playerState.subtitlesEnabled &&
        playerState.currentSubtitleTrack != null &&
        playerState.currentSubtitleTrack?.isEmbedded != true &&
        !playerState.usesLibAssSubtitleOverlay
    ) {
        val currentTime =
            if (playerState.userDragging) {
                playerState.duration *
                    (playerState.sliderPos / VideoPlayerState.SLIDER_SCALE).toDouble().coerceIn(0.0, 1.0)
            } else {
                playerState.preciseCurrentTime
            } + playerState.subtitleOffset

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
    Box(modifier = Modifier.fillMaxSize()) { overlay() }
}
