package io.github.kdroidfilter.composemediaplayer.dolbyvision

import kotlinx.coroutines.test.runTest
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class JvmLibDoviRpuConverterTest {
    @Test
    fun `published JVM libdovi matrix excludes Intel macOS`() {
        assertTrue(isJvmLibDoviPlatformSupported("Mac OS X", "arm64"))
        assertFalse(isJvmLibDoviPlatformSupported("Mac OS X", "x86_64"))
        assertTrue(isJvmLibDoviPlatformSupported("Windows 11", "x86_64"))
        assertTrue(isJvmLibDoviPlatformSupported("Linux", "aarch64"))
    }

    @Test
    fun `bundled native shim converts a real Profile 7 FEL RPU and drops FEL mapping`() =
        runTest {
            val converter = JvmLibDoviRpuConverter()

            assertTrue(converter.isAvailable, "The JVM artifact must package its host libdovi shim.")
            val result =
                assertIs<DolbyVisionRpuConversionResult.Success>(
                    converter.convertProfile7To81(Base64.getDecoder().decode(PROFILE_7_FEL_RPU)),
                )
            assertContentEquals(byteArrayOf(0x7c, 0x01), result.rpuNalUnit.copyOfRange(0, 2))
            assertEquals(PROFILE_8_1_WITHOUT_FEL_MAPPING_SIZE, result.rpuNalUnit.size)
            assertEquals(
                PROFILE_8_1_WITHOUT_FEL_MAPPING_SHA256,
                MessageDigest.getInstance("SHA-256").digest(result.rpuNalUnit).toHexString(),
            )
            assertIs<DolbyVisionRpuConversionResult.Invalid>(
                converter.convertProfile7To81(result.rpuNalUnit),
                "A converted Profile 8.1 RPU must not be accepted as another Profile 7 input.",
            )
            converter.close()
        }

    @Test
    fun `missing native shim is an unavailable result rather than a capability claim`() =
        runTest {
            val converter = JvmLibDoviRpuConverter(Path.of("/definitely/missing/composemediaplayer-libdovi"))

            assertFalse(converter.isAvailable)
            assertIs<DolbyVisionRpuConversionResult.Unavailable>(
                converter.convertProfile7To81(byteArrayOf(0x7c, 0x01)),
            )
            converter.close()
        }

    private companion object {
        // MIT dovi_tool 2.3.3 test vector, pinned to the same immutable commit as the native shim.
        const val PROFILE_7_FEL_RPU =
            "AAAAARkICQhAYTZQriAAIAgCAIAgCAIAf4Af/AD/wAH/+gAAAwEAAAMA0AAACAAABoAAAEAAADQAAAMCAAADAaAAABAAAA0AAAMAgAAAaAAABAAAAwNAAAAgAAAKhtnmey+GPvwYnmAp6YwGuMVmp81fjf/LbYw544qZgbhspnkPQF9DTLfYDp4Ew0n9O7d+ws1d46FH1EwKLoBiFkoCm11ZUA7Tj1jEbE7BOhRYLP8wI1NtV7u25BFIO5KWZBNpj85rwxvWIPrdQFu+9GQxYLHwG/Lu+/Kl4vPMtKvzzDmDd551aDEbAEgAAEAEAEAAAEASAAAQAQAQAAAQBIAABABABAAAByVmAAA16iVm+fzrHCVmRMoAAAMBAAADAAgAAAMACAAAAwAcNiJDAYYKXjCOBRQAAAMBpj5a//8AAAMAAAMAAAMAAGAgD4DhUYAwCABZyhIAwCghjfglgAgAYUAAAgIqUSCIBQAAAwACKBFQEgwH0AACDWABXrjynYKA"
        const val PROFILE_8_1_WITHOUT_FEL_MAPPING_SIZE = 162
        const val PROFILE_8_1_WITHOUT_FEL_MAPPING_SHA256 =
            "5c756843ed4a756ad722e8a6f139390ad34992385f417dbc39f4afde5da09998"
    }
}
