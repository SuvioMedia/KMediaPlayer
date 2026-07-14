package io.github.kdroidfilter.composemediaplayer

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assume
import org.junit.Before
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * Tests for the JVM implementation of VideoPlayerState
 */
class VideoPlayerStateTest {
    @Before
    fun setup() {
        assumeNativePlayerAvailable()
    }

    private fun assumeNativePlayerAvailable() {
        try {
            createVideoPlayerState().dispose()
        } catch (e: Throwable) {
            if (e.isNativeLibraryUnavailable()) {
                Assume.assumeTrue("Native player not available: ${e.message}", false)
            }
            throw e
        }
    }

    private fun Throwable.isNativeLibraryUnavailable(): Boolean =
        when (this) {
            is UnsatisfiedLinkError -> true
            is NoClassDefFoundError -> message?.contains("NativeBridge") == true
            is ExceptionInInitializerError -> cause?.isNativeLibraryUnavailable() == true
            else -> false
        }

    private fun withPlayerState(block: (VideoPlayerState) -> Unit) {
        val playerState: RenderableVideoPlayerState = createRenderableVideoPlayerState()
        try {
            block(playerState)
        } finally {
            playerState.dispose()
        }
    }

    @Test
    fun testCreateVideoPlayerState() {
        withPlayerState { playerState ->
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
    fun testVolumeControl() {
        withPlayerState { playerState ->
            assertEquals(1f, playerState.volume)

            playerState.volume = 0.5f
            assertEquals(0.5f, playerState.volume)

            playerState.volume = -0.1f
            assertEquals(0f, playerState.volume, "Volume should be clamped to 0")

            playerState.volume = 1.5f
            assertEquals(1f, playerState.volume, "Volume should be clamped to 1")
        }
    }

    @Test
    fun testLoopSetting() {
        withPlayerState { playerState ->
            assertFalse(playerState.loop)

            playerState.loop = true
            assertTrue(playerState.loop)

            playerState.loop = false
            assertFalse(playerState.loop)
        }
    }

    @Test
    fun testFullscreenToggle() {
        withPlayerState { playerState ->
            assertFalse(playerState.isFullscreen)

            playerState.toggleFullscreen()
            assertTrue(playerState.isFullscreen)

            playerState.toggleFullscreen()
            assertFalse(playerState.isFullscreen)
        }
    }

    @Test
    fun testDefaultStateReflectsDelegateSubtitleState() {
        withPlayerState { playerState ->
            val platformState = (playerState as? RenderableVideoPlayerState)?.platformState ?: playerState
            val defaultState =
                platformState as? DefaultVideoPlayerState
                    ?: error("The JVM renderable factory should wrap DefaultVideoPlayerState")

            val subtitleTrack =
                SubtitleTrack(
                    label = "ASS sample",
                    language = "en",
                    src = "/tmp/sample.ass",
                    format = SubtitleFormat.ASS,
                    isEmbedded = false,
                )

            defaultState.delegate.currentSubtitleTrack = subtitleTrack
            defaultState.delegate.subtitlesEnabled = true
            assertEquals(subtitleTrack, defaultState.currentSubtitleTrack)
            assertTrue(defaultState.subtitlesEnabled)

            defaultState.currentSubtitleTrack = null
            defaultState.subtitlesEnabled = false
            assertNull(defaultState.delegate.currentSubtitleTrack)
            assertFalse(defaultState.delegate.subtitlesEnabled)
        }
    }

    @Test
    fun testExternalSubtitleTrackApiUpdatesAvailableTracks() {
        withPlayerState { playerState ->
            val subtitleTrack =
                SubtitleTrack(
                    label = "External VTT",
                    language = "en",
                    src = "/tmp/external.vtt",
                    format = SubtitleFormat.WEBVTT,
                )

            playerState.addSubtitleTrack(subtitleTrack)

            assertEquals(
                subtitleTrack,
                playerState.availableSubtitleTracks.single { it.id == subtitleTrack.id },
            )

            playerState.currentSubtitleTrack = subtitleTrack
            playerState.subtitlesEnabled = true
            playerState.removeSubtitleTrack(subtitleTrack.id)

            assertTrue(playerState.availableSubtitleTracks.none { it.id == subtitleTrack.id })
            assertNull(playerState.currentSubtitleTrack)
            assertFalse(playerState.subtitlesEnabled)
        }
    }

    @Test
    fun testErrorHandling() {
        withPlayerState { playerState ->
            assertEquals(null, playerState.error)

            runBlocking {
                playerState.openUri("non_existent_file.mp4")
                delay(500.milliseconds)
            }

            assertNotNull(playerState.error)

            playerState.clearError()
            assertEquals(null, playerState.error)
        }
    }
}
