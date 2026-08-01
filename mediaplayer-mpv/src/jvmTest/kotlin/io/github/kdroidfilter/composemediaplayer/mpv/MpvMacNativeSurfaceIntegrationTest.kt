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
import java.awt.Color
import java.awt.GraphicsEnvironment
import java.awt.MouseInfo
import java.awt.Point
import java.awt.Rectangle
import java.awt.Robot
import java.awt.event.InputEvent
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.JFrame
import javax.swing.JPanel
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.math.abs
import kotlin.time.Duration.Companion.milliseconds

class MpvMacNativeSurfaceIntegrationTest {
    @Test
    fun closingWindowCancelsDelayedFullscreenReconciliation() {
        if (!isMacArm64() || GraphicsEnvironment.isHeadless()) return
        configuredFile(MPV_LIBRARY_PROPERTY) ?: return
        assertTrue(MpvMacNativeBridge.isAvailable, "The embedded MPV macOS window bridge did not load.")
        val window = createTransparentWindow()
        val windowedBounds = windowBounds(window)
        try {
            assertTrue(MpvMacNativeBridge.nSetWindowFullscreen(window, true))
            await("The MPV window did not enter full screen before the close test.") {
                MpvMacNativeBridge.nIsWindowFullscreen(window)
            }
            assertTrue(MpvMacNativeBridge.nSetWindowFullscreen(window, false))
            await("The MPV window did not restore its exact frame before the close test.") {
                !MpvMacNativeBridge.nIsWindowFullscreen(window) &&
                    sameBounds(windowBounds(window), windowedBounds)
            }
        } finally {
            onEdt { window.dispose() }
        }

        // The delayed native reconciliation must not retain a dangling raw NSWindow pointer.
        Thread.sleep(DELAYED_RECONCILIATION_SURVIVAL_MILLIS)
    }

    @Test
    fun attachesResizesAndReturnsToSoftwareRendering() {
        if (!isMacArm64() || GraphicsEnvironment.isHeadless()) return
        val library = configuredFile(MPV_LIBRARY_PROPERTY) ?: return
        val nativeSurfaceFixture = configuredFile(NATIVE_SURFACE_MEDIA_PROPERTY)
        val media =
            nativeSurfaceFixture
                ?: configuredFile(WMAPRO_MEDIA_PROPERTY)
                ?: configuredLegacyDirectory()?.findWmv()
                ?: return
        val player =
            assertIs<MpvVideoPlayerState>(
                createMpvVideoPlayerState(
                    MpvPlaybackOptions(
                        runtimeSource = MpvRuntimeSource.ExplicitPath(library.toString()),
                    ),
                ),
            )
        val window = createTransparentWindow()

        try {
            assertTrue(player.attachNativeMacWindow(window), "The native macOS MPV surface did not attach.")
            assertContains(player.renderingInfo.videoRenderer.orEmpty(), "OpenGL")
            dragWindowThroughNativeAppKitRegion(window)

            player.openUri(media.toUri().toString(), InitialPlayerState.PLAY)
            await("MPV did not play the native-surface fixture.") {
                player.hasMedia && player.isPlaying && !player.isLoading && player.currentTime >= 250.milliseconds
            }
            assertEquals(null, player.error)
            if (nativeSurfaceFixture != null) {
                await("MPV did not activate VideoToolbox for the H.264 native-surface fixture.") {
                    player.renderingInfo.videoDecoder
                        ?.contains("videotoolbox", ignoreCase = true) == true
                }
            }
            val timeBeforeFullscreen = player.currentTime
            verifyNativeFullscreenRoundTrips(window)
            await("Playback stalled during the native MPV full-screen transitions.") {
                player.currentTime >= timeBeforeFullscreen + 250.milliseconds
            }

            resizeWindow(window)
            val timeBeforeResize = player.currentTime
            await("Playback stalled while resizing the native MPV window.") {
                player.currentTime >= timeBeforeResize + 250.milliseconds
            }

            player.detachNativeMacWindow()
            assertContains(player.renderingInfo.videoRenderer.orEmpty(), "software")
            await(
                message = {
                    "MPV did not resume software rendering after native detach: " +
                        "hasMedia=${player.hasMedia}, playing=${player.isPlaying}, " +
                        "time=${player.currentTime}, error=${player.error}."
                },
            ) {
                player.renderFrame(RENDER_WIDTH, RENDER_HEIGHT)
                player.currentFrame.value
                    ?.toPixelMap()
                    ?.buffer
                    ?.hasVisibleVariation() == true
            }
        } finally {
            player.dispose()
            onEdt { window.dispose() }
        }
    }

    @Test
    fun disposesAttachedMpvBeforeStartingPlatformBackend() {
        if (!isMacArm64() || GraphicsEnvironment.isHeadless()) return
        val library = configuredFile(MPV_LIBRARY_PROPERTY) ?: return
        val media = configuredFile(NATIVE_SURFACE_MEDIA_PROPERTY) ?: return
        val mpvPlayer =
            assertIs<MpvVideoPlayerState>(
                createMpvVideoPlayerState(
                    MpvPlaybackOptions(
                        runtimeSource = MpvRuntimeSource.ExplicitPath(library.toString()),
                    ),
                ),
            )
        val window = createTransparentWindow()
        var platformPlayer: VideoPlayerState? = null

        try {
            assertTrue(mpvPlayer.attachNativeMacWindow(window))
            mpvPlayer.openUri(media.toUri().toString(), InitialPlayerState.PLAY)
            await("MPV did not start before the backend handoff.") {
                mpvPlayer.hasMedia &&
                    mpvPlayer.isPlaying &&
                    mpvPlayer.currentTime >= 250.milliseconds
            }

            // Match the sample handoff: dispose MPV while its AWT window still exists, then start
            // AVFoundation. A stale render callback here used to crash inside Objective-C retain.
            mpvPlayer.dispose()
            platformPlayer =
                createVideoPlayerState(
                    playbackOptions =
                        VideoPlaybackOptions(
                            desktopVideoBackend = DesktopVideoBackend.PLATFORM,
                        ),
                )
            platformPlayer.openUri(media.toUri().toString(), InitialPlayerState.PLAY)
            await("AVFoundation did not start after disposing the attached MPV backend.") {
                platformPlayer.hasMedia &&
                    platformPlayer.isPlaying &&
                    platformPlayer.currentTime >= 250.milliseconds
            }
            assertEquals(null, platformPlayer.error)
        } finally {
            platformPlayer?.dispose()
            mpvPlayer.dispose()
            onEdt { window.dispose() }
        }
    }

    private fun createTransparentWindow(): JFrame {
        lateinit var result: JFrame
        onEdt {
            result =
                JFrame("Compose Media Player MPV integration ${System.nanoTime()}").apply {
                    isUndecorated = true
                    background = Color(0, 0, 0, 0)
                    contentPane = JPanel().apply { isOpaque = false }
                    setSize(640, 360)
                    setLocation(40, 40)
                    isVisible = true
                }
        }
        return result
    }

    private fun resizeWindow(window: JFrame) {
        listOf(720 to 405, 960 to 540, 560 to 315, 800 to 450).forEach { (width, height) ->
            onEdt { window.setSize(width, height) }
            Thread.sleep(RESIZE_SETTLE_MILLIS)
        }
    }

    private fun verifyNativeFullscreenRoundTrips(window: JFrame) {
        val windowedBounds = windowBounds(window)
        repeat(NATIVE_FULLSCREEN_ROUND_TRIPS) { index ->
            val screenBounds = screenBounds(window)
            assertTrue(
                MpvMacNativeBridge.nSetWindowFullscreen(window, true),
                "AppKit rejected native MPV full-screen request ${index + 1}.",
            )
            await("The MPV window reported full screen without covering the display on cycle ${index + 1}.") {
                MpvMacNativeBridge.nIsWindowFullscreen(window) &&
                    sameBounds(windowBounds(window), screenBounds)
            }
            assertRenderedPixelsAdvanceInFullscreen(window)
            assertTrue(
                MpvMacNativeBridge.nSetWindowFullscreen(window, false),
                "AppKit rejected native MPV full-screen exit ${index + 1}.",
            )
            await("The MPV window did not restore its exact frame on cycle ${index + 1}.") {
                !MpvMacNativeBridge.nIsWindowFullscreen(window) &&
                    sameBounds(windowBounds(window), windowedBounds)
            }
        }
    }

    private fun windowBounds(window: JFrame): Rectangle = onEdtResult { window.bounds }

    private fun screenBounds(window: JFrame): Rectangle =
        onEdtResult { window.graphicsConfiguration.bounds }

    private fun sameBounds(
        actual: Rectangle,
        expected: Rectangle,
    ): Boolean =
        abs(actual.x - expected.x) <= WINDOW_BOUNDS_TOLERANCE_PX &&
            abs(actual.y - expected.y) <= WINDOW_BOUNDS_TOLERANCE_PX &&
            abs(actual.width - expected.width) <= WINDOW_BOUNDS_TOLERANCE_PX &&
            abs(actual.height - expected.height) <= WINDOW_BOUNDS_TOLERANCE_PX

    private fun assertRenderedPixelsAdvanceInFullscreen(window: JFrame) {
        val screen = screenBounds(window)
        val captureBounds =
            Rectangle(
                screen.x + screen.width / VISUAL_CAPTURE_INSET_DIVISOR,
                screen.y + screen.height / VISUAL_CAPTURE_INSET_DIVISOR,
                screen.width - 2 * (screen.width / VISUAL_CAPTURE_INSET_DIVISOR),
                screen.height - 2 * (screen.height / VISUAL_CAPTURE_INSET_DIVISOR),
            )
        val robot = Robot(window.graphicsConfiguration.device)
        Thread.sleep(VISUAL_FRAME_SETTLE_MILLIS)
        val first = robot.createScreenCapture(captureBounds)
        Thread.sleep(VISUAL_FRAME_ADVANCE_MILLIS)
        val second = robot.createScreenCapture(captureBounds)

        var samples = 0
        var spatiallyDifferent = 0
        var temporallyDifferent = 0
        val firstPixel = first.getRGB(0, 0)
        for (y in 0 until first.height step VISUAL_SAMPLE_STRIDE) {
            for (x in 0 until first.width step VISUAL_SAMPLE_STRIDE) {
                val before = first.getRGB(x, y)
                val after = second.getRGB(x, y)
                samples++
                if (colorDistance(before, firstPixel) >= VISUAL_COLOR_DISTANCE_THRESHOLD) {
                    spatiallyDifferent++
                }
                if (colorDistance(before, after) >= VISUAL_COLOR_DISTANCE_THRESHOLD) {
                    temporallyDifferent++
                }
            }
        }
        assertTrue(
            spatiallyDifferent >= samples / VISUAL_MINIMUM_VARIATION_DIVISOR,
            "The native MPV full-screen surface did not contain a visible video frame.",
        )
        assertTrue(
            temporallyDifferent >= samples / VISUAL_MINIMUM_CHANGE_DIVISOR,
            "The native MPV image froze after entering full screen.",
        )
    }

    private fun colorDistance(
        first: Int,
        second: Int,
    ): Int =
        abs((first shr 16 and 0xff) - (second shr 16 and 0xff)) +
            abs((first shr 8 and 0xff) - (second shr 8 and 0xff)) +
            abs((first and 0xff) - (second and 0xff))

    private fun dragWindowThroughNativeAppKitRegion(window: JFrame) {
        val origin = window.locationOnScreen
        val pointerBeforeTest = MouseInfo.getPointerInfo()?.location
        val start = Point(origin.x + NATIVE_DRAG_TEST_X, origin.y + NATIVE_DRAG_TEST_Y)
        val expected = Point(origin.x + NATIVE_DRAG_DELTA_X, origin.y + NATIVE_DRAG_DELTA_Y)
        val robot = Robot().apply { autoDelay = NATIVE_DRAG_STEP_DELAY_MILLIS }
        try {
            assertComposeChromeInsetDoesNotDragWindow(window, robot, origin)
            robot.mouseMove(start.x, start.y)
            robot.mousePress(InputEvent.BUTTON1_DOWN_MASK)
            try {
                repeat(NATIVE_DRAG_STEPS) { index ->
                    val progress = index + 1
                    robot.mouseMove(
                        start.x + NATIVE_DRAG_DELTA_X * progress / NATIVE_DRAG_STEPS,
                        start.y + NATIVE_DRAG_DELTA_Y * progress / NATIVE_DRAG_STEPS,
                    )
                }
            } finally {
                robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK)
            }
            await("AppKit did not move the native MPV window through its drag region.") {
                val actual = window.locationOnScreen
                abs(actual.x - expected.x) <= NATIVE_DRAG_TOLERANCE_PX &&
                    abs(actual.y - expected.y) <= NATIVE_DRAG_TOLERANCE_PX
            }
        } finally {
            pointerBeforeTest?.let { robot.mouseMove(it.x, it.y) }
        }
    }

    private fun assertComposeChromeInsetDoesNotDragWindow(
        window: JFrame,
        robot: Robot,
        origin: Point,
    ) {
        val iconArea = Point(origin.x + COMPOSE_CHROME_TEST_X, origin.y + NATIVE_DRAG_TEST_Y)
        robot.mouseMove(iconArea.x, iconArea.y)
        robot.mousePress(InputEvent.BUTTON1_DOWN_MASK)
        try {
            robot.mouseMove(
                iconArea.x + COMPOSE_CHROME_TEST_DELTA_X,
                iconArea.y + COMPOSE_CHROME_TEST_DELTA_Y,
            )
        } finally {
            robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK)
        }
        Thread.sleep(NATIVE_DRAG_SETTLE_MILLIS)
        val actual = window.locationOnScreen
        assertTrue(
            abs(actual.x - origin.x) <= COMPOSE_CHROME_STATIONARY_TOLERANCE_PX &&
                abs(actual.y - origin.y) <= COMPOSE_CHROME_STATIONARY_TOLERANCE_PX,
            "The native AppKit drag region covered the Compose window icons.",
        )
    }

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

    private fun isMacArm64(): Boolean {
        val os = System.getProperty("os.name", "").lowercase()
        val architecture = System.getProperty("os.arch", "").lowercase()
        return (os.contains("mac") || os.contains("darwin")) &&
            architecture in setOf("aarch64", "arm64")
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

    private fun await(
        message: String,
        condition: () -> Boolean,
    ) = await({ message }, condition)

    private fun await(
        message: () -> String,
        condition: () -> Boolean,
    ) {
        val deadline = System.nanoTime() + TEST_TIMEOUT_NANOS
        while (System.nanoTime() < deadline) {
            if (condition()) return
            Thread.sleep(POLL_INTERVAL_MILLIS)
        }
        assertTrue(condition(), message())
    }

    private fun IntArray.hasVisibleVariation(): Boolean = isNotEmpty() && any { pixel -> pixel != first() }

    private companion object {
        const val MPV_LIBRARY_PROPERTY = "composemediaplayer.mpvLibraryPath"
        const val LEGACY_MEDIA_PROPERTY = "composemediaplayer.legacyTestMedia"
        const val WMAPRO_MEDIA_PROPERTY = "composemediaplayer.wmaProTestMedia"
        const val NATIVE_SURFACE_MEDIA_PROPERTY = "composemediaplayer.nativeSurfaceTestMedia"
        const val RENDER_WIDTH = 320
        const val RENDER_HEIGHT = 180
        const val RESIZE_SETTLE_MILLIS = 60L
        const val NATIVE_DRAG_TEST_X = 180
        const val NATIVE_DRAG_TEST_Y = 16
        const val COMPOSE_CHROME_TEST_X = 48
        const val COMPOSE_CHROME_TEST_DELTA_X = 50
        const val COMPOSE_CHROME_TEST_DELTA_Y = 30
        const val COMPOSE_CHROME_STATIONARY_TOLERANCE_PX = 2
        const val NATIVE_DRAG_DELTA_X = 120
        const val NATIVE_DRAG_DELTA_Y = 90
        const val NATIVE_DRAG_STEPS = 6
        const val NATIVE_DRAG_STEP_DELAY_MILLIS = 20
        const val NATIVE_DRAG_SETTLE_MILLIS = 150L
        const val NATIVE_DRAG_TOLERANCE_PX = 8
        const val NATIVE_FULLSCREEN_ROUND_TRIPS = 1
        const val WINDOW_BOUNDS_TOLERANCE_PX = 2
        const val VISUAL_CAPTURE_INSET_DIVISOR = 8
        const val VISUAL_SAMPLE_STRIDE = 4
        const val VISUAL_COLOR_DISTANCE_THRESHOLD = 18
        const val VISUAL_MINIMUM_VARIATION_DIVISOR = 20
        const val VISUAL_MINIMUM_CHANGE_DIVISOR = 500
        const val VISUAL_FRAME_SETTLE_MILLIS = 250L
        const val VISUAL_FRAME_ADVANCE_MILLIS = 350L
        const val DELAYED_RECONCILIATION_SURVIVAL_MILLIS = 1_600L
        const val POLL_INTERVAL_MILLIS = 25L
        const val TEST_TIMEOUT_NANOS = 15_000_000_000L
    }
}
