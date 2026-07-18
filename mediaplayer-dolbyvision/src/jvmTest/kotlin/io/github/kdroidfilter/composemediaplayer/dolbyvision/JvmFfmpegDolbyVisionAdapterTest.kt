package io.github.kdroidfilter.composemediaplayer.dolbyvision

import io.github.kdroidfilter.composemediaplayer.DolbyVisionEnhancementLayer
import kotlinx.coroutines.test.runTest
import java.io.ByteArrayInputStream
import java.io.InputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class JvmFfmpegDolbyVisionAdapterTest {
    @Test
    fun `streamed FFmpeg fMP4 output is converted lazily and seek restarts process state`() =
        runTest {
            val starts = mutableListOf<Long>()
            val factory =
                object : FfmpegProcessFactory {
                    override fun start(
                        request: JvmFfmpegDolbyVisionRequest,
                        seekTimeUs: Long,
                    ): FfmpegProcess {
                        starts += seekTimeUs
                        return FakeProcess(initializationSegment() + mediaFragment())
                    }
                }
            val converter =
                object : DolbyVisionRpuConverter {
                    override val isAvailable = true

                    override suspend fun convertProfile7To81(rpuNalUnit: ByteArray) =
                        DolbyVisionRpuConversionResult.Success(rpuNalUnit + 0x55)
                }
            val request =
                JvmFfmpegDolbyVisionRequest(
                    input = "movie.mkv",
                    container = DolbyVisionContainer.MATROSKA,
                    enhancementLayer = DolbyVisionEnhancementLayer.FEL,
                )
            val session =
                assertIs<JvmFfmpegDolbyVisionOpenResult.Success>(
                    JvmFfmpegDolbyVisionAdapter.openWithFactory(request, converter, factory),
                ).session

            assertTrue(session.initializationSegment.containsFourCc("dvvC"))
            val first = assertIs<JvmFfmpegDolbyVisionFragmentResult.Success>(session.nextFragment())
            assertTrue(!first.payload.containsSubsequence(enhancementNal))
            assertIs<JvmFfmpegDolbyVisionFragmentResult.EndOfStream>(session.nextFragment())
            assertIs<JvmFfmpegDolbyVisionOpenResult.Success>(session.seekTo(50_000))
            assertIs<JvmFfmpegDolbyVisionFragmentResult.Success>(session.nextFragment())
            assertEquals(listOf(0L, 50_000L), starts)
            session.close()
        }

    @Test
    fun `stream parser rejects oversized boxes before allocating payload`() {
        val declared = byteArrayOf(0x7f, 0xff.toByte(), 0xff.toByte(), 0xff.toByte()) + "moov".encodeToByteArray()
        val reader = StreamingIsoBmffReader(ByteArrayInputStream(declared), 1024, 1024)

        val failure = runCatching { reader.readInitialization() }.exceptionOrNull()

        assertTrue(failure?.message.orEmpty().contains("byte limit"))
    }

    private class FakeProcess(
        bytes: ByteArray,
    ) : FfmpegProcess {
        override val output: InputStream = ByteArrayInputStream(bytes)

        override fun awaitExit(): Int = 0

        override fun failureMessage(prefix: String): String = prefix

        override fun close() = output.close()
    }

    private fun initializationSegment(): ByteArray {
        val tkhd = fullBox("tkhd", ByteArray(20).also { it.writeInt(8, 1) })
        val mdhd = fullBox("mdhd", ByteArray(20).also { it.writeInt(8, 1_000) })
        val hdlr = fullBox("hdlr", ByteArray(12).also { "vide".encodeToByteArray().copyInto(it, 4) })
        val hvcc = box("hvcC", ByteArray(22).also { it[21] = 3 })
        val packed = (7 shl 9) or (6 shl 3) or 0b111
        val dvcc =
            box(
                "dvcC",
                ByteArray(24).also {
                    it[0] = 1
                    it[2] = (packed ushr 8).toByte()
                    it[3] = packed.toByte()
                    it[4] = 0x60
                },
            )
        val sampleEntry = box("dvhe", ByteArray(78) + hvcc + dvcc)
        val stsd = fullBox("stsd", uint32(1) + sampleEntry)
        val stbl =
            box(
                "stbl",
                stsd +
                    fullBox(
                        "stts",
                        uint32(0),
                    ) + fullBox("stsc", uint32(0)) + fullBox("stsz", uint32(0) + uint32(0)) +
                    fullBox("stco", uint32(0)),
            )
        val minf = box("minf", stbl)
        val mdia = box("mdia", mdhd + hdlr + minf)
        return box("ftyp", "iso6".encodeToByteArray()) + box("moov", box("trak", tkhd + mdia))
    }

    private fun mediaFragment(): ByteArray {
        val video = lengthPrefixed(vps, byteArrayOf(0x7c, 0x01, 1), enhancementNal, slice)

        fun moof(dataOffset: Int): ByteArray {
            val mfhd = fullBox("mfhd", uint32(1))
            val tfhd = fullBox("tfhd", uint32(1), flags = 0x020000)
            val tfdt = fullBox("tfdt", uint32(0))
            val trun =
                fullBox(
                    "trun",
                    uint32(1) + int32(dataOffset) + uint32(40) + uint32(video.size) + uint32(0) + uint32(0),
                    flags = 0x000f01,
                )
            return box("moof", mfhd + box("traf", tfhd + tfdt + trun))
        }

        val placeholder = moof(0)
        return moof(placeholder.size + 8) + box("mdat", video)
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

    private fun ByteArray.writeInt(
        offset: Int,
        value: Int,
    ) = uint32(value).copyInto(this, offset)

    private fun ByteArray.containsFourCc(type: String): Boolean = containsSubsequence(type.encodeToByteArray())

    private fun ByteArray.containsSubsequence(needle: ByteArray): Boolean {
        for (index in 0..size - needle.size) {
            if (needle.indices.all { this[index + it] == needle[it] }) return true
        }
        return false
    }

    private val vps = byteArrayOf(0x40, 0x01, 1, 2)
    private val enhancementNal = byteArrayOf(0x7e, 0x01, 9, 8, 7)
    private val slice = byteArrayOf(0x26, 0x01, 3, 4, 5)
}
