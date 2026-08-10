@file:OptIn(io.github.kdroidfilter.composemediaplayer.ExperimentalComposeMediaPlayerBackendApi::class)

package io.github.kdroidfilter.composemediaplayer.linux

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import io.github.kdroidfilter.composemediaplayer.VideoPlayerState
import io.github.kdroidfilter.composemediaplayer.desktop.tao.DesktopColorManagedTextureVideoView
import io.github.kdroidfilter.composemediaplayer.desktop.tao.DesktopProjectedVideoCanvas
import io.github.kdroidfilter.composemediaplayer.subtitle.ComposeSubtitleLayer
import io.github.kdroidfilter.composemediaplayer.util.toCanvasModifier

/** Renders Linux video through Nucleus TextureView, or explicit CPU/SDR Compose mode. */
@Composable
internal fun LinuxVideoPlayerSurface(
    playerState: LinuxVideoPlayerState,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
    overlay: @Composable () -> Unit = {},
    onSurfaceAttached: () -> Unit = {},
) {
    val latestOnSurfaceAttached by rememberUpdatedState(onSurfaceAttached)
    val textureSurfaceRequested = playerState.shouldUseColorManagedTexture()
    val videoModifier =
        contentScale.toCanvasModifier(
            playerState.aspectRatio,
            playerState.metadata.width,
            playerState.metadata.height,
        )

    val hostModifier =
        modifier.onSizeChanged { size ->
            if (textureSurfaceRequested) {
                playerState.onTextureSurfaceResized(size.width, size.height)
            } else {
                playerState.onResized(size.width, size.height)
            }
        }

    Box(
        modifier = hostModifier,
        contentAlignment = Alignment.Center,
    ) {
        if (textureSurfaceRequested) {
            DesktopColorManagedTextureVideoView(
                streamController = playerState.textureStreamController,
                modifier = Modifier.fillMaxSize(),
                contentScale = contentScale,
                onHostCapabilitiesChanged = playerState::onTextureViewHostCapabilities,
                onSurfaceAttached = {
                    playerState.onColorManagedTextureHostAttached()
                    latestOnSurfaceAttached()
                },
                overlay = { LinuxVideoOverlayContent(playerState, overlay) },
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
            LinuxVideoOverlayContent(playerState, overlay)
            DisposableEffect(playerState) {
                latestOnSurfaceAttached()
                onDispose { }
            }
        }
    }
}

@Composable
private fun LinuxVideoOverlayContent(
    playerState: LinuxVideoPlayerState,
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
