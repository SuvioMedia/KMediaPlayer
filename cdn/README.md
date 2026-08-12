# Immutable browser distribution

This directory contains the native runtime payload used by KMediaPlayer. The
published `kmedia-wasm-engine-runtime-assets` ZIP exposes these files under
`kmedia-wasm-runtime/`:

- `kmedia-wasm.js`;
- `kmedia-wasm.wasm`;
- `kmedia-wasm-runtime.json`.

The runtime is versioned together with the Kotlin/Wasm KLIB. For release
`0.4.0-alpha.3`, both sides require runtime ABI 4 and loading fails with
`RUNTIME_ABI_MISMATCH` before playback when they differ.

Only the three files above enter the runtime-assets ZIP. Historical TypeScript
distribution chunks remain in this source tree solely for the parity audit and
are not consumed or published by the Kotlin/Wasm engine artifacts.

The complete native-shim source, notices, third-party licenses, FFmpeg build
recipe and relinking instructions are part of the same Git tag at the
repository root. The proprietary Kotlin/Wasm KLIB is a separate artifact and
is not linked into this runtime payload.
