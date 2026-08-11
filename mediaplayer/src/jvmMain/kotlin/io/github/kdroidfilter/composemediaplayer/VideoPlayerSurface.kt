package io.github.kdroidfilter.composemediaplayer

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import io.github.kdroidfilter.composemediaplayer.linux.LinuxVideoPlayerState
import io.github.kdroidfilter.composemediaplayer.linux.LinuxVideoPlayerSurface
import io.github.kdroidfilter.composemediaplayer.mac.MacVideoPlayerState
import io.github.kdroidfilter.composemediaplayer.mac.MacVideoPlayerSurface
import io.github.kdroidfilter.composemediaplayer.windows.WindowsVideoPlayerState
import io.github.kdroidfilter.composemediaplayer.windows.WindowsVideoPlayerSurface

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

/** Desktop surface variant that reports when the active backend has attached. */
@Composable
internal fun JvmTaoPlaybackSurface(
    playerState: VideoPlayerState,
    modifier: Modifier,
    contentScale: ContentScale,
    overlay: @Composable () -> Unit,
    onSurfaceAttached: () -> Unit,
) {
    when (val surfaceState = playerState.resolveJvmSurfaceState()) {
        is PreviewableVideoPlayerState -> {
            VideoPlayerSurfacePreview(modifier = modifier, overlay = overlay)
            SurfaceAttachedEffect(surfaceState, onSurfaceAttached)
        }
        is VideoPlayerSurfaceProvider -> {
            surfaceState.RenderVideoPlayerSurface(modifier, contentScale, overlay)
            SurfaceAttachedEffect(surfaceState, onSurfaceAttached)
        }
        is WindowsVideoPlayerState -> {
            WindowsVideoPlayerSurface(
                surfaceState,
                modifier,
                contentScale,
                overlay,
                onSurfaceAttached = onSurfaceAttached,
            )
        }
        is MacVideoPlayerState -> {
            MacVideoPlayerSurface(
                surfaceState,
                modifier,
                contentScale,
                overlay,
                onSurfaceAttached = onSurfaceAttached,
            )
        }
        is LinuxVideoPlayerState -> {
            LinuxVideoPlayerSurface(
                surfaceState,
                modifier,
                contentScale,
                overlay,
                onSurfaceAttached = onSurfaceAttached,
            )
        }
        else -> error("Unsupported JVM player state: ${surfaceState.javaClass.name}")
    }
}

@Composable
private fun SurfaceAttachedEffect(
    playerState: VideoPlayerState,
    onSurfaceAttached: () -> Unit,
) {
    DisposableEffect(playerState) {
        onSurfaceAttached()
        onDispose { }
    }
}

/** Resolves API and event wrappers to the platform state that owns the native video surface. */
internal fun VideoPlayerState.resolveJvmSurfaceState(): VideoPlayerState {
    var current = unwrapDelegatingState()
    repeat(MAXIMUM_JVM_SURFACE_STATE_DEPTH) {
        val defaultState = current as? DefaultVideoPlayerState ?: return current
        current = defaultState.delegate.unwrapDelegatingState()
    }
    error("The JVM player-state decorator chain is too deep.")
}

private const val MAXIMUM_JVM_SURFACE_STATE_DEPTH = 32
