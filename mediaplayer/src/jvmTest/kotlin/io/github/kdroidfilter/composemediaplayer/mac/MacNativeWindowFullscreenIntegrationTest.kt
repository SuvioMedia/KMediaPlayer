package io.github.kdroidfilter.composemediaplayer.mac

import io.github.kdroidfilter.composemediaplayer.DesktopVideoBackend
import io.github.kdroidfilter.composemediaplayer.DesktopVideoSurfaceMode
import io.github.kdroidfilter.composemediaplayer.InitialPlayerState
import io.github.kdroidfilter.composemediaplayer.VideoPlaybackOptions
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/** Verifies the AVFoundation view used by the in-place native-window fullscreen path. */
class MacNativeWindowFullscreenIntegrationTest {
    @Test
    fun rejectsMissingTaoNativeView() {
        assertFalse(MacNativeWindowFullscreen.setFullscreen(0L, fullscreen = true))
    }

    @Test
    fun createsAndDisposesTaoHostedAvFoundationView() {
        if (!isMacArm64()) return
        val media = configuredMedia() ?: return
        val player =
            MacVideoPlayerState(
                playbackOptions =
                    VideoPlaybackOptions(
                        desktopVideoBackend = DesktopVideoBackend.PLATFORM,
                        desktopVideoSurfaceMode = DesktopVideoSurfaceMode.PREFER_NATIVE,
                    ),
            )
        var nativeView = 0L
        try {
            player.openUri(media.toUri().toString(), InitialPlayerState.PLAY)
            await("AVFoundation did not start before NSView creation.") {
                player.hasMedia && player.isPlaying && player.currentTime >= 250.milliseconds
            }
            nativeView = player.createNativeVideoView(CONTENT_SCALE_FIT)
            assertTrue(nativeView != 0L, "The AVFoundation/Metal NSView was not created.")
            val timeBeforeObservation = player.currentTime
            await("AVFoundation stalled while the Tao-hosted NSView was active.") {
                player.currentTime >= timeBeforeObservation + 250.milliseconds ||
                    player.currentTime < timeBeforeObservation
            }
            player.disposeNativeVideoView(nativeView)
            nativeView = 0L
            assertEquals(null, player.error)
        } finally {
            if (nativeView != 0L) runCatching { player.disposeNativeVideoView(nativeView) }
            player.dispose()
        }
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

    private fun isMacArm64(): Boolean {
        val os = System.getProperty("os.name", "").lowercase()
        val architecture = System.getProperty("os.arch", "").lowercase()
        return (os.contains("mac") || os.contains("darwin")) && architecture in setOf("aarch64", "arm64")
    }

    private companion object {
        const val HDR_TEST_MEDIA_PROPERTY = "composemediaplayer.test.hdrMedia"
        const val CONTENT_SCALE_FIT = 0
        const val POLL_INTERVAL_MILLIS = 25L
        const val TEST_TIMEOUT_NANOS = 15_000_000_000L
    }
}
