package io.github.kdroidfilter.composemediaplayer.mac

import io.github.kdroidfilter.composemediaplayer.DesktopVideoSurfaceMode
import io.github.kdroidfilter.composemediaplayer.DynamicRangePolicy
import io.github.kdroidfilter.composemediaplayer.VideoColorInfo
import io.github.kdroidfilter.composemediaplayer.VideoDynamicRange
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
                dynamicRangePolicy = DynamicRangePolicy.AUTO,
                usesMetalProjection = false,
                sourceAlreadyConvertedForAvFoundation = false,
            ),
        )
        assertFalse(
            shouldUseMacNativeAvFoundationPresentation(
                surfaceMode = DesktopVideoSurfaceMode.COMPOSE,
                dynamicRangePolicy = DynamicRangePolicy.AUTO,
                usesMetalProjection = true,
                sourceAlreadyConvertedForAvFoundation = true,
            ),
        )
    }

    @Test
    fun `FORCE SDR stays native after an HLS source bridge already converted the video`() {
        assertFalse(
            shouldUseMacNativeAvFoundationPresentation(
                surfaceMode = DesktopVideoSurfaceMode.PREFER_NATIVE,
                dynamicRangePolicy = DynamicRangePolicy.FORCE_SDR,
                usesMetalProjection = false,
                sourceAlreadyConvertedForAvFoundation = false,
            ),
        )
        assertTrue(
            shouldUseMacNativeAvFoundationPresentation(
                surfaceMode = DesktopVideoSurfaceMode.PREFER_NATIVE,
                dynamicRangePolicy = DynamicRangePolicy.FORCE_SDR,
                usesMetalProjection = false,
                sourceAlreadyConvertedForAvFoundation = true,
            ),
        )
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
}
