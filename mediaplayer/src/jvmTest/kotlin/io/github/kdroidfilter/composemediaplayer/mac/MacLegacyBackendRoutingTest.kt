package io.github.kdroidfilter.composemediaplayer.mac

import io.github.kdroidfilter.composemediaplayer.DesktopVideoSurfaceMode
import io.github.kdroidfilter.composemediaplayer.RendererColorCapabilities
import io.github.kdroidfilter.composemediaplayer.VideoColorInfo
import io.github.kdroidfilter.composemediaplayer.VideoDynamicRange
import io.github.kdroidfilter.composemediaplayer.VideoProjectionSettings
import io.github.kdroidfilter.composemediaplayer.VideoProjectionType
import io.github.kdroidfilter.composemediaplayer.VideoTextureCrop
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MacLegacyBackendRoutingTest {
    @Test
    fun `AVFoundation uses native presentation except explicit Compose mini players`() {
        assertTrue(
            shouldUseMacNativeAvFoundationPresentation(
                surfaceMode = DesktopVideoSurfaceMode.PREFER_NATIVE,
            ),
        )
        assertFalse(
            shouldUseMacNativeAvFoundationPresentation(
                surfaceMode = DesktopVideoSurfaceMode.COMPOSE,
            ),
        )
    }

    @Test
    fun `FORCE SDR uses the same native CAMetalLayer route as HDR and bridged media`() {
        assertTrue(
            shouldUseMacNativeAvFoundationPresentation(
                surfaceMode = DesktopVideoSurfaceMode.PREFER_NATIVE,
            ),
        )
        assertTrue(
            shouldUseMacNativeAvFoundationPresentation(
                surfaceMode = DesktopVideoSurfaceMode.PREFER_NATIVE,
            ),
        )
    }

    @Test
    fun `flat and projected video advertise the same controlled FP16 HDR renderer`() {
        val base =
            RendererColorCapabilities(
                nativeSurfaceDynamicRanges = setOf(VideoDynamicRange.HDR10, VideoDynamicRange.DOLBY_VISION),
                controlledHdrDynamicRanges =
                    setOf(VideoDynamicRange.HDR10, VideoDynamicRange.HDR10_PLUS, VideoDynamicRange.HLG),
                supportsToneMappingToSdr = true,
                supportsNativeToneMappingToSdr = true,
                supportsHdrProjection = true,
                supportsHdr10PlusApplication = true,
                supportsDolbyVisionMetadata = true,
                supportsDolbyVisionToneMappingToSdr = true,
            )

        val active = macControlledMetalRendererCapabilities(base, rendererEnabled = true)

        assertEquals(emptySet(), active.nativeSurfaceDynamicRanges)
        assertEquals(base.controlledHdrDynamicRanges, active.controlledHdrDynamicRanges)
        assertTrue(active.supportsToneMappingToSdr)
        assertFalse(active.supportsNativeToneMappingToSdr)
        assertTrue(active.supportsHdrProjection)
        assertFalse(active.supportsDolbyVisionMetadata)
    }

    @Test
    fun `failed HDR range is removed without disabling SDR Metal output`() {
        val active =
            macControlledMetalRendererCapabilities(
                base =
                    RendererColorCapabilities(
                        controlledHdrDynamicRanges = setOf(VideoDynamicRange.HDR10, VideoDynamicRange.HLG),
                        supportsToneMappingToSdr = true,
                        supportsHdrProjection = true,
                    ),
                rendererEnabled = true,
                unavailableHdrRanges = setOf(VideoDynamicRange.HDR10),
            )

        assertEquals(setOf(VideoDynamicRange.HLG), active.controlledHdrDynamicRanges)
        assertTrue(active.supportsToneMappingToSdr)
        assertTrue(active.supportsHdrProjection)
    }

    @Test
    fun `legacy containers retain explicit decoder and compatibility backends`() {
        assertEquals("auto", resolveMacConfiguredFallbackBackend("auto", isLegacyContainer = true))
        listOf("ffmpeg", "kmediabridge", "bridge", "vlc").forEach { configured ->
            assertEquals(configured, resolveMacConfiguredFallbackBackend(configured, isLegacyContainer = true))
        }
        assertEquals(
            "libvlc-native-view",
            resolveMacConfiguredFallbackBackend("libvlc-native-view", isLegacyContainer = true),
        )
        assertEquals("platform", resolveMacConfiguredFallbackBackend("platform", isLegacyContainer = true))
        assertEquals("kmediabridge", resolveMacConfiguredFallbackBackend("kmediabridge", isLegacyContainer = false))
    }

    @Test
    fun `explicit libVLC is not silently replaced by AVFoundation`() {
        assertTrue(
            shouldUseMacLibVlcCandidate(
                sourceColorInfo = VideoColorInfo(),
                explicitlyRequested = true,
            ),
        )
        assertFalse(
            shouldUseMacLibVlcCandidate(
                sourceColorInfo = VideoColorInfo(),
                explicitlyRequested = false,
            ),
        )
        assertTrue(
            shouldUseMacLibVlcCandidate(
                sourceColorInfo = VideoColorInfo(dynamicRange = VideoDynamicRange.SDR, bitDepth = 8),
                explicitlyRequested = false,
            ),
        )
    }

    @Test
    fun `libVLC frame copy removes decoder alignment padding`() {
        assertEquals(180, visibleLibVlcFrameDimension(decodedDimension = 192, probedDimension = 180))
        assertEquals(192, visibleLibVlcFrameDimension(decodedDimension = 192, probedDimension = null))
        assertEquals(192, visibleLibVlcFrameDimension(decodedDimension = 192, probedDimension = 216))
    }

    @Test
    fun `libVLC keeps native view for flat media and uses controlled frames for fisheye`() {
        assertTrue(
            shouldUseMacLibVlcNativeVideoOutput(
                projection = VideoProjectionSettings(),
                textureCrop = VideoTextureCrop(),
            ),
        )
        assertFalse(
            shouldUseMacLibVlcNativeVideoOutput(
                projection = VideoProjectionSettings(projectionType = VideoProjectionType.Fisheye190),
                textureCrop = VideoTextureCrop(),
            ),
        )
    }

    @Test
    fun `projection texture retains media aspect inside every container`() {
        assertEquals(
            MacProjectionTextureViewport(width = 1600, height = 800),
            macProjectionTextureViewport(containerWidth = 1600, containerHeight = 1000, mediaAspectRatio = 2f),
        )
        assertEquals(
            MacProjectionTextureViewport(width = 1600, height = 800),
            macProjectionTextureViewport(containerWidth = 1800, containerHeight = 800, mediaAspectRatio = 2f),
        )
    }
}
