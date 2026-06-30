package io.github.kdroidfilter.composemediaplayer

internal val VideoProjectionType.isMedia3SphericalProjection: Boolean
    get() =
        when (this) {
            VideoProjectionType.Equirect360,
            -> true

            VideoProjectionType.Flat,
            VideoProjectionType.Equirect180,
            VideoProjectionType.Fisheye180,
            VideoProjectionType.Fisheye190,
            VideoProjectionType.Fisheye200,
            VideoProjectionType.Fisheye220,
            VideoProjectionType.Eac360,
            -> false
        }

internal fun VideoProjectionSettings.usesMedia3SphericalProjection(textureCrop: VideoTextureCrop): Boolean =
    normalized().projectionType.isMedia3SphericalProjection && textureCrop.isDefaultTextureCrop

internal fun VideoProjectionSettings.usesAndroidCustomProjectionRenderer(textureCrop: VideoTextureCrop): Boolean =
    normalized().let { projection ->
        (projection.requiresProjectionRenderer || !textureCrop.isDefaultTextureCrop) &&
            !projection.usesMedia3SphericalProjection(textureCrop)
    }
