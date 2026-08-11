package io.github.kdroidfilter.composemediaplayer

internal actual fun createPlatformExternalAudioPlaybackEngine(): ExternalAudioPlaybackEngine =
    VideoPlayerStateExternalAudioPlaybackEngine(createVideoPlayerState(cacheConfig = CacheConfig()))
