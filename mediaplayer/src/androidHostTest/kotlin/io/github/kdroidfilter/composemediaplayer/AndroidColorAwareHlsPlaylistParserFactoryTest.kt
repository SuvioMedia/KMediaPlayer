package io.github.kdroidfilter.composemediaplayer

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.hls.playlist.DefaultHlsPlaylistParserFactory
import androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistParserFactory
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AndroidColorAwareHlsPlaylistParserFactoryTest {
    @Test
    fun `annotates ordinary SDR PQ and HLG variants from VIDEO-RANGE`() {
        val playlist =
            parse(
                """
                #EXTM3U
                #EXT-X-STREAM-INF:BANDWIDTH=1000000,CODECS="avc1.640028",VIDEO-RANGE=SDR
                sdr.m3u8
                #EXT-X-STREAM-INF:BANDWIDTH=2000000,CODECS="hvc1.2.4.L120.B0",VIDEO-RANGE=PQ
                pq.m3u8
                #EXT-X-STREAM-INF:BANDWIDTH=3000000,CODECS="hvc1.2.4.L120.B0",VIDEO-RANGE=HLG
                hlg.m3u8
                """.trimIndent(),
            )

        val ranges = playlist.variants.associate { it.url.lastPathSegment to it.format.toVideoColorInfo() }
        assertEquals(VideoDynamicRange.SDR, ranges.getValue("sdr.m3u8").dynamicRange)
        assertEquals(VideoColorPrimaries.BT709, ranges.getValue("sdr.m3u8").primaries)
        assertEquals(VideoDynamicRange.HDR10, ranges.getValue("pq.m3u8").dynamicRange)
        assertEquals(VideoColorTransfer.PQ, ranges.getValue("pq.m3u8").transfer)
        assertEquals(10, ranges.getValue("pq.m3u8").bitDepth)
        assertEquals(VideoDynamicRange.HLG, ranges.getValue("hlg.m3u8").dynamicRange)
        assertEquals(VideoColorTransfer.HLG, ranges.getValue("hlg.m3u8").transfer)
    }

    @Test
    fun `preserves Media3 Dolby Vision color info and decoder signaling`() {
        val manifest =
            """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=3000000,CODECS="dvh1.05.06",VIDEO-RANGE=PQ
            dolby-vision.m3u8
            """.trimIndent()
        val media3Playlist = parse(manifest, DefaultHlsPlaylistParserFactory())
        val annotatedPlaylist = parse(manifest)

        assertEquals(
            media3Playlist.variants
                .single()
                .format.colorInfo,
            annotatedPlaylist.variants
                .single()
                .format.colorInfo,
        )
        assertEquals(
            VideoDynamicRange.DOLBY_VISION,
            annotatedPlaylist.variants
                .single()
                .format
                .toVideoColorInfo()
                .dynamicRange,
        )
    }

    @Test
    fun `expands HLS variables before matching a rendition URI`() {
        val playlist =
            parse(
                """
                #EXTM3U
                #EXT-X-DEFINE:NAME="rendition",VALUE="hlg-variable.m3u8"
                #EXT-X-STREAM-INF:BANDWIDTH=3000000,CODECS="hvc1.2.4.L120.B0",VIDEO-RANGE=HLG
                {${'$'}rendition}
                """.trimIndent(),
            )

        assertEquals(
            VideoDynamicRange.HLG,
            playlist.variants
                .single()
                .format
                .toVideoColorInfo()
                .dynamicRange,
        )
    }

    @Test
    fun `propagates an unambiguous range to EXT-X-MEDIA video renditions`() {
        val playlist =
            parse(
                """
                #EXTM3U
                #EXT-X-MEDIA:TYPE=VIDEO,GROUP-ID="video",NAME="main",URI="video.m3u8"
                #EXT-X-STREAM-INF:BANDWIDTH=3000000,CODECS="hvc1.2.4.L120.B0",VIDEO-RANGE=HLG,VIDEO="video"
                variant.m3u8
                """.trimIndent(),
            )

        assertEquals(
            VideoDynamicRange.HLG,
            playlist.videos
                .single()
                .format
                .toVideoColorInfo()
                .dynamicRange,
        )
    }

    @Test
    fun `does not guess when duplicate URI declarations disagree`() {
        val playlist =
            parse(
                """
                #EXTM3U
                #EXT-X-STREAM-INF:BANDWIDTH=1000000,CODECS="hvc1.2.4.L120.B0",VIDEO-RANGE=SDR
                same.m3u8
                #EXT-X-STREAM-INF:BANDWIDTH=2000000,CODECS="hvc1.2.4.L120.B0",VIDEO-RANGE=PQ
                same.m3u8
                """.trimIndent(),
            )

        assertNull(
            playlist.variants
                .single()
                .format.colorInfo,
        )
    }

    @Test
    fun `parses I-frame URI when URI is the first attribute`() {
        val playlist =
            parse(
                """
                #EXTM3U
                #EXT-X-STREAM-INF:BANDWIDTH=1000000,CODECS="avc1.640028",VIDEO-RANGE=SDR
                sdr.m3u8
                #EXT-X-I-FRAME-STREAM-INF:URI="iframe.m3u8",BANDWIDTH=500000,CODECS="hvc1.2.4.L120.B0",VIDEO-RANGE=PQ
                """.trimIndent(),
            )

        val iFrameVariant = playlist.variants.single { it.format.roleFlags == C.ROLE_FLAG_TRICK_PLAY }
        assertEquals(VideoDynamicRange.HDR10, iFrameVariant.format.toVideoColorInfo().dynamicRange)
    }

    @Test
    fun `preserves Media3 content steering while annotating video range`() {
        val playlist =
            parse(
                """
                #EXTM3U
                #EXT-X-CONTENT-STEERING:SERVER-URI="https://steering.example.test/config",PATHWAY-ID="primary"
                #EXT-X-STREAM-INF:BANDWIDTH=3000000,CODECS="hvc1.2.4.L120.B0",VIDEO-RANGE=HLG,PATHWAY-ID="primary"
                hlg.m3u8
                """.trimIndent(),
            )

        val contentSteeringInfo = assertNotNull(playlist.contentSteeringInfo)
        assertEquals(Uri.parse("https://steering.example.test/config"), contentSteeringInfo.serverUri)
        assertEquals("primary", contentSteeringInfo.pathwayId)
    }

    private fun parse(
        manifest: String,
        factory: HlsPlaylistParserFactory = AndroidColorAwareHlsPlaylistParserFactory(),
    ): HlsMultivariantPlaylist =
        factory
            .createPlaylistParser()
            .parse(Uri.parse(MASTER_PLAYLIST_URI), manifest.byteInputStream()) as HlsMultivariantPlaylist

    private companion object {
        const val MASTER_PLAYLIST_URI = "https://example.test/path/master.m3u8"
    }
}
