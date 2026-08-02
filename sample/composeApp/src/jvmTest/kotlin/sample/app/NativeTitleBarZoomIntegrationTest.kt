package sample.app

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import io.github.kdroidfilter.composemediaplayer.DesktopVideoBackend
import io.github.kdroidfilter.composemediaplayer.DesktopVideoSurfaceMode
import io.github.kdroidfilter.composemediaplayer.VideoPlaybackOptions
import java.awt.Frame
import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import java.awt.Robot
import java.awt.Toolkit
import java.awt.event.InputEvent
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference
import javax.swing.SwingUtilities
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class NativeTitleBarZoomIntegrationTest {
    @Test
    fun avFoundationSampleWindowDoubleClickZoomsAndRestoresWithoutFreezingVideo() {
        runSampleWindowZoomTest("PLATFORM")
    }

    @Test
    fun mpvSampleWindowDoubleClickZoomsAndRestoresWithoutFreezingVideo() {
        runSampleWindowZoomTest("MPV")
    }

    private fun runSampleWindowZoomTest(backendName: String) {
        if (!isMacArm64() || GraphicsEnvironment.isHeadless()) return
        val media = configuredMedia() ?: return
        val failure = AtomicReference<Throwable?>()

        application(exitProcessOnExit = false) {
            var appVisible by remember { mutableStateOf(true) }
            if (appVisible) {
                Window(
                    onCloseRequest = { appVisible = false },
                    title = TEST_CATALOG_TITLE,
                    state = rememberWindowState(width = 720.dp, height = 900.dp),
                ) {
                    App(
                        initialVideoUrl = media.toUri().toString(),
                        demoSubtitleEnabled = false,
                        initialLoop = true,
                        playbackOptions =
                            VideoPlaybackOptions(
                                desktopVideoBackend = DesktopVideoBackend.PLATFORM,
                                desktopVideoSurfaceMode = DesktopVideoSurfaceMode.PREFER_NATIVE,
                            ),
                        initialDesktopBackendName = backendName,
                        initialDesktopSourceAdapterName = "DIRECT",
                    )
                }
            }

            LaunchedEffect(Unit) {
                try {
                    withContext(Dispatchers.IO) { verifyActualSampleWindowZoomRoundTrip() }
                } catch (error: Throwable) {
                    failure.set(error)
                } finally {
                    appVisible = false
                    exitApplication()
                }
            }
        }

        failure.get()?.let { throw it }
    }

    private fun verifyActualSampleWindowZoomRoundTrip() {
        val playerWindow = awaitPlayerWindow()
        activate(playerWindow)
        val original = awaitStableBounds(playerWindow)
        assertRenderedPixelsAdvance(playerWindow, "before title-bar Zoom")

        repeat(ZOOM_ROUND_TRIPS) { index ->
            doubleClickTitleBar(playerWindow)
            await("Title-bar double-click did not Zoom the Compose player window on cycle ${index + 1}.") {
                !sameBounds(bounds(playerWindow), original)
            }
            awaitStableBounds(playerWindow)
            assertRenderedPixelsAdvance(playerWindow, "after title-bar Zoom cycle ${index + 1}")
            Thread.sleep(ZOOMED_WINDOW_HOLD_MILLIS)

            doubleClickTitleBar(playerWindow)
            await("The second title-bar double-click did not restore the window on cycle ${index + 1}.") {
                sameBounds(bounds(playerWindow), original)
            }
            awaitStableBounds(playerWindow)
            assertRenderedPixelsAdvance(playerWindow, "after restoring title-bar Zoom cycle ${index + 1}")
        }

        doubleClickTitleBar(playerWindow)
        Thread.sleep(RAPID_SECOND_DOUBLE_CLICK_DELAY_MILLIS)
        doubleClickTitleBar(playerWindow)
        await("A second double-click issued during the native Zoom animation was lost.") {
            sameBounds(bounds(playerWindow), original)
        }
        awaitStableBounds(playerWindow)
        assertRenderedPixelsAdvance(playerWindow, "after a rapid title-bar Zoom round trip")
    }

    private fun awaitPlayerWindow(): Frame {
        var result: Frame? = null
        await("The sample did not open its dedicated native Compose player window.") {
            result =
                onEdtResult {
                    Frame
                        .getFrames()
                        .firstOrNull { frame ->
                            frame.isShowing && frame.title.contains(NATIVE_PLAYER_TITLE_MARKER)
                        }
                }
            result != null
        }
        return checkNotNull(result)
    }

    private fun activate(window: Frame) {
        onEdt {
            window.toFront()
            window.requestFocus()
        }
        await("The Compose player window did not become active for the title-bar test.") {
            onEdtResult { window.isActive }
        }
    }

    private fun doubleClickTitleBar(window: Frame) {
        val frame = bounds(window)
        val robot = Robot(window.graphicsConfiguration.device).apply { autoDelay = ROBOT_EVENT_DELAY_MILLIS }
        robot.mouseMove(frame.x + frame.width / 2, frame.y + TITLE_BAR_CLICK_Y_PX)
        repeat(2) {
            robot.mousePress(InputEvent.BUTTON1_DOWN_MASK)
            robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK)
        }
        Toolkit.getDefaultToolkit().sync()
    }

    private fun assertRenderedPixelsAdvance(
        window: Frame,
        label: String,
    ) {
        val frame = bounds(window)
        val horizontalInset = frame.width / CAPTURE_INSET_DIVISOR
        val verticalInset = frame.height / CAPTURE_INSET_DIVISOR
        val capture =
            Rectangle(
                frame.x + horizontalInset,
                frame.y + verticalInset,
                frame.width - 2 * horizontalInset,
                frame.height - 2 * verticalInset,
            )
        val robot = Robot(window.graphicsConfiguration.device)
        Thread.sleep(FRAME_SETTLE_MILLIS)
        val first = robot.createScreenCapture(capture)
        Thread.sleep(FRAME_ADVANCE_MILLIS)
        val second = robot.createScreenCapture(capture)

        var samples = 0
        var temporallyDifferent = 0
        for (y in 0 until first.height step SAMPLE_STRIDE) {
            for (x in 0 until first.width step SAMPLE_STRIDE) {
                samples++
                if (colorDistance(first.getRGB(x, y), second.getRGB(x, y)) >= COLOR_DISTANCE_THRESHOLD) {
                    temporallyDifferent++
                }
            }
        }
        assertTrue(
            temporallyDifferent >= samples / MINIMUM_CHANGE_DIVISOR,
            "The real Compose/AVFoundation player image froze $label.",
        )
    }

    private fun awaitStableBounds(window: Frame): Rectangle {
        var previous = bounds(window)
        var stableSamples = 0
        await("The Compose player window never settled after title-bar Zoom.") {
            Thread.sleep(BOUNDS_STABILITY_SAMPLE_MILLIS)
            val current = bounds(window)
            if (sameBounds(current, previous)) {
                stableSamples++
            } else {
                stableSamples = 0
                previous = current
            }
            stableSamples >= REQUIRED_STABLE_BOUNDS_SAMPLES
        }
        return previous
    }

    private fun bounds(window: Frame): Rectangle = onEdtResult { window.bounds }

    private fun sameBounds(
        actual: Rectangle,
        expected: Rectangle,
    ): Boolean =
        abs(actual.x - expected.x) <= BOUNDS_TOLERANCE_PX &&
            abs(actual.y - expected.y) <= BOUNDS_TOLERANCE_PX &&
            abs(actual.width - expected.width) <= BOUNDS_TOLERANCE_PX &&
            abs(actual.height - expected.height) <= BOUNDS_TOLERANCE_PX

    private fun colorDistance(
        first: Int,
        second: Int,
    ): Int =
        abs((first shr 16 and 0xff) - (second shr 16 and 0xff)) +
            abs((first shr 8 and 0xff) - (second shr 8 and 0xff)) +
            abs((first and 0xff) - (second and 0xff))

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
        if (SwingUtilities.isEventDispatchThread()) {
            block()
        } else {
            SwingUtilities.invokeAndWait(block)
        }
    }

    private fun <T> onEdtResult(block: () -> T): T {
        if (SwingUtilities.isEventDispatchThread()) return block()
        var result: Result<T>? = null
        SwingUtilities.invokeAndWait { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    private fun configuredMedia(): Path? =
        System
            .getProperty(TEST_MEDIA_PROPERTY)
            ?.takeIf(String::isNotBlank)
            ?.let(Path::of)
            ?.takeIf(Files::isRegularFile)

    private fun isMacArm64(): Boolean {
        val os = System.getProperty("os.name", "").lowercase()
        val architecture = System.getProperty("os.arch", "").lowercase()
        return (os.contains("mac") || os.contains("darwin")) &&
            architecture in setOf("aarch64", "arm64")
    }

    private companion object {
        const val TEST_MEDIA_PROPERTY = "composemediaplayer.test.hdrMedia"
        const val TEST_CATALOG_TITLE = "Compose Media Player native Zoom integration"
        const val NATIVE_PLAYER_TITLE_MARKER = "native-player-"
        const val ROBOT_EVENT_DELAY_MILLIS = 35
        const val TITLE_BAR_CLICK_Y_PX = 12
        const val CAPTURE_INSET_DIVISOR = 8
        const val SAMPLE_STRIDE = 4
        const val COLOR_DISTANCE_THRESHOLD = 18
        const val MINIMUM_CHANGE_DIVISOR = 700
        const val FRAME_SETTLE_MILLIS = 250L
        const val FRAME_ADVANCE_MILLIS = 350L
        const val BOUNDS_STABILITY_SAMPLE_MILLIS = 75L
        const val REQUIRED_STABLE_BOUNDS_SAMPLES = 4
        const val ZOOM_ROUND_TRIPS = 3
        const val ZOOMED_WINDOW_HOLD_MILLIS = 2_000L
        const val RAPID_SECOND_DOUBLE_CLICK_DELAY_MILLIS = 100L
        const val BOUNDS_TOLERANCE_PX = 2
        const val POLL_INTERVAL_MILLIS = 25L
        const val TEST_TIMEOUT_NANOS = 20_000_000_000L
    }
}
