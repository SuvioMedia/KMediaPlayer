package io.github.kdroidfilter.composemediaplayer.mac

import io.github.kdroidfilter.composemediaplayer.DesktopMediaSourcePolicy
import io.github.kdroidfilter.composemediaplayer.DesktopVideoBackend
import io.github.kdroidfilter.composemediaplayer.DesktopVideoSurfaceMode
import io.github.kdroidfilter.composemediaplayer.ExternalVlcLocator
import io.github.kdroidfilter.composemediaplayer.InitialPlayerState
import io.github.kdroidfilter.composemediaplayer.JvmLibVlcMediaProbe
import io.github.kdroidfilter.composemediaplayer.VideoPlaybackOptions
import java.awt.Color
import java.awt.GraphicsEnvironment
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.JFrame
import javax.swing.JPanel
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

class MacVlcHlsLegacyIntegrationTest {
    @Test
    fun platformBackendPlaysConfiguredWmvThroughVlcHls() {
        if (!isMacArm64() || GraphicsEnvironment.isHeadless()) return
        val media = configuredWmv() ?: return
        if (ExternalVlcLocator.findVlc() == null) return
        val audioCodecs =
            JvmLibVlcMediaProbe
                .probe(media.toUri().toString())
                .audioStreams
                .mapNotNull { it.codecName }
                .toSet()
        if (audioCodecs.any { it in VLC_UNSUPPORTED_AUDIO_CODECS }) return

        val player =
            MacVideoPlayerState(
                playbackOptions =
                    VideoPlaybackOptions(
                        desktopVideoBackend = DesktopVideoBackend.PLATFORM,
                        desktopVideoSurfaceMode = DesktopVideoSurfaceMode.PREFER_NATIVE,
                        desktopMediaSourcePolicy = DesktopMediaSourcePolicy.VLC_HLS,
                    ),
            )
        val window = createTransparentWindow()
        var attached = false
        try {
            assertTrue(player.configureDedicatedWindow(window))
            player.openUri(media.toUri().toString(), InitialPlayerState.PLAY)
            await("VLC HLS did not deliver playable WMV media to AVFoundation.") {
                player.error != null ||
                    (player.hasMedia && player.isPlaying && !player.isLoading && player.currentTime >= 250.milliseconds)
            }
            assertNull(player.error, "VLC HLS failed while opening ${media.fileName}.")
            assertContains(player.renderingInfo.backend.orEmpty(), "VLC")

            attached = player.attachHdrMetalWindow(window, HDR_METAL_SCALE_FIT)
            assertTrue(attached, "The AVFoundation layer did not attach for VLC HLS playback.")
            await("AVFoundation did not render VLC HLS video frames.") {
                (player.diagnostics.renderedVideoFrames ?: 0L) > 0L
            }
            assertTrue(player.availableAudioTracks.isNotEmpty(), "VLC did not expose the WMV audio track.")
        } finally {
            if (attached) runCatching { player.detachHdrMetalComponent(window) }
            player.dispose()
            onEdt { window.dispose() }
        }
    }

    private fun configuredWmv(): Path? {
        val configured =
            System
                .getProperty(LEGACY_MEDIA_PROPERTY)
                ?.takeIf(String::isNotBlank)
                ?.let(Path::of)
                ?: return null
        if (Files.isRegularFile(configured)) {
            return configured.takeIf { it.extension() in ASF_EXTENSIONS }
        }
        if (!Files.isDirectory(configured)) return null
        return Files.list(configured).use { paths ->
            paths
                .filter(Files::isRegularFile)
                .filter { it.extension() in ASF_EXTENSIONS }
                .findFirst()
                .orElse(null)
        }
    }

    private fun Path.extension(): String = fileName.toString().substringAfterLast('.', "").lowercase()

    private fun createTransparentWindow(): JFrame {
        lateinit var result: JFrame
        onEdt {
            result =
                JFrame("Compose Media Player VLC HLS ${System.nanoTime()}").apply {
                    isUndecorated = true
                    background = Color(0, 0, 0, 0)
                    contentPane = JPanel().apply { isOpaque = false }
                    setSize(WINDOW_WIDTH, WINDOW_HEIGHT)
                    setLocation(WINDOW_X, WINDOW_Y)
                    isVisible = true
                }
        }
        return result
    }

    private fun await(
        message: String,
        condition: () -> Boolean,
    ) = assertTrue(waitUntil(condition), message)

    private fun waitUntil(condition: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + TEST_TIMEOUT_NANOS
        while (System.nanoTime() < deadline) {
            if (condition()) return true
            Thread.sleep(POLL_INTERVAL_MILLIS)
        }
        return condition()
    }

    private fun onEdt(block: () -> Unit) {
        if (SwingUtilities.isEventDispatchThread()) block() else SwingUtilities.invokeAndWait(block)
    }

    private fun isMacArm64(): Boolean {
        val os = System.getProperty("os.name", "").lowercase()
        val architecture = System.getProperty("os.arch", "").lowercase()
        return (os.contains("mac") || os.contains("darwin")) && architecture in setOf("aarch64", "arm64")
    }

    private companion object {
        const val LEGACY_MEDIA_PROPERTY = "composemediaplayer.test.legacyMedia"
        const val WINDOW_WIDTH = 640
        const val WINDOW_HEIGHT = 360
        const val WINDOW_X = 120
        const val WINDOW_Y = 120
        const val POLL_INTERVAL_MILLIS = 50L
        const val TEST_TIMEOUT_NANOS = 45_000_000_000L
        const val HDR_METAL_SCALE_FIT = 0
        val ASF_EXTENSIONS = setOf("wmv", "asf")
        val VLC_UNSUPPORTED_AUDIO_CODECS = setOf("wmapro", "wmalossless")
    }
}
