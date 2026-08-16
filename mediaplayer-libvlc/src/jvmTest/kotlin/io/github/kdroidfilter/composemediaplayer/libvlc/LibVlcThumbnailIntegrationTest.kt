@file:OptIn(io.github.kdroidfilter.composemediaplayer.ExperimentalComposeMediaPlayerBackendApi::class)

package io.github.kdroidfilter.composemediaplayer.libvlc

import io.github.kdroidfilter.composemediaplayer.InitialPlayerState
import io.github.kdroidfilter.composemediaplayer.JvmMediaThumbnail
import io.github.kdroidfilter.composemediaplayer.LibVlcBackendAvailability
import io.github.kdroidfilter.composemediaplayer.createLibVlcVideoPlayerState
import io.github.kdroidfilter.composemediaplayer.inspectLibVlcBackend
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class LibVlcThumbnailIntegrationTest {
    @Test
    fun `isolated thumbnails leave the visible libVLC player untouched`() {
        val media = configuredMedia() ?: return
        if (inspectLibVlcBackend() !is LibVlcBackendAvailability.Available) return
        val player = assertIs<LibVlcVideoPlayerState>(createLibVlcVideoPlayerState())
        try {
            val thumbnails = mutableListOf<JvmMediaThumbnail?>()
            runBlocking {
                player.openUri(media.toUri().toString(), InitialPlayerState.PAUSE)
                withTimeout(30.seconds) {
                    while (!player.hasMedia && player.error == null) delay(25.milliseconds)
                }
                assertNull(player.error)
                assertTrue(player.hasMedia)

                withTimeout(90.seconds) {
                    player.thumbnails(
                        positions = listOf(player.duration * 0.34, player.duration * 0.75),
                        maximumWidth = 240,
                    ) { _, thumbnail -> thumbnails += thumbnail }
                }
            }

            assertFalse(player.isPlaying)
            assertTrue(player.currentTime < 1.seconds)
            assertEquals(2, thumbnails.size)
            thumbnails.forEach(::assertJpegThumbnail)
        } finally {
            player.dispose()
        }
    }

    private fun configuredMedia(): Path? =
        System
            .getProperty(TEST_MEDIA_PROPERTY)
            ?.takeIf(String::isNotBlank)
            ?.let(Path::of)
            ?.takeIf(Files::isRegularFile)

    private fun assertJpegThumbnail(thumbnail: JvmMediaThumbnail?) {
        val generated = assertNotNull(thumbnail)
        assertTrue(generated.width in 1..240)
        assertTrue(generated.height > 0)
        assertTrue(generated.bytes.size in 4..1_048_576)
        assertEquals(0xff.toByte(), generated.bytes[0])
        assertEquals(0xd8.toByte(), generated.bytes[1])
    }

    private companion object {
        const val TEST_MEDIA_PROPERTY = "composemediaplayer.nativeSurfaceTestMedia"
    }
}
