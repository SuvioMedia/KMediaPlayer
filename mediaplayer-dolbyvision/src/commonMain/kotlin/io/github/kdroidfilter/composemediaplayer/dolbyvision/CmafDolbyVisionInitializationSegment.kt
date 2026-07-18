@file:Suppress("CyclomaticComplexMethod", "MagicNumber", "MaxLineLength")

package io.github.kdroidfilter.composemediaplayer.dolbyvision

/** Video-track facts required to parse and rewrite subsequent CMAF media fragments. */
data class CmafDolbyVisionTrackConfiguration(
    val trackId: Int,
    val timescale: Long,
    val nalLengthFieldBytes: Int,
    val sourceProfile: Int,
    val sourceLevel: Int,
    val rewrittenInitializationSegment: ByteArray,
) {
    init {
        require(trackId > 0) { "trackId must be positive." }
        require(timescale > 0) { "timescale must be positive." }
        require(nalLengthFieldBytes == SUPPORTED_NAL_LENGTH_FIELD_BYTES) {
            "Only four-byte HEVC NAL lengths are supported by the bounded CMAF bridge."
        }
    }
}

sealed interface CmafDolbyVisionInitializationResult {
    data class Success(
        val configuration: CmafDolbyVisionTrackConfiguration,
    ) : CmafDolbyVisionInitializationResult

    data class Failure(
        val message: String,
    ) : CmafDolbyVisionInitializationResult
}

/** Parses an unencrypted CMAF initialization segment and changes only Profile 7 signaling to Profile 8.1. */
object CmafDolbyVisionInitializationSegment {
    @Suppress("ReturnCount")
    fun prepareProfile81(
        payload: ByteArray,
        maximumInputBytes: Int = DEFAULT_MAXIMUM_INITIALIZATION_BYTES,
    ): CmafDolbyVisionInitializationResult {
        if (payload.isEmpty()) return failure("The CMAF initialization segment is empty.")
        if (maximumInputBytes <= 0 || payload.size > maximumInputBytes) {
            return failure("The CMAF initialization segment exceeds the configured byte limit.")
        }
        val topLevel =
            when (val parsed = payload.parseIsoBmffBoxes()) {
                is IsoBmffParseResult.Success -> parsed.boxes
                is IsoBmffParseResult.Failure -> return failure(parsed.message)
            }
        val moov = topLevel.singleOrNull { it.type == BOX_MOOV } ?: return failure("A single moov box is required.")
        val moovChildren =
            when (val parsed = payload.children(moov)) {
                is IsoBmffParseResult.Success -> parsed.boxes
                is IsoBmffParseResult.Failure -> return failure(parsed.message)
            }
        val tracks = moovChildren.filter { it.type == BOX_TRAK }
        if (tracks.isEmpty()) return failure("The initialization segment contains no tracks.")

        val candidates = mutableListOf<ParsedDolbyVisionTrack>()
        for (track in tracks) {
            when (val parsed = payload.parseTrack(track)) {
                is TrackParseResult.DolbyVision -> candidates += parsed.track
                is TrackParseResult.NotVideo -> Unit
                is TrackParseResult.VideoWithoutDolbyVision -> Unit
                is TrackParseResult.Failure -> return failure(parsed.message)
            }
        }
        val source =
            candidates.singleOrNull()
                ?: return failure(
                    if (candidates.isEmpty()) {
                        "No Dolby Vision HEVC video track with dvcC/dvvC configuration was found."
                    } else {
                        "Multiple Dolby Vision video tracks require explicit track selection."
                    },
                )
        if (source.profile != PROFILE_7) return failure("Dolby Vision Profile 7 input is required.")
        if (source.nalLengthFieldBytes != SUPPORTED_NAL_LENGTH_FIELD_BYTES) {
            return failure("Only four-byte HEVC NAL lengths are supported; found ${source.nalLengthFieldBytes}.")
        }

        val rewritten = payload.copyOf()
        val configurationOffset = source.dolbyConfiguration.contentOffset
        if (source.dolbyConfiguration.size - source.dolbyConfiguration.headerSize < MINIMUM_DOVI_CONFIG_BYTES) {
            return failure("The Dolby Vision decoder configuration is truncated.")
        }
        val oldWord =
            ((rewritten[configurationOffset + DOVI_PACKED_WORD_OFFSET].toInt() and BYTE_MASK) shl 8) or
                (rewritten[configurationOffset + DOVI_PACKED_WORD_OFFSET + 1].toInt() and BYTE_MASK)
        val level = (oldWord ushr DOVI_LEVEL_SHIFT) and DOVI_LEVEL_MASK
        val newWord =
            (PROFILE_8 shl DOVI_PROFILE_SHIFT) or
                (level shl DOVI_LEVEL_SHIFT) or
                DOVI_RPU_PRESENT_BIT or
                DOVI_BASE_LAYER_PRESENT_BIT
        rewritten[configurationOffset + DOVI_PACKED_WORD_OFFSET] = (newWord ushr 8).toByte()
        rewritten[configurationOffset + DOVI_PACKED_WORD_OFFSET + 1] = newWord.toByte()
        if (source.dolbyConfiguration.size - source.dolbyConfiguration.headerSize >=
            DOVI_COMPATIBILITY_BYTE_OFFSET + 1
        ) {
            val existing = rewritten[configurationOffset + DOVI_COMPATIBILITY_BYTE_OFFSET].toInt() and BYTE_MASK
            rewritten[configurationOffset + DOVI_COMPATIBILITY_BYTE_OFFSET] =
                ((PROFILE_81_BASE_LAYER_COMPATIBILITY_ID shl 4) or (existing and LOW_NIBBLE_MASK)).toByte()
        }
        rewritten.writeFourCc(source.dolbyConfiguration.offset + ISO_BOX_TYPE_OFFSET, BOX_DVVC)

        return CmafDolbyVisionInitializationResult.Success(
            CmafDolbyVisionTrackConfiguration(
                trackId = source.trackId,
                timescale = source.timescale,
                nalLengthFieldBytes = source.nalLengthFieldBytes,
                sourceProfile = source.profile,
                sourceLevel = source.level,
                rewrittenInitializationSegment = rewritten,
            ),
        )
    }

    private fun failure(message: String) = CmafDolbyVisionInitializationResult.Failure(message)
}

private sealed interface TrackParseResult {
    data class DolbyVision(
        val track: ParsedDolbyVisionTrack,
    ) : TrackParseResult

    data object NotVideo : TrackParseResult

    data object VideoWithoutDolbyVision : TrackParseResult

    data class Failure(
        val message: String,
    ) : TrackParseResult
}

private data class ParsedDolbyVisionTrack(
    val trackId: Int,
    val timescale: Long,
    val nalLengthFieldBytes: Int,
    val profile: Int,
    val level: Int,
    val dolbyConfiguration: IsoBmffBox,
)

@Suppress("ReturnCount")
private fun ByteArray.parseTrack(track: IsoBmffBox): TrackParseResult {
    val trackChildren =
        when (val parsed = children(track)) {
            is IsoBmffParseResult.Success -> parsed.boxes
            is IsoBmffParseResult.Failure -> return TrackParseResult.Failure(parsed.message)
        }
    val tkhd =
        trackChildren.singleOrNull { it.type == BOX_TKHD }
            ?: return TrackParseResult.Failure("A track has no single tkhd box.")
    val mdia =
        trackChildren.singleOrNull { it.type == BOX_MDIA }
            ?: return TrackParseResult.Failure("A track has no single mdia box.")
    val mdiaChildren =
        when (val parsed = children(mdia)) {
            is IsoBmffParseResult.Success -> parsed.boxes
            is IsoBmffParseResult.Failure -> return TrackParseResult.Failure(parsed.message)
        }
    val hdlr =
        mdiaChildren.singleOrNull { it.type == BOX_HDLR }
            ?: return TrackParseResult.Failure("A track has no single hdlr box.")
    if (hdlr.contentSize < HANDLER_TYPE_END_OFFSET) return TrackParseResult.Failure("The hdlr box is truncated.")
    if (readFourCc(hdlr.contentOffset + HANDLER_TYPE_OFFSET) != HANDLER_VIDEO) return TrackParseResult.NotVideo
    val mdhd =
        mdiaChildren.singleOrNull { it.type == BOX_MDHD }
            ?: return TrackParseResult.Failure("The video track has no single mdhd box.")
    val minf =
        mdiaChildren.singleOrNull { it.type == BOX_MINF }
            ?: return TrackParseResult.Failure("The video track has no single minf box.")
    val minfChildren =
        when (val parsed = children(minf)) {
            is IsoBmffParseResult.Success -> parsed.boxes
            is IsoBmffParseResult.Failure -> return TrackParseResult.Failure(parsed.message)
        }
    val stbl =
        minfChildren.singleOrNull { it.type == BOX_STBL }
            ?: return TrackParseResult.Failure("The video track has no single stbl box.")
    val stblChildren =
        when (val parsed = children(stbl)) {
            is IsoBmffParseResult.Success -> parsed.boxes
            is IsoBmffParseResult.Failure -> return TrackParseResult.Failure(parsed.message)
        }
    val stsd =
        stblChildren.singleOrNull { it.type == BOX_STSD }
            ?: return TrackParseResult.Failure("The video track has no single stsd box.")

    val trackId = readTrackId(tkhd) ?: return TrackParseResult.Failure("The tkhd track id is invalid.")
    val timescale = readTimescale(mdhd) ?: return TrackParseResult.Failure("The mdhd timescale is invalid.")
    val sampleEntry = readSingleVisualSampleEntry(stsd) ?: return TrackParseResult.VideoWithoutDolbyVision
    if (sampleEntry.type == SAMPLE_ENTRY_ENCRYPTED || containsProtectionMetadata(sampleEntry)) {
        return TrackParseResult.Failure("Encrypted/DRM sample entries are not supported by the Dolby Vision bridge.")
    }
    if (sampleEntry.type !in HEVC_SAMPLE_ENTRIES) return TrackParseResult.VideoWithoutDolbyVision
    val sampleEntryChildrenStart = sampleEntry.contentOffset + VISUAL_SAMPLE_ENTRY_FIELDS_BYTES
    if (sampleEntryChildrenStart >
        sampleEntry.endOffset
    ) {
        return TrackParseResult.Failure("The HEVC visual sample entry is truncated.")
    }
    val entryChildren =
        when (val parsed = parseIsoBmffBoxes(sampleEntryChildrenStart, sampleEntry.endOffset)) {
            is IsoBmffParseResult.Success -> parsed.boxes
            is IsoBmffParseResult.Failure -> return TrackParseResult.Failure(parsed.message)
        }
    val hevc =
        entryChildren.singleOrNull { it.type == BOX_HVCC }
            ?: return TrackParseResult.Failure("The Dolby Vision track has no single hvcC box.")
    if (hevc.contentSize < HEVC_CONFIGURATION_MINIMUM_BYTES) {
        return TrackParseResult.Failure("The hvcC decoder configuration is truncated.")
    }
    val nalLengthFieldBytes = (this[hevc.contentOffset + HEVC_LENGTH_SIZE_OFFSET].toInt() and NAL_LENGTH_SIZE_MASK) + 1
    val dolby =
        entryChildren.singleOrNull { it.type == BOX_DVCC || it.type == BOX_DVVC }
            ?: return TrackParseResult.VideoWithoutDolbyVision
    if (dolby.contentSize < MINIMUM_DOVI_CONFIG_BYTES) {
        return TrackParseResult.Failure("The Dolby Vision decoder configuration is truncated.")
    }
    val word =
        ((this[dolby.contentOffset + DOVI_PACKED_WORD_OFFSET].toInt() and BYTE_MASK) shl 8) or
            (this[dolby.contentOffset + DOVI_PACKED_WORD_OFFSET + 1].toInt() and BYTE_MASK)
    return TrackParseResult.DolbyVision(
        ParsedDolbyVisionTrack(
            trackId = trackId,
            timescale = timescale,
            nalLengthFieldBytes = nalLengthFieldBytes,
            profile = (word ushr DOVI_PROFILE_SHIFT) and DOVI_PROFILE_MASK,
            level = (word ushr DOVI_LEVEL_SHIFT) and DOVI_LEVEL_MASK,
            dolbyConfiguration = dolby,
        ),
    )
}

private fun ByteArray.readTrackId(box: IsoBmffBox): Int? {
    if (box.contentSize < TKHD_VERSION_AND_MINIMUM_FIELDS) return null
    val version = this[box.contentOffset].toInt() and BYTE_MASK
    val offset = box.contentOffset + if (version == 1) TKHD_TRACK_ID_OFFSET_V1 else TKHD_TRACK_ID_OFFSET_V0
    if (offset > box.endOffset - UINT32_BYTES) return null
    return readUnsignedInt(offset).takeIf { it in 1..Int.MAX_VALUE }?.toInt()
}

private fun ByteArray.readTimescale(box: IsoBmffBox): Long? {
    if (box.contentSize < MDHD_VERSION_AND_MINIMUM_FIELDS) return null
    val version = this[box.contentOffset].toInt() and BYTE_MASK
    val offset = box.contentOffset + if (version == 1) MDHD_TIMESCALE_OFFSET_V1 else MDHD_TIMESCALE_OFFSET_V0
    if (offset > box.endOffset - UINT32_BYTES) return null
    return readUnsignedInt(offset).takeIf { it > 0 }
}

private fun ByteArray.readSingleVisualSampleEntry(stsd: IsoBmffBox): IsoBmffBox? {
    if (stsd.contentSize < STSD_FIELDS_BEFORE_ENTRIES) return null
    val entryCount = readUnsignedInt(stsd.contentOffset + ISO_FULL_BOX_HEADER_BYTES)
    if (entryCount != 1L) return null
    val entryStart = stsd.contentOffset + STSD_FIELDS_BEFORE_ENTRIES
    return when (val result = parseIsoBmffBoxes(entryStart, stsd.endOffset)) {
        is IsoBmffParseResult.Success -> result.boxes.singleOrNull()
        is IsoBmffParseResult.Failure -> null
    }
}

private fun ByteArray.containsProtectionMetadata(sampleEntry: IsoBmffBox): Boolean {
    val start = (sampleEntry.contentOffset + VISUAL_SAMPLE_ENTRY_FIELDS_BYTES).coerceAtMost(sampleEntry.endOffset)
    return when (val result = parseIsoBmffBoxes(start, sampleEntry.endOffset)) {
        is IsoBmffParseResult.Success -> result.boxes.any { it.type == BOX_SINF }
        is IsoBmffParseResult.Failure -> true
    }
}

private fun ByteArray.children(parent: IsoBmffBox): IsoBmffParseResult =
    parseIsoBmffBoxes(parent.contentOffset, parent.endOffset)

private val IsoBmffBox.contentSize: Int get() = size - headerSize

private const val DEFAULT_MAXIMUM_INITIALIZATION_BYTES = 16 * 1024 * 1024
private const val SUPPORTED_NAL_LENGTH_FIELD_BYTES = 4
private const val PROFILE_7 = 7
private const val PROFILE_8 = 8
private const val PROFILE_81_BASE_LAYER_COMPATIBILITY_ID = 1
private const val BYTE_MASK = 0xff
private const val LOW_NIBBLE_MASK = 0x0f
private const val DOVI_PACKED_WORD_OFFSET = 2
private const val DOVI_COMPATIBILITY_BYTE_OFFSET = 4
private const val DOVI_PROFILE_SHIFT = 9
private const val DOVI_PROFILE_MASK = 0x7f
private const val DOVI_LEVEL_SHIFT = 3
private const val DOVI_LEVEL_MASK = 0x3f
private const val DOVI_RPU_PRESENT_BIT = 1 shl 2
private const val DOVI_BASE_LAYER_PRESENT_BIT = 1
private const val MINIMUM_DOVI_CONFIG_BYTES = 4
private const val HANDLER_TYPE_OFFSET = 8
private const val HANDLER_TYPE_END_OFFSET = HANDLER_TYPE_OFFSET + 4
private const val TKHD_VERSION_AND_MINIMUM_FIELDS = 16
private const val TKHD_TRACK_ID_OFFSET_V0 = 12
private const val TKHD_TRACK_ID_OFFSET_V1 = 20
private const val MDHD_VERSION_AND_MINIMUM_FIELDS = 16
private const val MDHD_TIMESCALE_OFFSET_V0 = 12
private const val MDHD_TIMESCALE_OFFSET_V1 = 20
private const val STSD_FIELDS_BEFORE_ENTRIES = 8
private const val VISUAL_SAMPLE_ENTRY_FIELDS_BYTES = 78
private const val HEVC_CONFIGURATION_MINIMUM_BYTES = 22
private const val HEVC_LENGTH_SIZE_OFFSET = 21
private const val NAL_LENGTH_SIZE_MASK = 0x03
private const val BOX_MOOV = "moov"
private const val BOX_TRAK = "trak"
private const val BOX_TKHD = "tkhd"
private const val BOX_MDIA = "mdia"
private const val BOX_MDHD = "mdhd"
private const val BOX_HDLR = "hdlr"
private const val BOX_MINF = "minf"
private const val BOX_STBL = "stbl"
private const val BOX_STSD = "stsd"
private const val BOX_HVCC = "hvcC"
private const val BOX_DVCC = "dvcC"
private const val BOX_DVVC = "dvvC"
private const val BOX_SINF = "sinf"
private const val HANDLER_VIDEO = "vide"
private const val SAMPLE_ENTRY_ENCRYPTED = "encv"
private val HEVC_SAMPLE_ENTRIES = setOf("hvc1", "hev1", "dvh1", "dvhe")
