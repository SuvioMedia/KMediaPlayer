# Compose Media Player ASS

Optional KMediaPlayer component for authored ASS/SSA subtitle presentation. The main
`composemediaplayer` artifact keeps a lightweight dialogue parser and does not package Android
libass libraries or the JASSUB browser runtime.

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

The dependency makes the platform implementation available. Rendering is activated explicitly by
adding one stable extension instance to `VideoPlaybackOptions`:

```kotlin
import androidx.compose.runtime.remember
import io.github.kdroidfilter.composemediaplayer.VideoPlaybackOptions
import io.github.kdroidfilter.composemediaplayer.ass.AssSubtitleExtension

val playbackOptions = remember {
    VideoPlaybackOptions(
        extensions = listOf(AssSubtitleExtension()),
    )
}

val playerState = rememberVideoPlayerState(
    playbackOptions = playbackOptions,
)
```

If an application already installs other pipeline extensions, keep them in the same list:

```kotlin
extensions = listOf(
    AssSubtitleExtension(),
    DolbyVisionExtension(),
)
```

Do not construct a new extension or `VideoPlaybackOptions` on every recomposition. A top-level,
owner-scoped or `remember`-created instance keeps the player backend and lifecycle stable.

## Platform behavior

| Platform | Optional backend | Behavior with `AssSubtitleExtension()` |
| :--- | :--- | :--- |
| Android | libass 0.17.5 | Full ASS/SSA styles, positioning, effects, animation and karaoke for external tracks and raw Matroska tracks; supported embedded font attachments are passed to libass. The AAR contains `arm64-v8a` and `armeabi-v7a`. |
| Browser Wasm | JASSUB 2.5.1 / libass Wasm | Full presentation for selected ASS/SSA sources through a transparent canvas overlay. The consumer bundler emits JASSUB's worker, Wasm modules and fallback font. |
| macOS JVM | Bundled libass 0.17.5 | Full ASS/SSA rendering in the Apple Silicon Compose/Skia canvas path with CoreText, complex HarfBuzz shaping and embedded Matroska font attachments. Intel macOS is not published. |
| Windows/Linux JVM | Bundled libass (Windows 0.17.4, Linux 0.17.5) | Full ASS/SSA rendering in writable BGRA frame paths, including styles, animation, karaoke and embedded Matroska fonts. The JAR carries x86_64 and ARM64 runtimes; users do not install libass. Windows uses DirectWrite and Linux uses the host's normal fontconfig configuration. Native HDR/color and native libVLC surfaces retain their platform subtitle route. |
| iOS | Bundled libass 0.17.5 | Full authored rendering for external ASS/SSA tracks on iOS arm64 and the arm64 Simulator. The extension uses CoreText and a transparent UIKit overlay; no x86 iOS target is built. Embedded Matroska ASS extraction is not yet exposed by AVFoundation, so this route is external-track only. |

Without the optional artifact, or without registering the extension, ASS/SSA remains selectable but
is rendered through the core/platform fallback without a full authored-style guarantee. SRT and VTT
never require this component.

## Android notes

Media3 continues to own playback and demuxing. The extension intercepts the raw Matroska ASS/SSA
route before Media3 flattens it, rasterizes with libass on the CPU and presents the result in a
transparent GLES overlay. GLES is only the presentation layer; the libass renderer is independent
of that uploader.

The Android route supports external `.ass`/`.ssa`, raw embedded Matroska ASS/SSA, supported font
attachments, seeking, subtitle offsets, resize and crop-to-fill geometry. ASS script styles control
the result, so `subtitleTextStyle` and `subtitleBackgroundColor` apply only to the Compose fallback.

A custom `AndroidMediaSourceProvider` returning a complete `MediaSource` must preserve the player's
subtitle parser and extractor setup. Otherwise it can flatten ASS packets before the optional
renderer receives them. Side-loaded Media3 SSA and HLS SSA keep Media3's standard path.

The Android AAR includes the native notices, corresponding source and LGPL replacement material
under `META-INF/kmediaplayer/android-ass`.

## Browser Wasm notes

The module owns the pinned `jassub` npm dependency. JASSUB resolves its default worker, normal and
SIMD Wasm modules, and fallback font through module-relative URLs, so consumers do not copy those
assets manually. Production applications may override the URLs before creating the player:

```kotlin
import io.github.kdroidfilter.composemediaplayer.AssSubtitleRendererConfig

AssSubtitleRendererConfig.workerUrl = "/jassub/worker/worker.js"
AssSubtitleRendererConfig.wasmUrl = "/jassub/wasm/jassub-worker.wasm"
AssSubtitleRendererConfig.modernWasmUrl = "/jassub/wasm/jassub-worker-modern.wasm"
AssSubtitleRendererConfig.fallbackFontUrl = "/jassub/default.woff2"
```

The defaults work with the standard Kotlin/Wasm browser bundle. Custom URLs may be relative to the
application base URL or absolute; cross-origin video, subtitle, worker, Wasm and font resources need
appropriate CORS headers. The browser requires `Worker`, WebAssembly, `OffscreenCanvas` and
`canvas.transferControlToOffscreen()`. If the optional renderer cannot become active, the player
keeps the Compose dialogue fallback.

`AssSubtitleRendererConfig.queryFonts` opts into browser font discovery where supported, and
`AssSubtitleRendererConfig.debug` enables JASSUB diagnostics. Android ignores these browser-only
settings.

## macOS JVM notes

The main player does not discover or load libass by itself. Add this artifact and register
`AssSubtitleExtension()`. The artifact carries its own architecture-specific renderer and the
separately replaceable `libkmediafribidi.dylib`; Homebrew, MacPorts and a system `libass` are not
used. This also keeps VLC and libass from competing for ownership of the same subtitle frame.

Availability is reported through `VideoPlaybackOptions.extensionStatuses`. If the library cannot be
loaded, the extension contributes no ASS formats and the player retains its dialogue fallback.

## Windows and Linux JVM notes

Windows and Linux use the stable Java 25 Foreign Function API. The JVM artifact bundles libass and
its private runtime files for x86_64 and ARM64, verifies every extracted file against its packaged
SHA-256 manifest, and loads the payload matching the running JVM. Application users do not install
libass or copy DLL/SO files. Launch with native access enabled:

```text
--enable-native-access=ALL-UNNAMED
```

Linux intentionally uses the desktop's normal fontconfig library and configuration so it sees the
same installed fonts as the rest of the application. Windows carries all DLL dependencies and
loads them from one private extracted directory.

An advanced deployment can override the bundled runtime with an exact compatible libass file or a
directory containing one. The override must be set before the extension is first queried:

```text
-Dcomposemediaplayer.ass.libraryPath=/opt/my-app/lib/libass.so.9
KMEDIA_ASS_LIBRARY_PATH=C:\my-app\lib\libass-9.dll
```

If an override is unavailable or incompatible, the loader tries the bundled runtime and finally a
compatible system library as a recovery path. External `.ass` and `.ssa` tracks work on writable
Compose/Skia video-frame paths. Embedded Matroska ASS/SSA tracks exposed by the libVLC canvas
backend are extracted together with supported font attachments. Native Windows HDR, Linux Wayland
color and `LIBVLC_NATIVE` surfaces do not expose a writable CPU frame, so they keep their
platform/native subtitle renderer. Any extraction, load or render failure leaves the existing
Compose or libVLC fallback active.

## iOS notes

The iOS implementation is published only for `iosArm64` and `iosSimulatorArm64`. Rendering is
hardware-independent subtitle rasterization: AVPlayer keeps its normal hardware video decoder while
libass generates only the transparent subtitle overlay. The overlay follows play, pause, seek,
subtitle offset and surface resize; while its source is loading or if initialization fails, the core
dialogue fallback remains visible.

Applications must add this artifact to `iosMain` and register the same `AssSubtitleExtension()`
instance in `VideoPlaybackOptions`. The sample project does both.

## Native licenses and distribution

libass itself is ISC licensed. Native payloads also contain permissively licensed FreeType,
HarfBuzz and, where enabled, libunibreak. FriBidi is LGPL-2.1-or-later and is deliberately kept
replaceable:

- macOS uses replaceable `libkmediafribidi.dylib` through `@loader_path`;
- Windows ships FriBidi as one of the sibling runtime DLLs;
- Linux uses `libkmediafribidi.so.0` through the libass `$ORIGIN` runpath;
- iOS links the distinct `libkmediafribidi.a` archive next to the renderer archive.

The exact FriBidi source, notices, reproducible builds and replacement instructions are in
[`native/apple`](native/apple) and [`native/desktop`](native/desktop). A distributor of a final
statically linked iOS application must also meet the LGPL relinking obligations described in
[`LGPL-RELINK.md`](native/apple/LGPL-RELINK.md). If that does not fit the application's
distribution model, omit `AssSubtitleExtension()` from the iOS source set; the base fallback remains
available.

Published root artifacts also provide the exact source and instructions in platform-specific ZIP
classifiers:

```text
io.github.shusek:composemediaplayer-ass:<version>:apple-lgpl-materials@zip
io.github.shusek:composemediaplayer-ass:<version>:desktop-lgpl-materials@zip
```
