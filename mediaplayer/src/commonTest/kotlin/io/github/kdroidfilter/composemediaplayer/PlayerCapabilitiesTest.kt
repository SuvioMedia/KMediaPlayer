package io.github.kdroidfilter.composemediaplayer

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlayerCapabilitiesTest {
    @Test
    fun `canPlaySource rejects unsupported schemes and container formats`() {
        val capabilities = PlayerCapabilities(supportsMkv = false)

        assertFalse(capabilities.canPlaySource("ftp://example.test/movie.mp4"))
        assertFalse(capabilities.canPlaySource("file:///movie.mkv"))
        assertTrue(capabilities.canPlaySource("file:///movie.mp4"))
    }

    @Test
    fun `canPlaySource accepts supported local and hls sources`() {
        val capabilities = PlayerCapabilities(supportsMkv = true)

        assertTrue(capabilities.canPlaySource("blob:https://example.test/video"))
        assertTrue(capabilities.canPlaySource("data:video/mp4;base64,AAA"))
        assertTrue(capabilities.canPlaySource("https://example.test/live.m3u8"))
        assertTrue(capabilities.canPlaySource("file:///movie.mkv"))
    }

    @Test
    fun `media support snapshot exposes platform source capabilities`() =
        runTest {
            val support = MediaSupport.query()
            val source = "file:///movie.mkv"

            assertEquals(
                MediaSupport.queryCanPlaySource(source),
                support.canPlaySource(source),
            )
        }
}
