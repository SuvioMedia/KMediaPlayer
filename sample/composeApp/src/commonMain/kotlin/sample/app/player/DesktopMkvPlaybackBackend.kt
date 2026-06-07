package sample.app.player

internal enum class DesktopMkvPlaybackBackend(val label: String) {
    AUTO("Auto"),
    LIBVLC("libVLC canvas"),
    LIBVLC_NATIVE("libVLC native"),
    FFMPEG_HLS("ffmpeg HLS"),
    VLC_HLS("VLC HLS"),
}

internal data class DesktopMkvPlaybackBackendOption(
    val backend: DesktopMkvPlaybackBackend,
    val enabled: Boolean,
    val status: String,
    val installHint: String? = null,
)

internal expect val desktopMkvPlaybackBackendSelectionAvailable: Boolean

internal expect fun desktopMkvPlaybackBackendOptions(): List<DesktopMkvPlaybackBackendOption>

internal expect fun applyDesktopMkvPlaybackBackend(backend: DesktopMkvPlaybackBackend)

internal expect fun restoreDesktopMkvPlaybackBackend()
