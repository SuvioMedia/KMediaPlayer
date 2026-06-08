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
            if (videoOutputMode == VideoOutputMode.NATIVE_HDR) {
                JVM_DESKTOP_BACKEND_LIBVLC_NATIVE_VIEW
            } else {
                null
            }
    }
