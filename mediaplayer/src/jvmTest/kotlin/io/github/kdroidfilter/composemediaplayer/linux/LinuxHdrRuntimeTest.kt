package io.github.kdroidfilter.composemediaplayer.linux

import io.github.kdroidfilter.composemediaplayer.DolbyVisionInfo
import io.github.kdroidfilter.composemediaplayer.DolbyVisionPolicy
import io.github.kdroidfilter.composemediaplayer.DynamicRangePolicy
import io.github.kdroidfilter.composemediaplayer.VideoColorInfo
import io.github.kdroidfilter.composemediaplayer.VideoDynamicRange
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LinuxHdrRuntimeTest {
    @Test
    fun `all Linux HDR prerequisites are required`() {
        val ready = LinuxHdrRuntimeEvaluator.evaluate(completeFacts())
        val missing =
            LinuxHdrRuntimeEvaluator.evaluate(
                completeFacts().copy(
                    gstreamerVersion = "1.28.4",
                    hasWaylandSink = false,
                    hasColorManagementProtocol = false,
                    nativeWaylandAdapterAvailable = false,
                ),
            )

        assertTrue(ready.isReady)
        assertFalse(missing.isReady)
        assertTrue(missing.detail.orEmpty().contains("GStreamer 1.28.5+"))
        assertTrue(missing.detail.orEmpty().contains("color-management-v1"))
        assertTrue(missing.detail.orEmpty().contains("waylandsink"))
        assertTrue(missing.detail.orEmpty().contains("surface adapter"))
    }

    @Test
    fun `runtime version comparison handles build suffixes`() {
        assertTrue(LinuxHdrRuntimeEvaluator.isAtLeast("25.0.3+9-b1234.1", "25.0.3"))
        assertTrue(LinuxHdrRuntimeEvaluator.isAtLeast("1.29.0", "1.28.5"))
        assertFalse(LinuxHdrRuntimeEvaluator.isAtLeast("25.0.2", "25.0.3"))
    }

    @Test
    fun `HDR10 plus frame metadata requires GStreamer 1_30 without raising the static HDR minimum`() {
        val staticHdr = LinuxHdrRuntimeEvaluator.evaluate(completeFacts())
        val dynamicHdr =
            LinuxHdrRuntimeEvaluator.evaluate(
                completeFacts().copy(gstreamerVersion = "1.30.0"),
            )

        assertTrue(staticHdr.isVulkanProjectionReady)
        assertFalse(staticHdr.supportsHdr10PlusMetadata)
        assertTrue(dynamicHdr.supportsHdr10PlusMetadata)
    }

    @Test
    fun `an SDR output keeps the color-managed fallback surface available`() {
        val status = LinuxHdrRuntimeEvaluator.evaluate(completeFacts().copy(hasHdrEnabledOutput = false))

        assertFalse(status.isReady)
        assertTrue(status.isColorManagedSurfaceReady)
        assertTrue(status.detail.orEmpty().contains("not operating in an HDR mode"))
    }

    @Test
    fun `native Vulkan capability flags are decoded without vulkaninfo`() {
        val all = LinuxVulkanCapabilitiesDecoder.decode((1 shl 7) - 1)
        val unavailable = LinuxVulkanCapabilitiesDecoder.decode(0)

        assertTrue(all.isAvailable)
        assertTrue(all.hasWaylandSurface)
        assertTrue(all.hasExternalMemoryDmaBuf)
        assertTrue(all.hasDrmFormatModifier)
        assertTrue(all.hasExternalMemoryFd)
        assertTrue(all.hasShaderFloat16)
        assertTrue(all.hasSamplerYcbcrConversion)
        assertFalse(unavailable.isAvailable)
    }

    @Test
    fun `native GStreamer runtime info is decoded without gst-inspect`() {
        val capabilities =
            LinuxGStreamerCapabilitiesDecoder.decode(
                intArrayOf(1, 28, 5, 0, (1 shl 5) - 1),
            )

        assertTrue(capabilities.version == "1.28.5")
        assertTrue(capabilities.hasWaylandSink)
        assertTrue(capabilities.hasVulkanUpload)
        assertTrue(capabilities.hasVulkanColorConvert)
        assertTrue(capabilities.hasVulkanShaderSpv)
        assertTrue(capabilities.hasVulkanOverlayCompositor)
    }

    @Test
    fun `flat HDR surface does not depend on Vulkan projection plugins`() {
        val status =
            LinuxHdrRuntimeEvaluator.evaluate(
                completeFacts().copy(
                    hasVulkan = false,
                    hasVulkanUpload = false,
                    hasVulkanColorConvert = false,
                    hasVulkanShaderSpv = false,
                    hasVulkanOverlayCompositor = false,
                ),
            )

        assertTrue(status.isColorManagedSurfaceReady)
        assertFalse(status.isVulkanProjectionReady)
        assertFalse(status.isReady)
    }

    @Test
    fun `optional Vulkan renderer is required only for projection`() {
        val status =
            LinuxHdrRuntimeEvaluator.evaluate(
                completeFacts().copy(nativeVulkanProjectionRendererAvailable = false),
            )

        assertTrue(status.isColorManagedSurfaceReady)
        assertFalse(status.isVulkanProjectionReady)
        assertTrue(status.projectionDetail.orEmpty().contains("KMediaPlayer Vulkan projection renderer"))
    }

    @Test
    fun `custom projection renderer does not require GStreamer Vulkan filters`() {
        val status =
            LinuxHdrRuntimeEvaluator.evaluate(
                completeFacts().copy(
                    hasVulkanUpload = false,
                    hasVulkanColorConvert = false,
                    hasVulkanShaderSpv = false,
                    hasVulkanOverlayCompositor = false,
                ),
            )

        assertTrue(status.isVulkanProjectionReady)
    }

    @Test
    fun `FORCE SDR never opens a color managed HDR surface`() {
        val source = VideoColorInfo(dynamicRange = VideoDynamicRange.HDR10)

        assertFalse(
            source.isEligibleForLinuxWaylandColorSurface(
                dynamicRangePolicy = DynamicRangePolicy.FORCE_SDR,
                dolbyVisionPolicy = DolbyVisionPolicy.AUTO,
            ),
        )
        assertTrue(
            source.isEligibleForLinuxWaylandColorSurface(
                dynamicRangePolicy = DynamicRangePolicy.AUTO,
                dolbyVisionPolicy = DolbyVisionPolicy.AUTO,
            ),
        )
    }

    @Test
    fun `Dolby Vision Wayland route requires a safe base layer and non-native policy`() {
        val source =
            VideoColorInfo(
                dynamicRange = VideoDynamicRange.DOLBY_VISION,
                dolbyVision = DolbyVisionInfo(hasHdr10CompatibleBaseLayer = true),
            )

        assertTrue(
            source.isEligibleForLinuxWaylandColorSurface(
                dynamicRangePolicy = DynamicRangePolicy.AUTO,
                dolbyVisionPolicy = DolbyVisionPolicy.AUTO,
            ),
        )
        assertFalse(
            source.isEligibleForLinuxWaylandColorSurface(
                dynamicRangePolicy = DynamicRangePolicy.AUTO,
                dolbyVisionPolicy = DolbyVisionPolicy.REQUIRE_NATIVE,
            ),
        )
        assertFalse(
            source
                .copy(dolbyVision = DolbyVisionInfo(hasHdr10CompatibleBaseLayer = false))
                .isEligibleForLinuxWaylandColorSurface(
                    dynamicRangePolicy = DynamicRangePolicy.AUTO,
                    dolbyVisionPolicy = DolbyVisionPolicy.AUTO,
                ),
        )
    }

    private fun completeFacts() =
        LinuxHdrRuntimeFacts(
            waylandSession = true,
            gstreamerVersion = "1.28.5",
            hasWaylandSink = true,
            hasVulkanUpload = true,
            hasVulkanColorConvert = true,
            hasVulkanShaderSpv = true,
            hasVulkanOverlayCompositor = true,
            hasColorManagementProtocol = true,
            hasParametricColorDescription = true,
            hasBt2020Primaries = true,
            hasPqTransfer = true,
            hasHlgTransfer = true,
            hasHdrEnabledOutput = true,
            hasVulkan = true,
            hasVulkanWaylandSurface = true,
            hasExternalMemoryDmaBuf = true,
            hasDrmFormatModifier = true,
            hasExternalMemoryFd = true,
            hasShaderFloat16 = true,
            hasSamplerYcbcrConversion = true,
            nativeWaylandAdapterAvailable = true,
            nativeVulkanProjectionRendererAvailable = true,
        )
}
