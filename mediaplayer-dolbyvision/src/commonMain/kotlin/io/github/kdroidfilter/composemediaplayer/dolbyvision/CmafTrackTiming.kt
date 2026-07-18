@file:Suppress("MagicNumber", "ReturnCount")

package io.github.kdroidfilter.composemediaplayer.dolbyvision

internal data class CmafTrackTimingConfiguration(
    val trackId: Int,
    val timescale: Long,
)

internal data class CmafTrackFragmentTiming(
    val firstDecodeTimeUs: Long,
    val firstPresentationTimeUs: Long,
    val startsWithSyncSample: Boolean,
)

/** Reads only the track identity and clock needed to map fMP4 timestamps onto an HLS timeline. */
internal fun ByteArray.readCmafTrackTimingConfiguration(handlerType: String): CmafTrackTimingConfiguration? {
    if (handlerType.length != 4) return null
    val top = (parseIsoBmffBoxes() as? IsoBmffParseResult.Success)?.boxes ?: return null
    val moov = top.singleOrNull { it.type == "moov" } ?: return null
    val tracks =
        (parseIsoBmffBoxes(moov.contentOffset, moov.endOffset) as? IsoBmffParseResult.Success)
            ?.boxes
            ?.filter { it.type == "trak" } ?: return null
    val matching =
        tracks.mapNotNull { track ->
            val trackChildren =
                (parseIsoBmffBoxes(track.contentOffset, track.endOffset) as? IsoBmffParseResult.Success)?.boxes
                    ?: return@mapNotNull null
            val tkhd = trackChildren.singleOrNull { it.type == "tkhd" } ?: return@mapNotNull null
            val mdia = trackChildren.singleOrNull { it.type == "mdia" } ?: return@mapNotNull null
            val mediaChildren =
                (parseIsoBmffBoxes(mdia.contentOffset, mdia.endOffset) as? IsoBmffParseResult.Success)?.boxes
                    ?: return@mapNotNull null
            val hdlr = mediaChildren.singleOrNull { it.type == "hdlr" } ?: return@mapNotNull null
            if (hdlr.contentOffset > hdlr.endOffset - HANDLER_TYPE_END_OFFSET) return@mapNotNull null
            if (readFourCc(hdlr.contentOffset + HANDLER_TYPE_OFFSET) != handlerType) return@mapNotNull null
            val mdhd = mediaChildren.singleOrNull { it.type == "mdhd" } ?: return@mapNotNull null
            val trackId = readCmafTrackId(tkhd) ?: return@mapNotNull null
            val timescale = readCmafTimescale(mdhd) ?: return@mapNotNull null
            CmafTrackTimingConfiguration(trackId, timescale)
        }
    return matching.singleOrNull()
}

private fun ByteArray.readCmafTrackId(box: IsoBmffBox): Int? {
    if (box.contentOffset >= box.endOffset) return null
    val version = this[box.contentOffset].toInt() and BYTE_MASK
    val offset = box.contentOffset + if (version == 1) TKHD_TRACK_ID_OFFSET_V1 else TKHD_TRACK_ID_OFFSET_V0
    if (offset > box.endOffset - UINT32_BYTES) return null
    return readUnsignedInt(offset).takeIf { it in 1..Int.MAX_VALUE }?.toInt()
}

private fun ByteArray.readCmafTimescale(box: IsoBmffBox): Long? {
    if (box.contentOffset >= box.endOffset) return null
    val version = this[box.contentOffset].toInt() and BYTE_MASK
    val offset = box.contentOffset + if (version == 1) MDHD_TIMESCALE_OFFSET_V1 else MDHD_TIMESCALE_OFFSET_V0
    if (offset > box.endOffset - UINT32_BYTES) return null
    return readUnsignedInt(offset).takeIf { it > 0 }
}

private const val BYTE_MASK = 0xff
private const val HANDLER_TYPE_OFFSET = 8
private const val HANDLER_TYPE_END_OFFSET = HANDLER_TYPE_OFFSET + 4
private const val TKHD_TRACK_ID_OFFSET_V0 = 12
private const val TKHD_TRACK_ID_OFFSET_V1 = 20
private const val MDHD_TIMESCALE_OFFSET_V0 = 12
private const val MDHD_TIMESCALE_OFFSET_V1 = 20
