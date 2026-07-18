@file:Suppress("MagicNumber", "TooManyFunctions")

package io.github.kdroidfilter.composemediaplayer

import androidx.compose.runtime.Immutable
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.round

@Immutable
data class LinearRgb(
    val red: Double,
    val green: Double,
    val blue: Double,
) {
    fun coerceIn(
        minimum: Double = 0.0,
        maximum: Double = 1.0,
    ): LinearRgb =
        LinearRgb(
            red = red.coerceIn(minimum, maximum),
            green = green.coerceIn(minimum, maximum),
            blue = blue.coerceIn(minimum, maximum),
        )

    internal fun isInUnitGamut(epsilon: Double = COLOR_EPSILON): Boolean =
        red in -epsilon..(1.0 + epsilon) &&
            green in -epsilon..(1.0 + epsilon) &&
            blue in -epsilon..(1.0 + epsilon)
}

@Immutable
data class ICtCpColor(
    val intensity: Double,
    val tritan: Double,
    val protan: Double,
)

/**
 * Reference color implementation used by platform renderers and CPU/GPU conformance tests.
 *
 * Linear RGB values use BT.2020 primaries. PQ functions use absolute cd/m²; HLG scene values and SDR output are
 * relative. Renderers should perform intermediate work in FP16 or better and quantize only at the final surface.
 */
object HdrColorMath {
    /** Decodes a BT.709-style SDR transfer value into relative linear light. */
    fun bt709InverseOetf(encoded: Double): Double {
        val signal = encoded.coerceAtLeast(0.0)
        return if (signal <= 0.081) signal / 4.5 else ((signal + 0.099) / 1.099).pow(1.0 / 0.45)
    }

    /** Decodes an IEC 61966-2-1 sRGB value into relative linear light. */
    fun sRgbEotf(encoded: Double): Double {
        val signal = encoded.coerceAtLeast(0.0)
        return if (signal <= 0.04045) signal / 12.92 else ((signal + 0.055) / 1.055).pow(2.4)
    }

    /** Converts supported D65 source primaries into the shared linear BT.2020 working space. */
    fun linearRgbToBt2020(
        rgb: LinearRgb,
        primaries: VideoColorPrimaries,
    ): LinearRgb =
        when (primaries) {
            VideoColorPrimaries.BT2020 -> rgb
            VideoColorPrimaries.BT709 ->
                rgb.transform(
                    0.627403896,
                    0.329283038,
                    0.043313066,
                    0.069097289,
                    0.919540395,
                    0.011362316,
                    0.016391439,
                    0.088013308,
                    0.895595253,
                )
            VideoColorPrimaries.DISPLAY_P3 ->
                rgb.transform(
                    0.753833034,
                    0.198597369,
                    0.047569597,
                    0.045743849,
                    0.941777220,
                    0.012478931,
                    -0.001210340,
                    0.017601717,
                    0.983608623,
                )
            VideoColorPrimaries.BT601_525 ->
                rgb.transform(
                    0.595254206,
                    0.349313920,
                    0.055431874,
                    0.081243662,
                    0.891503296,
                    0.027253043,
                    0.015512341,
                    0.081911642,
                    0.902576017,
                )
            VideoColorPrimaries.BT601_625 ->
                rgb.transform(
                    0.655036777,
                    0.302160965,
                    0.042802258,
                    0.072140556,
                    0.916631129,
                    0.011228315,
                    0.017113370,
                    0.097853470,
                    0.885033160,
                )
            VideoColorPrimaries.UNKNOWN -> error("Known source primaries are required for explicit color conversion.")
        }

    /** Normalizes a decoded luma code before transfer-function processing. */
    internal fun normalizeLumaCode(
        codeValue: Int,
        bitDepth: Int,
        range: VideoColorRange,
    ): Double {
        require(bitDepth in MIN_VIDEO_BIT_DEPTH..MAX_VIDEO_BIT_DEPTH) {
            "bitDepth must be between $MIN_VIDEO_BIT_DEPTH and $MAX_VIDEO_BIT_DEPTH."
        }
        require(range == VideoColorRange.LIMITED || range == VideoColorRange.FULL) {
            "A known limited or full video range is required."
        }
        val maximumCode = (1 shl bitDepth) - 1
        require(codeValue in 0..maximumCode) { "codeValue must fit in bitDepth." }
        if (range == VideoColorRange.FULL) return codeValue.toDouble() / maximumCode

        val scale = 1 shl (bitDepth - MIN_VIDEO_BIT_DEPTH)
        val blackCode = LIMITED_RANGE_BLACK_8_BIT * scale
        val whiteCode = LIMITED_RANGE_WHITE_8_BIT * scale
        return ((codeValue - blackCode).toDouble() / (whiteCode - blackCode)).coerceIn(0.0, 1.0)
    }

    /** SMPTE ST 2084 / BT.2100 inverse perceptual quantizer. */
    fun pqEotf(encoded: Double): Double {
        val signal = encoded.coerceIn(0.0, 1.0)
        val powered = signal.pow(1.0 / PQ_M2)
        val numerator = max(powered - PQ_C1, 0.0)
        val denominator = PQ_C2 - PQ_C3 * powered
        if (denominator <= 0.0) return PQ_MAX_NITS
        return (numerator / denominator).pow(1.0 / PQ_M1) * PQ_MAX_NITS
    }

    /** SMPTE ST 2084 / BT.2100 perceptual quantizer. */
    fun pqOetf(luminanceNits: Double): Double {
        val normalized = (luminanceNits.coerceIn(0.0, PQ_MAX_NITS) / PQ_MAX_NITS).pow(PQ_M1)
        return ((PQ_C1 + PQ_C2 * normalized) / (1.0 + PQ_C3 * normalized)).pow(PQ_M2)
    }

    /** BT.2100 HLG inverse OETF, producing scene-linear light. */
    fun hlgInverseOetf(encoded: Double): Double {
        val signal = encoded.coerceAtLeast(0.0)
        return if (signal <= 0.5) {
            signal * signal / 3.0
        } else {
            (exp((signal - HLG_C) / HLG_A) + HLG_B) / 12.0
        }
    }

    /** BT.2100 HLG OETF from scene-linear light. */
    fun hlgOetf(sceneLinear: Double): Double {
        val light = sceneLinear.coerceAtLeast(0.0)
        return if (light <= 1.0 / 12.0) {
            kotlin.math.sqrt(3.0 * light)
        } else {
            HLG_A * ln(12.0 * light - HLG_B) + HLG_C
        }
    }

    /**
     * Applies the BT.2100 HLG OOTF. The system gamma includes the BT.2390 ambient-light compensation term.
     */
    fun hlgSignalToNits(
        encoded: Double,
        displayPeakNits: Double = 1_000.0,
        ambientNits: Double = 5.0,
    ): Double {
        require(displayPeakNits > 0.0 && displayPeakNits.isFinite()) { "displayPeakNits must be finite and positive." }
        require(ambientNits > 0.0 && ambientNits.isFinite()) { "ambientNits must be finite and positive." }
        val systemGamma =
            1.2 +
                0.42 * log10(displayPeakNits / 1_000.0) -
                0.076 * log10(ambientNits / 5.0)
        return displayPeakNits * hlgInverseOetf(encoded).pow(systemGamma.coerceAtLeast(0.0))
    }

    /**
     * BT.2390-style EETF in the PQ domain. Values below the knee retain absolute luminance; highlights roll smoothly
     * to the target peak with a zero-slope Hermite endpoint.
     */
    fun toneMapPqBt2390(
        luminanceNits: Double,
        sourcePeakNits: Double,
        targetPeakNits: Double,
    ): Double {
        require(sourcePeakNits > 0.0 && sourcePeakNits.isFinite()) { "sourcePeakNits must be finite and positive." }
        require(targetPeakNits > 0.0 && targetPeakNits.isFinite()) { "targetPeakNits must be finite and positive." }
        if (targetPeakNits >= sourcePeakNits) return luminanceNits.coerceIn(0.0, sourcePeakNits)

        val sourcePeakCode = pqOetf(sourcePeakNits)
        val targetPeakCode = pqOetf(targetPeakNits)
        val normalizedTarget = (targetPeakCode / sourcePeakCode).coerceIn(0.0, 1.0)
        val knee = (1.5 * normalizedTarget - 0.5).coerceIn(0.0, 1.0)
        val normalizedInput = (pqOetf(luminanceNits) / sourcePeakCode).coerceIn(0.0, 1.0)
        val normalizedOutput =
            if (normalizedInput <= knee || knee >= 1.0) {
                normalizedInput
            } else {
                val t = ((normalizedInput - knee) / (1.0 - knee)).coerceIn(0.0, 1.0)
                hermite(
                    t = t,
                    start = knee,
                    startSlope = 1.0 - knee,
                    end = normalizedTarget,
                    endSlope = 0.0,
                )
            }
        return pqEotf((normalizedOutput * sourcePeakCode).coerceIn(0.0, 1.0))
            .coerceIn(0.0, targetPeakNits)
    }

    /** BT.2446-compatible HLG-to-SDR luminance mapping, returned as relative linear SDR light. */
    fun toneMapHlgBt2446(
        encoded: Double,
        sourcePeakNits: Double = 1_000.0,
        sdrWhiteNits: Double = 100.0,
        ambientNits: Double = 5.0,
    ): Double {
        require(sdrWhiteNits > 0.0 && sdrWhiteNits.isFinite()) { "sdrWhiteNits must be finite and positive." }
        val sourceNits = hlgSignalToNits(encoded, sourcePeakNits, ambientNits)
        return (toneMapPqBt2390(sourceNits, sourcePeakNits, sdrWhiteNits) / sdrWhiteNits)
            .coerceIn(0.0, 1.0)
    }

    /**
     * Applies a Profile A fallback or the Profile B ST 2094-40 Annex B OOTF to one luminance sample.
     *
     * HDR10+ MaxSCL values are normalized linear-light measurements (1.0 represents 10,000 nits),
     * while the Bezier curve operates in normalized linear luminance rather than in PQ code space.
     * This overload treats [targetPeakNits] as both the mastering OOTF target and the actual output
     * peak. Use the metadata overload when adapting a Profile B curve to a different display peak.
     */
    fun applyHdr10PlusToneMapping(
        luminanceNits: Double,
        window: Hdr10PlusWindowMetadata,
        targetPeakNits: Double,
    ): Double =
        applyHdr10PlusToneMapping(
            luminanceNits = luminanceNits,
            window = window,
            displayPeakNits = targetPeakNits,
            metadataTargetPeakNits = targetPeakNits,
        )

    /** Applies ST 2094-40 and adapts its reference OOTF to the active display peak. */
    fun applyHdr10PlusToneMapping(
        luminanceNits: Double,
        metadata: Hdr10PlusMetadata,
        displayPeakNits: Double,
    ): Double {
        val window =
            metadata.windows.singleOrNull()
                ?: throw IllegalArgumentException("HDR10+ Application 4 Version 1 requires exactly one window.")
        return applyHdr10PlusToneMapping(
            luminanceNits = luminanceNits,
            window = window,
            displayPeakNits = displayPeakNits,
            metadataTargetPeakNits =
                metadata.targetedSystemDisplayMaximumLuminance
                    .toDouble()
                    .takeIf { it > 0.0 } ?: displayPeakNits,
        )
    }

    internal fun estimateHdr10PlusScenePeakNits(window: Hdr10PlusWindowMetadata): Double? {
        val histogramFallback =
            window.distributionPercentiles
                .maxOfOrNull(Hdr10PlusDistributionPercentile::percentileMaxRgb)
                ?.takeIf { it > 0 }
        val channels =
            List(3) { index ->
                window.maxScl[index]
                    .takeIf { it > 0 }
                    ?: histogramFallback
                    ?: 0
            }
        if (channels.all { it == 0 }) return null
        return (
            BT2020_LUMA_RED * channels[0] +
                BT2020_LUMA_GREEN * channels[1] +
                BT2020_LUMA_BLUE * channels[2]
        ) / HDR10_PLUS_LUMINANCE_SCALE
    }

    private fun applyHdr10PlusToneMapping(
        luminanceNits: Double,
        window: Hdr10PlusWindowMetadata,
        displayPeakNits: Double,
        metadataTargetPeakNits: Double,
    ): Double {
        require(displayPeakNits > 0.0 && displayPeakNits.isFinite()) {
            "displayPeakNits must be finite and positive."
        }
        require(metadataTargetPeakNits > 0.0 && metadataTargetPeakNits.isFinite()) {
            "metadataTargetPeakNits must be finite and positive."
        }
        val minimumSourcePeak = minOf(displayPeakNits, metadataTargetPeakNits)
        val sourcePeakNits =
            (estimateHdr10PlusScenePeakNits(window) ?: max(displayPeakNits, metadataTargetPeakNits))
                .coerceAtLeast(minimumSourcePeak)
                .coerceIn(1.0, PQ_MAX_NITS)
        val outputPeakNits = minOf(displayPeakNits, sourcePeakNits)
        val toneMapping =
            window.toneMapping
                ?: return toneMapPqBt2390(
                    luminanceNits = luminanceNits,
                    sourcePeakNits = sourcePeakNits.coerceAtLeast(outputPeakNits),
                    targetPeakNits = outputPeakNits,
                )
        var kneeX = (toneMapping.kneePointX / HDR10_PLUS_KNEE_DENOMINATOR).coerceIn(0.0, 1.0)
        var kneeY = (toneMapping.kneePointY / HDR10_PLUS_KNEE_DENOMINATOR).coerceIn(0.0, 1.0)
        val controlPoints =
            buildList {
                add(0.0)
                toneMapping.bezierCurveAnchors.forEach {
                    add((it / HDR10_PLUS_BEZIER_DENOMINATOR).coerceIn(0.0, 1.0))
                }
                add(1.0)
            }.toMutableList()
        val degree = controlPoints.lastIndex
        val referenceTargetNits = metadataTargetPeakNits.coerceIn(1.0, sourcePeakNits)

        if (outputPeakNits < referenceTargetNits) {
            val adaptation = (outputPeakNits / referenceTargetNits).coerceIn(0.0, 1.0)
            kneeX *= adaptation
            kneeY *= adaptation
            val beta =
                if (kneeX >= 1.0) {
                    Double.POSITIVE_INFINITY
                } else {
                    degree * kneeX / (1.0 - kneeX)
                }
            val slopeBound = if (beta.isFinite()) beta / (beta + 1.0) else 1.0
            val linearKnee = minOf(kneeX * sourcePeakNits / outputPeakNits, slopeBound)
            kneeY = mix(linearKnee, kneeY, adaptation)
            for (index in 2..degree) {
                controlPoints[index] = mix(1.0, controlPoints[index], adaptation)
            }
            controlPoints[1] =
                mix(
                    st2094Intercept(degree, kneeX, kneeY),
                    controlPoints[1],
                    adaptation,
                )
        } else if (outputPeakNits > referenceTargetNits && sourcePeakNits > referenceTargetNits) {
            val adaptation =
                (
                    1.0 -
                        (outputPeakNits - referenceTargetNits) /
                        (sourcePeakNits - referenceTargetNits)
                ).coerceIn(0.0, 1.0).pow(HDR10_PLUS_BRIGHT_DISPLAY_EXPONENT)
            kneeY *= referenceTargetNits / outputPeakNits
            val linearKnee = kneeX * outputPeakNits / sourcePeakNits
            kneeY = mix(linearKnee, kneeY, adaptation)
            for (index in 2 until degree) {
                controlPoints[index] = mix(index.toDouble() / degree, controlPoints[index], adaptation)
            }
            controlPoints[1] =
                mix(
                    st2094Intercept(degree, kneeX, kneeY),
                    controlPoints[1],
                    adaptation,
                )
        }

        val input = (luminanceNits / sourcePeakNits).coerceIn(0.0, 1.0)
        val output =
            if (input <= kneeX && kneeX > 0.0) {
                input * kneeY / kneeX
            } else if (kneeX >= 1.0) {
                input
            } else {
                val t = ((input - kneeX) / (1.0 - kneeX)).coerceIn(0.0, 1.0)
                kneeY + (1.0 - kneeY) * bezier(controlPoints, t)
            }
        return (output.coerceIn(0.0, 1.0) * outputPeakNits).coerceIn(0.0, outputPeakNits)
    }

    private fun st2094Intercept(
        degree: Int,
        kneeX: Double,
        kneeY: Double,
    ): Double {
        if (kneeX <= 0.0 || kneeY >= 1.0) return 1.0 / degree
        val slope = kneeY / kneeX * (1.0 - kneeX) / (1.0 - kneeY)
        return minOf(slope / degree, 1.0)
    }

    private fun mix(
        left: Double,
        right: Double,
        amount: Double,
    ): Double = left * (1.0 - amount) + right * amount

    /** Converts linear BT.2020 to ICtCp using the BT.2100 PQ path. */
    fun bt2020ToICtCp(
        rgb: LinearRgb,
        nominalPeakNits: Double = 10_000.0,
    ): ICtCpColor {
        requireNominalPeak(nominalPeakNits)
        val l = matrixRow(rgb, 1688.0, 2146.0, 262.0) / 4096.0
        val m = matrixRow(rgb, 683.0, 2951.0, 462.0) / 4096.0
        val s = matrixRow(rgb, 99.0, 309.0, 3688.0) / 4096.0
        val lp = pqOetf(l.coerceAtLeast(0.0) * nominalPeakNits)
        val mp = pqOetf(m.coerceAtLeast(0.0) * nominalPeakNits)
        val sp = pqOetf(s.coerceAtLeast(0.0) * nominalPeakNits)
        return ICtCpColor(
            intensity = 0.5 * lp + 0.5 * mp,
            tritan = (6610.0 * lp - 13613.0 * mp + 7003.0 * sp) / 4096.0,
            protan = (17933.0 * lp - 17390.0 * mp - 543.0 * sp) / 4096.0,
        )
    }

    /** Converts PQ ICtCp back to linear BT.2020. */
    fun iCtCpToBt2020(
        color: ICtCpColor,
        nominalPeakNits: Double = 10_000.0,
    ): LinearRgb {
        requireNominalPeak(nominalPeakNits)
        val lp = color.intensity + 0.008609037 * color.tritan + 0.111029625 * color.protan
        val mp = color.intensity - 0.008609037 * color.tritan - 0.111029625 * color.protan
        val sp = color.intensity + 0.560031336 * color.tritan - 0.320627175 * color.protan
        val l = pqEotf(lp.coerceIn(0.0, 1.0)) / nominalPeakNits
        val m = pqEotf(mp.coerceIn(0.0, 1.0)) / nominalPeakNits
        val s = pqEotf(sp.coerceIn(0.0, 1.0)) / nominalPeakNits
        return LinearRgb(
            red = (3.43660669 * l - 2.50645212 * m + 0.06984542 * s),
            green = (-0.79132956 * l + 1.98360045 * m - 0.19227090 * s),
            blue = (-0.02594990 * l - 0.09891371 * m + 1.12486361 * s),
        )
    }

    /**
     * Maps linear BT.2020 into the BT.709 gamut by reducing ICtCp chroma while preserving intensity and hue.
     */
    fun gamutMapBt2020ToBt709(
        rgb: LinearRgb,
        nominalPeakNits: Double = 1_000.0,
    ): LinearRgb {
        requireNominalPeak(nominalPeakNits)
        val direct = linearBt2020ToBt709(rgb)
        if (direct.isInUnitGamut()) return direct.coerceIn()

        val source = bt2020ToICtCp(rgb, nominalPeakNits)
        var low = 0.0
        var high = 1.0
        var best = linearBt2020ToBt709(iCtCpToBt2020(source.copy(tritan = 0.0, protan = 0.0), nominalPeakNits))
        repeat(GAMUT_SEARCH_STEPS) {
            val scale = (low + high) * 0.5
            val candidate =
                linearBt2020ToBt709(
                    iCtCpToBt2020(
                        source.copy(tritan = source.tritan * scale, protan = source.protan * scale),
                        nominalPeakNits,
                    ),
                )
            if (candidate.isInUnitGamut()) {
                best = candidate
                low = scale
            } else {
                high = scale
            }
        }
        return best.coerceIn()
    }

    /** Quantizes a normalized channel with triangular-PDF dither. Noise samples must be in 0..1. */
    fun quantizeWithTriangularDither(
        value: Double,
        bitDepth: Int,
        noiseA: Double,
        noiseB: Double,
    ): Double {
        require(bitDepth in MIN_OUTPUT_BIT_DEPTH..MAX_OUTPUT_BIT_DEPTH) {
            "bitDepth must be between $MIN_OUTPUT_BIT_DEPTH and $MAX_OUTPUT_BIT_DEPTH."
        }
        require(noiseA in 0.0..1.0 && noiseB in 0.0..1.0) { "Dither samples must be in 0..1." }
        val levels = ((1L shl bitDepth) - 1L).toDouble()
        val dithered = value.coerceIn(0.0, 1.0) + (noiseA - noiseB) / levels
        return (round(dithered.coerceIn(0.0, 1.0) * levels) / levels).coerceIn(0.0, 1.0)
    }

    private fun linearBt2020ToBt709(rgb: LinearRgb): LinearRgb =
        LinearRgb(
            red = 1.660491 * rgb.red - 0.587641 * rgb.green - 0.072850 * rgb.blue,
            green = -0.124550 * rgb.red + 1.132900 * rgb.green - 0.008349 * rgb.blue,
            blue = -0.018151 * rgb.red - 0.100579 * rgb.green + 1.118730 * rgb.blue,
        )

    private fun LinearRgb.transform(
        redRed: Double,
        redGreen: Double,
        redBlue: Double,
        greenRed: Double,
        greenGreen: Double,
        greenBlue: Double,
        blueRed: Double,
        blueGreen: Double,
        blueBlue: Double,
    ): LinearRgb =
        LinearRgb(
            red = redRed * red + redGreen * green + redBlue * blue,
            green = greenRed * red + greenGreen * green + greenBlue * blue,
            blue = blueRed * red + blueGreen * green + blueBlue * blue,
        )

    private fun matrixRow(
        rgb: LinearRgb,
        red: Double,
        green: Double,
        blue: Double,
    ): Double = red * rgb.red + green * rgb.green + blue * rgb.blue

    private fun hermite(
        t: Double,
        start: Double,
        startSlope: Double,
        end: Double,
        endSlope: Double,
    ): Double {
        val t2 = t * t
        val t3 = t2 * t
        return (2.0 * t3 - 3.0 * t2 + 1.0) * start +
            (t3 - 2.0 * t2 + t) * startSlope +
            (-2.0 * t3 + 3.0 * t2) * end +
            (t3 - t2) * endSlope
    }

    private fun bezier(
        controlPoints: List<Double>,
        t: Double,
    ): Double {
        if (controlPoints.size == 1) return controlPoints.first()
        val degree = controlPoints.lastIndex
        var result = 0.0
        for (index in 0..degree) {
            result +=
                binomial(degree, index) *
                (1.0 - t).pow(degree - index) *
                t.pow(index) *
                controlPoints[index]
        }
        return result
    }

    private fun binomial(
        n: Int,
        k: Int,
    ): Double {
        val reducedK = minOf(k, n - k)
        var result = 1.0
        for (index in 1..reducedK) result = result * (n - reducedK + index) / index
        return result
    }

    private fun requireNominalPeak(value: Double) {
        require(value > 0.0 && value <= PQ_MAX_NITS && value.isFinite()) {
            "nominalPeakNits must be finite and in (0, $PQ_MAX_NITS]."
        }
    }
}

private const val PQ_M1 = 2610.0 / 16384.0
private const val PQ_M2 = 2523.0 / 32.0
private const val PQ_C1 = 3424.0 / 4096.0
private const val PQ_C2 = 2413.0 / 128.0
private const val PQ_C3 = 2392.0 / 128.0
private const val PQ_MAX_NITS = 10_000.0
private const val HDR10_PLUS_LUMINANCE_SCALE = 10.0
private const val HDR10_PLUS_KNEE_DENOMINATOR = 4095.0
private const val HDR10_PLUS_BEZIER_DENOMINATOR = 1023.0
private const val HDR10_PLUS_BRIGHT_DISPLAY_EXPONENT = 1.4
private const val BT2020_LUMA_RED = 0.2627
private const val BT2020_LUMA_GREEN = 0.6780
private const val BT2020_LUMA_BLUE = 0.0593
private const val HLG_A = 0.17883277
private const val HLG_B = 1.0 - 4.0 * HLG_A
private val HLG_C = 0.5 - HLG_A * ln(4.0 * HLG_A)
private const val GAMUT_SEARCH_STEPS = 16
private const val MIN_OUTPUT_BIT_DEPTH = 2
private const val MAX_OUTPUT_BIT_DEPTH = 16
private const val MIN_VIDEO_BIT_DEPTH = 8
private const val MAX_VIDEO_BIT_DEPTH = 16
private const val LIMITED_RANGE_BLACK_8_BIT = 16
private const val LIMITED_RANGE_WHITE_8_BIT = 235
private const val COLOR_EPSILON = 1e-7
