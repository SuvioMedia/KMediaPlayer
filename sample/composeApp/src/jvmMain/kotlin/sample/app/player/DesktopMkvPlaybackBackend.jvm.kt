package sample.app.player

import io.github.kdroidfilter.composemediaplayer.JvmMediaToolAvailability
import io.github.kdroidfilter.composemediaplayer.JvmMediaTools

internal actual val desktopMkvPlaybackBackendSelectionAvailable: Boolean
    get() = true

private const val FALLBACK_BACKEND_PROPERTY = "composemediaplayer.fallbackBackend"
private const val HLS_BACKEND_PROPERTY = "composemediaplayer.hlsFallbackBackend"

private var capturedOriginalValues = false
private var originalFallbackBackend: String? = null
private var originalHlsBackend: String? = null

internal actual fun desktopMkvPlaybackBackendOptions(): List<DesktopMkvPlaybackBackendOption> {
    if (!desktopMkvPlaybackBackendSelectionAvailable) return emptyList()

    val tools = JvmMediaTools.query()
    val hasLibVlcCanvas = tools.libVlc.available
    val hasHlsBackend =
        (tools.ffmpeg.available && tools.ffprobe.available && tools.ffmpegWithSubtitlesFilter.available) ||
            tools.vlc.available

    return listOf(
        DesktopMkvPlaybackBackendOption(
            backend = DesktopMkvPlaybackBackend.AUTO,
            enabled = true,
            status =
                if (hasLibVlcCanvas) {
                    "Uses libVLC canvas first, then VLC HLS, with ffmpeg as the last fallback."
                } else if (hasHlsBackend) {
                    "Uses VLC HLS when available, with ffmpeg as the last fallback."
                } else {
                    "No MKV helper detected; native formats can still play."
                },
            installHint =
                if (hasLibVlcCanvas || hasHlsBackend) {
                    null
                } else {
                    "Install VLC from https://www.videolan.org/vlc/"
                },
        ),
        libVlcOption(tools),
        libVlcNativeOption(tools),
        ffmpegHlsOption(tools),
        vlcHlsOption(tools),
    )
}

internal actual fun applyDesktopMkvPlaybackBackend(backend: DesktopMkvPlaybackBackend) {
    if (!desktopMkvPlaybackBackendSelectionAvailable) return
    captureOriginalValues()

    when (backend) {
        DesktopMkvPlaybackBackend.AUTO -> {
            System.setProperty(FALLBACK_BACKEND_PROPERTY, "auto")
            System.setProperty(HLS_BACKEND_PROPERTY, "auto")
        }
        DesktopMkvPlaybackBackend.LIBVLC -> {
            System.setProperty(FALLBACK_BACKEND_PROPERTY, "libvlc")
            System.clearProperty(HLS_BACKEND_PROPERTY)
        }
        DesktopMkvPlaybackBackend.LIBVLC_NATIVE -> {
            System.setProperty(FALLBACK_BACKEND_PROPERTY, "libvlc-native-view")
            System.clearProperty(HLS_BACKEND_PROPERTY)
        }
        DesktopMkvPlaybackBackend.FFMPEG_HLS -> {
            System.setProperty(FALLBACK_BACKEND_PROPERTY, "ffmpeg")
            System.setProperty(HLS_BACKEND_PROPERTY, "ffmpeg")
        }
        DesktopMkvPlaybackBackend.VLC_HLS -> {
            System.setProperty(FALLBACK_BACKEND_PROPERTY, "vlc")
            System.setProperty(HLS_BACKEND_PROPERTY, "vlc")
        }
    }
}

internal actual fun restoreDesktopMkvPlaybackBackend() {
    if (!capturedOriginalValues) return
    restoreProperty(FALLBACK_BACKEND_PROPERTY, originalFallbackBackend)
    restoreProperty(HLS_BACKEND_PROPERTY, originalHlsBackend)
    capturedOriginalValues = false
    originalFallbackBackend = null
    originalHlsBackend = null
}

private fun captureOriginalValues() {
    if (capturedOriginalValues) return
    originalFallbackBackend = System.getProperty(FALLBACK_BACKEND_PROPERTY)
    originalHlsBackend = System.getProperty(HLS_BACKEND_PROPERTY)
    capturedOriginalValues = true
}

private fun restoreProperty(
    key: String,
    value: String?,
) {
    if (value == null) {
        System.clearProperty(key)
    } else {
        System.setProperty(key, value)
    }
}

private fun libVlcOption(tools: JvmMediaToolAvailability): DesktopMkvPlaybackBackendOption {
    val enabled = tools.libVlc.available
    val status =
        when {
            enabled && isMacOs() && tools.libass.available -> "Ready. VLC/libVLC and libass detected."
            enabled -> "Ready. VLC/libVLC detected."
            !tools.libVlc.available -> "Requires VLC/libVLC."
            else -> "Requires VLC/libVLC."
        }

    return DesktopMkvPlaybackBackendOption(
        backend = DesktopMkvPlaybackBackend.LIBVLC,
        enabled = enabled,
        status = status,
        installHint =
            if (enabled) {
                "VLC: ${tools.vlc.path ?: tools.libVlc.path}. VLC is user-installed; it is not bundled or linked into the app."
            } else {
                "Install VLC from https://www.videolan.org/vlc/."
            },
    )
}

private fun libVlcNativeOption(tools: JvmMediaToolAvailability): DesktopMkvPlaybackBackendOption {
    val enabled = tools.libVlc.available
    val status =
        when {
            enabled && isLinux() -> "Ready. VLC/libVLC detected. Uses an X11/XWayland native window, not native Wayland."
            enabled -> "Ready. VLC/libVLC detected."
            else -> "Requires VLC/libVLC."
        }

    return DesktopMkvPlaybackBackendOption(
        backend = DesktopMkvPlaybackBackend.LIBVLC_NATIVE,
        enabled = enabled,
        status = status,
        installHint =
            if (enabled) {
                "VLC: ${tools.vlc.path ?: tools.libVlc.path}. Linux direct rendering requires X11/XWayland."
            } else {
                "Install VLC from https://www.videolan.org/vlc/."
            },
    )
}

private fun ffmpegHlsOption(tools: JvmMediaToolAvailability): DesktopMkvPlaybackBackendOption {
    val enabled = tools.ffmpeg.available && tools.ffprobe.available && tools.ffmpegWithSubtitlesFilter.available
    val status =
        when {
            !tools.ffmpeg.available -> "Requires ffmpeg."
            !tools.ffprobe.available -> "Requires ffprobe for duration and track selection."
            tools.ffmpegWithSubtitlesFilter.available -> "Ready. ffmpeg with the subtitles filter detected."
            else -> "ffmpeg detected; ASS subtitle burn-in needs the subtitles filter."
        }

    return DesktopMkvPlaybackBackendOption(
        backend = DesktopMkvPlaybackBackend.FFMPEG_HLS,
        enabled = enabled,
        status = status,
        installHint =
            when {
                !tools.ffmpeg.available -> "Install ffmpeg from https://ffmpeg.org/download.html"
                !tools.ffprobe.available -> "Install the matching ffprobe binary from the same ffmpeg distribution."
                tools.ffmpegWithSubtitlesFilter.available -> "ffmpeg: ${tools.ffmpegWithSubtitlesFilter.path}"
                else -> "Install an ffmpeg build with libass/subtitles filter for ASS subtitles."
            },
    )
}

private fun vlcHlsOption(tools: JvmMediaToolAvailability): DesktopMkvPlaybackBackendOption =
    DesktopMkvPlaybackBackendOption(
        backend = DesktopMkvPlaybackBackend.VLC_HLS,
        enabled = tools.vlc.available,
        status =
            if (tools.vlc.available) {
                "Ready. VLC executable detected."
            } else {
                "Requires VLC."
            },
        installHint =
            if (tools.vlc.available) {
                "VLC: ${tools.vlc.path}"
            } else {
                "Install VLC from https://www.videolan.org/vlc/"
            },
    )

private fun isMacOs(): Boolean {
    val osName = System.getProperty("os.name", "").lowercase()
    return osName.contains("mac") || osName.contains("darwin")
}

private fun isLinux(): Boolean {
    val osName = System.getProperty("os.name", "").lowercase()
    return osName.contains("linux")
}
