package io.github.kdroidfilter.composemediaplayer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class JvmDecodedVideoColorSignalTest {
    @Test
    fun `codec rejects incomplete snapshots and maps the stable native wire values`() {
        assertNull(JvmDecodedVideoColorSignalCodec.decode(null))
        assertNull(JvmDecodedVideoColorSignalCodec.decode(intArrayOf(0, 10, 4, 4, 4, 1, 0)))

        assertEquals(
            JvmDecodedVideoColorSignal(
                generation = 7,
                bitDepth = 10,
                primaries = VideoColorPrimaries.BT2020,
                transfer = VideoColorTransfer.PQ,
                matrix = VideoColorMatrix.BT2020_NCL,
                range = VideoColorRange.LIMITED,
            ),
            JvmDecodedVideoColorSignalCodec.decode(intArrayOf(7, 10, 4, 4, 4, 1, 0)),
        )
    }

    @Test
    fun `decoded PQ preserves richer HDR10 plus metadata from the selected source`() {
        val source =
            VideoColorInfo(
                dynamicRange = VideoDynamicRange.HDR10_PLUS,
                bitDepth = 10,
                primaries = VideoColorPrimaries.BT2020,
                transfer = VideoColorTransfer.PQ,
                matrix = VideoColorMatrix.BT2020_NCL,
                range = VideoColorRange.LIMITED,
                contentLightLevel = ContentLightLevelMetadata(4_000, 1_000),
                hdr10Plus = Hdr10PlusInfo(),
            )

        val merged =
            JvmDecodedVideoColorSignal(
                generation = 2,
                bitDepth = 10,
                primaries = VideoColorPrimaries.BT2020,
                transfer = VideoColorTransfer.PQ,
                matrix = VideoColorMatrix.BT2020_NCL,
                range = VideoColorRange.LIMITED,
            ).mergeInto(source)

        assertEquals(VideoDynamicRange.HDR10_PLUS, merged.dynamicRange)
        assertEquals(source.hdr10Plus, merged.hdr10Plus)
        assertEquals(source.contentLightLevel, merged.contentLightLevel)
    }

    @Test
    fun `adaptive HDR to SDR transition removes stale wide gamut and HDR metadata`() {
        val source =
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

        val merged =
            JvmDecodedVideoColorSignal(
                generation = 3,
                bitDepth = 8,
                primaries = VideoColorPrimaries.BT709,
                transfer = VideoColorTransfer.SDR,
                matrix = VideoColorMatrix.BT709,
                range = VideoColorRange.LIMITED,
            ).mergeInto(source)

        assertEquals(VideoDynamicRange.SDR, merged.dynamicRange)
        assertEquals(8, merged.bitDepth)
        assertEquals(VideoColorPrimaries.BT709, merged.primaries)
        assertNull(merged.masteringDisplay)
        assertNull(merged.hdr10Plus)
        assertNull(merged.dolbyVision)
    }

    @Test
    fun `unknown decoded fields do not leak prior fields across a transfer change`() {
        val source =
            VideoColorInfo(
                dynamicRange = VideoDynamicRange.HLG,
                bitDepth = 10,
                primaries = VideoColorPrimaries.BT2020,
                transfer = VideoColorTransfer.HLG,
                matrix = VideoColorMatrix.BT2020_NCL,
                range = VideoColorRange.LIMITED,
            )

        val merged =
            JvmDecodedVideoColorSignal(
                generation = 9,
                transfer = VideoColorTransfer.SDR,
            ).mergeInto(source)

        assertEquals(VideoDynamicRange.SDR, merged.dynamicRange)
        assertNull(merged.bitDepth)
        assertEquals(VideoColorPrimaries.UNKNOWN, merged.primaries)
        assertEquals(VideoColorMatrix.UNKNOWN, merged.matrix)
        assertEquals(VideoColorRange.UNKNOWN, merged.range)
    }

    @Test
    fun `authoritative loss of transfer makes the adaptive source unknown`() {
        val source =
            VideoColorInfo(
                dynamicRange = VideoDynamicRange.HDR10,
                bitDepth = 10,
                primaries = VideoColorPrimaries.BT2020,
                transfer = VideoColorTransfer.PQ,
                matrix = VideoColorMatrix.BT2020_NCL,
                range = VideoColorRange.LIMITED,
                contentLightLevel = ContentLightLevelMetadata(1_000, 400),
            )

        val merged =
            JvmDecodedVideoColorSignal(
                generation = 10,
                bitDepth = 10,
                authoritativeUnknowns = true,
            ).mergeInto(source)

        assertEquals(VideoDynamicRange.UNKNOWN, merged.dynamicRange)
        assertEquals(VideoColorPrimaries.UNKNOWN, merged.primaries)
        assertEquals(VideoColorTransfer.UNKNOWN, merged.transfer)
        assertNull(merged.contentLightLevel)
    }
}
