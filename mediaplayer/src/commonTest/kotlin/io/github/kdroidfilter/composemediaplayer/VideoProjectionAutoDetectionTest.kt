package io.github.kdroidfilter.composemediaplayer

import kotlin.test.Test
import kotlin.test.assertEquals

class VideoProjectionAutoDetectionTest {
    @Test
    fun defaultOptionsDetectProjectionFromUrlFilename() {
        val projection = VideoPlaybackOptions().detectProjectionForSource("https://cdn.example/movie_VR180_SBS.mp4")

        assertEquals(VideoProjectionType.Equirect180, projection.projectionType)
        assertEquals(VideoStereoLayout.SideBySide, projection.stereoLayout)
    }

    @Test
    fun configuredProjectionWinsOverAutoDetection() {
        val projection =
            VideoPlaybackOptions(
                projection =
                    VideoProjectionSettings(
                        projectionType = VideoProjectionType.Equirect360,
                        stereoLayout = VideoStereoLayout.OverUnder,
                    ),
            ).detectProjectionForSource("https://cdn.example/movie_VR180_SBS.mp4")

        assertEquals(VideoProjectionType.Equirect360, projection.projectionType)
        assertEquals(VideoStereoLayout.OverUnder, projection.stereoLayout)
    }

    @Test
    fun disabledDetectionKeepsDefaultFlatProjection() {
        val projection =
            VideoPlaybackOptions(
                projectionDetectionMode = VideoProjectionDetectionMode.DISABLED,
            ).detectProjectionForSource("https://cdn.example/movie_VR180_SBS.mp4")

        assertEquals(VideoProjectionType.Flat, projection.projectionType)
        assertEquals(VideoStereoLayout.Mono, projection.stereoLayout)
    }

    @Test
    fun dimensionsCanFillMissingStereoLayoutForAutoDetection() {
        val projection =
            VideoPlaybackOptions().detectProjectionForSource(
                uri = "https://cdn.example/movie_VR360.mp4",
                videoSizes = listOf(VideoProjectionVideoSize(width = 4096, height = 4096)),
            )

        assertEquals(VideoProjectionType.Equirect360, projection.projectionType)
        assertEquals(VideoStereoLayout.OverUnder, projection.stereoLayout)
    }
}
