package io.github.kdroidfilter.composemediaplayer

/**
 * Color signal read from the active decoded media type/caps.
 *
 * Unlike the container probe, this changes when an adaptive stream selects a
 * variant with a different transfer function. Native backends increment
 * [generation] only after publishing a complete snapshot.
 */
internal data class JvmDecodedVideoColorSignal(
    val generation: Int,
    val bitDepth: Int? = null,
    val primaries: VideoColorPrimaries = VideoColorPrimaries.UNKNOWN,
    val transfer: VideoColorTransfer = VideoColorTransfer.UNKNOWN,
    val matrix: VideoColorMatrix = VideoColorMatrix.UNKNOWN,
    val range: VideoColorRange = VideoColorRange.UNKNOWN,
    /** A previously known native field disappeared at a media-type boundary. */
    val authoritativeUnknowns: Boolean = false,
) {
    fun mergeInto(source: VideoColorInfo): VideoColorInfo {
        val resolvedDynamicRange = resolvedDynamicRange(source)
        val keepsSourceSignal =
            !authoritativeUnknowns &&
                (transfer == VideoColorTransfer.UNKNOWN || resolvedDynamicRange == source.dynamicRange)

        return source.copy(
            dynamicRange = resolvedDynamicRange,
            bitDepth = bitDepth ?: source.bitDepth.takeIf { keepsSourceSignal },
            primaries = primaries.orPrevious(source.primaries, keepsSourceSignal),
            transfer = transfer.orPrevious(source.transfer, keepsSourceSignal),
            matrix = matrix.orPrevious(source.matrix, keepsSourceSignal),
            range = range.orPrevious(source.range, keepsSourceSignal),
            masteringDisplay = source.masteringDisplay.takeIf { keepsSourceSignal },
            contentLightLevel = source.contentLightLevel.takeIf { keepsSourceSignal },
            hdr10Plus = source.hdr10Plus.takeIf { keepsSourceSignal },
            dolbyVision = source.dolbyVision.takeIf { keepsSourceSignal },
        )
    }

    private fun resolvedDynamicRange(source: VideoColorInfo): VideoDynamicRange =
        when (transfer) {
            VideoColorTransfer.PQ ->
                when (source.dynamicRange) {
                    VideoDynamicRange.HDR10_PLUS,
                    VideoDynamicRange.DOLBY_VISION,
                    -> source.dynamicRange
                    else -> VideoDynamicRange.HDR10
                }
            VideoColorTransfer.HLG -> VideoDynamicRange.HLG
            VideoColorTransfer.SDR,
            VideoColorTransfer.SRGB,
            -> VideoDynamicRange.SDR
            VideoColorTransfer.UNKNOWN,
            -> if (authoritativeUnknowns) VideoDynamicRange.UNKNOWN else source.dynamicRange
            VideoColorTransfer.LINEAR -> source.dynamicRange
        }
}

private fun VideoColorPrimaries.orPrevious(
    previous: VideoColorPrimaries,
    keepPrevious: Boolean,
): VideoColorPrimaries = if (this != VideoColorPrimaries.UNKNOWN) this else previous.takeIf { keepPrevious } ?: this

private fun VideoColorTransfer.orPrevious(
    previous: VideoColorTransfer,
    keepPrevious: Boolean,
): VideoColorTransfer = if (this != VideoColorTransfer.UNKNOWN) this else previous.takeIf { keepPrevious } ?: this

private fun VideoColorMatrix.orPrevious(
    previous: VideoColorMatrix,
    keepPrevious: Boolean,
): VideoColorMatrix = if (this != VideoColorMatrix.UNKNOWN) this else previous.takeIf { keepPrevious } ?: this

private fun VideoColorRange.orPrevious(
    previous: VideoColorRange,
    keepPrevious: Boolean,
): VideoColorRange = if (this != VideoColorRange.UNKNOWN) this else previous.takeIf { keepPrevious } ?: this

/** Stable JNI wire format shared by the Windows and Linux native backends. */
internal object JvmDecodedVideoColorSignalCodec {
    const val VALUE_COUNT: Int = 7

    fun decode(values: IntArray?): JvmDecodedVideoColorSignal? {
        if (values == null || values.size < VALUE_COUNT || values[0] <= 0) return null
        return JvmDecodedVideoColorSignal(
            generation = values[0],
            bitDepth = values[1].takeIf { it > 0 },
            primaries = values[2].toVideoColorPrimaries(),
            transfer = values[3].toVideoColorTransfer(),
            matrix = values[4].toVideoColorMatrix(),
            range = values[5].toVideoColorRange(),
            authoritativeUnknowns = values[6] != 0,
        )
    }
}

private fun Int.toVideoColorPrimaries(): VideoColorPrimaries =
    when (this) {
        DecodedColorWireCode.PRIMARIES_BT601_525 -> VideoColorPrimaries.BT601_525
        DecodedColorWireCode.PRIMARIES_BT601_625 -> VideoColorPrimaries.BT601_625
        DecodedColorWireCode.PRIMARIES_BT709 -> VideoColorPrimaries.BT709
        DecodedColorWireCode.PRIMARIES_BT2020 -> VideoColorPrimaries.BT2020
        DecodedColorWireCode.PRIMARIES_DISPLAY_P3 -> VideoColorPrimaries.DISPLAY_P3
        else -> VideoColorPrimaries.UNKNOWN
    }

private fun Int.toVideoColorTransfer(): VideoColorTransfer =
    when (this) {
        DecodedColorWireCode.TRANSFER_SDR -> VideoColorTransfer.SDR
        DecodedColorWireCode.TRANSFER_SRGB -> VideoColorTransfer.SRGB
        DecodedColorWireCode.TRANSFER_LINEAR -> VideoColorTransfer.LINEAR
        DecodedColorWireCode.TRANSFER_PQ -> VideoColorTransfer.PQ
        DecodedColorWireCode.TRANSFER_HLG -> VideoColorTransfer.HLG
        else -> VideoColorTransfer.UNKNOWN
    }

private fun Int.toVideoColorMatrix(): VideoColorMatrix =
    when (this) {
        DecodedColorWireCode.MATRIX_RGB -> VideoColorMatrix.RGB
        DecodedColorWireCode.MATRIX_BT601 -> VideoColorMatrix.BT601
        DecodedColorWireCode.MATRIX_BT709 -> VideoColorMatrix.BT709
        DecodedColorWireCode.MATRIX_BT2020_NCL -> VideoColorMatrix.BT2020_NCL
        DecodedColorWireCode.MATRIX_BT2020_CL -> VideoColorMatrix.BT2020_CL
        DecodedColorWireCode.MATRIX_ICTCP -> VideoColorMatrix.ICTCP
        else -> VideoColorMatrix.UNKNOWN
    }

private fun Int.toVideoColorRange(): VideoColorRange =
    when (this) {
        DecodedColorWireCode.RANGE_LIMITED -> VideoColorRange.LIMITED
        DecodedColorWireCode.RANGE_FULL -> VideoColorRange.FULL
        else -> VideoColorRange.UNKNOWN
    }

/** Stable native-to-JVM numeric protocol shared with Windows and Linux. */
private object DecodedColorWireCode {
    const val PRIMARIES_BT601_525 = 1
    const val PRIMARIES_BT601_625 = 2
    const val PRIMARIES_BT709 = 3
    const val PRIMARIES_BT2020 = 4
    const val PRIMARIES_DISPLAY_P3 = 5
    const val TRANSFER_SDR = 1
    const val TRANSFER_SRGB = 2
    const val TRANSFER_LINEAR = 3
    const val TRANSFER_PQ = 4
    const val TRANSFER_HLG = 5
    const val MATRIX_RGB = 1
    const val MATRIX_BT601 = 2
    const val MATRIX_BT709 = 3
    const val MATRIX_BT2020_NCL = 4
    const val MATRIX_BT2020_CL = 5
    const val MATRIX_ICTCP = 6
    const val RANGE_LIMITED = 1
    const val RANGE_FULL = 2
}
