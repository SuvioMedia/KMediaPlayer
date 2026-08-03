package io.github.kdroidfilter.composemediaplayer

import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertNull

class ExternalVlcHlsPlaylistTest {
    @Test
    fun dropsAudioOnlyPrerollBeforeExposingVlcPlaylist() {
        val directory = createTempDirectory("vlc-hls-playlist-test-")
        val audioOnly = directory.resolve("segment_00000001.ts")
        val video = directory.resolve("segment_00000002.ts")
        Files.write(audioOnly, byteArrayOf(1, 2, 3, 4))
        Files.write(
            video,
            byteArrayOf(
                0,
                0,
                0,
                1,
                H264_SPS_NAL_HEADER,
                0,
                0,
                1,
                H264_PPS_NAL_HEADER,
            ),
        )
        val playlist =
            directory.resolve("stream.m3u8").also { path ->
                Files.writeString(
                    path,
                    """
                    #EXTM3U
                    #EXT-X-TARGETDURATION:4
                    #EXT-X-MEDIA-SEQUENCE:9
                    #EXTINF:4.0,
                    segment_00000001.ts
                    #EXTINF:4.0,
                    segment_00000002.ts
                    #EXT-X-ENDLIST
                    """.trimIndent(),
                )
            }

        val exposed = ExternalVlcHlsFallback("unused").playablePlaylistContent(playlist).orEmpty()

        assertContains(exposed, "#EXT-X-MEDIA-SEQUENCE:10")
        assertFalse(exposed.contains("segment_00000001.ts"))
        assertContains(exposed, "segment_00000002.ts")
        assertContains(exposed, "#EXT-X-ENDLIST")
    }

    @Test
    fun withholdsPlaylistUntilVlcProducesVideo() {
        val directory = createTempDirectory("vlc-hls-audio-only-test-")
        Files.write(directory.resolve("segment_00000001.ts"), byteArrayOf(1, 2, 3, 4))
        val playlist =
            directory.resolve("stream.m3u8").also { path ->
                Files.writeString(
                    path,
                    """
                    #EXTM3U
                    #EXT-X-TARGETDURATION:4
                    #EXT-X-MEDIA-SEQUENCE:1
                    #EXTINF:4.0,
                    segment_00000001.ts
                    """.trimIndent(),
                )
            }

        assertNull(ExternalVlcHlsFallback("unused").playablePlaylistContent(playlist))
    }

    @Test
    fun withholdsLivePlaylistUntilItHasThreeVideoSegments() {
        val directory = createTempDirectory("vlc-hls-buffer-test-")
        repeat(2) { index ->
            Files.write(directory.resolve("segment_0000000${index + 1}.ts"), h264SegmentBytes())
        }
        val playlist =
            directory.resolve("stream.m3u8").also { path ->
                Files.writeString(
                    path,
                    """
                    #EXTM3U
                    #EXT-X-TARGETDURATION:2
                    #EXT-X-MEDIA-SEQUENCE:1
                    #EXTINF:2.0,
                    segment_00000001.ts
                    #EXTINF:2.0,
                    segment_00000002.ts
                    """.trimIndent(),
                )
            }

        assertNull(ExternalVlcHlsFallback("unused").playablePlaylistContent(playlist))
    }

    private fun h264SegmentBytes(): ByteArray =
        byteArrayOf(
            0,
            0,
            0,
            1,
            H264_SPS_NAL_HEADER,
            0,
            0,
            1,
            H264_PPS_NAL_HEADER,
        )

    private companion object {
        const val H264_SPS_NAL_HEADER: Byte = 0x67
        const val H264_PPS_NAL_HEADER: Byte = 0x68
    }
}
