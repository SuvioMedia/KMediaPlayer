package io.github.kdroidfilter.composemediaplayer

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import io.github.kdroidfilter.composemediaplayer.linux.LinuxVideoPlayerState
import io.github.kdroidfilter.composemediaplayer.linux.LinuxVideoPlayerSurface
import io.github.kdroidfilter.composemediaplayer.mac.MacVideoPlayerState
import io.github.kdroidfilter.composemediaplayer.mac.MacVideoPlayerSurface
import io.github.kdroidfilter.composemediaplayer.mac.MacVideoPlayerWindowSurface
import io.github.kdroidfilter.composemediaplayer.windows.WindowsVideoPlayerState
import io.github.kdroidfilter.composemediaplayer.windows.WindowsVideoPlayerSurface
import java.awt.Window

/**
 * Composable function for rendering a video player surface.
 *
 * The function delegates the rendering logic to specific platform-specific implementations
 * based on the type of the `delegate` within the provided `VideoPlayerState`.
 *
 * @param playerState The current state of the video player, encapsulating playback state
 *                    and platform-specific implementation details.
 * @param modifier A [Modifier] for styling or adjusting the layout of the video player surface.
 * @param contentScale Controls how the video content should be scaled inside the surface.
 *                    This affects how the video is displayed when its dimensions don't match
 *                    the surface dimensions.
 * @param overlay Optional composable content to be displayed on top of the video surface.
 *               This can be used to add custom controls, information, or any UI elements.
 */
@Composable
actual fun VideoPlayerSurface(
    playerState: VideoPlayerState,
    modifier: Modifier,
    contentScale: ContentScale,
    overlay: @Composable () -> Unit,
) {
    when (val surfaceState = playerState.resolveJvmSurfaceState()) {
        is PreviewableVideoPlayerState ->
            VideoPlayerSurfacePreview(modifier = modifier, overlay = overlay)
        is VideoPlayerSurfaceProvider ->
            surfaceState.RenderVideoPlayerSurface(modifier, contentScale, overlay)
        is WindowsVideoPlayerState -> WindowsVideoPlayerSurface(surfaceState, modifier, contentScale, overlay)
        is MacVideoPlayerState -> MacVideoPlayerSurface(surfaceState, modifier, contentScale, overlay)
        is LinuxVideoPlayerState -> LinuxVideoPlayerSurface(surfaceState, modifier, contentScale, overlay)
        else -> error("Unsupported JVM player state: ${surfaceState.javaClass.name}")
    }
}

/** Explicit full-player surface hosted by composemediaplayer-desktop-window. */
@Composable
internal fun JvmDesktopVideoWindowSurface(
    playerState: VideoPlayerState,
    window: Window,
    modifier: Modifier,
    contentScale: ContentScale,
    overlay: @Composable () -> Unit,
    onSurfaceAttached: () -> Unit,
) {
    when (val surfaceState = playerState.resolveJvmSurfaceState()) {
        is PreviewableVideoPlayerState -> {
            VideoPlayerSurfacePreview(modifier = modifier, overlay = overlay)
            SurfaceAttachedEffect(surfaceState, window, onSurfaceAttached)
        }
        is VideoPlayerSurfaceProvider -> {
            surfaceState.RenderVideoPlayerSurface(modifier, contentScale, overlay)
            SurfaceAttachedEffect(surfaceState, window, onSurfaceAttached)
        }
        is WindowsVideoPlayerState -> {
            WindowsVideoPlayerSurface(surfaceState, modifier, contentScale, overlay)
            SurfaceAttachedEffect(surfaceState, window, onSurfaceAttached)
        }
        is MacVideoPlayerState ->
            MacVideoPlayerWindowSurface(
                surfaceState,
                window,
                modifier,
                contentScale,
                overlay,
                onSurfaceAttached,
            )
        is LinuxVideoPlayerState -> {
            LinuxVideoPlayerSurface(surfaceState, modifier, contentScale, overlay)
            SurfaceAttachedEffect(surfaceState, window, onSurfaceAttached)
        }
        else -> error("Unsupported JVM player state: ${surfaceState.javaClass.name}")
    }
}

@Composable
private fun SurfaceAttachedEffect(
    playerState: VideoPlayerState,
    window: Window,
    onSurfaceAttached: () -> Unit,
) {
    DisposableEffect(playerState, window) {
        onSurfaceAttached()
        onDispose { }
    }
}

/** Resolves API and event wrappers to the platform state that owns the native video surface. */
internal fun VideoPlayerState.resolveJvmSurfaceState(): VideoPlayerState {
    var current = this
    while (true) {
        current =
            when (current) {
                is DefaultVideoPlayerState -> current.delegate
                is EventingVideoPlayerState -> current.wrappedState
                else -> return current
            }
    }
}
