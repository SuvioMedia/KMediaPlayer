package io.github.kdroidfilter.composemediaplayer.linux

import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

internal data class LinuxVulkanCapabilities(
    val isAvailable: Boolean = false,
    val hasWaylandSurface: Boolean = false,
    val hasExternalMemoryDmaBuf: Boolean = false,
    val hasDrmFormatModifier: Boolean = false,
    val hasExternalMemoryFd: Boolean = false,
    val hasShaderFloat16: Boolean = false,
    val hasSamplerYcbcrConversion: Boolean = false,
)

internal object LinuxVulkanCapabilitiesDecoder {
    fun decode(flags: Int): LinuxVulkanCapabilities =
        LinuxVulkanCapabilities(
            isAvailable = flags.has(AVAILABLE),
            hasWaylandSurface = flags.has(WAYLAND_SURFACE),
            hasExternalMemoryDmaBuf = flags.has(EXTERNAL_MEMORY_DMA_BUF),
            hasDrmFormatModifier = flags.has(IMAGE_DRM_FORMAT_MODIFIER),
            hasExternalMemoryFd = flags.has(EXTERNAL_MEMORY_FD),
            hasShaderFloat16 = flags.has(SHADER_FLOAT16),
            hasSamplerYcbcrConversion = flags.has(SAMPLER_YCBCR_CONVERSION),
        )

    private fun Int.has(flag: Int): Boolean = this and flag != 0

    private const val AVAILABLE = 1 shl 0
    private const val WAYLAND_SURFACE = 1 shl 1
    private const val EXTERNAL_MEMORY_DMA_BUF = 1 shl 2
    private const val IMAGE_DRM_FORMAT_MODIFIER = 1 shl 3
    private const val EXTERNAL_MEMORY_FD = 1 shl 4
    private const val SHADER_FLOAT16 = 1 shl 5
    private const val SAMPLER_YCBCR_CONVERSION = 1 shl 6
}

internal data class LinuxGStreamerCapabilities(
    val version: String? = null,
    val hasWaylandSink: Boolean = false,
    val hasVulkanUpload: Boolean = false,
    val hasVulkanColorConvert: Boolean = false,
    val hasVulkanShaderSpv: Boolean = false,
    val hasVulkanOverlayCompositor: Boolean = false,
)

internal object LinuxGStreamerCapabilitiesDecoder {
    fun decode(values: IntArray?): LinuxGStreamerCapabilities {
        if (values == null || values.size < VALUE_COUNT) return LinuxGStreamerCapabilities()
        val version =
            values
                .take(3)
                .takeIf { parts -> parts.firstOrNull()?.let { it > 0 } == true }
                ?.joinToString(".")
        val flags = values[FLAGS_INDEX]
        return LinuxGStreamerCapabilities(
            version = version,
            hasWaylandSink = flags.has(WAYLAND_SINK),
            hasVulkanUpload = flags.has(VULKAN_UPLOAD),
            hasVulkanColorConvert = flags.has(VULKAN_COLOR_CONVERT),
            hasVulkanShaderSpv = flags.has(VULKAN_SHADER_SPV),
            hasVulkanOverlayCompositor = flags.has(VULKAN_OVERLAY_COMPOSITOR),
        )
    }

    private fun Int.has(flag: Int): Boolean = this and flag != 0

    private const val VALUE_COUNT = 5
    private const val FLAGS_INDEX = 4
    private const val WAYLAND_SINK = 1 shl 0
    private const val VULKAN_UPLOAD = 1 shl 1
    private const val VULKAN_COLOR_CONVERT = 1 shl 2
    private const val VULKAN_SHADER_SPV = 1 shl 3
    private const val VULKAN_OVERLAY_COMPOSITOR = 1 shl 4
}

internal data class LinuxHdrRuntimeFacts(
    val waylandSession: Boolean,
    val gstreamerVersion: String?,
    val hasWaylandSink: Boolean,
    val hasVulkanUpload: Boolean,
    val hasVulkanColorConvert: Boolean,
    val hasVulkanShaderSpv: Boolean,
    val hasVulkanOverlayCompositor: Boolean,
    val hasColorManagementProtocol: Boolean,
    val hasParametricColorDescription: Boolean,
    val hasBt2020Primaries: Boolean,
    val hasPqTransfer: Boolean,
    val hasHlgTransfer: Boolean,
    val hasHdrEnabledOutput: Boolean,
    val hasVulkan: Boolean,
    val hasVulkanWaylandSurface: Boolean,
    val hasExternalMemoryDmaBuf: Boolean,
    val hasDrmFormatModifier: Boolean,
    val hasExternalMemoryFd: Boolean,
    val hasShaderFloat16: Boolean,
    val hasSamplerYcbcrConversion: Boolean,
    val nativeWaylandAdapterAvailable: Boolean,
    val nativeVulkanProjectionRendererAvailable: Boolean = false,
)

internal data class LinuxHdrRuntimeStatus(
    val isReady: Boolean,
    val missingRequirements: List<String>,
    val isColorManagedSurfaceReady: Boolean = false,
    val surfaceMissingRequirements: List<String> = emptyList(),
    val isVulkanProjectionReady: Boolean = false,
    val projectionMissingRequirements: List<String> = emptyList(),
    val supportsHdr10PlusMetadata: Boolean = false,
    val waylandColorSnapshot: LinuxWaylandColorSnapshot = LinuxWaylandColorSnapshot(),
    val defaultOutputId: Int? = null,
    val defaultDisplayName: String? = null,
) {
    val detail: String?
        get() =
            missingRequirements
                .takeIf(List<String>::isNotEmpty)
                ?.joinToString(prefix = "Linux HDR unavailable: ", separator = "; ", postfix = ".")

    val surfaceDetail: String?
        get() =
            surfaceMissingRequirements
                .takeIf(List<String>::isNotEmpty)
                ?.joinToString(prefix = "Linux HDR surface unavailable: ", separator = "; ", postfix = ".")

    val projectionDetail: String?
        get() =
            projectionMissingRequirements
                .takeIf(List<String>::isNotEmpty)
                ?.joinToString(prefix = "Linux HDR projection unavailable: ", separator = "; ", postfix = ".")
}

internal object LinuxHdrRuntimeEvaluator {
    @Suppress("CyclomaticComplexMethod")
    fun evaluate(facts: LinuxHdrRuntimeFacts): LinuxHdrRuntimeStatus {
        val surfaceMissing =
            buildList {
                if (!facts.waylandSession) add("the window is not in a native Wayland session")
                if (!isAtLeast(facts.gstreamerVersion, MINIMUM_GSTREAMER)) {
                    add("GStreamer 1.28.5+ is required")
                }
                if (!facts.hasWaylandSink) add("the GStreamer waylandsink plugin is unavailable")
                if (!facts.hasColorManagementProtocol) add("Wayland color-management-v1 is unavailable")
                if (!facts.hasParametricColorDescription) {
                    add("the compositor cannot create parametric color descriptions")
                }
                if (!facts.hasBt2020Primaries) add("the compositor does not accept BT.2020 primaries")
                if (!facts.hasPqTransfer) add("the compositor does not accept PQ/ST 2084 surfaces")
                if (!facts.hasHlgTransfer) add("the compositor does not accept HLG surfaces")
                if (!facts.nativeWaylandAdapterAvailable) add("the Tao/GTK Wayland HDR surface adapter is unavailable")
            }
        val projectionMissing =
            buildList {
                addAll(surfaceMissing)
                if (!facts.hasVulkan) add("Vulkan is unavailable")
                if (!facts.hasVulkanWaylandSurface) add("VK_KHR_wayland_surface is unavailable")
                if (!facts.hasExternalMemoryDmaBuf) add("VK_EXT_external_memory_dma_buf is unavailable")
                if (!facts.hasDrmFormatModifier) add("VK_EXT_image_drm_format_modifier is unavailable")
                if (!facts.hasExternalMemoryFd) add("VK_KHR_external_memory_fd is unavailable")
                if (!facts.hasShaderFloat16) add("Vulkan shaderFloat16 is unavailable")
                if (!facts.hasSamplerYcbcrConversion) add("Vulkan samplerYcbcrConversion is unavailable")
                if (!facts.nativeVulkanProjectionRendererAvailable) {
                    add("the KMediaPlayer Vulkan projection renderer is unavailable")
                }
            }
        val missing =
            buildList {
                addAll(projectionMissing)
                if (!facts.hasHdrEnabledOutput) add("the active output is not operating in an HDR mode")
            }
        return LinuxHdrRuntimeStatus(
            isReady = missing.isEmpty(),
            missingRequirements = missing,
            isColorManagedSurfaceReady = surfaceMissing.isEmpty(),
            surfaceMissingRequirements = surfaceMissing,
            isVulkanProjectionReady = projectionMissing.isEmpty(),
            projectionMissingRequirements = projectionMissing,
            supportsHdr10PlusMetadata = isAtLeast(facts.gstreamerVersion, MINIMUM_HDR10_PLUS_GSTREAMER),
        )
    }

    internal fun isAtLeast(
        actual: String?,
        minimum: String,
    ): Boolean {
        val actualParts = actual.versionParts()
        val minimumParts = minimum.versionParts()
        if (actualParts.isEmpty()) return false
        val size = maxOf(actualParts.size, minimumParts.size)
        repeat(size) { index ->
            val left = actualParts.getOrElse(index) { 0 }
            val right = minimumParts.getOrElse(index) { 0 }
            if (left != right) return left > right
        }
        return true
    }

    private fun String?.versionParts(): List<Int> =
        this
            ?.let { VERSION_PATTERN.find(it)?.value }
            ?.split('.')
            ?.mapNotNull(String::toIntOrNull)
            .orEmpty()

    private const val MINIMUM_GSTREAMER = "1.28.5"
    private const val MINIMUM_HDR10_PLUS_GSTREAMER = "1.30.0"
    private val VERSION_PATTERN = Regex("\\d+(?:\\.\\d+){1,3}")
}

internal object LinuxHdrRuntimeProbe {
    fun query(): LinuxHdrRuntimeStatus {
        val nativeColorValues =
            runCatching { LinuxNativeBridge.nQueryGtkWaylandColorCapabilities(outputId = -1) }
                .getOrNull()
        val nativeColorSnapshot = LinuxNativeWaylandColorCapabilitiesDecoder.decode(nativeColorValues)
        val waylandInfo =
            if (nativeColorSnapshot == null) {
                runCommand(findExecutable("wayland-info")?.let(::listOf).orEmpty())
            } else {
                ""
            }
        val vulkanCapabilities =
            LinuxVulkanCapabilitiesDecoder.decode(
                runCatching { LinuxNativeBridge.nQueryVulkanCapabilities() }.getOrDefault(0),
            )
        val gstreamerCapabilities =
            LinuxGStreamerCapabilitiesDecoder.decode(
                runCatching { LinuxNativeBridge.nGetGStreamerRuntimeInfo() }.getOrNull(),
            )
        val colorSnapshot =
            nativeColorSnapshot ?: LinuxWaylandColorCapabilitiesParser.parse(waylandInfo)
        val defaultOutputId = nativeColorValues?.getOrNull(1)?.toInt()?.takeIf { it >= 0 }
        val defaultDisplayName: String? = null
        val defaultOutput =
            colorSnapshot.outputFor(
                globalId = defaultOutputId,
                displayName = defaultDisplayName,
            )
        val gtkWaylandAvailable =
            runCatching { LinuxNativeBridge.nIsGtkWaylandAdapterAvailable() }.getOrDefault(false)
        val facts =
            LinuxHdrRuntimeFacts(
                waylandSession = gtkWaylandAvailable,
                gstreamerVersion = gstreamerCapabilities.version,
                hasWaylandSink = gstreamerCapabilities.hasWaylandSink,
                hasVulkanUpload = gstreamerCapabilities.hasVulkanUpload,
                hasVulkanColorConvert = gstreamerCapabilities.hasVulkanColorConvert,
                hasVulkanShaderSpv = gstreamerCapabilities.hasVulkanShaderSpv,
                hasVulkanOverlayCompositor = gstreamerCapabilities.hasVulkanOverlayCompositor,
                hasColorManagementProtocol = colorSnapshot.hasColorManager,
                hasParametricColorDescription = colorSnapshot.supportsParametricDescriptions,
                hasBt2020Primaries = colorSnapshot.supportsBt2020Primaries,
                hasPqTransfer = WaylandOutputTransfer.PQ in colorSnapshot.supportedTransfers,
                hasHlgTransfer = WaylandOutputTransfer.HLG in colorSnapshot.supportedTransfers,
                hasHdrEnabledOutput =
                    defaultOutput?.isHdrEnabled == true ||
                        (
                            defaultDisplayName == null &&
                                colorSnapshot.outputs.values
                                    .singleOrNull()
                                    ?.isHdrEnabled == true
                        ),
                hasVulkan = vulkanCapabilities.isAvailable,
                hasVulkanWaylandSurface = vulkanCapabilities.hasWaylandSurface,
                hasExternalMemoryDmaBuf = vulkanCapabilities.hasExternalMemoryDmaBuf,
                hasDrmFormatModifier = vulkanCapabilities.hasDrmFormatModifier,
                hasExternalMemoryFd = vulkanCapabilities.hasExternalMemoryFd,
                hasShaderFloat16 = vulkanCapabilities.hasShaderFloat16,
                hasSamplerYcbcrConversion = vulkanCapabilities.hasSamplerYcbcrConversion,
                nativeWaylandAdapterAvailable =
                gtkWaylandAvailable,
                nativeVulkanProjectionRendererAvailable =
                    runCatching { LinuxNativeBridge.nIsVulkanProjectionRendererAvailable() }.getOrDefault(false),
            )
        return LinuxHdrRuntimeEvaluator.evaluate(facts).copy(
            waylandColorSnapshot = colorSnapshot,
            defaultOutputId = defaultOutputId,
            defaultDisplayName = defaultDisplayName,
        )
    }

    private fun findExecutable(name: String): String? =
        System
            .getenv("PATH")
            ?.split(File.pathSeparator)
            ?.asSequence()
            ?.map { directory -> File(directory, name) }
            ?.firstOrNull { it.isFile && it.canExecute() }
            ?.absolutePath

    private fun runCommand(
        command: List<String>,
        maximumCharacters: Int = DEFAULT_OUTPUT_LIMIT,
    ): String {
        if (command.isEmpty()) return ""
        return runCatching {
            val process = ProcessBuilder(command).redirectErrorStream(true).start()
            val output = StringBuilder()
            val reader =
                thread(start = true, isDaemon = true, name = "compose-media-player-runtime-probe") {
                    process.inputStream.bufferedReader().useLines { lines ->
                        lines.forEach { line ->
                            synchronized(output) {
                                if (output.length < maximumCharacters) output.appendLine(line)
                            }
                        }
                    }
                }
            if (!process.waitFor(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) process.destroyForcibly()
            reader.join(READER_JOIN_TIMEOUT_MS)
            synchronized(output) { output.toString() }
        }.getOrDefault("")
    }

    private const val PROBE_TIMEOUT_SECONDS = 3L
    private const val READER_JOIN_TIMEOUT_MS = 500L
    private const val DEFAULT_OUTPUT_LIMIT = 64_000
}
