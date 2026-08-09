package io.github.kdroidfilter.composemediaplayer

import android.content.Context
import android.media.AudioManager
import android.os.Looper
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.source.MediaSource
import com.kdroid.androidcontextprovider.ContextProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Tests for the Android implementation of VideoPlayerState
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@Suppress("MagicNumber", "TooGenericExceptionCaught")
class VideoPlayerStateTest {
    @Before
    fun setup() {
        ContextProvider.initialize(RuntimeEnvironment.getApplication())
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

    @Test
    fun releaseSourceDetachesMediaAndKeepsEngineReusable() {
        withPlayerState { playerState ->
            val androidState = playerState as DefaultVideoPlayerState
            playerState.openUri("https://example.invalid/first.mp4", InitialPlayerState.PAUSE)
            assertTrue(playerState.hasMedia)
            assertEquals(1, androidState.exoPlayer?.mediaItemCount)

            playerState.releaseSource()
            shadowOf(Looper.getMainLooper()).idle()

            assertFalse(playerState.hasMedia)
            assertEquals(0, androidState.exoPlayer?.mediaItemCount)
            playerState.play()
            assertFalse(playerState.hasMedia)

            playerState.openUri("https://example.invalid/second.mp4", InitialPlayerState.PAUSE)
            assertTrue(playerState.hasMedia)
            assertEquals(1, androidState.exoPlayer?.mediaItemCount)
        }
    }

    @Test
    fun stopRetainsSourceForLaterPlayback() {
        withPlayerState { playerState ->
            val androidState = playerState as DefaultVideoPlayerState
            playerState.openUri("https://example.invalid/video.mp4", InitialPlayerState.PAUSE)

            playerState.stop()

            assertTrue(playerState.hasMedia)
            assertEquals(1, androidState.exoPlayer?.mediaItemCount)
            playerState.play()
            assertTrue(playerState.hasMedia)
        }
    }

    @Test
    fun onlyReleaseSourceEmitsSourceReleased() {
        withPlayerState { playerState ->
            val events = mutableListOf<PlaybackEvent>()
            val collectingScope = CoroutineScope(Dispatchers.Unconfined)
            collectingScope.launch(start = CoroutineStart.UNDISPATCHED) {
                playerState.playbackEvents.collect(events::add)
            }
            try {
                playerState.openUri("https://example.invalid/video.mp4", InitialPlayerState.PAUSE)
                playerState.stop()
                assertTrue(events.none { it is PlaybackEvent.SourceReleased })

                playerState.releaseSource()
                assertEquals(1, events.count { it is PlaybackEvent.SourceReleased })
            } finally {
                collectingScope.cancel()
            }
        }
    }

    @Test
    fun metadataHolderIsStableAcrossReset() {
        withPlayerState { playerState ->
            val metadata = playerState.metadata
            metadata.title = "old title"

            playerState.stop()

            assertSame(metadata, playerState.metadata)
            assertNull(metadata.title)
        }
    }

    @Test
    @Suppress("LongMethod", "MagicNumber")
    fun disposeIsIdempotentAndCommandsFailAfterwards() {
        val playerState = createVideoPlayerState()
        val androidState = playerState as DefaultVideoPlayerState
        val audioTrack = AudioTrack(id = "audio", label = "Audio")
        val subtitleTrack = SubtitleTrack(label = "Subtitle", language = "en", src = "subtitle.vtt")
        playerState.dispose()
        playerState.dispose()

        listOf<() -> Unit>(
            { playerState.play() },
            { playerState.pause() },
            { playerState.stop() },
            { playerState.openUri("https://example.invalid/video.mp4") },
            { playerState.prepare("https://example.invalid/video.mp4") },
            { playerState.openAsset("video.mp4") },
            { playerState.releaseSource() },
            { playerState.restart() },
            { playerState.seekTo(1.seconds) },
            { playerState.seekToMs(1_000L) },
            { playerState.seekBy(1.seconds) },
            { playerState.seekByMs(1_000L) },
            { playerState.seekToProgress(0.5f) },
            { playerState.seekStart(0.5f) },
            { playerState.seekFinished() },
            { playerState.clearError() },
            { playerState.clearCache() },
            { playerState.toggleFullscreen() },
            { androidState.togglePipFullScreen() },
            { runBlocking { playerState.enterPip() } },
            { playerState.volume = 0.5f },
            { playerState.sliderPos = 500f },
            { playerState.userDragging = true },
            { playerState.loop = true },
            { playerState.playbackSpeed = 1.5f },
            { playerState.onPlaybackEnded = {} },
            { playerState.onRestart = {} },
            { playerState.isFullscreen = true },
            { playerState.isPipEnabled = true },
            { playerState.isPipActive = true },
            { androidState.isPipFullScreen = true },
            { playerState.projection = VideoProjectionSettings() },
            { playerState.projectionView = VideoProjectionViewSettings(yawDegrees = 10f) },
            { playerState.projectionViewControlMode = VideoProjectionViewControlMode.MANUAL },
            { playerState.projectionTextureCrop = VideoTextureCrop(left = 0.1f) },
            { playerState.currentAudioTrack = audioTrack },
            { playerState.selectAudioTrack("missing") },
            { playerState.selectAudioTrack(audioTrack) },
            {
                playerState.addExternalAudioTrack(
                    ExternalAudioTrack(
                        id = "external-audio",
                        label = "External audio",
                        source = MediaSourceSpec("https://example.invalid/audio.m4a"),
                    ),
                )
            },
            { playerState.removeExternalAudioTrack("external-audio") },
            { playerState.clearExternalAudioTracks() },
            { playerState.replaceExternalAudioTracks(emptyList()) },
            { playerState.subtitlesEnabled = true },
            { playerState.currentSubtitleTrack = subtitleTrack },
            { playerState.subtitleTextStyle = TextStyle.Default },
            { playerState.subtitleBackgroundColor = Color.Transparent },
            { playerState.subtitleOffset = 1.seconds },
            { playerState.selectSubtitleTrack("missing") },
            { playerState.selectSubtitleTrack(subtitleTrack) },
            { playerState.addSubtitleTrack(subtitleTrack) },
            { playerState.removeSubtitleTrack(subtitleTrack.id) },
            { playerState.removeSubtitleTrack(subtitleTrack) },
            { playerState.clearExternalSubtitleTracks() },
            { playerState.replaceExternalSubtitleTracks(listOf(subtitleTrack)) },
            { playerState.disableSubtitles() },
            { playerState.selectHlsQuality(null) },
            { playerState.selectAutoHlsQuality() },
        ).forEach { command ->
            val error = assertFailsWith<IllegalStateException> { command() }
            assertEquals("VideoPlayerState has been disposed", error.message)
        }
    }

    @Test
    fun externalAudioTracksAreBoundToTheCurrentMediaSource() {
        withPlayerState { playerState ->
            val track =
                ExternalAudioTrack(
                    id = "narration-pl",
                    label = "Polish narration",
                    source = MediaSourceSpec("https://example.invalid/narration.m4a", "audio/mp4"),
                    language = "pl",
                )
            playerState.openUri("https://example.invalid/first.mp4", InitialPlayerState.PAUSE)

            playerState.addExternalAudioTrack(track)

            assertTrue(playerState.capabilities.supportsExternalAudioTracks)
            assertEquals(listOf(track), playerState.externalAudioTracks)

            playerState.openUri("https://example.invalid/second.mp4", InitialPlayerState.PAUSE)

            assertTrue(playerState.externalAudioTracks.isEmpty())
        }
    }

    @Test
    fun disposeFromBackgroundThreadReleasesPlayerAndCacheLease() {
        val leasesBefore = VideoCache.activeLeaseCount
        val playerState = DefaultVideoPlayerState(cacheConfig = CacheConfig(enabled = true))
        val finished = CountDownLatch(1)
        var failure: Throwable? = null
        thread(name = "android-player-dispose-test") {
            try {
                playerState.dispose()
            } catch (throwable: Throwable) {
                failure = throwable
            } finally {
                finished.countDown()
            }
        }

        while (!finished.await(10, TimeUnit.MILLISECONDS)) {
            shadowOf(Looper.getMainLooper()).idle()
        }

        failure?.let { throw it }
        assertNull(playerState.exoPlayer)
        assertEquals(leasesBefore, VideoCache.activeLeaseCount)
    }

    @Test
    fun initializedContextDoesNotMaskUnrelatedIllegalStateException() {
        val error =
            assertFailsWith<IllegalStateException> {
                createAndroidVideoPlayerState {
                    error("real initialization failure")
                }
            }
        assertEquals("real initialization failure", error.message)
    }

    @Test
    fun partialInitializationFailureRunsCleanupBeforePropagating() {
        var playerInitialized = false
        var receiverRegistrationAttempted = false
        var cleanedUp = false

        val error =
            assertFailsWith<IllegalStateException> {
                initializeAndroidPlayerResources(
                    initializePlayer = { playerInitialized = true },
                    registerReceiver = {
                        receiverRegistrationAttempted = true
                        error("receiver registration failed")
                    },
                    cleanup = { cleanedUp = true },
                )
            }

        assertEquals("receiver registration failed", error.message)
        assertTrue(playerInitialized)
        assertTrue(receiverRegistrationAttempted)
        assertTrue(cleanedUp)
    }

    @Test
    fun screenResumeTicketIsInvalidatedByLaterPlaybackIntentOrSource() {
        val ticket = ScreenLockResumeTicket(sourceGeneration = 4L, playbackIntentGeneration = 7L)
        assertTrue(ticket.isCurrent(4L, 7L, hasMedia = true, isDisposed = false))
        assertFalse(ticket.isCurrent(4L, 8L, hasMedia = true, isDisposed = false))
        assertFalse(ticket.isCurrent(5L, 7L, hasMedia = true, isDisposed = false))
        assertFalse(ticket.isCurrent(4L, 7L, hasMedia = false, isDisposed = false))
        assertFalse(ticket.isCurrent(4L, 7L, hasMedia = true, isDisposed = true))
    }

    @Test
    fun audioModesMapToTheirAndroidFocusStrategies() {
        assertEquals(
            AndroidAudioFocusStrategy.EXCLUSIVE,
            AudioMode(InterruptionMode.DoNotMix).androidAudioFocusStrategy(),
        )
        assertEquals(
            AndroidAudioFocusStrategy.MIX,
            AudioMode(InterruptionMode.MixWithOthers).androidAudioFocusStrategy(),
        )
        assertEquals(
            AndroidAudioFocusStrategy.DUCK_OTHERS,
            AudioMode(InterruptionMode.DuckOthers).androidAudioFocusStrategy(),
        )
    }

    @Test
    fun duckOthersRequestsMayDuckFocusAndAbandonsItOnPause() {
        val context = ContextProvider.getContext()
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val shadowAudioManager = shadowOf(audioManager)
        val playerState = DefaultVideoPlayerState(audioMode = AudioMode(InterruptionMode.DuckOthers))
        try {
            playerState.openUri("https://example.invalid/video.mp4", InitialPlayerState.PLAY)
            val focusRequest = shadowAudioManager.lastAudioFocusRequest.audioFocusRequest
            assertEquals(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK, focusRequest.focusGain)

            playerState.pause()
            assertSame(focusRequest, shadowAudioManager.lastAbandonedAudioFocusRequest)
        } finally {
            playerState.dispose()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun recoveryRecreatesCustomSourceWithHeaders() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val playerState = RecordingAndroidVideoPlayerState()
        try {
            val headers = mapOf("Authorization" to "Bearer token", "X-Test" to "value")
            playerState.openUri(
                uri = "https://example.invalid/protected.mp4",
                initializePlayerState = InitialPlayerState.PAUSE,
                requestHeaders = headers,
            )
            assertEquals(listOf(headers), playerState.recordedHeaders)

            runBlocking { playerState.attemptPlayerRecovery(Duration.ZERO).join() }

            assertEquals(listOf(headers, headers), playerState.recordedHeaders)
            assertEquals(1, playerState.exoPlayer?.mediaItemCount)
        } finally {
            playerState.dispose()
            Dispatchers.resetMain()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun recoveryUsesCapturedPlaybackIntentInsteadOfDelayedIsPlayingState() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val playerState = RecordingAndroidVideoPlayerState()
        try {
            playerState.openUri(
                uri = "https://example.invalid/video.mp4",
                initializePlayerState = InitialPlayerState.PAUSE,
            )
            val failedPlayer = requireNotNull(playerState.exoPlayer)
            failedPlayer.playWhenReady = true

            runBlocking {
                playerState
                    .attemptPlayerRecovery(
                        delayBeforeRecovery = Duration.ZERO,
                        resumePlayback = failedPlayer.playWhenReady,
                    ).join()
            }

            assertTrue(playerState.exoPlayer?.playWhenReady == true)
        } finally {
            playerState.dispose()
            Dispatchers.resetMain()
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

private class RecordingAndroidVideoPlayerState : DefaultVideoPlayerState() {
    val recordedHeaders = mutableListOf<Map<String, String>>()

    override fun createMediaSource(
        mediaItem: MediaItem,
        requestHeaders: Map<String, String>,
    ): MediaSource? {
        recordedHeaders += requestHeaders
        return null
    }
}
