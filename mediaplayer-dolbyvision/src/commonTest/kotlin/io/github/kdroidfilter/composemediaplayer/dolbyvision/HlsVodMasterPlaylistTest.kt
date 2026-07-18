@file:Suppress("MaxLineLength")

package io.github.kdroidfilter.composemediaplayer.dolbyvision

import io.github.kdroidfilter.composemediaplayer.DolbyVisionEnhancementLayer
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class HlsVodMasterPlaylistTest {
    @Test
    fun `master selects highest Profile 7 variant and proxies alternate audio without eager media reads`() =
        runTest {
            val videoFragment = mediaFragment(hevcSample(byteArrayOf(0x7c, 0x01, 1)))
            val audioInit = byteArrayOf(1, 2, 3, 4)
            val audioSegment = byteArrayOf(5, 6, 7, 8)
            val resources =
                mapOf(
                    VIDEO_PLAYLIST_URI to videoPlaylist().encodeToByteArray(),
                    AUDIO_PLAYLIST_URI to audioPlaylist(endList = true).encodeToByteArray(),
                    VIDEO_INIT_URI to initializationSegment(),
                    VIDEO_SEGMENT_URI to videoFragment,
                    AUDIO_INIT_URI to audioInit,
                    AUDIO_SEGMENT_URI to audioSegment,
                )
            val reads = mutableListOf<Triple<String, DolbyVisionByteRange?, Int>>()
            val source =
                DolbyVisionMediaDataSource { uri, range, maximumBytes ->
                    reads += Triple(uri, range, maximumBytes)
                    resources.getValue(uri).let { value ->
                        require(value.size <= maximumBytes)
                        value
                    }
                }

            val session =
                assertIs<HlsVodDolbyVisionOpenResult.Success>(
                    HlsVodDolbyVisionAdapter.open(
                        playlistUri = MASTER_URI,
                        playlist = masterPlaylist(),
                        dataSource = source,
                        converter = converter,
                        enhancementLayer = DolbyVisionEnhancementLayer.MEL,
                    ),
                ).session

            assertTrue(session.usesMasterPlaylist)
            assertTrue(session.hasExternalAudioRenditions)
            val preferredAudio = requireNotNull(session.preferredExternalAudioRendition)
            assertEquals("mp4a.40.2", preferredAudio.codec)
            assertTrue(preferredAudio.isDefault)
            assertEquals(
                40_000,
                preferredAudio.playlist
                    ?.segments
                    ?.single()
                    ?.durationUs,
            )
            assertEquals(
                listOf(VIDEO_PLAYLIST_URI, AUDIO_PLAYLIST_URI, VIDEO_INIT_URI),
                reads.map { it.first },
            )
            assertEquals(
                listOf(
                    MAXIMUM_DOVI_HLS_PLAYLIST_BYTES,
                    MAXIMUM_DOVI_HLS_PLAYLIST_BYTES,
                    DEFAULT_MAXIMUM_HLS_RESOURCE_BYTES,
                ),
                reads.map { it.third },
            )
            val entry = session.renderEntryPlaylist("cmpdovi://token")
            assertTrue(entry.contains("CODECS=\"dvhe.07.06,mp4a.40.2\""))
            assertTrue(entry.contains("cmpdovi://token/video.m3u8"))
            assertTrue(entry.contains("cmpdovi://token/rendition/0/playlist.m3u8"))
            assertFalse(entry.contains("low/main.m3u8"))
            assertFalse(entry.contains("p8/main.m3u8"))

            val rewrittenVideo =
                requireNotNull(session.readMasterResource("video.m3u8", "cmpdovi://token")).payload.decodeToString()
            assertTrue(rewrittenVideo.contains("cmpdovi://token/video/init.mp4"))
            assertTrue(rewrittenVideo.contains("cmpdovi://token/video/segment/0.m4s"))
            val rewrittenAudio =
                requireNotNull(
                    session.readMasterResource("rendition/0/playlist.m3u8", "cmpdovi://token"),
                ).payload.decodeToString()
            assertTrue(rewrittenAudio.contains("cmpdovi://token/rendition/0/init/0"))
            assertTrue(rewrittenAudio.contains("cmpdovi://token/rendition/0/segment/0"))

            assertContentEquals(
                audioInit,
                requireNotNull(session.readMasterResource("rendition/0/init/0", "cmpdovi://token")).payload,
            )
            assertContentEquals(
                audioSegment,
                requireNotNull(session.readMasterResource("rendition/0/segment/0", "cmpdovi://token")).payload,
            )
            assertIs<HlsVodBridgeResource>(session.readMasterResource("video/segment/0.m4s", "cmpdovi://token"))
            assertEquals(
                listOf(VIDEO_SEGMENT_URI, AUDIO_INIT_URI, AUDIO_SEGMENT_URI).sorted(),
                reads.drop(3).map { it.first }.sorted(),
            )
        }

    @Test
    fun `master reports live DRM and explicit non Profile 7 variants with typed reasons`() =
        runTest {
            val noFetch = DolbyVisionMediaDataSource { _, _, _ -> error("must not fetch") }
            val drm =
                assertIs<HlsVodDolbyVisionOpenResult.Failure>(
                    HlsVodDolbyVisionAdapter.open(
                        MASTER_URI,
                        masterPlaylist().replace(
                            "#EXT-X-VERSION:7",
                            "#EXT-X-SESSION-KEY:METHOD=SAMPLE-AES,URI=\"key\"",
                        ),
                        noFetch,
                        converter,
                        DolbyVisionEnhancementLayer.MEL,
                    ),
                )
            assertEquals(HlsVodDolbyVisionFailureReason.DRM_PROTECTED, drm.reason)

            val unsupported =
                assertIs<HlsVodDolbyVisionOpenResult.Failure>(
                    HlsVodDolbyVisionAdapter.open(
                        MASTER_URI,
                        """
                        #EXTM3U
                        #EXT-X-STREAM-INF:BANDWIDTH=1000,CODECS="avc1.640028,mp4a.40.2"
                        avc/main.m3u8
                        #EXT-X-STREAM-INF:BANDWIDTH=2000,CODECS="dvhe.08.06,ec-3"
                        p8/main.m3u8
                        """.trimIndent(),
                        noFetch,
                        converter,
                        DolbyVisionEnhancementLayer.MEL,
                    ),
                )
            assertEquals(HlsVodDolbyVisionFailureReason.UNSUPPORTED_VARIANT, unsupported.reason)

            val resources =
                mapOf(
                    VIDEO_PLAYLIST_URI to videoPlaylist().encodeToByteArray(),
                    AUDIO_PLAYLIST_URI to audioPlaylist(endList = false).encodeToByteArray(),
                )
            val liveSource = DolbyVisionMediaDataSource { uri, _, _ -> resources.getValue(uri) }
            val live =
                assertIs<HlsVodDolbyVisionOpenResult.Failure>(
                    HlsVodDolbyVisionAdapter.open(
                        MASTER_URI,
                        masterPlaylist(),
                        liveSource,
                        converter,
                        DolbyVisionEnhancementLayer.MEL,
                    ),
                )
            assertEquals(HlsVodDolbyVisionFailureReason.LIVE_SOURCE, live.reason)
        }

    @Test
    fun `repeated identical maps survive discontinuity and implicit ranges cannot jump resources`() =
        runTest {
            val fragment = mediaFragment(hevcSample(byteArrayOf(0x7c, 0x01, 1)))
            val resources =
                mapOf(
                    VIDEO_INIT_URI to initializationSegment(),
                    VIDEO_SEGMENT_URI to fragment,
                    SECOND_VIDEO_SEGMENT_URI to fragment,
                )
            val source = DolbyVisionMediaDataSource { uri, _, _ -> resources.getValue(uri) }
            val repeatedMaps =
                videoPlaylist().replace(
                    "#EXTINF:0.04,\nsegment-0.m4s\n#EXT-X-ENDLIST",
                    "#EXTINF:0.04,\nsegment-0.m4s\n#EXT-X-DISCONTINUITY\n" +
                        "#EXT-X-MAP:URI=\"init.mp4\"\n#EXTINF:0.04,\nsegment-1.m4s\n#EXT-X-ENDLIST",
                )
            val session =
                assertIs<HlsVodDolbyVisionOpenResult.Success>(
                    HlsVodDolbyVisionAdapter.open(
                        VIDEO_PLAYLIST_URI,
                        repeatedMaps,
                        source,
                        converter,
                        DolbyVisionEnhancementLayer.MEL,
                    ),
                ).session
            assertEquals(2, session.renderMediaPlaylist("cmpdovi://token").split("/init.mp4").size - 1)

            val invalidRange =
                """
                #EXTM3U
                #EXT-X-MAP:URI="init.mp4"
                #EXT-X-BYTERANGE:100@0
                #EXTINF:1,
                segment-0.m4s
                #EXT-X-BYTERANGE:100
                #EXTINF:1,
                segment-1.m4s
                #EXT-X-ENDLIST
                """.trimIndent()
            val rejected =
                assertIs<HlsVodDolbyVisionOpenResult.Failure>(
                    HlsVodDolbyVisionAdapter.open(
                        VIDEO_PLAYLIST_URI,
                        invalidRange,
                        source,
                        converter,
                        DolbyVisionEnhancementLayer.MEL,
                    ),
                )
            assertEquals(HlsVodDolbyVisionFailureReason.INVALID_PLAYLIST, rejected.reason)
            assertTrue(rejected.message.contains("changed resource URI"))
        }

    @Test
    fun `data source cannot exceed a declared limit or truncate a byte range`() =
        runTest {
            val oversizedSource = DolbyVisionMediaDataSource { _, _, _ -> ByteArray(65) }
            val oversized =
                assertIs<HlsVodDolbyVisionOpenResult.Failure>(
                    HlsVodDolbyVisionAdapter.open(
                        VIDEO_PLAYLIST_URI,
                        videoPlaylist(),
                        oversizedSource,
                        converter,
                        DolbyVisionEnhancementLayer.MEL,
                        maximumSegmentBytes = 64,
                    ),
                )
            assertEquals(HlsVodDolbyVisionFailureReason.RESOURCE_IO, oversized.reason)
            assertTrue(oversized.message.contains("byte limit"))

            val rangedPlaylist = videoPlaylist().replace("URI=\"init.mp4\"", "URI=\"init.mp4\",BYTERANGE=\"4@0\"")
            val truncatedSource = DolbyVisionMediaDataSource { _, _, _ -> ByteArray(3) }
            val truncated =
                assertIs<HlsVodDolbyVisionOpenResult.Failure>(
                    HlsVodDolbyVisionAdapter.open(
                        VIDEO_PLAYLIST_URI,
                        rangedPlaylist,
                        truncatedSource,
                        converter,
                        DolbyVisionEnhancementLayer.MEL,
                    ),
                )
            assertEquals(HlsVodDolbyVisionFailureReason.RESOURCE_IO, truncated.reason)
            assertTrue(truncated.message.contains("truncated"))
        }

    @Test
    fun `malformed master attributes and unquoted rendition URIs fail closed`() =
        runTest {
            val noFetch = DolbyVisionMediaDataSource { _, _, _ -> error("must not fetch") }
            val malformed =
                assertIs<HlsVodDolbyVisionOpenResult.Failure>(
                    HlsVodDolbyVisionAdapter.open(
                        MASTER_URI,
                        """
                        #EXTM3U
                        #EXT-X-STREAM-INF:BANDWIDTH=4000000,CODECS="dvhe.07.06,ec-3
                        video/main.m3u8
                        """.trimIndent(),
                        noFetch,
                        converter,
                        DolbyVisionEnhancementLayer.MEL,
                    ),
                )
            assertEquals(HlsVodDolbyVisionFailureReason.INVALID_PLAYLIST, malformed.reason)

            val resources = mapOf(VIDEO_PLAYLIST_URI to videoPlaylist().encodeToByteArray())
            val unquoted =
                assertIs<HlsVodDolbyVisionOpenResult.Failure>(
                    HlsVodDolbyVisionAdapter.open(
                        MASTER_URI,
                        masterPlaylist().replace("URI=\"audio/en.m3u8\"", "URI=audio/en.m3u8"),
                        DolbyVisionMediaDataSource { uri, _, _ -> resources.getValue(uri) },
                        converter,
                        DolbyVisionEnhancementLayer.MEL,
                    ),
                )
            assertEquals(HlsVodDolbyVisionFailureReason.INVALID_PLAYLIST, unquoted.reason)
            assertTrue(unquoted.message.contains("must be quoted"))
        }

    @Test
    fun `external audio keeps its timeline discontinuity and changing initialization map`() =
        runTest {
            val changingAudio =
                """
                #EXTM3U
                #EXT-X-TARGETDURATION:1
                #EXT-X-MAP:URI="init.m4a"
                #EXTINF:0.04,
                audio-0.m4s
                #EXT-X-DISCONTINUITY
                #EXT-X-MAP:URI="init-1.m4a"
                #EXTINF:0.06,
                audio-1.m4s
                #EXT-X-ENDLIST
                """.trimIndent()
            val resources =
                mapOf(
                    VIDEO_PLAYLIST_URI to videoPlaylist().encodeToByteArray(),
                    AUDIO_PLAYLIST_URI to changingAudio.encodeToByteArray(),
                    VIDEO_INIT_URI to initializationSegment(),
                )
            val session =
                assertIs<HlsVodDolbyVisionOpenResult.Success>(
                    HlsVodDolbyVisionAdapter.open(
                        MASTER_URI,
                        masterPlaylist(),
                        DolbyVisionMediaDataSource { uri, _, _ -> resources.getValue(uri) },
                        converter,
                        DolbyVisionEnhancementLayer.MEL,
                    ),
                ).session

            val audio = requireNotNull(session.preferredExternalAudioRendition?.playlist)
            assertEquals(2, audio.maps.size)
            assertEquals(listOf(0, 1), audio.segments.map { it.initializationIndex })
            assertEquals(listOf(0L, 40_000L), audio.segments.map { it.startPresentationTimeUs })
            assertEquals(listOf(40_000L, 60_000L), audio.segments.map { it.durationUs })
            assertEquals(listOf(false, true), audio.segments.map { it.discontinuity })
        }

    private fun masterPlaylist(): String =
        """
        #EXTM3U
        #EXT-X-VERSION:7
        #EXT-X-INDEPENDENT-SEGMENTS
        #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aud",NAME="English",LANGUAGE="en",DEFAULT=YES,AUTOSELECT=YES,URI="audio/en.m3u8"
        #EXT-X-STREAM-INF:BANDWIDTH=1000000,CODECS="dvhe.07.06,mp4a.40.2",AUDIO="aud"
        low/main.m3u8
        #EXT-X-STREAM-INF:BANDWIDTH=4000000,CODECS="dvhe.07.06,mp4a.40.2",AUDIO="aud"
        video/main.m3u8
        #EXT-X-STREAM-INF:BANDWIDTH=8000000,CODECS="dvhe.08.06,ec-3"
        p8/main.m3u8
        """.trimIndent()

    private fun videoPlaylist(): String =
        """
        #EXTM3U
        #EXT-X-VERSION:7
        #EXT-X-TARGETDURATION:1
        #EXT-X-MAP:URI="init.mp4"
        #EXTINF:0.04,
        segment-0.m4s
        #EXT-X-ENDLIST
        """.trimIndent()

    private fun audioPlaylist(endList: Boolean): String =
        buildString {
            appendLine("#EXTM3U")
            appendLine("#EXT-X-VERSION:7")
            appendLine("#EXT-X-TARGETDURATION:1")
            appendLine("#EXT-X-MAP:URI=\"init.m4a\"")
            appendLine("#EXTINF:0.04,")
            appendLine("audio-0.m4s")
            if (endList) appendLine("#EXT-X-ENDLIST")
        }

    private fun initializationSegment(): ByteArray {
        val tkhd = fullBox("tkhd", ByteArray(20).also { it.writeInt(8, 1) })
        val mdhd = fullBox("mdhd", ByteArray(20).also { it.writeInt(8, 1_000) })
        val hdlr = fullBox("hdlr", ByteArray(12).also { "vide".encodeToByteArray().copyInto(it, 4) })
        val hvcc = box("hvcC", ByteArray(22).also { it[21] = 3 })
        val doviWord = (7 shl 9) or (6 shl 3) or 0b111
        val dvcc =
            box(
                "dvcC",
                ByteArray(24).also {
                    it[0] = 1
                    it[2] = (doviWord ushr 8).toByte()
                    it[3] = doviWord.toByte()
                    it[4] = 0x60
                },
            )
        val sampleEntry = box("dvhe", ByteArray(78) + hvcc + dvcc)
        val stsd = fullBox("stsd", uint32(1) + sampleEntry)
        return box("ftyp", "iso6".encodeToByteArray()) +
            box("moov", box("trak", tkhd + box("mdia", mdhd + hdlr + box("minf", box("stbl", stsd)))))
    }

    private fun mediaFragment(video: ByteArray): ByteArray {
        fun moof(dataOffset: Int): ByteArray {
            val tfhd = fullBox("tfhd", uint32(1), flags = 0x020000)
            val tfdt = fullBox("tfdt", uint32(0))
            val trun =
                fullBox(
                    "trun",
                    uint32(1) + int32(dataOffset) + uint32(40) + uint32(video.size) + uint32(0) + uint32(0),
                    flags = 0x000f01,
                )
            return box("moof", fullBox("mfhd", uint32(1)) + box("traf", tfhd + tfdt + trun))
        }
        val placeholder = moof(0)
        return moof(placeholder.size + 8) + box("mdat", video)
    }

    private fun hevcSample(rpu: ByteArray): ByteArray =
        lengthPrefixed(byteArrayOf(0x40, 0x01, 1, 2), rpu, byteArrayOf(0x26, 0x01, 3, 4))

    private fun lengthPrefixed(vararg nals: ByteArray): ByteArray =
        nals.fold(ByteArray(0)) { bytes, nal -> bytes + uint32(nal.size) + nal }

    private fun fullBox(
        type: String,
        content: ByteArray,
        flags: Int = 0,
    ): ByteArray =
        box(type, byteArrayOf(0, (flags ushr 16).toByte(), (flags ushr 8).toByte(), flags.toByte()) + content)

    private fun box(
        type: String,
        content: ByteArray,
    ): ByteArray = uint32(content.size + 8) + type.encodeToByteArray() + content

    private fun uint32(value: Int): ByteArray =
        byteArrayOf((value ushr 24).toByte(), (value ushr 16).toByte(), (value ushr 8).toByte(), value.toByte())

    private fun int32(value: Int): ByteArray = uint32(value)

    private fun ByteArray.writeInt(
        offset: Int,
        value: Int,
    ) = uint32(value).copyInto(this, offset)

    private val converter =
        object : DolbyVisionRpuConverter {
            override val isAvailable: Boolean = true

            override suspend fun convertProfile7To81(rpuNalUnit: ByteArray): DolbyVisionRpuConversionResult =
                DolbyVisionRpuConversionResult.Success(rpuNalUnit + 0x44)
        }

    private companion object {
        const val MASTER_URI = "https://media.example/master.m3u8"
        const val VIDEO_PLAYLIST_URI = "https://media.example/video/main.m3u8"
        const val AUDIO_PLAYLIST_URI = "https://media.example/audio/en.m3u8"
        const val VIDEO_INIT_URI = "https://media.example/video/init.mp4"
        const val VIDEO_SEGMENT_URI = "https://media.example/video/segment-0.m4s"
        const val SECOND_VIDEO_SEGMENT_URI = "https://media.example/video/segment-1.m4s"
        const val AUDIO_INIT_URI = "https://media.example/audio/init.m4a"
        const val AUDIO_SEGMENT_URI = "https://media.example/audio/audio-0.m4s"
    }
}
