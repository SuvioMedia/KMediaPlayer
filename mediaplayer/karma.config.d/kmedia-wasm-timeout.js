// Real engine smoke tests initialize the packaged FFmpeg WebAssembly runtime before assertions.
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
