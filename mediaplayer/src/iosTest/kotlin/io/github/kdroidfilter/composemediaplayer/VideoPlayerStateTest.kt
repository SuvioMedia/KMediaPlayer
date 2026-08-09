package io.github.kdroidfilter.composemediaplayer

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import io.github.kdroidfilter.composemediaplayer.util.PipResult
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Tests for the iOS implementation of VideoPlayerState
 */
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
    @Suppress("MagicNumber")
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

        // Test initial fullscreen state
        assertFalse(playerState.isFullscreen)

        // Test toggling fullscreen
        playerState.toggleFullscreen()
        assertTrue(playerState.isFullscreen)

        playerState.toggleFullscreen()
        assertFalse(playerState.isFullscreen)

        // Clean up
        playerState.dispose()
    }

    /**
     * Test error handling
     * Note: Error handling in iOS implementation is minimal
     */
    @Test
    fun testErrorHandling() {
        val playerState = createVideoPlayerState()

        // Test opening a non-existent file
        playerState.openUri("non_existent_file.mp4")

        // Test clearing the error
        playerState.clearError()

        // Clean up
        playerState.dispose()
    }

    /**
     * Test subtitle functionality
     */
    @Test
    fun testSubtitleFunctionality() {
        val playerState = createVideoPlayerState()

        // Verify initial subtitle state
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

        // Verify subtitle state after selecting a track
        assertTrue(playerState.subtitlesEnabled)
        assertEquals(testTrack, playerState.currentSubtitleTrack)

        // Disable subtitles
        playerState.disableSubtitles()

        // Verify subtitle state after disabling subtitles
        assertFalse(playerState.subtitlesEnabled)
        assertEquals(null, playerState.currentSubtitleTrack)

        // Clean up
        playerState.dispose()
    }

    @Test
    fun releaseSourceDetachesPlayerItemAndKeepsStateReusable() {
        val playerState = DefaultVideoPlayerState()
        val metadata = playerState.metadata

        playerState.openUri(
            uri = "https://example.com/video.mp4",
            initializePlayerState = InitialPlayerState.PAUSE,
        )
        assertNotNull(playerState.player)

        playerState.releaseSource()

        assertNull(playerState.player)
        assertFalse(playerState.hasMedia)
        assertSame(metadata, playerState.metadata, "Metadata holder must remain observable across source changes")

        playerState.openUri(
            uri = "https://example.com/other.mp4",
            initializePlayerState = InitialPlayerState.PAUSE,
        )
        assertNotNull(playerState.player)
        playerState.dispose()
    }

    @Test
    @Suppress("LongMethod", "MagicNumber")
    fun disposeIsIdempotentAndRejectsFurtherCommands() {
        val playerState = createVideoPlayerState()
        val audioTrack = AudioTrack(id = "audio", label = "Audio")
        val subtitleTrack = SubtitleTrack(label = "Subtitle", language = "en", src = "subtitle.vtt")

        playerState.dispose()
        playerState.dispose()

        listOf<() -> Unit>(
            { playerState.play() },
            { playerState.pause() },
            { playerState.stop() },
            { playerState.restart() },
            { playerState.releaseSource() },
            { playerState.openUri("https://example.com/video.mp4") },
            { playerState.prepare("https://example.com/video.mp4") },
            { playerState.openAsset("video.mp4") },
            { playerState.seekTo(kotlin.time.Duration.ZERO) },
            { playerState.seekToMs(1_000L) },
            { playerState.seekBy(kotlin.time.Duration.ZERO) },
            { playerState.seekByMs(1_000L) },
            { playerState.seekToProgress(0.5f) },
            { playerState.seekStart(500f) },
            { playerState.seekFinished() },
            { playerState.clearError() },
            { playerState.clearCache() },
            { playerState.toggleFullscreen() },
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
            { playerState.projection = VideoProjectionSettings() },
            { playerState.projectionView = VideoProjectionViewSettings(yawDegrees = 10f) },
            { playerState.projectionViewControlMode = VideoProjectionViewControlMode.MANUAL },
            { playerState.projectionTextureCrop = VideoTextureCrop(left = 0.1f) },
            { playerState.currentAudioTrack = audioTrack },
            { playerState.selectAudioTrack(audioTrack) },
            { playerState.selectAudioTrack("missing") },
            { playerState.subtitlesEnabled = true },
            { playerState.currentSubtitleTrack = subtitleTrack },
            { playerState.subtitleTextStyle = TextStyle.Default },
            { playerState.subtitleBackgroundColor = Color.Transparent },
            { playerState.subtitleOffset = kotlin.time.Duration.ZERO },
            { playerState.selectSubtitleTrack(subtitleTrack) },
            { playerState.selectSubtitleTrack("missing") },
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
    fun enterPipWithoutAnOwnedPlayerLayerIsNotPossible() =
        runTest {
            val playerState = createVideoPlayerState()
            try {
                playerState.isPipEnabled = true
                assertEquals(PipResult.NotPossible, playerState.enterPip())
                assertFalse(playerState.isPipActive)
            } finally {
                playerState.dispose()
            }
        }

    @Test
    fun enterPipRejectsDisposedState() =
        runTest {
            val playerState = createVideoPlayerState()
            playerState.dispose()

            val error = assertFailsWith<IllegalStateException> { playerState.enterPip() }
            assertEquals("VideoPlayerState has been disposed", error.message)
        }

    @Test
    fun fullscreenSurfaceOwnershipFallsBackWithoutClearingAnotherLayer() {
        val ownership = IosSurfaceOwnership<Any>()
        val baseLayer = Any()
        val fullscreenLayer = Any()

        ownership.bind(baseLayer, isFullscreen = false)
        assertSame(baseLayer, ownership.activeOwner)

        ownership.bind(fullscreenLayer, isFullscreen = true)
        ownership.bind(baseLayer, isFullscreen = false)
        assertSame(fullscreenLayer, ownership.activeOwner)

        ownership.release(baseLayer)
        assertSame(fullscreenLayer, ownership.activeOwner)

        ownership.bind(baseLayer, isFullscreen = false)
        ownership.release(fullscreenLayer)
        assertSame(baseLayer, ownership.activeOwner)
    }

    @Test
    fun audioSessionLeasesAreReferenceCountedAtomically() {
        val originalPolicy = IosAudioSessionPolicy.automaticManagementEnabled
        val initialLeaseCount = IosAudioSessionManager.activeLeaseCount
        var firstLease: Long? = null
        var secondLease: Long? = null
        try {
            IosAudioSessionPolicy.automaticManagementEnabled = true
            firstLease = IosAudioSessionManager.acquire(null, AudioMode())
            secondLease = IosAudioSessionManager.acquire(null, AudioMode(InterruptionMode.DuckOthers))

            assertNotNull(firstLease)
            assertNotNull(secondLease)
            assertTrue(firstLease != secondLease)
            assertEquals(initialLeaseCount + 2, IosAudioSessionManager.activeLeaseCount)

            IosAudioSessionManager.release(firstLease)
            firstLease = null
            assertEquals(initialLeaseCount + 1, IosAudioSessionManager.activeLeaseCount)
        } finally {
            IosAudioSessionManager.release(firstLease)
            IosAudioSessionManager.release(secondLease)
            IosAudioSessionPolicy.automaticManagementEnabled = originalPolicy
        }
        assertEquals(initialLeaseCount, IosAudioSessionManager.activeLeaseCount)
    }

    @Test
    fun iosCacheDoesNotClearTheApplicationsSharedUrlCache() {
        val playerState = createVideoPlayerState(cacheConfig = CacheConfig(enabled = true))

        assertIs<CacheClearResult.NotSupported>(playerState.clearCache())
        playerState.dispose()
    }
}
