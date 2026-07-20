// Repeat the pre-Movi player-state regression suite with the explicit legacy engine in CI.
// This value is consumed only by wasmJsTest code; production state creation has no override.
const kmpPlaybackEngine = String(
    process.env.KMP_WASM_TEST_PLAYBACK_ENGINE || ""
).toLowerCase();

if (!["movi", "legacy"].includes(kmpPlaybackEngine)) {
    throw new Error(
        "KMP_WASM_TEST_PLAYBACK_ENGINE must be either 'movi' or 'legacy'."
    );
}

config.set({
    client: Object.assign({}, config.client || {}, {
        kmpPlaybackEngine
    })
});
