package io.github.kdroidfilter.composemediaplayer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class JvmVideoColorProbeTest {
    @Test
    fun `first decoded HEVC frame recovers HDR tags omitted by the Matroska container`() {
        val colorInfo =
            JvmLibVlcMediaProbe.parseFirstVideoFrameProbeOutput(
                """
                frames.frame.0.pix_fmt="yuv420p10le"
                frames.frame.0.color_range="tv"
                frames.frame.0.color_space="bt2020nc"
                frames.frame.0.color_primaries="bt2020"
                frames.frame.0.color_transfer="smpte2084"
                frames.frame.0.side_data_list.side_data.0.side_data_type="Mastering display metadata"
                frames.frame.0.side_data_list.side_data.0.red_x="34000/50000"
                frames.frame.0.side_data_list.side_data.0.red_y="16000/50000"
                frames.frame.0.side_data_list.side_data.0.green_x="13250/50000"
                frames.frame.0.side_data_list.side_data.0.green_y="34500/50000"
                frames.frame.0.side_data_list.side_data.0.blue_x="7500/50000"
                frames.frame.0.side_data_list.side_data.0.blue_y="3000/50000"
                frames.frame.0.side_data_list.side_data.0.white_point_x="15635/50000"
                frames.frame.0.side_data_list.side_data.0.white_point_y="16450/50000"
                frames.frame.0.side_data_list.side_data.0.min_luminance="50/10000"
                frames.frame.0.side_data_list.side_data.0.max_luminance="10000000/10000"
                frames.frame.0.side_data_list.side_data.1.side_data_type="Content light level metadata"
                frames.frame.0.side_data_list.side_data.1.max_content=1000
                frames.frame.0.side_data_list.side_data.1.max_average=400
                """.trimIndent(),
            )

        assertNotNull(colorInfo)
        assertEquals(VideoDynamicRange.HDR10, colorInfo.dynamicRange)
        assertEquals(10, colorInfo.bitDepth)
        assertEquals(VideoColorPrimaries.BT2020, colorInfo.primaries)
        assertEquals(VideoColorTransfer.PQ, colorInfo.transfer)
        assertEquals(VideoColorMatrix.BT2020_NCL, colorInfo.matrix)
        assertEquals(1_000f, colorInfo.masteringDisplay?.maxLuminanceNits)
        assertEquals(400, colorInfo.contentLightLevel?.maxFrameAverageLightLevelNits)
    }

    @Test
    fun `ffprobe fields produce typed HDR10 plus color info`() {
        val result =
            JvmLibVlcMediaProbe.parseProbeOutput(
                """
                streams.stream.0.index=0
                streams.stream.0.codec_type="video"
                streams.stream.0.codec_name="hevc"
                streams.stream.0.codec_tag_string="hvc1"
                streams.stream.0.pix_fmt="yuv420p10le"
                streams.stream.0.color_range="tv"
                streams.stream.0.color_space="bt2020nc"
                streams.stream.0.color_transfer="smpte2084"
                streams.stream.0.color_primaries="bt2020"
                streams.stream.0.side_data_list.side_data.0.side_data_type="Mastering display metadata"
                streams.stream.0.side_data_list.side_data.0.red_x="34000/50000"
                streams.stream.0.side_data_list.side_data.0.red_y="16000/50000"
                streams.stream.0.side_data_list.side_data.0.green_x="13250/50000"
                streams.stream.0.side_data_list.side_data.0.green_y="34500/50000"
                streams.stream.0.side_data_list.side_data.0.blue_x="7500/50000"
                streams.stream.0.side_data_list.side_data.0.blue_y="3000/50000"
                streams.stream.0.side_data_list.side_data.0.white_point_x="15635/50000"
                streams.stream.0.side_data_list.side_data.0.white_point_y="16450/50000"
                streams.stream.0.side_data_list.side_data.0.min_luminance="50/10000"
                streams.stream.0.side_data_list.side_data.0.max_luminance="10000000/10000"
                streams.stream.0.side_data_list.side_data.1.side_data_type="Content light level metadata"
                streams.stream.0.side_data_list.side_data.1.max_content=1000
                streams.stream.0.side_data_list.side_data.1.max_average=400
                streams.stream.0.side_data_list.side_data.2.side_data_type="HDR Dynamic Metadata SMPTE2094-40 (HDR10+)"
                format.duration="12.5"
                """.trimIndent(),
            )

        assertEquals("hevc", result.videoCodecName)
        assertEquals(VideoDynamicRange.HDR10_PLUS, result.videoColorInfo.dynamicRange)
        assertEquals(10, result.videoColorInfo.bitDepth)
        assertEquals(VideoColorPrimaries.BT2020, result.videoColorInfo.primaries)
        assertEquals(VideoColorTransfer.PQ, result.videoColorInfo.transfer)
        assertEquals(VideoColorMatrix.BT2020_NCL, result.videoColorInfo.matrix)
        assertEquals(VideoColorRange.LIMITED, result.videoColorInfo.range)
        assertEquals(1_000f, result.videoColorInfo.masteringDisplay?.maxLuminanceNits)
        assertEquals(1_000, result.videoColorInfo.contentLightLevel?.maxContentLightLevelNits)
        assertTrue(result.videoColorInfo.hdr10Plus?.hasPerFrameMetadata == true)
    }

    @Test
    fun `Dolby Vision probe does not infer support from HEVC alone`() {
        val plainHevc =
            JvmLibVlcMediaProbe.parseProbeOutput(
                """
                streams.stream.0.index=0
                streams.stream.0.codec_type="video"
                streams.stream.0.codec_name="hevc"
                streams.stream.0.codec_tag_string="hvc1"
                """.trimIndent(),
            )
        val dolbyVision =
            JvmLibVlcMediaProbe.parseProbeOutput(
                """
                streams.stream.0.index=0
                streams.stream.0.codec_type="video"
                streams.stream.0.codec_name="hevc"
                streams.stream.0.codec_tag_string="dvh1"
                streams.stream.0.color_transfer="smpte2084"
                streams.stream.0.side_data_list.side_data.0.side_data_type="DOVI configuration record"
                streams.stream.0.side_data_list.side_data.0.dv_profile=7
                streams.stream.0.side_data_list.side_data.0.dv_level=6
                streams.stream.0.side_data_list.side_data.0.rpu_present_flag=1
                streams.stream.0.side_data_list.side_data.0.el_present_flag=1
                streams.stream.0.side_data_list.side_data.0.bl_present_flag=1
                streams.stream.0.side_data_list.side_data.0.dv_bl_signal_compatibility_id=1
                """.trimIndent(),
            )

        assertEquals(VideoDynamicRange.UNKNOWN, plainHevc.videoColorInfo.dynamicRange)
        assertEquals(VideoDynamicRange.DOLBY_VISION, dolbyVision.videoColorInfo.dynamicRange)
        val dolbyVisionInfo = assertNotNull(dolbyVision.videoColorInfo.dolbyVision)
        assertEquals(7, dolbyVisionInfo.profile)
        assertTrue(dolbyVisionInfo.hasRpu == true)
        assertTrue(dolbyVisionInfo.hasHdr10CompatibleBaseLayer)
    }

    @Test
    fun `Dolby Vision probe requires explicit RPU and HDR10 compatibility evidence`() {
        fun probe(
            profile: Int,
            compatibilityId: Int?,
            rpuPresent: Int? = null,
        ): DolbyVisionInfo {
            val optionalFields =
                buildString {
                    rpuPresent?.let {
                        appendLine("streams.stream.0.side_data_list.side_data.0.rpu_present_flag=$it")
                    }
                    compatibilityId?.let {
                        appendLine("streams.stream.0.side_data_list.side_data.0.dv_bl_signal_compatibility_id=$it")
                    }
                }
            val result =
                JvmLibVlcMediaProbe.parseProbeOutput(
                    """
                    streams.stream.0.index=0
                    streams.stream.0.codec_type="video"
                    streams.stream.0.codec_name="hevc"
                    streams.stream.0.codec_tag_string="dvh1"
                    streams.stream.0.side_data_list.side_data.0.side_data_type="DOVI configuration record"
                    streams.stream.0.side_data_list.side_data.0.dv_profile=$profile
                    streams.stream.0.side_data_list.side_data.0.bl_present_flag=1
                    $optionalFields
                    """.trimIndent(),
                )
            return assertNotNull(result.videoColorInfo.dolbyVision)
        }

        assertFalse(probe(profile = 8, compatibilityId = null).hasRpu == true)
        assertFalse(probe(profile = 8, compatibilityId = null).hasHdr10CompatibleBaseLayer)
        assertFalse(probe(profile = 8, compatibilityId = 2).hasHdr10CompatibleBaseLayer)
        assertFalse(probe(profile = 8, compatibilityId = 4).hasHdr10CompatibleBaseLayer)
        assertTrue(probe(profile = 8, compatibilityId = 4).hasHlgCompatibleBaseLayer)
        assertFalse(probe(profile = 8, compatibilityId = 2).hasHlgCompatibleBaseLayer)
        assertTrue(probe(profile = 8, compatibilityId = 1, rpuPresent = 1).hasHdr10CompatibleBaseLayer)
        assertTrue(probe(profile = 7, compatibilityId = 6).hasHdr10CompatibleBaseLayer)
    }
}
