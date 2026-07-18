package io.github.kdroidfilter.composemediaplayer

import kotlin.math.floor
import kotlin.test.Test
import kotlin.test.assertTrue

class IctcpGamutLut3DAccuracyTest {
    @Test
    fun `default LUT stays close to the exact ICtCp mapper`() {
        val edge = IctcpGamutLut3D.DEFAULT_EDGE
        val lut = IctcpGamutLut3D.defaultRgba32f
        val errors =
            List(SAMPLE_COUNT) { index ->
                val input =
                    LinearRgb(
                        red = fractional(index * 0.7548776662466927 + 0.11),
                        green = fractional(index * 0.5698402909980532 + 0.37),
                        blue = fractional(index * 0.4382892350000000 + 0.73),
                    )
                val exact =
                    HdrColorMath.gamutMapBt2020ToBt709(
                        input,
                        IctcpGamutLut3D.SDR_NOMINAL_PEAK_NITS,
                    )
                val sampled = sampleTrilinear(lut, edge, input)
                maxOf(
                    kotlin.math.abs(exact.red - sampled.red),
                    kotlin.math.abs(exact.green - sampled.green),
                    kotlin.math.abs(exact.blue - sampled.blue),
                )
            }.sorted()

        val mean = errors.average()
        val percentile99 = errors[(errors.lastIndex * 0.99).toInt()]
        assertTrue(mean < MAX_MEAN_ERROR, "Mean LUT error was $mean")
        assertTrue(percentile99 < MAX_PERCENTILE_99_ERROR, "p99 LUT error was $percentile99")
    }

    private fun sampleTrilinear(
        values: FloatArray,
        edge: Int,
        input: LinearRgb,
    ): LinearRgb {
        val x = input.red.coerceIn(0.0, 1.0) * (edge - 1)
        val y = input.green.coerceIn(0.0, 1.0) * (edge - 1)
        val z = input.blue.coerceIn(0.0, 1.0) * (edge - 1)
        val x0 = floor(x).toInt()
        val y0 = floor(y).toInt()
        val z0 = floor(z).toInt()
        val x1 = (x0 + 1).coerceAtMost(edge - 1)
        val y1 = (y0 + 1).coerceAtMost(edge - 1)
        val z1 = (z0 + 1).coerceAtMost(edge - 1)
        val tx = x - x0
        val ty = y - y0
        val tz = z - z0

        fun channel(channel: Int): Double {
            fun at(
                red: Int,
                green: Int,
                blue: Int,
            ): Double =
                values[(((blue * edge + green) * edge + red) * IctcpGamutLut3D.CHANNEL_COUNT) + channel]
                    .toDouble()

            val z0y0 = lerp(at(x0, y0, z0), at(x1, y0, z0), tx)
            val z0y1 = lerp(at(x0, y1, z0), at(x1, y1, z0), tx)
            val z1y0 = lerp(at(x0, y0, z1), at(x1, y0, z1), tx)
            val z1y1 = lerp(at(x0, y1, z1), at(x1, y1, z1), tx)
            return lerp(lerp(z0y0, z0y1, ty), lerp(z1y0, z1y1, ty), tz)
        }

        return LinearRgb(channel(0), channel(1), channel(2))
    }

    private fun fractional(value: Double): Double = value - floor(value)

    private fun lerp(
        first: Double,
        second: Double,
        amount: Double,
    ): Double = first + (second - first) * amount

    private companion object {
        const val SAMPLE_COUNT = 2_000
        const val MAX_MEAN_ERROR = 0.004
        const val MAX_PERCENTILE_99_ERROR = 0.035
    }
}
