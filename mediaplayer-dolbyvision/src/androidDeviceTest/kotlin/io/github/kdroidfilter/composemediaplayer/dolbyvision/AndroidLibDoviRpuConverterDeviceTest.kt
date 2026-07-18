package io.github.kdroidfilter.composemediaplayer.dolbyvision

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest
import java.util.Base64

class AndroidLibDoviRpuConverterDeviceTest {
    @Test
    fun convertsRealProfile7FelRpuWithPackagedAbiCompatibleLibDovi() =
        runBlocking {
            val converter = LibDoviRpuConverter()

            assertTrue("The Android artifact must package its ABI-compatible libdovi shim.", converter.isAvailable)
            val result = converter.convertProfile7To81(Base64.getDecoder().decode(PROFILE_7_FEL_RPU))
            assertTrue(
                "Expected a successful Profile 7 FEL to Profile 8.1 conversion, got $result",
                result is DolbyVisionRpuConversionResult.Success,
            )
            val converted = (result as DolbyVisionRpuConversionResult.Success).rpuNalUnit
            assertArrayEquals(byteArrayOf(0x7c, 0x01), converted.copyOfRange(0, 2))
            assertEquals(PROFILE_8_1_WITHOUT_FEL_MAPPING_SIZE, converted.size)
            assertEquals(
                PROFILE_8_1_WITHOUT_FEL_MAPPING_SHA256,
                MessageDigest.getInstance("SHA-256").digest(converted).toHexString(),
            )
            converter.close()
        }

    private fun ByteArray.toHexString(): String = joinToString(separator = "") { byte -> "%02x".format(byte) }

    private companion object {
        // MIT dovi_tool 2.3.3 test vector, pinned to the same immutable commit as the native shim.
        const val PROFILE_7_FEL_RPU =
            "AAAAARkICQhAYTZQriAAIAgCAIAgCAIAf4Af/AD/wAH/+gAAAwEAAAMA0AAACAAABoAAAEAAADQAAAMCAAADAaAAABAAAA0AAAMAgAAAaAAABAAAAwNAAAAgAAAKhtnmey+GPvwYnmAp6YwGuMVmp81fjf/LbYw544qZgbhspnkPQF9DTLfYDp4Ew0n9O7d+ws1d46FH1EwKLoBiFkoCm11ZUA7Tj1jEbE7BOhRYLP8wI1NtV7u25BFIO5KWZBNpj85rwxvWIPrdQFu+9GQxYLHwG/Lu+/Kl4vPMtKvzzDmDd551aDEbAEgAAEAEAEAAAEASAAAQAQAQAAAQBIAABABABAAAByVmAAA16iVm+fzrHCVmRMoAAAMBAAADAAgAAAMACAAAAwAcNiJDAYYKXjCOBRQAAAMBpj5a//8AAAMAAAMAAAMAAGAgD4DhUYAwCABZyhIAwCghjfglgAgAYUAAAgIqUSCIBQAAAwACKBFQEgwH0AACDWABXrjynYKA"
        const val PROFILE_8_1_WITHOUT_FEL_MAPPING_SIZE = 162
        const val PROFILE_8_1_WITHOUT_FEL_MAPPING_SHA256 =
            "5c756843ed4a756ad722e8a6f139390ad34992385f417dbc39f4afde5da09998"
    }
}
