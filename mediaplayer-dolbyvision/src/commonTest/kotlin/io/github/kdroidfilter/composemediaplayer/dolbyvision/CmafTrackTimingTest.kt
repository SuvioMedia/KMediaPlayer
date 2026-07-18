package io.github.kdroidfilter.composemediaplayer.dolbyvision

import io.github.kdroidfilter.composemediaplayer.DolbyVisionEnhancementLayer
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CmafTrackTimingTest {
    @Test
    fun `audio track clock and raw fMP4 timestamps are read without decoding`() {
        val configuration = assertNotNull(audioInitializationSegment().readCmafTrackTimingConfiguration("soun"))
        assertEquals(2, configuration.trackId)
        assertEquals(48_000L, configuration.timescale)

        val timing = assertNotNull(audioFragment(baseDecodeTime = 96_000).readCmafTrackFragmentTiming(configuration))
        assertEquals(2_000_000L, timing.firstDecodeTimeUs)
        assertEquals(2_000_000L, timing.firstPresentationTimeUs)
        assertTrue(timing.startsWithSyncSample)
    }

    @Test
    fun `HLS seek scans backwards to a verified random access segment`() =
        runTest {
            val playlist =
                """
                #EXTM3U
                #EXT-X-MAP:URI="init.mp4"
                #EXTINF:0.04,
                segment-0.m4s
                #EXTINF:0.04,
                segment-1.m4s
                #EXTINF:0.04,
                segment-2.m4s
                #EXT-X-ENDLIST
                """.trimIndent()
            val resources =
                mapOf(
                    "https://media.example/init.mp4" to videoInitializationSegment(),
                    "https://media.example/segment-0.m4s" to videoFragment(1, 0, isSync = true),
                    "https://media.example/segment-1.m4s" to videoFragment(2, 40, isSync = false),
                    "https://media.example/segment-2.m4s" to videoFragment(3, 80, isSync = false),
                )
            val reads = mutableListOf<String>()
            val session =
                assertIs<HlsVodDolbyVisionOpenResult.Success>(
                    HlsVodDolbyVisionAdapter.open(
                        playlistUri = "https://media.example/media.m3u8",
                        playlist = playlist,
                        dataSource =
                            DolbyVisionMediaDataSource { uri, _, maximumBytes ->
                                reads += uri
                                resources.getValue(uri).also { require(it.size <= maximumBytes) }
                            },
                        converter = passthroughConverter,
                        enhancementLayer = DolbyVisionEnhancementLayer.MEL,
                    ),
                ).session

            assertEquals(0, session.resetForSeek(100_000))
            assertEquals(
                listOf(
                    "https://media.example/segment-2.m4s",
                    "https://media.example/segment-1.m4s",
                    "https://media.example/segment-0.m4s",
                ),
                reads.drop(1),
            )
        }

    private fun videoInitializationSegment(): ByteArray {
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
        return initializationSegment(
            trackId = 1,
            timescale = 1_000,
            handler = "vide",
            sampleEntry = box("dvhe", ByteArray(78) + hvcc + dvcc),
        )
    }

    private fun audioInitializationSegment(): ByteArray =
        initializationSegment(
            trackId = 2,
            timescale = 48_000,
            handler = "soun",
            sampleEntry = box("mp4a", ByteArray(28)),
        )

    private fun initializationSegment(
        trackId: Int,
        timescale: Int,
        handler: String,
        sampleEntry: ByteArray,
    ): ByteArray {
        val tkhd = fullBox("tkhd", ByteArray(20).also { uint32(trackId).copyInto(it, 8) })
        val mdhd = fullBox("mdhd", ByteArray(20).also { uint32(timescale).copyInto(it, 8) })
        val hdlr = fullBox("hdlr", ByteArray(12).also { handler.encodeToByteArray().copyInto(it, 4) })
        val stsd = fullBox("stsd", uint32(1) + sampleEntry)
        val mdia = box("mdia", mdhd + hdlr + box("minf", box("stbl", stsd)))
        return box("ftyp", "iso6".encodeToByteArray()) + box("moov", box("trak", tkhd + mdia))
    }

    private fun videoFragment(
        sequence: Int,
        baseDecodeTime: Int,
        isSync: Boolean,
    ): ByteArray {
        val sample = lengthPrefixed(byteArrayOf(0x40, 0x01, 1), byteArrayOf(0x7c, 0x01, 2), byteArrayOf(0x26, 1, 3))
        return mediaFragment(
            trackId = 1,
            sequence = sequence,
            baseDecodeTime = baseDecodeTime,
            duration = 40,
            sample = sample,
            sampleFlags = if (isSync) 0 else 0x0001_0000,
        )
    }

    private fun audioFragment(baseDecodeTime: Int): ByteArray =
        mediaFragment(
            trackId = 2,
            sequence = 1,
            baseDecodeTime = baseDecodeTime,
            duration = 1_024,
            sample = byteArrayOf(1, 2, 3, 4),
            sampleFlags = 0,
        )

    private fun mediaFragment(
        trackId: Int,
        sequence: Int,
        baseDecodeTime: Int,
        duration: Int,
        sample: ByteArray,
        sampleFlags: Int,
    ): ByteArray {
        fun moof(dataOffset: Int): ByteArray {
            val tfhd = fullBox("tfhd", uint32(trackId), flags = 0x020000)
            val tfdt = fullBox("tfdt", uint32(baseDecodeTime))
            val trun =
                fullBox(
                    "trun",
                    uint32(1) + int32(dataOffset) + uint32(duration) + uint32(sample.size) + uint32(sampleFlags),
                    flags = 0x000701,
                )
            return box("moof", fullBox("mfhd", uint32(sequence)) + box("traf", tfhd + tfdt + trun))
        }
        val placeholder = moof(0)
        return moof(placeholder.size + 8) + box("mdat", sample)
    }

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

    private val passthroughConverter =
        object : DolbyVisionRpuConverter {
            override val isAvailable: Boolean = true

            override suspend fun convertProfile7To81(rpuNalUnit: ByteArray): DolbyVisionRpuConversionResult =
                DolbyVisionRpuConversionResult.Success(rpuNalUnit)
        }
}
