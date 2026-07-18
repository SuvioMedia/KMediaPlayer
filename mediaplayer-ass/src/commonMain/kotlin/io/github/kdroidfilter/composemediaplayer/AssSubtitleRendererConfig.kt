package io.github.kdroidfilter.composemediaplayer

/**
 * Browser-only configuration for ASS/SSA subtitle rendering.
 *
 * This optional artifact uses JASSUB, which wraps
 * libass for browser targets. Its npm package provides the default
 * worker/WASM/font assets; these URLs are optional overrides for production
 * apps that need to self-host them.
 */
object AssSubtitleRendererConfig {
    var enabled: Boolean = true

    var workerUrl: String = ""
    var wasmUrl: String = ""
    var modernWasmUrl: String = ""
    var fallbackFontUrl: String = ""
    var fallbackFontFamily: String = "liberation sans"
    var queryFonts: Boolean = false
    var debug: Boolean = false
}
