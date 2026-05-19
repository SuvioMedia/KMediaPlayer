package io.github.kdroidfilter.composemediaplayer

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlayerCapabilitiesTest {
    @Test
    fun `canPlaySource rejects unsupported adaptive and container formats`() {
        val capabilities = PlayerCapabilities(supportsHls = false, supportsMkv = false)

        assertFalse(capabilities.canPlaySource("https://example.test/live.m3u8"))
        assertFalse(capabilities.canPlaySource("file:///movie.mkv"))
        assertTrue(capabilities.canPlaySource("file:///movie.mp4"))
    }

    @Test
    fun `canPlaySource accepts supported local and hls sources`() {
        val capabilities = PlayerCapabilities(supportsHls = true, supportsMkv = true)

        assertTrue(capabilities.canPlaySource("blob:https://example.test/video"))
        assertTrue(capabilities.canPlaySource("data:video/mp4;base64,AAA"))
        assertTrue(capabilities.canPlaySource("https://example.test/live.m3u8"))
        assertTrue(capabilities.canPlaySource("file:///movie.mkv"))
    }
}
