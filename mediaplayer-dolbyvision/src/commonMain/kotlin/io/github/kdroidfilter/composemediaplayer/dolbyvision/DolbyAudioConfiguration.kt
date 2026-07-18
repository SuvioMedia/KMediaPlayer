@file:Suppress("CyclomaticComplexMethod", "MagicNumber", "ReturnCount")

package io.github.kdroidfilter.composemediaplayer.dolbyvision

import kotlin.math.roundToInt

internal data class DolbyAudioConfiguration(
    val sampleRate: Int,
    val channelMode: Int,
    val lfeOn: Int,
    val durationNs: Long,
    val codecBoxPayload: ByteArray,
)

internal fun parseDolbyAudioPacketConfiguration(
    packet: ByteArray,
    eac3: Boolean,
): DolbyAudioConfiguration = if (eac3) parseEac3PacketConfiguration(packet) else parseAc3PacketConfiguration(packet)

private fun parseAc3PacketConfiguration(packet: ByteArray): DolbyAudioConfiguration {
    val header = parseAc3Header(packet, 0)
    require(header.frameSize <= packet.size) { "The Matroska AC-3 syncframe is truncated." }
    val writer = BitWriter(3)
    writer.write(header.fscod, 2)
    writer.write(header.bsid, 5)
    writer.write(header.bsmod, 3)
    writer.write(header.acmod, 3)
    writer.write(header.lfeOn, 1)
    writer.write(header.bitRateCode, 5)
    writer.write(0, 5)
    return DolbyAudioConfiguration(
        sampleRate = header.sampleRate,
        channelMode = header.acmod,
        lfeOn = header.lfeOn,
        durationNs = AC3_SAMPLES_PER_FRAME * NANOSECONDS_PER_SECOND / header.sampleRate,
        codecBoxPayload = writer.finish(),
    )
}

private fun parseEac3PacketConfiguration(packet: ByteArray): DolbyAudioConfiguration {
    require(packet.isNotEmpty()) { "The Matroska E-AC-3 packet is empty." }
    var offset = 0
    var independent: DolbyAudioHeader? = null
    var independentBlocks = 0
    var dependentCount = 0
    var channelLocation = 0
    var packetBitRate = 0.0
    while (offset < packet.size) {
        val header = parseEac3Header(packet, offset)
        require(header.frameSize <= packet.size - offset) { "The Matroska E-AC-3 syncframe is truncated." }
        val durationSamples = header.numBlocks * EAC3_SAMPLES_PER_BLOCK
        packetBitRate += header.frameSize.toDouble() * 8.0 * header.sampleRate / durationSamples
        when (header.streamType) {
            EAC3_STREAM_TYPE_INDEPENDENT, EAC3_STREAM_TYPE_AC3_CONVERT -> {
                require(header.substreamId == 0) { "Multiple E-AC-3 independent substreams are unsupported." }
                require(independent == null) { "A Matroska E-AC-3 packet contains multiple independent syncframes." }
                independent = header
                independentBlocks += header.numBlocks
            }
            EAC3_STREAM_TYPE_DEPENDENT -> {
                require(independent != null && header.substreamId == independent.substreamId) {
                    "An E-AC-3 dependent syncframe has no matching independent syncframe."
                }
                dependentCount++
                channelLocation = channelLocation or (header.channelMap?.ushr(5)?.and(0x1ff) ?: header.acmod)
            }
            else -> error("A reserved E-AC-3 stream type is unsupported.")
        }
        offset += header.frameSize
    }
    require(offset == packet.size) { "The Matroska E-AC-3 packet has trailing bytes." }
    val primary = independent ?: error("The Matroska E-AC-3 packet has no independent syncframe.")
    require(independentBlocks == EAC3_BLOCKS_PER_MP4_SAMPLE) {
        "An E-AC-3 CMAF sample must contain exactly six audio blocks; found $independentBlocks."
    }
    require(dependentCount <= 15) { "An E-AC-3 packet contains too many dependent substreams." }
    val dataRateKbps = (packetBitRate / 1_000.0).roundToInt().coerceAtLeast(1)
    require(dataRateKbps <= 0x1fff) { "The E-AC-3 data rate exceeds the dec3 field." }
    val writer = BitWriter(if (dependentCount == 0) 5 else 6)
    writer.write(dataRateKbps, 13)
    writer.write(0, 3) // num_ind_sub minus one: one independent substream
    writer.write(primary.fscod, 2)
    writer.write(primary.bsid, 5)
    writer.write(0, 1)
    writer.write(0, 1) // asvc
    writer.write(primary.bsmod, 3)
    writer.write(primary.acmod, 3)
    writer.write(primary.lfeOn, 1)
    writer.write(0, 3)
    writer.write(dependentCount, 4)
    if (dependentCount == 0) {
        writer.write(0, 1)
    } else {
        writer.write(channelLocation and 0x1ff, 9)
    }
    return DolbyAudioConfiguration(
        sampleRate = primary.sampleRate,
        channelMode = primary.acmod,
        lfeOn = primary.lfeOn,
        durationNs = AC3_SAMPLES_PER_FRAME * NANOSECONDS_PER_SECOND / primary.sampleRate,
        codecBoxPayload = writer.finish(),
    )
}

private data class DolbyAudioHeader(
    val streamType: Int,
    val substreamId: Int,
    val frameSize: Int,
    val fscod: Int,
    val sampleRate: Int,
    val numBlocks: Int,
    val bsid: Int,
    val bsmod: Int,
    val acmod: Int,
    val lfeOn: Int,
    val bitRateCode: Int,
    val channelMap: Int?,
)

private fun parseAc3Header(
    bytes: ByteArray,
    offset: Int,
): DolbyAudioHeader {
    val bits = BitReader(bytes, offset)
    require(bits.read(16) == AC3_SYNCWORD) { "A Matroska AC-3 frame has no syncword." }
    bits.skip(16) // crc1
    val fscod = bits.read(2)
    require(fscod < 3) { "An AC-3 frame has a reserved sample-rate code." }
    val frameSizeCode = bits.read(6)
    require(frameSizeCode < 38) { "An AC-3 frame has an invalid frame-size code." }
    val bsid = bits.read(5)
    require(bsid <= 8) { "The AC-3 bitstream id is not valid for ISO BMFF." }
    val bsmod = bits.read(3)
    val acmod = bits.read(3)
    if (acmod and 0x01 != 0 && acmod != 1) bits.skip(2)
    if (acmod and 0x04 != 0) bits.skip(2)
    if (acmod == 2) bits.skip(2)
    val lfeOn = bits.read(1)
    val bitRateCode = frameSizeCode ushr 1
    val frameSize = AC3_FRAME_SIZE_WORDS[fscod][frameSizeCode] * 2
    return DolbyAudioHeader(
        streamType = EAC3_STREAM_TYPE_AC3_CONVERT,
        substreamId = 0,
        frameSize = frameSize,
        fscod = fscod,
        sampleRate = AC3_SAMPLE_RATES[fscod],
        numBlocks = EAC3_BLOCKS_PER_MP4_SAMPLE,
        bsid = bsid,
        bsmod = bsmod,
        acmod = acmod,
        lfeOn = lfeOn,
        bitRateCode = bitRateCode,
        channelMap = null,
    )
}

private fun parseEac3Header(
    bytes: ByteArray,
    offset: Int,
): DolbyAudioHeader {
    val bits = BitReader(bytes, offset)
    require(bits.read(16) == AC3_SYNCWORD) { "A Matroska E-AC-3 frame has no syncword." }
    val streamType = bits.read(2)
    val substreamId = bits.read(3)
    val frameSize = (bits.read(11) + 1) * 2
    val fscod = bits.read(2)
    val sampleRate: Int
    val numBlocks: Int
    if (fscod == 3) {
        val fscod2 = bits.read(2)
        require(fscod2 < 3) { "An E-AC-3 frame has a reserved half sample-rate code." }
        sampleRate = EAC3_HALF_SAMPLE_RATES[fscod2]
        numBlocks = 6
    } else {
        sampleRate = AC3_SAMPLE_RATES[fscod]
        numBlocks = EAC3_BLOCKS[bits.read(2)]
    }
    val acmod = bits.read(3)
    val lfeOn = bits.read(1)
    val bsid = bits.read(5)
    require(bsid in 11..16) { "The E-AC-3 bitstream id is invalid." }
    bits.skip(5) // dialnorm
    if (bits.read(1) != 0) bits.skip(8) // compre/compr
    if (acmod == 0) {
        bits.skip(5)
        if (bits.read(1) != 0) bits.skip(8)
    }
    val channelMap =
        if (streamType == EAC3_STREAM_TYPE_DEPENDENT && bits.read(1) != 0) {
            bits.read(16)
        } else {
            null
        }
    return DolbyAudioHeader(
        streamType = streamType,
        substreamId = substreamId,
        frameSize = frameSize,
        fscod = fscod,
        sampleRate = sampleRate,
        numBlocks = numBlocks,
        bsid = bsid,
        bsmod = 0,
        acmod = acmod,
        lfeOn = lfeOn,
        bitRateCode = 0,
        channelMap = channelMap,
    )
}

private class BitReader(
    private val bytes: ByteArray,
    offset: Int,
) {
    private var bitOffset = offset * 8

    fun read(count: Int): Int {
        require(count in 1..24 && bitOffset <= bytes.size * 8 - count) { "A Dolby audio header is truncated." }
        var value = 0
        repeat(count) {
            val byte = bytes[bitOffset ushr 3].toInt() and 0xff
            value = (value shl 1) or ((byte ushr (7 - (bitOffset and 7))) and 1)
            bitOffset++
        }
        return value
    }

    fun skip(count: Int) {
        require(count >= 0 && bitOffset <= bytes.size * 8 - count) { "A Dolby audio header is truncated." }
        bitOffset += count
    }
}

private class BitWriter(
    size: Int,
) {
    private val bytes = ByteArray(size)
    private var bitOffset = 0

    fun write(
        value: Int,
        count: Int,
    ) {
        require(count in 1..24 && value >= 0 && (count == 24 || value < 1 shl count))
        require(bitOffset <= bytes.size * 8 - count)
        repeat(count) { index ->
            val bit = (value ushr (count - index - 1)) and 1
            val byteIndex = bitOffset ushr 3
            bytes[byteIndex] = (bytes[byteIndex].toInt() or (bit shl (7 - (bitOffset and 7)))).toByte()
            bitOffset++
        }
    }

    fun finish(): ByteArray = bytes.copyOf((bitOffset + 7) / 8)
}

private const val AC3_SYNCWORD = 0x0b77
private const val EAC3_STREAM_TYPE_INDEPENDENT = 0
private const val EAC3_STREAM_TYPE_DEPENDENT = 1
private const val EAC3_STREAM_TYPE_AC3_CONVERT = 2
private const val EAC3_SAMPLES_PER_BLOCK = 256
private const val EAC3_BLOCKS_PER_MP4_SAMPLE = 6
private const val AC3_SAMPLES_PER_FRAME = 1536L
private const val NANOSECONDS_PER_SECOND = 1_000_000_000L
private val AC3_SAMPLE_RATES = intArrayOf(48_000, 44_100, 32_000)
private val EAC3_HALF_SAMPLE_RATES = intArrayOf(24_000, 22_050, 16_000)
private val EAC3_BLOCKS = intArrayOf(1, 2, 3, 6)

// ATSC A/52 frame sizes in 16-bit words, indexed by fscod and frmsizecod.
private val AC3_FRAME_SIZE_WORDS =
    arrayOf(
        intArrayOf(
            64,
            64,
            80,
            80,
            96,
            96,
            112,
            112,
            128,
            128,
            160,
            160,
            192,
            192,
            224,
            224,
            256,
            256,
            320,
            320,
            384,
            384,
            448,
            448,
            512,
            512,
            640,
            640,
            768,
            768,
            896,
            896,
            1024,
            1024,
            1152,
            1152,
            1280,
            1280,
        ),
        intArrayOf(
            69,
            70,
            87,
            88,
            104,
            105,
            121,
            122,
            139,
            140,
            174,
            175,
            208,
            209,
            243,
            244,
            278,
            279,
            348,
            349,
            417,
            418,
            487,
            488,
            557,
            558,
            696,
            697,
            835,
            836,
            975,
            976,
            1114,
            1115,
            1253,
            1254,
            1393,
            1394,
        ),
        intArrayOf(
            96,
            96,
            120,
            120,
            144,
            144,
            168,
            168,
            192,
            192,
            240,
            240,
            288,
            288,
            336,
            336,
            384,
            384,
            480,
            480,
            576,
            576,
            672,
            672,
            768,
            768,
            960,
            960,
            1152,
            1152,
            1344,
            1344,
            1536,
            1536,
            1728,
            1728,
            1920,
            1920,
        ),
    )
