@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
@file:Suppress("CyclomaticComplexMethod", "MagicNumber")

package io.github.kdroidfilter.composemediaplayer

import kotlinx.browser.window
import org.w3c.dom.HTMLVideoElement
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.js

internal fun queryWebDisplayColorCapabilities(): DisplayColorCapabilities {
    val highDynamicRange = window.matchMedia("(dynamic-range: high)").matches
    return DisplayColorCapabilities(
        isKnown = true,
        supportedDynamicRanges =
            buildSet {
                add(VideoDynamicRange.SDR)
                if (highDynamicRange) {
                    add(VideoDynamicRange.HDR10)
                    add(VideoDynamicRange.HLG)
                }
            },
    )
}

internal fun queryWebRendererColorCapabilities(
    display: DisplayColorCapabilities = queryWebDisplayColorCapabilities(),
): RendererColorCapabilities =
    RendererColorCapabilities(
        nativeSurfaceDynamicRanges = display.supportedDynamicRanges - VideoDynamicRange.SDR,
        supportsNativeToneMappingToSdr = true,
    )

/** Reads the active decoded frame's WebCodecs color space without treating the codec name as HDR proof. */
internal fun HTMLVideoElement.toWebVideoColorInfo(): VideoColorInfo {
    val fields = probeWebVideoFrameColor(this).split(WEB_COLOR_FIELD_SEPARATOR)
    if (fields.size < WEB_COLOR_FIELD_COUNT) return VideoColorInfo()
    val primariesName = fields[0]
    val transferName = fields[1]
    val matrixName = fields[2]
    val fullRangeName = fields[3]
    val formatName = fields[4]
    val transfer =
        when (transferName) {
            "pq", "smpte2084" -> VideoColorTransfer.PQ
            "hlg", "arib-std-b67" -> VideoColorTransfer.HLG
            "iec61966-2-1", "srgb" -> VideoColorTransfer.SRGB
            "bt709", "smpte170m", "gamma22", "gamma28" -> VideoColorTransfer.SDR
            "linear" -> VideoColorTransfer.LINEAR
            else -> VideoColorTransfer.UNKNOWN
        }
    return VideoColorInfo(
        dynamicRange =
            when (transfer) {
                VideoColorTransfer.PQ -> VideoDynamicRange.HDR10
                VideoColorTransfer.HLG -> VideoDynamicRange.HLG
                VideoColorTransfer.SDR, VideoColorTransfer.SRGB -> VideoDynamicRange.SDR
                else -> VideoDynamicRange.UNKNOWN
            },
        bitDepth = formatName.webVideoFrameBitDepth(),
        primaries =
            when (primariesName) {
                "bt2020" -> VideoColorPrimaries.BT2020
                "bt709" -> VideoColorPrimaries.BT709
                "smpte432", "display-p3" -> VideoColorPrimaries.DISPLAY_P3
                "smpte170m" -> VideoColorPrimaries.BT601_525
                "bt470bg" -> VideoColorPrimaries.BT601_625
                else -> VideoColorPrimaries.UNKNOWN
            },
        transfer = transfer,
        matrix =
            when (matrixName) {
                "bt2020-ncl", "bt2020nc" -> VideoColorMatrix.BT2020_NCL
                "bt2020-cl", "bt2020c" -> VideoColorMatrix.BT2020_CL
                "bt709" -> VideoColorMatrix.BT709
                "smpte170m", "bt470bg" -> VideoColorMatrix.BT601
                "rgb" -> VideoColorMatrix.RGB
                else -> VideoColorMatrix.UNKNOWN
            },
        range =
            when (fullRangeName) {
                "true" -> VideoColorRange.FULL
                "false" -> VideoColorRange.LIMITED
                else -> VideoColorRange.UNKNOWN
            },
    )
}

private fun String.webVideoFrameBitDepth(): Int? {
    val normalized = uppercase()
    return when {
        "P10" in normalized || normalized == "P010" -> 10
        "P12" in normalized -> 12
        normalized.startsWith("RGBA") ||
            normalized.startsWith("BGRA") ||
            normalized.startsWith("I420") ||
            normalized.startsWith("I422") ||
            normalized.startsWith("I444") ||
            normalized == "NV12" -> 8
        else -> null
    }
}

@Suppress("UNUSED_PARAMETER")
private fun probeWebVideoFrameColor(video: HTMLVideoElement): String =
    js(
        """
        (() => {
            if (typeof globalThis.VideoFrame !== "function" || !video || video.readyState < 2) return "";
            let frame = null;
            try {
                frame = new globalThis.VideoFrame(video);
                const color = frame.colorSpace || {};
                return [
                    color.primaries || "",
                    color.transfer || "",
                    color.matrix || "",
                    color.fullRange === true ? "true" : (color.fullRange === false ? "false" : ""),
                    frame.format || ""
                ].join("\u001f");
            } catch (_) {
                return "";
            } finally {
                if (frame) frame.close();
            }
        })()
        """,
    )

private const val WEB_COLOR_FIELD_SEPARATOR = '\u001f'
private const val WEB_COLOR_FIELD_COUNT = 5
