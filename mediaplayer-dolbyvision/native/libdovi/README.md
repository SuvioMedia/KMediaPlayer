# libdovi native shim

This `cdylib` pins the MIT `dolby_vision` crate from the immutable dovi_tool 2.3.3 release commit.
It exposes only Profile 7 RPU to Profile 8.1 conversion and buffer release. It never decodes or
re-encodes picture data.

The optional artifact currently binds and packages the shim for:

- JVM macOS on ARM64, plus Windows and Linux on x64 and ARM64;
- Android armeabi-v7a and arm64-v8a through JNI;
- iOS device ARM64 and simulator ARM64, embedded in the published cinterop KLIB.
- browser Wasm through a packaged `wasm32-unknown-unknown` module and bounded linear-memory bridge.

The real-vector JVM and iOS tests verify Profile 7 FEL/MEL conversion and confirm that FEL mapping
is removed when producing Profile 8.1. Android packages both ARM ABIs and keeps its JNI entry
point from shrinking. The browser test instantiates the packaged Wasm payload and runs the same
real Profile 7 conversion; callers can await asynchronous availability through `prepare()`.

The Kotlin bridge is bounded and cancellation-safe. It preserves fragment timing and audio flags,
rewrites Annex-B or four-byte length-prefixed RPU NAL units one-for-one, and restarts seek from a
previous keyframe. Bounded fMP4/CMAF, flat MP4, HLS VOD, and common Matroska adapters are included.
The Matroska adapter preserves AAC, Opus, AC-3, and E-AC-3 on every target; JVM additionally uses
the verified FFmpeg bridge for compatible inputs outside that subset. The core player advertises
conversion only when both the native converter and the compatible container adapter are available.
Real-media coverage and the complete platform hardware matrix remain release gates.
