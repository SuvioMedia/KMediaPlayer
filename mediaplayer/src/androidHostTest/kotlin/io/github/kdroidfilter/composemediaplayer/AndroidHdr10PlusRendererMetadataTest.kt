package io.github.kdroidfilter.composemediaplayer

import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AndroidHdr10PlusRendererMetadataTest {
    @Test
    fun `copies a valid ST 2094-40 supplemental payload without mutating the codec buffer`() {
        val payload =
            byteArrayOf(
                0xB5.toByte(),
                0x00,
                0x3C,
                0x00,
                0x01,
                0x04,
                0x01,
                0x55,
            )
        val codecBuffer = ByteBuffer.wrap(payload).apply { position(3) }

        val copy = codecBuffer.copyHdr10PlusPayloadOrNull()

        assertContentEquals(payload, copy)
        assertEquals(3, codecBuffer.position())
    }

    @Test
    fun `rejects supplemental data that is not HDR10 plus`() {
        val wrongApplication =
            ByteBuffer.wrap(
                byteArrayOf(
                    0xB5.toByte(),
                    0x00,
                    0x3C,
                    0x00,
                    0x01,
                    0x05,
                    0x01,
                ),
            )

        assertNull(wrongApplication.copyHdr10PlusPayloadOrNull())
        assertNull(ByteBuffer.wrap(byteArrayOf(0xB5.toByte(), 0x00)).copyHdr10PlusPayloadOrNull())
    }
}
