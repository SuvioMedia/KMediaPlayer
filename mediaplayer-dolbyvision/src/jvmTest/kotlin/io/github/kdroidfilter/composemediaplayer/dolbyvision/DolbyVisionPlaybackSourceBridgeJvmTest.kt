package io.github.kdroidfilter.composemediaplayer.dolbyvision

import io.github.kdroidfilter.composemediaplayer.DynamicMetadataHandling
import io.github.kdroidfilter.composemediaplayer.VideoColorInfo
import io.github.kdroidfilter.composemediaplayer.VideoDynamicRange
import java.net.HttpURLConnection
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

class DolbyVisionPlaybackSourceBridgeJvmTest {
    @Test
    fun `loopback source serves a tokenized VOD playlist init and converted fragments`() {
        val requestedSegments = mutableListOf<Int>()
        val source =
            LoopbackDolbyVisionHlsSource(
                session =
                    object : JvmDolbyVisionHlsSession {
                        override val initializationSegment = "init-p81".encodeToByteArray()

                        override fun playlist(resourcePrefix: String): String =
                            "#EXTM3U\n#EXT-X-MAP:URI=\"$resourcePrefix/init.mp4\"\n" +
                                "$resourcePrefix/segment/2.m4s\n#EXT-X-ENDLIST\n"

                        override suspend fun segment(index: Int): ByteArray {
                            requestedSegments += index
                            return "segment-$index".encodeToByteArray()
                        }

                        override suspend fun additionalResource(
                            path: String,
                            resourcePrefix: String,
                        ): HlsVodBridgeResource? =
                            if (path == "rendition/0/segment/3") {
                                HlsVodBridgeResource("audio-3".encodeToByteArray(), "audio/aac")
                            } else {
                                null
                            }
                    },
                outputColorInfo = VideoColorInfo(VideoDynamicRange.DOLBY_VISION),
                detail = "converted",
            )
        try {
            assertTrue(source.uri.startsWith("http://127.0.0.1:"))
            assertTrue(read(source.uri).contains("/init.mp4"))
            val prefix = source.uri.substringBeforeLast('/')
            assertEquals("init-p81", read("$prefix/init.mp4"))
            assertEquals("segment-2", read("$prefix/segment/2.m4s"))
            assertEquals("audio-3", read("$prefix/rendition/0/segment/3"))
            assertEquals(listOf(2), requestedSegments)
            assertEquals(DynamicMetadataHandling.CONVERTED, source.metadataHandling)
        } finally {
            source.close()
            source.close()
        }
        assertFails { read(source.uri) }
    }

    private fun read(uri: String): String {
        val connection = URI(uri).toURL().openConnection() as HttpURLConnection
        connection.connectTimeout = 1_000
        connection.readTimeout = 1_000
        return try {
            assertEquals(200, connection.responseCode)
            connection.inputStream.use { it.readBytes().decodeToString() }
        } finally {
            connection.disconnect()
        }
    }
}
