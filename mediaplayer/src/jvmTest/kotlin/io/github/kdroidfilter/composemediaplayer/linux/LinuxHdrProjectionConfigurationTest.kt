package io.github.kdroidfilter.composemediaplayer.linux

import io.github.kdroidfilter.composemediaplayer.DisplayColorCapabilities
import io.github.kdroidfilter.composemediaplayer.DolbyVisionInfo
import io.github.kdroidfilter.composemediaplayer.DolbyVisionPolicy
import io.github.kdroidfilter.composemediaplayer.DynamicMetadataHandling
import io.github.kdroidfilter.composemediaplayer.VideoColorInfo
import io.github.kdroidfilter.composemediaplayer.VideoColorMatrix
import io.github.kdroidfilter.composemediaplayer.VideoColorPrimaries
import io.github.kdroidfilter.composemediaplayer.VideoColorRange
import io.github.kdroidfilter.composemediaplayer.VideoDynamicRange
import io.github.kdroidfilter.composemediaplayer.VideoProjectionSettings
import io.github.kdroidfilter.composemediaplayer.VideoProjectionType
import io.github.kdroidfilter.composemediaplayer.VideoProjectionViewSettings
import io.github.kdroidfilter.composemediaplayer.VideoTextureCrop
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class LinuxHdrProjectionConfigurationTest {
    @Test
    fun `PQ projection configuration carries output luminance and viewport`() {
        val configuration =
            assertNotNull(
                buildLinuxHdrProjectionNativeConfiguration(
                    source =
                        VideoColorInfo(
                            dynamicRange = VideoDynamicRange.HDR10,
                            primaries = VideoColorPrimaries.BT2020,
                            matrix = VideoColorMatrix.BT2020_NCL,
                            range = VideoColorRange.LIMITED,
                        ),
                    display = DisplayColorCapabilities(maxLuminanceNits = 750f, referenceWhiteNits = 203f),
                    dolbyVisionPolicy = DolbyVisionPolicy.AUTO,
                    projection = VideoProjectionSettings(projectionType = VideoProjectionType.Equirect360),
                    projectionView = VideoProjectionViewSettings(yawDegrees = 25f, zoom = 1.5f),
                    textureCrop = VideoTextureCrop(left = 0.1f),
                ),
            )

        assertEquals(0, configuration.integers[0])
        assertEquals(2, configuration.integers[1])
        assertEquals(25f, configuration.floats[1])
        assertEquals(0.1f, configuration.floats[5])
        assertEquals(750f, configuration.floats[22])
        assertEquals(203f, configuration.floats[23])
    }

    @Test
    fun `Dolby Vision requires an explicitly selected HDR10 base layer`() {
        val source =
            VideoColorInfo(
                dynamicRange = VideoDynamicRange.DOLBY_VISION,
                dolbyVision = DolbyVisionInfo(profile = 7, hasHdr10CompatibleBaseLayer = true),
            )
        assertNull(
            buildLinuxHdrProjectionNativeConfiguration(
                source,
                DisplayColorCapabilities(),
                DolbyVisionPolicy.AUTO,
                VideoProjectionSettings(),
                VideoProjectionViewSettings(),
                VideoTextureCrop(),
            ),
        )
        assertNotNull(
            buildLinuxHdrProjectionNativeConfiguration(
                source,
                DisplayColorCapabilities(),
                DolbyVisionPolicy.PREFER_HDR10_BASE_LAYER,
                VideoProjectionSettings(),
                VideoProjectionViewSettings(),
                VideoTextureCrop(),
            ),
        )
    }

    @Test
    fun `HDR10 plus shader application is an explicit native configuration bit`() {
        val source =
            VideoColorInfo(
                dynamicRange = VideoDynamicRange.HDR10_PLUS,
                primaries = VideoColorPrimaries.BT2020,
                matrix = VideoColorMatrix.BT2020_NCL,
            )
        val applied =
            assertNotNull(
                buildLinuxHdrProjectionNativeConfiguration(
                    source = source,
                    display = DisplayColorCapabilities(maxLuminanceNits = 1_000f),
                    dolbyVisionPolicy = DolbyVisionPolicy.AUTO,
                    projection = VideoProjectionSettings(),
                    projectionView = VideoProjectionViewSettings(),
                    textureCrop = VideoTextureCrop(),
                    metadataHandling = DynamicMetadataHandling.APPLIED_BY_RENDERER,
                ),
            )
        val dropped =
            assertNotNull(
                buildLinuxHdrProjectionNativeConfiguration(
                    source = source,
                    display = DisplayColorCapabilities(maxLuminanceNits = 1_000f),
                    dolbyVisionPolicy = DolbyVisionPolicy.AUTO,
                    projection = VideoProjectionSettings(),
                    projectionView = VideoProjectionViewSettings(),
                    textureCrop = VideoTextureCrop(),
                    metadataHandling = DynamicMetadataHandling.DROPPED,
                ),
            )

        assertEquals(1, applied.integers[8])
        assertEquals(0, dropped.integers[8])
    }
}
