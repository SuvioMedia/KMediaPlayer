package io.github.kdroidfilter.composemediaplayer

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.js
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Tests for the WebAssembly implementation of VideoPlayerState
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VideoPlayerStateTest {
    /**
     * Test the creation of VideoPlayerState
     */
    @Test
    fun testCreateVideoPlayerState() {
        val playerState = createVideoPlayerState()

        // Verify the player state is initialized correctly
        assertNotNull(playerState)
        assertFalse(playerState.hasMedia)
        assertFalse(playerState.isPlaying)
        assertEquals(0f, playerState.sliderPos)
        assertEquals(1f, playerState.volume)
        assertEquals(1f, playerState.playbackSpeed)
        assertFalse(playerState.loop)
        assertEquals("00:00", playerState.positionText)
        assertEquals("00:00", playerState.durationText)
        assertFalse(playerState.isFullscreen)

        // Clean up
        playerState.dispose()
    }

    /**
     * Test volume control
     */
    @Test
    fun testVolumeControl() {
        val playerState = createVideoPlayerState()

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

        // Clean up
        playerState.dispose()
    }

    @Test
    fun `testPlaybackSpeedControl`() {
        val playerState = createVideoPlayerState()

        // Test initial speed
        assertEquals(1f, playerState.playbackSpeed)

        // Test setting speed
        playerState.playbackSpeed = 0.5f
        assertEquals(0.5f, playerState.playbackSpeed)

        // Test speed bounds
        playerState.playbackSpeed = -1f
        assertEquals(0.25f, playerState.playbackSpeed, "Playback speed should be clamped to 0.25")

        playerState.playbackSpeed = 10f
        assertEquals(2f, playerState.playbackSpeed, "Playback speed should be clamped to 2")

        // Clean up
        playerState.dispose()
    }

    /**
     * Test loop setting
     */
    @Test
    fun testLoopSetting() {
        val playerState = createVideoPlayerState()

        // Test initial loop setting
        assertFalse(playerState.loop)

        // Test setting loop
        playerState.loop = true
        assertTrue(playerState.loop)

        playerState.loop = false
        assertFalse(playerState.loop)

        // Clean up
        playerState.dispose()
    }

    /**
     * Test fullscreen toggle
     */
    @Test
    fun testFullscreenToggle() {
        val playerState = createVideoPlayerState()

        try {
            // Test initial fullscreen state
            assertFalse(playerState.isFullscreen)

            // Browser fullscreen requires user activation, which Karma/Chrome Headless does not provide.
            if (!canRequestFullscreenFromCurrentContext()) return

            // Test toggling fullscreen
            playerState.toggleFullscreen()
            assertTrue(playerState.isFullscreen)

            playerState.toggleFullscreen()
            assertFalse(playerState.isFullscreen)
        } finally {
            // Clean up
            playerState.dispose()
        }
    }

    /**
     * Test error handling
     */
    @Test
    fun testErrorHandling() {
        val playerState = createVideoPlayerState()
        val webPlayerState = playerState as DefaultVideoPlayerState

        // Initially there should be no error
        assertEquals(null, playerState.error)

        // Test setting an error manually (since we can't easily trigger a real error in tests)
        webPlayerState.setError(VideoPlayerError.NetworkError("Test error"))

        // There should be an error now
        assertNotNull(playerState.error)

        // Test clearing the error
        playerState.clearError()
        assertEquals(null, playerState.error)

        // Clean up
        playerState.dispose()
    }

    /**
     * Test subtitle functionality
     */
    @Test
    fun testSubtitleFunctionality() {
        val playerState = createVideoPlayerState()

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

        // Clean up
        playerState.dispose()
    }

    /**
     * Test position updates
     */
    @Test
    fun testPositionUpdates() {
        val playerState = createVideoPlayerState()
        val webPlayerState = playerState as DefaultVideoPlayerState

        // Test initial position
        assertEquals(0f, playerState.sliderPos)
        assertEquals("00:00", playerState.positionText)
        assertEquals("00:00", playerState.durationText)

        // Test updating position manually with forceUpdate to bypass rate limiting
        webPlayerState.updatePosition(30.seconds, 120.seconds, forceUpdate = true)

        // Verify position was updated
        assertEquals("00:30", playerState.positionText)
        assertEquals("02:00", playerState.durationText)

        // Clean up
        playerState.dispose()
    }

    @Test
    fun testCurrentTimeUpdatesBypassDisplayRateLimit() {
        val playerState = createVideoPlayerState()
        val webPlayerState = playerState as DefaultVideoPlayerState

        webPlayerState.updatePosition(30.seconds, 120.seconds, forceUpdate = true)
        webPlayerState.updatePosition(30.seconds + 123.milliseconds, 120.seconds + 456.milliseconds)

        assertEquals(30.seconds + 123.milliseconds, playerState.currentTime)
        assertEquals(120.seconds + 456.milliseconds, playerState.duration)
        assertEquals("00:30", playerState.positionText)
        assertEquals("02:00", playerState.durationText)

        playerState.dispose()
    }

    @Test
    fun testSeekToMarksExplicitSeekRequest() {
        val playerState = createVideoPlayerState()
        val webPlayerState = playerState as DefaultVideoPlayerState
        val initialRequestId = webPlayerState.seekRequestId

        webPlayerState.updatePosition(0.seconds, 120.seconds, forceUpdate = true)
        playerState.seekToProgress(0.5f)

        assertTrue(webPlayerState.seekRequestId > initialRequestId)
        assertTrue(webPlayerState.hasPendingSeekRequest())
        assertEquals(60.seconds, webPlayerState.consumePendingSeekTime(120.seconds))
        assertFalse(webPlayerState.hasPendingSeekRequest())
        assertEquals(null, webPlayerState.consumePendingSeekTime(120.seconds))

        playerState.dispose()
    }

    @Test
    fun testStopClearsExplicitSeekRequest() {
        val playerState = createVideoPlayerState()
        val webPlayerState = playerState as DefaultVideoPlayerState

        playerState.seekToProgress(0.5f)
        playerState.stop()

        assertFalse(webPlayerState.hasPendingSeekRequest())

        playerState.dispose()
    }

    @Test
    fun testPreciseCurrentTimeCanReadDirectProviderValue() {
        val playerState = createVideoPlayerState()
        val webPlayerState = playerState as DefaultVideoPlayerState

        webPlayerState.preciseCurrentTimeProvider = { 42.seconds + 789.milliseconds }
        webPlayerState.durationProvider = { 120.seconds + 987.milliseconds }

        assertEquals(0.seconds, playerState.currentTime)
        assertEquals(42.seconds + 789.milliseconds, playerState.preciseCurrentTime)
        assertEquals(120.seconds + 987.milliseconds, playerState.duration)

        playerState.dispose()
    }

    @Test
    fun testLoadingVideoDoNotResetPlaybackSpeed() {
        val playerState = createVideoPlayerState()
        playerState.playbackSpeed = 2f

        playerState.openUri("file:///path/to/file")

        assertEquals(2f, playerState.playbackSpeed)
    }

    @Test
    fun testMediaSessionIdInvalidatesPreviousSource() {
        val playerState = createVideoPlayerState() as DefaultVideoPlayerState

        playerState.openUri("file:///first.mp4")
        val firstSessionId = playerState.mediaSessionId

        playerState.openUri("file:///second.mp4")
        val secondSessionId = playerState.mediaSessionId

        assertTrue(secondSessionId > firstSessionId)
        assertFalse(playerState.isCurrentMediaSession(firstSessionId))
        assertTrue(playerState.isCurrentMediaSession(secondSessionId))

        playerState.stop()

        assertFalse(playerState.isCurrentMediaSession(secondSessionId))
        playerState.dispose()
    }

    @Test
    fun testOpenUriReleasesPreviousMediaSession() =
        runTest {
            val playerState = createVideoPlayerState() as DefaultVideoPlayerState
            val events = collectEvents(playerState)

            playerState.openUri("file:///first.mp4")
            playerState.openUri("file:///second.mp4")
            runCurrent()

            val firstPreparing = events[0] as PlaybackEvent.SourcePreparing
            assertEquals(1L, firstPreparing.mediaSessionId)
            assertEquals("file:///first.mp4", firstPreparing.uri)

            val firstReleased = events[1] as PlaybackEvent.SourceReleased
            assertEquals(1L, firstReleased.mediaSessionId)

            val secondPreparing = events[2] as PlaybackEvent.SourcePreparing
            assertEquals(2L, secondPreparing.mediaSessionId)
            assertEquals("file:///second.mp4", secondPreparing.uri)
            assertEquals(3, events.size)

            playerState.dispose()
        }

    @Test
    fun testRepeatedStopDoesNotReleaseEmptyMediaSession() {
        val playerState = createVideoPlayerState() as DefaultVideoPlayerState

        playerState.openUri("file:///video.mp4")
        playerState.stop()
        val stoppedSessionId = playerState.mediaSessionId

        playerState.stop()

        assertEquals(stoppedSessionId, playerState.mediaSessionId)
        playerState.dispose()
    }

    @Test
    fun testRestartEmitsPlaybackRestartedForActiveMedia() =
        runTest {
            val playerState = createVideoPlayerState() as DefaultVideoPlayerState
            val events = collectEvents(playerState)

            playerState.openUri("file:///video.mp4")
            runCurrent()
            events.clear()

            playerState.restart()
            runCurrent()

            assertTrue(playerState.isPlaying)
            assertTrue(events.any { it is PlaybackEvent.PlaybackRestarted && it.mediaSessionId == 1L })

            playerState.dispose()
        }

    @Test
    fun testAutoHlsQualityWithoutHlsSourceIsNotSupported() =
        runTest {
            val playerState = createVideoPlayerState() as DefaultVideoPlayerState
            val events = collectEvents(playerState)

            val result = playerState.selectAutoHlsQuality()
            runCurrent()

            assertEquals(HlsQualitySelectionResult.NotSupported, result)
            assertEquals(emptyList(), events)

            playerState.dispose()
        }

    @Test
    fun testStopClearsStaleHlsQualityCallback() =
        runTest {
            val playerState = createVideoPlayerState() as DefaultVideoPlayerState
            val events = collectEvents(playerState)
            var callbackCalls = 0
            playerState.applyHlsQualityCallback = { callbackCalls += 1 }

            playerState.stop()
            val result = playerState.selectAutoHlsQuality()
            runCurrent()

            assertEquals(HlsQualitySelectionResult.NotSupported, result)
            assertEquals(0, callbackCalls)
            assertEquals(emptyList(), events)

            playerState.dispose()
        }

    @Test
    fun testHlsQualityRowsIgnoreInvalidVariantsAndMetrics() {
        val snapshot =
            parseHlsQualityRows(
                listOf(
                    "hls:good|720p|1280|720|2500000|avc1|1|0",
                    "|Missing id|1920|1080|5000000|avc1|0|0",
                    "hls:missing-label||1920|1080|5000000|avc1|0|0",
                    "hls:partial|Partial|-1|0|-500|avc1|0|0",
                ).joinToString("\n"),
            )

        assertEquals(2, snapshot.variants.size)
        assertEquals("hls:good", snapshot.selectedId)
        assertFalse(snapshot.autoMode)
        assertEquals(HlsQualityVariant("hls:good", "720p", 1280, 720, 2500000, "avc1"), snapshot.variants[0])
        assertEquals(HlsQualityVariant("hls:partial", "Partial", codecs = "avc1"), snapshot.variants[1])
    }

    @Test
    fun testBufferedRangeRowsIgnoreInvalidRanges() {
        val playerState = createVideoPlayerState() as DefaultVideoPlayerState

        playerState.updateBufferedRanges(
            listOf(
                "0|5",
                "5|1",
                "-1|3",
                "NaN|4",
                "8|12",
            ).joinToString("\n"),
        )

        assertEquals(
            listOf(
                BufferedRange(0.seconds, 5.seconds),
                BufferedRange(8.seconds, 12.seconds),
            ),
            playerState.bufferedRanges.toList(),
        )

        playerState.dispose()
    }

    @Test
    fun testWebCapabilitiesClassifySources() {
        val playerState = createVideoPlayerState()

        assertFalse(playerState.canPlaySource(""))
        assertTrue(playerState.canPlaySource("blob:https://example.test/video"))
        assertTrue(playerState.canPlaySource("data:video/mp4;base64,AAA"))
        assertTrue(playerState.canPlaySource("https://example.test/playlist.m3u8"))
        assertFalse(playerState.canPlaySource("file:///movie.mp4"))
        assertFalse(playerState.canPlaySource("file:///movie.mkv"))
        assertFalse(playerState.canPlaySource("""C:\Videos\movie.mp4"""))
        assertFalse(playerState.canPlaySource("""\\server\share\movie.mp4"""))

        playerState.dispose()
    }

    private fun TestScope.collectEvents(state: VideoPlayerState): MutableList<PlaybackEvent> {
        val events = mutableListOf<PlaybackEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            state.playbackEvents.collect { event ->
                events += event
            }
        }
        return events
    }
}

@OptIn(ExperimentalWasmJsInterop::class)
private fun canRequestFullscreenFromCurrentContext(): Boolean =
    js(
        """
        !!(
            document.documentElement &&
            typeof document.documentElement.requestFullscreen === "function" &&
            navigator.userActivation &&
            navigator.userActivation.isActive
        )
        """,
    )
