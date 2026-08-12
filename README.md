# KMedia Wasm Engine

This branch is a headless browser media engine written for Kotlin/Wasm. Its public API is a typed
Kotlin KLIB; it does not publish a Kotlin/JS target, JavaScript compatibility facade, web component,
or npm API.

The proprietary player is maintained in the private
`SuvioMedia/KMediaWasmPlayer` repository and is derived from the original
[mrujjwalg/movi-player](https://github.com/mrujjwalg/movi-player). The open runtime source remains
at [Shusek/kmedia-wasm-engine](https://github.com/Shusek/kmedia-wasm-engine). Its FFmpeg, dav1d,
and Signalsmith Emscripten runtime remains the native media foundation. Attribution and third-party
terms are recorded in [NOTICE](NOTICE) and [LGPL_RELINKING.md](LGPL_RELINKING.md).

## Published artifacts

Version `0.4.0-alpha.3` consists of two coordinated Maven artifacts with an explicit license boundary:

| Artifact | Purpose |
|---|---|
| `io.github.shusek:kmedia-wasm-engine` | Proprietary Kotlin/Wasm KLIB with the typed player API |
| `io.github.shusek:kmedia-wasm-engine-runtime-assets` | Separately licensed ZIP containing the open native shim, JS loader, FFmpeg-based Wasm binary and ABI manifest under `kmedia-wasm-runtime/` |

The runtime archive is separate so a consumer can put the native payload at a stable public URL
without importing executable JavaScript dynamically. Both artifacts must use the same version.

## Consumer setup

Declare the KLIB in `wasmJsMain`:

```kotlin
kotlin {
    wasmJs {
        browser()
    }

    sourceSets {
        wasmJsMain.dependencies {
            implementation("io.github.shusek:kmedia-wasm-engine:0.4.0-alpha.3")
        }
    }
}
```

Resolve `io.github.shusek:kmedia-wasm-engine-runtime-assets:0.4.0-alpha.3@zip`, unpack its
`kmedia-wasm-runtime/` directory into the browser distribution, and pass its public base URL to
`WasmRuntimeConfig`. The base URL may be relative to the application:

```kotlin
import io.github.shusek.kmedia.engine.wasm.MediaSource
import io.github.shusek.kmedia.engine.wasm.PlayerSurface
import io.github.shusek.kmedia.engine.wasm.WasmMediaPlayer
import io.github.shusek.kmedia.engine.wasm.WasmMediaPlayerConfig
import io.github.shusek.kmedia.engine.wasm.WasmRuntimeConfig
import kotlinx.browser.document
import org.w3c.dom.HTMLElement

val container = document.getElementById("player") as HTMLElement
val player =
    WasmMediaPlayer(
        WasmMediaPlayerConfig(
            runtime = WasmRuntimeConfig(assetBaseUrl = "/assets/kmedia-wasm-runtime/"),
        ),
    )

player.load(MediaSource.Url("https://media.example/movie.mkv"))
when (val surface = player.surface.value) {
    is PlayerSurface.Canvas -> {
        container.appendChild(surface.element)
        surface.mediaElement?.let { container.appendChild(it) }
    }
    is PlayerSurface.NativeVideo -> container.appendChild(surface.element)
    null -> error("The engine did not expose a playback surface.")
}
player.play()
```

The API exposes typed state and event flows, composite/encrypted/DRM sources, independent video and
audio decoder backends, confirmed track selection, live/DVR state, cache/network/color diagnostics,
snapshots, thumbnails, cover art, audio-output routing, and a pluggable embedded-subtitle renderer.
Call `close()` when the host UI is disposed.

## Playback routes

- Every progressive source—including MP4, WebM, MKV, MOV, AVI, MPEG-TS, `blob:`, `data:` and browser
  `File`—uses the FFmpeg demuxer. `AUTO` then selects WebCodecs or the native software decoder
  independently for video and audio.
- VC-1 Advanced Profile, as found in older Blu-ray streams and remuxes, is decoded directly by the
  FFmpeg-Wasm software path because WebCodecs does not define a VC-1 codec registration.
- HLS, DASH, Smooth Streaming and EME use Shaka first. hls.js/dash.js are secondary engines, and a
  finite HLS VOD or DASH single-file source can fall back to FFmpeg when MSE cannot decode it.
- Software frames share the WebGL2 renderer with rotation, crop, HDR/PQ/HLG tone mapping and
  equirectangular/VR180/fisheye/SBS/little-planet projections. Planar/NV YUV is uploaded directly
  as GPU textures (without a per-pixel JavaScript conversion), while uncommon pixel formats use
  the RGBA fallback. The software runtime is built with `-O3`, LTO and WebAssembly SIMD128.
- FFmpeg demux/decode still uses the single-threaded Asyncify ABI and cooperative ~8 ms work
  slices; it is not presented as a DedicatedWorker implementation. Audible rendering runs on the
  browser's Web Audio render thread and is scheduled ahead of main-thread decode work.
- AudioContext is the master clock whenever audio is running. PCM batches are normalized onto one
  continuous scheduled timeline; underruns pause/rebuffer instead of letting video drift ahead.
  Software video that falls behind this clock drops late/disposable frames instead of visibly
  fast-forwarding to catch up.
- Media request headers and license request headers remain separate typed maps.
- ASS/SSA integrations can implement `EmbeddedSubtitleRenderer`; subtitle packets and font
  attachments cross a Kotlin interface rather than an untyped JavaScript object.

Chromium, Firefox and Safari/WebKit are release-gated in CI. Missing WebCodecs support selects the
software decoder; browser capabilities such as EME and audio-output routing still depend on the
host browser and permissions.

## KMediaPlayer integration

KMediaPlayer links the KLIB directly and packages the runtime ZIP as Compose resources. During
coordinated development, point its composite build at this checkout:

```shell
./gradlew :mediaplayer:wasmJsBrowserTest \
  -PkmediaWasmEngineProjectDir=/absolute/path/to/kmedia-wasm-engine
```

No `engine.js` import, npm dependency, `JsAny` player facade, or Kotlin/JS compatibility target is
involved.

## Build and verify

JDK 25 and a locally installed Chrome are required:

```shell
./gradlew :player:wasmJsBrowserTest
./gradlew :player:wasmJsBrowserTest -PkmediaWasm.testBrowser=firefox
./gradlew :player:wasmJsBrowserTest -PkmediaWasm.testBrowser=safari
./gradlew \
  :player:publishAllPublicationsToProjectLocalRepository \
  :runtime-assets:publishAllPublicationsToProjectLocalRepository
```

The local publication is written to `build/project-local-repository/`. Runtime publication first
checks `cdn/chunks/kmedia-wasm.js` and `cdn/chunks/kmedia-wasm.wasm` against `cdn/SHA256SUMS`.

The historical TypeScript/npm application sources remain in the repository for upstream history
and native-runtime maintenance, but they are not built or published by this Kotlin/Wasm project.
They must not be removed until every required row in
[the non-UI engine parity matrix](docs/engine-parity.md) is green.

## Migration

See [docs/KOTLIN_WASM_MIGRATION.md](docs/KOTLIN_WASM_MIGRATION.md) for the intentional
incompatibilities with the earlier JavaScript engine and
[docs/engine-parity.md](docs/engine-parity.md) for the versioned acceptance matrix.

## License boundary

The Kotlin/Wasm player KLIB is proprietary under the
[Suvio Proprietary Component License](player/legal/META-INF/LICENSE). Its Maven publications
use empty source JAR placeholders and must never publish the Kotlin implementation sources.

Do not release `0.4.0-alpha.3` while `player/` remains in a public source repository. Move the
proprietary module and its publication workflow to a private repository first; keep the native
shim, reproducible FFmpeg build, corresponding source, and runtime notices publicly available.
Empty source JARs prevent Maven leakage but cannot make a public Git tree closed source.

The historical TypeScript code and the narrow native C/C++ shim retain their inherited
Apache-2.0 terms. They are not the Suvio player API and are not packaged in the proprietary KLIB.
The separate runtime archive also contains FFmpeg and other components under their respective
terms; consult [NOTICE](NOTICE) and [LGPL_RELINKING.md](LGPL_RELINKING.md) before redistributing it.
Releases through `0.4.0-alpha.2` remain available under the terms under which they were originally
published; the proprietary line starts at `0.4.0-alpha.3`.
