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
    sourceUri: String? = null,
): WebPlaybackDecision {
    val adaptiveStreamingFormat = sourceUri?.webAdaptiveStreamingFormatOrNull()
    if (webPlaybackEngine == WebPlaybackEngine.LEGACY) {
        return adaptiveStreamingFormat?.legacyPlaybackRejection()
            ?: WebPlaybackDecision(WebPlaybackRoute.LEGACY)
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
        if (adaptiveStreamingFormat != null) {
            return WebPlaybackDecision(
                route = WebPlaybackRoute.REJECTED,
                error =
                    VideoPlayerError.ColorPipelineError(
                        reason = ColorPipelineFallbackReason.PLATFORM_RUNTIME_UNAVAILABLE,
                        message =
                            "${adaptiveStreamingFormat.name} playback on WebAssembly requires Movi, " +
                                "which cannot guarantee the requested strict color policy.",
                    ),
            )
        }
        return WebPlaybackDecision(WebPlaybackRoute.LEGACY)
    }

    return WebPlaybackDecision(WebPlaybackRoute.MOVI)
}

private fun WebAdaptiveStreamingFormat.legacyPlaybackRejection(): WebPlaybackDecision =
    WebPlaybackDecision(
        route = WebPlaybackRoute.REJECTED,
        error =
            VideoPlayerError.SourceError(
                "$name playback on WebAssembly requires WebPlaybackEngine.MOVI; " +
                    "the legacy engine does not include an adaptive-streaming implementation.",
            ),
    )
