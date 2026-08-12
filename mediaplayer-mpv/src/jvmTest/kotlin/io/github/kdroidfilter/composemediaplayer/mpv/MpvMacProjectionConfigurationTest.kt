package io.github.kdroidfilter.composemediaplayer.mpv

import io.github.kdroidfilter.composemediaplayer.VideoProjectionDisplayMode
import io.github.kdroidfilter.composemediaplayer.VideoProjectionSettings
import io.github.kdroidfilter.composemediaplayer.VideoProjectionType
import io.github.kdroidfilter.composemediaplayer.VideoProjectionViewSettings
import io.github.kdroidfilter.composemediaplayer.VideoStereoLayout
import io.github.kdroidfilter.composemediaplayer.VideoTextureCrop
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MpvMacProjectionConfigurationTest {
    @Test
    fun `projection input always fills the intermediate texture`() {
        assertEquals(
            MpvMacInputGeometry(keepAspect = "no", panscan = "0.0"),
            mpvMacInputGeometry(
                projectionEnabled = true,
                contentScaleMode = MpvMacContentScaleMode.FIT,
            ),
        )
        assertEquals(
            MpvMacInputGeometry(keepAspect = "no", panscan = "0.0"),
            mpvMacInputGeometry(
                projectionEnabled = true,
                contentScaleMode = MpvMacContentScaleMode.FILL,
            ),
        )
    }

    @Test
    fun `ordinary video keeps public content scale behavior`() {
        assertEquals(
            MpvMacInputGeometry(keepAspect = "yes", panscan = "0.0"),
            mpvMacInputGeometry(
                projectionEnabled = false,
                contentScaleMode = MpvMacContentScaleMode.FIT,
            ),
        )
        assertEquals(
            MpvMacInputGeometry(keepAspect = "yes", panscan = "1.0"),
            mpvMacInputGeometry(
                projectionEnabled = false,
                contentScaleMode = MpvMacContentScaleMode.CROP,
            ),
        )
        assertEquals(
            MpvMacInputGeometry(keepAspect = "no", panscan = "0.0"),
            mpvMacInputGeometry(
                projectionEnabled = false,
                contentScaleMode = MpvMacContentScaleMode.FILL,
            ),
        )
    }

    @Test
    fun `default flat source keeps direct libmpv output`() {
        val configuration =
            mpvMacProjectionConfiguration(
                projection = VideoProjectionSettings(),
                projectionView = VideoProjectionViewSettings(),
                textureCrop = VideoTextureCrop(),
            )

        assertFalse(configuration.enabled)
        assertEquals(MPV_MAC_PROJECTION_PARAMETER_COUNT, configuration.toNativeArray().size)
    }

    @Test
    fun `monoscopic fisheye preview selects only the logical left eye`() {
        val configuration =
            mpvMacProjectionConfiguration(
                projection =
                    VideoProjectionSettings(
                        projectionType = VideoProjectionType.Fisheye190,
                        stereoLayout = VideoStereoLayout.SideBySide,
                        displayMode = VideoProjectionDisplayMode.MonoscopicLeft,
                    ),
                projectionView = VideoProjectionViewSettings(yawDegrees = 12f, zoom = 1.25f),
                textureCrop = VideoTextureCrop(),
            )

        assertTrue(configuration.enabled)
        assertEquals(4, configuration.projectionType)
        assertEquals(190f, configuration.fovDegrees)
        assertFalse(configuration.stereo)
        assertContentEquals(floatArrayOf(0f, 0f, 0.5f, 1f), configuration.leftWindow)
        assertContentEquals(configuration.leftWindow, configuration.rightWindow)
        assertEquals(12f, configuration.yawDegrees)
        assertEquals(1.25f, configuration.zoom)
    }

    @Test
    fun `stereo preview retains distinct source eye windows`() {
        val configuration =
            mpvMacProjectionConfiguration(
                projection =
                    VideoProjectionSettings(
                        projectionType = VideoProjectionType.Fisheye190,
                        stereoLayout = VideoStereoLayout.SideBySide,
                    ),
                projectionView = VideoProjectionViewSettings(),
                textureCrop = VideoTextureCrop(),
            )

        assertTrue(configuration.stereo)
        assertContentEquals(floatArrayOf(0f, 0f, 0.5f, 1f), configuration.leftWindow)
        assertContentEquals(floatArrayOf(0.5f, 0f, 1f, 1f), configuration.rightWindow)
    }

    @Test
    fun `flat texture crop still enables the native GPU pass`() {
        val configuration =
            mpvMacProjectionConfiguration(
                projection = VideoProjectionSettings(),
                projectionView = VideoProjectionViewSettings(),
                textureCrop = VideoTextureCrop(left = 0.1f),
            )

        assertTrue(configuration.enabled)
        assertContentEquals(floatArrayOf(0.1f, 0f, 1f, 1f), configuration.leftWindow)
    }

    @Test
    fun `gpu next projection hook exposes every projection control as a dynamic parameter`() {
        val configuration =
            mpvMacProjectionConfiguration(
                projection =
                    VideoProjectionSettings(
                        projectionType = VideoProjectionType.Equirect360,
                        stereoLayout = VideoStereoLayout.SideBySide,
                    ),
                projectionView =
                    VideoProjectionViewSettings(
                        yawDegrees = 12f,
                        pitchDegrees = -5f,
                        rollDegrees = 3f,
                        zoom = 1.25f,
                    ),
                textureCrop = VideoTextureCrop(left = 0.1f),
            )

        val options =
            MpvMacGpuNextProjectionShader.options(
                configuration = configuration,
                contentScaleMode = MpvMacContentScaleMode.CROP,
            )

        assertEquals(MPV_MAC_PROJECTION_PARAMETER_COUNT + 1, options.split(',').size)
        assertContains(options, "kmp_projection_enabled=1")
        assertContains(options, "kmp_projection_type=2")
        assertContains(options, "kmp_stereo=1")
        assertContains(options, "kmp_left_x0=0.05")
        assertContains(options, "kmp_yaw_degrees=12.0")
        assertContains(options, "kmp_pitch_degrees=-5.0")
        assertContains(options, "kmp_content_scale=1")
        assertContains(MpvMacGpuNextProjectionShader.source, "//!HOOK OUTPUT")
        assertContains(MpvMacGpuNextProjectionShader.source, "//!TYPE DYNAMIC float")
        assertContains(MpvMacGpuNextProjectionShader.source, "//!WHEN kmp_projection_enabled")
    }
}
