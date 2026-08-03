package io.github.kdroidfilter.composemediaplayer.mac

import io.github.kdroidfilter.composemediaplayer.ContentLightLevelMetadata
import io.github.kdroidfilter.composemediaplayer.DisplayColorCapabilities
import io.github.kdroidfilter.composemediaplayer.DolbyVisionEnhancementLayer
import io.github.kdroidfilter.composemediaplayer.DolbyVisionInfo
import io.github.kdroidfilter.composemediaplayer.Hdr10PlusInfo
import io.github.kdroidfilter.composemediaplayer.MasteringDisplayMetadata
import io.github.kdroidfilter.composemediaplayer.VideoColorInfo
import io.github.kdroidfilter.composemediaplayer.VideoColorMatrix
import io.github.kdroidfilter.composemediaplayer.VideoColorPrimaries
import io.github.kdroidfilter.composemediaplayer.VideoColorRange
import io.github.kdroidfilter.composemediaplayer.VideoColorTransfer
import io.github.kdroidfilter.composemediaplayer.VideoDynamicRange

internal fun String?.toMacVideoColorInfo(): VideoColorInfo {
    val values = this.toNativeValueMap()
    val reportedDynamicRange = values.enumValue<VideoDynamicRange>("dynamicRange") ?: VideoDynamicRange.UNKNOWN
    val hdr10Plus =
        values["hdr10PlusAppId"]
            .positiveIntOrNull()
            ?.let { applicationIdentifier ->
                Hdr10PlusInfo(
                    applicationIdentifier = applicationIdentifier,
                    applicationVersion = values["hdr10PlusAppVersion"].positiveIntOrNull(),
                    hasPerFrameMetadata = values["hdr10PlusPerFrame"] == "1",
                )
            }?.takeIf {
                reportedDynamicRange == VideoDynamicRange.HDR10_PLUS &&
                    it.applicationIdentifier == HDR10_PLUS_APPLICATION_IDENTIFIER &&
                    it.hasPerFrameMetadata
            }
    val dynamicRange =
        if (reportedDynamicRange == VideoDynamicRange.HDR10_PLUS && hdr10Plus == null) {
            VideoDynamicRange.HDR10
        } else {
            reportedDynamicRange
        }
    val masteringDisplay =
        listOf(
            "masterRedX",
            "masterRedY",
            "masterGreenX",
            "masterGreenY",
            "masterBlueX",
            "masterBlueY",
            "masterWhiteX",
            "masterWhiteY",
            "masterMinNits",
            "masterMaxNits",
        ).map(values::finiteFloat)
            .takeIf { components -> components.all { it != null } }
            ?.map { requireNotNull(it) }
            ?.let { components ->
                runCatching {
                    MasteringDisplayMetadata(
                        redX = components[0],
                        redY = components[1],
                        greenX = components[2],
                        greenY = components[3],
                        blueX = components[4],
                        blueY = components[5],
                        whiteX = components[6],
                        whiteY = components[7],
                        minLuminanceNits = components[8],
                        maxLuminanceNits = components[9],
                    )
                }.getOrNull()
            }
    val maxCll = values["maxCll"].positiveIntOrNull()
    val maxFall = values["maxFall"].positiveIntOrNull()
    val contentLightLevel =
        if (maxCll != null || maxFall != null) {
            ContentLightLevelMetadata(maxCll, maxFall)
        } else {
            null
        }
    val isDolbyVision = dynamicRange == VideoDynamicRange.DOLBY_VISION
    val dolbyVisionProfile = values["dvProfile"].positiveIntOrNull()
    val dolbyVisionCompatibilityId = values["dvCompatibilityId"].nonNegativeIntOrNull()
    return VideoColorInfo(
        dynamicRange = dynamicRange,
        bitDepth = values["bitDepth"].positiveIntOrNull()?.takeIf { it <= MAX_COMPONENT_BIT_DEPTH },
        primaries = values.enumValue<VideoColorPrimaries>("primaries") ?: VideoColorPrimaries.UNKNOWN,
        transfer = values.enumValue<VideoColorTransfer>("transfer") ?: VideoColorTransfer.UNKNOWN,
        matrix = values.enumValue<VideoColorMatrix>("matrix") ?: VideoColorMatrix.UNKNOWN,
        range = values.enumValue<VideoColorRange>("range") ?: VideoColorRange.UNKNOWN,
        masteringDisplay = masteringDisplay,
        contentLightLevel = contentLightLevel,
        hdr10Plus = hdr10Plus,
        dolbyVision =
            if (isDolbyVision) {
                DolbyVisionInfo(
                    profile = dolbyVisionProfile,
                    level = values["dvLevel"].positiveIntOrNull(),
                    hasRpu = values["dvHasRpu"] == "1",
                    enhancementLayer =
                        if (values["dvHasEl"] == "1") {
                            DolbyVisionEnhancementLayer.UNKNOWN
                        } else {
                            DolbyVisionEnhancementLayer.NONE
                        },
                    hasHdr10CompatibleBaseLayer =
                        values["dvHasBase"] == "1" &&
                            (
                                dolbyVisionProfile == DOLBY_VISION_PROFILE_7 ||
                                    dolbyVisionCompatibilityId == HDR10_DOVI_COMPATIBILITY_ID
                            ),
                    hasHlgCompatibleBaseLayer =
                        values["dvHasBase"] == "1" &&
                            dolbyVisionCompatibilityId == HLG_DOVI_COMPATIBILITY_ID,
                )
            } else {
                null
            },
    )
}

internal fun String?.toMacDisplayColorCapabilities(): DisplayColorCapabilities {
    val values = this.toNativeValueMap()
    if (values["known"] != "1") return DisplayColorCapabilities()
    val hasHdrOutput = values["native"] == "1" && values["eligible"] != "0"
    val ranges =
        buildSet {
            add(VideoDynamicRange.SDR)
            if (hasHdrOutput && values["hdr10"] == "SUPPORTED") add(VideoDynamicRange.HDR10)
            if (hasHdrOutput && values["hlg"] == "SUPPORTED") add(VideoDynamicRange.HLG)
            if (hasHdrOutput && values["dolbyVision"] == "SUPPORTED") add(VideoDynamicRange.DOLBY_VISION)
        }
    return DisplayColorCapabilities(
        isKnown = true,
        supportedDynamicRanges = ranges,
        // EDR headroom is a ratio, not an absolute panel luminance. Do not manufacture nits from it.
        minLuminanceNits = null,
        maxLuminanceNits = null,
        referenceWhiteNits = null,
    )
}

private fun String?.toNativeValueMap(): Map<String, String> =
    this
        ?.split(';')
        ?.mapNotNull { entry ->
            val separator = entry.indexOf('=')
            if (separator <= 0) return@mapNotNull null
            entry.substring(0, separator).trim() to entry.substring(separator + 1).trim()
        }?.toMap()
        .orEmpty()

private inline fun <reified T : Enum<T>> Map<String, String>.enumValue(key: String): T? =
    this[key]?.let { value -> enumValues<T>().firstOrNull { it.name == value } }

private fun Map<String, String>.finiteFloat(key: String): Float? = this[key]?.toFloatOrNull()?.takeIf(Float::isFinite)

private fun String?.positiveIntOrNull(): Int? = this?.toIntOrNull()?.takeIf { it > 0 }

private fun String?.nonNegativeIntOrNull(): Int? = this?.toIntOrNull()?.takeIf { it >= 0 }

private const val DOLBY_VISION_PROFILE_7 = 7
private const val HDR10_DOVI_COMPATIBILITY_ID = 1
private const val HLG_DOVI_COMPATIBILITY_ID = 4
private const val HDR10_PLUS_APPLICATION_IDENTIFIER = 4
private const val MAX_COMPONENT_BIT_DEPTH = 16
