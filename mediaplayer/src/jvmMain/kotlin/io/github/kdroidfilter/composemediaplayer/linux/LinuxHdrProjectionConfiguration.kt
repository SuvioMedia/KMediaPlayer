package io.github.kdroidfilter.composemediaplayer.linux

import io.github.kdroidfilter.composemediaplayer.DisplayColorCapabilities
import io.github.kdroidfilter.composemediaplayer.DolbyVisionPolicy
import io.github.kdroidfilter.composemediaplayer.DynamicMetadataHandling
import io.github.kdroidfilter.composemediaplayer.VideoColorInfo
import io.github.kdroidfilter.composemediaplayer.VideoColorMatrix
import io.github.kdroidfilter.composemediaplayer.VideoColorPrimaries
import io.github.kdroidfilter.composemediaplayer.VideoColorRange
import io.github.kdroidfilter.composemediaplayer.VideoDynamicRange
import io.github.kdroidfilter.composemediaplayer.VideoEyeOrder
import io.github.kdroidfilter.composemediaplayer.VideoProjectionSettings
import io.github.kdroidfilter.composemediaplayer.VideoProjectionViewSettings
import io.github.kdroidfilter.composemediaplayer.VideoStereoLayout
import io.github.kdroidfilter.composemediaplayer.VideoTextureCrop
import io.github.kdroidfilter.composemediaplayer.projectionShaderCode

internal data class LinuxHdrProjectionConfiguration(
    val integers: IntArray,
    val floats: FloatArray,
)

@Suppress("CyclomaticComplexMethod")
internal fun buildLinuxHdrProjectionNativeConfiguration(
    source: VideoColorInfo,
    display: DisplayColorCapabilities,
    dolbyVisionPolicy: DolbyVisionPolicy,
    projection: VideoProjectionSettings,
    projectionView: VideoProjectionViewSettings,
    textureCrop: VideoTextureCrop,
    metadataHandling: DynamicMetadataHandling = DynamicMetadataHandling.NONE,
): LinuxHdrProjectionConfiguration? {
    val transfer =
        when (source.dynamicRange) {
            VideoDynamicRange.HDR10,
            VideoDynamicRange.HDR10_PLUS,
            -> LINUX_HDR_TRANSFER_PQ

            VideoDynamicRange.HLG -> LINUX_HDR_TRANSFER_HLG
            VideoDynamicRange.DOLBY_VISION ->
                LINUX_HDR_TRANSFER_PQ.takeIf {
                    dolbyVisionPolicy == DolbyVisionPolicy.PREFER_HDR10_BASE_LAYER &&
                        source.dolbyVision?.hasHdr10CompatibleBaseLayer == true
                }

            VideoDynamicRange.UNKNOWN,
            VideoDynamicRange.SDR,
            -> null
        } ?: return null
    val matrix =
        when (source.matrix) {
            VideoColorMatrix.UNKNOWN,
            VideoColorMatrix.BT2020_NCL,
            -> LINUX_HDR_MATRIX_BT2020

            VideoColorMatrix.BT709 -> LINUX_HDR_MATRIX_BT709
            VideoColorMatrix.BT601 -> LINUX_HDR_MATRIX_BT601
            VideoColorMatrix.RGB,
            VideoColorMatrix.BT2020_CL,
            VideoColorMatrix.ICTCP,
            -> return null
        }
    val primaries =
        when (source.primaries) {
            VideoColorPrimaries.UNKNOWN,
            VideoColorPrimaries.BT2020,
            -> LINUX_HDR_PRIMARIES_BT2020

            VideoColorPrimaries.BT709 -> LINUX_HDR_PRIMARIES_BT709
            VideoColorPrimaries.DISPLAY_P3 -> LINUX_HDR_PRIMARIES_DISPLAY_P3
            VideoColorPrimaries.BT601_525,
            VideoColorPrimaries.BT601_625,
            -> return null
        }
    val normalizedProjection = projection.normalized()
    val normalizedView = projectionView.normalized()
    val crop = textureCrop.normalized()
    val mastering = source.masteringDisplay
    val contentLight = source.contentLightLevel
    val sourcePeak =
        contentLight?.maxContentLightLevelNits?.toFloat()
            ?: mastering?.maxLuminanceNits
            ?: DEFAULT_HDR_SOURCE_PEAK_NITS
    val targetPeak = display.maxLuminanceNits ?: sourcePeak
    val referenceWhite = display.referenceWhiteNits ?: DEFAULT_HDR_REFERENCE_WHITE_NITS
    return LinuxHdrProjectionConfiguration(
        integers =
            intArrayOf(
                transfer,
                normalizedProjection.projectionType.projectionShaderCode,
                normalizedProjection.stereoLayout.nativeCode,
                normalizedProjection.eyeOrder.nativeCode,
                normalizedProjection.rotation.ordinal,
                if (source.range == VideoColorRange.FULL) LINUX_HDR_RANGE_FULL else LINUX_HDR_RANGE_LIMITED,
                matrix,
                primaries,
                if (
                    source.dynamicRange == VideoDynamicRange.HDR10_PLUS &&
                    metadataHandling == DynamicMetadataHandling.APPLIED_BY_RENDERER
                ) {
                    1
                } else {
                    0
                },
            ),
        floats =
            floatArrayOf(
                normalizedProjection.fovDegrees,
                normalizedView.yawDegrees,
                normalizedView.pitchDegrees,
                normalizedView.rollDegrees,
                normalizedView.zoom,
                crop.left,
                crop.top,
                crop.right,
                crop.bottom,
                sourcePeak,
                mastering?.redX ?: 0f,
                mastering?.redY ?: 0f,
                mastering?.greenX ?: 0f,
                mastering?.greenY ?: 0f,
                mastering?.blueX ?: 0f,
                mastering?.blueY ?: 0f,
                mastering?.whiteX ?: 0f,
                mastering?.whiteY ?: 0f,
                mastering?.minLuminanceNits ?: 0f,
                mastering?.maxLuminanceNits ?: 0f,
                contentLight?.maxContentLightLevelNits?.toFloat() ?: 0f,
                contentLight?.maxFrameAverageLightLevelNits?.toFloat() ?: 0f,
                targetPeak,
                referenceWhite,
            ),
    )
}

private val VideoStereoLayout.nativeCode: Int
    get() =
        when (this) {
            VideoStereoLayout.Mono -> 0
            VideoStereoLayout.SideBySide -> 1
            VideoStereoLayout.OverUnder -> 2
        }

private val VideoEyeOrder.nativeCode: Int
    get() =
        when (this) {
            VideoEyeOrder.LeftRight -> 0
            VideoEyeOrder.RightLeft -> 1
        }

private const val LINUX_HDR_TRANSFER_PQ = 0
private const val LINUX_HDR_TRANSFER_HLG = 1
private const val LINUX_HDR_RANGE_LIMITED = 0
private const val LINUX_HDR_RANGE_FULL = 1
private const val LINUX_HDR_MATRIX_BT2020 = 0
private const val LINUX_HDR_MATRIX_BT709 = 1
private const val LINUX_HDR_MATRIX_BT601 = 2
private const val LINUX_HDR_PRIMARIES_BT2020 = 0
private const val LINUX_HDR_PRIMARIES_BT709 = 1
private const val LINUX_HDR_PRIMARIES_DISPLAY_P3 = 2
private const val DEFAULT_HDR_SOURCE_PEAK_NITS = 1_000f
private const val DEFAULT_HDR_REFERENCE_WHITE_NITS = 203f
