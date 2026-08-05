@file:OptIn(io.github.kdroidfilter.composemediaplayer.ExperimentalComposeMediaPlayerBackendApi::class)

package io.github.kdroidfilter.composemediaplayer.windows

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
import io.github.kdroidfilter.composemediaplayer.JvmProjectedVideoCanvas
import io.github.kdroidfilter.composemediaplayer.VideoPlayerState
import io.github.kdroidfilter.composemediaplayer.desktop.tao.TaoNativeVideoSurface
import io.github.kdroidfilter.composemediaplayer.desktop.tao.TaoNativeVideoSurfaceKind
import io.github.kdroidfilter.composemediaplayer.desktop.tao.TaoNativeVideoView
import io.github.kdroidfilter.composemediaplayer.subtitle.ComposeSubtitleLayer
import io.github.kdroidfilter.composemediaplayer.util.toCanvasModifier

/** Renders Windows video through a native child HWND or the Java-toolkit-free Skia fallback. */
@Composable
internal fun WindowsVideoPlayerSurface(
    playerState: WindowsVideoPlayerState,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
    overlay: @Composable () -> Unit = {},
    onSurfaceAttached: () -> Unit = {},
) {
    val latestOnSurfaceAttached by rememberUpdatedState(onSurfaceAttached)
    val nativeSurfaceRequested =
        playerState.shouldUseWindowsHdrSurface() || playerState.shouldUseLibVlcNativeSurface()
    val videoModifier =
        contentScale.toCanvasModifier(
            playerState.aspectRatio,
            playerState.metadata.width,
            playerState.metadata.height,
        )

    val hostModifier =
        if (nativeSurfaceRequested) {
            modifier
        } else {
            modifier.onSizeChanged { size ->
                playerState.onResized(size.width, size.height)
            }
        }

    Box(
        modifier = hostModifier,
        contentAlignment = Alignment.Center,
    ) {
        if (nativeSurfaceRequested) {
            val surface =
                remember(playerState, playerState.nativeSurfaceGeneration, nativeSurfaceRequested) {
                    TaoNativeVideoSurface(
                        kind = TaoNativeVideoSurfaceKind.WINDOWS_HWND,
                        createHandle = playerState::createNativeVideoWindow,
                        disposeHandle = playerState::disposeNativeVideoWindow,
                    )
                }
            TaoNativeVideoView(
                surface = surface,
                // Keep controls and the native child bound to the complete player viewport;
                // the Win32 renderer is responsible for fitting the media within that child.
                modifier = Modifier.fillMaxSize(),
                overlay = { WindowsVideoOverlayContent(playerState, overlay) },
                onAttached = { latestOnSurfaceAttached() },
                onUnavailable = { latestOnSurfaceAttached() },
            )
        } else {
            val currentFrame by remember(playerState) { playerState.currentFrameState }
            currentFrame?.let { frame ->
                JvmProjectedVideoCanvas(
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
