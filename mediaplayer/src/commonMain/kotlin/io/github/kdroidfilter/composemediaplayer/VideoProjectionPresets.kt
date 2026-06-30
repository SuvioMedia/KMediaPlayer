package io.github.kdroidfilter.composemediaplayer

val videoProjectionPresets: List<VideoProjectionPreset> =
    listOf(
        VideoProjectionPreset(
            id = "flat-2d",
            label = "Flat 2D",
            projection =
                VideoProjectionSettings(
                    projectionType = VideoProjectionType.Flat,
                    stereoLayout = VideoStereoLayout.Mono,
                    fovDegrees = VideoProjectionType.Flat.defaultFovDegrees(),
                ),
        ),
        VideoProjectionPreset(
            id = "vr180-sbs",
            label = "VR180 SBS",
            projection =
                VideoProjectionSettings(
                    projectionType = VideoProjectionType.Equirect180,
                    stereoLayout = VideoStereoLayout.SideBySide,
                    fovDegrees = VideoProjectionType.Equirect180.defaultFovDegrees(),
                ),
        ),
        VideoProjectionPreset(
            id = "vr180-ou",
            label = "VR180 OU",
            projection =
                VideoProjectionSettings(
                    projectionType = VideoProjectionType.Equirect180,
                    stereoLayout = VideoStereoLayout.OverUnder,
                    fovDegrees = VideoProjectionType.Equirect180.defaultFovDegrees(),
                ),
        ),
        VideoProjectionPreset(
            id = "vr360-mono",
            label = "VR360 Mono",
            projection =
                VideoProjectionSettings(
                    projectionType = VideoProjectionType.Equirect360,
                    stereoLayout = VideoStereoLayout.Mono,
                    fovDegrees = VideoProjectionType.Equirect360.defaultFovDegrees(),
                ),
        ),
        VideoProjectionPreset(
            id = "vr360-sbs",
            label = "VR360 SBS",
            projection =
                VideoProjectionSettings(
                    projectionType = VideoProjectionType.Equirect360,
                    stereoLayout = VideoStereoLayout.SideBySide,
                    fovDegrees = VideoProjectionType.Equirect360.defaultFovDegrees(),
                ),
        ),
        VideoProjectionPreset(
            id = "vr360-ou",
            label = "VR360 OU",
            projection =
                VideoProjectionSettings(
                    projectionType = VideoProjectionType.Equirect360,
                    stereoLayout = VideoStereoLayout.OverUnder,
                    fovDegrees = VideoProjectionType.Equirect360.defaultFovDegrees(),
                ),
        ),
        VideoProjectionPreset(
            id = "fisheye180-sbs",
            label = "Fisheye 180 SBS",
            projection =
                VideoProjectionSettings(
                    projectionType = VideoProjectionType.Fisheye180,
                    stereoLayout = VideoStereoLayout.SideBySide,
                    fovDegrees = VideoProjectionType.Fisheye180.defaultFovDegrees(),
                ),
        ),
        VideoProjectionPreset(
            id = "mkx200-sbs",
            label = "MKX200 SBS",
            projection =
                VideoProjectionSettings(
                    projectionType = VideoProjectionType.Fisheye200,
                    stereoLayout = VideoStereoLayout.SideBySide,
                    fovDegrees = VideoProjectionType.Fisheye200.defaultFovDegrees(),
                ),
        ),
        VideoProjectionPreset(
            id = "vrca220-ou",
            label = "VRCA220 OU",
            projection =
                VideoProjectionSettings(
                    projectionType = VideoProjectionType.Fisheye220,
                    stereoLayout = VideoStereoLayout.OverUnder,
                    fovDegrees = VideoProjectionType.Fisheye220.defaultFovDegrees(),
                ),
        ),
        VideoProjectionPreset(
            id = "eac360-mono",
            label = "EAC 360",
            projection =
                VideoProjectionSettings(
                    projectionType = VideoProjectionType.Eac360,
                    stereoLayout = VideoStereoLayout.Mono,
                    fovDegrees = VideoProjectionType.Eac360.defaultFovDegrees(),
                ),
        ),
    )

fun VideoProjectionSettings.matchingVideoProjectionPreset(): VideoProjectionPreset? {
    val normalized = normalized()
    return videoProjectionPresets.firstOrNull { preset -> preset.projection.matchesPreset(normalized) }
}

fun VideoProjectionSettings.nextVideoProjectionPreset(): VideoProjectionPreset {
    val currentIndex = matchingVideoProjectionPreset()?.let(videoProjectionPresets::indexOf) ?: -1
    val nextIndex = (currentIndex + 1).mod(videoProjectionPresets.size)
    return videoProjectionPresets[nextIndex]
}

fun VideoProjectionSettings.withProjectionTypeDefaults(projectionType: VideoProjectionType): VideoProjectionSettings =
    copy(
        projectionType = projectionType,
        stereoLayout =
            when {
                projectionType == VideoProjectionType.Flat -> VideoStereoLayout.Mono
                stereoLayout != VideoStereoLayout.Mono -> stereoLayout
                projectionType.prefersSideBySideByDefault() -> VideoStereoLayout.SideBySide
                else -> VideoStereoLayout.Mono
            },
        fovDegrees = projectionType.defaultFovDegrees(),
    )

private fun VideoProjectionSettings.matchesPreset(other: VideoProjectionSettings): Boolean {
    val normalized = normalized()
    return normalized.projectionType == other.projectionType &&
        normalized.stereoLayout == other.stereoLayout &&
        normalized.eyeOrder == other.eyeOrder &&
        normalized.rotation == other.rotation &&
        normalized.fovDegrees == other.fovDegrees &&
        normalized.aspectRatio == other.aspectRatio
}

private fun VideoProjectionType.prefersSideBySideByDefault(): Boolean =
    when (this) {
        VideoProjectionType.Equirect180,
        VideoProjectionType.Fisheye180,
        VideoProjectionType.Fisheye190,
        VideoProjectionType.Fisheye200,
        VideoProjectionType.Fisheye220,
        -> true

        VideoProjectionType.Flat,
        VideoProjectionType.Equirect360,
        VideoProjectionType.Eac360,
        -> false
    }
