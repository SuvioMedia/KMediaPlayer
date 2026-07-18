package io.github.kdroidfilter.composemediaplayer.dolbyvision

import io.github.kdroidfilter.composemediaplayer.DolbyVisionEnhancementLayer
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CmafDolbyVisionAdapterTest {
    @Test
    fun `initialization segment is inspected and rewritten from Profile 7 to 8 1`() {
        val result =
            assertIs<CmafDolbyVisionInitializationResult.Success>(
                CmafDolbyVisionInitializationSegment.prepareProfile81(initializationSegment()),
            )

        assertEquals(1, result.configuration.trackId)
        assertEquals(1_000L, result.configuration.timescale)
        assertEquals(4, result.configuration.nalLengthFieldBytes)
        assertEquals(7, result.configuration.sourceProfile)
        val rewritten = result.configuration.rewrittenInitializationSegment
        val dvvc = rewritten.indexOfFourCc("dvvC")
        assertTrue(dvvc > 0)
        val content = dvvc + 4
        val packed = ((rewritten[content + 2].toInt() and 0xff) shl 8) or (rewritten[content + 3].toInt() and 0xff)
        assertEquals(8, (packed ushr 9) and 0x7f)
        assertEquals(0, (packed ushr 1) and 1)
        assertEquals(1, packed and 1)
        assertEquals(1, (rewritten[content + 4].toInt() ushr 4) and 0x0f)
    }

    @Test
    fun `CMAF bridge preserves VFR timing and audio while replacing RPU and discarding FEL NALs`() =
        runTest {
            val configuration =
                assertIs<CmafDolbyVisionInitializationResult.Success>(
                    CmafDolbyVisionInitializationSegment.prepareProfile81(initializationSegment()),
                ).configuration
            val samples =
                listOf(
                    VideoSample(40, 5, hevcSample(byteArrayOf(0x7c, 0x01, 1))),
                    VideoSample(33, 3, hevcSample(byteArrayOf(0x7c, 0x01, 2))),
                )
            val audio = byteArrayOf(0x11, 0x22, 0x33, 0x44, 0x55)
            val media = mediaFragment(samples, audio)
            val demuxer = CmafDolbyVisionFragmentAdapter(configuration)
            val source = assertIs<CmafDolbyVisionDemuxResult.Success>(demuxer.demux(media)).fragment
            assertEquals(listOf(105_000L, 143_000L), source.rpus.map(TimedDolbyVisionRpu::presentationTimeUs))

            val bridge =
                DolbyVisionStreamingBridge(
                    request =
                        DolbyVisionConversionRequest(
                            container = DolbyVisionContainer.FRAGMENTED_MP4,
                            profile = 7,
                            hasRpu = true,
                            enhancementLayer = DolbyVisionEnhancementLayer.FEL,
                        ),
                    converter =
                        object : DolbyVisionRpuConverter {
                            override val isAvailable = true

                            override suspend fun convertProfile7To81(rpuNalUnit: ByteArray) =
                                DolbyVisionRpuConversionResult.Success(rpuNalUnit + byteArrayOf(0x55, 0x66))
                        },
                    remuxer = CmafDolbyVisionFragmentRemuxer(configuration),
                )
            val converted = assertIs<DolbyVisionFragmentConversionResult.Success>(bridge.convert(source)).value

            assertTrue(converted.timestampsAndAudioPreserved)
            assertEquals(source.startPresentationTimeUs, converted.fragment.startPresentationTimeUs)
            assertEquals(source.endPresentationTimeUs, converted.fragment.endPresentationTimeUs)
            assertContentEquals(
                audio,
                converted.fragment.payload.copyOfRange(
                    converted.fragment.payload.size - audio.size,
                    converted.fragment.payload.size,
                ),
            )
            assertTrue(!converted.fragment.payload.containsSubsequence(enhancementNal))

            val reparsed =
                assertIs<CmafDolbyVisionDemuxResult.Success>(
                    CmafDolbyVisionFragmentAdapter(configuration).demux(converted.fragment.payload),
                ).fragment
            assertEquals(listOf(105_000L, 143_000L), reparsed.rpus.map(TimedDolbyVisionRpu::presentationTimeUs))
            assertTrue(reparsed.rpus.all { it.nalUnit.takeLast(2) == listOf(0x55.toByte(), 0x66.toByte()) })
        }

    @Test
    fun `HLS VOD adapter resolves resources lazily and rejects live or encrypted playlists`() =
        runTest {
            val videoSamples = listOf(VideoSample(40, 0, hevcSample(byteArrayOf(0x7c, 0x01, 1))))
            val resources =
                mapOf(
                    "https://media.example/path/init.mp4" to initializationSegment(),
                    "https://media.example/path/s1.m4s" to mediaFragment(videoSamples, byteArrayOf(1, 2), 1, 0),
                    "https://media.example/path/s2.m4s" to mediaFragment(videoSamples, byteArrayOf(3, 4), 2, 40),
                )
            val reads = mutableListOf<String>()
            val source =
                DolbyVisionMediaDataSource { uri, _, maximumBytes ->
                    reads += uri
                    resources.getValue(uri).also { require(it.size <= maximumBytes) }
                }
            val playlist =
                """
                #EXTM3U
                #EXT-X-VERSION:7
                #EXT-X-TARGETDURATION:1
                #EXT-X-MEDIA-SEQUENCE:10
                #EXT-X-MAP:URI="init.mp4"
                #EXTINF:0.04,
                s1.m4s
                #EXTINF:0.04,
                s2.m4s
                #EXT-X-ENDLIST
                """.trimIndent()
            val converter =
                object : DolbyVisionRpuConverter {
                    override val isAvailable = true

                    override suspend fun convertProfile7To81(rpuNalUnit: ByteArray) =
                        DolbyVisionRpuConversionResult.Success(rpuNalUnit + 0x44)
                }

            val session =
                assertIs<HlsVodDolbyVisionOpenResult.Success>(
                    HlsVodDolbyVisionAdapter.open(
                        "https://media.example/path/master.m3u8",
                        playlist,
                        source,
                        converter,
                        DolbyVisionEnhancementLayer.MEL,
                    ),
                ).session

            assertEquals(listOf("https://media.example/path/init.mp4"), reads)
            assertIs<HlsVodDolbyVisionSegmentResult.Success>(session.convertSegment(0))
            assertIs<HlsVodDolbyVisionSegmentResult.Success>(session.convertSegment(1))
            assertEquals(1, session.restartSegmentIndexForSeek(60_000))
            assertTrue(session.renderMediaPlaylist("cmp-dovi://vod").contains("cmp-dovi://vod/segment/1.m4s"))
            assertEquals(
                listOf(
                    "https://media.example/path/init.mp4",
                    "https://media.example/path/s1.m4s",
                    "https://media.example/path/s2.m4s",
                ),
                reads,
            )

            val unavailableSource = DolbyVisionMediaDataSource { _, _, _ -> error("must not fetch") }
            assertIs<HlsVodDolbyVisionOpenResult.Failure>(
                HlsVodDolbyVisionAdapter.open(
                    "https://media.example/live.m3u8",
                    playlist.removeSuffix("#EXT-X-ENDLIST"),
                    unavailableSource,
                    converter,
                    DolbyVisionEnhancementLayer.MEL,
                ),
            )
            assertIs<HlsVodDolbyVisionOpenResult.Failure>(
                HlsVodDolbyVisionAdapter.open(
                    "https://media.example/drm.m3u8",
                    playlist.replace("#EXT-X-MAP", "#EXT-X-KEY:METHOD=SAMPLE-AES,URI=\"key\"\n#EXT-X-MAP"),
                    unavailableSource,
                    converter,
                    DolbyVisionEnhancementLayer.MEL,
                ),
            )
        }

    private fun initializationSegment(): ByteArray {
        val tkhd = fullBox("tkhd", ByteArray(20).also { it.writeTestInt(8, 1) })
        val mdhd = fullBox("mdhd", ByteArray(20).also { it.writeTestInt(8, 1_000) })
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
        val stbl = box("stbl", stsd)
        val minf = box("minf", stbl)
        val mdia = box("mdia", mdhd + hdlr + minf)
        return box("ftyp", "iso6".encodeToByteArray()) + box("moov", box("trak", tkhd + mdia))
    }

    private fun mediaFragment(
        videoSamples: List<VideoSample>,
        audio: ByteArray,
        sequence: Int = 1,
        baseDecodeTime: Int = 100,
    ): ByteArray {
        fun makeMoof(
            videoOffset: Int,
            audioOffset: Int,
        ): ByteArray {
            val mfhd = fullBox("mfhd", uint32(sequence))
            val videoTfhd = fullBox("tfhd", uint32(1), flags = 0x020000)
            val videoTfdt = fullBox("tfdt", uint32(baseDecodeTime))
            val videoEntries =
                videoSamples.fold(ByteArray(0)) { bytes, sample ->
                    bytes +
                        uint32(sample.duration) +
                        uint32(sample.payload.size) +
                        uint32(0) +
                        uint32(sample.compositionOffset)
                }
            val videoTrun =
                fullBox(
                    "trun",
                    uint32(videoSamples.size) + int32(videoOffset) + videoEntries,
                    flags = 0x000f01,
                )
            val audioTfhd = fullBox("tfhd", uint32(2), flags = 0x020000)
            val audioTfdt = fullBox("tfdt", uint32(0))
            val audioTrun =
                fullBox(
                    "trun",
                    uint32(1) + int32(audioOffset) + uint32(1_024) + uint32(audio.size) + uint32(0),
                    flags = 0x000701,
                )
            return box(
                "moof",
                mfhd +
                    box("traf", videoTfhd + videoTfdt + videoTrun) +
                    box("traf", audioTfhd + audioTfdt + audioTrun),
            )
        }

        val videoPayload = videoSamples.fold(ByteArray(0)) { bytes, sample -> bytes + sample.payload }
        val placeholder = makeMoof(0, 0)
        val videoOffset = placeholder.size + 8
        val moof = makeMoof(videoOffset, videoOffset + videoPayload.size)
        return moof + box("mdat", videoPayload + audio)
    }

    private fun hevcSample(rpu: ByteArray): ByteArray = lengthPrefixed(vps, rpu, enhancementNal, slice)

    private fun lengthPrefixed(vararg nals: ByteArray): ByteArray =
        nals.fold(ByteArray(0)) { bytes, nal -> bytes + uint32(nal.size) + nal }

    private fun fullBox(
        type: String,
        content: ByteArray,
        version: Int = 0,
        flags: Int = 0,
    ): ByteArray =
        box(
            type,
            byteArrayOf(version.toByte(), (flags ushr 16).toByte(), (flags ushr 8).toByte(), flags.toByte()) + content,
        )

    private fun box(
        type: String,
        content: ByteArray,
    ): ByteArray = uint32(content.size + 8) + type.encodeToByteArray() + content

    private fun uint32(value: Int): ByteArray =
        byteArrayOf((value ushr 24).toByte(), (value ushr 16).toByte(), (value ushr 8).toByte(), value.toByte())

    private fun int32(value: Int): ByteArray = uint32(value)

    private fun ByteArray.writeTestInt(
        offset: Int,
        value: Int,
    ) {
        uint32(value).copyInto(this, offset)
    }

    private fun ByteArray.indexOfFourCc(value: String): Int {
        val needle = value.encodeToByteArray()
        for (index in 0..size - needle.size) {
            if (needle.indices.all { this[index + it] == needle[it] }) return index
        }
        return -1
    }

    private fun ByteArray.containsSubsequence(needle: ByteArray): Boolean {
        if (needle.isEmpty()) return true
        for (index in 0..size - needle.size) {
            if (needle.indices.all { this[index + it] == needle[it] }) return true
        }
        return false
    }

    private data class VideoSample(
        val duration: Int,
        val compositionOffset: Int,
        val payload: ByteArray,
    )

    private val vps = byteArrayOf(0x40, 0x01, 1, 2)
    private val enhancementNal = byteArrayOf(0x7e, 0x01, 9, 8, 7)
    private val slice = byteArrayOf(0x26, 0x01, 3, 4, 5, 6)
}
