package io.github.kdroidfilter.composemediaplayer

import androidx.media3.common.C
import androidx.media3.common.ColorInfo
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi

@UnstableApi
internal fun Format.toVideoColorInfo(): VideoColorInfo {
    val dolbyVision = dolbyVisionInfoOrNull()
    val color = colorInfo
    val transfer = color.toVideoColorTransfer()
    val dynamicRange =
        when {
            dolbyVision != null -> VideoDynamicRange.DOLBY_VISION
            transfer == VideoColorTransfer.PQ -> VideoDynamicRange.HDR10
            transfer == VideoColorTransfer.HLG -> VideoDynamicRange.HLG
            color != null && ColorInfo.isEquivalentToAssumedSdrDefault(color) -> VideoDynamicRange.SDR
            transfer == VideoColorTransfer.SDR || transfer == VideoColorTransfer.SRGB -> VideoDynamicRange.SDR
            else -> VideoDynamicRange.UNKNOWN
        }
    val staticMetadata = color?.hdrStaticInfo?.parseCta861StaticMetadata()
    return VideoColorInfo(
        dynamicRange = dynamicRange,
        bitDepth = color?.lumaBitdepth?.takeIf { it > 0 },
        primaries =
            when (color?.colorSpace) {
                C.COLOR_SPACE_BT601 -> VideoColorPrimaries.BT601_625
                C.COLOR_SPACE_BT709 -> VideoColorPrimaries.BT709
                C.COLOR_SPACE_BT2020 -> VideoColorPrimaries.BT2020
                else -> VideoColorPrimaries.UNKNOWN
            },
        transfer = transfer,
        matrix =
            when (color?.colorSpace) {
                C.COLOR_SPACE_BT601 -> VideoColorMatrix.BT601
                C.COLOR_SPACE_BT709 -> VideoColorMatrix.BT709
                C.COLOR_SPACE_BT2020 -> VideoColorMatrix.BT2020_NCL
                else -> VideoColorMatrix.UNKNOWN
            },
        range =
            when (color?.colorRange) {
                C.COLOR_RANGE_LIMITED -> VideoColorRange.LIMITED
                C.COLOR_RANGE_FULL -> VideoColorRange.FULL
                else -> VideoColorRange.UNKNOWN
            },
        masteringDisplay = staticMetadata?.first,
        contentLightLevel = staticMetadata?.second,
        dolbyVision = dolbyVision,
    )
}

/**
 * Returns true when a later Media3 format only refines metadata for the already configured Android
 * output signal. In particular, Dolby Vision manifests often expose the profile before the decoder
 * reports PQ/BT.2020 details and the final level.
 */
internal fun VideoColorInfo.hasSameAndroidOutputSignalAs(other: VideoColorInfo): Boolean {
    if (androidOutputSignalRange() != other.androidOutputSignalRange()) return false
    if (
        dynamicRange == VideoDynamicRange.DOLBY_VISION &&
        other.dynamicRange == VideoDynamicRange.DOLBY_VISION
    ) {
        val profile = dolbyVision?.profile
        val otherProfile = other.dolbyVision?.profile
        return profile == null || otherProfile == null || profile == otherProfile
    }
    return transfer == other.transfer ||
        transfer == VideoColorTransfer.UNKNOWN ||
        other.transfer == VideoColorTransfer.UNKNOWN
}

private fun VideoColorInfo.androidOutputSignalRange(): VideoDynamicRange =
    if (dynamicRange == VideoDynamicRange.HDR10_PLUS) VideoDynamicRange.HDR10 else dynamicRange

private fun ColorInfo?.toVideoColorTransfer(): VideoColorTransfer =
    when (this?.colorTransfer) {
        C.COLOR_TRANSFER_LINEAR -> VideoColorTransfer.LINEAR
        C.COLOR_TRANSFER_SDR, C.COLOR_TRANSFER_GAMMA_2_2 -> VideoColorTransfer.SDR
        C.COLOR_TRANSFER_SRGB -> VideoColorTransfer.SRGB
        C.COLOR_TRANSFER_ST2084 -> VideoColorTransfer.PQ
        C.COLOR_TRANSFER_HLG -> VideoColorTransfer.HLG
        else -> VideoColorTransfer.UNKNOWN
    }

private fun Format.dolbyVisionInfoOrNull(): DolbyVisionInfo? {
    val codec =
        codecs
            ?.split(',')
            ?.map(String::trim)
            ?.firstOrNull { candidate -> candidate.isDolbyVisionCodec() }
    if (sampleMimeType != MimeTypes.VIDEO_DOLBY_VISION && codec == null) return null
    val parts = codec?.split('.').orEmpty()
    val profile = parts.getOrNull(1)?.toIntOrNull()
    val level = parts.getOrNull(2)?.toIntOrNull()
    return DolbyVisionInfo(
        profile = profile,
        level = level,
        // MIME/codec signaling identifies a DV stream, but does not expose the configuration
        // record's RPU-present flag or prove that a valid RPU was parsed.
        hasRpu = null,
        enhancementLayer = if (profile == 7) DolbyVisionEnhancementLayer.UNKNOWN else DolbyVisionEnhancementLayer.NONE,
        // Profile 7 is defined around an HDR10-compatible base layer. Profile 8 compatibility
        // still needs the configuration record's compatibility id, which Format does not expose.
        hasHdr10CompatibleBaseLayer = profile == DOLBY_VISION_PROFILE_7,
        // Media3 does expose the base signal's transfer, so HLG is sufficient evidence for P8.4.
        hasHlgCompatibleBaseLayer =
            profile == DOLBY_VISION_PROFILE_8 &&
                colorInfo.toVideoColorTransfer() == VideoColorTransfer.HLG,
    )
}

private fun String.isDolbyVisionCodec(): Boolean {
    val normalized = lowercase()
    return normalized.startsWith("dvav") ||
        normalized.startsWith("dva1") ||
        normalized.startsWith("dvhe") ||
        normalized.startsWith("dvh1")
}

internal fun ByteArray.parseCta861StaticMetadata(): Pair<MasteringDisplayMetadata?, ContentLightLevelMetadata?>? {
    if (size < CTA_861_STATIC_METADATA_SIZE || unsignedByte(0) != CTA_861_TYPE_1) return null
    val mastering =
        MasteringDisplayMetadata(
            redX = unsignedShortLe(1) / CHROMATICITY_DENOMINATOR,
            redY = unsignedShortLe(3) / CHROMATICITY_DENOMINATOR,
            greenX = unsignedShortLe(5) / CHROMATICITY_DENOMINATOR,
            greenY = unsignedShortLe(7) / CHROMATICITY_DENOMINATOR,
            blueX = unsignedShortLe(9) / CHROMATICITY_DENOMINATOR,
            blueY = unsignedShortLe(11) / CHROMATICITY_DENOMINATOR,
            whiteX = unsignedShortLe(13) / CHROMATICITY_DENOMINATOR,
            whiteY = unsignedShortLe(15) / CHROMATICITY_DENOMINATOR,
            maxLuminanceNits = unsignedShortLe(17).toFloat(),
            minLuminanceNits = unsignedShortLe(19) / MIN_LUMINANCE_DENOMINATOR,
        ).takeIf(MasteringDisplayMetadata::hasSpecifiedValue)
    val maxCll = unsignedShortLe(21).takeIf { it > 0 }
    val maxFall = unsignedShortLe(23).takeIf { it > 0 }
    val contentLightLevel =
        ContentLightLevelMetadata(maxCll, maxFall).takeIf { maxCll != null || maxFall != null }
    return (mastering to contentLightLevel).takeIf { mastering != null || contentLightLevel != null }
}

private fun MasteringDisplayMetadata.hasSpecifiedValue(): Boolean =
    redX > 0f ||
        redY > 0f ||
        greenX > 0f ||
        greenY > 0f ||
        blueX > 0f ||
        blueY > 0f ||
        whiteX > 0f ||
        whiteY > 0f ||
        minLuminanceNits > 0f ||
        maxLuminanceNits > 0f

private fun ByteArray.unsignedByte(index: Int): Int = this[index].toInt() and BYTE_MASK

private fun ByteArray.unsignedShortLe(index: Int): Int = unsignedByte(index) or (unsignedByte(index + 1) shl BYTE_BITS)

private const val CTA_861_STATIC_METADATA_SIZE = 25
private const val CTA_861_TYPE_1 = 0
private const val CHROMATICITY_DENOMINATOR = 50_000f
private const val MIN_LUMINANCE_DENOMINATOR = 10_000f
private const val BYTE_MASK = 0xff
private const val BYTE_BITS = 8
private const val DOLBY_VISION_PROFILE_7 = 7
private const val DOLBY_VISION_PROFILE_8 = 8
