package io.github.kdroidfilter.composemediaplayer

import androidx.compose.runtime.Stable

/**
 * Describes how a source video should be projected before it reaches the final display surface.
 */
@Stable
data class VideoProjectionSettings(
    val projectionType: VideoProjectionType = VideoProjectionType.Flat,
    val stereoLayout: VideoStereoLayout = VideoStereoLayout.Mono,
    val eyeOrder: VideoEyeOrder = VideoEyeOrder.LeftRight,
    val fovDegrees: Float = VideoProjectionType.Flat.defaultFovDegrees(),
    val aspectRatio: Float = DEFAULT_FLAT_PROJECTION_ASPECT_RATIO,
    val rotation: VideoProjectionRotation = VideoProjectionRotation.None,
) {
    fun normalized(): VideoProjectionSettings {
        val requestedFovDegrees =
            if (fovDegrees.isFinite()) {
                fovDegrees
            } else {
                projectionType.defaultFovDegrees()
            }
        val normalizedFovDegrees =
            if (projectionType != VideoProjectionType.Flat &&
                requestedFovDegrees == DEFAULT_FLAT_PROJECTION_FOV_DEGREES
            ) {
                projectionType.defaultFovDegrees()
            } else {
                requestedFovDegrees
            }

        return copy(
            fovDegrees =
                normalizedFovDegrees.coerceIn(
                    MINIMUM_PROJECTION_FOV_DEGREES,
                    projectionType.maximumFovDegrees(),
                ),
            aspectRatio =
                aspectRatio
                    .takeIf(Float::isFinite)
                    ?.coerceIn(MINIMUM_PROJECTION_ASPECT_RATIO, MAXIMUM_PROJECTION_ASPECT_RATIO)
                    ?: DEFAULT_FLAT_PROJECTION_ASPECT_RATIO,
        )
    }
}

@Stable
data class VideoProjectionViewSettings(
    val yawDegrees: Float = 0f,
    val pitchDegrees: Float = 0f,
    val rollDegrees: Float = 0f,
    val zoom: Float = 1f,
) {
    fun normalized(): VideoProjectionViewSettings =
        copy(
            yawDegrees = yawDegrees.takeIf(Float::isFinite)?.normalizedDegrees() ?: 0f,
            pitchDegrees =
                pitchDegrees
                    .takeIf(Float::isFinite)
                    ?.coerceIn(
                        MINIMUM_PROJECTION_VIEW_PITCH_DEGREES,
                        MAXIMUM_PROJECTION_VIEW_PITCH_DEGREES,
                    ) ?: 0f,
            rollDegrees = rollDegrees.takeIf(Float::isFinite)?.normalizedDegrees() ?: 0f,
            zoom =
                zoom.takeIf(Float::isFinite)?.coerceIn(MINIMUM_PROJECTION_VIEW_ZOOM, MAXIMUM_PROJECTION_VIEW_ZOOM)
                    ?: 1f,
        )
}

/**
 * Controls how the projection viewport is updated for VR-style videos.
 */
enum class VideoProjectionViewControlMode {
    /**
     * Use device motion on platforms where it is available for surround projections.
     */
    AUTO,

    /**
     * Keep the viewport under app control through [VideoPlayerState.projectionView].
     */
    MANUAL,

    /**
     * Prefer device motion whenever the selected projection uses a projection renderer.
     */
    DEVICE_MOTION,
}

enum class VideoProjectionType {
    Flat,
    Equirect180,
    Equirect360,
    Fisheye180,
    Fisheye190,
    Fisheye200,
    Fisheye220,
    Eac360,
}

enum class VideoStereoLayout {
    Mono,
    SideBySide,
    OverUnder,
}

enum class VideoEyeOrder {
    LeftRight,
    RightLeft,
}

enum class VideoProjectionRotation {
    None,
    Rotate90,
    Rotate180,
    Rotate270,
}

fun VideoProjectionType.defaultFovDegrees(): Float =
    when (this) {
        VideoProjectionType.Flat -> DEFAULT_FLAT_PROJECTION_FOV_DEGREES
        VideoProjectionType.Equirect180 -> HALF_DOME_PROJECTION_FOV_DEGREES
        VideoProjectionType.Equirect360 -> FULL_DOME_PROJECTION_FOV_DEGREES
        VideoProjectionType.Fisheye180 -> HALF_DOME_PROJECTION_FOV_DEGREES
        VideoProjectionType.Fisheye190 -> FISHEYE_190_PROJECTION_FOV_DEGREES
        VideoProjectionType.Fisheye200 -> FISHEYE_200_PROJECTION_FOV_DEGREES
        VideoProjectionType.Fisheye220 -> FISHEYE_220_PROJECTION_FOV_DEGREES
        VideoProjectionType.Eac360 -> FULL_DOME_PROJECTION_FOV_DEGREES
    }

private fun VideoProjectionType.maximumFovDegrees(): Float =
    when (this) {
        VideoProjectionType.Flat -> MAXIMUM_FLAT_PROJECTION_FOV_DEGREES
        VideoProjectionType.Equirect180,
        VideoProjectionType.Equirect360,
        VideoProjectionType.Fisheye180,
        VideoProjectionType.Fisheye190,
        VideoProjectionType.Fisheye200,
        VideoProjectionType.Fisheye220,
        VideoProjectionType.Eac360,
        -> MAXIMUM_SURROUND_PROJECTION_FOV_DEGREES
    }

val VideoProjectionType.isSurroundProjection: Boolean
    get() =
        when (this) {
            VideoProjectionType.Equirect180,
            VideoProjectionType.Equirect360,
            VideoProjectionType.Fisheye180,
            VideoProjectionType.Fisheye190,
            VideoProjectionType.Fisheye200,
            VideoProjectionType.Fisheye220,
            VideoProjectionType.Eac360,
            -> true

            VideoProjectionType.Flat -> false
        }

val VideoProjectionSettings.isProjected: Boolean
    get() = normalized().projectionType != VideoProjectionType.Flat

val VideoProjectionSettings.requiresProjectionRenderer: Boolean
    get() =
        normalized().let { projection ->
            projection.projectionType != VideoProjectionType.Flat ||
                projection.stereoLayout != VideoStereoLayout.Mono ||
                projection.rotation != VideoProjectionRotation.None
        }

@ExperimentalComposeMediaPlayerBackendApi
val VideoProjectionSettings.isDefaultProjectionSettings: Boolean
    get() =
        normalized().let { projection ->
            projection.projectionType == VideoProjectionType.Flat &&
                projection.stereoLayout == VideoStereoLayout.Mono &&
                projection.eyeOrder == VideoEyeOrder.LeftRight &&
                projection.fovDegrees == VideoProjectionType.Flat.defaultFovDegrees() &&
                projection.aspectRatio == DEFAULT_FLAT_PROJECTION_ASPECT_RATIO &&
                projection.rotation == VideoProjectionRotation.None
        }

@ExperimentalComposeMediaPlayerBackendApi
fun VideoProjectionViewControlMode.usesDeviceMotionFor(projection: VideoProjectionSettings): Boolean =
    when (this) {
        VideoProjectionViewControlMode.AUTO -> projection.normalized().projectionType.isSurroundProjection
        VideoProjectionViewControlMode.MANUAL -> false
        VideoProjectionViewControlMode.DEVICE_MOTION -> projection.requiresProjectionRenderer
    }

@ExperimentalComposeMediaPlayerBackendApi
fun VideoProjectionSettings.renderingInfoLabel(): String? {
    val normalized = normalized()
    if (normalized.projectionType == VideoProjectionType.Flat &&
        normalized.stereoLayout == VideoStereoLayout.Mono
    ) {
        return null
    }
    val projectionLabel =
        when (normalized.projectionType) {
            VideoProjectionType.Flat -> "Flat"
            VideoProjectionType.Equirect180 -> "Equirect 180"
            VideoProjectionType.Equirect360 -> "Equirect 360"
            VideoProjectionType.Fisheye180 -> "Fisheye 180"
            VideoProjectionType.Fisheye190 -> "Fisheye 190"
            VideoProjectionType.Fisheye200 -> "Fisheye 200"
            VideoProjectionType.Fisheye220 -> "Fisheye 220"
            VideoProjectionType.Eac360 -> "EAC 360"
        }
    val stereoLabel =
        when (normalized.stereoLayout) {
            VideoStereoLayout.Mono -> "mono"
            VideoStereoLayout.SideBySide -> "SBS ${normalized.eyeOrder.label}"
            VideoStereoLayout.OverUnder -> "OU ${normalized.eyeOrder.label}"
        }
    return "$projectionLabel $stereoLabel"
}

private val VideoEyeOrder.label: String
    get() =
        when (this) {
            VideoEyeOrder.LeftRight -> "LR"
            VideoEyeOrder.RightLeft -> "RL"
        }

private fun Float.normalizedDegrees(): Float {
    var normalized = this % FULL_ROTATION_DEGREES
    if (normalized > HALF_ROTATION_DEGREES) normalized -= FULL_ROTATION_DEGREES
    if (normalized < -HALF_ROTATION_DEGREES) normalized += FULL_ROTATION_DEGREES
    return normalized
}

@ExperimentalComposeMediaPlayerBackendApi
val VideoProjectionType.projectionShaderCode: Int
    get() =
        when (this) {
            VideoProjectionType.Flat -> PROJECTION_SHADER_CODE_FLAT
            VideoProjectionType.Equirect180 -> PROJECTION_SHADER_CODE_EQUIRECT_180
            VideoProjectionType.Equirect360 -> PROJECTION_SHADER_CODE_EQUIRECT_360
            VideoProjectionType.Fisheye180 -> PROJECTION_SHADER_CODE_FISHEYE_180
            VideoProjectionType.Fisheye190 -> PROJECTION_SHADER_CODE_FISHEYE_190
            VideoProjectionType.Fisheye200 -> PROJECTION_SHADER_CODE_FISHEYE_200
            VideoProjectionType.Fisheye220 -> PROJECTION_SHADER_CODE_FISHEYE_220
            VideoProjectionType.Eac360 -> PROJECTION_SHADER_CODE_EAC_360
        }

private const val DEFAULT_FLAT_PROJECTION_FOV_DEGREES = 60f
private const val DEFAULT_FLAT_PROJECTION_ASPECT_RATIO = 16f / 9f
private const val MINIMUM_PROJECTION_FOV_DEGREES = 1f
private const val MAXIMUM_FLAT_PROJECTION_FOV_DEGREES = 179f
private const val MAXIMUM_SURROUND_PROJECTION_FOV_DEGREES = 360f
private const val MINIMUM_PROJECTION_ASPECT_RATIO = 0.25f
private const val MAXIMUM_PROJECTION_ASPECT_RATIO = 4f
private const val MINIMUM_PROJECTION_VIEW_ZOOM = 0.5f
private const val MAXIMUM_PROJECTION_VIEW_ZOOM = 4f
private const val MINIMUM_PROJECTION_VIEW_PITCH_DEGREES = -89f
private const val MAXIMUM_PROJECTION_VIEW_PITCH_DEGREES = 89f
private const val HALF_DOME_PROJECTION_FOV_DEGREES = 180f
private const val FULL_DOME_PROJECTION_FOV_DEGREES = 360f
private const val FISHEYE_190_PROJECTION_FOV_DEGREES = 190f
private const val FISHEYE_200_PROJECTION_FOV_DEGREES = 200f
private const val FISHEYE_220_PROJECTION_FOV_DEGREES = 220f
private const val HALF_ROTATION_DEGREES = 180f
private const val FULL_ROTATION_DEGREES = 360f
private const val PROJECTION_SHADER_CODE_FLAT = 0
private const val PROJECTION_SHADER_CODE_EQUIRECT_180 = 1
private const val PROJECTION_SHADER_CODE_EQUIRECT_360 = 2
private const val PROJECTION_SHADER_CODE_FISHEYE_180 = 3
private const val PROJECTION_SHADER_CODE_FISHEYE_190 = 4
private const val PROJECTION_SHADER_CODE_FISHEYE_200 = 5
private const val PROJECTION_SHADER_CODE_FISHEYE_220 = 6
private const val PROJECTION_SHADER_CODE_EAC_360 = 7
