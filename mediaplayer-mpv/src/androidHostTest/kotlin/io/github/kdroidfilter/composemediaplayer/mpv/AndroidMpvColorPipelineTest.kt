package io.github.kdroidfilter.composemediaplayer.mpv

import io.github.kdroidfilter.composemediaplayer.VideoColorMatrix
import io.github.kdroidfilter.composemediaplayer.VideoColorPrimaries
import io.github.kdroidfilter.composemediaplayer.VideoColorRange
import io.github.kdroidfilter.composemediaplayer.VideoColorTransfer
import io.github.kdroidfilter.composemediaplayer.VideoDynamicRange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class AndroidMpvColorPipelineTest {
    @Test
    fun mapsHdr10SourceMetadata() {
        val info =
            mpvVideoColorInfo(
                pixelFormat = "p010le",
                primaries = "bt.2020",
                transfer = "pq",
                matrix = "bt.2020-ncl",
                range = "limited",
                maxCll = 1_000.0,
                maxFall = 400.0,
                hasHdr10PlusSceneMetadata = false,
                minimumLuminanceNits = 0.005,
                maximumLuminanceNits = 1_000.0,
            )

        assertEquals(VideoDynamicRange.HDR10, info.dynamicRange)
        assertEquals(10, info.bitDepth)
        assertEquals(VideoColorPrimaries.BT2020, info.primaries)
        assertEquals(VideoColorTransfer.PQ, info.transfer)
        assertEquals(VideoColorMatrix.BT2020_NCL, info.matrix)
        assertEquals(VideoColorRange.LIMITED, info.range)
        assertEquals(1_000, assertNotNull(info.contentLightLevel).maxContentLightLevelNits)
        assertEquals(0.708f, assertNotNull(info.masteringDisplay).redX)
        assertEquals(1_000f, info.masteringDisplay?.maxLuminanceNits)
    }

    @Test
    fun distinguishesHlgHdr10PlusDolbyVisionAndSdr() {
        assertEquals(
            VideoDynamicRange.HLG,
            signal(transfer = "hlg").dynamicRange,
        )
        assertEquals(
            VideoDynamicRange.HDR10_PLUS,
            signal(transfer = "pq", hasHdr10Plus = true).dynamicRange,
        )
        assertEquals(
            VideoDynamicRange.DOLBY_VISION,
            signal(transfer = "pq", matrix = "dolbyvision").dynamicRange,
        )
        assertEquals(
            VideoDynamicRange.SDR,
            signal(transfer = "bt.1886", primaries = "bt.709", matrix = "bt.709").dynamicRange,
        )
    }

    @Test
    fun reportsOnlyRecognizedPixelDepths() {
        assertEquals(10, mpvPixelFormatBitDepth("yuv420p10le"))
        assertEquals(12, mpvPixelFormatBitDepth("gbrp12le"))
        assertEquals(8, mpvPixelFormatBitDepth("nv12"))
        assertEquals(null, mpvPixelFormatBitDepth("mediacodec"))
    }

    @Test
    fun doesNotMislabelDciP3AsDisplayP3() {
        val info = signal(transfer = "bt.1886", primaries = "dci-p3", matrix = "rgb")

        assertEquals(VideoColorPrimaries.UNKNOWN, info.primaries)
    }

    private fun signal(
        transfer: String,
        primaries: String = "bt.2020",
        matrix: String = "bt.2020-ncl",
        hasHdr10Plus: Boolean = false,
    ) = mpvVideoColorInfo(
        pixelFormat = "p010",
        primaries = primaries,
        transfer = transfer,
        matrix = matrix,
        range = "limited",
        maxCll = Double.NaN,
        maxFall = Double.NaN,
        hasHdr10PlusSceneMetadata = hasHdr10Plus,
    )
}
