package io.github.kdroidfilter.composemediaplayer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class VideoPlayerErrorTest {
    @Test
    fun testCodecError() {
        val error: VideoPlayerError = VideoPlayerError.CodecError("Unsupported codec")

        assertEquals("Unsupported codec", assertIs<VideoPlayerError.CodecError>(error).message)
    }

    @Test
    fun testNetworkError() {
        val error: VideoPlayerError = VideoPlayerError.NetworkError("Connection timeout")

        assertEquals("Connection timeout", assertIs<VideoPlayerError.NetworkError>(error).message)
    }

    @Test
    fun testSourceError() {
        val error: VideoPlayerError = VideoPlayerError.SourceError("File not found")

        assertEquals("File not found", assertIs<VideoPlayerError.SourceError>(error).message)
    }

    @Test
    fun testUnknownError() {
        val error: VideoPlayerError = VideoPlayerError.UnknownError("Unexpected error")

        assertEquals("Unexpected error", assertIs<VideoPlayerError.UnknownError>(error).message)
    }

    @Test
    fun testErrorEquality() {
        val error1 = VideoPlayerError.CodecError("Same error")
        val error2 = VideoPlayerError.CodecError("Same error")
        val error3 = VideoPlayerError.CodecError("Different error")
        val error4 = VideoPlayerError.NetworkError("Same error")

        assertEquals(error1, error2, "Same error type and message should be equal")
        assertNotEquals(error1, error3, "Same error type but different message should not be equal")

        // For different types, we can just assert they're not the same object
        assertTrue(error1 != error4, "Different error type should not be equal")
    }

    @Test
    fun testErrorTypes() {
        val errors =
            listOf<VideoPlayerError>(
                VideoPlayerError.CodecError("Codec error"),
                VideoPlayerError.NetworkError("Network error"),
                VideoPlayerError.SourceError("Source error"),
                VideoPlayerError.UnknownError("Unknown error"),
            )

        // Verify that errors of different types are not equal
        for (i in errors.indices) {
            for (j in errors.indices) {
                if (i != j) {
                    assertTrue(
                        errors[i] != errors[j],
                        "Different error types should not be equal: ${errors[i]} vs ${errors[j]}",
                    )
                }
            }
        }
    }
}
