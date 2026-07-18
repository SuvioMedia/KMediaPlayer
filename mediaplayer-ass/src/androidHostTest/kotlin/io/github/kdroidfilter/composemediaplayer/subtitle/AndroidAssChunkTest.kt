package io.github.kdroidfilter.composemediaplayer.subtitle

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AndroidAssChunkTest {
    @Test
    fun `extracts duration and preserves the complete Matroska payload`() {
        val sample =
            "Dialogue: 0:00:00:00,0:01:02.34,7,Default,Actor,0010,0020,0030,fx,{\\b1}Hello, world"
                .encodeToByteArray()

        val chunk = parseAndroidAssChunk(sample)

        assertEquals(62_340L, chunk?.durationMs)
        assertContentEquals(
            "7,Default,Actor,0010,0020,0030,fx,{\\b1}Hello, world".encodeToByteArray(),
            chunk?.payload,
        )
    }

    @Test
    fun `accepts a zero duration`() {
        val chunk = parseAndroidAssChunk("Dialogue: 0:00:00:00,0:00:00.00,0,Default,,text".encodeToByteArray())

        assertEquals(0L, chunk?.durationMs)
        assertContentEquals("0,Default,,text".encodeToByteArray(), chunk?.payload)
    }

    @Test
    fun `rejects malformed or out of range duration fields`() {
        val invalidDurations =
            listOf(
                "",
                "1:02:03",
                "1:02:03.4x",
                "1:60:00.00",
                "1:00:60.00",
                "1:00:00.100",
                "-1:00:00.00",
                "999999999999:00:00.00",
            )

        invalidDurations.forEach { duration ->
            assertNull(
                parseAndroidAssChunk("Dialogue: 0:00:00:00,$duration,0,Default,,text".encodeToByteArray()),
                "duration '$duration' should be rejected",
            )
        }
    }

    @Test
    fun `rejects incomplete packets`() {
        val invalidSamples =
            listOf(
                byteArrayOf(),
                "Comment: 0:00:00:00,0:00:01.00,0,Default,,text".encodeToByteArray(),
                "Dialogue: 0:00:00:00".encodeToByteArray(),
                "Dialogue: 0:00:00:00,0:00:01.00".encodeToByteArray(),
                "Dialogue: 0:00:00:00,0:00:01.00,".encodeToByteArray(),
            )

        invalidSamples.forEach { sample -> assertNull(parseAndroidAssChunk(sample)) }
    }

    @Test
    fun `enforces the packet size limit`() {
        assertNull(parseAndroidAssChunk(ByteArray(MAX_ASS_SAMPLE_BYTES + 1)))
    }
}
