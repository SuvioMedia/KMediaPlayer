package io.github.kdroidfilter.composemediaplayer.dolbyvision

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class IosLibDoviRpuConverterTest {
    @Test
    fun `statically linked libdovi converts a real Profile 7 MEL RPU`() =
        runTest {
            val converter = LibDoviRpuConverter()

            assertTrue(converter.isAvailable)
            val converted =
                assertIs<DolbyVisionRpuConversionResult.Success>(
                    converter.convertProfile7To81(PROFILE_7_MEL_RPU.decodeHex()),
                ).rpuNalUnit

            assertContentEquals(byteArrayOf(0x7c, 0x01), converted.copyOfRange(0, 2))
            assertIs<DolbyVisionRpuConversionResult.Invalid>(converter.convertProfile7To81(converted))
            converter.close()
        }

    private fun String.decodeHex(): ByteArray =
        chunked(2)
            .map { it.toInt(radix = 16).toByte() }
            .toByteArray()

    private companion object {
        // MIT dovi_tool 2.3.3 MEL test vector from the pinned source commit.
        const val PROFILE_7_MEL_RPU =
            "000000011908090840613650af003ff801ffc00ffc001fffa000001000000d000003008000006800000400000300004" +
                "0000020000020000003000400000302000003020000030000400000200000200000344acc00006bd44acdf3f9d6384" +
                "acc8994000003020000030010000003001000000300386c4486030c14bc611c0a28000003034c7cb5fffe0000030000" +
                "030000030000c0401f01c2a2003008004f930f010115f0ae448301f40000800000030000f91f77ee80"
    }
}
