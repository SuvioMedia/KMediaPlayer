package io.github.kdroidfilter.composemediaplayer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VideoProjectionDetectorTest {
    @Test
    fun detects180SideBySideLeftRightVideo() {
        val detection =
            detectVideoProjection(
                VideoProjectionDetectionInput(title = "Example VR180 SBS LR 4K"),
            )

        assertEquals(VideoProjectionType.Equirect180, detection.projection.projectionType)
        assertEquals(VideoStereoLayout.SideBySide, detection.projection.stereoLayout)
        assertEquals(VideoEyeOrder.LeftRight, detection.projection.eyeOrder)
        assertEquals(180f, detection.projection.fovDegrees)
        assertEquals(VideoProjectionDetectionConfidence.High, detection.confidence)
    }

    @Test
    fun detectsCompactSideBySideEyeOrderTags() {
        val leftRight =
            detectVideoProjection(
                VideoProjectionDetectionInput(title = "Example_VR180_SBSLR_8K_HEVC"),
            )
        val rightLeft =
            detectVideoProjection(
                VideoProjectionDetectionInput(title = "Example 180 SBS-RL"),
            )

        assertEquals(VideoProjectionType.Equirect180, leftRight.projection.projectionType)
        assertEquals(VideoStereoLayout.SideBySide, leftRight.projection.stereoLayout)
        assertEquals(VideoEyeOrder.LeftRight, leftRight.projection.eyeOrder)
        assertEquals(VideoStereoLayout.SideBySide, rightLeft.projection.stereoLayout)
        assertEquals(VideoEyeOrder.RightLeft, rightLeft.projection.eyeOrder)
    }

    @Test
    fun detectsSplitVrAndDelimitedStereoTags() {
        val detection =
            detectVideoProjection(
                VideoProjectionDetectionInput(title = "Scene VR 180 H-SBS Right Left"),
            )

        assertEquals(VideoProjectionType.Equirect180, detection.projection.projectionType)
        assertEquals(VideoStereoLayout.SideBySide, detection.projection.stereoLayout)
        assertEquals(VideoEyeOrder.RightLeft, detection.projection.eyeOrder)
    }

    @Test
    fun detectsExplicit180AngleShorthand() {
        val detection =
            detectVideoProjection(
                VideoProjectionDetectionInput(title = "Scene 180' SBS passthrough black-bg"),
            )

        assertEquals(VideoProjectionType.Equirect180, detection.projection.projectionType)
        assertEquals(VideoStereoLayout.SideBySide, detection.projection.stereoLayout)
    }

    @Test
    fun detects360OverUnderRightLeftVideo() {
        val detection =
            detectVideoProjection(
                VideoProjectionDetectionInput(title = "Tour 360 OU RL HEVC"),
            )

        assertEquals(VideoProjectionType.Equirect360, detection.projection.projectionType)
        assertEquals(VideoStereoLayout.OverUnder, detection.projection.stereoLayout)
        assertEquals(VideoEyeOrder.RightLeft, detection.projection.eyeOrder)
        assertEquals(360f, detection.projection.fovDegrees)
    }

    @Test
    fun detectsTopBottomAndVertical3dTagsAsOverUnder() {
        val topBottom =
            detectVideoProjection(
                VideoProjectionDetectionInput(title = "Tour_VR360_TopBottom_HEVC"),
            )
        val vertical =
            detectVideoProjection(
                VideoProjectionDetectionInput(title = "Clip 3DV 360"),
            )

        assertEquals(VideoProjectionType.Equirect360, topBottom.projection.projectionType)
        assertEquals(VideoStereoLayout.OverUnder, topBottom.projection.stereoLayout)
        assertEquals(VideoStereoLayout.OverUnder, vertical.projection.stereoLayout)
    }

    @Test
    fun detectsBottomTopAsSwappedOverUnder() {
        val detection =
            detectVideoProjection(
                VideoProjectionDetectionInput(title = "Tour_360_BT_HEVC"),
            )

        assertEquals(VideoProjectionType.Equirect360, detection.projection.projectionType)
        assertEquals(VideoStereoLayout.OverUnder, detection.projection.stereoLayout)
        assertEquals(VideoEyeOrder.RightLeft, detection.projection.eyeOrder)
    }

    @Test
    fun infersStereoLayoutFromVrSourceDimensions() {
        val sideBySide =
            detectVideoProjection(
                VideoProjectionDetectionInput(
                    title = "Example VR180 8K HEVC",
                    videoSizes = listOf(VideoProjectionVideoSize(width = 7680, height = 3840)),
                ),
            )
        val overUnder =
            detectVideoProjection(
                VideoProjectionDetectionInput(
                    title = "Tour VR360 4K HEVC",
                    videoSizes = listOf(VideoProjectionVideoSize(width = 4096, height = 4096)),
                ),
            )

        assertEquals(VideoProjectionType.Equirect180, sideBySide.projection.projectionType)
        assertEquals(VideoStereoLayout.SideBySide, sideBySide.projection.stereoLayout)
        assertEquals(VideoProjectionType.Equirect360, overUnder.projection.projectionType)
        assertEquals(VideoStereoLayout.OverUnder, overUnder.projection.stereoLayout)
    }

    @Test
    fun detectsEyeOrderTokenAsSideBySideStereo() {
        val detection =
            detectVideoProjection(
                VideoProjectionDetectionInput(title = "Example 3D RL HEVC"),
            )

        assertEquals(VideoStereoLayout.SideBySide, detection.projection.stereoLayout)
        assertEquals(VideoEyeOrder.RightLeft, detection.projection.eyeOrder)
    }

    @Test
    fun detectsFisheyeVendorPresets() {
        val mkx = detectVideoProjection(VideoProjectionDetectionInput(title = "Scene MKX200 SBS"))
        val mkx22 = detectVideoProjection(VideoProjectionDetectionInput(title = "Scene_MKX22_LR"))
        val vrca = detectVideoProjection(VideoProjectionDetectionInput(title = "Scene VRCA220 TB"))
        val rf52 = detectVideoProjection(VideoProjectionDetectionInput(title = "Scene_RF52_LR"))
        val reverseFisheye = detectVideoProjection(VideoProjectionDetectionInput(title = "Scene_180F_LR"))

        assertEquals(VideoProjectionType.Fisheye200, mkx.projection.projectionType)
        assertEquals(200f, mkx.projection.fovDegrees)
        assertEquals(VideoProjectionType.Fisheye220, mkx22.projection.projectionType)
        assertEquals(VideoProjectionType.Fisheye220, vrca.projection.projectionType)
        assertEquals(220f, vrca.projection.fovDegrees)
        assertEquals(VideoProjectionType.Fisheye190, rf52.projection.projectionType)
        assertEquals(VideoProjectionType.Fisheye180, reverseFisheye.projection.projectionType)
    }

    @Test
    fun detectsDelimitedLr180FilenameAsFisheyeSideBySide() {
        val detection =
            detectVideoProjection(
                VideoProjectionDetectionInput(
                    title = "Scene_4096p_8K_LR_180.mp4",
                    videoSizes = listOf(VideoProjectionVideoSize(width = 4096, height = 4096)),
                ),
            )

        assertEquals(VideoProjectionType.Fisheye180, detection.projection.projectionType)
        assertEquals(VideoStereoLayout.SideBySide, detection.projection.stereoLayout)
        assertEquals(VideoEyeOrder.LeftRight, detection.projection.eyeOrder)
    }

    @Test
    fun detectsPercentEncodedLr180FilenameAsFisheyeSideBySide() {
        val exactTitle =
            detectVideoProjection(
                VideoProjectionDetectionInput(
                    title = "Example%20Scene_4096p_8K_LR_180.mp4",
                    videoSizes = listOf(VideoProjectionVideoSize(width = 4096, height = 4096)),
                ),
            )
        val encodedSeparator =
            detectVideoProjection(
                VideoProjectionDetectionInput(
                    title = "Scene_4096p_8K_LR%20180.mp4",
                    videoSizes = listOf(VideoProjectionVideoSize(width = 4096, height = 4096)),
                ),
            )

        assertEquals(VideoProjectionType.Fisheye180, exactTitle.projection.projectionType)
        assertEquals(VideoStereoLayout.SideBySide, exactTitle.projection.stereoLayout)
        assertEquals(VideoProjectionType.Fisheye180, encodedSeparator.projection.projectionType)
        assertEquals(VideoStereoLayout.SideBySide, encodedSeparator.projection.stereoLayout)
    }

    @Test
    fun doesNotUseTechnicalUrlPathSegmentsAsStereoTokens() {
        val detection =
            detectVideoProjection(
                VideoProjectionDetectionInput(
                    title = "Example VR180",
                    url = "https://cdn.example/ou/redirect/3thj7p1c7?token=ou&source=tb",
                ),
            )

        assertEquals(VideoProjectionType.Equirect180, detection.projection.projectionType)
        assertEquals(VideoStereoLayout.Mono, detection.projection.stereoLayout)
    }

    @Test
    fun usesUrlFilenameAndFilenameQueryParameterForProjectionDetection() {
        val pathFilename =
            detectVideoProjection(
                VideoProjectionDetectionInput(
                    title = "",
                    url = "https://cdn.example/video/Example_VR180_OU.mp4?token=ignored",
                ),
            )
        val queryFilename =
            detectVideoProjection(
                VideoProjectionDetectionInput(
                    title = "",
                    url = "https://cdn.example/download?id=123&filename=Example_VR180_SBS.mp4",
                ),
            )

        assertEquals(VideoProjectionType.Equirect180, pathFilename.projection.projectionType)
        assertEquals(VideoStereoLayout.OverUnder, pathFilename.projection.stereoLayout)
        assertEquals(VideoProjectionType.Equirect180, queryFilename.projection.projectionType)
        assertEquals(VideoStereoLayout.SideBySide, queryFilename.projection.stereoLayout)
    }

    @Test
    fun detectsEacCompactSuffixes() {
        val forward = detectVideoProjection(VideoProjectionDetectionInput(title = "Tour_EAC360_TB"))
        val reverse = detectVideoProjection(VideoProjectionDetectionInput(title = "Tour_360EAC_3DV"))

        assertEquals(VideoProjectionType.Eac360, forward.projection.projectionType)
        assertEquals(VideoProjectionType.Eac360, reverse.projection.projectionType)
        assertEquals(VideoStereoLayout.OverUnder, reverse.projection.stereoLayout)
    }

    @Test
    fun doesNotTreatPlainEpisodeNumbersAsVrAngles() {
        val detection =
            detectVideoProjection(
                VideoProjectionDetectionInput(title = "Episode 180 H264"),
            )

        assertEquals(VideoProjectionType.Flat, detection.projection.projectionType)
        assertEquals(VideoStereoLayout.Mono, detection.projection.stereoLayout)
        assertEquals(VideoProjectionDetectionConfidence.None, detection.confidence)
        assertTrue(detection.tokens.isEmpty())
    }

    @Test
    fun doesNotTreat1080pAs180() {
        val detection =
            detectVideoProjection(
                VideoProjectionDetectionInput(title = "Movie 1080p H264 2D"),
            )

        assertEquals(VideoProjectionType.Flat, detection.projection.projectionType)
        assertEquals(VideoStereoLayout.Mono, detection.projection.stereoLayout)
        assertTrue("180" !in detection.tokens)
    }
}
