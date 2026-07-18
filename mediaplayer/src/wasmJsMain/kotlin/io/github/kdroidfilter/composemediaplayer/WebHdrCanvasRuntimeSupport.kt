@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package io.github.kdroidfilter.composemediaplayer

import kotlinx.browser.window
import kotlin.js.js

internal data class WebHdrCanvasRuntimeSupport(
    val hasWebGpu: Boolean,
    val hasCanvasConfigurationReadback: Boolean,
    val displayReportsHighDynamicRange: Boolean,
) {
    val canAttemptHdrCanvas: Boolean
        get() = hasWebGpu && hasCanvasConfigurationReadback && displayReportsHighDynamicRange

    fun controlledDynamicRanges(
        display: DisplayColorCapabilities,
        disabledAfterRuntimeFailure: Boolean,
    ): Set<VideoDynamicRange> {
        if (!canAttemptHdrCanvas || disabledAfterRuntimeFailure) return emptySet()
        return display.supportedDynamicRanges intersect WEB_GPU_CONTROLLED_HDR_RANGES
    }
}

internal fun queryWebHdrCanvasRuntimeSupport(): WebHdrCanvasRuntimeSupport =
    WebHdrCanvasRuntimeSupport(
        hasWebGpu = webGpuIsExposed(),
        hasCanvasConfigurationReadback = webGpuCanvasConfigurationCanBeReadBack(),
        displayReportsHighDynamicRange = window.matchMedia("(dynamic-range: high)").matches,
    )

internal fun queryWebProjectionRendererColorCapabilities(
    display: DisplayColorCapabilities,
    runtimeSupport: WebHdrCanvasRuntimeSupport = queryWebHdrCanvasRuntimeSupport(),
    hdrDisabledAfterRuntimeFailure: Boolean = false,
): RendererColorCapabilities {
    val controlledRanges =
        runtimeSupport.controlledDynamicRanges(
            display = display,
            disabledAfterRuntimeFailure = hdrDisabledAfterRuntimeFailure,
        )
    return RendererColorCapabilities(
        controlledHdrDynamicRanges = controlledRanges,
        supportsToneMappingToSdr = runtimeSupport.hasWebGpu,
        supportsHdrProjection = controlledRanges.isNotEmpty(),
    )
}

internal fun webGpuHdrCanvasColorSpaceFor(dynamicRange: VideoDynamicRange): String? =
    when (dynamicRange) {
        VideoDynamicRange.HDR10, VideoDynamicRange.HLG -> WEB_GPU_HDR_CANVAS_COLOR_SPACE
        else -> null
    }

internal fun webGpuExternalTextureColorSpaceFor(outputHdr: Boolean): String =
    if (outputHdr) WEB_GPU_HDR_CANVAS_COLOR_SPACE else WEB_GPU_SDR_CANVAS_COLOR_SPACE

internal val VideoColorInfo.hasWebGpuManagedHdrTransfer: Boolean
    get() = transfer == VideoColorTransfer.PQ || transfer == VideoColorTransfer.HLG

internal fun confirmsWebGpuHdrOutput(
    outputDynamicRange: VideoDynamicRange,
    surfaceKind: VideoSurfaceKind,
): Boolean =
    surfaceKind == VideoSurfaceKind.WEB_GPU_CANVAS &&
        outputDynamicRange in WEB_GPU_CONTROLLED_HDR_RANGES

private fun webGpuIsExposed(): Boolean =
    js(
        """
        typeof globalThis.navigator !== "undefined" &&
            !!globalThis.navigator.gpu &&
            globalThis.isSecureContext !== false
        """,
    )

private fun webGpuCanvasConfigurationCanBeReadBack(): Boolean =
    js(
        """
        (() => {
            if (typeof globalThis.document === "undefined" || !globalThis.navigator || !globalThis.navigator.gpu) {
                return false;
            }
            try {
                const probe = globalThis.document.createElement("canvas");
                const context = probe.getContext("webgpu");
                const supported = !!context && typeof context.getConfiguration === "function";
                if (context && typeof context.unconfigure === "function") context.unconfigure();
                return supported;
            } catch (_) {
                return false;
            }
        })()
        """,
    )

private val WEB_GPU_CONTROLLED_HDR_RANGES = setOf(VideoDynamicRange.HDR10, VideoDynamicRange.HLG)
private const val WEB_GPU_HDR_CANVAS_COLOR_SPACE = "display-p3"
private const val WEB_GPU_SDR_CANVAS_COLOR_SPACE = "srgb"
