package io.github.kdroidfilter.composemediaplayer

internal fun VideoColorInfo.toConfirmedDecoderCapabilities(): DecoderColorCapabilities {
    if (dynamicRange == VideoDynamicRange.UNKNOWN) return DecoderColorCapabilities()
    val ranges =
        buildSet {
            add(dynamicRange)
            if (dynamicRange == VideoDynamicRange.HDR10_PLUS) add(VideoDynamicRange.HDR10)
            dolbyVision?.compatibleBaseLayerDynamicRange?.let(::add)
        }
    return DecoderColorCapabilities(
        isKnown = true,
        supportedDynamicRanges = ranges,
        maxBitDepth = bitDepth,
        supportedDolbyVisionProfiles = dolbyVision?.profile?.let { setOf(it) }.orEmpty(),
        isDolbyVisionProfileSupportKnown = dynamicRange == VideoDynamicRange.DOLBY_VISION,
    )
}

/**
 * Whether a desktop fallback may forward/transcode this signal without an explicit color transform.
 *
 * Untagged 8-bit video is accepted because HDR10, HDR10+, HLG and Dolby Vision delivery paths require
 * higher precision or explicit signalling. Untagged 10-bit video, wide-gamut video and completely
 * unprobed input stay unsafe: treating any of them as BT.709 is exactly the washed-out fallback that
 * the 2.0 color contract forbids.
 */
internal fun VideoColorInfo.isSafeForUnmanagedSdrFallback(): Boolean {
    val hasOnlySdrTransfer =
        transfer == VideoColorTransfer.UNKNOWN ||
            transfer == VideoColorTransfer.SDR ||
            transfer == VideoColorTransfer.SRGB
    val hasOnlySdrGamut =
        primaries == VideoColorPrimaries.UNKNOWN ||
            primaries == VideoColorPrimaries.BT709 ||
            primaries == VideoColorPrimaries.BT601_525 ||
            primaries == VideoColorPrimaries.BT601_625
    val hasOnlySdrMatrix =
        matrix == VideoColorMatrix.UNKNOWN ||
            matrix == VideoColorMatrix.BT709 ||
            matrix == VideoColorMatrix.BT601 ||
            matrix == VideoColorMatrix.RGB
    val hasNoHdrMetadata =
        masteringDisplay == null &&
            contentLightLevel == null &&
            hdr10Plus == null &&
            dolbyVision == null

    if (!hasOnlySdrTransfer || !hasOnlySdrGamut || !hasOnlySdrMatrix || !hasNoHdrMetadata) return false
    return when (dynamicRange) {
        VideoDynamicRange.SDR -> true
        VideoDynamicRange.UNKNOWN ->
            bitDepth?.let { it in MINIMUM_VALID_BIT_DEPTH..MAXIMUM_UNTAGGED_SDR_BIT_DEPTH } == true
        else -> false
    }
}

private const val MINIMUM_VALID_BIT_DEPTH = 1
private const val MAXIMUM_UNTAGGED_SDR_BIT_DEPTH = 8
