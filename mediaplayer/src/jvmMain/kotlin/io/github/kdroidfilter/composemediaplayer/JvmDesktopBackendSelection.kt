package io.github.kdroidfilter.composemediaplayer

internal const val JVM_DESKTOP_BACKEND_LIBVLC = "libvlc"
internal const val JVM_DESKTOP_BACKEND_LIBVLC_NATIVE_VIEW = "libvlc-native-view"
internal const val JVM_DESKTOP_BACKEND_PLATFORM = "platform"

internal fun VideoPlaybackOptions.forcedJvmDesktopBackend(): String? =
    when (desktopVideoBackend) {
        DesktopVideoBackend.LIBVLC -> JVM_DESKTOP_BACKEND_LIBVLC
        DesktopVideoBackend.LIBVLC_NATIVE -> JVM_DESKTOP_BACKEND_LIBVLC_NATIVE_VIEW
        DesktopVideoBackend.PLATFORM -> JVM_DESKTOP_BACKEND_PLATFORM
        DesktopVideoBackend.AUTO ->
            null
    }

internal fun DesktopMediaSourcePolicy.explicitFallbackBackend(): String? =
    when (this) {
        DesktopMediaSourcePolicy.INHERIT -> null
        DesktopMediaSourcePolicy.AUTO -> "auto"
        DesktopMediaSourcePolicy.DIRECT -> JVM_DESKTOP_BACKEND_PLATFORM
        DesktopMediaSourcePolicy.KMEDIA_BRIDGE -> "kmediabridge"
        DesktopMediaSourcePolicy.VLC_HLS -> "vlc"
    }

internal fun VideoPlaybackOptions.allowsExternalSourceAdapter(): Boolean =
    when (desktopMediaSourcePolicy) {
        DesktopMediaSourcePolicy.DIRECT -> false
        DesktopMediaSourcePolicy.AUTO,
        DesktopMediaSourcePolicy.KMEDIA_BRIDGE,
        DesktopMediaSourcePolicy.VLC_HLS,
        -> true
        DesktopMediaSourcePolicy.INHERIT -> desktopVideoBackend == DesktopVideoBackend.AUTO
    }
