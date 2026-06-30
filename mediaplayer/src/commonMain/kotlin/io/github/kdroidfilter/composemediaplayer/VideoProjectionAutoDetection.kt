package io.github.kdroidfilter.composemediaplayer

internal fun VideoPlaybackOptions.usesAutoProjectionDetection(): Boolean =
    projectionDetectionMode == VideoProjectionDetectionMode.AUTO &&
        projection.isDefaultProjectionSettings

internal fun VideoPlaybackOptions.detectProjectionForSource(
    uri: String,
    title: String? = null,
    metadata: List<String> = emptyList(),
    videoSizes: List<VideoProjectionVideoSize> = emptyList(),
): VideoProjectionSettings {
    val configuredProjection = projection.normalized()
    if (!usesAutoProjectionDetection()) return configuredProjection

    val detection =
        detectVideoProjection(
            VideoProjectionDetectionInput(
                title = title.orEmpty(),
                url = uri,
                metadata = metadata,
                videoSizes = videoSizes,
            ),
        )

    return when (detection.confidence) {
        VideoProjectionDetectionConfidence.None -> configuredProjection
        VideoProjectionDetectionConfidence.Low,
        VideoProjectionDetectionConfidence.High,
        -> detection.projection.normalized()
    }
}
