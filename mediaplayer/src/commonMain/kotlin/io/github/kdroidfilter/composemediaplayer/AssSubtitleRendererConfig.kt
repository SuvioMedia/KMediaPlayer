package io.github.kdroidfilter.composemediaplayer

/**
 * Browser-only configuration for ASS/SSA subtitle rendering.
 *
 * The default URLs load JavascriptSubtitlesOctopus/libass assets from jsDelivr so
 * web and wasm samples work without extra bundler setup. Production apps can set
 * these to self-hosted assets before selecting an ASS subtitle track.
 */
object AssSubtitleRendererConfig {
    private const val LIBASS_WASM_VERSION = "4.1.0"
    private const val BILIBLITZ_LIBASS_WASM_VERSION = "0.0.3"

    var enabled: Boolean = true
    var scriptUrl: String =
        "https://cdn.jsdelivr.net/npm/libass-wasm@$LIBASS_WASM_VERSION/dist/js/subtitles-octopus.js"
    var workerUrl: String =
        "https://cdn.jsdelivr.net/npm/libass-wasm@$LIBASS_WASM_VERSION/dist/js/subtitles-octopus-worker.js"
    var legacyWorkerUrl: String =
        "https://cdn.jsdelivr.net/npm/libass-wasm@$LIBASS_WASM_VERSION/dist/js/subtitles-octopus-worker-legacy.js"
    var fallbackFontUrl: String =
        "https://cdn.jsdelivr.net/npm/@biliblitz/libass-wasm@$BILIBLITZ_LIBASS_WASM_VERSION/src/assets/default.woff2"
    var debug: Boolean = false
}
