package sample.app.player

internal actual val desktopMkvPlaybackBackendSelectionAvailable: Boolean = false

internal actual fun desktopMkvPlaybackBackendOptions(): List<DesktopMkvPlaybackBackendOption> = emptyList()

internal actual fun applyDesktopMkvPlaybackBackend(backend: DesktopMkvPlaybackBackend) {
}

internal actual fun restoreDesktopMkvPlaybackBackend() {
}
