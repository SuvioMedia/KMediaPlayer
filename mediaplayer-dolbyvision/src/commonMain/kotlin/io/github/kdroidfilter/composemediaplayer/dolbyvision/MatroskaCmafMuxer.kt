@file:Suppress(
    "CyclomaticComplexMethod",
    "LongMethod",
    "MagicNumber",
    "MaxLineLength",
    "TooManyFunctions",
)

package io.github.kdroidfilter.composemediaplayer.dolbyvision

import kotlin.math.roundToLong

internal fun buildMatroskaCmafInitialization(
    movie: ParsedMatroskaMovie,
    maximumBytes: Int,
): ByteArray {
    require(movie.tracks.isNotEmpty()) { "A Matroska CMAF initialization needs at least one track." }
    require(maximumBytes > 0) { "maximumBytes must be positive." }
    val ftyp =
        mkvIsoBox(
            "ftyp",
            "iso6".encodeToByteArray() +
                mkvUInt32(1) +
                "iso6".encodeToByteArray() +
                "mp41".encodeToByteArray() +
                "dash".encodeToByteArray() +
                "cmfc".encodeToByteArray(),
        )
    val mvhd = buildMovieHeader(movie.tracks.maxOf(MatroskaTrack::trackId) + 1)
    val trackBoxes = mkvConcat(movie.tracks.map(::buildTrack), maximumBytes)
    val trex =
        mkvConcat(
            movie.tracks.map { track ->
                mkvFullBox(
                    "trex",
                    mkvUInt32(track.trackId.toLong()) +
                        mkvUInt32(1) +
                        mkvUInt32(0) +
                        mkvUInt32(0) +
                        mkvUInt32(0),
                )
            },
            maximumBytes,
        )
    val moov = mkvIsoBox("moov", mkvConcat(listOf(mvhd, trackBoxes, mkvIsoBox("mvex", trex)), maximumBytes))
    return mkvConcat(listOf(ftyp, moov), maximumBytes)
}

internal suspend fun buildMatroskaCmafFragment(
    source: DolbyVisionRandomAccessDataSource,
    movie: ParsedMatroskaMovie,
    plan: MatroskaFragmentPlan,
    maximumBytes: Int,
): ByteArray {
    require(maximumBytes > 0) { "maximumBytes must be positive." }
    val payloads = mutableListOf<MatroskaTrackPayload>()
    var totalPayload = 0L
    movie.tracks.forEach { track ->
        val indices = plan.sampleIndicesByTrackId[track.trackId].orEmpty()
        if (indices.isEmpty()) return@forEach
        val samples =
            indices.map { index ->
                track.samples.getOrNull(index)
                    ?: error("A Matroska fragment sample index is invalid.")
            }
        val size = samples.sumOf { it.size.toLong() }
        require(size <= Int.MAX_VALUE && totalPayload + size <= maximumBytes) {
            "The Matroska CMAF fragment exceeds the configured byte limit."
        }
        totalPayload += size
        payloads += MatroskaTrackPayload(track, samples, size.toInt())
    }
    require(payloads.any { it.track.trackId == movie.videoTrack.trackId }) {
        "A Matroska CMAF fragment contains no video samples."
    }

    fun makeMoof(dataOffsets: Map<Int, Int>): ByteArray {
        val mfhd = mkvFullBox("mfhd", mkvUInt32((plan.index + 1).toLong()))
        val trafs =
            mkvConcat(
                payloads.map { payload ->
                    val tfhd =
                        mkvFullBox(
                            "tfhd",
                            mkvUInt32(payload.track.trackId.toLong()),
                            flags = TFHD_DEFAULT_BASE_IS_MOOF,
                        )
                    val tfdt = mkvFullBox("tfdt", mkvUInt64(payload.samples.first().decodeTime), version = 1)
                    val entries = buildMatroskaSampleEntries(payload.samples, maximumBytes)
                    val trun =
                        mkvFullBox(
                            "trun",
                            mkvUInt32(payload.samples.size.toLong()) +
                                mkvInt32(dataOffsets[payload.track.trackId]?.toLong() ?: 0L) +
                                entries,
                            version = 1,
                            flags = TRUN_ALL_SAMPLE_FIELDS,
                        )
                    mkvIsoBox("traf", mkvConcat(listOf(tfhd, tfdt, trun), maximumBytes))
                },
                maximumBytes,
            )
        return mkvIsoBox("moof", mfhd + trafs)
    }

    val placeholder = makeMoof(emptyMap())
    var dataOffset = placeholder.size + MKV_ISO_BOX_HEADER_BYTES
    val offsets = linkedMapOf<Int, Int>()
    payloads.forEach { payload ->
        offsets[payload.track.trackId] = dataOffset
        dataOffset += payload.size
    }
    val moof = makeMoof(offsets)
    val resultSize = moof.size.toLong() + MKV_ISO_BOX_HEADER_BYTES + totalPayload
    require(resultSize <= maximumBytes) { "The Matroska CMAF fragment exceeds the configured byte limit." }
    val result = ByteArray(resultSize.toInt())
    moof.copyInto(result)
    mkvUInt32(totalPayload + MKV_ISO_BOX_HEADER_BYTES).copyInto(result, moof.size)
    "mdat".encodeToByteArray().copyInto(result, moof.size + MKV_UINT32_BYTES)
    var cursor = moof.size + MKV_ISO_BOX_HEADER_BYTES
    payloads.forEach { payload ->
        payload.samples.forEach { sample ->
            val frame = source.read(sample.offset, sample.size)
            require(frame.size == sample.size) { "A Matroska frame read was truncated." }
            frame.copyInto(result, cursor)
            cursor += frame.size
        }
    }
    require(cursor == result.size) { "The Matroska CMAF fragment size changed while reading samples." }
    return result
}

private data class MatroskaTrackPayload(
    val track: MatroskaTrack,
    val samples: List<MatroskaSample>,
    val size: Int,
)

private fun buildMatroskaSampleEntries(
    samples: List<MatroskaSample>,
    maximumBytes: Int,
): ByteArray {
    val size = samples.size.toLong() * MATROSKA_TRUN_SAMPLE_BYTES
    require(size <= maximumBytes && size <= Int.MAX_VALUE) {
        "The Matroska CMAF sample table exceeds the configured byte limit."
    }
    val result = ByteArray(size.toInt())
    var cursor = 0
    samples.forEach { sample ->
        cursor = result.writeMkvUInt32(cursor, sample.duration)
        cursor = result.writeMkvUInt32(cursor, sample.size.toLong())
        cursor = result.writeMkvUInt32(cursor, if (sample.isSync) 0L else SAMPLE_IS_NON_SYNC_FLAG)
        cursor = result.writeMkvUInt32(cursor, sample.compositionOffset and MKV_UINT32_MAX)
    }
    return result
}

private fun ByteArray.writeMkvUInt32(
    offset: Int,
    value: Long,
): Int {
    require(value in 0..MKV_UINT32_MAX && offset in 0..size - MKV_UINT32_BYTES)
    this[offset] = (value ushr 24).toByte()
    this[offset + 1] = (value ushr 16).toByte()
    this[offset + 2] = (value ushr 8).toByte()
    this[offset + 3] = value.toByte()
    return offset + MKV_UINT32_BYTES
}

private fun mkvConcat(
    parts: List<ByteArray>,
    maximumBytes: Int,
): ByteArray {
    require(maximumBytes > 0)
    val size = parts.sumOf { it.size.toLong() }
    require(size <= maximumBytes && size <= Int.MAX_VALUE) {
        "Generated Matroska CMAF data exceeds the configured byte limit."
    }
    val result = ByteArray(size.toInt())
    var cursor = 0
    parts.forEach { part ->
        part.copyInto(result, cursor)
        cursor += part.size
    }
    return result
}

private fun buildMovieHeader(nextTrackId: Int): ByteArray {
    val identityMatrix =
        mkvInt32(0x0001_0000) + mkvInt32(0) + mkvInt32(0) +
            mkvInt32(0) + mkvInt32(0x0001_0000) + mkvInt32(0) +
            mkvInt32(0) + mkvInt32(0) + mkvInt32(0x4000_0000)
    val content =
        mkvUInt32(0) +
            mkvUInt32(0) +
            mkvUInt32(MOVIE_TIMESCALE) +
            mkvUInt32(0) +
            mkvInt32(0x0001_0000) +
            mkvUInt16(0x0100) +
            mkvUInt16(0) +
            ByteArray(8) +
            identityMatrix +
            ByteArray(24) +
            mkvUInt32(nextTrackId.toLong())
    return mkvFullBox("mvhd", content)
}

private fun buildTrack(track: MatroskaTrack): ByteArray {
    val volume = if (track.kind == MatroskaTrackKind.AUDIO) 0x0100 else 0
    val alternateGroup = if (track.kind == MatroskaTrackKind.AUDIO) AUDIO_ALTERNATE_GROUP else 0
    val width = if (track.kind == MatroskaTrackKind.VIDEO) track.width.toLong() shl 16 else 0L
    val height = if (track.kind == MatroskaTrackKind.VIDEO) track.height.toLong() shl 16 else 0L
    val matrix =
        mkvInt32(0x0001_0000) + mkvInt32(0) + mkvInt32(0) +
            mkvInt32(0) + mkvInt32(0x0001_0000) + mkvInt32(0) +
            mkvInt32(0) + mkvInt32(0) + mkvInt32(0x4000_0000)
    val tkhd =
        mkvFullBox(
            "tkhd",
            mkvUInt32(0) +
                mkvUInt32(0) +
                mkvUInt32(track.trackId.toLong()) +
                mkvUInt32(0) +
                mkvUInt32(0) +
                ByteArray(8) +
                mkvUInt16(0) +
                mkvUInt16(alternateGroup) +
                mkvUInt16(volume) +
                mkvUInt16(0) +
                matrix +
                mkvUInt32(width) +
                mkvUInt32(height),
            flags = TRACK_HEADER_FLAGS,
        )
    return mkvIsoBox("trak", tkhd + buildMedia(track))
}

private fun buildMedia(track: MatroskaTrack): ByteArray {
    val mdhd =
        mkvFullBox(
            "mdhd",
            mkvUInt32(0) +
                mkvUInt32(0) +
                mkvUInt32(track.timescale) +
                mkvUInt32(0) +
                mkvUInt16(iso639Language(track.language)) +
                mkvUInt16(0),
        )
    val handler = if (track.kind == MatroskaTrackKind.VIDEO) "vide" else "soun"
    val defaultName = if (track.kind == MatroskaTrackKind.VIDEO) "VideoHandler" else "SoundHandler"
    val hdlr =
        mkvFullBox(
            "hdlr",
            mkvUInt32(0) + handler.encodeToByteArray() + ByteArray(12) +
                (track.name ?: defaultName).encodeToByteArray() + byteArrayOf(0),
        )
    return mkvIsoBox("mdia", mdhd + hdlr + buildMediaInformation(track))
}

private fun buildMediaInformation(track: MatroskaTrack): ByteArray {
    val mediaHeader =
        if (track.kind == MatroskaTrackKind.VIDEO) {
            mkvFullBox("vmhd", ByteArray(8), flags = 1)
        } else {
            mkvFullBox("smhd", ByteArray(4))
        }
    val url = mkvFullBox("url ", ByteArray(0), flags = 1)
    val dref = mkvFullBox("dref", mkvUInt32(1) + url)
    val dinf = mkvIsoBox("dinf", dref)
    return mkvIsoBox("minf", mediaHeader + dinf + buildEmptySampleTable(track))
}

private fun buildEmptySampleTable(track: MatroskaTrack): ByteArray {
    val sampleEntry =
        when (track.kind) {
            MatroskaTrackKind.VIDEO -> buildDolbyVisionSampleEntry(track)
            MatroskaTrackKind.AUDIO -> buildAudioSampleEntry(track)
        }
    return mkvIsoBox(
        "stbl",
        mkvFullBox("stsd", mkvUInt32(1) + sampleEntry) +
            mkvFullBox("stts", mkvUInt32(0)) +
            mkvFullBox("stsc", mkvUInt32(0)) +
            mkvFullBox("stsz", mkvUInt32(0) + mkvUInt32(0)) +
            mkvFullBox("stco", mkvUInt32(0)),
    )
}

private fun buildDolbyVisionSampleEntry(track: MatroskaTrack): ByteArray {
    require(track.codecPrivate.size >= HEVC_CONFIGURATION_MINIMUM_BYTES) {
        "The Matroska HEVC configuration is truncated."
    }
    val dovi = track.dolbyVisionConfiguration ?: error("The Matroska Dolby Vision configuration is missing.")
    val compressor = "KMediaPlayer HEVC".encodeToByteArray()
    val compressorField = ByteArray(32)
    compressorField[0] = minOf(31, compressor.size).toByte()
    compressor.copyInto(compressorField, 1, 0, minOf(31, compressor.size))
    val visualFields =
        ByteArray(6) +
            mkvUInt16(1) +
            ByteArray(16) +
            mkvUInt16(track.width) +
            mkvUInt16(track.height) +
            mkvUInt32(0x0048_0000) +
            mkvUInt32(0x0048_0000) +
            mkvUInt32(0) +
            mkvUInt16(1) +
            compressorField +
            mkvUInt16(0x0018) +
            mkvUInt16(0xffff)
    val children =
        mkvIsoBox("hvcC", track.codecPrivate) +
            mkvIsoBox("dvcC", dovi) +
            buildColourBoxes(track.colour)
    return mkvIsoBox("dvhe", visualFields + children)
}

private fun buildAudioSampleEntry(track: MatroskaTrack): ByteArray {
    require(track.sampleRate in 1..MAXIMUM_ISO_AUDIO_SAMPLE_RATE) {
        "The Matroska audio sample rate cannot be represented by this CMAF bridge."
    }
    val fields =
        ByteArray(6) +
            mkvUInt16(1) +
            ByteArray(8) +
            mkvUInt16(track.channels) +
            mkvUInt16(track.bitDepth.takeIf { it > 0 } ?: 16) +
            mkvUInt16(0) +
            mkvUInt16(0) +
            mkvUInt32(track.sampleRate.toLong() shl 16)
    return when (track.audioCodec) {
        MatroskaAudioCodec.AAC -> mkvIsoBox("mp4a", fields + buildEsds(track))
        MatroskaAudioCodec.OPUS -> mkvIsoBox("Opus", fields + mkvIsoBox("dOps", buildDOps(track)))
        MatroskaAudioCodec.AC3 -> mkvIsoBox("ac-3", fields + mkvIsoBox("dac3", track.codecPrivate))
        MatroskaAudioCodec.EAC3 -> mkvIsoBox("ec-3", fields + mkvIsoBox("dec3", track.codecPrivate))
        null -> error("The Matroska audio codec is unsupported.")
    }
}

private fun buildEsds(track: MatroskaTrack): ByteArray {
    val decoderSpecific = track.codecPrivate.takeIf(ByteArray::isNotEmpty) ?: synthesizeAacAudioSpecificConfig(track)
    val decoderConfig =
        mp4Descriptor(
            0x04,
            byteArrayOf(0x40, 0x15) +
                byteArrayOf(0, 0, 0) +
                mkvUInt32(0) +
                mkvUInt32(0) +
                mp4Descriptor(0x05, decoderSpecific),
        )
    val es =
        mp4Descriptor(
            0x03,
            mkvUInt16(track.trackId) + byteArrayOf(0) + decoderConfig + mp4Descriptor(0x06, byteArrayOf(0x02)),
        )
    return mkvFullBox("esds", es)
}

private fun synthesizeAacAudioSpecificConfig(track: MatroskaTrack): ByteArray {
    val objectType =
        when {
            track.codecId.contains("MAIN") -> 1
            track.codecId.contains("SSR") -> 3
            track.codecId.contains("LTP") -> 4
            else -> 2
        }
    val frequencyIndex = AAC_SAMPLE_RATES.indexOf(track.sampleRate)
    require(frequencyIndex >= 0) { "AAC without CodecPrivate uses an unsupported sample rate." }
    require(track.channels in 1..15) { "AAC without CodecPrivate uses an unsupported channel configuration." }
    val bits = (objectType shl 11) or (frequencyIndex shl 7) or (track.channels shl 3)
    return byteArrayOf((bits ushr 8).toByte(), bits.toByte())
}

private fun buildDOps(track: MatroskaTrack): ByteArray {
    val opusHead = track.codecPrivate
    require(opusHead.size >= 19 && opusHead.copyOfRange(0, 8).decodeToString() == "OpusHead") {
        "A Matroska Opus track has an invalid OpusHead."
    }
    val channels = opusHead[9].toInt() and 0xff
    require(channels == track.channels) { "Matroska OpusHead channel count differs from TrackEntry." }
    val preSkip = opusHead.readLittleEndianUnsigned(10, 2)
    val inputRate = opusHead.readLittleEndianUnsigned(12, 4)
    val outputGain = opusHead.readLittleEndianUnsigned(16, 2)
    val mappingFamily = opusHead[18].toInt() and 0xff
    val mapping =
        if (mappingFamily == 0) {
            ByteArray(0)
        } else {
            require(opusHead.size >= 21 + channels) { "A mapped Matroska OpusHead is truncated." }
            opusHead.copyOfRange(19, 21 + channels)
        }
    return byteArrayOf(0, channels.toByte()) +
        mkvUInt16(preSkip.toInt()) +
        mkvUInt32(inputRate) +
        mkvUInt16(outputGain.toInt()) +
        byteArrayOf(mappingFamily.toByte()) +
        mapping
}

private fun buildColourBoxes(colour: MatroskaColour?): ByteArray {
    if (colour == null) return ByteArray(0)
    val colr =
        mkvIsoBox(
            "colr",
            "nclx".encodeToByteArray() +
                mkvUInt16(colour.primaries.coerceIn(0, 0xffff)) +
                mkvUInt16(colour.transfer.coerceIn(0, 0xffff)) +
                mkvUInt16(colour.matrix.coerceIn(0, 0xffff)) +
                byteArrayOf(if (colour.range == 2) 0x80.toByte() else 0),
        )
    val clli =
        if (colour.maxCll != null && colour.maxFall != null) {
            mkvIsoBox(
                "clli",
                mkvUInt16(colour.maxCll.coerceIn(0, 0xffff)) +
                    mkvUInt16(colour.maxFall.coerceIn(0, 0xffff)),
            )
        } else {
            ByteArray(0)
        }
    val mdcv = colour.mastering?.toMdcvBox() ?: ByteArray(0)
    return colr + mdcv + clli
}

private fun MatroskaMasteringMetadata.toMdcvBox(): ByteArray? {
    val values = listOf(greenX, greenY, blueX, blueY, redX, redY, whiteX, whiteY, maxLuminance, minLuminance)
    if (values.any { it == null || !it.isFinite() || it < 0.0 }) return null

    fun chromaticity(value: Double?): Int =
        (value!! * CHROMATICITY_DENOMINATOR).roundToLong().coerceIn(0, 0xffff).toInt()

    fun luminance(value: Double?): Long = (value!! * LUMINANCE_DENOMINATOR).roundToLong().coerceIn(0, MKV_UINT32_MAX)
    return mkvIsoBox(
        "mdcv",
        mkvUInt16(chromaticity(greenX)) +
            mkvUInt16(chromaticity(greenY)) +
            mkvUInt16(chromaticity(blueX)) +
            mkvUInt16(chromaticity(blueY)) +
            mkvUInt16(chromaticity(redX)) +
            mkvUInt16(chromaticity(redY)) +
            mkvUInt16(chromaticity(whiteX)) +
            mkvUInt16(chromaticity(whiteY)) +
            mkvUInt32(luminance(maxLuminance)) +
            mkvUInt32(luminance(minLuminance)),
    )
}

private fun mp4Descriptor(
    tag: Int,
    payload: ByteArray,
): ByteArray = byteArrayOf(tag.toByte()) + descriptorLength(payload.size) + payload

private fun descriptorLength(length: Int): ByteArray {
    require(length >= 0)
    val groups = mutableListOf(length and 0x7f)
    var remaining = length ushr 7
    while (remaining > 0) {
        groups += remaining and 0x7f
        remaining = remaining ushr 7
    }
    require(groups.size <= 4) { "An MPEG-4 descriptor is too large." }
    return groups
        .asReversed()
        .mapIndexed { index, value ->
            (value or if (index < groups.lastIndex) 0x80 else 0).toByte()
        }.toByteArray()
}

private fun iso639Language(language: String): Int {
    val code = language.substringBefore('-').lowercase().takeIf { it.length == 3 && it.all(Char::isLetter) } ?: "und"
    return ((code[0].code - 0x60) shl 10) or ((code[1].code - 0x60) shl 5) or (code[2].code - 0x60)
}

private fun ByteArray.readLittleEndianUnsigned(
    offset: Int,
    length: Int,
): Long {
    require(offset >= 0 && length in 1..8 && offset <= size - length)
    var value = 0L
    repeat(length) { index -> value = value or ((this[offset + index].toLong() and 0xff) shl (index * 8)) }
    return value
}

private fun mkvIsoBox(
    type: String,
    content: ByteArray,
): ByteArray {
    require(type.encodeToByteArray().size == 4) { "An ISO BMFF box type must contain four bytes." }
    val size = content.size.toLong() + MKV_ISO_BOX_HEADER_BYTES
    require(size <= MKV_UINT32_MAX) { "Generated ISO BMFF box exceeds 32-bit size." }
    return mkvUInt32(size) + type.encodeToByteArray() + content
}

private fun mkvFullBox(
    type: String,
    content: ByteArray,
    version: Int = 0,
    flags: Int = 0,
): ByteArray =
    mkvIsoBox(
        type,
        byteArrayOf(version.toByte(), (flags ushr 16).toByte(), (flags ushr 8).toByte(), flags.toByte()) + content,
    )

private fun mkvUInt16(value: Int): ByteArray {
    require(value in 0..0xffff)
    return byteArrayOf((value ushr 8).toByte(), value.toByte())
}

private fun mkvUInt32(value: Long): ByteArray {
    require(value in 0..MKV_UINT32_MAX)
    return byteArrayOf((value ushr 24).toByte(), (value ushr 16).toByte(), (value ushr 8).toByte(), value.toByte())
}

private fun mkvUInt64(value: Long): ByteArray {
    require(value >= 0)
    return mkvUInt32(value ushr 32) + mkvUInt32(value and MKV_UINT32_MAX)
}

private fun mkvInt32(value: Long): ByteArray {
    require(value in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong())
    return mkvUInt32(value and MKV_UINT32_MAX)
}

private const val MKV_ISO_BOX_HEADER_BYTES = 8
private const val MKV_UINT32_BYTES = 4
private const val MATROSKA_TRUN_SAMPLE_BYTES = 16L
private const val MKV_UINT32_MAX = 0xffff_ffffL
private const val MOVIE_TIMESCALE = 1_000L
private const val TRACK_HEADER_FLAGS = 0x000007
private const val AUDIO_ALTERNATE_GROUP = 1
private const val TFHD_DEFAULT_BASE_IS_MOOF = 0x020000
private const val TRUN_ALL_SAMPLE_FIELDS = 0x000f01
private const val SAMPLE_IS_NON_SYNC_FLAG = 0x0001_0000L
private const val HEVC_CONFIGURATION_MINIMUM_BYTES = 22
private const val MAXIMUM_ISO_AUDIO_SAMPLE_RATE = 65_535
private const val CHROMATICITY_DENOMINATOR = 50_000.0
private const val LUMINANCE_DENOMINATOR = 10_000.0
private val AAC_SAMPLE_RATES =
    listOf(96_000, 88_200, 64_000, 48_000, 44_100, 32_000, 24_000, 22_050, 16_000, 12_000, 11_025, 8_000, 7_350)
