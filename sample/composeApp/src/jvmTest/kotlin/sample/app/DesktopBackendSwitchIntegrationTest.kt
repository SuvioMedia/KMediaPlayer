@file:OptIn(io.github.kdroidfilter.composemediaplayer.ExperimentalComposeMediaPlayerBackendApi::class)

package sample.app

import io.github.kdroidfilter.composemediaplayer.LibVlcPlaybackOptions
import io.github.kdroidfilter.composemediaplayer.MediaSourceSpec
import io.github.kdroidfilter.composemediaplayer.VideoPlaybackOptions
import io.github.kdroidfilter.composemediaplayer.VideoPlayerState
import io.github.kdroidfilter.composemediaplayer.adaptedPlatformDesktopPlaybackBackend
import io.github.kdroidfilter.composemediaplayer.desktop.DesktopBackendAvailability
import io.github.kdroidfilter.composemediaplayer.desktop.DesktopPlaybackRequest
import io.github.kdroidfilter.composemediaplayer.desktop.DesktopPlaybackSession
import io.github.kdroidfilter.composemediaplayer.libVlcDesktopPlaybackBackend
import io.github.kdroidfilter.composemediaplayer.mpvDesktopPlaybackBackend
import kotlinx.coroutines.runBlocking
import sample.app.player.desktopPipelineExtensions
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertNotSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class DesktopBackendSwitchIntegrationTest {
    @Test
    fun switchesWmaProTransactionallyAcrossSampleBackends() =
        runBlocking {
            if (!isMacArm64()) return@runBlocking
            val media = configuredWmaProMedia() ?: return@runBlocking
            val playbackOptions = VideoPlaybackOptions(extensions = desktopPipelineExtensions)
            val mpv = mpvDesktopPlaybackBackend()
            val libVlc = libVlcDesktopPlaybackBackend(LibVlcPlaybackOptions())
            val adaptedPlatform = adaptedPlatformDesktopPlaybackBackend(playbackOptions = playbackOptions)
            val session =
                DesktopPlaybackSession(
                    backends = listOf(mpv, libVlc, adaptedPlatform),
                    readyTimeout = 45.seconds,
                )

            try {
                val firstMpv =
                    session.open(
                        request = DesktopPlaybackRequest(MediaSourceSpec(media.toUri().toString())),
                        backendId = "mpv",
                    )
                awaitPlayback(firstMpv, "MPV")
                assertContains(firstMpv.renderingInfo.audioRenderer.orEmpty().lowercase(), "wmapro via coreaudio")
                session.notifySurfaceAttached(firstMpv)

                if (libVlc.inspectAvailability() is DesktopBackendAvailability.Available) {
                    val vlc = session.switchBackend("libvlc4-texture")
                    assertNotSame(firstMpv, vlc)
                    awaitPlayback(vlc, "libVLC")
                    assertTrue(vlc.currentAudioTrack != null, "libVLC did not keep an active WMA Pro audio track.")
                    session.notifySurfaceAttached(vlc)

                    val secondMpv = session.switchBackend("mpv")
                    assertNotSame(vlc, secondMpv)
                    awaitPlayback(secondMpv, "MPV after libVLC")
                    assertContains(
                        secondMpv.renderingInfo.audioRenderer.orEmpty().lowercase(),
                        "wmapro via coreaudio",
                    )
                    session.notifySurfaceAttached(secondMpv)
                }

                val platform = session.switchBackend("platform-adapted")
                awaitPlayback(platform, "AVFoundation with KMediaBridge")
                assertTrue(
                    platform.renderingInfo.backend.orEmpty().contains("media bridge", ignoreCase = true),
                    "The platform route did not report the configured KMediaBridge adapter.",
                )
                assertTrue(platform.currentAudioTrack != null, "The adapted platform route exposed no audio track.")
                session.notifySurfaceAttached(platform)
            } finally {
                session.close()
            }
        }

    private fun configuredWmaProMedia(): Path? =
        System
            .getProperty(WMA_PRO_MEDIA_PROPERTY)
            ?.takeIf(String::isNotBlank)
            ?.let(Path::of)
            ?.takeIf(Files::isRegularFile)

    private fun awaitPlayback(
        player: VideoPlayerState,
        backend: String,
    ) {
        val deadline = System.nanoTime() + PLAYBACK_TIMEOUT.inWholeNanoseconds
        while (System.nanoTime() < deadline) {
            if (
                player.hasMedia &&
                player.error == null &&
                !player.isLoading &&
                player.currentTime >= MINIMUM_PROGRESS
            ) {
                return
            }
            Thread.sleep(POLL_INTERVAL_MILLIS)
        }
        assertTrue(
            player.hasMedia && player.error == null && player.currentTime >= MINIMUM_PROGRESS,
            "$backend did not progress after the transactional switch: ${player.error}",
        )
    }

    private fun isMacArm64(): Boolean {
        val os = System.getProperty("os.name", "").lowercase()
        val architecture = System.getProperty("os.arch", "").lowercase()
        return (os.contains("mac") || os.contains("darwin")) && architecture in setOf("aarch64", "arm64")
    }

    private companion object {
        const val WMA_PRO_MEDIA_PROPERTY = "composemediaplayer.test.wmaProMedia"
        const val POLL_INTERVAL_MILLIS = 50L
        val MINIMUM_PROGRESS = 250.milliseconds
        val PLAYBACK_TIMEOUT = 20.seconds
    }
}
