package io.github.kdroidfilter.composemediaplayer.mac

import io.github.kdroidfilter.composemediaplayer.DesktopVideoBackend
import io.github.kdroidfilter.composemediaplayer.DesktopVideoSurfaceMode
import io.github.kdroidfilter.composemediaplayer.InitialPlayerState
import io.github.kdroidfilter.composemediaplayer.JvmMediaThumbnail
import io.github.kdroidfilter.composemediaplayer.VideoPlaybackOptions
import io.github.kdroidfilter.composemediaplayer.util.CurrentPlatform
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume
import org.junit.Before
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Tests for the Mac implementation of VideoPlayerState
 *
 * Note: These tests will only run on Mac platforms. On other platforms,
 * the tests will be skipped.
 */
class MacVideoPlayerStateTest {
    @Before
    fun setup() {
        Assume.assumeTrue(
            "Skipping Mac-specific test on non-Mac platform",
            CurrentPlatform.os == CurrentPlatform.OS.MAC,
        )
    }

    private fun withMacPlayerState(block: (MacVideoPlayerState) -> Unit) {
        val playerState = MacVideoPlayerState()
        try {
            block(playerState)
        } finally {
            playerState.dispose()
        }
    }

    /**
     * Test the creation of MacVideoPlayerState
     */
    @Test
    fun testCreateMacVideoPlayerState() {
        withMacPlayerState { playerState ->
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
        withMacPlayerState { playerState ->
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
        withMacPlayerState { playerState ->
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
        withMacPlayerState { playerState ->
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
        withMacPlayerState { playerState ->
            // Initially there should be no error
            assertNull(playerState.error)

            // Test opening a non-existent file (should cause an error)
            runBlocking {
                playerState.openUri("non_existent_file.mp4")
                withTimeout(15_000) {
                    while (playerState.error == null) {
                        delay(25.milliseconds)
                    }
                }
            }

            // There should be an error now
            assertNotNull(playerState.error)

            // Test clearing the error
            playerState.clearError()
            assertNull(playerState.error)
        }
    }

    private fun testOpenLocalFile(file: String) {
        withMacPlayerState { playerState ->
            // Initially there should be no error
            assertNull(playerState.error)

            // Test opening a non-existent file (should cause an error)
            runBlocking {
                playerState.openUri(file)
                delay(500.milliseconds) // Give some time for the error to be set
            }

            // There should be no error
            assertNull(playerState.error)
        }
    }

    @Test
    fun testOpenLocalFile() {
        val path = assertNotNull(javaClass.classLoader.getResource("existing_file.mp4")).toURI().path
        testOpenLocalFile(path)
    }

    @Test
    fun testOpenLocalFileWithScheme() {
        val path = assertNotNull(javaClass.classLoader.getResource("existing_file.mp4")).toURI().path
        testOpenLocalFile("file:$path")
    }

    @Test
    fun testOpenLocalFileWithSchemeWithAuthority() {
        val path = assertNotNull(javaClass.classLoader.getResource("existing_file.mp4")).toURI().path
        testOpenLocalFile("file://$path")
    }

    @Test
    fun `Compose mini-player mode receives AVFoundation frame copies without a native surface`() {
        val configuredMedia =
            System
                .getProperty("composemediaplayer.test.hdrMedia")
                ?.takeIf(String::isNotBlank)
                ?.let { java.io.File(it) }
                ?.takeIf(java.io.File::isFile)
        Assume.assumeTrue(
            "The AVFoundation frame-copy integration test needs -PkmediaPlayerHdrTestMedia=<MP4>.",
            configuredMedia != null,
        )
        val path = checkNotNull(configuredMedia).toURI().toString()
        val playerState =
            MacVideoPlayerState(
                VideoPlaybackOptions(
                    desktopVideoBackend = DesktopVideoBackend.PLATFORM,
                    desktopVideoSurfaceMode = DesktopVideoSurfaceMode.COMPOSE,
                ),
            )
        try {
            runBlocking {
                playerState.openUri(path, InitialPlayerState.PLAY)
                withTimeout(15_000) {
                    while (playerState.currentFrameState.value == null && playerState.error == null) {
                        delay(25.milliseconds)
                    }
                }
            }

            assertNull(playerState.error)
            assertNotNull(playerState.currentFrameState.value)
            assertFalse(playerState.shouldUseHdrMetalSurface())
        } finally {
            playerState.dispose()
        }
    }

    @Test
    fun `isolated thumbnail decoding leaves the visible AVFoundation player untouched`() {
        val configuredMedia =
            System
                .getProperty("composemediaplayer.test.hdrMedia")
                ?.takeIf(String::isNotBlank)
                ?.let(Path::of)
                ?.takeIf(Files::isRegularFile)
        Assume.assumeTrue(
            "The thumbnail integration test needs -PkmediaPlayerHdrTestMedia=<MP4>.",
            configuredMedia != null,
        )
        val playerState =
            MacVideoPlayerState(
                VideoPlaybackOptions(
                    desktopVideoBackend = DesktopVideoBackend.PLATFORM,
                    desktopVideoSurfaceMode = DesktopVideoSurfaceMode.PREFER_COLOR_MANAGED_TEXTURE,
                ),
            )
        try {
            val thumbnails = mutableListOf<JvmMediaThumbnail?>()
            runBlocking {
                playerState.openUri(checkNotNull(configuredMedia).toUri().toString(), InitialPlayerState.PAUSE)
                withTimeout(30.seconds) {
                    while (!playerState.hasMedia && playerState.error == null) {
                        delay(25.milliseconds)
                    }
                }
                assertNull(playerState.error)
                assertTrue(playerState.hasMedia)
                val positions = listOf(0.01, 0.34, 0.75).map { progress -> playerState.duration * progress }

                withTimeout(90.seconds) {
                    playerState.thumbnails(positions, maximumWidth = 240) { _, thumbnail ->
                        thumbnails += thumbnail
                    }
                }
            }

            assertFalse(playerState.isPlaying)
            assertTrue(playerState.currentTime < 1.seconds)
            assertEquals(3, thumbnails.size)
            thumbnails.forEach { thumbnail ->
                val generated = assertNotNull(thumbnail)
                assertTrue(generated.width in 1..240)
                assertTrue(generated.height > 0)
                assertTrue(generated.bytes.size in 4..1_048_576)
                assertEquals(0xff.toByte(), generated.bytes[0])
                assertEquals(0xd8.toByte(), generated.bytes[1])
            }
        } finally {
            playerState.dispose()
        }
    }

    private fun testMalformedUri(uri: String) {
        withMacPlayerState { playerState ->
            // Initially there should be no error
            assertNull(playerState.error)

            // Test opening a non-existent file (should cause an error)
            runBlocking {
                playerState.openUri(uri)
                delay(500.milliseconds) // Give some time for the error to be set
            }

            // There should be an error now
            assertNotNull(playerState.error)

            // Test clearing the error
            playerState.clearError()
            assertNull(playerState.error)
        }
    }

    @Test
    fun testMalformedUri() {
        val path = assertNotNull(javaClass.classLoader.getResource("existing_file.mp4")).toURI().path
        testMalformedUri("file:${path.removePrefix("/")}")
    }
}
