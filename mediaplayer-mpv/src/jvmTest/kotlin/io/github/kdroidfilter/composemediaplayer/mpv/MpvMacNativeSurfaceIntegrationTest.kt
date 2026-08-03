@file:OptIn(io.github.kdroidfilter.composemediaplayer.ExperimentalComposeMediaPlayerBackendApi::class)

package io.github.kdroidfilter.composemediaplayer.mpv

import androidx.compose.ui.graphics.toPixelMap
import io.github.kdroidfilter.composemediaplayer.DesktopVideoBackend
import io.github.kdroidfilter.composemediaplayer.InitialPlayerState
import io.github.kdroidfilter.composemediaplayer.MpvPlaybackOptions
import io.github.kdroidfilter.composemediaplayer.MpvRuntimeSource
import io.github.kdroidfilter.composemediaplayer.VideoPlaybackOptions
import io.github.kdroidfilter.composemediaplayer.VideoPlayerState
import io.github.kdroidfilter.composemediaplayer.createMpvVideoPlayerState
import io.github.kdroidfilter.composemediaplayer.createVideoPlayerState
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
    fun createsNativeViewAndReturnsToSoftwareRendering() {
        if (!isMacArm64()) return
        val library = configuredFile(MPV_LIBRARY_PROPERTY) ?: return
        val media =
            configuredFile(NATIVE_SURFACE_MEDIA_PROPERTY)
                ?: configuredFile(WMAPRO_MEDIA_PROPERTY)
                ?: configuredLegacyDirectory()?.findWmv()
                ?: return
        val player = createExplicitMpvPlayer(library)
        var nativeView = 0L

        try {
            player.openUri(media.toUri().toString(), InitialPlayerState.PLAY)
            await("MPV did not start before native-view creation.") {
                player.hasMedia &&
                    player.isPlaying &&
                    !player.isLoading &&
                    player.currentTime >= 250.milliseconds
            }

            nativeView = player.createNativeMacView()
            assertTrue(nativeView != 0L, "The native macOS MPV NSView was not created.")
            assertContains(player.renderingInfo.videoRenderer.orEmpty(), "OpenGL")
            val timeBeforeObservation = player.currentTime
            await("Playback stalled while the native MPV NSView was active.") {
                player.currentTime >= timeBeforeObservation + 250.milliseconds
            }

            player.disposeNativeMacView(nativeView)
            nativeView = 0L
            assertContains(player.renderingInfo.videoRenderer.orEmpty(), "software")
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
        val mpvPlayer = createExplicitMpvPlayer(library)
        var platformPlayer: VideoPlayerState? = null

        try {
            mpvPlayer.openUri(media.toUri().toString(), InitialPlayerState.PLAY)
            await("MPV did not start before backend handoff.") {
                mpvPlayer.hasMedia &&
                    mpvPlayer.isPlaying &&
                    mpvPlayer.currentTime >= 250.milliseconds
            }
            assertTrue(mpvPlayer.createNativeMacView() != 0L)

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

    private fun createExplicitMpvPlayer(library: Path): MpvVideoPlayerState =
        assertIs(
            createMpvVideoPlayerState(
                MpvPlaybackOptions(
                    runtimeSource = MpvRuntimeSource.ExplicitPath(library.toString()),
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
