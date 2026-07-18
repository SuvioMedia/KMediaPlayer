package io.github.kdroidfilter.composemediaplayer.mac

import io.github.kdroidfilter.composemediaplayer.DynamicMetadataHandling
import io.github.kdroidfilter.composemediaplayer.VideoColorInfo
import io.github.kdroidfilter.composemediaplayer.VideoColorMatrix
import io.github.kdroidfilter.composemediaplayer.VideoColorPrimaries
import io.github.kdroidfilter.composemediaplayer.VideoColorRange
import io.github.kdroidfilter.composemediaplayer.VideoColorTransfer
import io.github.kdroidfilter.composemediaplayer.VideoDynamicRange
import io.github.kdroidfilter.composemediaplayer.VideoProjectionRenderOptions
import io.github.kdroidfilter.composemediaplayer.VideoProjectionSettings
import io.github.kdroidfilter.composemediaplayer.VideoProjectionViewSettings
import io.github.kdroidfilter.composemediaplayer.VideoTextureCrop
import io.github.kdroidfilter.composemediaplayer.VideoTextureWindow
import io.github.kdroidfilter.composemediaplayer.projectionShaderCode
import io.github.kdroidfilter.composemediaplayer.toVideoProjectionRenderPlan

internal fun macMetalProjectionConfiguration(
    projection: VideoProjectionSettings,
    projectionView: VideoProjectionViewSettings,
    textureCrop: VideoTextureCrop,
    source: VideoColorInfo,
    outputDynamicRange: VideoDynamicRange,
    metadataHandling: DynamicMetadataHandling = DynamicMetadataHandling.NONE,
    displayPeakLuminanceNits: Float? = null,
): String {
    val normalizedProjection = projection.normalized()
    val normalizedView = projectionView.normalized()
    val plan =
        normalizedProjection.toVideoProjectionRenderPlan(
            VideoProjectionRenderOptions(textureCrop = textureCrop),
        )
    return buildString {
        append("enabled=1")
        appendField("type", normalizedProjection.projectionType.projectionShaderCode)
        appendField("fov", plan.mesh.horizontalFovDegrees)
        appendField("stereo", if (plan.stereo) 1 else 0)
        appendField("left", plan.leftEyeTexture.serialized())
        appendField("right", plan.rightEyeTexture.serialized())
        appendField("yaw", normalizedView.yawDegrees)
        appendField("pitch", normalizedView.pitchDegrees)
        appendField("roll", normalizedView.rollDegrees)
        appendField("zoom", normalizedView.zoom)
        appendField("transfer", source.transfer.macMetalCode)
        appendField("matrix", source.matrix.macMetalCode)
        appendField("primaries", source.macMetalPrimariesCode)
        appendField("outputHdr", if (outputDynamicRange.isMacMetalHdrOutput) 1 else 0)
        appendField("peak", source.macMetalPeakNits)
        appendField(
            "displayPeak",
            if (outputDynamicRange.isMacMetalHdrOutput) {
                displayPeakLuminanceNits?.coerceIn(MINIMUM_HDR_PEAK_NITS, MAXIMUM_HDR_PEAK_NITS)
                    ?: DEFAULT_HDR_PEAK_NITS
            } else {
                SDR_REFERENCE_WHITE_NITS
            },
        )
        appendField(
            "hdr10Plus",
            if (
                source.dynamicRange == VideoDynamicRange.HDR10_PLUS &&
                metadataHandling == DynamicMetadataHandling.APPLIED_BY_RENDERER
            ) {
                1
            } else {
                0
            },
        )
        appendField(
            "tenBit",
            if (source.bitDepth?.let { it > MAXIMUM_NV12_BIT_DEPTH } == true || source.isHdr) 1 else 0,
        )
        appendField("fullRange", if (source.range == VideoColorRange.FULL) 1 else 0)
    }
}

internal const val MAC_METAL_PROJECTION_DISABLED_CONFIGURATION = "enabled=0"

private fun StringBuilder.appendField(
    name: String,
    value: Any,
) {
    append(';')
    append(name)
    append('=')
    append(value)
}

private fun VideoTextureWindow.serialized(): String = "$left,$top,$right,$bottom,${rotation.ordinal}"

private val VideoColorTransfer.macMetalCode: Int
    get() =
        when (this) {
            VideoColorTransfer.PQ -> 1
            VideoColorTransfer.HLG -> 2
            VideoColorTransfer.SRGB -> MAC_METAL_TRANSFER_SRGB
            VideoColorTransfer.LINEAR -> MAC_METAL_TRANSFER_LINEAR
            else -> 0
        }

private val VideoColorInfo.macMetalPrimariesCode: Int
    get() =
        when (primaries) {
            VideoColorPrimaries.BT2020 -> 0
            VideoColorPrimaries.BT709 -> 1
            VideoColorPrimaries.DISPLAY_P3 -> 2
            VideoColorPrimaries.BT601_525 -> MAC_METAL_PRIMARIES_BT601_525
            VideoColorPrimaries.BT601_625 -> MAC_METAL_PRIMARIES_BT601_625
            VideoColorPrimaries.UNKNOWN ->
                when (matrix) {
                    VideoColorMatrix.BT2020_NCL, VideoColorMatrix.BT2020_CL, VideoColorMatrix.ICTCP -> 0
                    VideoColorMatrix.BT601 -> MAC_METAL_PRIMARIES_BT601_625
                    else -> 1
                }
        }

private val VideoColorMatrix.macMetalCode: Int
    get() =
        when (this) {
            VideoColorMatrix.BT2020_NCL, VideoColorMatrix.BT2020_CL -> 1
            VideoColorMatrix.BT601 -> 2
            else -> 0
        }

private val VideoDynamicRange.isMacMetalHdrOutput: Boolean
    get() = this == VideoDynamicRange.HDR10 || this == VideoDynamicRange.HLG

private val VideoColorInfo.macMetalPeakNits: Float
    get() =
        listOfNotNull(
            masteringDisplay?.maxLuminanceNits,
            contentLightLevel?.maxContentLightLevelNits?.toFloat(),
        ).maxOrNull()?.coerceIn(MINIMUM_HDR_PEAK_NITS, MAXIMUM_HDR_PEAK_NITS)
            ?: DEFAULT_HDR_PEAK_NITS

private const val DEFAULT_HDR_PEAK_NITS = 1_000f
private const val MAC_METAL_TRANSFER_SRGB = 3
private const val MAC_METAL_TRANSFER_LINEAR = 4
private const val MAC_METAL_PRIMARIES_BT601_525 = 3
private const val MAC_METAL_PRIMARIES_BT601_625 = 4
private const val MINIMUM_HDR_PEAK_NITS = 100f
private const val MAXIMUM_HDR_PEAK_NITS = 10_000f
private const val MAXIMUM_NV12_BIT_DEPTH = 8
private const val SDR_REFERENCE_WHITE_NITS = 100f
