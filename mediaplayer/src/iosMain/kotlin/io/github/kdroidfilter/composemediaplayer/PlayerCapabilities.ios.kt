package io.github.kdroidfilter.composemediaplayer

import platform.AVKit.AVPictureInPictureController

internal actual fun platformPlayerCapabilities(): PlayerCapabilities =
    PlayerCapabilities(
        supportsMkv = false,
        supportsPiP = AVPictureInPictureController.isPictureInPictureSupported(),
    )

internal actual fun platformQueryCanPlaySource(source: MediaSourceSpec): Boolean =
    platformPlayerCapabilities().canPlaySource(source)
