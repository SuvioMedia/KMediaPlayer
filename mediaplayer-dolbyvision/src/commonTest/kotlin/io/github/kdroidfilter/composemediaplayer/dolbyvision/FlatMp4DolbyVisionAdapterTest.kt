package io.github.kdroidfilter.composemediaplayer.dolbyvision

import io.github.kdroidfilter.composemediaplayer.DolbyVisionEnhancementLayer
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FlatMp4DolbyVisionAdapterTest {
    @Test
    fun `flat MP4 is fragmented lazily with PTS DTS audio and Profile 8 1 output preserved`() =
        runTest {
            val fixture = flatMp4()
            val reads = mutableListOf<Pair<Long, Int>>()
            val source =
                object : DolbyVisionRandomAccessDataSource {
                    override suspend fun size(): Long = fixture.bytes.size.toLong()

                    override suspend fun read(
                        offset: Long,
                        length: Int,
                    ): ByteArray {
                        reads += offset to length
                        return fixture.bytes.copyOfRange(offset.toInt(), offset.toInt() + length)
                    }
                }
            val converter =
                object : DolbyVisionRpuConverter {
                    override val isAvailable = true

                    override suspend fun convertProfile7To81(rpuNalUnit: ByteArray) =
                        DolbyVisionRpuConversionResult.Success(rpuNalUnit + 0x55)
                }
            val session =
                assertIs<FlatMp4DolbyVisionOpenResult.Success>(
                    FlatMp4DolbyVisionAdapter.open(
                        source = source,
                        converter = converter,
                        enhancementLayer = DolbyVisionEnhancementLayer.FEL,
                        targetFragmentDurationUs = 1,
                    ),
                ).session

            assertEquals(2, session.fragments.size)
            assertTrue(session.initializationSegment.containsFourCc("mvex"))
            assertTrue(session.initializationSegment.containsFourCc("dvvC"))
            assertTrue(reads.none { it.first == 0L && it.second == fixture.bytes.size })

            val first = assertIs<FlatMp4DolbyVisionFragmentResult.Success>(session.convertFragment(0))
            val second = assertIs<FlatMp4DolbyVisionFragmentResult.Success>(session.convertFragment(1))
            assertContentEquals(
                fixture.audioTracks.single()[0],
                first.payload.takeLast(fixture.audioTracks.single()[0].size).toByteArray(),
            )
            assertContentEquals(
                fixture.audioTracks.single()[1],
                second.payload.takeLast(fixture.audioTracks.single()[1].size).toByteArray(),
            )
            assertTrue(!first.payload.containsSubsequence(enhancementNal))
            assertEquals(1, session.restartFragmentIndexForSeek(45_000))
        }

    @Test
    fun `multiple MP4 audio tracks remain independently present after conversion`() =
        runTest {
            val alternateAudio = listOf(byteArrayOf(0x61, 0x62, 0x63), byteArrayOf(0x71, 0x72, 0x73, 0x74))
            val fixture = flatMp4(additionalAudioTracks = listOf(alternateAudio))
            val session =
                assertIs<FlatMp4DolbyVisionOpenResult.Success>(
                    FlatMp4DolbyVisionAdapter.open(
                        source = ByteArrayDolbyVisionDataSource(fixture.bytes),
                        converter = passthroughConverter(),
                        enhancementLayer = DolbyVisionEnhancementLayer.MEL,
                        targetFragmentDurationUs = 1,
                    ),
                ).session

            assertEquals(3, session.initializationSegment.countFourCc("trak"))
            assertEquals(2, session.initializationSegment.countFourCc("soun"))
            val first = assertIs<FlatMp4DolbyVisionFragmentResult.Success>(session.convertFragment(0))
            val second = assertIs<FlatMp4DolbyVisionFragmentResult.Success>(session.convertFragment(1))
            fixture.audioTracks.forEach { samples ->
                assertTrue(first.payload.containsSubsequence(samples[0]))
                assertTrue(second.payload.containsSubsequence(samples[1]))
            }
        }

    @Test
    fun `oversized fragment is rejected before media samples are read`() =
        runTest {
            val fixture = flatMp4()
            val reads = mutableListOf<Pair<Long, Int>>()
            val source =
                object : DolbyVisionRandomAccessDataSource {
                    override suspend fun size(): Long = fixture.bytes.size.toLong()

                    override suspend fun read(
                        offset: Long,
                        length: Int,
                    ): ByteArray {
                        reads += offset to length
                        return fixture.bytes.copyOfRange(offset.toInt(), offset.toInt() + length)
                    }
                }
            val session =
                assertIs<FlatMp4DolbyVisionOpenResult.Success>(
                    FlatMp4DolbyVisionAdapter.open(
                        source = source,
                        converter = unavailableConverter(),
                        enhancementLayer = DolbyVisionEnhancementLayer.MEL,
                        targetFragmentDurationUs = 1,
                        maximumFragmentBytes = 1,
                    ),
                ).session
            reads.clear()

            assertIs<FlatMp4DolbyVisionFragmentResult.Failure>(session.convertFragment(0))
            assertTrue(reads.isEmpty(), "Fragment payload must not be read after its declared size exceeds the limit.")
        }

    @Test
    fun `flat MP4 sample limit is global across tracks`() =
        runTest {
            val fixture = flatMp4()
            val failure =
                assertIs<FlatMp4DolbyVisionOpenResult.Failure>(
                    FlatMp4DolbyVisionAdapter.open(
                        source = ByteArrayDolbyVisionDataSource(fixture.bytes),
                        converter = unavailableConverter(),
                        enhancementLayer = DolbyVisionEnhancementLayer.MEL,
                        maximumSamples = 3,
                    ),
                )

            assertTrue(failure.message.contains("sample-count"))
        }

    @Test
    fun `fragmented or encrypted MP4 is rejected before conversion`() =
        runTest {
            val converter = unavailableConverter()
            val fragmented = box("ftyp", brand) + box("moof", ByteArray(0))
            val encrypted = box("ftyp", brand) + box("pssh", ByteArray(16)) + box("moov", ByteArray(0))

            assertIs<FlatMp4DolbyVisionOpenResult.Failure>(
                FlatMp4DolbyVisionAdapter.open(
                    ByteArrayDolbyVisionDataSource(fragmented),
                    converter,
                    DolbyVisionEnhancementLayer.MEL,
                ),
            )
            assertIs<FlatMp4DolbyVisionOpenResult.Failure>(
                FlatMp4DolbyVisionAdapter.open(
                    ByteArrayDolbyVisionDataSource(encrypted),
                    converter,
                    DolbyVisionEnhancementLayer.MEL,
                ),
            )
        }

    private fun flatMp4(additionalAudioTracks: List<List<ByteArray>> = emptyList()): Fixture {
        val videoSamples =
            listOf(
                lengthPrefixed(vps, byteArrayOf(0x7c, 0x01, 1), enhancementNal, slice),
                lengthPrefixed(vps, byteArrayOf(0x7c, 0x01, 2), enhancementNal, slice),
            )
        val audioTracks = listOf(listOf(byteArrayOf(1, 2, 3), byteArrayOf(4, 5, 6, 7))) + additionalAudioTracks
        require(audioTracks.all { it.size == videoSamples.size })
        val ftyp = box("ftyp", brand)
        val mediaPayload =
            videoSamples.fold(ByteArray(0), ByteArray::plus) +
                audioTracks.flatten().fold(ByteArray(0), ByteArray::plus)
        val mdat = box("mdat", mediaPayload)
        val videoOffset = ftyp.size + 8
        val videoTrack = track(1, "vide", videoSamples, videoOffset, includeDolbyVision = true)
        var audioOffset = videoOffset + videoSamples.sumOf(ByteArray::size)
        val audioTrackBoxes =
            audioTracks.mapIndexed { index, samples ->
                track(index + 2, "soun", samples, audioOffset, includeDolbyVision = false).also {
                    audioOffset += samples.sumOf(ByteArray::size)
                }
            }
        val mvhd =
            fullBox(
                "mvhd",
                ByteArray(20).also {
                    it.writeInt(8, 1_000)
                    it.writeInt(12, 80)
                },
            )
        val moov = box("moov", mvhd + videoTrack + audioTrackBoxes.fold(ByteArray(0), ByteArray::plus))
        return Fixture(ftyp + mdat + moov, audioTracks)
    }

    private fun track(
        trackId: Int,
        handler: String,
        samples: List<ByteArray>,
        chunkOffset: Int,
        includeDolbyVision: Boolean,
    ): ByteArray {
        val duration = samples.size * 40
        val tkhd =
            fullBox(
                "tkhd",
                ByteArray(28).also {
                    it.writeInt(8, trackId)
                    it.writeInt(16, duration)
                },
            )
        val mdhd =
            fullBox(
                "mdhd",
                ByteArray(20).also {
                    it.writeInt(8, 1_000)
                    it.writeInt(12, duration)
                },
            )
        val hdlr = fullBox("hdlr", ByteArray(12).also { handler.encodeToByteArray().copyInto(it, 4) })
        val sampleEntry =
            if (includeDolbyVision) {
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
                box("dvhe", ByteArray(78) + hvcc + dvcc)
            } else {
                box("mp4a", ByteArray(28))
            }
        val stsd = fullBox("stsd", uint32(1) + sampleEntry)
        val stts = fullBox("stts", uint32(1) + uint32(samples.size) + uint32(40))
        val ctts = fullBox("ctts", uint32(1) + uint32(samples.size) + uint32(0))
        val stsc = fullBox("stsc", uint32(1) + uint32(1) + uint32(samples.size) + uint32(1))
        val stsz =
            fullBox(
                "stsz",
                uint32(0) +
                    uint32(samples.size) +
                    samples.fold(ByteArray(0)) { all, sample -> all + uint32(sample.size) },
            )
        val stco = fullBox("stco", uint32(1) + uint32(chunkOffset))
        val stss =
            if (includeDolbyVision) {
                fullBox(
                    "stss",
                    uint32(samples.size) +
                        samples.indices.fold(ByteArray(0)) { all, index -> all + uint32(index + 1) },
                )
            } else {
                ByteArray(0)
            }
        val stbl = box("stbl", stsd + stts + ctts + stsc + stsz + stco + stss)
        val minf = box("minf", stbl)
        val mdia = box("mdia", mdhd + hdlr + minf)
        return box("trak", tkhd + mdia)
    }

    private fun unavailableConverter() =
        object : DolbyVisionRpuConverter {
            override val isAvailable = false

            override suspend fun convertProfile7To81(rpuNalUnit: ByteArray) =
                DolbyVisionRpuConversionResult.Unavailable("not used")
        }

    private fun passthroughConverter() =
        object : DolbyVisionRpuConverter {
            override val isAvailable = true

            override suspend fun convertProfile7To81(rpuNalUnit: ByteArray) =
                DolbyVisionRpuConversionResult.Success(rpuNalUnit)
        }

    private fun lengthPrefixed(vararg nals: ByteArray): ByteArray =
        nals.fold(ByteArray(0)) { all, nal -> all + uint32(nal.size) + nal }

    private fun fullBox(
        type: String,
        content: ByteArray,
    ): ByteArray = box(type, byteArrayOf(0, 0, 0, 0) + content)

    private fun box(
        type: String,
        content: ByteArray,
    ): ByteArray = uint32(content.size + 8) + type.encodeToByteArray() + content

    private fun uint32(value: Int): ByteArray =
        byteArrayOf((value ushr 24).toByte(), (value ushr 16).toByte(), (value ushr 8).toByte(), value.toByte())

    private fun ByteArray.writeInt(
        offset: Int,
        value: Int,
    ) = uint32(value).copyInto(this, offset)

    private fun ByteArray.containsFourCc(type: String): Boolean = containsSubsequence(type.encodeToByteArray())

    private fun ByteArray.countFourCc(type: String): Int {
        val needle = type.encodeToByteArray()
        return indices.count { index ->
            index <= size - needle.size && needle.indices.all { this[index + it] == needle[it] }
        }
    }

    private fun ByteArray.containsSubsequence(needle: ByteArray): Boolean {
        if (needle.isEmpty()) return true
        for (index in 0..size - needle.size) {
            if (needle.indices.all { this[index + it] == needle[it] }) return true
        }
        return false
    }

    private data class Fixture(
        val bytes: ByteArray,
        val audioTracks: List<List<ByteArray>>,
    )

    private val brand = "iso6".encodeToByteArray() + uint32(0) + "iso6mp41".encodeToByteArray()
    private val vps = byteArrayOf(0x40, 0x01, 1, 2)
    private val enhancementNal = byteArrayOf(0x7e, 0x01, 9, 8, 7)
    private val slice = byteArrayOf(0x26, 0x01, 3, 4, 5)
}
