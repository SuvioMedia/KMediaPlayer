package io.github.kdroidfilter.composemediaplayer

/**
 * Browser dependency configuration for media runtime assets.
 *
 * Set overrides before opening the first affected media source. Applications with an offline or strict-CSP
 * deployment can self-host the same immutable files.
 */
object WebMediaDependencyConfig {
    /**
     * Base URL containing `kmedia-wasm.js`, `kmedia-wasm.wasm`, and the ABI manifest from the
     * `io.github.shusek:kmedia-wasm-engine-runtime-assets` artifact.
     *
     * The Kotlin player API is linked into the application as a KLIB; only the
     * Emscripten runtime files are loaded from this public, credential-free path.
     */
    var kmediaWasmRuntimeAssetBaseUrl: String =
        "composeResources/io.github.shusek.mediaplayer.generated.resources/files/kmedia-wasm-runtime/"
}
