package sample.app.player

internal enum class MacMkvPlaybackBackend(val label: String) {
    AUTO("Auto"),
    LIBVLC("libVLC canvas"),
    LIBVLC_NATIVE("libVLC native"),
    FFMPEG_HLS("ffmpeg HLS"),
    VLC_HLS("VLC HLS"),
}

internal data class MacMkvPlaybackBackendOption(
    val backend: MacMkvPlaybackBackend,
    val enabled: Boolean,
    val status: String,
    val installHint: String? = null,
)

internal expect val macMkvPlaybackBackendSelectionAvailable: Boolean

internal expect fun macMkvPlaybackBackendOptions(): List<MacMkvPlaybackBackendOption>

internal expect fun applyMacMkvPlaybackBackend(backend: MacMkvPlaybackBackend)

internal expect fun restoreMacMkvPlaybackBackend()
