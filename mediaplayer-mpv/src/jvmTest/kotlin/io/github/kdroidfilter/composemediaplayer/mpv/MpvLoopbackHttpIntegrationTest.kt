@file:OptIn(io.github.kdroidfilter.composemediaplayer.ExperimentalComposeMediaPlayerBackendApi::class)

package io.github.kdroidfilter.composemediaplayer.mpv

import io.github.kdroidfilter.composemediaplayer.InitialPlayerState
import io.github.kdroidfilter.composemediaplayer.MpvPlaybackOptions
import io.github.kdroidfilter.composemediaplayer.MpvRuntimeSource
import io.github.kdroidfilter.composemediaplayer.createMpvVideoPlayerState
import java.net.URI
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class MpvLoopbackHttpIntegrationTest {
    @Test
    fun opensConfiguredProgressiveSourceWithoutAnIntermediateProxy() {
        val source = System.getProperty(TEST_SOURCE_PROPERTY)?.takeIf(String::isNotBlank) ?: return
        require(source.isNumericLoopbackHttpUri()) {
            "$TEST_SOURCE_PROPERTY accepts an uncredentialed numeric-loopback HTTP URI only."
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
            player.openUri(source, InitialPlayerState.PAUSE)
            val deadline = System.nanoTime() + TEST_TIMEOUT_NANOS
            while (System.nanoTime() < deadline && (player.isLoading || !player.hasMedia) && player.error == null) {
                Thread.sleep(POLL_INTERVAL_MILLIS)
            }

            assertEquals(null, player.error, "Bundled MPV rejected the loopback HTTP source.")
            assertTrue(
                player.hasMedia && !player.isLoading,
                "Bundled MPV did not finish opening loopback HTTP media " +
                    "(hasMedia=${player.hasMedia}, loading=${player.isLoading}).",
            )
        } finally {
            player.dispose()
        }
    }

    private fun String.isNumericLoopbackHttpUri(): Boolean {
        val uri = runCatching { URI.create(this) }.getOrNull() ?: return false
        return uri.scheme.equals("http", ignoreCase = true) &&
            uri.userInfo == null &&
            uri.port in 1..65535 &&
            uri.host?.removeSurrounding("[", "]") in setOf("127.0.0.1", "::1")
    }

    private companion object {
        const val TEST_SOURCE_PROPERTY = "composemediaplayer.test.loopbackHttpSource"
        const val MPV_LIBRARY_PROPERTY = "composemediaplayer.mpvLibraryPath"
        const val POLL_INTERVAL_MILLIS = 20L
        const val TEST_TIMEOUT_NANOS = 30_000_000_000L
    }
}
