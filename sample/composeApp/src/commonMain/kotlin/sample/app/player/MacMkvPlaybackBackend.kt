package sample.app.player

internal enum class MacMkvPlaybackBackend(val label: String) {
    AUTO("Auto"),
    LIBVLC("libVLC"),
    FFMPEG_HLS("ffmpeg HLS"),
    VLC_HLS("VLC HLS"),
}

internal expect val macMkvPlaybackBackendSelectionAvailable: Boolean

internal expect fun applyMacMkvPlaybackBackend(backend: MacMkvPlaybackBackend)

internal expect fun restoreMacMkvPlaybackBackend()
