@file:OptIn(ExperimentalComposeMediaPlayerBackendApi::class)

package io.github.kdroidfilter.composemediaplayer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VideoProjectionRenderPlanTest {
    @Test
    fun flatProjectionUsesSingleFlatPlaneMesh() {
        val plan = VideoProjectionSettings().toVideoProjectionRenderPlan()

        assertEquals(VideoProjectionMeshType.FlatPlane, plan.mesh.type)
        assertEquals(1, plan.mesh.columns)
        assertEquals(1, plan.mesh.rows)
        assertFalse(plan.stereo)
    }

    @Test
    fun curvedFlatProjectionUsesCurvedPlaneMesh() {
        val plan =
            VideoProjectionSettings().toVideoProjectionRenderPlan(
                VideoProjectionRenderOptions(curvature = 0.5f),
            )

        assertEquals(VideoProjectionMeshType.CurvedPlane, plan.mesh.type)
        assertEquals(32, plan.mesh.columns)
        assertEquals(8, plan.mesh.rows)
    }

    @Test
    fun equirect360UsesDenseSphereMesh() {
        val plan =
            VideoProjectionSettings(
                projectionType = VideoProjectionType.Equirect360,
                fovDegrees = 360f,
            ).toVideoProjectionRenderPlan()

        assertEquals(VideoProjectionMeshType.EquirectSphere, plan.mesh.type)
        assertEquals(360f, plan.mesh.horizontalFovDegrees)
        assertEquals(180f, plan.mesh.verticalFovDegrees)
        assertEquals(96, plan.mesh.columns)
        assertEquals(48, plan.mesh.rows)
    }

    @Test
    fun oversizedFlatFovProducesFinitePerspectiveMesh() {
        val plan =
            VideoProjectionSettings(
                projectionType = VideoProjectionType.Flat,
                fovDegrees = 360f,
            ).toVideoProjectionRenderPlan()

        assertEquals(179f, plan.mesh.horizontalFovDegrees)
        assertTrue(plan.mesh.verticalFovDegrees.isFinite())
        assertTrue(plan.mesh.verticalFovDegrees > 0f)
    }

    @Test
    fun sideBySideLayoutSplitsTextureHorizontally() {
        val plan =
            VideoProjectionSettings(
                projectionType = VideoProjectionType.Equirect180,
                stereoLayout = VideoStereoLayout.SideBySide,
            ).toVideoProjectionRenderPlan()

        assertTrue(plan.stereo)
        assertEquals(VideoTextureWindow(0f, 0f, 0.5f, 1f, VideoProjectionRotation.None), plan.leftEyeTexture)
        assertEquals(VideoTextureWindow(0.5f, 0f, 1f, 1f, VideoProjectionRotation.None), plan.rightEyeTexture)
    }

    @Test
    fun rightLeftSideBySideSwapsTextureWindows() {
        val plan =
            VideoProjectionSettings(
                projectionType = VideoProjectionType.Equirect180,
                stereoLayout = VideoStereoLayout.SideBySide,
                eyeOrder = VideoEyeOrder.RightLeft,
            ).toVideoProjectionRenderPlan()

        assertEquals(VideoTextureWindow(0.5f, 0f, 1f, 1f, VideoProjectionRotation.None), plan.leftEyeTexture)
        assertEquals(VideoTextureWindow(0f, 0f, 0.5f, 1f, VideoProjectionRotation.None), plan.rightEyeTexture)
    }

    @Test
    fun monoscopicLeftUsesTheLogicalLeftEyeAcrossOneViewport() {
        val plan =
            VideoProjectionSettings(
                projectionType = VideoProjectionType.Fisheye190,
                stereoLayout = VideoStereoLayout.SideBySide,
                displayMode = VideoProjectionDisplayMode.MonoscopicLeft,
            ).toVideoProjectionRenderPlan()

        assertFalse(plan.stereo)
        assertEquals(VideoTextureWindow(0f, 0f, 0.5f, 1f, VideoProjectionRotation.None), plan.leftEyeTexture)
        assertEquals(plan.leftEyeTexture, plan.rightEyeTexture)
    }

    @Test
    fun monoscopicRightHonoursRightLeftSourceOrderAndPerEyeCrop() {
        val plan =
            VideoProjectionSettings(
                projectionType = VideoProjectionType.Fisheye190,
                stereoLayout = VideoStereoLayout.SideBySide,
                eyeOrder = VideoEyeOrder.RightLeft,
                displayMode = VideoProjectionDisplayMode.MonoscopicRight,
            ).toVideoProjectionRenderPlan(
                VideoProjectionRenderOptions(textureCrop = VideoTextureCrop(left = 0.1f, right = 0.1f)),
            )

        assertFalse(plan.stereo)
        assertEquals(VideoTextureWindow(0.05f, 0f, 0.45f, 1f, VideoProjectionRotation.None), plan.leftEyeTexture)
        assertEquals(plan.leftEyeTexture, plan.rightEyeTexture)
    }

    @Test
    fun overUnderLayoutSplitsTextureVertically() {
        val plan =
            VideoProjectionSettings(
                projectionType = VideoProjectionType.Equirect360,
                stereoLayout = VideoStereoLayout.OverUnder,
            ).toVideoProjectionRenderPlan()

        assertEquals(VideoTextureWindow(0f, 0f, 1f, 0.5f, VideoProjectionRotation.None), plan.leftEyeTexture)
        assertEquals(VideoTextureWindow(0f, 0.5f, 1f, 1f, VideoProjectionRotation.None), plan.rightEyeTexture)
    }

    @Test
    fun textureCropIsAppliedWithinEachEyeWindow() {
        val plan =
            VideoProjectionSettings(
                projectionType = VideoProjectionType.Equirect180,
                stereoLayout = VideoStereoLayout.SideBySide,
            ).toVideoProjectionRenderPlan(
                VideoProjectionRenderOptions(
                    textureCrop = VideoTextureCrop(left = 0.1f, top = 0.2f, right = 0.1f, bottom = 0.2f),
                ),
            )

        assertEquals(VideoTextureWindow(0.05f, 0.2f, 0.45f, 0.8f, VideoProjectionRotation.None), plan.leftEyeTexture)
        assertEquals(VideoTextureWindow(0.55f, 0.2f, 0.95f, 0.8f, VideoProjectionRotation.None), plan.rightEyeTexture)
    }

    @Test
    fun textureCropNormalizationKeepsAVisibleTextureWindow() {
        val crop =
            VideoTextureCrop(
                left = Float.NaN,
                top = 0.7f,
                right = Float.POSITIVE_INFINITY,
                bottom = -0.1f,
            ).normalized()

        assertEquals(VideoTextureCrop(left = 0f, top = 0.49f, right = 0f, bottom = 0f), crop)
    }

    @Test
    fun defaultTextureCropReflectsNormalizedValues() {
        assertTrue(VideoTextureCrop(left = -0.2f, top = -0.1f).isDefaultTextureCrop)
        assertFalse(VideoTextureCrop(right = 0.01f).isDefaultTextureCrop)
    }
}
