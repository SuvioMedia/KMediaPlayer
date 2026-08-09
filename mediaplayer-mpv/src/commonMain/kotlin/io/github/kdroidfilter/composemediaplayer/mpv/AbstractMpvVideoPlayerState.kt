@file:OptIn(io.github.kdroidfilter.composemediaplayer.ExperimentalComposeMediaPlayerBackendApi::class)

package io.github.kdroidfilter.composemediaplayer.mpv

import io.github.kdroidfilter.composemediaplayer.AbstractBackendVideoPlayerState

/**
 * MPV specialization of the shared optional-backend state contract.
 *
 * Native source resolution, polling, and rendering remain in the platform adapters.
 */
internal abstract class AbstractMpvVideoPlayerState : AbstractBackendVideoPlayerState() {
    override val seekFailureMessage: String
        get() = "MPV rejected the seek request."
}
