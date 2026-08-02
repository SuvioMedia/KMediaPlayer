package io.github.kdroidfilter.composemediaplayer.mac

import io.github.kdroidfilter.composemediaplayer.DesktopVideoBackend
import io.github.kdroidfilter.composemediaplayer.DesktopVideoSurfaceMode
import io.github.kdroidfilter.composemediaplayer.DefaultVideoPlayerState
import io.github.kdroidfilter.composemediaplayer.InitialPlayerState
import io.github.kdroidfilter.composemediaplayer.VideoPlaybackOptions
import io.github.kdroidfilter.composemediaplayer.desktop.DesktopVideoWindowSurfaceProvider
import java.awt.Color
import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import java.awt.Robot
import java.awt.Toolkit
import java.awt.event.InputEvent
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.JFrame
import javax.swing.JPanel
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

class MacNativeWindowFullscreenIntegrationTest {
    @Test
    fun desktopWindowProviderForwardsFullscreenToAppKit() {
        if (!isMacArm64() || GraphicsEnvironment.isHeadless()) return
        val window = createTransparentWindow()
        val player =
            DefaultVideoPlayerState(
                VideoPlaybackOptions(desktopVideoSurfaceMode = DesktopVideoSurfaceMode.PREFER_NATIVE),
            )
        val provider = assertIs<DesktopVideoWindowSurfaceProvider>(player)
        try {
            assertTrue(provider.configureNativeWindow(window))
            val windowedBounds = windowBounds(window)
            assertEquals(false, provider.nativeWindowFullscreenState(window))
            assertTrue(provider.requestWindowFullscreen(window, true))
            await("The desktop-window provider did not enter AppKit full screen.") {
                MacNativeBridge.nIsWindowFullscreen(window) &&
                    sameBounds(windowBounds(window), screenBounds(window))
            }
            assertTrue(provider.requestWindowFullscreen(window, false))
            await("The desktop-window provider did not leave AppKit full screen.") {
                !MacNativeBridge.nIsWindowFullscreen(window)
            }
            val restoredBounds = windowBounds(window)
            assertTrue(
                sameBounds(restoredBounds, windowedBounds),
                "The decorated AppKit window restored $restoredBounds instead of $windowedBounds.",
            )
        } finally {
            restoreWindow(window)
            player.dispose()
            onEdt { window.dispose() }
        }
    }

    @Test
    fun entersAndLeavesAppKitFullscreen() {
        if (!isMacArm64() || GraphicsEnvironment.isHeadless()) return
        val window = createTransparentWindow()
        val windowedBounds = windowBounds(window)
        try {
            repeat(WINDOW_FULLSCREEN_ROUND_TRIPS) { index ->
                enterFullscreen(window, "shared native window cycle ${index + 1}")
                leaveFullscreen(window, windowedBounds, "shared native window cycle ${index + 1}")
            }
        } finally {
            restoreWindow(window)
            onEdt { window.dispose() }
        }
    }

    @Test
    fun closingWindowCancelsDelayedFullscreenReconciliation() {
        if (!isMacArm64() || GraphicsEnvironment.isHeadless()) return
        val window = createTransparentWindow()
        val windowedBounds = windowBounds(window)
        try {
            enterFullscreen(window)
            leaveFullscreen(window, windowedBounds)
        } finally {
            restoreWindow(window)
            onEdt { window.dispose() }
        }

        // The native coordinator reconciles AppKit state after 1.25 s. Keeping the process alive
        // beyond that deadline catches stale callbacks that outlive their AWT-owned NSWindow.
        Thread.sleep(DELAYED_RECONCILIATION_SURVIVAL_MILLIS)
    }

    @Test
    fun keepsAttachedAvFoundationVideoAliveAcrossFullscreenTransitions() {
        if (!isMacArm64() || GraphicsEnvironment.isHeadless()) return
        val media = configuredMedia() ?: return
        val window = createTransparentWindow()
        val player =
            MacVideoPlayerState(
                playbackOptions =
                    VideoPlaybackOptions(
                        desktopVideoBackend = DesktopVideoBackend.PLATFORM,
                        desktopVideoSurfaceMode = DesktopVideoSurfaceMode.PREFER_NATIVE,
                    ),
            )
        var attached = false
        try {
            assertTrue(player.configureDedicatedWindow(window))
            val windowedBounds = windowBounds(window)
            player.openUri(media.toUri().toString(), InitialPlayerState.PLAY)
            await("AVFoundation did not start the native-window fixture.") {
                player.hasMedia &&
                    player.isPlaying &&
                    player.currentTime >= 250.milliseconds
            }
            attached = player.attachHdrMetalWindow(window, HDR_METAL_SCALE_FIT)
            assertTrue(attached, "The AVFoundation layer did not attach to its dedicated window.")

            val timeBeforeFullscreen = player.currentTime
            repeat(VIDEO_FULLSCREEN_ROUND_TRIPS) { index ->
                enterFullscreen(window, "AVFoundation cycle ${index + 1}")
                assertRenderedPixelsAdvanceInFullscreen(window)
                leaveFullscreen(window, windowedBounds, "AVFoundation cycle ${index + 1}")
            }
            await("AVFoundation playback stalled during native full-screen transitions.") {
                player.currentTime >= timeBeforeFullscreen + 250.milliseconds
            }
            assertEquals(null, player.error)
        } finally {
            restoreWindow(window)
            if (attached) runCatching { player.detachHdrMetalComponent(window) }
            player.dispose()
            onEdt { window.dispose() }
        }
    }

    @Test
    fun nativeTitleBarDoubleClickZoomsAndRestoresWhileVideoKeepsAdvancing() {
        if (!isMacArm64() || GraphicsEnvironment.isHeadless()) return
        val media = configuredMedia() ?: return
        val window = createTransparentWindow()
        val player =
            MacVideoPlayerState(
                playbackOptions =
                    VideoPlaybackOptions(
                        desktopVideoBackend = DesktopVideoBackend.PLATFORM,
                        desktopVideoSurfaceMode = DesktopVideoSurfaceMode.PREFER_NATIVE,
                    ),
            )
        var attached = false
        try {
            assertTrue(player.configureDedicatedWindow(window))
            val windowedBounds = windowBounds(window)
            player.loop = true
            player.openUri(media.toUri().toString(), InitialPlayerState.PLAY)
            await("AVFoundation did not start before the native title-bar Zoom test.") {
                player.hasMedia &&
                    player.isPlaying &&
                    player.currentTime >= 250.milliseconds
            }
            attached = player.attachHdrMetalWindow(window, HDR_METAL_SCALE_FIT)
            assertTrue(attached, "The AVFoundation layer did not attach before title-bar Zoom.")
            activateWindowForRobot(window)

            val timeBeforeZoom = player.currentTime
            doubleClickNativeTitleBar(window)
            await("A real double-click on the AppKit title bar did not Zoom the window.") {
                !sameBounds(windowBounds(window), windowedBounds)
            }
            assertRenderedPixelsAdvanceInWindow(window, "after native title-bar Zoom")
            await("AVFoundation playback stalled after native title-bar Zoom.") {
                player.currentTime >= timeBeforeZoom + 250.milliseconds ||
                    player.currentTime < timeBeforeZoom
            }

            val timeBeforeRestore = player.currentTime
            doubleClickNativeTitleBar(window)
            await("The second AppKit title-bar double-click did not restore the window frame.") {
                sameBounds(windowBounds(window), windowedBounds)
            }
            assertRenderedPixelsAdvanceInWindow(window, "after restoring from title-bar Zoom")
            await("AVFoundation playback stalled after restoring from native title-bar Zoom.") {
                player.currentTime >= timeBeforeRestore + 250.milliseconds ||
                    player.currentTime < timeBeforeRestore
            }
            assertEquals(null, player.error)
        } finally {
            restoreWindow(window)
            if (attached) runCatching { player.detachHdrMetalComponent(window) }
            player.dispose()
            onEdt { window.dispose() }
        }
    }

    private fun enterFullscreen(
        window: JFrame,
        label: String = "shared native window",
    ) {
        val screenBounds = screenBounds(window)
        assertTrue(
            MacNativeBridge.nSetWindowFullscreen(window, true),
            "$label rejected the native full-screen request.",
        )
        await("$label reported full screen without covering the display.") {
            MacNativeBridge.nIsWindowFullscreen(window) &&
                sameBounds(windowBounds(window), screenBounds)
        }
    }

    private fun leaveFullscreen(
        window: JFrame,
        expectedWindowedBounds: Rectangle,
        label: String = "shared native window",
    ) {
        assertTrue(
            MacNativeBridge.nSetWindowFullscreen(window, false),
            "$label rejected the native full-screen exit.",
        )
        await("$label did not restore its exact windowed frame.") {
            !MacNativeBridge.nIsWindowFullscreen(window) &&
                sameBounds(windowBounds(window), expectedWindowedBounds)
        }
    }

    private fun windowBounds(window: JFrame): Rectangle = onEdtResult { window.bounds }

    private fun screenBounds(window: JFrame): Rectangle = onEdtResult { window.graphicsConfiguration.bounds }

    private fun sameBounds(
        actual: Rectangle,
        expected: Rectangle,
    ): Boolean =
        kotlin.math.abs(actual.x - expected.x) <= WINDOW_BOUNDS_TOLERANCE_PX &&
            kotlin.math.abs(actual.y - expected.y) <= WINDOW_BOUNDS_TOLERANCE_PX &&
            kotlin.math.abs(actual.width - expected.width) <= WINDOW_BOUNDS_TOLERANCE_PX &&
            kotlin.math.abs(actual.height - expected.height) <= WINDOW_BOUNDS_TOLERANCE_PX

    private fun assertRenderedPixelsAdvanceInFullscreen(window: JFrame) {
        val screen = screenBounds(window)
        val captureBounds =
            Rectangle(
                screen.x + screen.width / VISUAL_CAPTURE_INSET_DIVISOR,
                screen.y + screen.height / VISUAL_CAPTURE_INSET_DIVISOR,
                screen.width - 2 * (screen.width / VISUAL_CAPTURE_INSET_DIVISOR),
                screen.height - 2 * (screen.height / VISUAL_CAPTURE_INSET_DIVISOR),
            )
        assertRenderedPixelsAdvance(window, captureBounds, "in native full screen")
    }

    private fun assertRenderedPixelsAdvanceInWindow(
        window: JFrame,
        label: String,
    ) {
        val bounds = windowBounds(window)
        val horizontalInset = bounds.width / VISUAL_CAPTURE_INSET_DIVISOR
        val verticalInset = bounds.height / VISUAL_CAPTURE_INSET_DIVISOR
        val captureBounds =
            Rectangle(
                bounds.x + horizontalInset,
                bounds.y + verticalInset,
                bounds.width - 2 * horizontalInset,
                bounds.height - 2 * verticalInset,
            )
        assertRenderedPixelsAdvance(window, captureBounds, label)
    }

    private fun assertRenderedPixelsAdvance(
        window: JFrame,
        captureBounds: Rectangle,
        label: String,
    ) {
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
            "The native surface did not contain a visible video frame $label.",
        )
        assertTrue(
            temporallyDifferent >= samples / VISUAL_MINIMUM_CHANGE_DIVISOR,
            "The AVFoundation image froze $label.",
        )
    }

    private fun activateWindowForRobot(window: JFrame) {
        onEdt {
            window.toFront()
            window.requestFocus()
        }
        await("The native player window did not become active for the title-bar test.") {
            onEdtResult { window.isActive }
        }
    }

    private fun doubleClickNativeTitleBar(window: JFrame) {
        val bounds = windowBounds(window)
        val robot = Robot(window.graphicsConfiguration.device).apply { autoDelay = ROBOT_EVENT_DELAY_MILLIS }
        robot.mouseMove(bounds.x + bounds.width / 2, bounds.y + TITLE_BAR_CLICK_Y_PX)
        repeat(2) {
            robot.mousePress(InputEvent.BUTTON1_DOWN_MASK)
            robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK)
        }
        Toolkit.getDefaultToolkit().sync()
    }

    private fun colorDistance(
        first: Int,
        second: Int,
    ): Int =
        kotlin.math.abs((first shr 16 and 0xff) - (second shr 16 and 0xff)) +
            kotlin.math.abs((first shr 8 and 0xff) - (second shr 8 and 0xff)) +
            kotlin.math.abs((first and 0xff) - (second and 0xff))

    private fun restoreWindow(window: JFrame) {
        if (runCatching { MacNativeBridge.nIsWindowFullscreen(window) }.getOrDefault(false)) {
            runCatching { MacNativeBridge.nSetWindowFullscreen(window, false) }
            runCatching {
                await("The test window could not be restored before disposal.") {
                    !MacNativeBridge.nIsWindowFullscreen(window)
                }
            }
        }
    }

    private fun createTransparentWindow(): JFrame {
        lateinit var result: JFrame
        onEdt {
            result =
                JFrame("Compose Media Player native fullscreen ${System.nanoTime()}").apply {
                    isUndecorated = true
                    background = Color(0, 0, 0, 0)
                    contentPane = JPanel().apply { isOpaque = false }
                    setSize(640, 360)
                    setLocation(80, 80)
                    isVisible = true
                }
        }
        return result
    }

    private fun configuredMedia(): Path? =
        System
            .getProperty(HDR_TEST_MEDIA_PROPERTY)
            ?.takeIf(String::isNotBlank)
            ?.let(Path::of)
            ?.takeIf(Files::isRegularFile)

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

    private fun isMacArm64(): Boolean {
        val os = System.getProperty("os.name", "").lowercase()
        val architecture = System.getProperty("os.arch", "").lowercase()
        return (os.contains("mac") || os.contains("darwin")) &&
            architecture in setOf("aarch64", "arm64")
    }

    private companion object {
        const val HDR_TEST_MEDIA_PROPERTY = "composemediaplayer.test.hdrMedia"
        const val HDR_METAL_SCALE_FIT = 0
        const val WINDOW_FULLSCREEN_ROUND_TRIPS = 3
        const val VIDEO_FULLSCREEN_ROUND_TRIPS = 1
        const val WINDOW_BOUNDS_TOLERANCE_PX = 2
        const val VISUAL_CAPTURE_INSET_DIVISOR = 8
        const val VISUAL_SAMPLE_STRIDE = 4
        const val VISUAL_COLOR_DISTANCE_THRESHOLD = 18
        const val VISUAL_MINIMUM_VARIATION_DIVISOR = 20
        const val VISUAL_MINIMUM_CHANGE_DIVISOR = 500
        const val VISUAL_FRAME_SETTLE_MILLIS = 250L
        const val VISUAL_FRAME_ADVANCE_MILLIS = 350L
        const val ROBOT_EVENT_DELAY_MILLIS = 35
        const val TITLE_BAR_CLICK_Y_PX = 12
        const val DELAYED_RECONCILIATION_SURVIVAL_MILLIS = 1_600L
        const val POLL_INTERVAL_MILLIS = 25L
        const val TEST_TIMEOUT_NANOS = 15_000_000_000L
    }
}
