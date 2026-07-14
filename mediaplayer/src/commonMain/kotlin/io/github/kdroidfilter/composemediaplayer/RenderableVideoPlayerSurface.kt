package io.github.kdroidfilter.composemediaplayer

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale

/**
 * Type-safe overload for library-created player states. The legacy [VideoPlayerState] overload remains
 * available for source and binary compatibility with the 1.x API.
 */
@Composable
fun VideoPlayerSurface(
    playerState: RenderableVideoPlayerState,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
    overlay: @Composable () -> Unit = {},
) {
    VideoPlayerSurface(
        playerState = playerState.platformState,
        modifier = modifier,
        contentScale = contentScale,
        overlay = overlay,
    )
}
