package sample.app.player

import io.github.kdroidfilter.composemediaplayer.MacOsMediaToolAvailability
import io.github.kdroidfilter.composemediaplayer.MacOsMediaTools

internal actual val macMkvPlaybackBackendSelectionAvailable: Boolean
    get() = System.getProperty("os.name").contains("Mac", ignoreCase = true)

private const val FALLBACK_BACKEND_PROPERTY = "composemediaplayer.macos.fallbackBackend"
private const val HLS_BACKEND_PROPERTY = "composemediaplayer.macos.hlsFallbackBackend"

private var capturedOriginalValues = false
private var originalFallbackBackend: String? = null
private var originalHlsBackend: String? = null

internal actual fun macMkvPlaybackBackendOptions(): List<MacMkvPlaybackBackendOption> {
    if (!macMkvPlaybackBackendSelectionAvailable) return emptyList()

    val tools = MacOsMediaTools.query()
    val hasLibVlcCanvas = tools.libVlc.available && tools.libass.available
    val hasHlsBackend =
        (tools.ffmpeg.available && tools.ffprobe.available && tools.ffmpegWithSubtitlesFilter.available) ||
            tools.vlc.available

    return listOf(
        MacMkvPlaybackBackendOption(
            backend = MacMkvPlaybackBackend.AUTO,
            enabled = true,
            status =
                if (hasLibVlcCanvas) {
                    "Uses libVLC canvas first, then falls back to HLS helpers."
                } else if (hasHlsBackend) {
                    "Uses the first available HLS helper for macOS MKV playback."
                } else {
                    "No MKV helper detected; regular AVFoundation formats can still play."
                },
            installHint =
                if (hasLibVlcCanvas || hasHlsBackend) {
                    null
                } else {
                    "Install VLC from https://www.videolan.org/vlc/ or ffmpeg from https://ffmpeg.org/download.html"
                },
        ),
        libVlcOption(tools),
        ffmpegHlsOption(tools),
        vlcHlsOption(tools),
    )
}

internal actual fun applyMacMkvPlaybackBackend(backend: MacMkvPlaybackBackend) {
    if (!macMkvPlaybackBackendSelectionAvailable) return
    captureOriginalValues()

    when (backend) {
        MacMkvPlaybackBackend.AUTO -> {
            System.setProperty(FALLBACK_BACKEND_PROPERTY, "auto")
            System.setProperty(HLS_BACKEND_PROPERTY, "auto")
        }
        MacMkvPlaybackBackend.LIBVLC -> {
            System.setProperty(FALLBACK_BACKEND_PROPERTY, "libvlc")
            System.clearProperty(HLS_BACKEND_PROPERTY)
        }
        MacMkvPlaybackBackend.FFMPEG_HLS -> {
            System.setProperty(FALLBACK_BACKEND_PROPERTY, "ffmpeg")
            System.setProperty(HLS_BACKEND_PROPERTY, "ffmpeg")
        }
        MacMkvPlaybackBackend.VLC_HLS -> {
            System.setProperty(FALLBACK_BACKEND_PROPERTY, "vlc")
            System.setProperty(HLS_BACKEND_PROPERTY, "vlc")
        }
    }
}

internal actual fun restoreMacMkvPlaybackBackend() {
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

private fun libVlcOption(tools: MacOsMediaToolAvailability): MacMkvPlaybackBackendOption {
    val enabled = tools.libVlc.available && tools.libass.available
    val status =
        when {
            enabled -> "Ready. VLC/libVLC and libass detected."
            !tools.libVlc.available && !tools.libass.available -> "Requires VLC/libVLC and libass for ASS subtitle rendering."
            !tools.libVlc.available -> "Requires VLC/libVLC."
            else -> "VLC/libVLC detected; libass is required for ASS subtitle rendering."
        }

    return MacMkvPlaybackBackendOption(
        backend = MacMkvPlaybackBackend.LIBVLC,
        enabled = enabled,
        status = status,
        installHint =
            if (enabled) {
                "VLC: ${tools.vlc.path ?: tools.libVlc.path}; libass: ${tools.libass.path}. VLC is user-installed; it is not bundled or linked into the app."
            } else {
                "Install VLC from https://www.videolan.org/vlc/ and libass from Homebrew or MacPorts."
            },
    )
}

private fun ffmpegHlsOption(tools: MacOsMediaToolAvailability): MacMkvPlaybackBackendOption {
    val enabled = tools.ffmpeg.available && tools.ffprobe.available && tools.ffmpegWithSubtitlesFilter.available
    val status =
        when {
            !tools.ffmpeg.available -> "Requires ffmpeg."
            !tools.ffprobe.available -> "Requires ffprobe for duration and track selection."
            tools.ffmpegWithSubtitlesFilter.available -> "Ready. ffmpeg with the subtitles filter detected."
            else -> "ffmpeg detected; ASS subtitle burn-in needs the subtitles filter."
        }

    return MacMkvPlaybackBackendOption(
        backend = MacMkvPlaybackBackend.FFMPEG_HLS,
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

private fun vlcHlsOption(tools: MacOsMediaToolAvailability): MacMkvPlaybackBackendOption =
    MacMkvPlaybackBackendOption(
        backend = MacMkvPlaybackBackend.VLC_HLS,
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
