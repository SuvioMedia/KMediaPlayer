package io.github.kdroidfilter.composemediaplayer.common

import io.github.kdroidfilter.composemediaplayer.VideoPlayerError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Tests for the VideoPlayerError class
 */
class VideoPlayerErrorTest {
    /**
     * Test the creation of CodecError
     */
    @Test
    fun testCodecError() {
        val error: VideoPlayerError = VideoPlayerError.CodecError("Unsupported codec")

        // Verify the error is initialized correctly
        assertEquals("Unsupported codec", assertIs<VideoPlayerError.CodecError>(error).message)

        // Test equality
        val sameError = VideoPlayerError.CodecError("Unsupported codec")
        val differentError = VideoPlayerError.CodecError("Different codec error")

        assertEquals(error, sameError)
        assertNotEquals(error, differentError)
    }

    /**
     * Test the creation of NetworkError
     */
    @Test
    fun testNetworkError() {
        val error: VideoPlayerError = VideoPlayerError.NetworkError("Connection timeout")

        // Verify the error is initialized correctly
        assertEquals("Connection timeout", assertIs<VideoPlayerError.NetworkError>(error).message)

        // Test equality
        val sameError = VideoPlayerError.NetworkError("Connection timeout")
        val differentError = VideoPlayerError.NetworkError("Network unavailable")

        assertEquals(error, sameError)
        assertNotEquals(error, differentError)
    }

    /**
     * Test the creation of SourceError
     */
    @Test
    fun testSourceError() {
        val error: VideoPlayerError = VideoPlayerError.SourceError("File not found")

        // Verify the error is initialized correctly
        assertEquals("File not found", assertIs<VideoPlayerError.SourceError>(error).message)

        // Test equality
        val sameError = VideoPlayerError.SourceError("File not found")
        val differentError = VideoPlayerError.SourceError("Invalid URL")

        assertEquals(error, sameError)
        assertNotEquals(error, differentError)
    }

    /**
     * Test the creation of UnknownError
     */
    @Test
    fun testUnknownError() {
        val error: VideoPlayerError = VideoPlayerError.UnknownError("Unexpected error")

        // Verify the error is initialized correctly
        assertEquals("Unexpected error", assertIs<VideoPlayerError.UnknownError>(error).message)

        // Test equality
        val sameError = VideoPlayerError.UnknownError("Unexpected error")
        val differentError = VideoPlayerError.UnknownError("Another error")

        assertEquals(error, sameError)
        assertNotEquals(error, differentError)
    }

    /**
     * Test that different error types are not equal
     */
    @Test
    fun testDifferentErrorTypes() {
        val codecError = VideoPlayerError.CodecError("Codec error")
        val networkError = VideoPlayerError.NetworkError("Network error")
        val sourceError = VideoPlayerError.SourceError("Source error")
        val unknownError = VideoPlayerError.UnknownError("Unknown error")

        // Verify different error types are not equal
        assertTrue(codecError != networkError)
        assertTrue(codecError != sourceError)
        assertTrue(codecError != unknownError)
        assertTrue(networkError != sourceError)
        assertTrue(networkError != unknownError)
        assertTrue(sourceError != unknownError)
    }

    /**
     * Test when used in a when expression
     */
    @Test
    fun testWhenExpression() {
        val errors =
            listOf(
                VideoPlayerError.CodecError("Codec error"),
                VideoPlayerError.UnsupportedCodecError("Unsupported codec"),
                VideoPlayerError.NetworkError("Network error"),
                VideoPlayerError.CorsError("CORS error"),
                VideoPlayerError.SourceError("Source error"),
                VideoPlayerError.NoSourceError("No source"),
                VideoPlayerError.TimeoutError("Timeout"),
                VideoPlayerError.HlsError("HLS error", type = "networkError", details = "manifestLoadError"),
                VideoPlayerError.UnknownError("Unknown error"),
            )

        for (error in errors) {
            val message =
                when (error) {
                    is VideoPlayerError.CodecError -> "Codec: ${error.message}"
                    is VideoPlayerError.UnsupportedCodecError -> "Codec: ${error.message}"
                    is VideoPlayerError.NetworkError -> "Network: ${error.message}"
                    is VideoPlayerError.CorsError -> "CORS: ${error.message}"
                    is VideoPlayerError.SourceError -> "Source: ${error.message}"
                    is VideoPlayerError.NoSourceError -> "Source: ${error.message}"
                    is VideoPlayerError.TimeoutError -> "Timeout: ${error.message}"
                    is VideoPlayerError.HlsError -> "HLS: ${error.message}"
                    is VideoPlayerError.UnknownError -> "Unknown: ${error.message}"
                }

            when (error) {
                is VideoPlayerError.CodecError -> assertEquals("Codec: Codec error", message)
                is VideoPlayerError.UnsupportedCodecError -> assertEquals("Codec: Unsupported codec", message)
                is VideoPlayerError.NetworkError -> assertEquals("Network: Network error", message)
                is VideoPlayerError.CorsError -> assertEquals("CORS: CORS error", message)
                is VideoPlayerError.SourceError -> assertEquals("Source: Source error", message)
                is VideoPlayerError.NoSourceError -> assertEquals("Source: No source", message)
                is VideoPlayerError.TimeoutError -> assertEquals("Timeout: Timeout", message)
                is VideoPlayerError.HlsError -> assertEquals("HLS: HLS error", message)
                is VideoPlayerError.UnknownError -> assertEquals("Unknown: Unknown error", message)
            }
        }
    }
}
