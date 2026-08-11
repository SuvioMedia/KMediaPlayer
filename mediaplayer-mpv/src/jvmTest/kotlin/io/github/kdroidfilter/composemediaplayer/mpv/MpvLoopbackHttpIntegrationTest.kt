@file:OptIn(io.github.kdroidfilter.composemediaplayer.ExperimentalComposeMediaPlayerBackendApi::class)

package io.github.kdroidfilter.composemediaplayer.mpv

import io.github.kdroidfilter.composemediaplayer.InitialPlayerState
import io.github.kdroidfilter.composemediaplayer.MpvPlaybackOptions
import io.github.kdroidfilter.composemediaplayer.MpvRuntimeSource
import io.github.kdroidfilter.composemediaplayer.createMpvVideoPlayerState
import java.net.URI
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class MpvLoopbackHttpIntegrationTest {
    @Test
    fun opensConfiguredDirectHttpSourceWithStructuredHeaders() {
        val source =
            System.getProperty(TEST_SOURCE_PROPERTY)?.takeIf(String::isNotBlank)
                ?: System.getProperty(LEGACY_TEST_SOURCE_PROPERTY)?.takeIf(String::isNotBlank)
                ?: return
        require(source.isDirectHttpUri()) {
            "$TEST_SOURCE_PROPERTY accepts a direct HTTP/HTTPS URI without user information."
        }
        val playbackOptions =
            System
                .getProperty(MPV_LIBRARY_PROPERTY)
                ?.takeIf(String::isNotBlank)
                ?.let(Path::of)
                ?.let { library ->
                    MpvPlaybackOptions(runtimeSource = MpvRuntimeSource.ExplicitPath(library.toString()))
                } ?: MpvPlaybackOptions()
        val player = assertIs<MpvVideoPlayerState>(createMpvVideoPlayerState(playbackOptions))

        try {
            player.openUri(
                source,
                InitialPlayerState.PAUSE,
                mapOf(
                    "User-Agent" to "KMediaPlayer-Integration-Test",
                    "X-KMedia-Test" to "direct,https",
                ),
            )
            val deadline = System.nanoTime() + TEST_TIMEOUT_NANOS
            while (System.nanoTime() < deadline && (player.isLoading || !player.hasMedia) && player.error == null) {
                Thread.sleep(POLL_INTERVAL_MILLIS)
            }

            assertEquals(null, player.error, "MPV rejected the direct HTTP(S) source.")
            assertTrue(
                player.hasMedia && !player.isLoading,
                "MPV did not finish opening direct HTTP(S) media " +
                    "(hasMedia=${player.hasMedia}, loading=${player.isLoading}).",
            )
            if (System.getProperty("os.name", "").contains("mac", ignoreCase = true)) {
                assertContains(player.renderingInfo.videoRenderer.orEmpty(), "macvk")
            }
        } finally {
            player.dispose()
        }
    }

    private fun String.isDirectHttpUri(): Boolean {
        val uri = runCatching { URI.create(this) }.getOrNull() ?: return false
        return uri.scheme?.lowercase() in setOf("http", "https") &&
            uri.userInfo == null &&
            !uri.host.isNullOrBlank() &&
            uri.port != 0 &&
            uri.port <= 65_535
    }

    private companion object {
        const val TEST_SOURCE_PROPERTY = "composemediaplayer.test.directHttpSource"
        const val LEGACY_TEST_SOURCE_PROPERTY = "composemediaplayer.test.loopbackHttpSource"
        const val MPV_LIBRARY_PROPERTY = "composemediaplayer.mpvLibraryPath"
        const val POLL_INTERVAL_MILLIS = 20L
        const val TEST_TIMEOUT_NANOS = 30_000_000_000L
    }
}
