package io.github.kdroidfilter.composemediaplayer.dolbyvision

import io.github.kdroidfilter.composemediaplayer.DynamicMetadataHandling
import io.github.kdroidfilter.composemediaplayer.VideoColorInfo
import io.github.kdroidfilter.composemediaplayer.VideoDynamicRange
import kotlinx.coroutines.test.runTest
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DolbyVisionPlaybackSourceBridgeJvmTest {
    @Test
    fun `materialized source concatenates init and fragments and owns temporary storage`() =
        runTest {
            val requestedSegments = mutableListOf<Int>()
            var closeCount = 0
            val source =
                materializeDolbyVisionSession(
                    session =
                        object : JvmDolbyVisionHlsSession {
                            override val initializationSegment = byteArrayOf(1, 2)
                            override val singleFileSegmentCount: Int = 2

                            override fun playlist(resourcePrefix: String): String = error("Not used")

                            override suspend fun segment(index: Int): ByteArray {
                                requestedSegments += index
                                return byteArrayOf((index + 3).toByte())
                            }

                            override fun close() {
                                closeCount += 1
                            }
                        },
                    outputColorInfo = VideoColorInfo(VideoDynamicRange.DOLBY_VISION),
                    detail = "converted",
                    maximumBytes = 16,
                )
            val path = Path.of(source.uri)

            assertTrue(Files.isRegularFile(path))
            assertContentEquals(byteArrayOf(1, 2, 3, 4), Files.readAllBytes(path))
            assertEquals(listOf(0, 1), requestedSegments)
            assertEquals(DynamicMetadataHandling.CONVERTED, source.metadataHandling)

            source.close()
            source.close()
            assertFalse(Files.exists(path))
            assertEquals(1, closeCount)
        }

    @Test
    fun `materialization failure releases the source session`() =
        runTest {
            var closeCount = 0
            val session =
                object : JvmDolbyVisionHlsSession {
                    override val initializationSegment = byteArrayOf(1)
                    override val singleFileSegmentCount: Int = 1

                    override fun playlist(resourcePrefix: String): String = error("Not used")

                    override suspend fun segment(index: Int): ByteArray = error("conversion failed")

                    override fun close() {
                        closeCount += 1
                    }
                }

            assertFails {
                materializeDolbyVisionSession(
                    session = session,
                    outputColorInfo = VideoColorInfo(VideoDynamicRange.DOLBY_VISION),
                    detail = "converted",
                    maximumBytes = 16,
                )
            }
            assertEquals(1, closeCount)
        }

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
