// Real Movi smoke tests initialize the packaged FFmpeg WebAssembly runtime before assertions.
// Keep the ordinary Kotlin test runner arguments intact and only extend Mocha/Karma timeouts.
config.set({
    client: {
        mocha: {
            timeout: 90000
        }
    },
    browserNoActivityTimeout: 120000,
    browserDisconnectTimeout: 10000,
    captureTimeout: 60000
});
