package io.github.kdroidfilter.composemediaplayer

import io.github.kdroidfilter.composemediaplayer.desktop.DesktopPlaybackRequest
import io.github.kdroidfilter.composemediaplayer.desktop.DesktopPlaybackSession
import io.github.kdroidfilter.composemediaplayer.util.CurrentPlatform
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assume
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class WindowsPlatformLocalPlaybackIntegrationTest {
    @Test
    fun explicitPlatformSessionOpensLocalPath() =
        runBlocking {
            val fixture = windowsPlaybackFixture()
            val backend = platformDesktopPlaybackBackend()
            val session = DesktopPlaybackSession(listOf(backend))
            try {
                val player =
                    session.open(
                        request = DesktopPlaybackRequest(MediaSourceSpec(fixture.toString())),
                        backendId = "platform",
                    )

                assertTrue(player.hasMedia, "The platform session did not retain the local media source.")
            } finally {
                session.close()
            }
        }

    @Test
    fun platformStateOpensLocalFileUri() =
        runBlocking {
            val fixture = windowsPlaybackFixture()
            val player = platformDesktopPlaybackBackend().createPlayerState()
            try {
                player.openUri(fixture.toUri().toString(), InitialPlayerState.PAUSE)
                withTimeout(15_000.milliseconds) {
                    while (!player.hasMedia && player.error == null) delay(25.milliseconds)
                }

                assertTrue(
                    player.hasMedia,
                    "The platform player rejected the local file URI: ${player.error?.let { it::class.simpleName }}",
                )
                withTimeout(15_000.milliseconds) {
                    while (
                        player.colorPipelineStatus.value.source.dynamicRange != VideoDynamicRange.SDR ||
                        player.colorPipelineStatus.value.verification != ColorPipelineVerification.RENDERER_CONFIGURED
                    ) {
                        check(
                            player.error == null,
                        ) { "Playback failed before color output was verified: ${player.error}" }
                        delay(25.milliseconds)
                    }
                }
                assertEquals(VideoDynamicRange.SDR, player.colorPipelineStatus.value.outputDynamicRange)
            } finally {
                player.dispose()
            }
        }

    @Test
    fun wmaProPlaybackSelectsAudioAndAdvances() =
        runBlocking {
            val fixture = windowsPlaybackFixture(WMA_PRO_FIXTURE_PROPERTY, "WMA Pro/WMV")
            val player = platformDesktopPlaybackBackend().createPlayerState()
            try {
                player.openUri(fixture.toUri().toString(), InitialPlayerState.PLAY)
                withTimeout(20_000.milliseconds) {
                    while (!player.hasMedia && player.error == null) delay(25.milliseconds)
                }
                assertTrue(player.hasMedia, "The WMA Pro fixture was rejected: ${player.error}")
                val playbackConfirmed =
                    withTimeoutOrNull(20_000.milliseconds) {
                        while (
                            player.currentTime < 1.seconds ||
                            player.currentAudioTrack == null ||
                            player.diagnostics.maximumAvSyncOffsetMs == null
                        ) {
                            check(player.error == null) { "WMA Pro playback failed: ${player.error}" }
                            delay(50.milliseconds)
                        }
                        true
                    }
                assertTrue(
                    playbackConfirmed == true,
                    "WMA Pro did not fully start: time=${player.currentTime}, playing=${player.isPlaying}, " +
                        "audioTrack=${player.currentAudioTrack}, audioTracks=${player.availableAudioTracks}, " +
                        "metadata=${player.metadata}, diagnostics=${player.diagnostics}, error=${player.error}",
                )
                assertNotNull(player.currentAudioTrack)
                assertTrue(player.isPlaying)
                assertTrue(player.currentTime >= 1.seconds)
            } finally {
                player.dispose()
            }
        }

    @Test
    fun platformControlsPauseSeekStopAndReopen() =
        runBlocking {
            val fixture = windowsPlaybackFixture()
            val player = platformDesktopPlaybackBackend().createPlayerState()
            try {
                player.openUri(fixture.toUri().toString(), InitialPlayerState.PLAY)
                awaitPlayingFrame(player)

                player.pause()
                withTimeout(10_000.milliseconds) {
                    while (player.isPlaying) delay(25.milliseconds)
                }
                val pausedAt = player.currentTime
                delay(350.milliseconds)
                assertTrue(
                    player.currentTime <= pausedAt + 500.milliseconds,
                    "Playback advanced unexpectedly while paused: $pausedAt -> ${player.currentTime}",
                )

                player.seekTo(2.seconds)
                withTimeout(10_000.milliseconds) {
                    while (player.currentTime < 1.seconds) {
                        check(player.error == null) { "Seek failed: ${player.error}" }
                        delay(25.milliseconds)
                    }
                }

                player.play()
                withTimeout(10_000.milliseconds) {
                    while (!player.isPlaying || player.currentTime <= 2.seconds) {
                        check(player.error == null) { "Resume failed: ${player.error}" }
                        delay(25.milliseconds)
                    }
                }

                player.stop()
                withTimeout(10_000.milliseconds) {
                    while (
                        player.hasMedia ||
                        player.isPlaying ||
                        player.currentTime != kotlin.time.Duration.ZERO
                    ) {
                        delay(25.milliseconds)
                    }
                }
                assertEquals(kotlin.time.Duration.ZERO, player.currentTime)

                player.play()
                awaitPlayingFrame(player)
            } finally {
                player.dispose()
            }
        }

    @Test
    fun platformPlaybackStaysInsideAudioVideoSyncBudget() =
        runBlocking {
            val fixture = windowsPlaybackFixture()
            val player = platformDesktopPlaybackBackend().createPlayerState()
            try {
                player.openUri(fixture.toUri().toString(), InitialPlayerState.PLAY)
                awaitPlayingFrame(player)
                delay(8.seconds)

                val maximumOffsetMs = assertNotNull(player.diagnostics.maximumAvSyncOffsetMs)
                assertTrue(
                    maximumOffsetMs <= MAXIMUM_ACCEPTABLE_AV_SYNC_OFFSET_MS,
                    "Maximum A/V offset $maximumOffsetMs ms exceeds " +
                        "$MAXIMUM_ACCEPTABLE_AV_SYNC_OFFSET_MS ms: ${player.diagnostics}",
                )
            } finally {
                player.dispose()
            }
        }

    @Test
    fun platformPlayRestartsAfterPlaybackEnds() =
        runBlocking {
            val fixture = windowsPlaybackFixture()
            val player = platformDesktopPlaybackBackend().createPlayerState()
            try {
                player.openUri(fixture.toUri().toString(), InitialPlayerState.PLAY)
                awaitPlayingFrame(player)
                val nearEnd = (player.duration - 2.seconds).coerceAtLeast(Duration.ZERO)
                player.seekTo(nearEnd)
                withTimeout(15_000.milliseconds) {
                    while (player.isPlaying || player.currentTime < player.duration) {
                        check(player.error == null) { "Playback did not reach EOF: ${player.error}" }
                        delay(25.milliseconds)
                    }
                }
                val renderedAtEnd = player.diagnostics.renderedVideoFrames ?: 0L

                player.play()
                withTimeout(15_000.milliseconds) {
                    while (
                        !player.isPlaying ||
                        player.currentTime <= Duration.ZERO ||
                        player.currentTime >= player.duration ||
                        (player.diagnostics.renderedVideoFrames ?: 0L) <= renderedAtEnd
                    ) {
                        check(player.error == null) { "Replay after EOF failed: ${player.error}" }
                        delay(25.milliseconds)
                    }
                }
            } finally {
                player.dispose()
            }
        }

    @Test
    fun remoteMp4StreamsAndAdvances() =
        runBlocking {
            val uri = windowsRemoteFixture(REMOTE_MP4_FIXTURE_PROPERTY, "remote MP4")
            val player = platformDesktopPlaybackBackend().createPlayerState()
            try {
                player.openUri(uri, InitialPlayerState.PLAY)
                awaitPlayingFrame(player, 45_000)
            } finally {
                player.dispose()
            }
        }

    @Test
    fun hlsStreamRendersAndAdvances() =
        runBlocking {
            val uri = windowsRemoteFixture(HLS_FIXTURE_PROPERTY, "HLS")
            val player = platformDesktopPlaybackBackend().createPlayerState()
            try {
                player.openUri(uri, InitialPlayerState.PLAY)
                awaitPlayingFrame(player, 60_000)
            } finally {
                player.dispose()
            }
        }

    private suspend fun awaitPlayingFrame(
        player: VideoPlayerState,
        timeoutMs: Long = 20_000,
    ) {
        withTimeout(timeoutMs.milliseconds) {
            while (
                !player.hasMedia ||
                !player.isPlaying ||
                player.currentTime < 1.seconds ||
                (player.diagnostics.renderedVideoFrames ?: 0L) <= 0L
            ) {
                check(player.error == null) { "Playback failed: ${player.error}" }
                delay(25.milliseconds)
            }
        }
    }

    private fun windowsPlaybackFixture(
        property: String = FIXTURE_PROPERTY,
        description: String = "MP4",
    ): Path {
        Assume.assumeTrue(
            "Windows playback integration test",
            CurrentPlatform.os == CurrentPlatform.OS.WINDOWS,
        )
        val configured = System.getProperty(property)
        Assume.assumeTrue("Set -D$property to a local $description fixture", !configured.isNullOrBlank())
        val fixture = Path.of(configured).toAbsolutePath().normalize()
        Assume.assumeTrue("The configured $description fixture does not exist", Files.isRegularFile(fixture))
        return fixture
    }

    private fun windowsRemoteFixture(
        property: String,
        description: String,
    ): String {
        Assume.assumeTrue(
            "Windows playback integration test",
            CurrentPlatform.os == CurrentPlatform.OS.WINDOWS,
        )
        val configured = System.getProperty(property)
        Assume.assumeTrue("Set -D$property to a $description fixture", !configured.isNullOrBlank())
        return configured
    }

    private companion object {
        const val FIXTURE_PROPERTY = "kmediaplayer.windowsPlaybackFixture"
        const val WMA_PRO_FIXTURE_PROPERTY = "kmediaplayer.windowsWmaProFixture"
        const val REMOTE_MP4_FIXTURE_PROPERTY = "kmediaplayer.windowsRemoteMp4Fixture"
        const val HLS_FIXTURE_PROPERTY = "kmediaplayer.windowsHlsFixture"
        const val MAXIMUM_ACCEPTABLE_AV_SYNC_OFFSET_MS = 45.0f
    }
}
