package io.github.kdroidfilter.composemediaplayer

import kotlin.test.Test
import kotlin.test.assertEquals

class JvmVideoColorPipelineSupportTest {
    @Test
    fun `confirmed decoder capabilities include the exact Dolby Vision compatibility base`() {
        val hlgCompatible =
            VideoColorInfo(
                dynamicRange = VideoDynamicRange.DOLBY_VISION,
                bitDepth = 10,
                dolbyVision =
                    DolbyVisionInfo(
                        profile = 8,
                        hasRpu = true,
                        hasHlgCompatibleBaseLayer = true,
                    ),
            )

        assertEquals(
            setOf(VideoDynamicRange.DOLBY_VISION, VideoDynamicRange.HLG),
            hlgCompatible.toConfirmedDecoderCapabilities().supportedDynamicRanges,
        )
    }
}
