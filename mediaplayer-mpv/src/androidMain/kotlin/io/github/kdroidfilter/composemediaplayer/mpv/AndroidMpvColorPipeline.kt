package io.github.kdroidfilter.composemediaplayer.mpv

import android.content.Context
import android.hardware.display.DisplayManager
import android.view.Display
import io.github.kdroidfilter.composemediaplayer.ColorConversionCapabilities
import io.github.kdroidfilter.composemediaplayer.ColorPipelineFallbackReason
import io.github.kdroidfilter.composemediaplayer.ColorPipelineRenderer
import io.github.kdroidfilter.composemediaplayer.ColorPipelineVerification
import io.github.kdroidfilter.composemediaplayer.ContentLightLevelMetadata
import io.github.kdroidfilter.composemediaplayer.DecoderColorCapabilities
import io.github.kdroidfilter.composemediaplayer.DisplayColorCapabilities
import io.github.kdroidfilter.composemediaplayer.DolbyVisionInfo
import io.github.kdroidfilter.composemediaplayer.DynamicMetadataHandling
import io.github.kdroidfilter.composemediaplayer.DynamicRangePolicy
import io.github.kdroidfilter.composemediaplayer.Hdr10PlusInfo
import io.github.kdroidfilter.composemediaplayer.MasteringDisplayMetadata
import io.github.kdroidfilter.composemediaplayer.RendererColorCapabilities
import io.github.kdroidfilter.composemediaplayer.VideoColorInfo
import io.github.kdroidfilter.composemediaplayer.VideoColorMatrix
import io.github.kdroidfilter.composemediaplayer.VideoColorPipelinePlanner
import io.github.kdroidfilter.composemediaplayer.VideoColorPipelineRequest
import io.github.kdroidfilter.composemediaplayer.VideoColorPipelineStatus
import io.github.kdroidfilter.composemediaplayer.VideoColorPrimaries
import io.github.kdroidfilter.composemediaplayer.VideoColorRange
import io.github.kdroidfilter.composemediaplayer.VideoColorTransfer
import io.github.kdroidfilter.composemediaplayer.VideoDynamicRange
import io.github.kdroidfilter.composemediaplayer.VideoSurfaceKind
import io.github.kdroidfilter.composemediaplayer.toStatus
import io.github.shusek.kmediampv.runtime.android.MpvAndroidPlaybackSnapshot
import io.github.shusek.kmediampv.runtime.android.MpvAndroidSurfaceDynamicRange
import io.github.shusek.kmediampv.runtime.android.MpvAndroidVideoColorInfo
import kotlin.math.roundToInt

internal val MPV_ANDROID_RENDERER_CAPABILITIES =
    RendererColorCapabilities(
        controlledHdrDynamicRanges = setOf(VideoDynamicRange.HDR10, VideoDynamicRange.HLG),
        supportsToneMappingToSdr = true,
    )

@Suppress("DEPRECATION")
internal fun Context.mpvDisplayColorCapabilities(): DisplayColorCapabilities {
    val display =
        getSystemService(DisplayManager::class.java)
            ?.getDisplay(Display.DEFAULT_DISPLAY)
            ?: return DisplayColorCapabilities()
    val hdr =
        display.hdrCapabilities
            ?: return DisplayColorCapabilities(
                isKnown = true,
                supportedDynamicRanges = setOf(VideoDynamicRange.SDR),
            )
    val supported =
        buildSet {
            add(VideoDynamicRange.SDR)
            hdr.supportedHdrTypes.forEach { type ->
                when (type) {
                    Display.HdrCapabilities.HDR_TYPE_HDR10 -> add(VideoDynamicRange.HDR10)
                    Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS -> add(VideoDynamicRange.HDR10_PLUS)
                    Display.HdrCapabilities.HDR_TYPE_HLG -> add(VideoDynamicRange.HLG)
                    Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION -> add(VideoDynamicRange.DOLBY_VISION)
                }
            }
        }
    return DisplayColorCapabilities(
        isKnown = true,
        supportedDynamicRanges = supported,
        minLuminanceNits = hdr.desiredMinLuminance.validLuminance(allowZero = true),
        maxLuminanceNits = hdr.desiredMaxLuminance.validLuminance(),
    )
}

internal fun initialAndroidMpvColorPipelineStatus(display: DisplayColorCapabilities): VideoColorPipelineStatus =
    VideoColorPipelineStatus(
        requestedDynamicRangePolicy = DynamicRangePolicy.AUTO,
        display = display,
        surface = VideoSurfaceKind.CONTROLLED_GPU_SURFACE,
        rendererCapabilities = MPV_ANDROID_RENDERER_CAPABILITIES,
        conversionCapabilities = ColorConversionCapabilities(),
        fallbackReason = ColorPipelineFallbackReason.SOURCE_COLOR_UNKNOWN,
        detail = "Waiting for libmpv to decode and configure an Android Vulkan frame.",
    )

internal fun MpvAndroidPlaybackSnapshot.toAndroidMpvColorPipelineStatus(
    display: DisplayColorCapabilities,
): VideoColorPipelineStatus {
    val source = sourceColorInfo.toVideoColorInfo()
    if (source.dynamicRange == VideoDynamicRange.UNKNOWN) {
        return initialAndroidMpvColorPipelineStatus(display).copy(
            source = source,
            decoderName = currentHardwareDecoder,
        )
    }
    val decoder =
        DecoderColorCapabilities(
            isKnown = true,
            supportedDynamicRanges = setOf(source.dynamicRange),
            maxBitDepth = source.bitDepth,
        )
    val request =
        VideoColorPipelineRequest(
            source = source,
            display = display,
            decoder = decoder,
            renderer = MPV_ANDROID_RENDERER_CAPABILITIES,
            conversion = ColorConversionCapabilities(),
            dynamicRangePolicy = DynamicRangePolicy.AUTO,
            nativeSurfaceAvailable = false,
            surfaceKind = VideoSurfaceKind.CONTROLLED_GPU_SURFACE,
        )
    val plan = VideoColorPipelinePlanner.plan(request)
    val actualOutput = verifiedMpvSurfaceOutput()
    val rendererConfigured =
        currentVideoOutput == "gpu-next" &&
            currentGpuContext == "androidvk" &&
            isVideoOutputConfigurationKnown &&
            isVideoOutputConfigured &&
            surfaceOutputInfo.pixelFormat > 0 &&
            actualOutput != VideoDynamicRange.UNKNOWN
    val verification =
        if (rendererConfigured && actualOutput == plan.outputDynamicRange) {
            ColorPipelineVerification.RENDERER_CONFIGURED
        } else {
            ColorPipelineVerification.NONE
        }
    val plannedStatus = plan.toStatus(request, currentHardwareDecoder, verification)
    if (!rendererConfigured || actualOutput == plan.outputDynamicRange) {
        return plannedStatus.copy(detail = runtimeColorDetail(plannedStatus.detail))
    }

    val fallback =
        when {
            source.dynamicRange == VideoDynamicRange.DOLBY_VISION &&
                actualOutput == VideoDynamicRange.HDR10 ->
                ColorPipelineFallbackReason.DOLBY_VISION_BASE_LAYER_USED
            source.dynamicRange == VideoDynamicRange.HDR10_PLUS &&
                actualOutput == VideoDynamicRange.HDR10 ->
                ColorPipelineFallbackReason.DYNAMIC_METADATA_UNSUPPORTED
            source.isHdr && actualOutput == VideoDynamicRange.SDR ->
                ColorPipelineFallbackReason.HDR_SURFACE_UNAVAILABLE
            else -> ColorPipelineFallbackReason.RENDERER_CONFIGURATION_FAILED
        }
    return plannedStatus.copy(
        outputDynamicRange = actualOutput,
        outputDolbyVision = null,
        metadataHandling = actualMetadataHandling(source, actualOutput),
        verification = ColorPipelineVerification.RENDERER_CONFIGURED,
        renderer =
            if (actualOutput == VideoDynamicRange.SDR) {
                ColorPipelineRenderer.CONTROLLED_SDR
            } else {
                ColorPipelineRenderer.CONTROLLED_HDR
            },
        requestHonored = false,
        fallbackReason = fallback,
        detail = runtimeColorDetail(plannedStatus.detail),
    )
}

internal fun mpvVideoColorInfo(
    pixelFormat: String?,
    primaries: String?,
    transfer: String?,
    matrix: String?,
    range: String?,
    maxCll: Double,
    maxFall: Double,
    hasHdr10PlusSceneMetadata: Boolean,
    minimumLuminanceNits: Double = Double.NaN,
    maximumLuminanceNits: Double = Double.NaN,
    primaryRedX: Double = Double.NaN,
    primaryRedY: Double = Double.NaN,
    primaryGreenX: Double = Double.NaN,
    primaryGreenY: Double = Double.NaN,
    primaryBlueX: Double = Double.NaN,
    primaryBlueY: Double = Double.NaN,
    primaryWhiteX: Double = Double.NaN,
    primaryWhiteY: Double = Double.NaN,
): VideoColorInfo {
    val mappedTransfer = transfer.toMpvColorTransfer()
    val mappedMatrix = matrix.toMpvColorMatrix()
    val dynamicRange =
        when {
            matrix.equals("dolbyvision", ignoreCase = true) -> VideoDynamicRange.DOLBY_VISION
            mappedTransfer == VideoColorTransfer.PQ && hasHdr10PlusSceneMetadata ->
                VideoDynamicRange.HDR10_PLUS
            mappedTransfer == VideoColorTransfer.PQ -> VideoDynamicRange.HDR10
            mappedTransfer == VideoColorTransfer.HLG -> VideoDynamicRange.HLG
            mappedTransfer != VideoColorTransfer.UNKNOWN -> VideoDynamicRange.SDR
            else -> VideoDynamicRange.UNKNOWN
        }
    val mappedPrimaries = primaries.toMpvColorPrimaries()
    return VideoColorInfo(
        dynamicRange = dynamicRange,
        bitDepth = mpvPixelFormatBitDepth(pixelFormat),
        primaries = mappedPrimaries,
        transfer = mappedTransfer,
        matrix = mappedMatrix,
        range = range.toMpvColorRange(),
        masteringDisplay =
            masteringDisplayMetadata(
                primaries = mappedPrimaries,
                minimumLuminanceNits = minimumLuminanceNits,
                maximumLuminanceNits = maximumLuminanceNits,
                primaryRedX = primaryRedX,
                primaryRedY = primaryRedY,
                primaryGreenX = primaryGreenX,
                primaryGreenY = primaryGreenY,
                primaryBlueX = primaryBlueX,
                primaryBlueY = primaryBlueY,
                primaryWhiteX = primaryWhiteX,
                primaryWhiteY = primaryWhiteY,
            ),
        contentLightLevel = contentLightLevel(maxCll, maxFall),
        hdr10Plus = Hdr10PlusInfo().takeIf { dynamicRange == VideoDynamicRange.HDR10_PLUS },
        dolbyVision = DolbyVisionInfo().takeIf { dynamicRange == VideoDynamicRange.DOLBY_VISION },
    )
}

internal fun mpvPixelFormatBitDepth(pixelFormat: String?): Int? {
    val normalized = pixelFormat?.lowercase()?.trim().orEmpty()
    if (normalized.isEmpty()) return null
    if (normalized.startsWith("p010") || normalized.startsWith("p210") || normalized.startsWith("p410")) {
        return 10
    }
    Regex("(?:p|gray|rgb|bgr)(9|10|12|14|16)(?:le|be)?")
        .find(normalized)
        ?.groupValues
        ?.getOrNull(1)
        ?.toIntOrNull()
        ?.let { return it }
    if (normalized.startsWith("rgb48") || normalized.startsWith("bgr48") || normalized.startsWith("rgba64")) {
        return 16
    }
    return 8.takeIf {
        normalized in setOf("nv12", "nv21", "yuv420p", "yuv422p", "yuv444p", "rgba", "bgra", "rgb0", "bgr0")
    }
}

private fun MpvAndroidVideoColorInfo.toVideoColorInfo(): VideoColorInfo =
    mpvVideoColorInfo(
        pixelFormat = pixelFormat,
        primaries = primaries,
        transfer = transfer,
        matrix = matrix,
        range = range,
        maxCll = maximumContentLightLevelNits,
        maxFall = maximumFrameAverageLightLevelNits,
        hasHdr10PlusSceneMetadata = hasHdr10PlusSceneMetadata(),
        minimumLuminanceNits = minimumLuminanceNits,
        maximumLuminanceNits = maximumLuminanceNits,
        primaryRedX = primaryRedX,
        primaryRedY = primaryRedY,
        primaryGreenX = primaryGreenX,
        primaryGreenY = primaryGreenY,
        primaryBlueX = primaryBlueX,
        primaryBlueY = primaryBlueY,
        primaryWhiteX = primaryWhiteX,
        primaryWhiteY = primaryWhiteY,
    )

private fun MpvAndroidPlaybackSnapshot.verifiedMpvSurfaceOutput(): VideoDynamicRange =
    when (surfaceOutputInfo.dynamicRange) {
        MpvAndroidSurfaceDynamicRange.SDR -> VideoDynamicRange.SDR
        MpvAndroidSurfaceDynamicRange.HDR10 ->
            VideoDynamicRange.HDR10.takeIf { surfaceOutputInfo.isHdrCapablePixelFormat }
                ?: VideoDynamicRange.UNKNOWN
        MpvAndroidSurfaceDynamicRange.HLG ->
            VideoDynamicRange.HLG.takeIf { surfaceOutputInfo.isHdrCapablePixelFormat }
                ?: VideoDynamicRange.UNKNOWN
        MpvAndroidSurfaceDynamicRange.UNKNOWN -> VideoDynamicRange.UNKNOWN
    }

private fun MpvAndroidPlaybackSnapshot.runtimeColorDetail(planned: String?): String =
    listOfNotNull(
        planned,
        "libmpv ${currentVideoOutput ?: "unknown"}/${currentGpuContext ?: "unknown"}; " +
            "ANativeWindow dataspace=${surfaceOutputInfo.dataSpace}, format=${surfaceOutputInfo.pixelFormat}.",
    ).joinToString(" ")

private fun actualMetadataHandling(
    source: VideoColorInfo,
    output: VideoDynamicRange,
): DynamicMetadataHandling =
    when {
        source.dynamicRange == VideoDynamicRange.HDR10_PLUS ||
            source.dynamicRange == VideoDynamicRange.DOLBY_VISION ->
            DynamicMetadataHandling.DROPPED
        source.isHdr && source.dynamicRange == output -> DynamicMetadataHandling.PASSTHROUGH
        source.isHdr -> DynamicMetadataHandling.CONVERTED
        else -> DynamicMetadataHandling.NONE
    }

private fun String?.toMpvColorPrimaries(): VideoColorPrimaries =
    when (this?.lowercase()) {
        "bt.601-525" -> VideoColorPrimaries.BT601_525
        "bt.601-625" -> VideoColorPrimaries.BT601_625
        "bt.709" -> VideoColorPrimaries.BT709
        "bt.2020" -> VideoColorPrimaries.BT2020
        "display-p3" -> VideoColorPrimaries.DISPLAY_P3
        else -> VideoColorPrimaries.UNKNOWN
    }

private fun String?.toMpvColorTransfer(): VideoColorTransfer =
    when (this?.lowercase()) {
        "pq" -> VideoColorTransfer.PQ
        "hlg" -> VideoColorTransfer.HLG
        "srgb" -> VideoColorTransfer.SRGB
        "linear" -> VideoColorTransfer.LINEAR
        "bt.1886", "gamma1.8", "gamma2.0", "gamma2.2", "gamma2.4", "gamma2.6", "gamma2.8" ->
            VideoColorTransfer.SDR
        else -> VideoColorTransfer.UNKNOWN
    }

private fun String?.toMpvColorMatrix(): VideoColorMatrix =
    when (this?.lowercase()) {
        "rgb" -> VideoColorMatrix.RGB
        "bt.601" -> VideoColorMatrix.BT601
        "bt.709" -> VideoColorMatrix.BT709
        "bt.2020-ncl" -> VideoColorMatrix.BT2020_NCL
        "bt.2020-cl" -> VideoColorMatrix.BT2020_CL
        "bt.2100-pq", "bt.2100-hlg", "dolbyvision" -> VideoColorMatrix.ICTCP
        else -> VideoColorMatrix.UNKNOWN
    }

private fun String?.toMpvColorRange(): VideoColorRange =
    when (this?.lowercase()) {
        "limited" -> VideoColorRange.LIMITED
        "full" -> VideoColorRange.FULL
        else -> VideoColorRange.UNKNOWN
    }

private fun contentLightLevel(
    maxCll: Double,
    maxFall: Double,
): ContentLightLevelMetadata? {
    val cll = maxCll.validMetadataLuminance()
    val fall = maxFall.validMetadataLuminance()
    return ContentLightLevelMetadata(cll, fall).takeIf { cll != null || fall != null }
}

private fun masteringDisplayMetadata(
    primaries: VideoColorPrimaries,
    minimumLuminanceNits: Double,
    maximumLuminanceNits: Double,
    primaryRedX: Double,
    primaryRedY: Double,
    primaryGreenX: Double,
    primaryGreenY: Double,
    primaryBlueX: Double,
    primaryBlueY: Double,
    primaryWhiteX: Double,
    primaryWhiteY: Double,
): MasteringDisplayMetadata? {
    val minimum = minimumLuminanceNits.validFloat(allowZero = true) ?: return null
    val maximum = maximumLuminanceNits.validFloat() ?: return null
    if (minimum > maximum) return null
    val explicit =
        listOf(
            primaryRedX,
            primaryRedY,
            primaryGreenX,
            primaryGreenY,
            primaryBlueX,
            primaryBlueY,
            primaryWhiteX,
            primaryWhiteY,
        ).map(Double::validChromaticity)
            .takeIf { values -> values.all { it != null } }
            ?.map { value -> requireNotNull(value) }
    val chromaticity = explicit ?: primaries.standardChromaticity() ?: return null
    return MasteringDisplayMetadata(
        redX = chromaticity[0],
        redY = chromaticity[1],
        greenX = chromaticity[2],
        greenY = chromaticity[3],
        blueX = chromaticity[4],
        blueY = chromaticity[5],
        whiteX = chromaticity[6],
        whiteY = chromaticity[7],
        minLuminanceNits = minimum,
        maxLuminanceNits = maximum,
    )
}

private fun VideoColorPrimaries.standardChromaticity(): List<Float>? =
    when (this) {
        VideoColorPrimaries.BT709 -> listOf(0.64f, 0.33f, 0.30f, 0.60f, 0.15f, 0.06f, 0.3127f, 0.3290f)
        VideoColorPrimaries.BT2020 -> listOf(0.708f, 0.292f, 0.170f, 0.797f, 0.131f, 0.046f, 0.3127f, 0.3290f)
        VideoColorPrimaries.DISPLAY_P3 -> listOf(0.68f, 0.32f, 0.265f, 0.69f, 0.15f, 0.06f, 0.3127f, 0.3290f)
        else -> null
    }

private fun Double.validMetadataLuminance(): Int? =
    takeIf { it.isFinite() && it > 0.0 && it <= 100_000.0 }
        ?.roundToInt()
        ?.takeIf { it > 0 }

private fun Double.validFloat(allowZero: Boolean = false): Float? =
    takeIf { it.isFinite() && it <= 100_000.0 && if (allowZero) it >= 0.0 else it > 0.0 }
        ?.toFloat()

private fun Double.validChromaticity(): Float? = takeIf { it.isFinite() && it in 0.0..1.0 }?.toFloat()

private fun Float.validLuminance(allowZero: Boolean = false): Float? =
    takeIf { it.isFinite() && if (allowZero) it >= 0f else it > 0f }
