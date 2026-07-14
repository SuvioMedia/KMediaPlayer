package io.github.kdroidfilter.composemediaplayer.windows

import io.github.kdroidfilter.composemediaplayer.VideoPlayerError
import io.github.kdroidfilter.composemediaplayer.VideoProjectionSettings
import io.github.kdroidfilter.composemediaplayer.util.CurrentPlatform
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assume
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * Tests for the Windows implementation of VideoPlayerState
 *
 * Note: These tests will only run on Windows platforms. On other platforms,
 * the tests will be skipped.
 */
class WindowsVideoPlayerStateTest {
    private fun assumeWindows() {
        Assume.assumeTrue(
            "Skipping Windows-specific test on non-Windows platform",
            CurrentPlatform.os == CurrentPlatform.OS.WINDOWS,
        )
    }

    private fun withWindowsPlayerState(block: (WindowsVideoPlayerState) -> Unit) {
        assumeWindows()
        val playerState = WindowsVideoPlayerState()
        try {
            block(playerState)
        } finally {
            playerState.dispose()
        }
    }

    @Test
    fun testNormalizeLocalFileUriForPlaybackAcceptsFileSchemeVariants() {
        val path = assertNotNull(javaClass.classLoader.getResource("existing_file.mp4")).toURI().path

        assertEquals(path, normalizeWindowsLocalFileUriForPlayback(path))
        assertEquals(path, normalizeWindowsLocalFileUriForPlayback("file:$path"))
        assertEquals(path, normalizeWindowsLocalFileUriForPlayback("file://$path"))
        assertEquals(path, normalizeWindowsLocalFileUriForPlayback("file://localhost$path"))
    }

    @Test
    fun testNormalizeLocalFileUriForPlaybackDecodesEncodedFileUri() {
        val tempFile = Files.createTempFile("compose media player ", ".mp4")

        try {
            assertEquals(tempFile.toString(), normalizeWindowsLocalFileUriForPlayback(tempFile.toUri().toString()))
        } finally {
            Files.deleteIfExists(tempFile)
        }
    }

    @Test
    fun testNormalizeLocalFileUriForPlaybackConvertsUncFileUri() {
        assertEquals(
            "\\\\server\\share\\video.mp4",
            normalizeWindowsLocalFileUriForPlayback("file://server/share/video.mp4"),
        )
    }

    /**
     * Test the creation of WindowsVideoPlayerState
     */
    @Test
    fun testCreateWindowsVideoPlayerState() {
        withWindowsPlayerState { playerState ->
            // Verify the player state is initialized correctly
            assertNotNull(playerState)
            assertFalse(playerState.hasMedia)
            assertFalse(playerState.isPlaying)
            assertEquals(0f, playerState.sliderPos)
            assertEquals(1f, playerState.volume)
            assertFalse(playerState.loop)
            assertEquals("00:00", playerState.positionText)
            assertEquals("00:00", playerState.durationText)
            assertFalse(playerState.isFullscreen)
            assertNull(playerState.error)
        }
    }

    /**
     * Test volume control
     */
    @Test
    fun testVolumeControl() {
        withWindowsPlayerState { playerState ->
            // Test initial volume
            assertEquals(1f, playerState.volume)

            // Test setting volume
            playerState.volume = 0.5f
            assertEquals(0.5f, playerState.volume)

            // Test volume bounds
            playerState.volume = -0.1f
            assertEquals(0f, playerState.volume, "Volume should be clamped to 0")

            playerState.volume = 1.5f
            assertEquals(1f, playerState.volume, "Volume should be clamped to 1")
        }
    }

    /**
     * Test loop setting
     */
    @Test
    fun testLoopSetting() {
        withWindowsPlayerState { playerState ->
            // Test initial loop setting
            assertFalse(playerState.loop)

            // Test setting loop
            playerState.loop = true
            assertTrue(playerState.loop)

            playerState.loop = false
            assertFalse(playerState.loop)
        }
    }

    /**
     * Test fullscreen toggle
     */
    @Test
    fun testFullscreenToggle() {
        withWindowsPlayerState { playerState ->
            // Test initial fullscreen state
            assertFalse(playerState.isFullscreen)

            // Test toggling fullscreen
            playerState.toggleFullscreen()
            assertTrue(playerState.isFullscreen)

            playerState.toggleFullscreen()
            assertFalse(playerState.isFullscreen)
        }
    }

    /**
     * Test error handling
     */
    @Test
    fun testErrorHandling() {
        withWindowsPlayerState { playerState ->
            // Initially there should be no error
            assertNull(playerState.error)

            // Test opening a non-existent file (should cause an error)
            runBlocking {
                playerState.openUri("non_existent_file.mp4")
                delay(500.milliseconds) // Give some time for the error to be set
            }

            // There should be an error now
            assertNotNull(playerState.error)
            assertTrue(playerState.error is VideoPlayerError.UnknownError)

            // Test clearing the error
            playerState.clearError()
            assertNull(playerState.error)
        }
    }

    @Test
    fun disposeIsIdempotentAndRejectsCommands() {
        assumeWindows()
        val playerState = WindowsVideoPlayerState()

        playerState.dispose()
        playerState.dispose()

        val commands =
            listOf<() -> Unit>(
                { playerState.openUri("ignored.mp4") },
                { playerState.play() },
                { playerState.pause() },
                { playerState.stop() },
                { playerState.releaseSource() },
                { playerState.seekToProgress(0.5f) },
                { playerState.seekStart(0.5f) },
                { playerState.restart() },
                { playerState.clearError() },
                { playerState.clearCache() },
                { playerState.canPlaySource("ignored.mp4") },
                { playerState.selectAudioTrack("missing") },
                { playerState.selectSubtitleTrack("missing") },
                { playerState.selectHlsQuality(null) },
                { playerState.toggleFullscreen() },
                { playerState.volume = 0.5f },
                { playerState.loop = true },
                { playerState.projection = VideoProjectionSettings() },
                { playerState.onPlaybackEnded = null },
            )

        commands.forEach { command ->
            val error = assertFailsWith<IllegalStateException> { command() }
            assertEquals("VideoPlayerState has been disposed", error.message)
        }
    }
}
