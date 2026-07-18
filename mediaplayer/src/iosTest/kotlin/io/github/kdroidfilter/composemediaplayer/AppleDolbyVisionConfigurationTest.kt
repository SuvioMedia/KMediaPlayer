package io.github.kdroidfilter.composemediaplayer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AppleDolbyVisionConfigurationTest {
    @Test
    fun `dvcC bit fields expose profile level RPU and base layer`() {
        val profile = 7
        val level = 9
        val profileAndLevelHighBit = (profile shl 1) or (level ushr 5)
        val levelAndFlags = ((level and 0x1f) shl 3) or RPU_PRESENT or EL_PRESENT or BL_PRESENT

        val parsed =
            byteArrayOf(1, 0, profileAndLevelHighBit.toByte(), levelAndFlags.toByte())
                .toAppleDolbyVisionInfo()

        requireNotNull(parsed)
        assertEquals(profile, parsed.profile)
        assertEquals(level, parsed.level)
        assertTrue(parsed.hasRpu == true)
        assertEquals(DolbyVisionEnhancementLayer.UNKNOWN, parsed.enhancementLayer)
        assertTrue(parsed.hasHdr10CompatibleBaseLayer)
    }

    @Test
    fun `configuration without EL or base layer reports neither`() {
        val parsed = byteArrayOf(1, 0, (8 shl 1).toByte(), RPU_PRESENT.toByte()).toAppleDolbyVisionInfo()

        requireNotNull(parsed)
        assertEquals(8, parsed.profile)
        assertTrue(parsed.hasRpu == true)
        assertEquals(DolbyVisionEnhancementLayer.NONE, parsed.enhancementLayer)
        assertFalse(parsed.hasHdr10CompatibleBaseLayer)
        assertNull(byteArrayOf(1, 0, 0, 0).toAppleDolbyVisionInfo())
    }

    @Test
    fun `Dolby Vision display support requires both EDR and an explicit AVPlayer mode`() {
        val supported =
            appleDisplayColorCapabilities(
                hasEdrHeadroom = true,
                eligibleForHdrPlayback = true,
                supportsHdr10 = true,
                supportsHlg = true,
                supportsDolbyVision = true,
            )
        assertTrue(supported.supports(VideoDynamicRange.DOLBY_VISION))

        val noEdr =
            appleDisplayColorCapabilities(
                hasEdrHeadroom = false,
                eligibleForHdrPlayback = true,
                supportsHdr10 = true,
                supportsHlg = true,
                supportsDolbyVision = true,
            )
        assertFalse(noEdr.supports(VideoDynamicRange.DOLBY_VISION))

        val noDolbyVisionMode =
            appleDisplayColorCapabilities(
                hasEdrHeadroom = true,
                eligibleForHdrPlayback = true,
                supportsHdr10 = true,
                supportsHlg = true,
                supportsDolbyVision = false,
            )
        assertFalse(noDolbyVisionMode.supports(VideoDynamicRange.DOLBY_VISION))

        val ineligiblePlayer =
            appleDisplayColorCapabilities(
                hasEdrHeadroom = true,
                eligibleForHdrPlayback = false,
                supportsHdr10 = true,
                supportsHlg = true,
                supportsDolbyVision = true,
            )
        assertFalse(ineligiblePlayer.supports(VideoDynamicRange.HDR10))
        assertFalse(ineligiblePlayer.supports(VideoDynamicRange.HLG))
        assertFalse(ineligiblePlayer.supports(VideoDynamicRange.DOLBY_VISION))
    }

    @Test
    fun `EDR and general eligibility never invent missing mode-specific support`() {
        val hlgOnly =
            appleDisplayColorCapabilities(
                hasEdrHeadroom = true,
                eligibleForHdrPlayback = true,
                supportsHdr10 = false,
                supportsHlg = true,
                supportsDolbyVision = false,
            )

        assertEquals(
            setOf(VideoDynamicRange.SDR, VideoDynamicRange.HLG),
            hlgOnly.supportedDynamicRanges,
        )
        assertFalse(hlgOnly.supports(VideoDynamicRange.HDR10))
        assertFalse(hlgOnly.supports(VideoDynamicRange.DOLBY_VISION))
    }

    private companion object {
        const val RPU_PRESENT = 0x04
        const val EL_PRESENT = 0x02
        const val BL_PRESENT = 0x01
    }
}
