@file:OptIn(ExperimentalWasmJsInterop::class)

package io.github.kdroidfilter.composemediaplayer.dolbyvision

import kotlinx.coroutines.test.runTest
import kotlin.js.JsArray
import kotlin.js.JsNumber
import kotlin.js.toInt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class WasmLibDoviRpuConverterTest {
    @Test
    fun `packaged Wasm shim converts a real Profile 7 RPU`() =
        runTest {
            val converter = LibDoviRpuConverter()

            assertTrue(converter.prepare(), "The browser artifact must package and instantiate libdovi Wasm.")
            assertTrue(converter.isAvailable)
            val result =
                assertIs<DolbyVisionRpuConversionResult.Success>(
                    converter.convertProfile7To81(decodeBase64(PROFILE_7_FEL_RPU)),
                )
            assertContentEquals(byteArrayOf(0x7c, 0x01), result.rpuNalUnit.copyOfRange(0, 2))
            assertEquals(PROFILE_8_1_WITHOUT_FEL_MAPPING_SIZE, result.rpuNalUnit.size)
            assertIs<DolbyVisionRpuConversionResult.Invalid>(converter.convertProfile7To81(result.rpuNalUnit))
            converter.close()
        }

    private companion object {
        const val PROFILE_7_FEL_RPU =
            "AAAAARkICQhAYTZQriAAIAgCAIAgCAIAf4Af/AD/wAH/+gAAAwEAAAMA0AAACAAABoAAAEAAADQAAAMCAAADAaAAABAAAA0AAAMAgAAAaAAABAAAAwNAAAAgAAAKhtnmey+GPvwYnmAp6YwGuMVmp81fjf/LbYw544qZgbhspnkPQF9DTLfYDp4Ew0n9O7d+ws1d46FH1EwKLoBiFkoCm11ZUA7Tj1jEbE7BOhRYLP8wI1NtV7u25BFIO5KWZBNpj85rwxvWIPrdQFu+9GQxYLHwG/Lu+/Kl4vPMtKvzzDmDd551aDEbAEgAAEAEAEAAAEASAAAQAQAQAAAQBIAABABABAAAByVmAAA16iVm+fzrHCVmRMoAAAMBAAADAAgAAAMACAAAAwAcNiJDAYYKXjCOBRQAAAMBpj5a//8AAAMAAAMAAAMAAGAgD4DhUYAwCABZyhIAwCghjfglgAgAYUAAAgIqUSCIBQAAAwACKBFQEgwH0AACDWABXrjynYKA"
        const val PROFILE_8_1_WITHOUT_FEL_MAPPING_SIZE = 162
    }
}

private fun decodeBase64(encoded: String): ByteArray {
    val values = decodeBase64ToNumbers(encoded)
    return ByteArray(values.length) { index -> values[index]?.toInt()?.toByte() ?: 0 }
}

@Suppress("UNUSED_PARAMETER")
private fun decodeBase64ToNumbers(encoded: String): JsArray<JsNumber> =
    js(
        """
        Array.from(Uint8Array.from(atob(encoded), function(character) { return character.charCodeAt(0); }))
        """,
    )
