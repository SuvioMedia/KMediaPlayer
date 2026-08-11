@file:OptIn(io.github.kdroidfilter.composemediaplayer.ExperimentalComposeMediaPlayerBackendApi::class)

package io.github.kdroidfilter.composemediaplayer.mpv

import androidx.compose.ui.graphics.toPixelMap
import io.github.kdroidfilter.composemediaplayer.DesktopVideoBackend
import io.github.kdroidfilter.composemediaplayer.InitialPlayerState
import io.github.kdroidfilter.composemediaplayer.MpvBackendAvailability
import io.github.kdroidfilter.composemediaplayer.MpvMacRenderer
import io.github.kdroidfilter.composemediaplayer.MpvPlaybackOptions
import io.github.kdroidfilter.composemediaplayer.MpvRuntimeSource
import io.github.kdroidfilter.composemediaplayer.VideoPlaybackOptions
import io.github.kdroidfilter.composemediaplayer.VideoPlayerState
import io.github.kdroidfilter.composemediaplayer.VideoProjectionSettings
import io.github.kdroidfilter.composemediaplayer.VideoProjectionType
import io.github.kdroidfilter.composemediaplayer.createMpvVideoPlayerState
import io.github.kdroidfilter.composemediaplayer.createVideoPlayerState
import io.github.kdroidfilter.composemediaplayer.inspectMpvBackend
import io.github.kdroidfilter.composemediaplayer.mpv.internal.LibMpvLibrary
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

class MpvMacNativeSurfaceIntegrationTest {
    @Test
    fun bundledMacVkOpensAfterAvailabilityProbe() {
        if (!isMacArm64()) return
        val media = configuredFile(NATIVE_SURFACE_MEDIA_PROPERTY) ?: return
        assertIs<MpvBackendAvailability.Available>(inspectMpvBackend())
        val player = assertIs<MpvVideoPlayerState>(createMpvVideoPlayerState())

        try {
            assertContains(player.renderingInfo.videoRenderer.orEmpty(), "macvk")
            player.openUri(media.toUri().toString(), InitialPlayerState.PAUSE)
            await("Bundled macvk did not load media after the availability probe.") {
                player.error != null || (player.hasMedia && !player.isLoading)
            }
            assertEquals(null, player.error)
            assertTrue(player.hasMedia)
        } finally {
            player.dispose()
        }
    }

    @Test
    fun playsThroughCapabilityMarkedEmbeddedMacVk() {
        if (!isMacArm64()) return
        val library = configuredFile(MPV_LIBRARY_PROPERTY) ?: return
        val media = configuredFile(NATIVE_SURFACE_MEDIA_PROPERTY) ?: return
        val loadedLibrary = LibMpvLibrary.open(MpvLibrarySource.ExplicitPath(library))
        try {
            assertEquals(2, loadedLibrary.embeddedMacVkApiVersion)
        } finally {
            loadedLibrary.close()
        }
        assertTrue(MpvMacNativeBridge.isAvailable, "The macOS native bridge is unavailable.")
        val probeHost = MpvMacNativeBridge.nCreateMacVkHost()
        assertTrue(probeHost != 0L, "The macOS native bridge did not create a macvk host.")
        val probeLibrary = LibMpvLibrary.open(MpvLibrarySource.ExplicitPath(library))
        val probeEngine =
            try {
                probeLibrary.createEngine(
                    options =
                        mpvInitializationOptions(
                            config = MpvRuntimeConfig(),
                            macBackend = MpvMacNativeBackend.MACVK,
                            macVkHostView = probeHost,
                        ),
                    createSoftwareRenderer = false,
                )
            } catch (failure: Throwable) {
                probeLibrary.close()
                throw failure
            }
        probeEngine.close()
        MpvMacNativeBridge.nDestroyMacVkHost(probeHost)
        val player = createExplicitMpvPlayer(library)
        val telemetryLibrary = LibMpvLibrary.open(MpvLibrarySource.ExplicitPath(library))

        try {
            assertContains(player.renderingInfo.videoRenderer.orEmpty(), "macvk")
            val nativeView = player.createNativeMacView()
            assertTrue(nativeView != 0L, "The embedded macvk NSView was not created.")
            val presentationsBeforePlayback =
                checkNotNull(telemetryLibrary.embeddedMacVkPresentedFrames(nativeView))
            player.onNativeMacSurfaceAttached()
            player.openUri(media.toUri().toString(), InitialPlayerState.PLAY)

            await("Embedded macvk did not reach active playback.") {
                player.hasMedia &&
                    player.isPlaying &&
                    !player.isLoading &&
                    player.currentTime >= 250.milliseconds
            }
            await("Embedded macvk did not report a presented frame.") {
                telemetryLibrary
                    .embeddedMacVkPresentedFrames(nativeView)
                    ?.let { it > presentationsBeforePlayback } == true
            }
            assertContains(player.renderingInfo.videoRenderer.orEmpty(), "macvk")
            assertEquals(null, player.error)
            MpvMacOutputColorMode.entries.forEach { mode ->
                MpvMacNativeBridge.nSetMacVkColorMode(nativeView, mode.nativeValue)
            }
            MpvMacNativeBridge.nSetMacVkColorMode(
                nativeView,
                MpvMacOutputColorMode.SDR.nativeValue,
            )

            val timeBeforeProjection = player.currentTime
            player.projection =
                VideoProjectionSettings(projectionType = VideoProjectionType.Fisheye190)
            player.updateNativeMacProjection()
            assertContains(player.renderingInfo.videoRenderer.orEmpty(), "OpenGL")
            assertContains(player.renderingInfo.notes.orEmpty(), "projection requires")
            await("Playback stalled while switching macvk to the OpenGL projection pass.") {
                player.currentTime >= timeBeforeProjection + 250.milliseconds
            }
            assertEquals(null, player.error)
        } finally {
            player.dispose()
            telemetryLibrary.close()
        }
    }

    @Test
    fun createsNativeViewAndReturnsToSoftwareRendering() {
        if (!isMacArm64()) return
        val library = configuredFile(MPV_LIBRARY_PROPERTY) ?: return
        val media =
            configuredFile(NATIVE_SURFACE_MEDIA_PROPERTY)
                ?: configuredFile(WMAPRO_MEDIA_PROPERTY)
                ?: configuredLegacyDirectory()?.findWmv()
                ?: return
        val player = createExplicitMpvPlayer(library, MpvMacRenderer.OPENGL)
        var nativeView = 0L

        try {
            nativeView = player.createNativeMacView()
            assertTrue(nativeView != 0L, "The native macOS MPV NSView was not created.")
            player.onNativeMacSurfaceAttached()
            player.openUri(media.toUri().toString(), InitialPlayerState.PLAY)
            await("MPV did not start with the native view attached.") {
                player.hasMedia &&
                    player.isPlaying &&
                    !player.isLoading &&
                    player.currentTime >= 250.milliseconds
            }

            assertContains(player.renderingInfo.videoRenderer.orEmpty(), "OpenGL")
            val timeBeforeObservation = player.currentTime
            await("Playback stalled while the native MPV NSView was active.") {
                player.currentTime >= timeBeforeObservation + 250.milliseconds
            }

            player.disposeNativeMacView(nativeView)
            nativeView = 0L
            await("MPV did not return to software rendering after native-view disposal.") {
                player.renderingInfo.videoRenderer
                    .orEmpty()
                    .contains("software")
            }
            await("MPV did not resume software rendering after native-view disposal.") {
                player.renderFrame(RENDER_WIDTH, RENDER_HEIGHT)
                player.currentFrame.value
                    ?.toPixelMap()
                    ?.buffer
                    ?.hasVisibleVariation() == true
            }
            assertEquals(null, player.error)
        } finally {
            if (nativeView != 0L) runCatching { player.disposeNativeMacView(nativeView) }
            player.dispose()
        }
    }

    @Test
    fun disposingNativeMpvViewAllowsPlatformBackendHandoff() {
        if (!isMacArm64()) return
        val library = configuredFile(MPV_LIBRARY_PROPERTY) ?: return
        val media = configuredFile(NATIVE_SURFACE_MEDIA_PROPERTY) ?: return
        val mpvPlayer = createExplicitMpvPlayer(library, MpvMacRenderer.OPENGL)
        var platformPlayer: VideoPlayerState? = null

        try {
            assertTrue(mpvPlayer.createNativeMacView() != 0L)
            mpvPlayer.onNativeMacSurfaceAttached()
            mpvPlayer.openUri(media.toUri().toString(), InitialPlayerState.PLAY)
            await("MPV did not start with the native view attached before backend handoff.") {
                mpvPlayer.hasMedia &&
                    mpvPlayer.isPlaying &&
                    mpvPlayer.currentTime >= 250.milliseconds
            }

            // Disposing the player must synchronously release its renderer-owned NSView.
            mpvPlayer.dispose()
            platformPlayer =
                createVideoPlayerState(
                    playbackOptions =
                        VideoPlaybackOptions(
                            desktopVideoBackend = DesktopVideoBackend.PLATFORM,
                        ),
                )
            platformPlayer.openUri(media.toUri().toString(), InitialPlayerState.PLAY)
            await("AVFoundation did not start after the MPV native view was disposed.") {
                platformPlayer.hasMedia &&
                    platformPlayer.isPlaying &&
                    platformPlayer.currentTime >= 250.milliseconds
            }
            assertEquals(null, platformPlayer.error)
        } finally {
            platformPlayer?.dispose()
            mpvPlayer.dispose()
        }
    }

    private fun createExplicitMpvPlayer(
        library: Path,
        macRenderer: MpvMacRenderer = MpvMacRenderer.MOLTENVK,
    ): MpvVideoPlayerState =
        assertIs(
            createMpvVideoPlayerState(
                MpvPlaybackOptions(
                    runtimeSource = MpvRuntimeSource.ExplicitPath(library.toString()),
                    macRenderer = macRenderer,
                ),
            ),
        )

    private fun configuredLegacyDirectory(): Path? =
        System
            .getProperty(LEGACY_MEDIA_PROPERTY)
            ?.takeIf(String::isNotBlank)
            ?.let(Path::of)
            ?.takeIf(Files::isDirectory)

    private fun configuredFile(property: String): Path? =
        System
            .getProperty(property)
            ?.takeIf(String::isNotBlank)
            ?.let(Path::of)
            ?.takeIf(Files::isRegularFile)

    private fun Path.findWmv(): Path? =
        Files.list(this).use { paths ->
            paths
                .filter(Files::isRegularFile)
                .filter { it.fileName.toString().endsWith(".wmv", ignoreCase = true) }
                .findFirst()
                .orElse(null)
        }

    private fun await(
        message: String,
        condition: () -> Boolean,
    ) {
        val deadline = System.nanoTime() + TEST_TIMEOUT_NANOS
        while (System.nanoTime() < deadline) {
            if (condition()) return
            Thread.sleep(POLL_INTERVAL_MILLIS)
        }
        assertTrue(condition(), message)
    }

    private fun IntArray.hasVisibleVariation(): Boolean = isNotEmpty() && any { pixel -> pixel != first() }

    private fun isMacArm64(): Boolean {
        val os = System.getProperty("os.name", "").lowercase()
        val architecture = System.getProperty("os.arch", "").lowercase()
        return (os.contains("mac") || os.contains("darwin")) && architecture in setOf("aarch64", "arm64")
    }

    private companion object {
        const val MPV_LIBRARY_PROPERTY = "composemediaplayer.mpvLibraryPath"
        const val LEGACY_MEDIA_PROPERTY = "composemediaplayer.legacyTestMedia"
        const val WMAPRO_MEDIA_PROPERTY = "composemediaplayer.wmaProTestMedia"
        const val NATIVE_SURFACE_MEDIA_PROPERTY = "composemediaplayer.nativeSurfaceTestMedia"
        const val RENDER_WIDTH = 320
        const val RENDER_HEIGHT = 180
        const val POLL_INTERVAL_MILLIS = 25L
        const val TEST_TIMEOUT_NANOS = 15_000_000_000L
    }
}
