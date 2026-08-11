package io.github.kdroidfilter.composemediaplayer

/**
 * Requests a locally controllable primary-audio path for external audio mixing.
 *
 * The return value is true only when the platform explicitly suppressed encoded passthrough for the request.
 */
internal expect fun configurePrimaryAudioForExternalMixing(
    state: VideoPlayerState,
    enabled: Boolean,
): Boolean
