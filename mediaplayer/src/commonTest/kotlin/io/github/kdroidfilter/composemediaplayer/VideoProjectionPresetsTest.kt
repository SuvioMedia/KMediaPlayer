package io.github.kdroidfilter.composemediaplayer

import kotlin.test.Test
import kotlin.test.assertEquals

class VideoProjectionPresetsTest {
    @Test
    fun matchesCommonVr180SideBySidePreset() {
        val projection =
            VideoProjectionSettings(
                projectionType = VideoProjectionType.Equirect180,
                stereoLayout = VideoStereoLayout.SideBySide,
                fovDegrees = 180f,
            )

        assertEquals("vr180-sbs", projection.matchingVideoProjectionPreset()?.id)
    }

    @Test
    fun customProjectionStartsPresetCycleFromFlat2d() {
        val projection =
            VideoProjectionSettings(
                projectionType = VideoProjectionType.Flat,
                stereoLayout = VideoStereoLayout.SideBySide,
            )

        assertEquals("flat-2d", projection.nextVideoProjectionPreset().id)
    }

    @Test
    fun presetCycleAdvancesFromFlatToVr180SideBySide() {
        val projection = VideoProjectionSettings()

        assertEquals("vr180-sbs", projection.nextVideoProjectionPreset().id)
    }

    @Test
    fun presetCycleWrapsAround() {
        val lastProjection = videoProjectionPresets.last().projection

        assertEquals(videoProjectionPresets.first().id, lastProjection.nextVideoProjectionPreset().id)
    }

    @Test
    fun manual180ProjectionDefaultsMonoSourceToSideBySide() {
        val projection = VideoProjectionSettings().withProjectionTypeDefaults(VideoProjectionType.Equirect180)

        assertEquals(VideoProjectionType.Equirect180, projection.projectionType)
        assertEquals(VideoStereoLayout.SideBySide, projection.stereoLayout)
        assertEquals(180f, projection.fovDegrees)
    }

    @Test
    fun manualFlatProjectionResetsStereoToMono() {
        val projection =
            VideoProjectionSettings(
                projectionType = VideoProjectionType.Equirect180,
                stereoLayout = VideoStereoLayout.SideBySide,
            ).withProjectionTypeDefaults(VideoProjectionType.Flat)

        assertEquals(VideoProjectionType.Flat, projection.projectionType)
        assertEquals(VideoStereoLayout.Mono, projection.stereoLayout)
        assertEquals(VideoProjectionType.Flat.defaultFovDegrees(), projection.fovDegrees)
    }

    @Test
    fun manualProjectionKeepsExplicitStereoLayout() {
        val projection =
            VideoProjectionSettings(
                stereoLayout = VideoStereoLayout.OverUnder,
            ).withProjectionTypeDefaults(VideoProjectionType.Fisheye180)

        assertEquals(VideoProjectionType.Fisheye180, projection.projectionType)
        assertEquals(VideoStereoLayout.OverUnder, projection.stereoLayout)
    }

    @Test
    fun normalizedProjectionUsesProjectionDefaultFovWhenOnlyTypeChanges() {
        val projection = VideoProjectionSettings(projectionType = VideoProjectionType.Equirect180).normalized()

        assertEquals(180f, projection.fovDegrees)
    }

    @Test
    fun normalizedFlatProjectionClampsFovBelowPerspectiveSingularity() {
        val projection =
            VideoProjectionSettings(
                projectionType = VideoProjectionType.Flat,
                fovDegrees = 360f,
            ).normalized()

        assertEquals(179f, projection.fovDegrees)
    }
}
