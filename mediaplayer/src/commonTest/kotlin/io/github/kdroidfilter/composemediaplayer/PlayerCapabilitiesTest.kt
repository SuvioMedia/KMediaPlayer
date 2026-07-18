package io.github.kdroidfilter.composemediaplayer

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@Suppress("FunctionNaming")
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
        assertEquals(
            capabilities.supportsHls,
            capabilities.canPlaySource("https://example.test/live.m3u8"),
        )
        assertTrue(capabilities.canPlaySource("file:///movie.mkv"))
    }

    @Test
    fun `canPlaySource honors configured URI schemes`() {
        val capabilities = PlayerCapabilities(supportedUriSchemes = setOf("FILE", "Https"))

        assertTrue(capabilities.canPlaySource("https://example.test/movie.mp4"))
        assertTrue(capabilities.canPlaySource("file:///movie.mp4"))
        assertFalse(capabilities.canPlaySource("http://example.test/movie.mp4"))
        assertFalse(capabilities.canPlaySource("blob:https://example.test/movie.mp4"))
        assertFalse(capabilities.canPlaySource("data:video/mp4;base64,AAA"))
    }

    @Test
    fun `canPlaySource treats Windows file paths as file sources`() {
        val fileCapabilities = PlayerCapabilities(supportedUriSchemes = setOf("file"))
        val networkCapabilities = PlayerCapabilities(supportedUriSchemes = setOf("https"))

        assertTrue(fileCapabilities.canPlaySource("""C:\Videos\movie.mp4"""))
        assertTrue(fileCapabilities.canPlaySource("""C:/Videos/movie.mp4"""))
        assertTrue(fileCapabilities.canPlaySource("""\\server\share\movie.mp4"""))
        assertFalse(networkCapabilities.canPlaySource("""C:\Videos\movie.mp4"""))
        assertFalse(networkCapabilities.canPlaySource("""\\server\share\movie.mp4"""))
    }

    @Test
    fun `display capabilities expose HDR only from explicit dynamic ranges`() {
        assertFalse(DisplayColorCapabilities(isKnown = true).supportsHdr)
        assertTrue(
            DisplayColorCapabilities(
                isKnown = true,
                supportedDynamicRanges = setOf(VideoDynamicRange.HDR10),
            ).supportsHdr,
        )
    }

    @Test
    fun `display dynamic range support distinguishes unsupported from unknown`() {
        val knownHdr10Display =
            DisplayColorCapabilities(
                isKnown = true,
                supportedDynamicRanges = setOf(VideoDynamicRange.SDR, VideoDynamicRange.HDR10),
            )
        val unknownDisplay = DisplayColorCapabilities()

        assertEquals(VideoDynamicRangeSupport.SUPPORTED, knownHdr10Display.supportFor(VideoDynamicRange.HDR10))
        assertEquals(
            VideoDynamicRangeSupport.UNSUPPORTED,
            knownHdr10Display.supportFor(VideoDynamicRange.HDR10_PLUS),
        )
        assertEquals(VideoDynamicRangeSupport.UNKNOWN, unknownDisplay.supportFor(VideoDynamicRange.HDR10_PLUS))
        assertEquals(VideoDynamicRangeSupport.UNKNOWN, knownHdr10Display.supportFor(VideoDynamicRange.UNKNOWN))
    }

    @Test
    fun `display capabilities reject invalid luminance`() {
        assertFailsWith<IllegalArgumentException> {
            DisplayColorCapabilities(maxLuminanceNits = -1f)
        }
        assertFailsWith<IllegalArgumentException> {
            DisplayColorCapabilities(maxLuminanceNits = Float.NaN)
        }
        assertFailsWith<IllegalArgumentException> {
            DisplayColorCapabilities(minLuminanceNits = 10f, maxLuminanceNits = 5f)
        }
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
