package io.github.kdroidfilter.composemediaplayer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JvmIsoBmffDolbyVisionProbeTest {
    @Test
    fun `parses profile 8 point 1 compatible base from dvvC`() {
        val box = dolbyVisionBox(type = "dvvC", profile = 8, level = 5, compatibilityId = 1)
        val color = checkNotNull(JvmIsoBmffDolbyVisionProbe.parseConfigurationBox(box, typeOffset = 4))

        assertEquals(VideoDynamicRange.DOLBY_VISION, color.dynamicRange)
        assertEquals(8, color.dolbyVision?.profile)
        assertEquals(5, color.dolbyVision?.level)
        assertTrue(color.dolbyVision?.hasRpu == true)
        assertTrue(color.dolbyVision?.hasHdr10CompatibleBaseLayer == true)
        assertEquals(DolbyVisionEnhancementLayer.NONE, color.dolbyVision?.enhancementLayer)
    }

    @Test
    fun `parses profile 5 without claiming an HDR10 base`() {
        val box = dolbyVisionBox(type = "dvcC", profile = 5, level = 5, compatibilityId = 0)
        val color = checkNotNull(JvmIsoBmffDolbyVisionProbe.parseConfigurationBox(box, typeOffset = 4))

        assertEquals(5, color.dolbyVision?.profile)
        assertFalse(color.dolbyVision?.hasHdr10CompatibleBaseLayer == true)
        assertFalse(color.dolbyVision?.hasHlgCompatibleBaseLayer == true)
    }

    @Test
    fun `parses profile 8 point 4 HLG compatible base`() {
        val box = dolbyVisionBox(type = "dvvC", profile = 8, level = 5, compatibilityId = 4)
        val color = checkNotNull(JvmIsoBmffDolbyVisionProbe.parseConfigurationBox(box, typeOffset = 4))

        assertEquals(VideoDynamicRange.DOLBY_VISION, color.dynamicRange)
        assertFalse(color.dolbyVision?.hasHdr10CompatibleBaseLayer == true)
        assertTrue(color.dolbyVision?.hasHlgCompatibleBaseLayer == true)
        assertEquals(VideoDynamicRange.HLG, color.dolbyVision?.compatibleBaseLayerDynamicRange)
    }

    @Test
    fun `rejects a configuration without an RPU`() {
        val box = dolbyVisionBox(type = "dvcC", profile = 5, level = 5, compatibilityId = 0, rpu = false)

        assertNull(JvmIsoBmffDolbyVisionProbe.parseConfigurationBox(box, typeOffset = 4))
    }

    private fun dolbyVisionBox(
        type: String,
        profile: Int,
        level: Int,
        compatibilityId: Int,
        rpu: Boolean = true,
    ): ByteArray {
        val payload = ByteArray(24)
        payload[0] = 1
        payload[1] = 0
        val packed =
            ((profile and 0x7f) shl 9) or
                ((level and 0x3f) shl 3) or
                (if (rpu) 0x04 else 0) or
                0x01
        payload[2] = (packed ushr 8).toByte()
        payload[3] = packed.toByte()
        payload[4] = ((compatibilityId and 0x0f) shl 4).toByte()

        return ByteArray(8 + payload.size).also { box ->
            box[3] = box.size.toByte()
            type.toByteArray(Charsets.ISO_8859_1).copyInto(box, destinationOffset = 4)
            payload.copyInto(box, destinationOffset = 8)
        }
    }
}
