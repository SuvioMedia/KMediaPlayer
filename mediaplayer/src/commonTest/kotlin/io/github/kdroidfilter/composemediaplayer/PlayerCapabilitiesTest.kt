package io.github.kdroidfilter.composemediaplayer

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
    fun `hdr capabilities expose HDR display only above SDR range`() {
        assertFalse(HdrCapabilities(maxExtendedDynamicRange = 1f).hasHdrDisplay)
        assertTrue(HdrCapabilities(maxExtendedDynamicRange = 1.5f).hasHdrDisplay)
    }

    @Test
    fun `hdr capabilities reject invalid extended dynamic range`() {
        assertFailsWith<IllegalArgumentException> {
            HdrCapabilities(maxExtendedDynamicRange = 0.9f)
        }
        assertFailsWith<IllegalArgumentException> {
            HdrCapabilities(maxExtendedDynamicRange = Float.NaN)
        }
        assertFailsWith<IllegalArgumentException> {
            HdrCapabilities(maxExtendedDynamicRange = Float.POSITIVE_INFINITY)
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
