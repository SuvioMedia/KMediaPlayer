@file:Suppress("MagicNumber")

package io.github.kdroidfilter.composemediaplayer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class Hdr10PlusMetadataTest {
    @Test
    fun `parser binds complete ST 2094-40 payload to frame timestamp`() {
        val result = Hdr10PlusMetadataParser.parse(singleWindowPayload(), timestampUs = 41_708L)
        val metadata = assertIs<Hdr10PlusParseResult.Success>(result).metadata

        assertEquals(41_708L, metadata.timestampUs)
        assertEquals(4, metadata.applicationIdentifier)
        assertEquals(1, metadata.applicationVersion)
        assertEquals(1_000, metadata.targetedSystemDisplayMaximumLuminance)
        assertEquals(1, metadata.windows.size)
        assertEquals(listOf(10_000, 11_000, 12_000), metadata.windows.single().maxScl)
        assertEquals(4_000, metadata.windows.single().averageMaxRgb)
        assertEquals(
            9,
            metadata.windows
                .single()
                .distributionPercentiles.size,
        )
        assertEquals(
            1,
            metadata.windows
                .single()
                .distributionPercentiles
                .first()
                .percentage,
        )
        assertEquals(
            1_000,
            metadata.windows
                .single()
                .distributionPercentiles
                .first()
                .percentileMaxRgb,
        )
        assertEquals(
            1_024,
            metadata.windows
                .single()
                .toneMapping
                ?.kneePointX,
        )
        assertEquals(
            listOf(320, 700),
            metadata.windows
                .single()
                .toneMapping
                ?.bezierCurveAnchors,
        )
        assertNull(metadata.windows.single().colorSaturationWeight)
    }

    @Test
    fun `parser rejects wrong registration and truncation without throwing`() {
        val payload = singleWindowPayload()
        assertIs<Hdr10PlusParseResult.Invalid>(
            Hdr10PlusMetadataParser.parse(payload.copyOf().also { it[0] = 0 }, timestampUs = 0),
        )
        assertIs<Hdr10PlusParseResult.Invalid>(
            Hdr10PlusMetadataParser.parse(payload.copyOf(payload.size / 2), timestampUs = 0),
        )
    }

    @Test
    fun `valid dynamic metadata promotes only a PQ HDR10 source`() {
        val hdr10 =
            VideoColorInfo(
                dynamicRange = VideoDynamicRange.HDR10,
                bitDepth = 10,
                primaries = VideoColorPrimaries.BT2020,
                transfer = VideoColorTransfer.PQ,
                matrix = VideoColorMatrix.BT2020_NCL,
                range = VideoColorRange.LIMITED,
            )

        val promoted = assertNotNull(hdr10.withObservedHdr10PlusPayload(singleWindowPayload(), 41_708L))

        assertEquals(VideoDynamicRange.HDR10_PLUS, promoted.dynamicRange)
        assertEquals(4, promoted.hdr10Plus?.applicationIdentifier)
        assertEquals(1, promoted.hdr10Plus?.applicationVersion)
        assertTrue(promoted.hdr10Plus?.hasPerFrameMetadata == true)
        assertNull(
            hdr10
                .copy(dynamicRange = VideoDynamicRange.SDR, transfer = VideoColorTransfer.SDR)
                .withObservedHdr10PlusPayload(singleWindowPayload(), 41_708L),
        )
        assertNull(hdr10.withObservedHdr10PlusPayload(byteArrayOf(0), 41_708L))
    }

    @Test
    fun `parser rejects nonconformant application version window count saturation and trailing data`() {
        val wrongVersion = singleWindowPayload().also { it[6] = 2 }
        val multipleWindows = singleWindowPayload().also { it[7] = (2 shl 6).toByte() }
        val saturation = singleWindowPayload(colorSaturationWeight = 12)
        val profileBWithoutAnchors = singleWindowPayload(bezierAnchors = emptyList())
        val trailingData = singleWindowPayload() + byteArrayOf(1)

        listOf(wrongVersion, multipleWindows, saturation, profileBWithoutAnchors, trailingData).forEach { payload ->
            assertIs<Hdr10PlusParseResult.Invalid>(Hdr10PlusMetadataParser.parse(payload, timestampUs = 0))
        }
    }

    @Test
    fun `timestamp timeline stays bounded and matches exact or nearby frames`() {
        val timeline = Hdr10PlusMetadataTimeline(capacity = 2)
        val first = parsed(timestampUs = 10_000)
        val second = parsed(timestampUs = 20_000)
        val third = parsed(timestampUs = 30_000)

        timeline.put(first)
        timeline.put(second)
        timeline.put(third)

        assertEquals(2, timeline.size)
        assertNull(timeline.metadataAt(10_000))
        assertEquals(second, timeline.metadataAt(20_000))
        assertEquals(third, timeline.metadataNear(29_500, toleranceUs = 1_000))
        timeline.discardBefore(30_000)
        assertEquals(1, timeline.size)
    }

    @Test
    fun `dynamic curve remains monotonic and bounded by target peak`() {
        val window = parsed(0).windows.single()
        val mapped = (0..120).map { HdrColorMath.applyHdr10PlusToneMapping(it * 10.0, window, 600.0) }

        assertTrue(mapped.all { it in 0.0..600.0 })
        assertTrue(mapped.zipWithNext().all { (left, right) -> right + 1e-7 >= left })
    }

    @Test
    fun `renderer tone curve preserves timestamp and samples the CPU reference OOTF`() {
        val metadata = parsed(timestampUs = 41_708L)
        val curve = requireNotNull(metadata.toToneCurve(displayPeakNits = 600.0))

        assertEquals(41_708L, curve.presentationTimeUs)
        assertEquals(HDR10_PLUS_TONE_CURVE_SAMPLE_COUNT, curve.normalizedOutputLuminance.size)
        assertTrue(curve.sourcePeakNits >= 600f)
        assertTrue(curve.normalizedOutputLuminance.all { it in 0f..0.06f })
        assertTrue(
            (1 until curve.normalizedOutputLuminance.size).all { index ->
                curve.normalizedOutputLuminance[index] + 1e-7f >=
                    curve.normalizedOutputLuminance[index - 1]
            },
        )

        curve.normalizedOutputLuminance.forEachIndexed { index, sampledOutput ->
            val inputNits = curve.sourcePeakNits * index / (HDR10_PLUS_TONE_CURVE_SAMPLE_COUNT - 1)
            val expected =
                HdrColorMath.applyHdr10PlusToneMapping(inputNits.toDouble(), metadata, 600.0) /
                    HDR10_PLUS_PQ_MAX_LUMINANCE_NITS
            assertEquals(expected, sampledOutput.toDouble(), absoluteTolerance = 1e-7)
        }
    }

    @Test
    fun `MaxSCL converts from normalized linear units to a BT2020 luminance estimate`() {
        val peak = HdrColorMath.estimateHdr10PlusScenePeakNits(parsed(0).windows.single())

        assertEquals(1_079.66, requireNotNull(peak), absoluteTolerance = 1e-9)
    }

    @Test
    fun `ST 2094-40 anchors are relative to the post-knee segment in linear luminance`() {
        val identityWindow =
            profileBWindow(
                kneePointX = 1_024,
                kneePointY = 1_024,
                anchors = listOf(341, 682),
            )
        val compressedWindow =
            profileBWindow(
                kneePointX = 1_024,
                kneePointY = 2_048,
                anchors = listOf(512),
            )

        assertEquals(
            500.0,
            HdrColorMath.applyHdr10PlusToneMapping(500.0, identityWindow, 1_000.0),
            absoluteTolerance = 1e-9,
        )
        assertEquals(
            400.09770927160935,
            HdrColorMath.applyHdr10PlusToneMapping(500.0, compressedWindow, 600.0),
            absoluteTolerance = 1e-9,
        )
    }

    @Test
    fun `ST 2094-40 reference OOTF adapts to a dimmer physical display`() {
        val metadata =
            Hdr10PlusMetadata(
                timestampUs = 0,
                applicationIdentifier = 4,
                applicationVersion = 1,
                targetedSystemDisplayMaximumLuminance = 1_000,
                targetedSystemDisplayActualPeakLuminance = null,
                masteringDisplayActualPeakLuminance = null,
                windows =
                    listOf(
                        profileBWindow(
                            kneePointX = 1_024,
                            kneePointY = 2_048,
                            anchors = listOf(512),
                        ),
                    ),
            )

        assertEquals(
            387.8068221725017,
            HdrColorMath.applyHdr10PlusToneMapping(500.0, metadata, displayPeakNits = 600.0),
            absoluteTolerance = 1e-9,
        )
    }

    private fun parsed(timestampUs: Long): Hdr10PlusMetadata =
        assertIs<Hdr10PlusParseResult.Success>(
            Hdr10PlusMetadataParser.parse(singleWindowPayload(), timestampUs),
        ).metadata

    private fun profileBWindow(
        kneePointX: Int,
        kneePointY: Int,
        anchors: List<Int>,
    ): Hdr10PlusWindowMetadata =
        Hdr10PlusWindowMetadata(
            maxScl = listOf(10_000, 10_000, 10_000),
            averageMaxRgb = 2_000,
            distributionPercentiles = emptyList(),
            fractionBrightPixels = 0,
            toneMapping =
                Hdr10PlusToneMapping(
                    kneePointX = kneePointX,
                    kneePointY = kneePointY,
                    bezierCurveAnchors = anchors,
                ),
        )

    private fun singleWindowPayload(
        colorSaturationWeight: Int? = null,
        bezierAnchors: List<Int> = listOf(320, 700),
    ): ByteArray =
        BitWriter()
            .write(0xb5, 8)
            .write(0x003c, 16)
            .write(0x0001, 16)
            .write(4, 8)
            .write(1, 8)
            .write(1, 2)
            .write(1_000, 27)
            .write(0, 1)
            .write(10_000, 17)
            .write(11_000, 17)
            .write(12_000, 17)
            .write(4_000, 17)
            .write(9, 4)
            .writePercentiles()
            .write(64, 10)
            .write(0, 1)
            .write(1, 1)
            .write(1_024, 12)
            .write(1_200, 12)
            .write(bezierAnchors.size, 4)
            .apply { bezierAnchors.forEach { write(it, 10) } }
            .write(if (colorSaturationWeight == null) 0 else 1, 1)
            .apply { colorSaturationWeight?.let { write(it, 6) } }
            .toByteArray()

    private class BitWriter {
        private val bits = mutableListOf<Int>()

        fun write(
            value: Int,
            count: Int,
        ): BitWriter =
            apply {
                for (shift in count - 1 downTo 0) bits += (value ushr shift) and 1
            }

        fun writePercentiles(): BitWriter =
            apply {
                listOf(1, 5, 10, 25, 50, 75, 90, 95, 99).forEachIndexed { index, percentage ->
                    write(percentage, 7)
                    write((index + 1) * 1_000, 17)
                }
            }

        fun toByteArray(): ByteArray =
            ByteArray((bits.size + 7) / 8) { byteIndex ->
                var value = 0
                repeat(8) { bitIndex ->
                    value = value shl 1
                    value = value or bits.getOrElse(byteIndex * 8 + bitIndex) { 0 }
                }
                value.toByte()
            }
    }
}
