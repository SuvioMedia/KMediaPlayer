package io.github.kdroidfilter.composemediaplayer

import platform.AVKit.AVPictureInPictureController

internal actual fun platformPlayerCapabilities(playbackOptions: VideoPlaybackOptions): PlayerCapabilities =
    PlayerCapabilities(
        supportsMkv = false,
        supportsHls = true,
        supportsPiP = AVPictureInPictureController.isPictureInPictureSupported(),
        rendererColorCapabilities = queryAppleProjectionRendererColorCapabilities(),
        supportedUriSchemes = IOS_SUPPORTED_URI_SCHEMES,
    )

internal actual fun platformQueryCanPlaySource(source: MediaSourceSpec): Boolean =
    platformPlayerCapabilities().canPlaySource(source)

private val IOS_SUPPORTED_URI_SCHEMES = setOf("file", "http", "https")
