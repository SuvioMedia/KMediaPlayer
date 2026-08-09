package io.github.kdroidfilter.composemediaplayer

import io.github.kdroidfilter.composemediaplayer.desktop.DesktopPlaybackBackend
import io.github.kdroidfilter.composemediaplayer.desktop.OptionalDesktopPlaybackBackendOptions
import io.github.kdroidfilter.composemediaplayer.desktop.OptionalDesktopPlaybackBackendProvider
import io.github.shusek.kmediavlc.runtime.desktop.VlcDesktopRuntimeResolution
import io.github.shusek.kmediavlc.runtime.desktop.VlcFrameDeliveryMode
import io.github.shusek.kmediavlc.runtime.desktop.VlcRenderEngine
import io.github.shusek.kmediavlc.runtime.desktop.VlcRuntimeCapabilities
import java.nio.file.Files
import java.nio.file.Path

/** Service-loader entry point supplied only by the optional libVLC 4 artifact. */
public class LibVlcDesktopPlaybackBackendProvider : OptionalDesktopPlaybackBackendProvider {
    override val providerId: String = "libvlc4"

    override fun create(options: OptionalDesktopPlaybackBackendOptions): DesktopPlaybackBackend {
        val runtimeSource = developmentFixtureRuntimeSource() ?: LibVlcRuntimeSource.Bundled
        return libVlcDesktopPlaybackBackend(
            LibVlcPlaybackOptions(
                runtimeSource = runtimeSource,
                dynamicRangePolicy = options.dynamicRangePolicy,
                dolbyVisionPolicy = options.dolbyVisionPolicy,
                desktopVideoSurfaceMode = options.desktopVideoSurfaceMode,
            ),
        )
    }
}

/** Temporary local smoke-test hook; removed after the Windows validation run. */
private fun developmentFixtureRuntimeSource(): LibVlcRuntimeSource.Resolved? {
    if (System.getenv(DevelopmentFixtureEnabled) != "1") return null
    fun requiredPath(name: String, directory: Boolean = false): Path {
        val path = Path.of(checkNotNull(System.getenv(name)) { "$name is required." }).toAbsolutePath().normalize()
        check(if (directory) Files.isDirectory(path) else Files.isRegularFile(path)) { "$name is invalid: $path" }
        return path
    }
    return LibVlcRuntimeSource.Resolved(
        VlcDesktopRuntimeResolution(
            requiredPath(DevelopmentFixtureBridge),
            requiredPath(DevelopmentFixtureLibVlc),
            requiredPath(DevelopmentFixturePlugins, directory = true),
            "videolan-nightly-b5536cde-test-only",
            VlcRuntimeCapabilities(
                4,
                2,
                "4.0.0-dev",
                "b5536cdea24b313ba9215eacfbd7fa3295d7f3ee",
                setOf(VlcFrameDeliveryMode.GPU_PUSH, VlcFrameDeliveryMode.CPU_PULL),
                setOf(VlcRenderEngine.D3D11),
                true,
            ),
        ),
    )
}

private const val DevelopmentFixtureEnabled = "KMEDIAVLC_DEVELOPMENT_FIXTURE"
private const val DevelopmentFixtureBridge = "KMEDIAVLC_DEVELOPMENT_BRIDGE"
private const val DevelopmentFixtureLibVlc = "KMEDIAVLC_DEVELOPMENT_LIBVLC"
private const val DevelopmentFixturePlugins = "KMEDIAVLC_DEVELOPMENT_PLUGINS"
