package io.github.kdroidfilter.composemediaplayer.mpv

import kotlin.test.Test
import kotlin.test.assertEquals

class MpvMacDecodeRouteTest {
    @Test
    fun `high-throughput SDR HEVC uses measured software route for an external runtime`() {
        assertEquals(
            MpvMacDecodeRoute.SOFTWARE_HIGH_THROUGHPUT_HEVC,
            selectMpvMacDecodeRoute(
                videoFormat = "hevc",
                videoCodec = "H.265 / HEVC (High Efficiency Video Coding)",
                width = 8192,
                height = 4096,
                frameRate = 59.94f,
                transfer = "bt.1886",
                pipelinedVideoToolboxAvailable = false,
            ),
        )
    }

    @Test
    fun `bundled runtime leaves high-throughput HEVC on pipelined VideoToolbox selection`() {
        assertEquals(
            MpvMacDecodeRoute.HARDWARE_AUTO,
            selectMpvMacDecodeRoute(
                videoFormat = "hevc",
                videoCodec = "H.265 / HEVC (High Efficiency Video Coding)",
                width = 8192,
                height = 4096,
                frameRate = 59.94f,
                transfer = "bt.1886",
                pipelinedVideoToolboxAvailable = true,
            ),
        )
    }

    @Test
    fun `ordinary HEVC remains on safe hardware decoding`() {
        assertEquals(
            MpvMacDecodeRoute.HARDWARE_AUTO,
            selectMpvMacDecodeRoute(
                videoFormat = "hevc",
                videoCodec = "HEVC",
                width = 3840,
                height = 2160,
                frameRate = 60f,
                transfer = "bt.1886",
                pipelinedVideoToolboxAvailable = false,
            ),
        )
        assertEquals(
            MpvMacDecodeRoute.HARDWARE_AUTO,
            selectMpvMacDecodeRoute(
                videoFormat = "hevc",
                videoCodec = "HEVC",
                width = 8192,
                height = 4096,
                frameRate = 30f,
                transfer = "bt.1886",
                pipelinedVideoToolboxAvailable = false,
            ),
        )
    }

    @Test
    fun `HDR HEVC remains on VideoToolbox path`() {
        assertEquals(
            MpvMacDecodeRoute.HARDWARE_AUTO,
            selectMpvMacDecodeRoute(
                videoFormat = "hevc",
                videoCodec = "HEVC Main 10",
                width = 8192,
                height = 4096,
                frameRate = 60f,
                transfer = "pq",
                pipelinedVideoToolboxAvailable = false,
            ),
        )
        assertEquals(
            MpvMacDecodeRoute.HARDWARE_AUTO,
            selectMpvMacDecodeRoute(
                videoFormat = "hevc",
                videoCodec = "HEVC Main 10",
                width = 8192,
                height = 4096,
                frameRate = 60f,
                transfer = "hlg",
                pipelinedVideoToolboxAvailable = false,
            ),
        )
    }

    @Test
    fun `non-HEVC and incomplete metadata remain on hardware auto`() {
        assertEquals(
            MpvMacDecodeRoute.HARDWARE_AUTO,
            selectMpvMacDecodeRoute(
                videoFormat = "h264",
                videoCodec = "H.264 / AVC",
                width = 8192,
                height = 4096,
                frameRate = 60f,
                transfer = "bt.1886",
                pipelinedVideoToolboxAvailable = false,
            ),
        )
        assertEquals(
            MpvMacDecodeRoute.HARDWARE_AUTO,
            selectMpvMacDecodeRoute(
                videoFormat = "hevc",
                videoCodec = "HEVC",
                width = null,
                height = 4096,
                frameRate = 60f,
                transfer = "bt.1886",
                pipelinedVideoToolboxAvailable = false,
            ),
        )
    }
}
