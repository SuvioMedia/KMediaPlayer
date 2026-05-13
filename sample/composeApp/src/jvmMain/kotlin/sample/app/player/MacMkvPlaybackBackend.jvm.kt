package sample.app.player

internal actual val macMkvPlaybackBackendSelectionAvailable: Boolean
    get() = System.getProperty("os.name").contains("Mac", ignoreCase = true)

private const val FALLBACK_BACKEND_PROPERTY = "composemediaplayer.macos.fallbackBackend"
private const val HLS_BACKEND_PROPERTY = "composemediaplayer.macos.hlsFallbackBackend"

private var capturedOriginalValues = false
private var originalFallbackBackend: String? = null
private var originalHlsBackend: String? = null

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
