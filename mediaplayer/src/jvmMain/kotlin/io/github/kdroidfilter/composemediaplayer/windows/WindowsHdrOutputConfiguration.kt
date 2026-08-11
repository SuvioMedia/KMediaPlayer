package io.github.kdroidfilter.composemediaplayer.windows

import io.github.kdroidfilter.composemediaplayer.DisplayColorCapabilities
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

internal data class WindowsHdrNativeConfiguration(
    val integers: IntArray,
    val floats: FloatArray,
)

internal data class WindowsNativeHdrOutputStatus(
    val displayQueried: Boolean,
    val advancedColorEnabled: Boolean,
    val swapChainConfigured: Boolean,
    val firstFramePresented: Boolean,
    val p010InputConfirmed: Boolean,
    val bitsPerColor: Int,
    val displayColorSpace: Int,
    val swapChainColorSpace: Int,
    val monitorGeneration: Int,
    val lastError: Int,
    val minLuminanceNits: Float,
    val maxLuminanceNits: Float,
    val maxFullFrameLuminanceNits: Float,
) {
    val isConfirmedHdrOutput: Boolean
        get() =
            displayQueried &&
                advancedColorEnabled &&
                swapChainConfigured &&
                firstFramePresented &&
                p010InputConfirmed &&
                (
                    swapChainColorSpace == WINDOWS_HDR10_SWAP_CHAIN_COLOR_SPACE ||
                        swapChainColorSpace == WINDOWS_SCRGB_SWAP_CHAIN_COLOR_SPACE
                ) &&
                lastError >= 0

    val isConfirmedSdrOutput: Boolean
        get() =
            displayQueried &&
                swapChainConfigured &&
                firstFramePresented &&
                p010InputConfirmed &&
                swapChainColorSpace == WINDOWS_SDR_SWAP_CHAIN_COLOR_SPACE &&
                lastError >= 0

    fun displayCapabilities(): DisplayColorCapabilities =
        maxLuminanceNits.positiveOrNull().let { maximum ->
            DisplayColorCapabilities(
                isKnown = displayQueried,
                supportedDynamicRanges =
                    if (advancedColorEnabled) {
                        setOf(VideoDynamicRange.HDR10, VideoDynamicRange.HLG)
                    } else {
                        emptySet()
                    },
                minLuminanceNits =
                    minLuminanceNits
                        .positiveOrNull(allowZero = true)
                        ?.takeIf { minimum -> maximum == null || minimum <= maximum },
                maxLuminanceNits = maximum,
                referenceWhiteNits = WINDOWS_SCRGB_REFERENCE_WHITE_NITS,
            )
        }
}

@Suppress("CyclomaticComplexMethod")
internal fun buildWindowsHdrNativeConfiguration(
    source: VideoColorInfo,
    dolbyVisionBaseLayerOutput: VideoDynamicRange? = null,
    projection: VideoProjectionSettings,
    projectionView: VideoProjectionViewSettings,
    textureCrop: VideoTextureCrop,
    metadataHandling: DynamicMetadataHandling = DynamicMetadataHandling.NONE,
    forceSdrOutput: Boolean = false,
): WindowsHdrNativeConfiguration? {
    val transfer =
        when (source.dynamicRange) {
            VideoDynamicRange.HDR10,
            VideoDynamicRange.HDR10_PLUS,
            -> WINDOWS_HDR_TRANSFER_PQ

            VideoDynamicRange.HLG -> WINDOWS_HDR_TRANSFER_HLG
            VideoDynamicRange.DOLBY_VISION ->
                dolbyVisionBaseLayerOutput
                    .takeIf { it == source.dolbyVision?.compatibleBaseLayerDynamicRange }
                    ?.windowsHdrTransfer()

            VideoDynamicRange.SDR -> WINDOWS_TRANSFER_SDR
            VideoDynamicRange.UNKNOWN -> null
        } ?: return null
    val isSdrSource = source.dynamicRange == VideoDynamicRange.SDR
    val normalizedProjection = projection.normalized()
    val normalizedView = projectionView.normalized()
    val crop = textureCrop.normalized()
    val mastering = source.masteringDisplay
    val contentLight = source.contentLightLevel
    val range =
        when (source.range) {
            VideoColorRange.UNKNOWN,
            VideoColorRange.LIMITED,
            -> WINDOWS_HDR_RANGE_LIMITED

            VideoColorRange.FULL -> WINDOWS_HDR_RANGE_FULL
        }
    val matrix =
        when (source.matrix) {
            VideoColorMatrix.UNKNOWN ->
                if (isSdrSource) WINDOWS_HDR_MATRIX_BT709 else WINDOWS_HDR_MATRIX_BT2020

            VideoColorMatrix.BT2020_NCL -> WINDOWS_HDR_MATRIX_BT2020

            VideoColorMatrix.BT709 -> WINDOWS_HDR_MATRIX_BT709
            VideoColorMatrix.BT601 -> WINDOWS_HDR_MATRIX_BT601
            VideoColorMatrix.RGB,
            VideoColorMatrix.BT2020_CL,
            VideoColorMatrix.ICTCP,
            -> return null
        }
    val primaries =
        when (source.primaries) {
            VideoColorPrimaries.UNKNOWN ->
                if (isSdrSource) WINDOWS_HDR_PRIMARIES_BT709 else WINDOWS_HDR_PRIMARIES_BT2020

            VideoColorPrimaries.BT2020 -> WINDOWS_HDR_PRIMARIES_BT2020

            VideoColorPrimaries.BT709 -> WINDOWS_HDR_PRIMARIES_BT709
            VideoColorPrimaries.DISPLAY_P3 -> WINDOWS_HDR_PRIMARIES_DISPLAY_P3
            VideoColorPrimaries.BT601_525,
            VideoColorPrimaries.BT601_625,
            -> WINDOWS_HDR_PRIMARIES_BT709
        }
    val sourcePeak =
        contentLight?.maxContentLightLevelNits?.toFloat()
            ?: mastering?.maxLuminanceNits
            ?: if (isSdrSource) DEFAULT_SDR_SOURCE_PEAK_NITS else DEFAULT_HDR_SOURCE_PEAK_NITS
    return WindowsHdrNativeConfiguration(
        integers =
            intArrayOf(
                transfer,
                normalizedProjection.projectionType.projectionShaderCode,
                normalizedProjection.stereoLayout.nativeCode,
                normalizedProjection.eyeOrder.nativeCode,
                normalizedProjection.rotation.ordinal,
                range,
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
                if (forceSdrOutput || isSdrSource) 1 else 0,
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
            ),
    )
}

private fun VideoDynamicRange.windowsHdrTransfer(): Int? =
    when (this) {
        VideoDynamicRange.HDR10,
        VideoDynamicRange.HDR10_PLUS,
        -> WINDOWS_HDR_TRANSFER_PQ
        VideoDynamicRange.HLG -> WINDOWS_HDR_TRANSFER_HLG
        else -> null
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

private fun Float.positiveOrNull(allowZero: Boolean = false): Float? =
    takeIf { value -> value.isFinite() && if (allowZero) value >= 0f else value > 0f }

private const val WINDOWS_HDR_TRANSFER_PQ = 0
private const val WINDOWS_HDR_TRANSFER_HLG = 1
private const val WINDOWS_TRANSFER_SDR = 2
private const val WINDOWS_HDR_RANGE_LIMITED = 0
private const val WINDOWS_HDR_RANGE_FULL = 1
private const val WINDOWS_HDR_MATRIX_BT2020 = 0
private const val WINDOWS_HDR_MATRIX_BT709 = 1
private const val WINDOWS_HDR_MATRIX_BT601 = 2
private const val WINDOWS_HDR_PRIMARIES_BT2020 = 0
private const val WINDOWS_HDR_PRIMARIES_BT709 = 1
private const val WINDOWS_HDR_PRIMARIES_DISPLAY_P3 = 2
private const val WINDOWS_HDR10_SWAP_CHAIN_COLOR_SPACE = 12
private const val WINDOWS_SCRGB_SWAP_CHAIN_COLOR_SPACE = 1
private const val WINDOWS_SDR_SWAP_CHAIN_COLOR_SPACE = 0
private const val DEFAULT_HDR_SOURCE_PEAK_NITS = 1_000f
private const val DEFAULT_SDR_SOURCE_PEAK_NITS = 100f
private const val WINDOWS_SCRGB_REFERENCE_WHITE_NITS = 80f
