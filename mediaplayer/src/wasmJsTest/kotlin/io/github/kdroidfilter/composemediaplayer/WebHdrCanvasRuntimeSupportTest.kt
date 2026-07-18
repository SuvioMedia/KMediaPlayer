package io.github.kdroidfilter.composemediaplayer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WebHdrCanvasRuntimeSupportTest {
    @Test
    fun `HDR canvas attempt requires WebGPU configuration readback and an HDR display`() {
        assertTrue(fullySupportedRuntime().canAttemptHdrCanvas)
        assertFalse(fullySupportedRuntime().copy(hasWebGpu = false).canAttemptHdrCanvas)
        assertFalse(fullySupportedRuntime().copy(hasCanvasConfigurationReadback = false).canAttemptHdrCanvas)
        assertFalse(fullySupportedRuntime().copy(displayReportsHighDynamicRange = false).canAttemptHdrCanvas)
    }

    @Test
    fun `controlled ranges are limited to static PQ and HLG exposed by the active display`() {
        val display =
            DisplayColorCapabilities(
                isKnown = true,
                supportedDynamicRanges =
                    setOf(
                        VideoDynamicRange.SDR,
                        VideoDynamicRange.HDR10,
                        VideoDynamicRange.HDR10_PLUS,
                        VideoDynamicRange.HLG,
                        VideoDynamicRange.DOLBY_VISION,
                    ),
            )

        assertEquals(
            setOf(VideoDynamicRange.HDR10, VideoDynamicRange.HLG),
            fullySupportedRuntime().controlledDynamicRanges(display, disabledAfterRuntimeFailure = false),
        )
    }

    @Test
    fun `HDR confirmation uses a standard extended range canvas color space`() {
        assertEquals("display-p3", webGpuHdrCanvasColorSpaceFor(VideoDynamicRange.HDR10))
        assertEquals("display-p3", webGpuHdrCanvasColorSpaceFor(VideoDynamicRange.HLG))
        assertEquals(null, webGpuHdrCanvasColorSpaceFor(VideoDynamicRange.HDR10_PLUS))
        assertEquals(null, webGpuHdrCanvasColorSpaceFor(VideoDynamicRange.SDR))
    }

    @Test
    fun `a real configuration failure disables the optimistic HDR route`() {
        val display =
            DisplayColorCapabilities(
                isKnown = true,
                supportedDynamicRanges = setOf(VideoDynamicRange.SDR, VideoDynamicRange.HDR10),
            )

        val capabilities =
            queryWebProjectionRendererColorCapabilities(
                display = display,
                runtimeSupport = fullySupportedRuntime(),
                hdrDisabledAfterRuntimeFailure = true,
            )

        assertTrue(capabilities.controlledHdrDynamicRanges.isEmpty())
        assertFalse(capabilities.supportsHdrProjection)
        assertTrue(capabilities.supportsToneMappingToSdr)
    }

    @Test
    fun `an SDR display never gets an advertised controlled HDR path`() {
        val capabilities =
            queryWebProjectionRendererColorCapabilities(
                display =
                    DisplayColorCapabilities(
                        isKnown = true,
                        supportedDynamicRanges = setOf(VideoDynamicRange.SDR),
                    ),
                runtimeSupport = fullySupportedRuntime(),
            )

        assertTrue(capabilities.controlledHdrDynamicRanges.isEmpty())
        assertFalse(capabilities.supportsHdrProjection)
    }

    @Test
    fun `force SDR uses the controlled canvas for flat HDR video`() {
        val hdrStatus =
            VideoColorPipelineStatus(
                requestedDynamicRangePolicy = DynamicRangePolicy.FORCE_SDR,
                source = VideoColorInfo(dynamicRange = VideoDynamicRange.HDR10),
            )

        assertTrue(shouldUseWebControlledColorRenderer(hdrStatus, usesProjectionRenderer = false))
        assertFalse(
            shouldUseWebControlledColorRenderer(
                hdrStatus.copy(requestedDynamicRangePolicy = DynamicRangePolicy.AUTO),
                usesProjectionRenderer = false,
            ),
        )
        assertFalse(
            shouldUseWebControlledColorRenderer(
                hdrStatus.copy(source = VideoColorInfo(dynamicRange = VideoDynamicRange.SDR)),
                usesProjectionRenderer = false,
            ),
        )
        assertTrue(
            shouldUseWebControlledColorRenderer(
                hdrStatus.copy(source = VideoColorInfo()),
                usesProjectionRenderer = false,
            ),
        )
    }

    @Test
    fun `controlled HDR to SDR requires WebGPU instead of an ambiguous WebGL video upload`() {
        val capabilities =
            queryWebProjectionRendererColorCapabilities(
                display = DisplayColorCapabilities(supportedDynamicRanges = setOf(VideoDynamicRange.SDR)),
                runtimeSupport = fullySupportedRuntime().copy(hasWebGpu = false),
            )

        assertFalse(capabilities.supportsToneMappingToSdr)
        assertEquals("display-p3", webGpuExternalTextureColorSpaceFor(outputHdr = true))
        assertEquals("srgb", webGpuExternalTextureColorSpaceFor(outputHdr = false))
        assertTrue(
            VideoColorInfo(
                dynamicRange = VideoDynamicRange.HDR10,
                transfer = VideoColorTransfer.PQ,
            ).hasWebGpuManagedHdrTransfer,
        )
        assertTrue(
            VideoColorInfo(
                dynamicRange = VideoDynamicRange.HLG,
                transfer = VideoColorTransfer.HLG,
            ).hasWebGpuManagedHdrTransfer,
        )
        assertFalse(VideoColorInfo(dynamicRange = VideoDynamicRange.SDR).hasWebGpuManagedHdrTransfer)
    }

    @Test
    fun `a successful WebGPU SDR fallback does not re-enable a failed HDR canvas`() {
        assertTrue(confirmsWebGpuHdrOutput(VideoDynamicRange.HDR10, VideoSurfaceKind.WEB_GPU_CANVAS))
        assertTrue(confirmsWebGpuHdrOutput(VideoDynamicRange.HLG, VideoSurfaceKind.WEB_GPU_CANVAS))
        assertFalse(confirmsWebGpuHdrOutput(VideoDynamicRange.DOLBY_VISION, VideoSurfaceKind.WEB_GPU_CANVAS))
        assertFalse(confirmsWebGpuHdrOutput(VideoDynamicRange.SDR, VideoSurfaceKind.WEB_GPU_CANVAS))
        assertFalse(confirmsWebGpuHdrOutput(VideoDynamicRange.HDR10, VideoSurfaceKind.WEB_GL_CANVAS))
    }

    @Test
    fun `WebGPU gamut LUT is encoded as IEEE 754 half precision instead of eight bit color`() {
        assertEquals(0x0000u.toUShort(), webFloatToHalfBits(0.0f))
        assertEquals(0x3800u.toUShort(), webFloatToHalfBits(0.5f))
        assertEquals(0x3c00u.toUShort(), webFloatToHalfBits(1.0f))
        assertEquals(0x7c00u.toUShort(), webFloatToHalfBits(Float.POSITIVE_INFINITY))
    }

    @Test
    fun `projection always uses the controlled canvas`() {
        assertTrue(
            shouldUseWebControlledColorRenderer(
                status = VideoColorPipelineStatus(),
                usesProjectionRenderer = true,
            ),
        )
    }

    private fun fullySupportedRuntime(): WebHdrCanvasRuntimeSupport =
        WebHdrCanvasRuntimeSupport(
            hasWebGpu = true,
            hasCanvasConfigurationReadback = true,
            displayReportsHighDynamicRange = true,
        )
}
