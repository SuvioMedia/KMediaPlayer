package io.github.kdroidfilter.composemediaplayer.mac

import io.github.kdroidfilter.composemediaplayer.DesktopVideoBackend
import io.github.kdroidfilter.composemediaplayer.DesktopVideoSurfaceMode
import io.github.kdroidfilter.composemediaplayer.InitialPlayerState
import io.github.kdroidfilter.composemediaplayer.VideoPlaybackOptions
import java.awt.Color
import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import java.awt.Robot
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.JFrame
import javax.swing.JPanel
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

class MacLibVlcLegacyIntegrationTest {
    @Test
    fun playsConfiguredAviWmvAndWmaProInANativeWindow() {
        if (!isMacArm64() || GraphicsEnvironment.isHeadless()) return
        val inputs = configuredMedia()
        if (inputs.isEmpty()) return

        assertTrue(inputs.any { it.extension() == "avi" }, "The configured fixtures contain no AVI file.")
        assertTrue(inputs.any { it.extension() in ASF_EXTENSIONS }, "The configured fixtures contain no WMV/ASF file.")
        inputs.forEach(::verifyNativeLibVlcPlayback)
    }

    private fun verifyNativeLibVlcPlayback(media: Path) {
        val player =
            MacVideoPlayerState(
                playbackOptions =
                    VideoPlaybackOptions(
                        desktopVideoBackend = DesktopVideoBackend.LIBVLC_NATIVE,
                        desktopVideoSurfaceMode = DesktopVideoSurfaceMode.PREFER_NATIVE,
                    ),
            )
        val window = createTransparentWindow(media.fileName.toString())
        var attached = false
        try {
            player.openUri(media.toUri().toString(), InitialPlayerState.PLAY)
            await("libVLC did not start ${media.fileName}.") {
                player.hasMedia && player.isPlaying && !player.isLoading && player.currentTime >= 250.milliseconds
            }
            assertEquals(null, player.error, "libVLC reported a playback error for ${media.fileName}.")
            assertContains(player.renderingInfo.backend.orEmpty(), "libVLC")
            assertNotNull(player.currentAudioTrack, "libVLC did not select an audio track for ${media.fileName}.")
            assertTrue(player.availableAudioTracks.isNotEmpty(), "libVLC found no audio track in ${media.fileName}.")

            attached = player.attachLibVlcNativeWindow(window)
            assertTrue(attached, "The native libVLC surface did not attach for ${media.fileName}.")
            await("The native libVLC surface did not display ${media.fileName}.") {
                capturedPixels(window).hasVisibleVariation()
            }
            val timeBeforeObservation = player.currentTime
            await("libVLC stalled after native attachment for ${media.fileName}.") {
                player.currentTime >= timeBeforeObservation + 250.milliseconds
            }
        } finally {
            if (attached) runCatching { player.detachLibVlcNativeComponent(window) }
            player.dispose()
            onEdt { window.dispose() }
        }
    }

    private fun configuredMedia(): List<Path> {
        val legacy =
            System
                .getProperty(LEGACY_MEDIA_PROPERTY)
                ?.takeIf(String::isNotBlank)
                ?.let(Path::of)
                ?.legacyMediaFiles()
                .orEmpty()
        val wmaPro =
            System
                .getProperty(WMAPRO_MEDIA_PROPERTY)
                ?.takeIf(String::isNotBlank)
                ?.let(Path::of)
                ?.takeIf(Files::isRegularFile)
        return (legacy + listOfNotNull(wmaPro)).distinct()
    }

    private fun Path.legacyMediaFiles(): List<Path> =
        if (Files.isDirectory(this)) {
            Files.list(this).use { paths ->
                paths
                    .filter(Files::isRegularFile)
                    .filter { it.extension() in LEGACY_EXTENSIONS }
                    .sorted()
                    .toList()
            }
        } else {
            listOf(this).filter { Files.isRegularFile(it) && it.extension() in LEGACY_EXTENSIONS }
        }

    private fun Path.extension(): String = fileName.toString().substringAfterLast('.', "").lowercase()

    private fun createTransparentWindow(label: String): JFrame {
        lateinit var result: JFrame
        onEdt {
            result =
                JFrame("Compose Media Player libVLC $label ${System.nanoTime()}").apply {
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

    private fun capturedPixels(window: JFrame): IntArray {
        val bounds = onEdtResult { Rectangle(window.locationOnScreen, window.size) }
        val image = Robot(window.graphicsConfiguration.device).createScreenCapture(bounds)
        return image.getRGB(0, 0, image.width, image.height, null, 0, image.width)
    }

    private fun IntArray.hasVisibleVariation(): Boolean = isNotEmpty() && any { pixel -> pixel != first() }

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

    private fun onEdt(block: () -> Unit) {
        if (SwingUtilities.isEventDispatchThread()) block() else SwingUtilities.invokeAndWait(block)
    }

    private fun <T> onEdtResult(block: () -> T): T {
        if (SwingUtilities.isEventDispatchThread()) return block()
        var result: Result<T>? = null
        SwingUtilities.invokeAndWait { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    private fun isMacArm64(): Boolean {
        val os = System.getProperty("os.name", "").lowercase()
        val architecture = System.getProperty("os.arch", "").lowercase()
        return (os.contains("mac") || os.contains("darwin")) && architecture in setOf("aarch64", "arm64")
    }

    private companion object {
        const val LEGACY_MEDIA_PROPERTY = "composemediaplayer.test.legacyMedia"
        const val WMAPRO_MEDIA_PROPERTY = "composemediaplayer.test.wmaProMedia"
        const val WINDOW_WIDTH = 640
        const val WINDOW_HEIGHT = 360
        const val WINDOW_X = 100
        const val WINDOW_Y = 100
        const val POLL_INTERVAL_MILLIS = 50L
        const val TEST_TIMEOUT_NANOS = 15_000_000_000L
        val ASF_EXTENSIONS = setOf("wmv", "asf")
        val LEGACY_EXTENSIONS = ASF_EXTENSIONS + "avi"
    }
}
