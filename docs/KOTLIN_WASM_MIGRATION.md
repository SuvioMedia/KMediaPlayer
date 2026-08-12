# Migration from the JavaScript engine

`0.4.0-alpha.2` starts the renamed KMedia Kotlin/Wasm-only API line. It is not source- or binary-compatible with
the `0.3.x` npm package, `/engine` entrypoint, web component, or Kotlin/JS consumers.

`0.4.0-alpha.3` starts the proprietary KLIB publication line. Earlier artifacts retain the terms
under which they were published. The separately versioned native runtime remains open and
replaceable for Apache/LGPL compliance.

## What changed

- Link `io.github.shusek:kmedia-wasm-engine` in `wasmJsMain`; do not load an npm module.
- Construct `WasmMediaPlayer` with a typed `WasmMediaPlayerConfig`.
- Pass a typed `MediaSource.Url`, `BrowserFile`, `Adapter`, `Encrypted`, `Drm`, or `Composite` to
  `load`. Progressive URLs always use the demuxed engine; MIME identifies adaptive sources.
- Collect Kotlin `StateFlow` and `SharedFlow` values instead of installing JavaScript callbacks.
- Select tracks with `TrackSelectionRequest` and inspect `TrackSelectionOutcome`.
- Use `WasmMediaAdvancedControls` for live/DVR, output routing, stable volume, snapshots, thumbnails,
  cover art and full subtitle-cue access.
- Implement `EmbeddedSubtitleRenderer` for custom ASS/SSA rendering.
- Publish `kmedia-wasm.js` and `kmedia-wasm.wasm` from the matching runtime-assets ZIP and configure only their
  base URL.
- Keep the KLIB and runtime manifest on the same ABI. A mismatch fails before playback with
  `RUNTIME_ABI_MISMATCH`.

There is deliberately no adapter for the old constructor shape, dynamic method names, JavaScript
event payloads, `<kmedia-wasm-engine>` element, or npm package exports.

## Coordinated rollout

1. Publish the KLIB and runtime-assets ZIP under the same version.
2. Update the consuming Kotlin/Wasm project to the new Maven coordinate.
3. Package the runtime files with the web application and set `WasmRuntimeConfig.assetBaseUrl`.
4. Replace dynamic player calls with the typed API.
5. Replace JavaScript subtitle objects with `EmbeddedSubtitleRenderer`.
6. Run browser tests against the packaged runtime, not a mutable CDN URL.

KMediaPlayer supports step 1 during local development with
`-PkmediaWasmEngineProjectDir=/absolute/path/to/kmedia-wasm-engine`, which substitutes both Maven artifacts from
the included build.
