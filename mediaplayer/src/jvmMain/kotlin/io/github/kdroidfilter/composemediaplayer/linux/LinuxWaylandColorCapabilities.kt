package io.github.kdroidfilter.composemediaplayer.linux

import io.github.kdroidfilter.composemediaplayer.DisplayColorCapabilities
import io.github.kdroidfilter.composemediaplayer.VideoDynamicRange

internal enum class WaylandOutputTransfer {
    UNKNOWN,
    SDR,
    PQ,
    HLG,
}

internal data class LinuxWaylandOutputColorDescription(
    val globalId: Int,
    val name: String?,
    val transfer: WaylandOutputTransfer,
    val primaries: String?,
    val minLuminanceNits: Float?,
    val maxLuminanceNits: Float?,
    val referenceWhiteNits: Float?,
) {
    val isHdrEnabled: Boolean
        get() = transfer == WaylandOutputTransfer.PQ || transfer == WaylandOutputTransfer.HLG
}

internal data class LinuxWaylandColorSnapshot(
    val hasColorManager: Boolean = false,
    val supportsParametricDescriptions: Boolean = false,
    val supportedTransfers: Set<WaylandOutputTransfer> = emptySet(),
    val supportsBt2020Primaries: Boolean = false,
    val outputs: Map<Int, LinuxWaylandOutputColorDescription> = emptyMap(),
) {
    fun outputFor(
        globalId: Int? = null,
        displayName: String? = null,
    ): LinuxWaylandOutputColorDescription? =
        globalId
            ?.let(outputs::get)
            ?: displayName
                ?.let { expected ->
                    outputs.values.firstOrNull { output -> output.name == expected }
                }

    fun displayCapabilitiesFor(
        globalId: Int? = null,
        displayName: String? = null,
    ): DisplayColorCapabilities {
        val output = outputFor(globalId, displayName) ?: return DisplayColorCapabilities()
        if (output.transfer == WaylandOutputTransfer.UNKNOWN) return DisplayColorCapabilities()
        if (output.transfer == WaylandOutputTransfer.SDR) {
            return DisplayColorCapabilities(
                isKnown = true,
                supportedDynamicRanges = setOf(VideoDynamicRange.SDR),
                minLuminanceNits = output.minLuminanceNits,
                maxLuminanceNits = output.maxLuminanceNits,
                referenceWhiteNits = output.referenceWhiteNits,
            )
        }

        val ranges =
            buildSet {
                add(VideoDynamicRange.SDR)
                if (WaylandOutputTransfer.PQ in supportedTransfers) {
                    add(VideoDynamicRange.HDR10)
                }
                if (WaylandOutputTransfer.HLG in supportedTransfers) add(VideoDynamicRange.HLG)
            }
        return DisplayColorCapabilities(
            isKnown = ranges.size > 1,
            supportedDynamicRanges = ranges,
            minLuminanceNits = output.minLuminanceNits,
            maxLuminanceNits = output.maxLuminanceNits,
            referenceWhiteNits = output.referenceWhiteNits,
        )
    }
}

internal object LinuxNativeWaylandColorCapabilitiesDecoder {
    fun decode(values: LongArray?): LinuxWaylandColorSnapshot? {
        if (values == null || values.size < VALUE_COUNT) return null
        val flags = values[FLAGS_INDEX]
        if (!flags.has(PROBE_COMPLETED)) return null

        val outputId = values[OUTPUT_ID_INDEX].toInt()
        val output =
            if (flags.has(OUTPUT_DESCRIPTION) && outputId >= 0) {
                val transfer =
                    when {
                        flags.has(OUTPUT_PQ) -> WaylandOutputTransfer.PQ
                        flags.has(OUTPUT_HLG) -> WaylandOutputTransfer.HLG
                        flags.has(OUTPUT_SDR) -> WaylandOutputTransfer.SDR
                        else -> WaylandOutputTransfer.UNKNOWN
                    }
                val hasLuminances =
                    values[MAX_LUMINANCE_INDEX] > 0L || values[REFERENCE_LUMINANCE_INDEX] > 0L
                LinuxWaylandOutputColorDescription(
                    globalId = outputId,
                    name = null,
                    transfer = transfer,
                    primaries = "bt2020".takeIf { flags.has(OUTPUT_BT2020) },
                    minLuminanceNits =
                        values[MIN_LUMINANCE_INDEX]
                            .toFloat()
                            .div(10_000f)
                            .takeIf { hasLuminances },
                    maxLuminanceNits = values[MAX_LUMINANCE_INDEX].toFloat().takeIf { hasLuminances },
                    referenceWhiteNits =
                        values[REFERENCE_LUMINANCE_INDEX].toFloat().takeIf { hasLuminances },
                )
            } else {
                null
            }

        return LinuxWaylandColorSnapshot(
            hasColorManager = flags.has(COLOR_MANAGER),
            supportsParametricDescriptions = flags.has(PARAMETRIC),
            supportedTransfers =
                buildSet {
                    if (flags.has(PQ)) add(WaylandOutputTransfer.PQ)
                    if (flags.has(HLG)) add(WaylandOutputTransfer.HLG)
                },
            supportsBt2020Primaries = flags.has(BT2020),
            outputs = output?.let { mapOf(outputId to it) }.orEmpty(),
        )
    }

    private fun Long.has(flag: Long): Boolean = this and flag != 0L

    private const val FLAGS_INDEX = 0
    private const val OUTPUT_ID_INDEX = 1
    private const val MIN_LUMINANCE_INDEX = 2
    private const val MAX_LUMINANCE_INDEX = 3
    private const val REFERENCE_LUMINANCE_INDEX = 4
    private const val VALUE_COUNT = 5

    private const val PROBE_COMPLETED = 1L shl 0
    private const val COLOR_MANAGER = 1L shl 1
    private const val PARAMETRIC = 1L shl 2
    private const val BT2020 = 1L shl 3
    private const val PQ = 1L shl 4
    private const val HLG = 1L shl 5
    private const val OUTPUT_DESCRIPTION = 1L shl 6
    private const val OUTPUT_PQ = 1L shl 7
    private const val OUTPUT_HLG = 1L shl 8
    private const val OUTPUT_BT2020 = 1L shl 9
    private const val OUTPUT_SDR = 1L shl 10
}

internal object LinuxWaylandColorCapabilitiesParser {
    fun parse(output: String): LinuxWaylandColorSnapshot {
        if (output.isBlank()) return LinuxWaylandColorSnapshot()

        val blocks = splitGlobalBlocks(output)
        val outputNames = mutableMapOf<Int, String>()
        blocks
            .filter { block -> block.header.contains("'wl_output'") }
            .forEach { block ->
                val id =
                    GLOBAL_ID_PATTERN
                        .find(block.header)
                        ?.groupValues
                        ?.get(1)
                        ?.toIntOrNull()
                val name =
                    block.lines
                        .firstNotNullOfOrNull { line -> OUTPUT_NAME_PATTERN.matchEntire(line)?.groupValues?.get(1) }
                        ?.trim()
                        ?.takeIf(String::isNotEmpty)
                if (id != null && name != null) outputNames[id] = name
            }

        val colorBlock =
            blocks.firstOrNull { block -> block.header.contains("'wp_color_manager_v1'") }
                ?: return LinuxWaylandColorSnapshot()
        val sections = colorBlock.sectionValues()
        val supportedTransfers =
            sections["supported named transfer functions"]
                .orEmpty()
                .mapNotNull(::parseTransfer)
                .toSet()
        val supportedPrimaries = sections["supported named primaries"].orEmpty().map(String::trim)
        val supportedFeatures = sections["supported features"].orEmpty().map(String::trim)

        val descriptions = mutableMapOf<Int, LinuxWaylandOutputColorDescription>()
        var index = 0
        while (index < colorBlock.lines.size) {
            val match = COLOR_OUTPUT_PATTERN.matchEntire(colorBlock.lines[index])
            if (match == null) {
                index++
                continue
            }
            val id = match.groupValues[1].toInt()
            val detailLines = mutableListOf<String>()
            index++
            while (index < colorBlock.lines.size && COLOR_OUTPUT_PATTERN.matchEntire(colorBlock.lines[index]) == null) {
                detailLines += colorBlock.lines[index]
                index++
            }
            val transfer =
                detailLines
                    .firstNotNullOfOrNull { line -> OUTPUT_TRANSFER_PATTERN.matchEntire(line)?.groupValues?.get(1) }
                    ?.let(::parseTransfer)
                    ?: WaylandOutputTransfer.UNKNOWN
            val primaries =
                detailLines
                    .firstNotNullOfOrNull { line -> OUTPUT_PRIMARIES_PATTERN.matchEntire(line)?.groupValues?.get(1) }
                    ?.trim()
            val luminance =
                detailLines
                    .firstNotNullOfOrNull { line -> OUTPUT_LUMINANCE_PATTERN.matchEntire(line) }
            descriptions[id] =
                LinuxWaylandOutputColorDescription(
                    globalId = id,
                    name = outputNames[id],
                    transfer = transfer,
                    primaries = primaries,
                    minLuminanceNits = luminance?.groupValues?.get(LUMINANCE_MIN_GROUP)?.toFloatOrNull(),
                    maxLuminanceNits = luminance?.groupValues?.get(LUMINANCE_MAX_GROUP)?.toFloatOrNull(),
                    referenceWhiteNits = luminance?.groupValues?.get(LUMINANCE_REFERENCE_GROUP)?.toFloatOrNull(),
                )
        }

        return LinuxWaylandColorSnapshot(
            hasColorManager = true,
            supportsParametricDescriptions = "parametric" in supportedFeatures,
            supportedTransfers = supportedTransfers,
            supportsBt2020Primaries = "bt2020" in supportedPrimaries,
            outputs = descriptions,
        )
    }

    private fun parseTransfer(value: String): WaylandOutputTransfer? =
        when (value.trim().lowercase()) {
            "st2084_pq" -> WaylandOutputTransfer.PQ
            "hlg" -> WaylandOutputTransfer.HLG
            "bt1886", "gamma22", "gamma28", "srgb" -> WaylandOutputTransfer.SDR
            else -> null
        }

    private fun splitGlobalBlocks(output: String): List<GlobalBlock> {
        val result = mutableListOf<GlobalBlock>()
        var header: String? = null
        var lines = mutableListOf<String>()
        output.lineSequence().forEach { line ->
            if (line.startsWith("interface:")) {
                header?.let { result += GlobalBlock(it, lines) }
                header = line
                lines = mutableListOf()
            } else if (header != null) {
                lines += line
            }
        }
        header?.let { result += GlobalBlock(it, lines) }
        return result
    }

    private data class GlobalBlock(
        val header: String,
        val lines: List<String>,
    ) {
        fun sectionValues(): Map<String, List<String>> {
            val result = mutableMapOf<String, MutableList<String>>()
            var active: String? = null
            lines.forEach { line ->
                val section = SECTION_PATTERN.matchEntire(line)?.groupValues?.get(1)
                if (section != null) {
                    active = section.lowercase()
                } else if (line.startsWith("\t\t") && !line.startsWith("\t\t\t")) {
                    active?.let { result.getOrPut(it) { mutableListOf() } += line.trim() }
                } else if (line.startsWith("\t") && !line.startsWith("\t\t")) {
                    active = null
                }
            }
            return result
        }
    }

    private val GLOBAL_ID_PATTERN = Regex("name:\\s*(\\d+)\\s*$")
    private val OUTPUT_NAME_PATTERN = Regex("\\tname:\\s*(.+)")
    private val SECTION_PATTERN = Regex("\\t([^:]+):")
    private val COLOR_OUTPUT_PATTERN = Regex("\\toutput:\\s*(\\d+)")
    private val OUTPUT_TRANSFER_PATTERN = Regex("\\t\\ttf_named:\\s*(\\S+)")
    private val OUTPUT_PRIMARIES_PATTERN = Regex("\\t\\tprimaries_named:\\s*(\\S+)")
    private val OUTPUT_LUMINANCE_PATTERN =
        Regex("\\t\\tluminances \\(cd/m²\\): min ([0-9.]+) max ([0-9.]+) reference ([0-9.]+)")
    private const val LUMINANCE_MIN_GROUP = 1
    private const val LUMINANCE_MAX_GROUP = 2
    private const val LUMINANCE_REFERENCE_GROUP = 3
}
