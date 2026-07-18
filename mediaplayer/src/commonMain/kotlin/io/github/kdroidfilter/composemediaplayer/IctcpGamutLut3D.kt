package io.github.kdroidfilter.composemediaplayer

/**
 * A renderer-friendly 3D approximation of the reference ICtCp gamut mapper.
 *
 * Red changes fastest, followed by green and blue. Four floats are stored for
 * every texel so the array can be uploaded directly as an RGBA32F texture.
 */
internal object IctcpGamutLut3D {
    const val DEFAULT_EDGE: Int = 33
    const val CHANNEL_COUNT: Int = 4
    const val SDR_NOMINAL_PEAK_NITS: Double = 100.0

    val defaultRgba32f: FloatArray by lazy {
        generateRgba32f(DEFAULT_EDGE, SDR_NOMINAL_PEAK_NITS)
    }

    fun generateRgba32f(
        edge: Int,
        nominalPeakNits: Double,
    ): FloatArray {
        require(edge >= 2) { "A 3D gamut LUT must have at least two samples per axis." }
        require(edge <= MAX_EDGE) { "A 3D gamut LUT edge must not exceed $MAX_EDGE." }
        require(nominalPeakNits.isFinite() && nominalPeakNits > 0.0 && nominalPeakNits <= MAXIMUM_PEAK_NITS) {
            "nominalPeakNits must be finite and in (0, 10000]."
        }
        val values = FloatArray(edge * edge * edge * CHANNEL_COUNT)
        val denominator = (edge - 1).toDouble()
        var outputIndex = 0
        repeat(edge) { blue ->
            repeat(edge) { green ->
                repeat(edge) { red ->
                    val mapped =
                        HdrColorMath.gamutMapBt2020ToBt709(
                            LinearRgb(
                                red = red / denominator,
                                green = green / denominator,
                                blue = blue / denominator,
                            ),
                            nominalPeakNits,
                        )
                    values[outputIndex++] = mapped.red.toFloat()
                    values[outputIndex++] = mapped.green.toFloat()
                    values[outputIndex++] = mapped.blue.toFloat()
                    values[outputIndex++] = 1.0f
                }
            }
        }
        return values
    }

    private const val MAX_EDGE = 65
    private const val MAXIMUM_PEAK_NITS = 10_000.0
}
