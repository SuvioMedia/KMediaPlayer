package io.github.kdroidfilter.composemediaplayer

import com.kdroid.androidcontextprovider.ContextProvider
import org.junit.Assume
import org.junit.Before
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Tests for the Android implementation of VideoPlayerState
 */
class VideoPlayerStateTest {
    @Before
    fun setup() {
        assumeContextProviderAvailable()
    }

    private fun assumeContextProviderAvailable() {
        try {
            ContextProvider.getContext()
        } catch (e: IllegalStateException) {
            Assume.assumeTrue(
                "ContextProvider has not been initialized: ${e.message}",
                false,
            )
        }
    }

    private fun withPlayerState(block: (VideoPlayerState) -> Unit) {
        val playerState = createVideoPlayerState()
        try {
            block(playerState)
        } finally {
            playerState.dispose()
        }
    }

    /**
     * Test the creation of VideoPlayerState
     */
    @Test
    fun testCreateVideoPlayerState() {
        withPlayerState { playerState ->
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
        }
    }

    @Test
    fun testPlaybackControlsWithoutSourceDoNotMarkMediaAvailable() {
        withPlayerState { playerState ->
            playerState.play()
            assertFalse(playerState.hasMedia)

            playerState.restart()
            assertFalse(playerState.hasMedia)

            playerState.seekTo(5.seconds)
            assertFalse(playerState.hasMedia)
            assertFalse(playerState.isSeeking)
        }
    }

    /**
     * Test volume control
     */
    @Test
    fun testVolumeControl() {
        withPlayerState { playerState ->
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
        withPlayerState { playerState ->
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
        withPlayerState { playerState ->
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
        withPlayerState { playerState ->
            // Initially there should be no error
            assertEquals(null, playerState.error)

            // Test opening a non-existent file (should cause an error)
            playerState.openUri("non_existent_file.mp4")

            // Test clearing the error
            playerState.clearError()
            assertEquals(null, playerState.error)
        }
    }

    /**
     * Test subtitle functionality
     */
    @Test
    fun testSubtitleFunctionality() {
        withPlayerState { playerState ->
            // Initially subtitles should be disabled
            assertFalse(playerState.subtitlesEnabled)
            assertEquals(null, playerState.currentSubtitleTrack)
            assertTrue(playerState.availableSubtitleTracks.isEmpty())

            // Create a test subtitle track
            val testTrack =
                SubtitleTrack(
                    label = "English",
                    language = "en",
                    src = "test.vtt",
                )

            // Select the subtitle track
            playerState.selectSubtitleTrack(testTrack)

            // Verify subtitle state
            assertTrue(playerState.subtitlesEnabled)
            assertEquals(testTrack, playerState.currentSubtitleTrack)

            // Disable subtitles
            playerState.disableSubtitles()

            // Verify subtitle state after disabling
            assertFalse(playerState.subtitlesEnabled)
            assertEquals(null, playerState.currentSubtitleTrack)
        }
    }

    /**
     * Test metadata functionality
     */
    @Test
    fun testMetadataFunctionality() {
        withPlayerState { playerState ->
            // Verify metadata object is initialized
            assertNotNull(playerState.metadata)

            // Initially metadata fields should be null
            assertEquals(null, playerState.metadata.title)
            assertEquals(null, playerState.metadata.duration)
            assertEquals(null, playerState.metadata.width)
            assertEquals(null, playerState.metadata.height)
            assertEquals(null, playerState.metadata.bitrate)
            assertEquals(null, playerState.metadata.frameRate)
            assertEquals(null, playerState.metadata.mimeType)
            assertEquals(null, playerState.metadata.audioChannels)
            assertEquals(null, playerState.metadata.audioSampleRate)
        }
    }
}
