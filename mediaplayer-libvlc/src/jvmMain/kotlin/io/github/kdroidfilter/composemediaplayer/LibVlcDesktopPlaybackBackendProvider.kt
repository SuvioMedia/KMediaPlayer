package io.github.kdroidfilter.composemediaplayer

import io.github.kdroidfilter.composemediaplayer.desktop.DesktopPlaybackBackend
import io.github.kdroidfilter.composemediaplayer.desktop.OptionalDesktopPlaybackBackendOptions
import io.github.kdroidfilter.composemediaplayer.desktop.OptionalDesktopPlaybackBackendProvider

/** Service-loader entry point supplied only by the optional libVLC 4 artifact. */
public class LibVlcDesktopPlaybackBackendProvider : OptionalDesktopPlaybackBackendProvider {
    override val providerId: String = "libvlc4"

    override fun create(options: OptionalDesktopPlaybackBackendOptions): DesktopPlaybackBackend =
        libVlcDesktopPlaybackBackend(
            LibVlcPlaybackOptions(
                dynamicRangePolicy = options.dynamicRangePolicy,
                dolbyVisionPolicy = options.dolbyVisionPolicy,
                desktopVideoSurfaceMode = options.desktopVideoSurfaceMode,
            ),
        )
}
