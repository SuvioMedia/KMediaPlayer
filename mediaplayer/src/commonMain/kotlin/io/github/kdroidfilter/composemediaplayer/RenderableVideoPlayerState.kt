package io.github.kdroidfilter.composemediaplayer

import androidx.compose.runtime.Stable

/**
 * A library-issued handle for a default [VideoPlayerState] accepted by the strict
 * [VideoPlayerSurface] overload.
 *
 * Optional backends render through [VideoPlayerSurfaceProvider] and do not need this wrapper.
 */
@Stable
class RenderableVideoPlayerState internal constructor(
    internal val platformState: VideoPlayerState,
) : VideoPlayerState by platformState
