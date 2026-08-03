package io.github.kdroidfilter.composemediaplayer.windows

import com.sun.net.httpserver.HttpServer
import io.github.kdroidfilter.composemediaplayer.VideoColorTransfer
import io.github.kdroidfilter.composemediaplayer.VideoDynamicRange
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WindowsHlsColorProbeTest {
    @Test
    fun `infers SDR for master containing only eight bit AVC variants`() {
        val playlist =
            """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=2149280,CODECS="mp4a.40.2,avc1.64001f",RESOLUTION=1280x720
            720p.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=246440,CODECS="mp4a.40.5,avc1.4d4015",VIDEO-RANGE=SDR
            240p.m3u8
            """.trimIndent()

        val color = WindowsHlsColorProbe.inferColorInfo(playlist)

        assertEquals(VideoDynamicRange.SDR, color?.dynamicRange)
        assertEquals(8, color?.bitDepth)
        assertEquals(VideoColorTransfer.SDR, color?.transfer)
    }

    @Test
    fun `does not infer SDR for HDR or high bit depth variants`() {
        val hdr =
            "#EXT-X-STREAM-INF:BANDWIDTH=1,CODECS=\"hvc1.2.4.L153.B0\",VIDEO-RANGE=PQ\nvideo.m3u8"
        val high10 =
            "#EXT-X-STREAM-INF:BANDWIDTH=1,CODECS=\"mp4a.40.2,avc1.6e001f\"\nvideo.m3u8"

        assertNull(WindowsHlsColorProbe.inferColorInfo(hdr))
        assertNull(WindowsHlsColorProbe.inferColorInfo(high10))
    }

    @Test
    fun `requires codec evidence for every variant`() {
        val playlist =
            """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=1,CODECS="avc1.64001f"
            known.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=2
            unknown.m3u8
            """.trimIndent()

        assertNull(WindowsHlsColorProbe.inferColorInfo(playlist))
    }

    @Test
    fun `finds a segment in a media playlist but never treats a master variant as a segment`() {
        val mediaPlaylist =
            """
            #EXTM3U
            #EXT-X-TARGETDURATION:10
            #EXTINF:10.0,
            video/segment-001.ts
            """.trimIndent()
        val masterPlaylist =
            """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=1
            video/variant.m3u8
            """.trimIndent()

        assertEquals("video/segment-001.ts", WindowsHlsColorProbe.firstMediaSegmentLocator(mediaPlaylist))
        assertNull(WindowsHlsColorProbe.firstMediaSegmentLocator(masterPlaylist))
    }

    @Test
    fun `finds the initialization segment of fragmented mp4 HLS`() {
        val playlist =
            """
            #EXTM3U
            #EXT-X-MAP:URI="video/init.mp4"
            #EXTINF:4.0,
            video/segment-001.m4s
            """.trimIndent()

        assertEquals("video/init.mp4", WindowsHlsColorProbe.initializationSegmentLocator(playlist))
    }

    @Test
    fun `infers untagged eight bit AVC from fragmented mp4 initialization data as SDR`() {
        val avcConfigurationBox =
            byteArrayOf(
                0,
                0,
                0,
                14,
                'a'.code.toByte(),
                'v'.code.toByte(),
                'c'.code.toByte(),
                'C'.code.toByte(),
                1,
                0x64,
                0,
                0,
                0,
                0,
            )

        val color = WindowsHlsColorProbe.inferFragmentedMp4InitializationColor(avcConfigurationBox)

        assertEquals(VideoDynamicRange.SDR, color?.dynamicRange)
        assertEquals(VideoColorTransfer.SDR, color?.transfer)
        assertEquals(8, color?.bitDepth)
    }

    @Test
    fun `infers PQ from legacy nclc color information used by Apple HLS`() {
        val nclcColorInformationBox =
            byteArrayOf(
                0,
                0,
                0,
                18,
                'c'.code.toByte(),
                'o'.code.toByte(),
                'l'.code.toByte(),
                'r'.code.toByte(),
                'n'.code.toByte(),
                'c'.code.toByte(),
                'l'.code.toByte(),
                'c'.code.toByte(),
                0,
                9,
                0,
                16,
                0,
                9,
            )

        val color = WindowsHlsColorProbe.inferFragmentedMp4InitializationColor(nclcColorInformationBox)

        assertEquals(VideoDynamicRange.HDR10, color?.dynamicRange)
        assertEquals(VideoColorTransfer.PQ, color?.transfer)
    }

    @Test
    fun `rejects an invalid AVC configuration record`() {
        val invalidAvcConfigurationBox =
            byteArrayOf(
                0,
                0,
                0,
                10,
                'a'.code.toByte(),
                'v'.code.toByte(),
                'c'.code.toByte(),
                'C'.code.toByte(),
                2,
                0x64,
            )

        assertNull(WindowsHlsColorProbe.inferFragmentedMp4InitializationColor(invalidAvcConfigurationBox))
    }

    @Test
    fun `playlist preflight ignores caller range and compressed response requests`() {
        val receivedRange = AtomicReference<String?>()
        val receivedEncoding = AtomicReference<String?>()
        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/fixture.m3u8") { exchange ->
            receivedRange.set(exchange.requestHeaders.getFirst("Range"))
            receivedEncoding.set(exchange.requestHeaders.getFirst("Accept-Encoding"))
            val playlist = "#EXTM3U\n#EXT-X-ENDLIST\n".encodeToByteArray()
            exchange.sendResponseHeaders(200, playlist.size.toLong())
            exchange.responseBody.use { it.write(playlist) }
        }
        server.start()
        try {
            val loaded =
                WindowsHlsResourceReader.readPlaylist(
                    "http://127.0.0.1:${server.address.port}/fixture.m3u8",
                    mapOf("Range" to "bytes=100-", "Accept-Encoding" to "gzip"),
                )

            assertEquals("#EXTM3U\n#EXT-X-ENDLIST\n", loaded?.content)
            assertNull(receivedRange.get())
            assertEquals("identity", receivedEncoding.get())
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `missing initialization segment is an inconclusive probe instead of a playback failure`() {
        val base = Files.createTempDirectory("kmediaplayer-hls-probe-")
        try {
            val playlist = base.resolve("stream.m3u8")
            Files.writeString(playlist, "#EXTM3U\n#EXT-X-MAP:URI=\"missing.mp4\"\n")
            val loaded = WindowsHlsResourceReader.readPlaylist(playlist.toString(), emptyMap())

            assertNull(
                loaded?.let { WindowsHlsResourceReader.readInitializationSegment(it, "missing.mp4") },
            )
        } finally {
            base.toFile().deleteRecursively()
        }
    }
}
