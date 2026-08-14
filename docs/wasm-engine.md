# Kotlin/Wasm engine integration

KMediaPlayer uses [`kmedia-wasm-engine`](https://github.com/Shusek/kmedia-wasm-engine) as its only
browser playback implementation. KMediaPlayer retains multiplatform state, Compose layout,
overlays, fullscreen/PiP hosting, extension orchestration and diagnostics mapping. The engine owns
source routing, media elements, clocks, decoding, adaptive streaming and frame rendering.

## Versioned artifacts

The proprietary `0.4.0-alpha.4` integration consumes matching artifacts:

| Artifact | Purpose |
|---|---|
| `io.github.shusek:kmedia-wasm-engine` | Typed Kotlin/Wasm API and implementation |
| `io.github.shusek:kmedia-wasm-engine-runtime-assets` | ZIP containing the Emscripten runtime |

The runtime is packaged under `kmedia-wasm-runtime/` and consists of:

- `kmedia-wasm.js`;
- `kmedia-wasm.wasm`;
- `kmedia-wasm-runtime.json`.

Both artifacts use runtime ABI 4. A KLIB/runtime mismatch fails before playback with
`RUNTIME_ABI_MISMATCH`. The default Compose-resource location can be replaced before opening a
source:

```kotlin
WebMediaDependencyConfig.kmediaWasmRuntimeAssetBaseUrl =
    "/vendor/kmedia-wasm-runtime/"
```

Keep this URL public and credential-free. KMediaPlayer does not dynamically import a Kotlin/JS or
TypeScript player facade.

## Playback ownership

`WasmEnginePlaybackSession` is the single Wasm session. It creates `WasmMediaPlayer`, maps KMedia
configuration before `load()`, and mounts the returned `PlayerSurface`:

- progressive URLs, browser files, `blob:` and `data:` sources are demuxed by the engine;
- HLS, DASH, MSS and DRM use its browser/MSE backend;
- clear adaptive streams can fall back to the engine demuxer when MSE cannot decode the codec;
- DRM remains on a protected native surface;
- projection, crop, rotation and `FORCE_SDR` use the engine-controlled canvas.

KMediaPlayer never creates or directly controls its own playback `<video>`. A `NativeVideo`
surface is mounted as-is. A controlled adaptive `Canvas` may include an engine-owned media element;
KMediaPlayer mounts it invisibly for timing/decoding but does not set its source, clock or playback
state.

## Prepared Dolby Vision source

The optional Dolby Vision extension can prepare a Profile 7 → 8.1 MediaSource transport. KMedia
wraps its `WebPreparedVideoPipelineSource` as the engine's `BrowserMediaSourceAdapter` and transfers
ownership during `load()`.

The lifecycle is ordered and singular:

1. the engine calls `attach(video, onFailure)`;
2. it assigns the adapter URL and calls `load()`;
3. source replacement, failure or player close causes exactly one `detach()` and `close()`.

Protected DRM sources cannot use this conversion bridge and fail closed.

## Color and projection policy

`DynamicRangePolicy` maps directly to `OutputDynamicRangePolicy`:

| KMediaPlayer | Engine |
|---|---|
| `AUTO` | `AUTO` |
| `PREFER_HDR` | `PREFER_HDR` |
| `REQUIRE_HDR` | `REQUIRE_HDR` |
| `FORCE_SDR` | `FORCE_SDR` |

`REQUIRE_HDR` succeeds only after the engine confirms HDR output. KMediaPlayer does not maintain a
second WebGL/WebGPU renderer or infer output from source metadata. `VideoColorPipelineStatus` keeps
source metadata, decoded material and confirmed output distinct.

## Advanced Wasm API

`VideoPlayerState.webMediaAdvancedControls` exposes KMedia-owned types so public ABI does not leak
engine KLIB classes:

- `WebMediaSurface` and `WebMediaSnapshot`;
- `WebLivePlaybackWindow`;
- `WebAudioOutputDevice`;
- `WebSubtitleCue`;
- `PlaybackDiagnostics`.

The controls provide stable volume, audio-only playback, audio output routing, snapshots,
thumbnails, subtitle cue prefetch and seek-to-live.

## Composite development

Use the engine checkout through Gradle substitution:

```text
./gradlew :mediaplayer:compileKotlinWasmJs \
  -PkmediaWasmEngineProjectDir=/absolute/path/to/kmedia-wasm-engine
```

Useful verification commands are:

```text
./gradlew :mediaplayer:compileTestKotlinWasmJs \
  -PkmediaWasmEngineProjectDir=/absolute/path/to/kmedia-wasm-engine

./gradlew :mediaplayer:wasmJsBrowserTest \
  -PkmediaWasmEngineProjectDir=/absolute/path/to/kmedia-wasm-engine

./gradlew :sample:composeApp:wasmJsBrowserDistribution \
  -PkmediaWasmEngineProjectDir=/absolute/path/to/kmedia-wasm-engine
```

The sample has no legacy engine switch. It exercises the same session used by applications.

To verify the same boundary with locally published artifacts instead of Gradle substitution,
publish both engine modules to a local Maven repository and point KMediaPlayer at it:

```text
./gradlew :mediaplayer:wasmJsBrowserTest \
  -PkmediaWasmEngineMavenRepository=/absolute/path/to/local-repository \
  --dependency-verification=off
```

Dependency verification is disabled only for this disposable, locally generated repository.
Released artifacts remain covered by the checksums committed in KMediaPlayer.

## Attribution and licensing

`kmedia-wasm-engine` derives from movi-player. Versions through `0.4.0-alpha.2` retain the Apache
terms under which they were published. Starting with `0.4.0-alpha.3`, the Kotlin/Wasm KLIB is
proprietary and publishes only empty source-JAR placeholders. KMediaPlayer pins that line at
`0.4.0-alpha.4`; publish both matching engine artifacts before releasing KMediaPlayer `4.1.18`.

The separate native runtime/shim remains open and replaceable. The engine repository retains the
Apache NOTICE plus corresponding-source/build information for its bundled LGPL FFmpeg/Wasm
runtime assets. Historical TypeScript and web-component sources are not part of KMediaPlayer's
active browser implementation.
