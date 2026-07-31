@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package io.github.kdroidfilter.composemediaplayer

import kotlinx.browser.window

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
