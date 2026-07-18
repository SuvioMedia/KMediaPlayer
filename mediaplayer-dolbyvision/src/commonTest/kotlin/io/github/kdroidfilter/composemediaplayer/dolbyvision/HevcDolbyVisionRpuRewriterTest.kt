package io.github.kdroidfilter.composemediaplayer.dolbyvision

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class HevcDolbyVisionRpuRewriterTest {
    @Test
    fun `Annex B replacement preserves start codes and non-RPU NAL units`() {
        val source = byteArrayOf(9, 0, 0, 1) + vps + byteArrayOf(0, 0, 0, 1) + oldRpu + byteArrayOf(0, 0, 1) + slice
        val expected =
            byteArrayOf(9, 0, 0, 1) + vps + byteArrayOf(0, 0, 0, 1) + newRpu + byteArrayOf(0, 0, 1) + slice

        val result =
            assertIs<HevcRpuRewriteResult.Success>(
                HevcDolbyVisionRpuRewriter.rewrite(source, HevcNalUnitFormat.ANNEX_B, listOf(newRpu)),
            )

        assertEquals(1, result.replacedRpus)
        assertEquals(0, result.discardedEnhancementLayerNals)
        assertContentEquals(expected, result.payload)
    }

    @Test
    fun `Profile 7 enhancement NAL units are removed when producing Profile 8 1`() {
        val enhancement = byteArrayOf(0x7e, 0x01, 9, 8, 7)
        val source = lengthPrefixed(vps, oldRpu, enhancement, slice)

        val result =
            assertIs<HevcRpuRewriteResult.Success>(
                HevcDolbyVisionRpuRewriter.rewrite(
                    source,
                    HevcNalUnitFormat.LENGTH_PREFIXED_4,
                    listOf(newRpu),
                    discardEnhancementLayer = true,
                ),
            )

        assertEquals(1, result.discardedEnhancementLayerNals)
        assertContentEquals(lengthPrefixed(vps, newRpu, slice), result.payload)
    }

    @Test
    fun `length-prefixed replacement updates NAL length without touching picture bytes`() {
        val longerRpu = newRpu + byteArrayOf(4, 5, 6)
        val source = lengthPrefixed(vps, oldRpu, slice)
        val result =
            assertIs<HevcRpuRewriteResult.Success>(
                HevcDolbyVisionRpuRewriter.rewrite(
                    source,
                    HevcNalUnitFormat.LENGTH_PREFIXED_4,
                    listOf(byteArrayOf(0, 0, 0, 1) + longerRpu),
                ),
            )

        assertContentEquals(lengthPrefixed(vps, longerRpu, slice), result.payload)
    }

    @Test
    fun `RPU count mismatch malformed lengths and non-RPU replacements fail closed`() {
        val countMismatch =
            HevcDolbyVisionRpuRewriter.rewrite(
                lengthPrefixed(vps, oldRpu),
                HevcNalUnitFormat.LENGTH_PREFIXED_4,
                emptyList(),
            )
        val malformed =
            HevcDolbyVisionRpuRewriter.rewrite(
                byteArrayOf(0, 0, 1, 0, 1),
                HevcNalUnitFormat.LENGTH_PREFIXED_4,
                emptyList(),
            )
        val invalidReplacement =
            HevcDolbyVisionRpuRewriter.rewrite(
                lengthPrefixed(oldRpu),
                HevcNalUnitFormat.LENGTH_PREFIXED_4,
                listOf(slice),
            )

        assertTrue(assertIs<HevcRpuRewriteResult.Failure>(countMismatch).message.contains("count"))
        assertTrue(assertIs<HevcRpuRewriteResult.Failure>(malformed).message.contains("length"))
        assertTrue(assertIs<HevcRpuRewriteResult.Failure>(invalidReplacement).message.contains("UNSPEC-62"))
    }

    @Test
    fun `configured output limit is enforced before allocating rewritten payload`() {
        val result =
            HevcDolbyVisionRpuRewriter.rewrite(
                lengthPrefixed(oldRpu),
                HevcNalUnitFormat.LENGTH_PREFIXED_4,
                listOf(newRpu + ByteArray(32)),
                maximumOutputBytes = 16,
            )

        assertIs<HevcRpuRewriteResult.Failure>(result)
    }

    private fun lengthPrefixed(vararg units: ByteArray): ByteArray {
        val result = ByteArray(units.sumOf { 4 + it.size })
        var offset = 0
        units.forEach { unit ->
            result[offset++] = (unit.size ushr 24).toByte()
            result[offset++] = (unit.size ushr 16).toByte()
            result[offset++] = (unit.size ushr 8).toByte()
            result[offset++] = unit.size.toByte()
            unit.copyInto(result, offset)
            offset += unit.size
        }
        return result
    }

    private val vps = byteArrayOf(0x40, 0x01, 10, 11)
    private val slice = byteArrayOf(0x26, 0x01, 20, 21, 22)
    private val oldRpu = byteArrayOf(0x7c, 0x01, 1, 2)
    private val newRpu = byteArrayOf(0x7c, 0x01, 3)
}
