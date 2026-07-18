package io.github.kdroidfilter.composemediaplayer

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HdrColorMathTest {
    @Test
    fun `limited and full range luma normalize before HDR transfer`() {
        assertEquals(0.0, HdrColorMath.normalizeLumaCode(64, 10, VideoColorRange.LIMITED))
        assertEquals(1.0, HdrColorMath.normalizeLumaCode(940, 10, VideoColorRange.LIMITED))
        assertEquals(0.5, HdrColorMath.normalizeLumaCode(502, 10, VideoColorRange.LIMITED))
        assertEquals(0.0, HdrColorMath.normalizeLumaCode(0, 10, VideoColorRange.FULL))
        assertEquals(1.0, HdrColorMath.normalizeLumaCode(1023, 10, VideoColorRange.FULL))
    }

    @Test
    fun `PQ normative points round trip in absolute nits`() {
        assertEquals(0.0, HdrColorMath.pqEotf(0.0), absoluteTolerance = 1e-9)
        assertEquals(10_000.0, HdrColorMath.pqEotf(1.0), absoluteTolerance = 1e-5)
        assertEquals(100.0, HdrColorMath.pqEotf(HdrColorMath.pqOetf(100.0)), absoluteTolerance = 1e-6)
        assertEquals(4_000.0, HdrColorMath.pqEotf(HdrColorMath.pqOetf(4_000.0)), absoluteTolerance = 1e-4)
    }

    @Test
    fun `HLG OETF round trips scene linear values`() {
        listOf(0.0, 1.0 / 12.0, 0.18, 0.5, 1.0).forEach { value ->
            assertEquals(
                value,
                HdrColorMath.hlgInverseOetf(HdrColorMath.hlgOetf(value)),
                absoluteTolerance = 1e-9,
            )
        }
    }

    @Test
    fun `SDR and sRGB transfer reference points decode to linear light`() {
        assertEquals(0.018, HdrColorMath.bt709InverseOetf(0.081), absoluteTolerance = 1e-12)
        assertEquals(1.0, HdrColorMath.bt709InverseOetf(1.0), absoluteTolerance = 1e-12)
        assertEquals(0.0031308, HdrColorMath.sRgbEotf(0.04045), absoluteTolerance = 1e-7)
        assertEquals(1.0, HdrColorMath.sRgbEotf(1.0), absoluteTolerance = 1e-12)
    }

    @Test
    fun `source primary matrices preserve neutral and map into BT2020`() {
        val neutral = LinearRgb(0.42, 0.42, 0.42)
        VideoColorPrimaries.entries
            .filterNot { it == VideoColorPrimaries.UNKNOWN }
            .forEach { primaries ->
                val converted = HdrColorMath.linearRgbToBt2020(neutral, primaries)
                assertEquals(neutral.red, converted.red, absoluteTolerance = 1e-7)
                assertEquals(neutral.green, converted.green, absoluteTolerance = 1e-7)
                assertEquals(neutral.blue, converted.blue, absoluteTolerance = 1e-7)
            }

        val p3Red = HdrColorMath.linearRgbToBt2020(LinearRgb(1.0, 0.0, 0.0), VideoColorPrimaries.DISPLAY_P3)
        assertEquals(0.753833034, p3Red.red, absoluteTolerance = 1e-9)
        assertEquals(0.045743849, p3Red.green, absoluteTolerance = 1e-9)
        assertEquals(-0.001210340, p3Red.blue, absoluteTolerance = 1e-9)
    }

    @Test
    fun `BT2390 rolloff is monotonic and bounded by target`() {
        val samples = (0..400).map { it * 10.0 }
        val mapped =
            samples.map {
                HdrColorMath.toneMapPqBt2390(
                    it,
                    sourcePeakNits = 4_000.0,
                    targetPeakNits = 1_000.0,
                )
            }

        assertTrue(mapped.zipWithNext().all { (left, right) -> right + 1e-8 >= left })
        assertTrue(mapped.all { it in 0.0..1_000.0 })
        assertEquals(1_000.0, mapped.last(), absoluteTolerance = 1e-4)
    }

    @Test
    fun `ICtCp round trip preserves linear BT2020`() {
        val input = LinearRgb(0.72, 0.16, 0.03)
        val output = HdrColorMath.iCtCpToBt2020(HdrColorMath.bt2020ToICtCp(input, 1_000.0), 1_000.0)

        assertTrue(abs(input.red - output.red) < 2e-5)
        assertTrue(abs(input.green - output.green) < 2e-5)
        assertTrue(abs(input.blue - output.blue) < 2e-5)
    }

    @Test
    fun `wide BT2020 primary is compressed into BT709 gamut`() {
        val mapped = HdrColorMath.gamutMapBt2020ToBt709(LinearRgb(1.0, 0.0, 0.0), 1_000.0)

        assertTrue(mapped.red in 0.0..1.0)
        assertTrue(mapped.green in 0.0..1.0)
        assertTrue(mapped.blue in 0.0..1.0)
        assertTrue(mapped.red > mapped.green)
    }

    @Test
    fun `dithered quantization lands on requested code grid`() {
        val value = HdrColorMath.quantizeWithTriangularDither(0.501, bitDepth = 10, noiseA = 0.75, noiseB = 0.25)

        assertEquals(kotlin.math.round(value * 1023.0), value * 1023.0, absoluteTolerance = 1e-9)
    }
}
