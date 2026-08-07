@file:OptIn(io.github.kdroidfilter.composemediaplayer.ExperimentalComposeMediaPlayerBackendApi::class)

package io.github.kdroidfilter.composemediaplayer.mpv

import io.github.kdroidfilter.composemediaplayer.VideoProjectionRenderOptions
import io.github.kdroidfilter.composemediaplayer.VideoProjectionSettings
import io.github.kdroidfilter.composemediaplayer.VideoProjectionViewSettings
import io.github.kdroidfilter.composemediaplayer.VideoTextureCrop
import io.github.kdroidfilter.composemediaplayer.isDefaultTextureCrop
import io.github.kdroidfilter.composemediaplayer.projectionShaderCode
import io.github.kdroidfilter.composemediaplayer.requiresProjectionRenderer
import io.github.kdroidfilter.composemediaplayer.toVideoProjectionRenderPlan

/** Parameters consumed by the zero-copy OpenGL projection pass after libmpv renders a frame. */
internal data class MpvMacProjectionConfiguration(
    val enabled: Boolean,
    val projectionType: Int,
    val fovDegrees: Float,
    val stereo: Boolean,
    val leftWindow: FloatArray,
    val leftRotation: Int,
    val rightWindow: FloatArray,
    val rightRotation: Int,
    val yawDegrees: Float,
    val pitchDegrees: Float,
    val rollDegrees: Float,
    val zoom: Float,
) {
    fun toNativeArray(): FloatArray =
        floatArrayOf(
            if (enabled) 1f else 0f,
            projectionType.toFloat(),
            fovDegrees,
            if (stereo) 1f else 0f,
            leftWindow[0],
            leftWindow[1],
            leftWindow[2],
            leftWindow[3],
            leftRotation.toFloat(),
            rightWindow[0],
            rightWindow[1],
            rightWindow[2],
            rightWindow[3],
            rightRotation.toFloat(),
            yawDegrees,
            pitchDegrees,
            rollDegrees,
            zoom,
        )
}

internal fun mpvMacProjectionConfiguration(
    projection: VideoProjectionSettings,
    projectionView: VideoProjectionViewSettings,
    textureCrop: VideoTextureCrop,
): MpvMacProjectionConfiguration {
    val normalizedProjection = projection.normalized()
    val normalizedView = projectionView.normalized()
    val normalizedCrop = textureCrop.normalized()
    val plan =
        normalizedProjection.toVideoProjectionRenderPlan(
            VideoProjectionRenderOptions(textureCrop = normalizedCrop),
        )
    return MpvMacProjectionConfiguration(
        enabled = normalizedProjection.requiresProjectionRenderer || !normalizedCrop.isDefaultTextureCrop,
        projectionType = normalizedProjection.projectionType.projectionShaderCode,
        fovDegrees = normalizedProjection.fovDegrees,
        stereo = plan.stereo,
        leftWindow =
            floatArrayOf(
                plan.leftEyeTexture.left,
                plan.leftEyeTexture.top,
                plan.leftEyeTexture.right,
                plan.leftEyeTexture.bottom,
            ),
        leftRotation = plan.leftEyeTexture.rotation.ordinal,
        rightWindow =
            floatArrayOf(
                plan.rightEyeTexture.left,
                plan.rightEyeTexture.top,
                plan.rightEyeTexture.right,
                plan.rightEyeTexture.bottom,
            ),
        rightRotation = plan.rightEyeTexture.rotation.ordinal,
        yawDegrees = normalizedView.yawDegrees,
        pitchDegrees = normalizedView.pitchDegrees,
        rollDegrees = normalizedView.rollDegrees,
        zoom = normalizedView.zoom,
    )
}

internal const val MPV_MAC_PROJECTION_PARAMETER_COUNT = 18

/**
 * libmpv renders into the projection pass's intermediate texture before our shader samples it.
 * That texture must represent normalized source coordinates: allowing libmpv to letterbox or
 * panscan it makes the fisheye crop move whenever the destination aspect ratio changes.
 */
internal data class MpvMacInputGeometry(
    val keepAspect: String,
    val panscan: String,
)

internal fun mpvMacInputGeometry(
    projectionEnabled: Boolean,
    crop: Boolean,
): MpvMacInputGeometry =
    if (projectionEnabled) {
        MpvMacInputGeometry(keepAspect = "no", panscan = "0.0")
    } else {
        MpvMacInputGeometry(
            keepAspect = "yes",
            panscan = if (crop) "1.0" else "0.0",
        )
    }
