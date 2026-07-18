package io.github.kdroidfilter.composemediaplayer

import kotlinx.coroutines.CancellationException

/** Resolves at most one source owner. A failed explicit conversion is never silently skipped. */
@Suppress("TooGenericExceptionCaught")
internal suspend fun VideoPlaybackOptions.prepareSourceWithExtensions(
    request: VideoPipelineSourceRequest,
): VideoPipelineSourcePreparation {
    for (
    extension in
    extensions
        .filterIsInstance<VideoSourcePipelineExtension>()
        .filter { it.availability.canContribute }
    ) {
        val result =
            try {
                extension.prepareSource(request)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                VideoPipelineSourcePreparation.Rejected(
                    reason =
                        if (request.requestedOutputDynamicRange == VideoDynamicRange.SDR) {
                            ColorPipelineFallbackReason.TONE_MAPPER_UNAVAILABLE
                        } else {
                            ColorPipelineFallbackReason.DOLBY_VISION_CONVERTER_UNAVAILABLE
                        },
                    detail =
                        "Video pipeline extension ${extension.id} failed: " +
                            (error.message ?: error::class.simpleName.orEmpty()),
                )
            }
        if (result != VideoPipelineSourcePreparation.NotApplicable) return result
    }
    return VideoPipelineSourcePreparation.NotApplicable
}
