package io.github.kdroidfilter.composemediaplayer.mac

import io.github.kdroidfilter.composemediaplayer.DynamicMetadataHandling
import io.github.kdroidfilter.composemediaplayer.MasteringDisplayMetadata
import io.github.kdroidfilter.composemediaplayer.VideoColorInfo
import io.github.kdroidfilter.composemediaplayer.VideoColorMatrix
import io.github.kdroidfilter.composemediaplayer.VideoColorPrimaries
import io.github.kdroidfilter.composemediaplayer.VideoColorRange
import io.github.kdroidfilter.composemediaplayer.VideoColorTransfer
import io.github.kdroidfilter.composemediaplayer.VideoDynamicRange
import io.github.kdroidfilter.composemediaplayer.VideoProjectionDisplayMode
import io.github.kdroidfilter.composemediaplayer.VideoProjectionSettings
import io.github.kdroidfilter.composemediaplayer.VideoProjectionType
import io.github.kdroidfilter.composemediaplayer.VideoProjectionViewSettings
import io.github.kdroidfilter.composemediaplayer.VideoStereoLayout
import io.github.kdroidfilter.composemediaplayer.VideoTextureCrop
import io.github.kdroidfilter.composemediaplayer.util.CurrentPlatform
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MacMetalProjectionConfigurationTest {
    @Test
    fun `serializes projection and color state without locale-dependent values`() {
        val configuration =
            macMetalProjectionConfiguration(
                projection =
                    VideoProjectionSettings(
                        projectionType = VideoProjectionType.Equirect360,
                        stereoLayout = VideoStereoLayout.SideBySide,
                    ),
                projectionView = VideoProjectionViewSettings(yawDegrees = 12.5f, pitchDegrees = -3f),
                textureCrop = VideoTextureCrop(left = 0.1f, right = 0.2f),
                source = hdr10Source(),
                outputDynamicRange = VideoDynamicRange.HDR10,
            )

        assertContains(configuration, "enabled=1")
        assertContains(configuration, "type=2")
        assertContains(configuration, "stereo=1")
        assertContains(configuration, "yaw=12.5")
        assertContains(configuration, "transfer=1")
        assertContains(configuration, "matrix=1")
        assertContains(configuration, "primaries=0")
        assertContains(configuration, "outputHdr=1")
        assertContains(configuration, "peak=4000.0")
        assertContains(configuration, "displayPeak=1000.0")
        assertContains(configuration, "hdr10Plus=0")
        assertContains(configuration, "tenBit=1")
        assertContains(configuration, "fullRange=0")
    }

    @Test
    fun `HDR10 plus application is explicit and carries the active display peak`() {
        val configuration =
            macMetalProjectionConfiguration(
                projection = VideoProjectionSettings(projectionType = VideoProjectionType.Equirect360),
                projectionView = VideoProjectionViewSettings(),
                textureCrop = VideoTextureCrop(),
                source = hdr10Source().copy(dynamicRange = VideoDynamicRange.HDR10_PLUS),
                outputDynamicRange = VideoDynamicRange.HDR10,
                metadataHandling = DynamicMetadataHandling.APPLIED_BY_RENDERER,
                displayPeakLuminanceNits = 1_600f,
            )

        assertContains(configuration, "displayPeak=1600.0")
        assertContains(configuration, "hdr10Plus=1")
    }

    @Test
    fun `SDR output keeps the P010 source while disabling EDR presentation`() {
        val configuration =
            macMetalProjectionConfiguration(
                projection = VideoProjectionSettings(projectionType = VideoProjectionType.Fisheye180),
                projectionView = VideoProjectionViewSettings(),
                textureCrop = VideoTextureCrop(),
                source = hdr10Source(),
                outputDynamicRange = VideoDynamicRange.SDR,
            )

        assertContains(configuration, "outputHdr=0")
        assertContains(configuration, "tenBit=1")
    }

    @Test
    fun `monoscopic desktop preview sends one fisheye eye to the full Metal viewport`() {
        val configuration =
            macMetalProjectionConfiguration(
                projection =
                    VideoProjectionSettings(
                        projectionType = VideoProjectionType.Fisheye190,
                        stereoLayout = VideoStereoLayout.SideBySide,
                        displayMode = VideoProjectionDisplayMode.MonoscopicLeft,
                    ),
                projectionView = VideoProjectionViewSettings(),
                textureCrop = VideoTextureCrop(),
                source = hdr10Source(),
                outputDynamicRange = VideoDynamicRange.HDR10,
            )

        assertContains(configuration, "type=4")
        assertContains(configuration, "stereo=0")
        assertContains(configuration, "left=0.0,0.0,0.5,1.0,0")
        assertContains(configuration, "right=0.0,0.0,0.5,1.0,0")
    }

    @Test
    fun `serializes source transfer and primaries for explicit Metal conversion`() {
        val configuration =
            macMetalProjectionConfiguration(
                projection = VideoProjectionSettings(),
                projectionView = VideoProjectionViewSettings(),
                textureCrop = VideoTextureCrop(),
                source =
                    VideoColorInfo(
                        dynamicRange = VideoDynamicRange.SDR,
                        bitDepth = 8,
                        primaries = VideoColorPrimaries.DISPLAY_P3,
                        transfer = VideoColorTransfer.SRGB,
                        matrix = VideoColorMatrix.RGB,
                        range = VideoColorRange.FULL,
                    ),
                outputDynamicRange = VideoDynamicRange.SDR,
            )

        assertContains(configuration, "transfer=3")
        assertContains(configuration, "primaries=2")
        assertContains(configuration, "fullRange=1")
    }

    @Test
    fun `infers BT2020 primaries from a tagged BT2020 matrix`() {
        val configuration =
            macMetalProjectionConfiguration(
                projection = VideoProjectionSettings(),
                projectionView = VideoProjectionViewSettings(),
                textureCrop = VideoTextureCrop(),
                source =
                    hdr10Source().copy(
                        primaries = VideoColorPrimaries.UNKNOWN,
                        transfer = VideoColorTransfer.LINEAR,
                    ),
                outputDynamicRange = VideoDynamicRange.HDR10,
            )

        assertContains(configuration, "transfer=4")
        assertContains(configuration, "primaries=0")
    }

    @Test
    fun `bundled native renderer compiles its Metal projection pipeline`() {
        assumeTrue(CurrentPlatform.os == CurrentPlatform.OS.MAC)
        val handle = MacNativeBridge.nCreatePlayer()
        assertTrue(handle != 0L)
        try {
            val configured =
                MacNativeBridge.nSetHdrMetalProjectionConfiguration(
                    handle,
                    macMetalProjectionConfiguration(
                        projection = VideoProjectionSettings(projectionType = VideoProjectionType.Equirect360),
                        projectionView = VideoProjectionViewSettings(),
                        textureCrop = VideoTextureCrop(),
                        source = hdr10Source(),
                        outputDynamicRange = VideoDynamicRange.HDR10,
                    ),
                )
            assertTrue(configured)
            assertTrue(MacNativeBridge.nIsHdrMetalAvailable(handle))
            assertNull(MacNativeBridge.nGetHdrRendererFailure(handle))
        } finally {
            assertEquals(Unit, MacNativeBridge.nDisposePlayer(handle))
        }
    }

    private fun hdr10Source(): VideoColorInfo =
        VideoColorInfo(
            dynamicRange = VideoDynamicRange.HDR10,
            bitDepth = 10,
            primaries = VideoColorPrimaries.BT2020,
            transfer = VideoColorTransfer.PQ,
            matrix = VideoColorMatrix.BT2020_NCL,
            range = VideoColorRange.LIMITED,
            masteringDisplay =
                MasteringDisplayMetadata(
                    redX = 0.708f,
                    redY = 0.292f,
                    greenX = 0.17f,
                    greenY = 0.797f,
                    blueX = 0.131f,
                    blueY = 0.046f,
                    whiteX = 0.3127f,
                    whiteY = 0.329f,
                    minLuminanceNits = 0.005f,
                    maxLuminanceNits = 4_000f,
                ),
        )
}
