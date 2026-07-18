@file:Suppress("MagicNumber", "TooManyFunctions")

package io.github.kdroidfilter.composemediaplayer

import androidx.compose.runtime.Immutable
import kotlin.math.abs

@Immutable
data class Hdr10PlusPeakLuminanceGrid(
    val rows: Int,
    val columns: Int,
    val values: List<Int>,
) {
    init {
        require(rows in 2..25 && columns in 2..25) { "HDR10+ peak-luminance grid dimensions must be in 2..25." }
        require(values.size == rows * columns) { "HDR10+ peak-luminance grid size does not match its dimensions." }
        require(values.all { it in 0..15 }) { "HDR10+ peak-luminance grid values must be 4-bit values." }
    }
}

@Immutable
data class Hdr10PlusWindowGeometry(
    val upperLeftX: Int,
    val upperLeftY: Int,
    val lowerRightX: Int,
    val lowerRightY: Int,
    val ellipseCenterX: Int,
    val ellipseCenterY: Int,
    val rotationAngle: Int,
    val internalEllipseSemiMajorAxis: Int,
    val externalEllipseSemiMajorAxis: Int,
    val externalEllipseSemiMinorAxis: Int,
    val overlapProcessOption: Int,
)

@Immutable
data class Hdr10PlusDistributionPercentile(
    val percentage: Int,
    val percentileMaxRgb: Int,
)

@Immutable
data class Hdr10PlusToneMapping(
    val kneePointX: Int,
    val kneePointY: Int,
    val bezierCurveAnchors: List<Int>,
)

@Immutable
data class Hdr10PlusWindowMetadata(
    val geometry: Hdr10PlusWindowGeometry? = null,
    /** Raw normalized-linear values in 0.00001 steps; divide by 10 for the corresponding nit estimate. */
    val maxScl: List<Int>,
    /** Raw normalized-linear value in 0.00001 steps; divide by 10 for the corresponding nit estimate. */
    val averageMaxRgb: Int,
    val distributionPercentiles: List<Hdr10PlusDistributionPercentile>,
    val fractionBrightPixels: Int,
    val toneMapping: Hdr10PlusToneMapping? = null,
    val colorSaturationWeight: Int? = null,
) {
    init {
        require(maxScl.size == 3) { "HDR10+ MaxSCL must contain three components." }
    }
}

/** One SMPTE ST 2094-40 metadata unit associated with a decoded frame timestamp. */
@Immutable
data class Hdr10PlusMetadata(
    val timestampUs: Long,
    val applicationIdentifier: Int,
    val applicationVersion: Int,
    /** Nominal OOTF target peak in whole cd/m² (nits), as corrected by ST 2094-40:2016 Amd 1:2019. */
    val targetedSystemDisplayMaximumLuminance: Int,
    val targetedSystemDisplayActualPeakLuminance: Hdr10PlusPeakLuminanceGrid?,
    val masteringDisplayActualPeakLuminance: Hdr10PlusPeakLuminanceGrid?,
    val windows: List<Hdr10PlusWindowMetadata>,
)

sealed interface Hdr10PlusParseResult {
    @Immutable
    data class Success(
        val metadata: Hdr10PlusMetadata,
    ) : Hdr10PlusParseResult

    @Immutable
    data class Invalid(
        val reason: String,
    ) : Hdr10PlusParseResult
}

/** Parser for HDR10+ User Data Registered ITU-T T.35 payloads carrying SMPTE ST 2094-40. */
object Hdr10PlusMetadataParser {
    @Suppress("ReturnCount")
    fun parse(
        payload: ByteArray,
        timestampUs: Long,
    ): Hdr10PlusParseResult {
        if (timestampUs < 0L) return Hdr10PlusParseResult.Invalid("timestampUs must not be negative.")
        val reader = BitReader(payload)
        return try {
            if (reader.readBits(8) != ITU_T_T35_COUNTRY_CODE) {
                return Hdr10PlusParseResult.Invalid("Not an HDR10+ ITU-T T.35 country code.")
            }
            if (reader.readBits(16) != HDR10_PLUS_PROVIDER_CODE) {
                return Hdr10PlusParseResult.Invalid("Unexpected HDR10+ terminal provider code.")
            }
            if (reader.readBits(16) != HDR10_PLUS_PROVIDER_ORIENTED_CODE) {
                return Hdr10PlusParseResult.Invalid("Unexpected HDR10+ provider-oriented code.")
            }
            val applicationIdentifier = reader.readBits(8)
            if (applicationIdentifier != HDR10_PLUS_APPLICATION_IDENTIFIER) {
                return Hdr10PlusParseResult.Invalid("Unsupported HDR10+ application identifier $applicationIdentifier.")
            }
            val applicationVersion = reader.readBits(8)
            if (applicationVersion != HDR10_PLUS_APPLICATION_VERSION) {
                return Hdr10PlusParseResult.Invalid("Unsupported HDR10+ application version $applicationVersion.")
            }
            val windowCount = reader.readBits(2)
            if (windowCount != HDR10_PLUS_APPLICATION_VERSION_1_WINDOW_COUNT) {
                return Hdr10PlusParseResult.Invalid(
                    "HDR10+ Application 4 Version 1 requires exactly one processing window.",
                )
            }

            val geometries = MutableList<Hdr10PlusWindowGeometry?>(windowCount) { null }
            for (windowIndex in 1 until windowCount) {
                geometries[windowIndex] =
                    Hdr10PlusWindowGeometry(
                        upperLeftX = reader.readBits(16),
                        upperLeftY = reader.readBits(16),
                        lowerRightX = reader.readBits(16),
                        lowerRightY = reader.readBits(16),
                        ellipseCenterX = reader.readBits(16),
                        ellipseCenterY = reader.readBits(16),
                        rotationAngle = reader.readBits(8),
                        internalEllipseSemiMajorAxis = reader.readBits(16),
                        externalEllipseSemiMajorAxis = reader.readBits(16),
                        externalEllipseSemiMinorAxis = reader.readBits(16),
                        overlapProcessOption = reader.readBits(1),
                    )
            }

            val targetedMaximumLuminance = reader.readBits(27)
            val targetedPeakGrid = if (reader.readFlag()) reader.readPeakLuminanceGrid() else null
            val windows =
                MutableList(windowCount) { index ->
                    val maxScl = List(3) { reader.readBits(17) }
                    val averageMaxRgb = reader.readBits(17)
                    val percentileCount = reader.readBits(4)
                    val percentiles =
                        List(percentileCount) {
                            Hdr10PlusDistributionPercentile(
                                percentage = reader.readBits(7),
                                percentileMaxRgb = reader.readBits(17),
                            )
                        }
                    Hdr10PlusWindowMetadata(
                        geometry = geometries[index],
                        maxScl = maxScl,
                        averageMaxRgb = averageMaxRgb,
                        distributionPercentiles = percentiles,
                        fractionBrightPixels = reader.readBits(10),
                    )
                }

            val masteringPeakGrid = if (reader.readFlag()) reader.readPeakLuminanceGrid() else null
            for (windowIndex in 0 until windowCount) {
                val toneMapping =
                    if (reader.readFlag()) {
                        val kneeX = reader.readBits(12)
                        val kneeY = reader.readBits(12)
                        val anchorCount = reader.readBits(4)
                        Hdr10PlusToneMapping(
                            kneePointX = kneeX,
                            kneePointY = kneeY,
                            bezierCurveAnchors = List(anchorCount) { reader.readBits(10) },
                        )
                    } else {
                        null
                    }
                val saturationWeight = if (reader.readFlag()) reader.readBits(6) else null
                windows[windowIndex] =
                    windows[windowIndex].copy(
                        toneMapping = toneMapping,
                        colorSaturationWeight = saturationWeight,
                    )
            }

            val metadata =
                Hdr10PlusMetadata(
                    timestampUs = timestampUs,
                    applicationIdentifier = applicationIdentifier,
                    applicationVersion = applicationVersion,
                    targetedSystemDisplayMaximumLuminance = targetedMaximumLuminance,
                    targetedSystemDisplayActualPeakLuminance = targetedPeakGrid,
                    masteringDisplayActualPeakLuminance = masteringPeakGrid,
                    windows = windows,
                )
            metadata.profileValidationError()?.let { return Hdr10PlusParseResult.Invalid(it) }
            if (!reader.hasOnlyByteAlignmentPadding()) {
                return Hdr10PlusParseResult.Invalid("Unexpected trailing data in HDR10+ ST 2094-40 payload.")
            }
            Hdr10PlusParseResult.Success(
                metadata,
            )
        } catch (_: EndOfPayloadException) {
            Hdr10PlusParseResult.Invalid("Truncated HDR10+ ST 2094-40 payload.")
        } catch (error: IllegalArgumentException) {
            Hdr10PlusParseResult.Invalid(error.message ?: "Invalid HDR10+ ST 2094-40 payload.")
        }
    }

    private fun BitReader.readPeakLuminanceGrid(): Hdr10PlusPeakLuminanceGrid {
        val rows = readBits(5)
        val columns = readBits(5)
        require(rows in 2..25 && columns in 2..25) { "Invalid HDR10+ peak-luminance grid dimensions." }
        return Hdr10PlusPeakLuminanceGrid(
            rows = rows,
            columns = columns,
            values = List(rows * columns) { readBits(4) },
        )
    }
}

/**
 * Promotes a PQ HDR10 source only after a valid ST 2094-40 payload has actually been observed.
 *
 * PQ signaling by itself is not evidence of HDR10+. Keeping this decision next to the parser
 * prevents platform backends from advertising dynamic metadata based on codec or transfer
 * signaling alone.
 */
internal fun VideoColorInfo.withObservedHdr10PlusPayload(
    payload: ByteArray,
    timestampUs: Long,
): VideoColorInfo? {
    if (dynamicRange != VideoDynamicRange.HDR10 && dynamicRange != VideoDynamicRange.HDR10_PLUS) return null
    if (transfer != VideoColorTransfer.PQ) return null
    val metadata =
        when (val parsed = Hdr10PlusMetadataParser.parse(payload, timestampUs)) {
            is Hdr10PlusParseResult.Success -> parsed.metadata
            is Hdr10PlusParseResult.Invalid -> return null
        }
    return copy(
        dynamicRange = VideoDynamicRange.HDR10_PLUS,
        hdr10Plus =
            Hdr10PlusInfo(
                applicationIdentifier = metadata.applicationIdentifier,
                applicationVersion = metadata.applicationVersion,
                hasPerFrameMetadata = true,
            ),
    )
}

private fun Hdr10PlusMetadata.profileValidationError(): String? {
    if (targetedSystemDisplayMaximumLuminance !in 0..MAX_HDR10_PLUS_LUMINANCE_NITS) {
        return "HDR10+ targeted display luminance exceeds 10,000 nits."
    }
    if (targetedSystemDisplayActualPeakLuminance != null || masteringDisplayActualPeakLuminance != null) {
        return "HDR10+ Application 4 Version 1 does not permit actual-peak-luminance grids."
    }
    val window = windows.singleOrNull() ?: return "HDR10+ Application 4 Version 1 requires one window."
    if (window.geometry != null) return "HDR10+ Application 4 Version 1 does not permit window geometry."
    if (window.colorSaturationWeight != null) {
        return "HDR10+ Application 4 Version 1 does not permit color-saturation mapping."
    }
    if (window.maxScl.any { it !in 0..MAX_HDR10_PLUS_MEASUREMENT }) {
        return "HDR10+ MaxSCL exceeds its 10,000-nit profile bound."
    }
    if (window.averageMaxRgb !in 0..MAX_HDR10_PLUS_MEASUREMENT) {
        return "HDR10+ AverageMaxRGB exceeds its 10,000-nit profile bound."
    }
    if (window.distributionPercentiles.map(Hdr10PlusDistributionPercentile::percentage) !in VALID_PERCENTILE_INDEXES) {
        return "HDR10+ distribution percentiles do not match the Application 4 profile."
    }
    if (window.distributionPercentiles.any { it.percentileMaxRgb !in 0..MAX_HDR10_PLUS_MEASUREMENT }) {
        return "HDR10+ distribution percentile exceeds its 10,000-nit profile bound."
    }
    val toneMapping = window.toneMapping
    if (toneMapping == null && targetedSystemDisplayMaximumLuminance != 0) {
        return "HDR10+ Profile A requires zero targeted display luminance when no tone curve is present."
    }
    if (toneMapping != null && targetedSystemDisplayMaximumLuminance == 0) {
        return "HDR10+ Profile B requires a non-zero targeted display luminance."
    }
    if (toneMapping != null && toneMapping.bezierCurveAnchors.size > MAX_BEZIER_ANCHORS) {
        return "HDR10+ tone mapping contains more than nine Bezier anchors."
    }
    if (toneMapping != null && toneMapping.bezierCurveAnchors.isEmpty()) {
        return "HDR10+ Profile B requires at least one Bezier anchor."
    }
    return null
}

/** Bounded timestamp index used by streaming bridges to attach dynamic metadata to decoded frames. */
class Hdr10PlusMetadataTimeline(
    private val capacity: Int = DEFAULT_TIMELINE_CAPACITY,
) {
    private val packets = mutableListOf<Hdr10PlusMetadata>()

    init {
        require(capacity > 0) { "capacity must be positive." }
    }

    val size: Int
        get() = packets.size

    fun put(metadata: Hdr10PlusMetadata) {
        packets.removeAll { it.timestampUs == metadata.timestampUs }
        val insertionIndex =
            packets.indexOfFirst { it.timestampUs > metadata.timestampUs }.takeIf { it >= 0 } ?: packets.size
        packets.add(insertionIndex, metadata)
        while (packets.size > capacity) packets.removeAt(0)
    }

    fun metadataAt(timestampUs: Long): Hdr10PlusMetadata? = packets.firstOrNull { it.timestampUs == timestampUs }

    fun metadataNear(
        timestampUs: Long,
        toleranceUs: Long,
    ): Hdr10PlusMetadata? {
        require(toleranceUs >= 0L) { "toleranceUs must not be negative." }
        return packets
            .asSequence()
            .map { it to abs(it.timestampUs - timestampUs) }
            .filter { (_, distance) -> distance <= toleranceUs }
            .minByOrNull { (_, distance) -> distance }
            ?.first
    }

    fun discardBefore(timestampUs: Long) {
        packets.removeAll { it.timestampUs < timestampUs }
    }

    fun clear() = packets.clear()
}

/**
 * A bounded, renderer-neutral sampling of one ST 2094-40 OOTF.
 *
 * GPU backends interpolate the 33 samples in linear luminance. Values are
 * normalized to the 10,000-nit PQ domain so the same payload can be uploaded
 * unchanged to GLES, Metal, D3D11, Vulkan, and WebGPU shaders.
 */
internal data class Hdr10PlusToneCurve(
    val presentationTimeUs: Long,
    val sourcePeakNits: Float,
    val normalizedOutputLuminance: FloatArray,
)

internal fun Hdr10PlusMetadata.toToneCurve(displayPeakNits: Double): Hdr10PlusToneCurve? {
    require(displayPeakNits.isFinite() && displayPeakNits > 0.0) {
        "displayPeakNits must be finite and positive."
    }
    val window = windows.singleOrNull() ?: return null
    // Application 4 uses a full-picture processing window. Geometry would
    // require a spatial metadata texture rather than this one-dimensional OOTF.
    if (window.geometry != null) return null
    val metadataTargetPeak = targetedSystemDisplayMaximumLuminance.toDouble().takeIf { it > 0.0 }
    val targetPeak = minOf(displayPeakNits, metadataTargetPeak ?: displayPeakNits).coerceAtLeast(1.0)
    val sourcePeak =
        (HdrColorMath.estimateHdr10PlusScenePeakNits(window) ?: targetPeak)
            .coerceAtLeast(targetPeak)
    val curve =
        FloatArray(HDR10_PLUS_TONE_CURVE_SAMPLE_COUNT) { index ->
            val inputNits = sourcePeak * index / HDR10_PLUS_TONE_CURVE_INTERVALS
            (
                HdrColorMath.applyHdr10PlusToneMapping(inputNits, this, displayPeakNits) /
                    HDR10_PLUS_PQ_MAX_LUMINANCE_NITS
            ).toFloat()
        }
    return Hdr10PlusToneCurve(
        presentationTimeUs = timestampUs,
        sourcePeakNits = sourcePeak.toFloat(),
        normalizedOutputLuminance = curve,
    )
}

private class BitReader(
    private val bytes: ByteArray,
) {
    private var bitOffset = 0

    fun readFlag(): Boolean = readBits(1) != 0

    fun readBits(count: Int): Int {
        require(count in 1..31) { "Bit count must be in 1..31." }
        if (bitOffset + count > bytes.size * 8) throw EndOfPayloadException()
        var value = 0
        repeat(count) {
            val byteIndex = bitOffset ushr 3
            val bitIndex = 7 - (bitOffset and 7)
            value = (value shl 1) or ((bytes[byteIndex].toInt() ushr bitIndex) and 1)
            bitOffset += 1
        }
        return value
    }

    fun hasOnlyByteAlignmentPadding(): Boolean {
        val remaining = bytes.size * 8 - bitOffset
        if (remaining !in 0..7) return false
        repeat(remaining) {
            val byteIndex = bitOffset ushr 3
            val bitIndex = 7 - (bitOffset and 7)
            if (((bytes[byteIndex].toInt() ushr bitIndex) and 1) != 0) return false
            bitOffset += 1
        }
        return true
    }
}

private class EndOfPayloadException : RuntimeException()

private const val ITU_T_T35_COUNTRY_CODE = 0xb5
private const val HDR10_PLUS_PROVIDER_CODE = 0x003c
private const val HDR10_PLUS_PROVIDER_ORIENTED_CODE = 0x0001
private const val HDR10_PLUS_APPLICATION_IDENTIFIER = 4
private const val HDR10_PLUS_APPLICATION_VERSION = 1
private const val HDR10_PLUS_APPLICATION_VERSION_1_WINDOW_COUNT = 1
private const val MAX_HDR10_PLUS_LUMINANCE_NITS = 10_000
private const val MAX_HDR10_PLUS_MEASUREMENT = 100_000
private const val MAX_BEZIER_ANCHORS = 9
private val VALID_PERCENTILE_INDEXES =
    setOf(
        listOf(1, 5, 10, 25, 50, 75, 90, 95, 99),
        listOf(1, 5, 10, 25, 50, 75, 90, 95, 98, 99),
    )
private const val DEFAULT_TIMELINE_CAPACITY = 240
internal const val HDR10_PLUS_TONE_CURVE_SAMPLE_COUNT = 33
private const val HDR10_PLUS_TONE_CURVE_INTERVALS = 32.0
internal const val HDR10_PLUS_PQ_MAX_LUMINANCE_NITS = 10_000.0
