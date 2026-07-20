package io.github.kdroidfilter.composemediaplayer

/**
 * Browser dependency configuration for externally hosted media components.
 *
 * Set overrides before opening the first affected media source. Applications with an offline or strict-CSP
 * deployment can self-host the same immutable files.
 */
object WebMediaDependencyConfig {
    /**
     * ES module used by [WebPlaybackEngine.MOVI].
     *
     * The default is an exact, immutable CDN release. KMediaPlayer imports it at runtime and does not bundle
     * MoviPlayer or its media Wasm in its own artifacts. The URL must identify a public module and must not
     * contain credentials.
     */
    var moviPlayerModuleUrl: String =
        "https://cdn.jsdelivr.net/gh/Shusek/movi-player@v0.3.5-kmp.1/cdn/engine.js"

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
