package io.github.kdroidfilter.composemediaplayer.util

import androidx.compose.runtime.Stable
import io.github.kdroidfilter.composemediaplayer.VideoPlayerState

/**
 * Compatibility registry retained for applications compiled against 1.x.
 *
 * The library no longer uses a global registry for fullscreen playback. New code should pass
 * [VideoPlayerState] directly to the surface that renders it.
 */
@Stable
@Deprecated(
    message = "The fullscreen implementation no longer requires a global player-state registry.",
)
object VideoPlayerStateRegistry {
    private var registeredState: VideoPlayerState? = null

    /** Registers a state for legacy integrations. */
    fun registerState(state: VideoPlayerState) {
        registeredState = state
    }

    /** Returns the state registered by a legacy integration, if any. */
    fun getRegisteredState(): VideoPlayerState? = registeredState

    /** Removes the state registered by a legacy integration. */
    fun clearRegisteredState() {
        registeredState = null
    }
}
