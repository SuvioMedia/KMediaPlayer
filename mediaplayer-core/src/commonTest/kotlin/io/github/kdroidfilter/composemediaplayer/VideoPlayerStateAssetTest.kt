@file:OptIn(ExperimentalComposeMediaPlayerBackendApi::class)

package io.github.kdroidfilter.composemediaplayer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class VideoPlayerStateAssetTest {
    @Test
    fun `normalizedAssetPath trims whitespace and leading slashes`() {
        assertEquals("video.mp4", " video.mp4 ".normalizedAssetPath())
        assertEquals("videos/intro.mp4", "/videos/intro.mp4".normalizedAssetPath())
        assertEquals("videos/intro.mp4", "\\videos/intro.mp4".normalizedAssetPath())
    }

    @Test
    fun `normalizedAssetPath rejects blank names`() {
        assertFailsWith<IllegalArgumentException> {
            "  /\\  ".normalizedAssetPath()
        }
    }

    @Test
    fun `normalizedAssetUri returns asset uri for normalized asset path`() {
        assertEquals("asset:///video.mp4", " video.mp4 ".normalizedAssetUri())
        assertEquals("asset:///videos/intro.mp4", "/videos/intro.mp4".normalizedAssetUri())
    }
}
