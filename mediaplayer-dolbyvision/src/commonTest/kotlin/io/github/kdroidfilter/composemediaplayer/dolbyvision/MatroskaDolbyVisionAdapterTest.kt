@file:Suppress("MagicNumber", "MaxLineLength", "TooManyFunctions")

package io.github.kdroidfilter.composemediaplayer.dolbyvision

import io.github.kdroidfilter.composemediaplayer.DolbyVisionEnhancementLayer
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class MatroskaDolbyVisionAdapterTest {
    @Test
    fun `common Matroska bridge preserves AAC and timestamps while converting RPU`() =
        runTest {
            val fixture = matroskaFixture()
            val convertedRpus = mutableListOf<ByteArray>()
            val opened =
                MatroskaDolbyVisionAdapter.open(
                    source = ByteArrayDolbyVisionDataSource(fixture.payload),
                    converter =
                        object : DolbyVisionRpuConverter {
                            override val isAvailable: Boolean = true

                            override suspend fun convertProfile7To81(
                                rpuNalUnit: ByteArray,
                            ): DolbyVisionRpuConversionResult {
                                convertedRpus += rpuNalUnit.copyOf()
                                return DolbyVisionRpuConversionResult.Success(rpuNalUnit)
                            }
                        },
                    enhancementLayer = DolbyVisionEnhancementLayer.MEL,
                    targetFragmentDurationUs = 1_000_000,
                )
            val session = assertIs<MatroskaDolbyVisionOpenResult.Success>(opened).session
            assertEquals(2, session.fragments.size)
            assertEquals(0, session.restartFragmentIndexForSeek(500_000))
            assertEquals(1, session.restartFragmentIndexForSeek(2_500_000))
            assertTrue(session.initializationSegment.indexOfAscii("dvvC") >= 0)
            assertTrue(session.initializationSegment.indexOfAscii("dvcC") < 0)

            val first = assertIs<MatroskaDolbyVisionFragmentResult.Success>(session.convertFragment(0))
            val second = assertIs<MatroskaDolbyVisionFragmentResult.Success>(session.convertFragment(1))
            assertTrue(first.payload.containsSlice(fixture.firstAudioSample))
            assertTrue(second.payload.containsSlice(fixture.secondAudioSample))
            assertEquals(
                listOf(0L, 2_000_000L),
                listOf(first.fragment.startPresentationTimeUs, second.fragment.startPresentationTimeUs),
            )
            assertEquals(2, convertedRpus.size)
            assertContentEquals(fixture.firstRpu, convertedRpus.first())

            assertEquals(1, session.resetForSeek(2_100_000))
            assertIs<MatroskaDolbyVisionFragmentResult.Success>(session.convertFragment(1))
        }

    @Test
    fun `unknown Segment and Cluster sizes plus fixed audio lacing are bounded and parsed`() =
        runTest {
            val fixture = matroskaFixture(unknownSizes = true, fixedAudioLacing = true)
            val opened =
                MatroskaDolbyVisionAdapter.open(
                    source = ByteArrayDolbyVisionDataSource(fixture.payload),
                    converter = passthroughConverter(),
                    enhancementLayer = DolbyVisionEnhancementLayer.MEL,
                    targetFragmentDurationUs = 1_000_000,
                )
            val session = assertIs<MatroskaDolbyVisionOpenResult.Success>(opened).session
            val first = assertIs<MatroskaDolbyVisionFragmentResult.Success>(session.convertFragment(0))
            assertTrue(first.payload.containsSlice(fixture.firstAudioSample))
            assertTrue(first.payload.containsSlice(fixture.secondLacedAudioSample))
        }

    @Test
    fun `unsupported audio and missing dvcC fail closed instead of dropping tracks`() =
        runTest {
            val unsupportedAudio = matroskaFixture(audioCodecId = "A_TRUEHD").payload
            val noDovi = matroskaFixture(includeDolbyMapping = false).payload

            val audioFailure =
                assertIs<MatroskaDolbyVisionOpenResult.Failure>(
                    MatroskaDolbyVisionAdapter.open(
                        ByteArrayDolbyVisionDataSource(unsupportedAudio),
                        passthroughConverter(),
                        DolbyVisionEnhancementLayer.FEL,
                    ),
                )
            assertTrue(audioFailure.message.contains("cannot be preserved"))
            val doviFailure =
                assertIs<MatroskaDolbyVisionOpenResult.Failure>(
                    MatroskaDolbyVisionAdapter.open(
                        ByteArrayDolbyVisionDataSource(noDovi),
                        passthroughConverter(),
                        DolbyVisionEnhancementLayer.MEL,
                    ),
                )
            assertTrue(doviFailure.message.contains("dvcC"))
        }

    @Test
    fun `Matroska Opus AC3 and EAC3 survive the complete CMAF bridge`() =
        runTest {
            listOf("A_OPUS" to "dOps", "A_AC3" to "dac3", "A_EAC3" to "dec3")
                .forEach { (codecId, configurationBox) ->
                    val fixture = matroskaFixture(audioCodecId = codecId)
                    val opened =
                        MatroskaDolbyVisionAdapter.open(
                            source = ByteArrayDolbyVisionDataSource(fixture.payload),
                            converter = passthroughConverter(),
                            enhancementLayer = DolbyVisionEnhancementLayer.MEL,
                            targetFragmentDurationUs = 1_000_000,
                        )
                    val session = assertIs<MatroskaDolbyVisionOpenResult.Success>(opened).session
                    assertTrue(session.initializationSegment.indexOfAscii(configurationBox) >= 0)

                    val fragment = assertIs<MatroskaDolbyVisionFragmentResult.Success>(session.convertFragment(0))
                    assertTrue(fragment.payload.containsSlice(fixture.firstAudioSample))
                }
        }

    @Test
    fun `multiple Matroska audio tracks remain independently present after conversion`() =
        runTest {
            val fixture = matroskaFixture(includeSecondAudioTrack = true)
            val session =
                assertIs<MatroskaDolbyVisionOpenResult.Success>(
                    MatroskaDolbyVisionAdapter.open(
                        source = ByteArrayDolbyVisionDataSource(fixture.payload),
                        converter = passthroughConverter(),
                        enhancementLayer = DolbyVisionEnhancementLayer.MEL,
                        targetFragmentDurationUs = 1_000_000,
                    ),
                ).session

            assertEquals(2, session.initializationSegment.countAscii("soun"))
            assertEquals(listOf(0, 1, 1), session.initializationSegment.trackAlternateGroups())
            val first = assertIs<MatroskaDolbyVisionFragmentResult.Success>(session.convertFragment(0))
            val second = assertIs<MatroskaDolbyVisionFragmentResult.Success>(session.convertFragment(1))
            assertTrue(first.payload.containsSlice(requireNotNull(fixture.firstAlternateAudioSample)))
            assertTrue(second.payload.containsSlice(requireNotNull(fixture.secondAlternateAudioSample)))
            assertTrue(first.payload.containsSlice(fixture.firstAudioSample))
            assertTrue(second.payload.containsSlice(fixture.secondAudioSample))
        }

    @Test
    fun `sample and fragment limits reject before unbounded allocation`() =
        runTest {
            val fixture = matroskaFixture()
            val sampleFailure =
                assertIs<MatroskaDolbyVisionOpenResult.Failure>(
                    MatroskaDolbyVisionAdapter.open(
                        source = ByteArrayDolbyVisionDataSource(fixture.payload),
                        converter = passthroughConverter(),
                        enhancementLayer = DolbyVisionEnhancementLayer.MEL,
                        maximumSamples = 2,
                    ),
                )
            assertTrue(sampleFailure.message.contains("sample-count"))

            val countedSource = CountingMatroskaDataSource(fixture.payload)
            val session =
                assertIs<MatroskaDolbyVisionOpenResult.Success>(
                    MatroskaDolbyVisionAdapter.open(
                        source = countedSource,
                        converter = passthroughConverter(),
                        enhancementLayer = DolbyVisionEnhancementLayer.MEL,
                        maximumFragmentBytes = 64,
                    ),
                ).session
            val readsAfterIndexing = countedSource.readCount
            assertIs<MatroskaDolbyVisionFragmentResult.Failure>(session.convertFragment(0))
            assertEquals(readsAfterIndexing, countedSource.readCount)
        }

    private fun passthroughConverter() =
        object : DolbyVisionRpuConverter {
            override val isAvailable: Boolean = true

            override suspend fun convertProfile7To81(rpuNalUnit: ByteArray) =
                DolbyVisionRpuConversionResult.Success(rpuNalUnit)
        }
}

private class CountingMatroskaDataSource(
    bytes: ByteArray,
) : DolbyVisionRandomAccessDataSource {
    private val delegate = ByteArrayDolbyVisionDataSource(bytes)
    var readCount: Int = 0
        private set

    override suspend fun size(): Long = delegate.size()

    override suspend fun read(
        offset: Long,
        length: Int,
    ): ByteArray {
        readCount++
        return delegate.read(offset, length)
    }
}

private data class MatroskaFixture(
    val payload: ByteArray,
    val firstRpu: ByteArray,
    val firstAudioSample: ByteArray,
    val secondAudioSample: ByteArray,
    val secondLacedAudioSample: ByteArray,
    val firstAlternateAudioSample: ByteArray?,
    val secondAlternateAudioSample: ByteArray?,
)

private fun matroskaFixture(
    unknownSizes: Boolean = false,
    fixedAudioLacing: Boolean = false,
    audioCodecId: String = "A_AAC",
    includeDolbyMapping: Boolean = true,
    includeSecondAudioTrack: Boolean = false,
): MatroskaFixture {
    val rpu1 = nal(62, byteArrayOf(1, 2, 3))
    val rpu2 = nal(62, byteArrayOf(4, 5, 6))
    val video1 = lengthPrefixed(nal(19, byteArrayOf(9, 9)), rpu1)
    val video2 = lengthPrefixed(nal(19, byteArrayOf(8, 8)), rpu2)
    val (audio1, audio2, audioLaced2) = matroskaAudioSamples(audioCodecId)
    val ebmlHeader =
        ebmlMaster(
            0x1A45DFA3,
            ebmlUnsigned(0x4286, 1) +
                ebmlUnsigned(0x42F7, 1) +
                ebmlUnsigned(0x42F2, 4) +
                ebmlUnsigned(0x42F3, 8) +
                ebmlString(0x4282, "matroska") +
                ebmlUnsigned(0x4287, 4) +
                ebmlUnsigned(0x4285, 2),
        )
    val info = ebmlMaster(0x1549A966, ebmlUnsigned(0x2AD7B1, 1_000_000))
    val hvcc =
        ByteArray(22).also {
            it[0] = 1
            it[21] = 3
        }
    val doviWord = (7 shl 9) or (6 shl 3) or 0b111
    val dovi =
        ByteArray(24).also {
            it[0] = 1
            it[2] = (doviWord ushr 8).toByte()
            it[3] = doviWord.toByte()
        }
    val mapping =
        if (includeDolbyMapping) {
            ebmlMaster(
                0x41E4,
                ebmlUnsigned(0x41F0, 2) +
                    ebmlUnsigned(0x41E7, 0x64766343) +
                    ebmlBinary(0x41ED, dovi),
            )
        } else {
            ByteArray(0)
        }
    val videoTrack =
        ebmlMaster(
            0xAE,
            ebmlUnsigned(0xD7, 1) +
                ebmlUnsigned(0x73C5, 101) +
                ebmlUnsigned(0x83, 1) +
                ebmlUnsigned(0x23E383, 1_000_000_000) +
                ebmlString(0x86, "V_MPEGH/ISO/HEVC") +
                ebmlBinary(0x63A2, hvcc) +
                mapping +
                ebmlMaster(
                    0xE0,
                    ebmlUnsigned(0xB0, 1920) +
                        ebmlUnsigned(0xBA, 1080) +
                        ebmlMaster(
                            0x55B0,
                            ebmlUnsigned(0x55B1, 9) +
                                ebmlUnsigned(0x55B9, 1) +
                                ebmlUnsigned(0x55BA, 16) +
                                ebmlUnsigned(0x55BB, 9) +
                                ebmlUnsigned(0x55BC, 1_000) +
                                ebmlUnsigned(0x55BD, 400),
                        ),
                ),
        )
    val audioDefaultDurationNs =
        if (audioCodecId == "A_AC3" || audioCodecId == "A_EAC3") 32_000_000L else 21_333_333L
    val audioCodecPrivate =
        when (audioCodecId) {
            "A_AAC" -> ebmlBinary(0x63A2, byteArrayOf(0x11, 0x90.toByte()))
            "A_OPUS" -> ebmlBinary(0x63A2, matroskaOpusHead())
            else -> ByteArray(0)
        }

    fun audioTrack(
        trackNumber: Int,
        trackUid: Int,
        language: String,
    ): ByteArray =
        ebmlMaster(
            0xAE,
            ebmlUnsigned(0xD7, trackNumber.toLong()) +
                ebmlUnsigned(0x73C5, trackUid.toLong()) +
                ebmlUnsigned(0x83, 2) +
                ebmlUnsigned(0x23E383, audioDefaultDurationNs) +
                ebmlString(0x86, audioCodecId) +
                ebmlString(0x22B59C, language) +
                audioCodecPrivate +
                ebmlMaster(
                    0xE1,
                    ebmlFloat(0xB5, 48_000.0) + ebmlUnsigned(0x9F, 2) + ebmlUnsigned(0x6264, 16),
                ),
        )
    val tracks =
        ebmlMaster(
            0x1654AE6B,
            videoTrack +
                audioTrack(2, 102, "eng") +
                if (includeSecondAudioTrack) audioTrack(3, 103, "pol") else ByteArray(0),
        )
    val alternateAudio1 = if (includeSecondAudioTrack) byteArrayOf(0x61, 0x62, 0x63) else null
    val alternateAudio2 = if (includeSecondAudioTrack) byteArrayOf(0x71, 0x72, 0x73, 0x74) else null
    val firstAudioBlock =
        if (fixedAudioLacing) {
            simpleBlockFixedLace(2, 0, listOf(audio1, audioLaced2))
        } else {
            simpleBlock(2, 0, keyframe = true, audio1)
        }
    val firstClusterContent =
        ebmlUnsigned(0xE7, 0) +
            simpleBlock(1, 0, keyframe = true, video1) +
            firstAudioBlock +
            (alternateAudio1?.let { simpleBlock(3, 0, keyframe = true, it) } ?: ByteArray(0))
    val secondClusterContent =
        ebmlUnsigned(0xE7, 2_000) + simpleBlock(1, 0, keyframe = true, video2) +
            simpleBlock(2, 0, keyframe = true, audio2) +
            (alternateAudio2?.let { simpleBlock(3, 0, keyframe = true, it) } ?: ByteArray(0))
    val firstCluster = ebmlMaster(0x1F43B675, firstClusterContent, unknownSize = unknownSizes)
    val secondCluster = ebmlMaster(0x1F43B675, secondClusterContent, unknownSize = unknownSizes)
    val segment = ebmlMaster(0x18538067, info + tracks + firstCluster + secondCluster, unknownSize = unknownSizes)
    return MatroskaFixture(
        payload = ebmlHeader + segment,
        firstRpu = rpu1,
        firstAudioSample = audio1,
        secondAudioSample = audio2,
        secondLacedAudioSample = audioLaced2,
        firstAlternateAudioSample = alternateAudio1,
        secondAlternateAudioSample = alternateAudio2,
    )
}

private fun matroskaAudioSamples(codecId: String): Triple<ByteArray, ByteArray, ByteArray> {
    val first =
        when (codecId) {
            "A_AC3" -> matroskaPaddedFrame(768, "0b775052144043e106f46370808082101010415c7cf9f3e7")
            "A_EAC3" -> matroskaPaddedFrame(768, "0b77017f3487c0002000000045008c0404040101010063e7")
            "A_OPUS" -> byteArrayOf(0x98.toByte(), 0x11, 0x22)
            else -> byteArrayOf(0x11, 0x22, 0x33)
        }
    val second = first.copyOf().also { it[it.lastIndex] = 0x44 }
    val laced = first.copyOf().also { it[it.lastIndex] = 0x77 }
    return Triple(first, second, laced)
}

private fun matroskaOpusHead(): ByteArray =
    "OpusHead".encodeToByteArray() +
        byteArrayOf(
            1,
            2,
            0x38,
            0x01,
            0x80.toByte(),
            0xbb.toByte(),
            0,
            0,
            0,
            0,
            0,
        )

private fun matroskaPaddedFrame(
    size: Int,
    headerHex: String,
): ByteArray =
    ByteArray(size).also { output ->
        matroskaDecodeHex(headerHex).copyInto(output)
    }

private fun matroskaDecodeHex(value: String): ByteArray {
    require(value.length % 2 == 0)
    return ByteArray(value.length / 2) { index ->
        value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }
}

private fun simpleBlock(
    track: Int,
    timestamp: Int,
    keyframe: Boolean,
    payload: ByteArray,
): ByteArray {
    val flags = if (keyframe) 0x80 else 0
    return ebmlBinary(
        0xA3,
        byteArrayOf((0x80 or track).toByte(), (timestamp shr 8).toByte(), timestamp.toByte(), flags.toByte()) + payload,
    )
}

private fun simpleBlockFixedLace(
    track: Int,
    timestamp: Int,
    frames: List<ByteArray>,
): ByteArray {
    require(frames.size > 1 && frames.map(ByteArray::size).distinct().size == 1)
    return ebmlBinary(
        0xA3,
        byteArrayOf(
            (0x80 or track).toByte(),
            (timestamp shr 8).toByte(),
            timestamp.toByte(),
            (0x80 or 0x04).toByte(),
            (frames.size - 1).toByte(),
        ) + frames.fold(ByteArray(0), ByteArray::plus),
    )
}

private fun nal(
    type: Int,
    payload: ByteArray,
): ByteArray = byteArrayOf((type shl 1).toByte(), 1) + payload

private fun lengthPrefixed(vararg nals: ByteArray): ByteArray =
    nals.fold(ByteArray(0)) { output, nal -> output + uint32(nal.size) + nal }

private fun ebmlMaster(
    id: Long,
    content: ByteArray,
    unknownSize: Boolean = false,
): ByteArray =
    ebmlId(id) +
        (if (unknownSize) byteArrayOf(0x01, -1, -1, -1, -1, -1, -1, -1) else ebmlSize(content.size)) +
        content

private fun ebmlBinary(
    id: Long,
    content: ByteArray,
): ByteArray = ebmlId(id) + ebmlSize(content.size) + content

private fun ebmlString(
    id: Long,
    value: String,
): ByteArray = ebmlBinary(id, value.encodeToByteArray())

private fun ebmlUnsigned(
    id: Long,
    value: Long,
): ByteArray {
    require(value >= 0)
    val bytes =
        (1..8).firstNotNullOf { length ->
            val maximum = if (length == 8) Long.MAX_VALUE else (1L shl (length * 8)) - 1
            if (value <= maximum) {
                ByteArray(length) { index -> (value ushr ((length - index - 1) * 8)).toByte() }
            } else {
                null
            }
        }
    return ebmlBinary(id, bytes)
}

private fun ebmlFloat(
    id: Long,
    value: Double,
): ByteArray {
    val bits = value.toBits()
    return ebmlBinary(id, ByteArray(8) { index -> (bits ushr ((7 - index) * 8)).toByte() })
}

private fun ebmlId(id: Long): ByteArray {
    require(id > 0)
    val length = (1..4).first { id ushr (it * 8) == 0L }
    return ByteArray(length) { index -> (id ushr ((length - index - 1) * 8)).toByte() }
}

private fun ebmlSize(size: Int): ByteArray {
    require(size >= 0)
    val length = (1..8).first { bits -> bits == 8 || size.toLong() < (1L shl (bits * 7)) - 1 }
    val bytes = ByteArray(length)
    var value = size.toLong()
    for (index in length - 1 downTo 0) {
        bytes[index] = value.toByte()
        value = value ushr 8
    }
    bytes[0] = (bytes[0].toInt() or (1 shl (8 - length))).toByte()
    return bytes
}

private fun uint32(value: Int): ByteArray =
    byteArrayOf((value ushr 24).toByte(), (value ushr 16).toByte(), (value ushr 8).toByte(), value.toByte())

private fun ByteArray.containsSlice(needle: ByteArray): Boolean =
    indices.any { index -> index <= size - needle.size && needle.indices.all { this[index + it] == needle[it] } }

private fun ByteArray.indexOfAscii(value: String): Int {
    val bytes = value.encodeToByteArray()
    return indices.firstOrNull { index ->
        index <= size - bytes.size && bytes.indices.all { this[index + it] == bytes[it] }
    } ?: -1
}

private fun ByteArray.countAscii(value: String): Int {
    val bytes = value.encodeToByteArray()
    return indices.count { index ->
        index <= size - bytes.size && bytes.indices.all { this[index + it] == bytes[it] }
    }
}

private fun ByteArray.trackAlternateGroups(): List<Int> {
    fun boxes(
        start: Int = 0,
        end: Int = size,
    ): List<IsoBmffBox> =
        when (val parsed = parseIsoBmffBoxes(start, end)) {
            is IsoBmffParseResult.Success -> parsed.boxes
            is IsoBmffParseResult.Failure -> error(parsed.message)
        }

    val moov = boxes().single { it.type == "moov" }
    return boxes(moov.contentOffset, moov.endOffset)
        .filter { it.type == "trak" }
        .map { track ->
            val tkhd = boxes(track.contentOffset, track.endOffset).single { it.type == "tkhd" }
            val version = this[tkhd.contentOffset].toInt() and 0xff
            val offset = tkhd.contentOffset + if (version == 1) 46 else 34
            ((this[offset].toInt() and 0xff) shl 8) or (this[offset + 1].toInt() and 0xff)
        }
}
