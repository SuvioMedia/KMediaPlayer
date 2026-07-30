package io.github.kdroidfilter.composemediaplayer

/**
 * Browser dependency configuration for media runtime assets.
 *
 * Set overrides before opening the first affected media source. Applications with an offline or strict-CSP
 * deployment can self-host the same immutable files.
 */
object WebMediaDependencyConfig {
    /**
     * Base URL containing `movi.js` and `movi.wasm` from the
     * `io.github.shusek:movi-player-runtime-assets` artifact.
     *
     * The Kotlin player API is linked into the application as a KLIB; only the
     * Emscripten runtime files are loaded from this public, credential-free path.
     */
    var moviRuntimeAssetBaseUrl: String =
        "composeResources/io.github.shusek.mediaplayer.generated.resources/files/movi-runtime/"

    /**
     * Browser bundle used for legacy embedded MKV subtitle extraction.
     *
     * Setting this to an empty string disables that extraction without affecting normal video playback.
     */
    var matroskaSubtitlesScriptUrl: String =
        "https://cdn.jsdelivr.net/npm/matroska-subtitles@3.3.2/dist/matroska-subtitles.min.js"

    var matroskaSubtitlesScriptIntegrity: String =
        "sha384-gGN9a/1oMjF5kIq0N0PFrgbT2AT1N5ZumuzEMDqib6LM5G60oHAajwuuEYfldRu7"
}
