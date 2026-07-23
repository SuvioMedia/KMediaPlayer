@file:Suppress("FunctionNaming", "MagicNumber")

package io.github.kdroidfilter.composemediaplayer

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.w3c.dom.events.Event
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.js
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Tests for the WebAssembly implementation of VideoPlayerState
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VideoPlayerStateTest {
    private fun createVideoPlayerState(): VideoPlayerState =
        io.github.kdroidfilter.composemediaplayer.createVideoPlayerState(
            playbackOptions = VideoPlaybackOptions(webPlaybackEngine = configuredWebTestPlaybackEngine()),
        )

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
        assertEquals(
            configuredWebTestPlaybackEngine(),
            (playerState as DefaultVideoPlayerState).playbackOptions.webPlaybackEngine,
        )
        assertEquals(
            if (configuredWebTestPlaybackEngine() == WebPlaybackEngine.LEGACY) {
                LEGACY_RENDERING_BACKEND
            } else {
                MOVI_RENDERING_BACKEND
            },
            playerState.renderingInfo.backend,
        )
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
    fun testReleaseSourceInvalidatesPreviousSource() {
        val playerState = createVideoPlayerState() as DefaultVideoPlayerState

        playerState.openUri("file:///first.mp4")
        val firstSessionId = playerState.mediaSessionId

        playerState.openUri("file:///second.mp4")
        val secondSessionId = playerState.mediaSessionId

        assertTrue(secondSessionId > firstSessionId)
        assertFalse(playerState.isCurrentMediaSession(firstSessionId))
        assertTrue(playerState.isCurrentMediaSession(secondSessionId))

        playerState.releaseSource()

        assertFalse(playerState.isCurrentMediaSession(secondSessionId))
        playerState.dispose()
    }

    @Test
    fun testStopKeepsCurrentSourceReusable() {
        val playerState = createVideoPlayerState() as DefaultVideoPlayerState

        playerState.openUri("https://example.test/video.mp4")
        val sessionId = playerState.mediaSessionId
        playerState.play()
        playerState.stop()

        assertTrue(playerState.hasMedia)
        assertEquals("https://example.test/video.mp4", playerState.sourceUri)
        assertEquals(sessionId, playerState.mediaSessionId)
        assertFalse(playerState.isPlaying)
        assertEquals(0.seconds, playerState.currentTime)

        playerState.play()
        assertTrue(playerState.isPlaying)
        assertEquals(sessionId, playerState.mediaSessionId)
        playerState.dispose()
    }

    @Test
    fun testPlaybackCallbackRunsSynchronouslyWithTransportCommand() {
        val playerState = createVideoPlayerState() as DefaultVideoPlayerState
        val appliedStates = mutableListOf<Boolean>()
        playerState.applyPlaybackCallback = appliedStates::add
        playerState.openUri(
            uri = "https://example.test/video.mp4",
            initializePlayerState = InitialPlayerState.PAUSE,
        )

        playerState.play()
        playerState.pause()

        assertEquals(listOf(true, false), appliedStates)
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
    fun testReleaseSourceClearsStaleHlsQualityCallback() =
        runTest {
            val playerState = createVideoPlayerState() as DefaultVideoPlayerState
            val events = collectEvents(playerState)
            var callbackCalls = 0
            playerState.applyHlsQualityCallback = { callbackCalls += 1 }

            playerState.releaseSource()
            val result = playerState.selectAutoHlsQuality()
            runCurrent()

            assertEquals(HlsQualitySelectionResult.NotSupported, result)
            assertEquals(0, callbackCalls)
            assertEquals(emptyList(), events)

            playerState.dispose()
        }

    @Test
    fun testSeekAndStallEventsArePairedWithoutDuplicates() =
        runTest {
            val playerState = createVideoPlayerState() as DefaultVideoPlayerState
            val events = collectEvents(playerState)
            playerState.openUri("https://example.test/video.mp4")
            playerState.updatePosition(0.seconds, 60.seconds, forceUpdate = true)
            runCurrent()
            events.clear()

            playerState.seekTo(10.seconds)
            playerState.onWebSeeking()
            playerState.onWebSeeking()
            playerState.onWebSeeked()
            playerState.onWebSeeked()
            playerState.onWebPlaybackReady()
            playerState.onWebWaiting()
            playerState.onWebWaiting()
            playerState.onWebPlaybackReady()
            playerState.onWebPlaybackReady()
            runCurrent()

            assertEquals(4, events.size)
            assertIs<PlaybackEvent.SeekStarted>(events[0])
            assertIs<PlaybackEvent.SeekCompleted>(events[1])
            assertIs<PlaybackEvent.Stalled>(events[2])
            assertIs<PlaybackEvent.Recovered>(events[3])
            assertTrue(events.all { it.mediaSessionId == playerState.mediaSessionId })

            playerState.dispose()
        }

    @Test
    fun testStopPositionResetDoesNotEmitSeekEvents() =
        runTest {
            val playerState = createVideoPlayerState() as DefaultVideoPlayerState
            val events = collectEvents(playerState)
            playerState.openUri("https://example.test/video.mp4")
            playerState.updatePosition(5.seconds, 60.seconds, forceUpdate = true)
            runCurrent()
            events.clear()

            playerState.stop()
            playerState.onWebSeeking()
            playerState.onWebSeeked()
            runCurrent()

            assertTrue(events.none { it is PlaybackEvent.SeekStarted || it is PlaybackEvent.SeekCompleted })
            playerState.dispose()
        }

    @Test
    fun testSourceLoadedIsEmittedOncePerSession() =
        runTest {
            val playerState = createVideoPlayerState() as DefaultVideoPlayerState
            val events = collectEvents(playerState)
            playerState.openUri("https://example.test/video.mp4")
            runCurrent()
            events.clear()

            playerState.onWebSourceLoaded(60.seconds)
            playerState.onWebSourceLoaded(60.seconds)
            runCurrent()

            assertEquals(1, events.size)
            assertIs<PlaybackEvent.SourceLoaded>(events.single())
            playerState.dispose()
        }

    @Test
    fun testReleaseSourceIgnoresLateBrowserSignals() =
        runTest {
            val playerState = createVideoPlayerState() as DefaultVideoPlayerState
            val events = collectEvents(playerState)
            playerState.openUri("https://example.test/video.mp4")
            runCurrent()
            playerState.releaseSource()
            runCurrent()
            events.clear()

            playerState.onWebSeeking()
            playerState.onWebSeeked()
            playerState.onWebWaiting()
            playerState.onWebPlaybackReady()
            playerState.onWebSourceLoaded(60.seconds)
            runCurrent()

            assertEquals(emptyList(), events)
            assertFalse(playerState.hasMedia)
            playerState.dispose()
        }

    @Test
    @Suppress("LongMethod")
    fun testDisposeIsIdempotentAndPublicCommandsFailAfterwards() {
        val playerState = createVideoPlayerState() as DefaultVideoPlayerState
        val audioTrack = AudioTrack(id = "audio", label = "Audio")
        val subtitleTrack = SubtitleTrack(label = "Subtitle", language = "en", src = "subtitle.vtt")
        playerState.openUri("https://example.test/video.mp4")

        playerState.dispose()
        playerState.dispose()

        val commands =
            listOf<() -> Unit>(
                { playerState.openUri("https://example.test/other.mp4") },
                { playerState.play() },
                { playerState.pause() },
                { playerState.stop() },
                { playerState.releaseSource() },
                { playerState.restart() },
                { playerState.prepare("https://example.test/other.mp4") },
                { playerState.openAsset("video.mp4") },
                { playerState.seekTo(1.seconds) },
                { playerState.seekToMs(1_000L) },
                { playerState.seekBy(1.seconds) },
                { playerState.seekByMs(1_000L) },
                { playerState.seekToProgress(0.5f) },
                { playerState.seekStart(0.5f) },
                { playerState.seekFinished() },
                { playerState.clearError() },
                { playerState.clearCache() },
                { playerState.canPlaySource("https://example.test/video.mp4") },
                {
                    playerState.addSubtitleTrack(
                        SubtitleTrack(label = "Track", language = "", src = "track.vtt", id = "id"),
                    )
                },
                { playerState.selectAudioTrack(null as AudioTrack?) },
                { playerState.selectAudioTrack(audioTrack) },
                { playerState.selectAudioTrack("missing") },
                { playerState.selectSubtitleTrack(subtitleTrack) },
                { playerState.selectSubtitleTrack("missing") },
                { playerState.removeSubtitleTrack(subtitleTrack.id) },
                { playerState.removeSubtitleTrack(subtitleTrack) },
                { playerState.clearExternalSubtitleTracks() },
                { playerState.replaceExternalSubtitleTracks(listOf(subtitleTrack)) },
                { playerState.disableSubtitles() },
                { playerState.selectHlsQuality(null) },
                { playerState.selectAutoHlsQuality() },
                { playerState.toggleFullscreen() },
                { playerState.volume = 0.5f },
                { playerState.sliderPos = 500f },
                { playerState.userDragging = true },
                { playerState.loop = true },
                { playerState.playbackSpeed = 1.5f },
                { playerState.isFullscreen = true },
                { playerState.isPipActive = true },
                { playerState.isPipEnabled = true },
                { playerState.projection = VideoProjectionSettings() },
                { playerState.projectionView = VideoProjectionViewSettings(yawDegrees = 10f) },
                { playerState.projectionViewControlMode = VideoProjectionViewControlMode.MANUAL },
                { playerState.projectionTextureCrop = VideoTextureCrop(left = 0.1f) },
                { playerState.onPlaybackEnded = null },
                { playerState.onRestart = null },
                { playerState.currentAudioTrack = audioTrack },
                { playerState.subtitlesEnabled = true },
                { playerState.currentSubtitleTrack = subtitleTrack },
                { playerState.subtitleTextStyle = TextStyle.Default },
                { playerState.subtitleBackgroundColor = Color.Transparent },
                { playerState.subtitleOffset = 1.seconds },
            )

        commands.forEach { command ->
            val error = assertFailsWith<IllegalStateException> { command() }
            assertEquals("VideoPlayerState has been disposed", error.message)
        }
    }

    @Test
    fun testDisposedStateRejectsEnterPip() =
        runTest {
            val playerState = createVideoPlayerState()
            playerState.dispose()

            val error = assertFailsWith<IllegalStateException> { playerState.enterPip() }
            assertEquals("VideoPlayerState has been disposed", error.message)
        }

    @Test
    fun testExternalSubtitleTracksSurviveSourceChangesAndRelease() {
        val playerState = createVideoPlayerState()
        val externalTrack = SubtitleTrack(label = "External", language = "en", src = "external.vtt")
        try {
            playerState.addSubtitleTrack(externalTrack)
            playerState.selectSubtitleTrack(externalTrack)

            playerState.openUri("https://example.test/first.mp4")
            assertTrue(playerState.availableSubtitleTracks.any { it.id == externalTrack.id && it.isExternal })
            assertEquals(externalTrack.id, playerState.currentSubtitleTrack?.id)

            playerState.releaseSource()
            assertTrue(playerState.availableSubtitleTracks.any { it.id == externalTrack.id && it.isExternal })
            assertEquals(externalTrack.id, playerState.currentSubtitleTrack?.id)

            playerState.openUri("https://example.test/second.mp4")
            assertTrue(playerState.availableSubtitleTracks.any { it.id == externalTrack.id && it.isExternal })
        } finally {
            playerState.dispose()
        }
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
        val adaptiveStreamingSupported = configuredWebTestPlaybackEngine() == WebPlaybackEngine.MOVI

        assertFalse(playerState.canPlaySource(""))
        assertTrue(playerState.canPlaySource("blob:https://example.test/video"))
        assertTrue(playerState.canPlaySource("data:video/mp4;base64,AAA"))
        assertEquals(
            adaptiveStreamingSupported,
            playerState.canPlaySource("https://example.test/playlist.m3u8"),
        )
        assertEquals(
            adaptiveStreamingSupported,
            playerState.canPlaySource("https://example.test/manifest.mpd"),
        )
        assertEquals(
            adaptiveStreamingSupported,
            playerState.canPlaySource("https://example.test/channel.ism/Manifest"),
        )
        assertEquals(
            adaptiveStreamingSupported,
            playerState.canPlaySource(
                uri = "https://example.test/opaque-stream",
                mimeType = "application/dash+xml",
            ),
        )
        assertFalse(playerState.canPlaySource("file:///movie.mp4"))
        assertFalse(playerState.canPlaySource("file:///movie.mkv"))
        assertFalse(playerState.canPlaySource("""C:\Videos\movie.mp4"""))
        assertFalse(playerState.canPlaySource("""\\server\share\movie.mp4"""))

        playerState.dispose()
    }

    @Test
    fun testExternalMediaDependenciesUsePinnedDefaultsAndCanBeOverridden() =
        runTest {
            assertTrue(WebMediaDependencyConfig.moviPlayerModuleUrl.contains("Shusek/movi-player@v0.3.5-kmp.2/"))
            assertTrue(WebMediaDependencyConfig.moviPlayerModuleUrl.endsWith("/cdn/engine.js"))
            assertTrue(WebMediaDependencyConfig.matroskaSubtitlesScriptUrl.contains("@3.3.2/"))
            assertTrue(WebMediaDependencyConfig.matroskaSubtitlesScriptIntegrity.startsWith("sha384-"))

            val previousMoviUrl = WebMediaDependencyConfig.moviPlayerModuleUrl
            val previousUrl = WebMediaDependencyConfig.matroskaSubtitlesScriptUrl
            try {
                WebMediaDependencyConfig.moviPlayerModuleUrl = "https://cdn.example.test/movi-player.js"
                WebMediaDependencyConfig.matroskaSubtitlesScriptUrl = ""
                assertEquals(
                    "https://cdn.example.test/movi-player.js",
                    WebMediaDependencyConfig.moviPlayerModuleUrl,
                )
                assertFalse(ensureMatroskaSubtitlesModuleLoaded())
            } finally {
                WebMediaDependencyConfig.moviPlayerModuleUrl = previousMoviUrl
                WebMediaDependencyConfig.matroskaSubtitlesScriptUrl = previousUrl
            }
        }

    @Test
    fun testVideoElementCleanupDetachesManagedListenersAndSource() {
        val video = createVideoElement()
        var eventCalls = 0
        video.addManagedEventListener("play") { eventCalls += 1 }
        video.setAttribute("src", "https://example.test/video.mp4")

        video.cleanupWebVideoElement()
        video.dispatchEvent(Event("play"))

        assertEquals(0, eventCalls)
        assertFalse(video.hasAttribute("src"))
    }

    @Test
    fun testVideoCallbacksIgnorePreviousSessionWhenSameUriIsReopened() =
        runTest {
            val playerState = createVideoPlayerState() as DefaultVideoPlayerState
            val previousVideo = createVideoElement(useCors = false)
            val currentVideo = createVideoElement(useCors = false)
            val events = collectEvents(playerState)
            val uri = "https://example.test/video.mp4"

            try {
                playerState.openUri(uri)
                previousVideo.src = uri
                previousVideo.markMediaSession(playerState.mediaSessionId, uri)
                setupVideoElement(
                    video = previousVideo,
                    playerState = playerState,
                    scope = this,
                    useCors = false,
                )
                events.clear()

                playerState.openUri(uri)
                val reopenedSessionId = playerState.mediaSessionId
                previousVideo.markMediaSession(reopenedSessionId, uri)
                previousVideo.dispatchEvent(Event("waiting"))
                runCurrent()

                assertTrue(events.none { it is PlaybackEvent.Stalled })

                currentVideo.src = uri
                currentVideo.markMediaSession(reopenedSessionId, uri)
                setupVideoElement(
                    video = currentVideo,
                    playerState = playerState,
                    scope = this,
                    useCors = false,
                )
                currentVideo.dispatchEvent(Event("waiting"))
                runCurrent()

                assertEquals(
                    listOf(reopenedSessionId),
                    events.filterIsInstance<PlaybackEvent.Stalled>().map(PlaybackEvent::mediaSessionId),
                )
            } finally {
                previousVideo.cleanupWebVideoElement()
                currentVideo.cleanupWebVideoElement()
                playerState.dispose()
            }
        }

    @Test
    fun testVideoElementCorsModeFollowsSourceAttempt() {
        val video = createVideoElement(useCors = true)

        assertEquals("anonymous", video.getAttribute("crossorigin"))

        video.configureCrossOrigin(useCors = true, useCredentials = true)
        assertEquals("use-credentials", video.getAttribute("crossorigin"))

        video.configureCrossOrigin(useCors = false, useCredentials = false)
        assertFalse(video.hasAttribute("crossorigin"))
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

@Suppress("UNUSED_PARAMETER")
private fun configuredWebTestPlaybackEngine(): WebPlaybackEngine =
    when (val configured = readConfiguredWebTestPlaybackEngine().lowercase()) {
        "movi" -> WebPlaybackEngine.MOVI
        "legacy" -> WebPlaybackEngine.LEGACY
        else -> error("Missing or invalid Karma playback engine: '$configured'.")
    }

@OptIn(ExperimentalWasmJsInterop::class)
private fun readConfiguredWebTestPlaybackEngine(): String =
    js("String(globalThis.__karma__?.config?.kmpPlaybackEngine || '')")

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
