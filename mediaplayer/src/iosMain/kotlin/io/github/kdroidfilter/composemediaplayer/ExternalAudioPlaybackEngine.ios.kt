package io.github.kdroidfilter.composemediaplayer

internal actual fun createPlatformExternalAudioPlaybackEngine(): ExternalAudioPlaybackEngine =
    VideoPlayerStateExternalAudioPlaybackEngine(
        DefaultVideoPlayerState(
            audioMode = AudioMode(interruptionMode = InterruptionMode.MixWithOthers),
            cacheConfig = CacheConfig(),
        ),
    )
