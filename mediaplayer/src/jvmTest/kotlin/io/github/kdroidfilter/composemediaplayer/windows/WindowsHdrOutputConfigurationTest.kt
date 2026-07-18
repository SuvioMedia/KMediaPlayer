package io.github.kdroidfilter.composemediaplayer.windows

import io.github.kdroidfilter.composemediaplayer.ContentLightLevelMetadata
import io.github.kdroidfilter.composemediaplayer.DolbyVisionEnhancementLayer
import io.github.kdroidfilter.composemediaplayer.DolbyVisionInfo
import io.github.kdroidfilter.composemediaplayer.DolbyVisionPolicy
import io.github.kdroidfilter.composemediaplayer.DynamicMetadataHandling
import io.github.kdroidfilter.composemediaplayer.MasteringDisplayMetadata
import io.github.kdroidfilter.composemediaplayer.VideoColorInfo
import io.github.kdroidfilter.composemediaplayer.VideoColorMatrix
import io.github.kdroidfilter.composemediaplayer.VideoColorPrimaries
import io.github.kdroidfilter.composemediaplayer.VideoColorRange
import io.github.kdroidfilter.composemediaplayer.VideoDynamicRange
import io.github.kdroidfilter.composemediaplayer.VideoEyeOrder
import io.github.kdroidfilter.composemediaplayer.VideoProjectionRotation
import io.github.kdroidfilter.composemediaplayer.VideoProjectionSettings
import io.github.kdroidfilter.composemediaplayer.VideoProjectionType
import io.github.kdroidfilter.composemediaplayer.VideoProjectionViewSettings
import io.github.kdroidfilter.composemediaplayer.VideoStereoLayout
import io.github.kdroidfilter.composemediaplayer.VideoTextureCrop
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WindowsHdrOutputConfigurationTest {
    @Test
    fun `HDR10 projection and mastering metadata map to fixed native ABI`() {
        val configuration =
            buildWindowsHdrNativeConfiguration(
                source =
                    VideoColorInfo(
                        dynamicRange = VideoDynamicRange.HDR10,
                        primaries = VideoColorPrimaries.BT2020,
                        matrix = VideoColorMatrix.BT2020_NCL,
                        range = VideoColorRange.FULL,
                        masteringDisplay = masteringDisplay(),
                        contentLightLevel = ContentLightLevelMetadata(4_000, 600),
                    ),
                dolbyVisionPolicy = DolbyVisionPolicy.AUTO,
                projection =
                    VideoProjectionSettings(
                        projectionType = VideoProjectionType.Equirect360,
                        stereoLayout = VideoStereoLayout.SideBySide,
                        eyeOrder = VideoEyeOrder.RightLeft,
                        fovDegrees = 360f,
                        rotation = VideoProjectionRotation.Rotate90,
                    ),
                projectionView = VideoProjectionViewSettings(20f, -15f, 5f, 1.5f),
                textureCrop = VideoTextureCrop(0.01f, 0.02f, 0.03f, 0.04f),
            )

        assertNotNull(configuration)
        assertContentEquals(intArrayOf(0, 2, 1, 1, 1, 1, 0, 0, 0, 0), configuration.integers)
        assertEquals(22, configuration.floats.size)
        assertEquals(4_000f, configuration.floats[9])
        assertEquals(0.708f, configuration.floats[10])
        assertEquals(0.005f, configuration.floats[18])
        assertEquals(600f, configuration.floats[21])
    }

    @Test
    fun `unsupported constant-luminance input is rejected instead of rendered with a wrong matrix`() {
        assertNull(
            buildWindowsHdrNativeConfiguration(
                source =
                    VideoColorInfo(
                        dynamicRange = VideoDynamicRange.HDR10,
                        primaries = VideoColorPrimaries.BT2020,
                        matrix = VideoColorMatrix.BT2020_CL,
                    ),
                dolbyVisionPolicy = DolbyVisionPolicy.AUTO,
                projection = VideoProjectionSettings(),
                projectionView = VideoProjectionViewSettings(),
                textureCrop = VideoTextureCrop(),
            ),
        )
    }

    @Test
    fun `HLG selects linear scRGB native transfer route`() {
        val configuration =
            buildWindowsHdrNativeConfiguration(
                source = VideoColorInfo(dynamicRange = VideoDynamicRange.HLG),
                dolbyVisionPolicy = DolbyVisionPolicy.AUTO,
                projection = VideoProjectionSettings(),
                projectionView = VideoProjectionViewSettings(),
                textureCrop = VideoTextureCrop(),
            )

        assertNotNull(configuration)
        assertEquals(1, configuration.integers[0])
        assertEquals(1_000f, configuration.floats[9])
    }

    @Test
    fun `HDR10 plus application is an explicit per-route native capability`() {
        val source = VideoColorInfo(dynamicRange = VideoDynamicRange.HDR10_PLUS)
        val applied =
            buildWindowsHdrNativeConfiguration(
                source = source,
                dolbyVisionPolicy = DolbyVisionPolicy.AUTO,
                projection = VideoProjectionSettings(),
                projectionView = VideoProjectionViewSettings(),
                textureCrop = VideoTextureCrop(),
                metadataHandling = DynamicMetadataHandling.APPLIED_BY_RENDERER,
            )
        val dropped =
            buildWindowsHdrNativeConfiguration(
                source = source,
                dolbyVisionPolicy = DolbyVisionPolicy.AUTO,
                projection = VideoProjectionSettings(),
                projectionView = VideoProjectionViewSettings(),
                textureCrop = VideoTextureCrop(),
                metadataHandling = DynamicMetadataHandling.DROPPED,
            )

        assertNotNull(applied)
        assertNotNull(dropped)
        assertEquals(1, applied.integers[8])
        assertEquals(0, dropped.integers[8])
    }

    @Test
    fun `forced SDR selects the controlled native tone mapping output`() {
        val configuration =
            buildWindowsHdrNativeConfiguration(
                source = VideoColorInfo(dynamicRange = VideoDynamicRange.HDR10),
                dolbyVisionPolicy = DolbyVisionPolicy.AUTO,
                projection = VideoProjectionSettings(),
                projectionView = VideoProjectionViewSettings(),
                textureCrop = VideoTextureCrop(),
                forceSdrOutput = true,
            )

        assertNotNull(configuration)
        assertEquals(1, configuration.integers[9])
    }

    @Test
    fun `Dolby Vision only exposes verified HDR10 base layer policy`() {
        val source =
            VideoColorInfo(
                dynamicRange = VideoDynamicRange.DOLBY_VISION,
                dolbyVision =
                    DolbyVisionInfo(
                        profile = 7,
                        hasRpu = true,
                        enhancementLayer = DolbyVisionEnhancementLayer.MEL,
                        hasHdr10CompatibleBaseLayer = true,
                    ),
            )

        assertNull(
            buildWindowsHdrNativeConfiguration(
                source,
                DolbyVisionPolicy.AUTO,
                VideoProjectionSettings(),
                VideoProjectionViewSettings(),
                VideoTextureCrop(),
            ),
        )
        assertNotNull(
            buildWindowsHdrNativeConfiguration(
                source,
                DolbyVisionPolicy.PREFER_HDR10_BASE_LAYER,
                VideoProjectionSettings(),
                VideoProjectionViewSettings(),
                VideoTextureCrop(),
            ),
        )
    }

    @Test
    fun `native status requires display swapchain P010 and a presented frame`() {
        val base = nativeStatus(firstFramePresented = false)
        assertFalse(base.isConfirmedHdrOutput)
        assertTrue(base.copy(firstFramePresented = true).isConfirmedHdrOutput)
        assertFalse(base.copy(firstFramePresented = true).isConfirmedSdrOutput)
        assertTrue(
            base
                .copy(
                    advancedColorEnabled = false,
                    firstFramePresented = true,
                    swapChainColorSpace = 0,
                ).isConfirmedSdrOutput,
        )
        assertEquals(
            setOf(VideoDynamicRange.HDR10, VideoDynamicRange.HLG),
            base.displayCapabilities().supportedDynamicRanges,
        )
        assertEquals(
            setOf(VideoDynamicRange.HDR10, VideoDynamicRange.HLG),
            base.copy(bitsPerColor = 8).displayCapabilities().supportedDynamicRanges,
        )
    }

    private fun masteringDisplay() =
        MasteringDisplayMetadata(
            redX = 0.708f,
            redY = 0.292f,
            greenX = 0.170f,
            greenY = 0.797f,
            blueX = 0.131f,
            blueY = 0.046f,
            whiteX = 0.3127f,
            whiteY = 0.3290f,
            minLuminanceNits = 0.005f,
            maxLuminanceNits = 1_000f,
        )

    private fun nativeStatus(firstFramePresented: Boolean) =
        WindowsNativeHdrOutputStatus(
            displayQueried = true,
            advancedColorEnabled = true,
            swapChainConfigured = true,
            firstFramePresented = firstFramePresented,
            p010InputConfirmed = true,
            bitsPerColor = 10,
            displayColorSpace = 12,
            swapChainColorSpace = 12,
            monitorGeneration = 1,
            lastError = 0,
            minLuminanceNits = 0.005f,
            maxLuminanceNits = 1_000f,
            maxFullFrameLuminanceNits = 600f,
        )
}
