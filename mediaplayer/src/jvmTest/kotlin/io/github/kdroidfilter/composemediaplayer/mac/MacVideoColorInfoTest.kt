package io.github.kdroidfilter.composemediaplayer.mac

import io.github.kdroidfilter.composemediaplayer.DolbyVisionEnhancementLayer
import io.github.kdroidfilter.composemediaplayer.VideoColorMatrix
import io.github.kdroidfilter.composemediaplayer.VideoColorPrimaries
import io.github.kdroidfilter.composemediaplayer.VideoColorRange
import io.github.kdroidfilter.composemediaplayer.VideoColorTransfer
import io.github.kdroidfilter.composemediaplayer.VideoDynamicRange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MacVideoColorInfoTest {
    @Test
    fun `parses selected AVFoundation HDR10 track without inventing display facts`() {
        val info =
            (
                "dynamicRange=HDR10;bitDepth=10;primaries=BT2020;transfer=PQ;matrix=BT2020_NCL;range=LIMITED;" +
                    "masterRedX=0.68;masterRedY=0.32;masterGreenX=0.265;masterGreenY=0.69;" +
                    "masterBlueX=0.15;masterBlueY=0.06;masterWhiteX=0.3127;masterWhiteY=0.329;" +
                    "masterMinNits=0.005;masterMaxNits=1000;maxCll=1000;maxFall=400"
            ).toMacVideoColorInfo()

        assertEquals(VideoDynamicRange.HDR10, info.dynamicRange)
        assertEquals(10, info.bitDepth)
        assertEquals(VideoColorPrimaries.BT2020, info.primaries)
        assertEquals(VideoColorTransfer.PQ, info.transfer)
        assertEquals(VideoColorMatrix.BT2020_NCL, info.matrix)
        assertEquals(VideoColorRange.LIMITED, info.range)
        assertEquals(1_000f, info.masteringDisplay?.maxLuminanceNits)
        assertEquals(1_000, info.contentLightLevel?.maxContentLightLevelNits)
    }

    @Test
    fun `rejects AVFoundation total sample depth as component bit depth`() {
        val info =
            "dynamicRange=HDR10;bitDepth=24;primaries=BT2020;transfer=PQ;matrix=BT2020_NCL;range=LIMITED"
                .toMacVideoColorInfo()

        assertNull(info.bitDepth)
    }

    @Test
    fun `promotes PQ to HDR10 plus only after validated per-frame metadata observation`() {
        val observed =
            (
                "dynamicRange=HDR10_PLUS;bitDepth=10;primaries=BT2020;transfer=PQ;" +
                    "matrix=BT2020_NCL;range=LIMITED;hdr10PlusAppId=4;" +
                    "hdr10PlusAppVersion=1;hdr10PlusPerFrame=1"
            ).toMacVideoColorInfo()

        assertEquals(VideoDynamicRange.HDR10_PLUS, observed.dynamicRange)
        assertEquals(4, observed.hdr10Plus?.applicationIdentifier)
        assertEquals(1, observed.hdr10Plus?.applicationVersion)
        assertTrue(observed.hdr10Plus?.hasPerFrameMetadata == true)

        val unconfirmed =
            "dynamicRange=HDR10_PLUS;bitDepth=10;transfer=PQ;hdr10PlusAppId=4;hdr10PlusPerFrame=0"
                .toMacVideoColorInfo()
        assertEquals(VideoDynamicRange.HDR10, unconfirmed.dynamicRange)
        assertNull(unconfirmed.hdr10Plus)

        val wrongApplication =
            "dynamicRange=HDR10_PLUS;bitDepth=10;transfer=PQ;hdr10PlusAppId=7;hdr10PlusPerFrame=1"
                .toMacVideoColorInfo()
        assertEquals(VideoDynamicRange.HDR10, wrongApplication.dynamicRange)
        assertNull(wrongApplication.hdr10Plus)
    }

    @Test
    fun `parses Dolby Vision configuration flags separately from HEVC`() {
        val info =
            "dynamicRange=DOLBY_VISION;bitDepth=10;primaries=BT2020;transfer=PQ;matrix=BT2020_NCL;" +
                "range=LIMITED;dvProfile=7;dvLevel=6;dvHasRpu=1;dvHasEl=1;dvHasBase=1"

        val parsed = info.toMacVideoColorInfo()
        assertEquals(VideoDynamicRange.DOLBY_VISION, parsed.dynamicRange)
        val dolbyVision = assertNotNull(parsed.dolbyVision)
        assertEquals(7, dolbyVision.profile)
        assertEquals(6, dolbyVision.level)
        assertTrue(dolbyVision.hasRpu == true)
        assertEquals(DolbyVisionEnhancementLayer.UNKNOWN, dolbyVision.enhancementLayer)
        assertTrue(dolbyVision.hasHdr10CompatibleBaseLayer)

        val profile8WithoutCompatibilityId =
            "dynamicRange=DOLBY_VISION;dvProfile=8;dvHasRpu=1;dvHasBase=1".toMacVideoColorInfo()
        assertFalse(profile8WithoutCompatibilityId.dolbyVision?.hasHdr10CompatibleBaseLayer == true)
        val profile81 =
            "dynamicRange=DOLBY_VISION;dvProfile=8;dvHasRpu=1;dvHasBase=1;dvCompatibilityId=1"
                .toMacVideoColorInfo()
        assertTrue(profile81.dolbyVision?.hasHdr10CompatibleBaseLayer == true)
        val profile84 =
            "dynamicRange=DOLBY_VISION;dvProfile=8;dvHasRpu=1;dvHasBase=1;dvCompatibilityId=4"
                .toMacVideoColorInfo()
        assertFalse(profile84.dolbyVision?.hasHdr10CompatibleBaseLayer == true)

        assertEquals(VideoDynamicRange.UNKNOWN, "codec=hvc1".toMacVideoColorInfo().dynamicRange)
        assertNull("codec=hvc1".toMacVideoColorInfo().dolbyVision)
    }

    @Test
    fun `display parser requires current EDR AVPlayer eligibility and explicit DV decode support`() {
        assertFalse(null.toMacDisplayColorCapabilities().isKnown)
        assertFalse("known=1;native=0;eligible=1".toMacDisplayColorCapabilities().supportsHdr)

        val hdr =
            "known=1;native=1;eligible=1;potentialEdr=4;hdr10=SUPPORTED;hlg=SUPPORTED;dolbyVision=SUPPORTED"
                .toMacDisplayColorCapabilities()
        assertTrue(hdr.isKnown)
        assertEquals(
            setOf(
                VideoDynamicRange.SDR,
                VideoDynamicRange.HDR10,
                VideoDynamicRange.HLG,
                VideoDynamicRange.DOLBY_VISION,
            ),
            hdr.supportedDynamicRanges,
        )
        assertNull(hdr.maxLuminanceNits)

        val noDolbyVisionDecoder =
            "known=1;native=1;eligible=1;potentialEdr=4;hdr10=SUPPORTED;hlg=SUPPORTED;dolbyVision=UNSUPPORTED"
                .toMacDisplayColorCapabilities()
        assertFalse(noDolbyVisionDecoder.supports(VideoDynamicRange.DOLBY_VISION))
    }
}
