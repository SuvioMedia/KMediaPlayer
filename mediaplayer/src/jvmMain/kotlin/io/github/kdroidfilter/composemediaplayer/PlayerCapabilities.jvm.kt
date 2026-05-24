package io.github.kdroidfilter.composemediaplayer

import io.github.kdroidfilter.composemediaplayer.util.CurrentPlatform

internal actual fun platformPlayerCapabilities(): PlayerCapabilities =
    when (CurrentPlatform.os) {
        CurrentPlatform.OS.WINDOWS ->
            PlayerCapabilities(
                supportsMkv = false,
            )
        CurrentPlatform.OS.MAC,
        CurrentPlatform.OS.LINUX,
        ->
            PlayerCapabilities(
                supportsMkv = true,
            )
    }

internal actual fun platformQueryCanPlaySource(source: MediaSourceSpec): Boolean =
    platformPlayerCapabilities().canPlaySource(source)
