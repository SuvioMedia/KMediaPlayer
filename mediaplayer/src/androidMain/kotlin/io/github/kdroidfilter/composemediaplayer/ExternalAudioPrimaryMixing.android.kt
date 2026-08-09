package io.github.kdroidfilter.composemediaplayer

internal actual fun configurePrimaryAudioForExternalMixing(
    state: VideoPlayerState,
    enabled: Boolean,
): Boolean =
    (state.unwrapDelegatingState() as? DefaultVideoPlayerState)
        ?.setExternalAudioLocalMixingEnabled(enabled)
        ?: false
