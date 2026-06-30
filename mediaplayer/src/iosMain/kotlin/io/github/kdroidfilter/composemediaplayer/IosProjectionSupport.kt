package io.github.kdroidfilter.composemediaplayer

internal fun VideoProjectionSettings.usesIosSceneKitProjectionRenderer(textureCrop: VideoTextureCrop): Boolean =
    requiresProjectionRenderer || !textureCrop.isDefaultTextureCrop

internal fun VideoProjectionSettings.iosVideoRendererLabel(textureCrop: VideoTextureCrop): String =
    when {
        usesIosSceneKitProjectionRenderer(textureCrop) -> "AVPlayer -> SceneKit projection view"
        else -> "AVPlayerLayer"
    }
