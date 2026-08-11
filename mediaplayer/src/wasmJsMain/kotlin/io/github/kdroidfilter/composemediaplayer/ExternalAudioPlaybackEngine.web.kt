package io.github.kdroidfilter.composemediaplayer

internal actual fun createPlatformExternalAudioPlaybackEngine(): ExternalAudioPlaybackEngine =
    WebExternalAudioPlaybackEngine()
