package io.github.kdroidfilter.composemediaplayer

import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.io.path.deleteIfExists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class JvmLegacyVideoContainerSupportTest {
    @Test
    fun `probes ffmpeg-style MPEG-4 and MJPEG AVI headers as eight-bit SDR`() {
        listOf("FMP4" to "mpeg4", "MJPG" to "mjpeg").forEach { (fourCc, codecName) ->
            val result = assertNotNull(JvmLegacyVideoContainerSupport.parseContainerPrefix(aviFixture(fourCc)))

            assertEquals(codecName, result.videoCodecName)
            assertEquals(320, result.videoWidth)
            assertEquals(180, result.videoHeight)
            assertEquals(2.0, result.durationSeconds)
            assertEquals(VideoDynamicRange.SDR, result.videoColorInfo.dynamicRange)
            assertEquals(8, result.videoColorInfo.bitDepth)
            assertTrue(result.videoColorInfo.isSafeForUnmanagedSdrFallback())
        }
    }

    @Test
    fun `probes WMV2 ASF header as eight-bit SDR`() {
        val result = assertNotNull(JvmLegacyVideoContainerSupport.parseContainerPrefix(asfFixture("WMV2")))

        assertEquals("wmv2", result.videoCodecName)
        assertEquals(320, result.videoWidth)
        assertEquals(180, result.videoHeight)
        assertEquals(2.0, result.durationSeconds)
        assertEquals(VideoDynamicRange.SDR, result.videoColorInfo.dynamicRange)
        assertEquals(8, result.videoColorInfo.bitDepth)
        assertTrue(result.videoColorInfo.isSafeForUnmanagedSdrFallback())
    }

    @Test
    fun `does not claim unprobed H264 in AVI is SDR`() {
        val result = assertNotNull(JvmLegacyVideoContainerSupport.parseContainerPrefix(aviFixture("H264")))

        assertEquals("h264", result.videoCodecName)
        assertEquals(VideoDynamicRange.UNKNOWN, result.videoColorInfo.dynamicRange)
        assertFalse(result.videoColorInfo.isSafeForUnmanagedSdrFallback())
    }

    @Test
    fun `detects legacy containers without sending them to the HLS fallback`() =
        runTest {
            val avi = Files.createTempFile("compose-media-player-legacy-", ".bin")
            val wmv = Files.createTempFile("compose-media-player-legacy-", ".bin")
            try {
                Files.write(avi, aviFixture("FMP4"))
                Files.write(wmv, asfFixture("WMV2"))

                assertEquals(JvmLegacyVideoContainer.AVI, JvmLegacyVideoContainerSupport.containerFor(avi.toString()))
                assertEquals(JvmLegacyVideoContainer.ASF, JvmLegacyVideoContainerSupport.containerFor(wmv.toString()))
                assertFalse(JvmExternalFallbackContainerSupport.needsContainerFallback(avi.toString()))
                assertFalse(JvmExternalFallbackContainerSupport.needsContainerFallback(wmv.toString()))
            } finally {
                avi.deleteIfExists()
                wmv.deleteIfExists()
            }
        }

    @Test
    fun `libVLC media probe includes legacy container metadata`() {
        val file = Files.createTempFile("compose-media-player-legacy-", ".avi")
        try {
            Files.write(file, aviFixture("FMP4"))

            val result = JvmLibVlcMediaProbe.probe(file.toUri().toString())

            assertEquals("mpeg4", result.videoCodecName)
            assertEquals(320, result.videoWidth)
            assertEquals(180, result.videoHeight)
            assertEquals(VideoDynamicRange.SDR, result.videoColorInfo.dynamicRange)
        } finally {
            file.deleteIfExists()
        }
    }

    private fun aviFixture(codec: String): ByteArray {
        val mainHeader = ByteArray(56)
        mainHeader.putLeInt(0, 40_000)
        mainHeader.putLeInt(16, 50)
        mainHeader.putLeInt(32, 320)
        mainHeader.putLeInt(36, 180)

        val streamHeader = ByteArray(56)
        streamHeader.putAscii(0, "vids")
        streamHeader.putAscii(4, codec)
        streamHeader.putLeInt(20, 1)
        streamHeader.putLeInt(24, 25)
        streamHeader.putLeInt(32, 50)

        val bitmapInfo = ByteArray(40)
        bitmapInfo.putLeInt(0, 40)
        bitmapInfo.putLeInt(4, 320)
        bitmapInfo.putLeInt(8, 180)
        bitmapInfo.putLeShort(12, 1)
        bitmapInfo.putLeShort(14, 24)
        bitmapInfo.putAscii(16, codec)

        val streamList =
            riffChunk(
                "LIST",
                "strl".asciiBytes() +
                    riffChunk("strh", streamHeader) +
                    riffChunk("strf", bitmapInfo),
            )
        val headerList =
            riffChunk(
                "LIST",
                "hdrl".asciiBytes() + riffChunk("avih", mainHeader) + streamList,
            )
        val body = "AVI ".asciiBytes() + headerList
        return "RIFF".asciiBytes() + body.size.leIntBytes() + body
    }

    private fun asfFixture(codec: String): ByteArray {
        val fileProperties = ByteArray(80)
        fileProperties.putLeLong(40, 30_000_000L)
        fileProperties.putLeLong(56, 1_000L)

        val streamProperties = ByteArray(54 + 55)
        asfVideoMediaGuid.copyInto(streamProperties, destinationOffset = 0)
        streamProperties.putLeInt(40, 55)
        val typeData = 54
        streamProperties.putLeInt(typeData, 320)
        streamProperties.putLeInt(typeData + 4, 180)
        streamProperties[typeData + 8] = 2
        streamProperties.putLeShort(typeData + 9, 44)
        streamProperties.putLeInt(typeData + 11, 44)
        streamProperties.putLeInt(typeData + 15, 320)
        streamProperties.putLeInt(typeData + 19, 180)
        streamProperties.putLeShort(typeData + 23, 1)
        streamProperties.putLeShort(typeData + 25, 24)
        streamProperties.putAscii(typeData + 27, codec)

        val objects =
            asfObject(asfFilePropertiesGuid, fileProperties) +
                asfObject(asfStreamPropertiesGuid, streamProperties)
        val headerSize = 30 + objects.size
        return asfHeaderGuid +
            headerSize.toLong().leLongBytes() +
            2.leIntBytes() +
            byteArrayOf(1, 2) +
            objects
    }

    private fun riffChunk(
        id: String,
        payload: ByteArray,
    ): ByteArray =
        id.asciiBytes() +
            payload.size.leIntBytes() +
            payload +
            if (payload.size % 2 == 0) byteArrayOf() else byteArrayOf(0)

    private fun asfObject(
        guid: ByteArray,
        payload: ByteArray,
    ): ByteArray = guid + (24L + payload.size).leLongBytes() + payload

    private fun String.asciiBytes(): ByteArray = toByteArray(Charsets.ISO_8859_1)

    private fun Int.leIntBytes(): ByteArray =
        byteArrayOf(
            toByte(),
            (this ushr 8).toByte(),
            (this ushr 16).toByte(),
            (this ushr 24).toByte(),
        )

    private fun Long.leLongBytes(): ByteArray = ByteArray(8) { index -> (this ushr (index * 8)).toByte() }

    private fun ByteArray.putAscii(
        offset: Int,
        value: String,
    ) {
        value.asciiBytes().copyInto(this, destinationOffset = offset)
    }

    private fun ByteArray.putLeShort(
        offset: Int,
        value: Int,
    ) {
        this[offset] = value.toByte()
        this[offset + 1] = (value ushr 8).toByte()
    }

    private fun ByteArray.putLeInt(
        offset: Int,
        value: Int,
    ) {
        value.leIntBytes().copyInto(this, destinationOffset = offset)
    }

    private fun ByteArray.putLeLong(
        offset: Int,
        value: Long,
    ) {
        value.leLongBytes().copyInto(this, destinationOffset = offset)
    }

    private val asfHeaderGuid =
        byteArrayOf(
            0x30,
            0x26,
            0xB2.toByte(),
            0x75,
            0x8E.toByte(),
            0x66,
            0xCF.toByte(),
            0x11,
            0xA6.toByte(),
            0xD9.toByte(),
            0x00,
            0xAA.toByte(),
            0x00,
            0x62,
            0xCE.toByte(),
            0x6C,
        )
    private val asfFilePropertiesGuid =
        byteArrayOf(
            0xA1.toByte(),
            0xDC.toByte(),
            0xAB.toByte(),
            0x8C.toByte(),
            0x47,
            0xA9.toByte(),
            0xCF.toByte(),
            0x11,
            0x8E.toByte(),
            0xE4.toByte(),
            0x00,
            0xC0.toByte(),
            0x0C,
            0x20,
            0x53,
            0x65,
        )
    private val asfStreamPropertiesGuid =
        byteArrayOf(
            0x91.toByte(),
            0x07,
            0xDC.toByte(),
            0xB7.toByte(),
            0xB7.toByte(),
            0xA9.toByte(),
            0xCF.toByte(),
            0x11,
            0x8E.toByte(),
            0xE6.toByte(),
            0x00,
            0xC0.toByte(),
            0x0C,
            0x20,
            0x53,
            0x65,
        )
    private val asfVideoMediaGuid =
        byteArrayOf(
            0xC0.toByte(),
            0xEF.toByte(),
            0x19,
            0xBC.toByte(),
            0x4D,
            0x5B,
            0xCF.toByte(),
            0x11,
            0xA8.toByte(),
            0xFD.toByte(),
            0x00,
            0x80.toByte(),
            0x5F,
            0x5C,
            0x44,
            0x2B,
        )
}
