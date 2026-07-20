package io.github.kdroidfilter.composemediaplayer

internal enum class WebPlaybackRoute {
    MOVI,
    MOVI_DRM,
    LEGACY,
    REJECTED,
}

internal data class WebPlaybackDecision(
    val route: WebPlaybackRoute,
    val error: VideoPlayerError? = null,
) {
    init {
        require((route == WebPlaybackRoute.REJECTED) == (error != null)) {
            "Only a rejected web playback route may contain an error."
        }
    }
}

internal fun VideoPlaybackOptions.webPlaybackDecision(
    projection: VideoProjectionSettings = this.projection,
    textureCrop: VideoTextureCrop = projectionTextureCrop,
): WebPlaybackDecision {
    if (webPlaybackEngine == WebPlaybackEngine.LEGACY) {
        return WebPlaybackDecision(WebPlaybackRoute.LEGACY)
    }

    val drm = webDrmConfiguration
    val strictColorPolicy =
        dynamicRangePolicy == DynamicRangePolicy.REQUIRE_HDR ||
            dynamicRangePolicy == DynamicRangePolicy.FORCE_SDR ||
            dolbyVisionPolicy != DolbyVisionPolicy.AUTO
    val requiresProjection =
        projection.requiresProjectionRenderer || !textureCrop.isDefaultTextureCrop

    if (drm != null && (strictColorPolicy || requiresProjection)) {
        val incompatibleFeatures =
            buildList {
                if (strictColorPolicy) add("the requested strict color policy")
                if (requiresProjection) add("the requested projection")
            }.joinToString(" and ")
        return WebPlaybackDecision(
            route = WebPlaybackRoute.REJECTED,
            error =
                VideoPlayerError.DrmError(
                    "DRM playback cannot safely combine with $incompatibleFeatures on WebAssembly.",
                ),
        )
    }

    if (drm != null) {
        return WebPlaybackDecision(WebPlaybackRoute.MOVI_DRM)
    }

    if (strictColorPolicy) {
        return WebPlaybackDecision(WebPlaybackRoute.LEGACY)
    }

    return WebPlaybackDecision(WebPlaybackRoute.MOVI)
}
