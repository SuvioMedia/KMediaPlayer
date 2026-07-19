@file:OptIn(ExperimentalWasmJsInterop::class)

package io.github.kdroidfilter.composemediaplayer

import androidx.compose.ui.layout.ContentScale
import kotlinx.browser.document
import org.w3c.dom.HTMLCanvasElement
import org.w3c.dom.HTMLElement
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.js
import kotlin.math.roundToInt

internal data class AssSubtitleOverlayRect(
    val x: Double,
    val y: Double,
    val width: Double,
    val height: Double,
)

private data class AssSubtitleContentSnapshot(
    val header: String,
    val events: List<String>,
)

internal fun computeAssSubtitleStreamUpdate(
    previousContent: String?,
    nextContent: String?,
): String {
    if (previousContent == nextContent) return "none"
    val previous = previousContent?.toAssSubtitleContentSnapshot() ?: return "restart"
    val next = nextContent?.toAssSubtitleContentSnapshot() ?: return "restart"
    if (previous.header != next.header || next.events.size < previous.events.size) {
        return "restart"
    }
    if (previous.events.indices.any { index -> previous.events[index] != next.events[index] }) {
        return "restart"
    }
    val appendedEvents = next.events.drop(previous.events.size)
    return if (appendedEvents.isEmpty()) {
        "none"
    } else {
        "append\u0000${appendedEvents.joinToString(separator = "\n", postfix = "\n")}"
    }
}

private fun String.toAssSubtitleContentSnapshot(): AssSubtitleContentSnapshot {
    val lines = replace("\r\n", "\n").replace('\r', '\n').split('\n')
    val firstEvent =
        lines
            .indexOfFirst { line ->
                val normalized = line.trimStart()
                normalized.startsWith("Dialogue:", ignoreCase = true) ||
                    normalized.startsWith("Comment:", ignoreCase = true)
            }.takeIf { it >= 0 } ?: lines.size
    return AssSubtitleContentSnapshot(
        header = lines.take(firstEvent).joinToString("\n").trimEnd(),
        events = lines.drop(firstEvent).filter(String::isNotBlank),
    )
}

internal fun computeAssSubtitleOverlayRect(
    containerWidth: Double,
    containerHeight: Double,
    videoWidth: Double,
    videoHeight: Double,
    contentScaleMode: String,
    useWholeDisplayElement: Boolean,
): AssSubtitleOverlayRect {
    if (containerWidth <= 0.0 || containerHeight <= 0.0) {
        return AssSubtitleOverlayRect(0.0, 0.0, 0.0, 0.0)
    }
    val contentScaleUsesWholeElement =
        when (contentScaleMode) {
            "fill", "fillWidth", "fillHeight" -> true
            else -> false
        }
    val videoSizeUnavailable = videoWidth <= 0.0 || videoHeight <= 0.0
    if (useWholeDisplayElement || contentScaleUsesWholeElement || videoSizeUnavailable) {
        return AssSubtitleOverlayRect(0.0, 0.0, containerWidth, containerHeight)
    }

    val videoRatio = videoWidth / videoHeight
    val containerRatio = containerWidth / containerHeight
    val isCover = contentScaleMode == "cover"
    val width: Double
    val height: Double
    if ((containerRatio > videoRatio) xor isCover) {
        height = containerHeight
        width = height * videoRatio
    } else {
        width = containerWidth
        height = width / videoRatio
    }
    return AssSubtitleOverlayRect(
        x = (containerWidth - width) / 2.0,
        y = (containerHeight - height) / 2.0,
        width = width,
        height = height,
    )
}

internal fun encodeAssSubtitleOverlayRect(
    containerWidth: Double,
    containerHeight: Double,
    videoWidth: Double,
    videoHeight: Double,
    contentScaleMode: String,
    useWholeDisplayElement: Boolean,
): String {
    val rect =
        computeAssSubtitleOverlayRect(
            containerWidth = containerWidth,
            containerHeight = containerHeight,
            videoWidth = videoWidth,
            videoHeight = videoHeight,
            contentScaleMode = contentScaleMode,
            useWholeDisplayElement = useWholeDisplayElement,
        )
    return "${rect.x}|${rect.y}|${rect.width}|${rect.height}"
}

internal fun ContentScale.toAssSubtitleContentScaleMode(): String =
    when (this) {
        ContentScale.Crop -> "cover"
        ContentScale.FillBounds -> "fill"
        ContentScale.FillWidth -> "fillWidth"
        ContentScale.FillHeight -> "fillHeight"
        else -> "contain"
    }

internal fun createAssSubtitleCanvasElement(): HTMLCanvasElement =
    (document.createElement("canvas") as HTMLCanvasElement).apply {
        className = "compose-media-player-ass-subtitles"
        applyAssSubtitleCanvasStyle()
    }

internal fun HTMLCanvasElement.applyAssSubtitleCanvasStyle() {
    val wrapper = parentElement as? HTMLElement
    wrapper?.style?.apply {
        setProperty("z-index", "-1", "important")
        setProperty("pointer-events", "none")
        setProperty("overflow", "hidden", "important")
        backgroundColor = "transparent"
        display = "block"
        if (position.isBlank() || position == "static") {
            position = "relative"
        }
    }
    (wrapper?.parentElement as? HTMLElement)?.style?.setProperty("pointer-events", "none")

    style.apply {
        position = "absolute"
        display = "block"
        setProperty("pointer-events", "none")
        backgroundColor = "transparent"
    }
}

internal fun assSubtitleRendererUnavailableReason(enabled: Boolean): String =
    checkAssSubtitleRendererCapabilities(enabled)

@Suppress("UNUSED_PARAMETER")
private fun checkAssSubtitleRendererCapabilities(enabled: Boolean): String =
    js(
        """
        (function() {
            if (!enabled) return "Browser ASS rendering is disabled by configuration.";
            const missing = [];
            if (typeof Worker !== "function") missing.push("Worker");
            if (typeof WebAssembly !== "object") missing.push("WebAssembly");
            if (typeof TextDecoder !== "function") missing.push("TextDecoder");
            if (typeof OffscreenCanvas !== "function") missing.push("OffscreenCanvas");
            if (
                typeof HTMLCanvasElement !== "function" ||
                typeof HTMLCanvasElement.prototype.transferControlToOffscreen !== "function"
            ) {
                missing.push("canvas.transferControlToOffscreen");
            }
            if (typeof ResizeObserver !== "function") missing.push("ResizeObserver");
            if (typeof MutationObserver !== "function") missing.push("MutationObserver");
            if (typeof fetch !== "function") missing.push("Fetch");
            if (typeof Promise !== "function") missing.push("Promise");
            if (typeof Proxy !== "function") missing.push("Proxy");
            const videoPrototype =
                typeof HTMLVideoElement === "function" ? HTMLVideoElement.prototype : null;
            if (
                !videoPrototype ||
                typeof videoPrototype.requestVideoFrameCallback !== "function"
            ) {
                missing.push("requestVideoFrameCallback");
            }
            return missing.length === 0
                ? ""
                : "JASSUB cannot run because this browser is missing: " + missing.join(", ") + ".";
        })()
        """,
    )

internal fun assSubtitleRenderDimension(
    cssPixels: Double,
    devicePixelRatio: Double,
): Int = (cssPixels * devicePixelRatio).roundToInt().coerceAtLeast(1)
