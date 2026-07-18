package io.github.kdroidfilter.composemediaplayer

import android.media.MediaFormat
import androidx.media3.common.C
import androidx.media3.common.ColorInfo
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AndroidVideoColorInfoTest {
    @Test
    fun `PQ Media3 color info maps to typed HDR10 source`() {
        val format =
            Format
                .Builder()
                .setSampleMimeType(MimeTypes.VIDEO_H265)
                .setColorInfo(
                    ColorInfo
                        .Builder()
                        .setColorSpace(C.COLOR_SPACE_BT2020)
                        .setColorRange(C.COLOR_RANGE_LIMITED)
                        .setColorTransfer(C.COLOR_TRANSFER_ST2084)
                        .setLumaBitdepth(10)
                        .build(),
                ).build()

        val color = format.toVideoColorInfo()

        assertEquals(VideoDynamicRange.HDR10, color.dynamicRange)
        assertEquals(VideoColorPrimaries.BT2020, color.primaries)
        assertEquals(VideoColorTransfer.PQ, color.transfer)
        assertEquals(VideoColorRange.LIMITED, color.range)
        assertEquals(10, color.bitDepth)
    }

    @Test
    fun `Dolby Vision codec string preserves profile and level`() {
        val format =
            Format
                .Builder()
                .setSampleMimeType(MimeTypes.VIDEO_DOLBY_VISION)
                .setCodecs("dvhe.07.06")
                .build()

        val color = format.toVideoColorInfo()

        assertEquals(VideoDynamicRange.DOLBY_VISION, color.dynamicRange)
        assertEquals(7, color.dolbyVision?.profile)
        assertEquals(6, color.dolbyVision?.level)
        assertEquals(DolbyVisionEnhancementLayer.UNKNOWN, color.dolbyVision?.enhancementLayer)
        assertNull(color.dolbyVision?.hasRpu)
        assertTrue(color.dolbyVision?.hasHdr10CompatibleBaseLayer == true)
    }

    @Test
    fun `CTA 861 static metadata exposes mastering display and content light`() {
        val metadata = ByteArray(25)
        putU16(metadata, 1, 35_400)
        putU16(metadata, 3, 14_600)
        putU16(metadata, 17, 1_000)
        putU16(metadata, 19, 50)
        putU16(metadata, 21, 4_000)
        putU16(metadata, 23, 800)
        val format =
            Format
                .Builder()
                .setColorInfo(
                    ColorInfo
                        .Builder()
                        .setColorTransfer(C.COLOR_TRANSFER_ST2084)
                        .setHdrStaticInfo(metadata)
                        .build(),
                ).build()

        val color = format.toVideoColorInfo()

        val mastering = assertNotNull(color.masteringDisplay)
        assertEquals(0.708f, mastering.redX, absoluteTolerance = 0.00001f)
        assertEquals(1_000f, mastering.maxLuminanceNits)
        assertEquals(0.005f, mastering.minLuminanceNits, absoluteTolerance = 0.00001f)
        assertEquals(4_000, color.contentLightLevel?.maxContentLightLevelNits)
        assertEquals(800, color.contentLightLevel?.maxFrameAverageLightLevelNits)
    }

    @Test
    fun `codec output static metadata is copied without mutating its buffer`() {
        val metadata = ByteArray(25)
        putU16(metadata, 17, 1_000)
        putU16(metadata, 21, 1_000)
        putU16(metadata, 23, 400)
        val codecBuffer = ByteBuffer.wrap(metadata).apply { position(7) }
        val mediaFormat = MediaFormat().apply { setByteBuffer(MediaFormat.KEY_HDR_STATIC_INFO, codecBuffer) }

        val copy = mediaFormat.copyHdrStaticInfoOrNull()

        assertTrue(metadata.contentEquals(assertNotNull(copy)))
        assertEquals(7, codecBuffer.position())
    }

    @Test
    fun `empty codec static metadata descriptor does not invent mastering values`() {
        val color =
            Format
                .Builder()
                .setColorInfo(
                    ColorInfo
                        .Builder()
                        .setColorTransfer(C.COLOR_TRANSFER_HLG)
                        .setHdrStaticInfo(ByteArray(25))
                        .build(),
                ).build()
                .toVideoColorInfo()

        assertNull(color.masteringDisplay)
        assertNull(color.contentLightLevel)
    }

    @Test
    fun `missing color metadata remains unknown without inventing SDR or HDR metadata`() {
        val color = Format.Builder().build().toVideoColorInfo()

        assertEquals(VideoDynamicRange.UNKNOWN, color.dynamicRange)
        assertNull(color.masteringDisplay)
        assertNull(color.hdr10Plus)
        assertNull(color.dolbyVision)
    }

    @Test
    fun `explicit Media3 assumed default color info maps to SDR`() {
        val color =
            Format
                .Builder()
                .setColorInfo(ColorInfo.Builder().build())
                .build()
                .toVideoColorInfo()

        assertEquals(VideoDynamicRange.SDR, color.dynamicRange)
    }

    @Test
    fun `Dolby Vision metadata refinement preserves the configured output signal`() {
        val manifestSignal =
            VideoColorInfo(
                dynamicRange = VideoDynamicRange.DOLBY_VISION,
                transfer = VideoColorTransfer.UNKNOWN,
                dolbyVision = DolbyVisionInfo(profile = 5, level = 1),
            )
        val decoderSignal =
            manifestSignal.copy(
                transfer = VideoColorTransfer.PQ,
                dolbyVision = DolbyVisionInfo(profile = 5, level = 3),
            )

        assertTrue(manifestSignal.hasSameAndroidOutputSignalAs(decoderSignal))
    }

    @Test
    fun `Dolby Vision profile change requires output re-verification`() {
        val profile5 =
            VideoColorInfo(
                dynamicRange = VideoDynamicRange.DOLBY_VISION,
                dolbyVision = DolbyVisionInfo(profile = 5),
            )
        val profile8 = profile5.copy(dolbyVision = DolbyVisionInfo(profile = 8))

        assertFalse(profile5.hasSameAndroidOutputSignalAs(profile8))
    }

    private fun putU16(
        bytes: ByteArray,
        offset: Int,
        value: Int,
    ) {
        bytes[offset] = (value and 0xff).toByte()
        bytes[offset + 1] = ((value ushr 8) and 0xff).toByte()
    }
}
