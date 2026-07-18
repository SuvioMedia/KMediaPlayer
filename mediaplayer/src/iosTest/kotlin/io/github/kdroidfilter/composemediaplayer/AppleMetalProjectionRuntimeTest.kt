package io.github.kdroidfilter.composemediaplayer

import platform.QuartzCore.CALayer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AppleMetalProjectionRuntimeTest {
    @Test
    fun `native AVPlayer layer does not advertise the detached controlled renderer`() {
        val capabilities =
            queryAppleProjectionRendererColorCapabilities(
                runtimeSupport = AppleMetalProjectionRuntimeSupport(hasMetalDevice = true),
                includesControlledRenderer = false,
            )

        assertTrue(capabilities.nativeSurfaceDynamicRanges.isNotEmpty())
        assertTrue(capabilities.controlledHdrDynamicRanges.isEmpty())
        assertFalse(capabilities.supportsToneMappingToSdr)
        assertFalse(capabilities.supportsHdrProjection)
        assertFalse(capabilities.supportsHdr10PlusApplication)
    }

    @Test
    fun metalRuntimeAdvertisesControlledHdr10PlusApplication() {
        val capabilities =
            queryAppleProjectionRendererColorCapabilities(
                runtimeSupport = AppleMetalProjectionRuntimeSupport(hasMetalDevice = true),
                includesNativeSurface = false,
            )

        assertEquals(
            setOf(VideoDynamicRange.HDR10, VideoDynamicRange.HDR10_PLUS, VideoDynamicRange.HLG),
            capabilities.controlledHdrDynamicRanges,
        )
        assertTrue(capabilities.supportsHdrProjection)
        assertTrue(capabilities.supportsToneMappingToSdr)
        assertTrue(capabilities.supportsHdr10PlusApplication)
        assertFalse(VideoDynamicRange.DOLBY_VISION in capabilities.controlledHdrDynamicRanges)
        assertTrue(capabilities.nativeSurfaceDynamicRanges.isEmpty())
        assertFalse(capabilities.supportsDolbyVisionMetadata)
    }

    @Test
    fun failedHdrRangeIsRemovedWithoutDisablingSdrToneMapping() {
        val capabilities =
            queryAppleProjectionRendererColorCapabilities(
                runtimeSupport = AppleMetalProjectionRuntimeSupport(hasMetalDevice = true),
                unavailableHdrRanges = setOf(VideoDynamicRange.HDR10),
                includesNativeSurface = false,
            )

        assertEquals(
            setOf(VideoDynamicRange.HDR10_PLUS, VideoDynamicRange.HLG),
            capabilities.controlledHdrDynamicRanges,
        )
        assertTrue(capabilities.supportsHdrProjection)
        assertTrue(capabilities.supportsToneMappingToSdr)
    }

    @Test
    fun missingMetalDeviceDisablesControlledRendering() {
        val capabilities =
            queryAppleProjectionRendererColorCapabilities(
                runtimeSupport = AppleMetalProjectionRuntimeSupport(hasMetalDevice = false),
                includesNativeSurface = false,
            )

        assertTrue(capabilities.controlledHdrDynamicRanges.isEmpty())
        assertFalse(capabilities.supportsHdrProjection)
        assertFalse(capabilities.supportsToneMappingToSdr)
    }

    @Test
    fun projectionShaderAndPipelineCompileOnAvailableMetalDevice() {
        if (!queryAppleMetalProjectionRuntimeSupport().hasMetalDevice) {
            val creation = AppleMetalProjectionRenderer.create()
            assertNull(creation.renderer)
            assertEquals("No Metal device is available for projected video.", creation.failureDetail)
            return
        }

        val creation = AppleMetalProjectionRenderer.create()
        try {
            assertNull(creation.failureDetail, creation.failureDetail)
            assertNotNull(creation.renderer)
        } finally {
            creation.renderer?.release()
        }
    }

    @Test
    fun layerDynamicRangeBridgeRoundTripsHdrAndSdr() {
        val layer = CALayer()

        layer.configureAppleDynamicRange(hdr = true, contentHeadroom = 10.0)
        assertTrue(layer.isAppleDynamicRangeConfigured(hdr = true))

        layer.configureAppleDynamicRange(hdr = false)
        assertTrue(layer.isAppleDynamicRangeConfigured(hdr = false))
    }
}
