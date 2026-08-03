package io.github.kdroidfilter.composemediaplayer.mac

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
import io.github.kdroidfilter.composemediaplayer.desktop.DesktopNativeVideoSurface
import io.github.kdroidfilter.composemediaplayer.desktop.DesktopNativeVideoSurfaceKind
import io.github.kdroidfilter.composemediaplayer.desktop.DesktopNativeVideoView
import io.github.kdroidfilter.composemediaplayer.subtitle.ComposeSubtitleLayer
import io.github.kdroidfilter.composemediaplayer.util.toCanvasModifier

/** Renders macOS video through an AppKit `NSView` or the Java-toolkit-free Skia fallback. */
@Composable
internal fun MacVideoPlayerSurface(
    playerState: MacVideoPlayerState,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
    overlay: @Composable () -> Unit = {},
    @Suppress("UNUSED_PARAMETER") isInFullscreenWindow: Boolean = false,
) {
    MacVideoSurfaceContent(playerState, modifier, contentScale, overlay)
}

/** Full-player variant that reports when its native child has been created. */
@Composable
internal fun MacVideoPlayerWindowSurface(
    playerState: MacVideoPlayerState,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
    overlay: @Composable () -> Unit = {},
    onSurfaceAttached: () -> Unit = {},
) {
    MacVideoSurfaceContent(playerState, modifier, contentScale, overlay, onSurfaceAttached)
}

@Composable
private fun MacVideoSurfaceContent(
    playerState: MacVideoPlayerState,
    modifier: Modifier,
    contentScale: ContentScale,
    overlay: @Composable () -> Unit,
    onSurfaceAttached: () -> Unit = {},
) {
    val latestOnSurfaceAttached by rememberUpdatedState(onSurfaceAttached)
    val nativeSurfaceRequested =
        playerState.shouldUseHdrMetalSurface() || playerState.shouldUseLibVlcNativeSurface()
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
                remember(playerState, playerState.nativeSurfaceGeneration, nativeSurfaceRequested, contentScale) {
                    DesktopNativeVideoSurface(
                        kind = DesktopNativeVideoSurfaceKind.MACOS_NS_VIEW,
                        createHandle = { playerState.createNativeVideoView(contentScale.toHdrMetalMode()) },
                        disposeHandle = { handle -> playerState.disposeNativeVideoView(handle) },
                    )
                }
            DesktopNativeVideoView(
                surface = surface,
                // The native view and Compose overlay represent the complete player viewport.
                // AVPlayerLayer/Metal applies ContentScale to the media inside that viewport.
                modifier = Modifier.fillMaxSize(),
                overlay = { MacVideoOverlayContent(playerState, overlay) },
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
            MacVideoOverlayContent(playerState, overlay)
            DisposableEffect(playerState) {
                latestOnSurfaceAttached()
                onDispose { }
            }
        }
    }
}

@Composable
private fun MacVideoOverlayContent(
    playerState: MacVideoPlayerState,
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

/** Retained for source compatibility with render-policy tests and older callers. */
internal fun shouldRenderMacVideoSurface(
    hasMedia: Boolean,
    libVlcNativeSurfaceRequested: Boolean,
    @Suppress("UNUSED_PARAMETER") isFullscreen: Boolean,
    @Suppress("UNUSED_PARAMETER") isInFullscreenWindow: Boolean,
    @Suppress("UNUSED_PARAMETER") usesDedicatedNativeWindow: Boolean,
): Boolean = hasMedia || libVlcNativeSurfaceRequested

private fun ContentScale.toHdrMetalMode(): Int =
    when (this) {
        ContentScale.Crop,
        ContentScale.FillWidth,
        ContentScale.FillHeight,
        -> HDR_METAL_SCALE_CROP
        ContentScale.FillBounds -> HDR_METAL_SCALE_FILL
        else -> HDR_METAL_SCALE_FIT
    }

private const val HDR_METAL_SCALE_FIT = 0
private const val HDR_METAL_SCALE_CROP = 1
private const val HDR_METAL_SCALE_FILL = 2
