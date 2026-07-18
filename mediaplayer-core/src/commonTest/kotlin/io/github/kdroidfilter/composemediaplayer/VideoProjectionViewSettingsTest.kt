@file:OptIn(ExperimentalComposeMediaPlayerBackendApi::class)

package io.github.kdroidfilter.composemediaplayer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VideoProjectionViewSettingsTest {
    @Test
    fun normalizedViewSettingsClampPitchAndZoom() {
        val settings =
            VideoProjectionViewSettings(
                yawDegrees = 540f,
                pitchDegrees = 120f,
                rollDegrees = -540f,
                zoom = 10f,
            ).normalized()

        assertEquals(180f, settings.yawDegrees)
        assertEquals(89f, settings.pitchDegrees)
        assertEquals(-180f, settings.rollDegrees)
        assertEquals(4f, settings.zoom)
    }

    @Test
    fun autoViewControlUsesDeviceMotionOnlyForSurroundProjection() {
        assertFalse(
            VideoProjectionViewControlMode.AUTO.usesDeviceMotionFor(VideoProjectionSettings()),
        )
        assertFalse(
            VideoProjectionViewControlMode.AUTO.usesDeviceMotionFor(
                VideoProjectionSettings(stereoLayout = VideoStereoLayout.SideBySide),
            ),
        )
        assertTrue(
            VideoProjectionViewControlMode.AUTO.usesDeviceMotionFor(
                VideoProjectionSettings(projectionType = VideoProjectionType.Equirect180),
            ),
        )
    }

    @Test
    fun explicitDeviceMotionUsesProjectedRendererModes() {
        assertFalse(
            VideoProjectionViewControlMode.DEVICE_MOTION.usesDeviceMotionFor(VideoProjectionSettings()),
        )
        assertTrue(
            VideoProjectionViewControlMode.DEVICE_MOTION.usesDeviceMotionFor(
                VideoProjectionSettings(stereoLayout = VideoStereoLayout.SideBySide),
            ),
        )
    }

    @Test
    fun manualViewControlNeverUsesDeviceMotion() {
        assertFalse(
            VideoProjectionViewControlMode.MANUAL.usesDeviceMotionFor(
                VideoProjectionSettings(projectionType = VideoProjectionType.Equirect360),
            ),
        )
    }
}
