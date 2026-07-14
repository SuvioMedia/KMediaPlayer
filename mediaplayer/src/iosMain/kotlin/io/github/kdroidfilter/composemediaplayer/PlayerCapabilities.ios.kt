package io.github.kdroidfilter.composemediaplayer

import platform.AVKit.AVPictureInPictureController

internal actual fun platformPlayerCapabilities(): PlayerCapabilities =
    PlayerCapabilities(
        supportsMkv = false,
        supportsPiP = AVPictureInPictureController.isPictureInPictureSupported(),
        supportedUriSchemes = IOS_SUPPORTED_URI_SCHEMES,
    )

internal actual fun platformSupportsHls(): Boolean = true

internal actual fun platformQueryCanPlaySource(source: MediaSourceSpec): Boolean =
    platformPlayerCapabilities().canPlaySource(source)

private val IOS_SUPPORTED_URI_SCHEMES = setOf("file", "http", "https")
