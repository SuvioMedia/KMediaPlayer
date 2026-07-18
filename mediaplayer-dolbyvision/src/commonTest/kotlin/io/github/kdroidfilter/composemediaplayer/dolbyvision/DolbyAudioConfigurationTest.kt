package io.github.kdroidfilter.composemediaplayer.dolbyvision

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class DolbyAudioConfigurationTest {
    @Test
    fun `AC3 syncframe produces normative dac3 fields and duration`() {
        val packet = paddedFrame(768, "0b775052144043e106f46370808082101010415c7cf9f3e7")
        val configuration = parseDolbyAudioPacketConfiguration(packet, eac3 = false)

        assertEquals(48_000, configuration.sampleRate)
        assertEquals(32_000_000L, configuration.durationNs)
        assertEquals(2, configuration.channelMode)
        assertEquals(0, configuration.lfeOn)
        assertContentEquals(byteArrayOf(0x10, 0x11, 0x40), configuration.codecBoxPayload)
    }

    @Test
    fun `EAC3 six-block syncframe produces bounded dec3 configuration`() {
        val packet = paddedFrame(768, "0b77017f3487c0002000000045008c0404040101010063e7")
        val configuration = parseDolbyAudioPacketConfiguration(packet, eac3 = true)

        assertEquals(48_000, configuration.sampleRate)
        assertEquals(32_000_000L, configuration.durationNs)
        assertEquals(2, configuration.channelMode)
        assertEquals(0, configuration.lfeOn)
        assertEquals(5, configuration.codecBoxPayload.size)
    }

    @Test
    fun `truncated and partial-block Dolby packets fail closed`() {
        kotlin.test.assertFailsWith<IllegalArgumentException> {
            parseDolbyAudioPacketConfiguration(byteArrayOf(0x0b, 0x77), eac3 = false)
        }
        val partialBlocks = paddedFrame(768, "0b77017f0487c0002000000045008c0404040101010063e7")
        kotlin.test.assertFailsWith<IllegalArgumentException> {
            parseDolbyAudioPacketConfiguration(partialBlocks, eac3 = true)
        }
    }
}

private fun paddedFrame(
    size: Int,
    headerHex: String,
): ByteArray =
    ByteArray(size).also { output ->
        headerHex.decodeHex().copyInto(output)
    }

private fun String.decodeHex(): ByteArray {
    require(length % 2 == 0)
    return ByteArray(length / 2) { index -> substring(index * 2, index * 2 + 2).toInt(16).toByte() }
}
