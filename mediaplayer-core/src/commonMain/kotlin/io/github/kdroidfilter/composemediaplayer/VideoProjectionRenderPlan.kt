@file:Suppress("MagicNumber")

package io.github.kdroidfilter.composemediaplayer

import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.tan

data class VideoProjectionRenderPlan(
    val mesh: VideoProjectionMesh,
    val leftEyeTexture: VideoTextureWindow,
    val rightEyeTexture: VideoTextureWindow,
) {
    val stereo: Boolean
        get() = leftEyeTexture != rightEyeTexture
}

data class VideoProjectionMesh(
    val type: VideoProjectionMeshType,
    val horizontalFovDegrees: Float,
    val verticalFovDegrees: Float,
    val columns: Int,
    val rows: Int,
)

enum class VideoProjectionMeshType {
    FlatPlane,
    CurvedPlane,
    EquirectSphere,
    FisheyeDome,
    EacCube,
}

data class VideoTextureWindow(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val rotation: VideoProjectionRotation,
)

data class VideoProjectionRenderOptions(
    val curvature: Float = 0f,
    val textureCrop: VideoTextureCrop = VideoTextureCrop(),
) {
    fun normalized(): VideoProjectionRenderOptions =
        copy(
            curvature = curvature.coerceIn(0f, 1f),
            textureCrop = textureCrop.normalized(),
        )
}

data class VideoTextureCrop(
    val left: Float = 0f,
    val top: Float = 0f,
    val right: Float = 0f,
    val bottom: Float = 0f,
) {
    fun normalized(): VideoTextureCrop =
        copy(
            left = left.normalizedTextureCropEdge(),
            top = top.normalizedTextureCropEdge(),
            right = right.normalizedTextureCropEdge(),
            bottom = bottom.normalizedTextureCropEdge(),
        )
}

private fun Float.normalizedTextureCropEdge(): Float = takeIf(Float::isFinite)?.coerceIn(0f, MAXIMUM_TEXTURE_CROP) ?: 0f

@ExperimentalComposeMediaPlayerBackendApi
val VideoTextureCrop.isDefaultTextureCrop: Boolean
    get() =
        normalized().let { crop ->
            crop.left == 0f &&
                crop.top == 0f &&
                crop.right == 0f &&
                crop.bottom == 0f
        }

fun VideoProjectionSettings.toVideoProjectionRenderPlan(
    options: VideoProjectionRenderOptions = VideoProjectionRenderOptions(),
): VideoProjectionRenderPlan {
    val normalizedProjection = normalized()
    val normalizedOptions = options.normalized()
    val mesh =
        VideoProjectionMesh(
            type = normalizedProjection.meshType(normalizedOptions.curvature),
            horizontalFovDegrees = normalizedProjection.fovDegrees,
            verticalFovDegrees = normalizedProjection.verticalFovDegrees(),
            columns = normalizedProjection.recommendedColumns(normalizedOptions.curvature),
            rows = normalizedProjection.recommendedRows(normalizedOptions.curvature),
        )
    val eyeWindows = normalizedProjection.eyeTextureWindows(normalizedOptions.textureCrop)
    val displayWindows =
        when (normalizedProjection.displayMode) {
            VideoProjectionDisplayMode.Stereo -> eyeWindows
            VideoProjectionDisplayMode.MonoscopicLeft -> eyeWindows.first to eyeWindows.first
            VideoProjectionDisplayMode.MonoscopicRight -> eyeWindows.second to eyeWindows.second
        }
    return VideoProjectionRenderPlan(
        mesh = mesh,
        leftEyeTexture = displayWindows.first,
        rightEyeTexture = displayWindows.second,
    )
}

private fun VideoProjectionSettings.meshType(curvature: Float): VideoProjectionMeshType =
    when (projectionType) {
        VideoProjectionType.Flat ->
            if (curvature > 0f) {
                VideoProjectionMeshType.CurvedPlane
            } else {
                VideoProjectionMeshType.FlatPlane
            }

        VideoProjectionType.Equirect180,
        VideoProjectionType.Equirect360,
        -> VideoProjectionMeshType.EquirectSphere

        VideoProjectionType.Fisheye180,
        VideoProjectionType.Fisheye190,
        VideoProjectionType.Fisheye200,
        VideoProjectionType.Fisheye220,
        -> VideoProjectionMeshType.FisheyeDome

        VideoProjectionType.Eac360 -> VideoProjectionMeshType.EacCube
    }

private fun VideoProjectionSettings.verticalFovDegrees(): Float =
    when (projectionType) {
        VideoProjectionType.Flat -> horizontalToVerticalFovDegrees(fovDegrees, aspectRatio)

        VideoProjectionType.Equirect180,
        VideoProjectionType.Equirect360,
        VideoProjectionType.Eac360,
        -> 180f

        VideoProjectionType.Fisheye180,
        VideoProjectionType.Fisheye190,
        VideoProjectionType.Fisheye200,
        VideoProjectionType.Fisheye220,
        -> fovDegrees
    }

private fun VideoProjectionSettings.recommendedColumns(curvature: Float): Int =
    when (projectionType) {
        VideoProjectionType.Flat -> if (curvature > 0f) CURVED_PLANE_COLUMNS else FLAT_PLANE_COLUMNS

        VideoProjectionType.Equirect180,
        VideoProjectionType.Equirect360,
        VideoProjectionType.Fisheye180,
        VideoProjectionType.Fisheye190,
        VideoProjectionType.Fisheye200,
        VideoProjectionType.Fisheye220,
        VideoProjectionType.Eac360,
        -> DENSE_PROJECTION_COLUMNS
    }

private fun VideoProjectionSettings.recommendedRows(curvature: Float): Int =
    when (projectionType) {
        VideoProjectionType.Flat -> if (curvature > 0f) CURVED_PLANE_ROWS else FLAT_PLANE_ROWS

        VideoProjectionType.Equirect180,
        VideoProjectionType.Equirect360,
        VideoProjectionType.Fisheye180,
        VideoProjectionType.Fisheye190,
        VideoProjectionType.Fisheye200,
        VideoProjectionType.Fisheye220,
        VideoProjectionType.Eac360,
        -> DENSE_PROJECTION_ROWS
    }

private fun VideoProjectionSettings.eyeTextureWindows(
    textureCrop: VideoTextureCrop,
): Pair<VideoTextureWindow, VideoTextureWindow> {
    val baseLeft = VideoTextureWindow(0f, 0f, 1f, 1f, rotation)
    val baseRight = baseLeft
    val stereoWindows =
        when (stereoLayout) {
            VideoStereoLayout.Mono -> baseLeft to baseRight

            VideoStereoLayout.SideBySide -> {
                val first = baseLeft.copy(right = 0.5f)
                val second = baseLeft.copy(left = 0.5f)
                orderedEyeWindows(first, second)
            }

            VideoStereoLayout.OverUnder -> {
                val first = baseLeft.copy(bottom = 0.5f)
                val second = baseLeft.copy(top = 0.5f)
                orderedEyeWindows(first, second)
            }
        }
    return stereoWindows.first.cropped(textureCrop) to stereoWindows.second.cropped(textureCrop)
}

private fun VideoProjectionSettings.orderedEyeWindows(
    first: VideoTextureWindow,
    second: VideoTextureWindow,
): Pair<VideoTextureWindow, VideoTextureWindow> =
    when (eyeOrder) {
        VideoEyeOrder.LeftRight -> first to second
        VideoEyeOrder.RightLeft -> second to first
    }

private fun VideoTextureWindow.cropped(textureCrop: VideoTextureCrop): VideoTextureWindow {
    val width = right - left
    val height = bottom - top
    return copy(
        left = left + width * textureCrop.left,
        top = top + height * textureCrop.top,
        right = right - width * textureCrop.right,
        bottom = bottom - height * textureCrop.bottom,
    )
}

private fun horizontalToVerticalFovDegrees(
    horizontalFovDegrees: Float,
    aspectRatio: Float,
): Float {
    val horizontalRadians = horizontalFovDegrees.toDouble() * PI / DEGREES_PER_HALF_CIRCLE
    val verticalRadians = 2.0 * atan(tan(horizontalRadians / 2.0) / aspectRatio.toDouble())
    return (verticalRadians * DEGREES_PER_HALF_CIRCLE / PI).toFloat()
}

private const val MAXIMUM_TEXTURE_CROP = 0.49f
private const val DEGREES_PER_HALF_CIRCLE = 180.0
private const val FLAT_PLANE_COLUMNS = 1
private const val FLAT_PLANE_ROWS = 1
private const val CURVED_PLANE_COLUMNS = 32
private const val CURVED_PLANE_ROWS = 8
private const val DENSE_PROJECTION_COLUMNS = 96
private const val DENSE_PROJECTION_ROWS = 48
