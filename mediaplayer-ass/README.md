# Compose Media Player ASS

Optional authored ASS/SSA rendering for KMediaPlayer. The public API remains
`AssSubtitleExtension()`; the base `composemediaplayer` artifact contains no ASS
runtime and no backend.

## Install

Use the same version for the core and optional artifacts:

```kotlin
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation("io.github.shusek:composemediaplayer:<version>")
            implementation("io.github.shusek:composemediaplayer-ass:<version>")
        }
    }
}
```

Register one stable extension instance:

```kotlin
val playbackOptions = remember {
    VideoPlaybackOptions(
        extensions = listOf(AssSubtitleExtension()),
    )
}

val playerState = rememberVideoPlayerState(
    playbackOptions = playbackOptions,
)
```

Keep other optional extensions in the same list. Do not recreate the extension
or `VideoPlaybackOptions` on every recomposition.

## Shared native runtime

Android and desktop depend transitively on the exact matching
`KMediaAssRuntime 0.1.0-rc.6`. That artifact owns the single process-wide
libass 0.17.5, FreeType, FriBidi, and HarfBuzz stack.

`composemediaplayer-ass` owns only renderer glue:

- Android: one thin `libkmediaass.so` JNI bridge for `arm64-v8a` and
  `armeabi-v7a`;
- JVM: no native libraries; Java 25 FFM calls the loaded shared libass;
- iOS: one thin renderer archive for ARM64 device and ARM64 simulator;
- Wasm: the existing JASSUB 2.5.14 integration.

MPV and KMediaBridge reach the same ASS runtime through
`KMediaFfmpegRuntime`. Adding any combination of the three optional modules
therefore resolves one text stack. A mismatched runtime ID is rejected before
the renderer client loads.

## Platform behavior

| Platform | Behavior |
| :--- | :--- |
| Android | Full ASS/SSA styles, positioning, animation, karaoke, and supported embedded fonts. Media3 owns playback; the extension rasterizes subtitles and presents a transparent GLES overlay. API 23+ runtime, ARM64/ARMv7 only. |
| JVM desktop | Full rendering through the shared libass runtime and writable BGRA frames. macOS ARM64, Linux x86_64/ARM64, and Windows x86_64 are published. Launch Java 25 with `--enable-native-access=ALL-UNNAMED`. |
| iOS | Full external ASS/SSA rendering in a transparent UIKit overlay while AVPlayer keeps video decoding. ARM64 device/simulator only. The generated `ComposeMediaPlayerAss` pod depends on the exact `KMediaAssRuntime` pod. |
| Browser Wasm | JASSUB 2.5.14 renders selected external or engine-provided ASS/SSA through a transparent canvas. |

Without the optional artifact, or without registering the extension, ASS/SSA
uses the core/platform fallback without a full authored-style guarantee. SRT
and VTT do not require this module.

## Android details

The route supports external `.ass`/`.ssa`, raw embedded Matroska ASS/SSA,
supported font attachments, seeking, subtitle offsets, resize, and crop-to-fill
geometry. Script styles own presentation; Compose subtitle text/background
settings apply only to the fallback.

A custom `AndroidMediaSourceProvider` returning a complete `MediaSource` must
preserve the player's subtitle parser and extractor setup, otherwise it can
flatten ASS packets before the optional renderer receives them.

The adapter AAR intentionally contains no libass, FriBidi, FreeType, HarfBuzz,
source bundle, or LGPL notice copied from the runtime. Those files and
obligations are carried once by `kmedia-ass-runtime-android`.

## Desktop details

`KMediaAssRuntime.initialize(RuntimeSource.bundled())` extracts and verifies
the platform payload once, then `composemediaplayer-ass` resolves libass symbols
through FFM. The extension status reports a controlled failure and leaves the
existing subtitle fallback active if the runtime is unavailable.

There is no Homebrew/system-libass recovery path and no
`composemediaplayer.ass.libraryPath` override. Applications that need a
replaceable build select a complete compatible runtime directory through
`RuntimeSource.externalDirectory` before any native client is initialized.

Native Windows HDR, Linux Wayland color, and `LIBVLC_NATIVE` surfaces that do
not expose a writable CPU frame keep their platform subtitle renderer.

## iOS details

The checked renderer code calls libass and the `KMediaAssRuntime` identity
probe dynamically; neither libass nor FriBidi is merged into the client
archive. Release verification rejects private text libraries, Intel slices,
and a missing runtime-ID reference.

Embedded Matroska ASS extraction is not exposed by the AVFoundation route, so
iOS currently supports external ASS/SSA tracks. Playback, pause, seek,
subtitle offset, and surface resize remain synchronized with the overlay.

## Browser Wasm details

The module owns the pinned JASSUB npm dependency. Standard Kotlin/Wasm bundles
use module-relative worker, Wasm, and fallback-font URLs. Applications may
override them:

```kotlin
val assExtension =
    AssSubtitleExtension(
        config =
            AssSubtitleRendererConfig(
                workerUrl = "/jassub/worker/worker.js",
                wasmUrl = "/jassub/wasm/jassub-worker.wasm",
                modernWasmUrl = "/jassub/wasm/jassub-worker-modern.wasm",
                fallbackFontUrl = "/jassub/default.woff2",
                preloadFontUrls = listOf("/fonts/Brand-Regular.woff2"),
                availableFontUrls = mapOf("Brand Medium" to "/fonts/Brand-Medium.woff2"),
                fontQueryMode = AssFontQueryMode.DISABLED,
                debug = false,
            ),
    )
```

Cross-origin resources need suitable CORS headers. The multithreaded path also
needs:

```text
Cross-Origin-Embedder-Policy: require-corp
Cross-Origin-Opener-Policy: same-origin
```

Without those headers JASSUB uses its single-threaded fallback.
`AssFontQueryMode.LOCAL_AND_REMOTE` has additional network/privacy implications;
the default remains `DISABLED`.

## Licensing boundary

The adapter keeps KMediaPlayer's existing license. It is not relicensed merely
because it dynamically calls the separately published runtime.
`KMediaAssRuntime` carries its own LGPL/permissive notices, corresponding
source, SBOM, hashes, and replacement instructions. Web JASSUB and all other
third-party components retain their upstream licenses.
