# 🎥 Compose Media Player

<img src="/assets/banner.jpg" style="border-radius: 10px;"/>

[![Maven Central](https://img.shields.io/maven-central/v/io.github.shusek/composemediaplayer)](https://central.sonatype.com/artifact/io.github.shusek/composemediaplayer)
[![Kotlin](https://img.shields.io/badge/kotlin-multiplatform-blue.svg?logo=kotlin)](https://kotlinlang.org/docs/multiplatform.html)
[![Platforms](https://img.shields.io/badge/platforms-Android%20|%20iOS%20|%20macOS%20JVM%20|%20Windows%20JVM%20|%20Linux%20JVM%20|%20Web-lightgrey.svg)](https://github.com/SuvioMedia/KMediaPlayer)

**Compose Media Player** is a video player library designed for Compose Multiplatform, supporting multiple platforms including Android, macOS, Windows, and Linux. Desktop playback bridges are packaged in the JVM artifact and communicate through **pure JNI** — no JNA and no Java media bindings. The optional Windows/Linux ASS renderer uses Java 25's Foreign Function API to load its packaged libass runtime. Linux additionally requires a compatible GStreamer 1.x runtime and plugins to be installed on the host. The library leverages:

- **GStreamer** (via JNI) for Linux
- **Media Foundation** (via JNI) for Windows
- **AVPlayer** (via JNI) for macOS and iOS
- **Media3** for Android
- **kmedia-wasm-engine 0.4.0-alpha.2** for Kotlin/Wasm

## Project Status

This is a private, standalone SuvioMedia-maintained derivative used for internal projects and integrations. Immutable tagged artifacts are distributed only through Maven Central; GitHub Releases, GitHub Pages, and JitPack are not publication channels. Artifact availability is not an additional grant beyond the permissions in [LICENSE](LICENSE). If you need a supported public library, use the upstream project instead: [kdroidFilter/ComposeMediaPlayer](https://github.com/kdroidFilter/ComposeMediaPlayer).

## Table of Contents
- [Project Status](#project-status)
- [Features](#-features)
- [Supported Video Formats](#-supported-video-formats)
- [Supported Codec Checks](#-supported-codec-checks)
- [Installation](#-installation)
  - [Optional MPV backend](#optional-mpv-backend)
- [Compatibility Table](#-compatibility-table)
- [Getting Started](#-getting-started)
  - [Initialization](#initialization)
  - [Wasm playback engine](#wasm-playback-engine)
  - [Displaying the Video Surface](#displaying-the-video-surface)
    - [Content Scaling](#content-scaling)
    - [Surface type](#surface-type)
    - [3D and VR projection](#3d-and-vr-projection)
    - [Custom Overlay UI](#custom-overlay-ui)
  - [Video Playback via URL or Local Files](#video-playback-via-url-or-local-files)
  - [HLS Quality Selection](#hls-quality-selection)
  - [Chapters](#chapters)
  - [Full Controls](#full-controls)
  - [Progress Indicators](#progress-indicators)
  - [Error Handling](#error-handling)
  - [Diagnostics and Logging](#diagnostics-and-logging)
  - [Loading Indicator](#loading-indicator)
  - [Using Subtitles](#using-subtitles)
    - [Supported Formats](#-supported-formats)
    - [Adding Subtitles from URL or Local File](#-adding-subtitles-from-url-or-local-file)
    - [Android ASS/SSA subtitles with libass](#android-assssa-subtitles-with-libass)
    - [Windows and Linux ASS/SSA subtitles with libass](#windows-and-linux-assssa-subtitles-with-libass)
    - [Browser ASS/SSA subtitles with JASSUB/libass](#browser-assssa-subtitles-with-jassublibass)
    - [Selecting Embedded Audio and Subtitle Tracks](#selecting-embedded-audio-and-subtitle-tracks)
    - [Customizing Subtitle Appearance](#-customizing-subtitle-appearance)
    - [Disabling Subtitles](#-disabling-subtitles)
  - [Fullscreen Mode](#️-fullscreen-mode)
  - [Picture-in-Picture (PiP)](#-picture-in-picture-pip)
  - [Audio Mode](#-audio-mode)
- [Video Caching](#-video-caching)
- [Metadata Support](#-metadata-support)
  - [Example Usage](#example-usage)
  - [Basic Example](#-basic-example)
- [Roadmap](#-roadmap)

## ✨ Features

- **Multiplatform Support**: Works seamlessly on Android, macOS, Windows, Linux and Compose Web (Wasm).
- **File and URL Support**: Play videos from local files or directly from URLs.
- **Media Controls**: Includes play, pause, loop toggle, volume control, playback speed, loop playback and timeline slider.
- **Initial Playback Control**: Choose whether videos automatically play or remain paused after opening.
- **Custom Video Player UI**: Fully customizable using Compose Multiplatform, with support for custom overlays that display even in fullscreen mode.
- **Adaptive Streaming**: Native HLS playback on Android, iOS, macOS, Windows, and Linux. Wasm delegates HLS, DASH and Smooth Streaming to `kmedia-wasm-engine`; KMediaPlayer does not ship a second browser adaptive implementation.
- **Media Chapters**: Observe embedded chapter titles and time ranges, resolve the active chapter, and seek to a chapter through one common multiplatform API.
- **Fullscreen Mode**: Toggle between windowed and fullscreen playback modes.
- **Picture-in-Picture (PiP)**: Continue watching in a floating window on Android (8.0+) and iOS.
- **Audio Mode**: Configure audio interruption behavior and iOS silent switch handling.
- **Video Caching**: Opt-in Media3 disk caching on Android, ideal for scroll-based UIs.
- **3D/VR Projection**: Configure and auto-detect flat 3D, VR180, VR360, fisheye, and EAC projection modes. Android uses Media3's controlled frame processor, Apple targets use Metal, Windows uses a Media Foundation/D3D11 P010 presenter, Linux uses an optional P010/DMABuf Vulkan presenter, and Web delegates its controlled WebGL renderer to `kmedia-wasm-engine`.
- **Typed HDR pipeline**: `DynamicRangePolicy` selects a confirmed native HDR path, a controlled renderer, or a verified HDR-to-SDR fallback. `VideoColorPipelineStatus` reports source, decoder, active display, surface, metadata handling, planned output, confirmed output, and fallback reason separately.
- **Error handling** Simple error handling for network or playback issues.
- **Optional MPV backend**: `composemediaplayer-mpv` is an Android/iOS/JVM adapter
  with libass-based ASS/SSA subtitle support. The default artifact has no MPV
  dependency; applications add the adapter only to the platform source sets
  where they select it.
- **Optional advertising contracts**: `composemediaplayer-ads-core` contains the
  provider-neutral ad plan and playback state machine. The default player does
  not depend on it, so products without advertising do not resolve or package
  the module.

## ✨ Supported Video Formats
| Format | Windows                                                                                                           | Linux  | macOS & iOS                                                       | Android | WASM |
|--------|-------------------------------------------------------------------------------------------------------------------|-------------------|-----------------------------------------------------------------------------|-----------------|-------------------|
| **Player** | [MediaFoundation](https://learn.microsoft.com/en-us/windows/win32/medfound/microsoft-media-foundation-sdk) | [GStreamer](https://gstreamer.freedesktop.org/) | [AVPlayer](https://developer.apple.com/documentation/avfoundation/avplayer) | [Media 3](https://developer.android.com/media/media3) | [`kmedia-wasm-engine` 0.4.0-alpha.2](https://github.com/Shusek/kmedia-wasm-engine) |
| MP4 (H.264) | ✅                                                                                                                 | ✅ | ✅                                                                           | ✅ | ✅ |
| AVI | ❌                                                                                                                 | ✅ | ❌                                                                           | ❌ | ✅§ |
| MKV | ⚠️†                                                                                                                | ✅ | ⚠️†                                                                          | ✅ | ✅§ |
| MOV | ✅                                                                                                                 | ✅ | ✅                                                                           | ❌ | ✅ |
| FLV | ❌                                                                                                                 | ✅ | ❌                                                                           | ❌ | ❌ |
| WEBM | ⚠️†                                                                                                                | ✅ | ⚠️†                                                                          | ✅ | ✅ |
| WMV | ✅                                                                                                                 | ✅ | ❌                                                                           | ❌ | ✅§ |
| 3GP | ✅                                                                                                                 | ✅ | ✅                                                                           | ✅ | ❌ |
| MPEG-TS | ✅                                                                                                             | ✅ | ✅                                                                           | ✅ | ✅§ |
| HLS (M3U8) | ✅                                                                                                         | ✅ | ✅                                                                           | ✅ | ✅§ |
| MPEG-DASH (MPD) | Backend dependent                                                                                      | Backend dependent | Backend dependent                                               | ✅ | ✅§ |
| Smooth Streaming (MSS) | Backend dependent                                                                                | Backend dependent | Backend dependent                                               | ❌ | ✅§ |

> The Android artifact includes the matching Media3 HLS module; consumers do not need to add it separately.

> § Wasm links the exact `io.github.shusek:kmedia-wasm-engine:0.4.0-alpha.2` Kotlin/Wasm KLIB. Its matching runtime-assets ZIP is packaged transitively as Compose resources, so playback does not import executable code from a CDN. The engine preserves upstream attribution and ships notices, corresponding source, build recipes and relinking instructions for its LGPL FFmpeg/Wasm payload. KMediaPlayer mounts the surface returned by the engine and never owns a parallel HTML-video playback route.

> † On desktop JVM, MKV/WebM can use native playback, optional in-process libVLC, or the explicitly installed `composemediaplayer-kmediabridge` extension when the platform backend cannot demux the source directly. The default player contains no KMediaBridge or FFmpeg dependency. The optional bridge uses the process-wide `KMediaFfmpegRuntime`, shared with the MPV adapter; there is no `ffmpeg`/`ffprobe` executable or system installation. Its exact runtime dependency also supplies the single shared libass, FreeType, FriBidi and HarfBuzz stack. The bridge remuxes compatible local video/audio into bounded-memory CMAF/HLS without re-encoding; the macOS runtime can also tone-map explicit HDR10/HDR10+/HLG to limited-range BT.709 for `FORCE_SDR` and burn embedded text subtitles for confirmed SDR input. Strictly validated HEVC Main 10 HDR10/HDR10+/HLG remains tagged as an HDR sample-copy route under HDR-preserving policies; the platform color pipeline still has to confirm the decoder, surface and display before reporting HDR output. Unsupported conversion requests fail closed or use an explicitly available VLC fallback. See [JVM MKV/WebM fallback support](docs/macos-mkv-webm-support/README.md).

> HDR recognition is not treated as proof of HDR output. In particular, a libVLC native child view on Windows or Linux is not a confirmed HDR route. `AUTO` uses a verified SDR tone-map when the native HDR requirements are unavailable, while `REQUIRE_HDR` returns a typed color-pipeline error. See [HDR pipeline and platform status](docs/hdr-pipeline.md).

> iPhone and iPad are supported on a non-blocking [software-verified best-effort basis](docs/ios-best-effort-readiness.md): device arm64 builds, Simulator tests and shared Metal references are required, but this project has no physical iOS hardware and makes no physical HDR, Dolby Vision, 4K60 or thermal-performance claim. Runtime color status therefore fails closed whenever Apple does not explicitly confirm the format, display and surface.

> Flat Android video follows the platform-recommended MediaCodec + `SurfaceView` path; KMediaPlayer does not overwrite decoder buffers with a guessed dataspace. On Android 9+, a small read-only JNI bridge compares `ANativeWindow_getBuffersDataSpace()` with the decoded HDR10/HDR10+/HLG signal after the first frame. An exact match is `RENDERER_CONFIGURED`; Android 14+'s active HDR/SDR composition report is independent `SYSTEM_REPORTED` evidence and is retained only for the current decoded output signal, source, surface and display. Dolby Vision remains vendor-managed. If neither public signal confirms the route, `AUTO` uses a verified controlled or source-bridge SDR fallback when available, while `REQUIRE_HDR` fails instead of labeling inferred playback as HDR.

> On macOS JVM, `VideoPlayerSurface` and `DesktopPlaybackSurface` mount an AVFoundation/Metal, native MPV, or libVLC `NSView` directly inside the application's existing Nucleus Tao `NSWindow`, below the Compose controls. No second player window or AWT/JBR peer participates in this route. `DesktopVideoSurfaceMode.COMPOSE` remains available for feeds, galleries, and mini players that explicitly prefer frame-copy rendering. Runtime selection is controlled by the color policy; see [macOS color pipeline](docs/macos-color-pipeline.md) for the current behavior.

> Linux `libvlc-native-view` currently embeds VLC through an X11/XWayland xwindow. Native Wayland needs a separate `wl_surface` backend and is not implemented by this path.

## ✅ Media Support Preflight

The library exposes a single suspendable preflight query so apps can check player capabilities, source/container support and codecs before creating a `VideoPlayerState` or navigating to the player screen:

```kotlin
val mediaSupport = MediaSupport.query()

val mkvSupported = mediaSupport.capabilities.supportsMkv
val supportedSchemes = mediaSupport.capabilities.supportedUriSchemes
val canOpenSource = mediaSupport.canPlaySource(
    uri = "file:///movie.mkv",
    mimeType = "video/x-matroska",
)

val h264Supported = mediaSupport.isCodecSupported(MediaCodec.H264)
val supportedVideoCodecs = mediaSupport.videoCodecs
val supportedAudioCodecs = mediaSupport.audioCodecs
val supportedCodecs = mediaSupport.allCodecs

val supportedHdr = mediaSupport.supportedHdrDynamicRanges
val hdr10PlusSupport = mediaSupport.dynamicRangeSupport(VideoDynamicRange.HDR10_PLUS)
val showHdr10PlusSources = hdr10PlusSupport == VideoDynamicRangeSupport.SUPPORTED
```

`MediaSupport.query()` checks the current platform contract for URI schemes and known container/adaptive formats such as MKV and HLS, plus codec support reported by the backend. On browser targets codec support reflects the engine's browser and software decode paths rather than `HTMLVideoElement.canPlayType`.

Display support is a preflight snapshot of the active output, not evidence that a future playback
surface reached HDR. `UNSUPPORTED` means the platform produced a known capability set without that
range; `UNKNOWN` remains distinct so strict source catalogs can require `SUPPORTED`. Query again
after an HDMI or monitor change before filtering a new source list.

> [!NOTE]
> Media support can still depend on the device, OS version, browser, installed system components, or GStreamer plugins. Android and web targets query the current runtime where possible. Desktop and iOS expose conservative backend capability lists. Query methods are suspend functions so callers can run them from `LaunchedEffect`, `async`, `produceState`, or their own coroutine/lazy strategy.

## 🔧 Installation

> [!IMPORTANT]
> These coordinates reproduce the maintainer's tagged internal artifacts. Their availability does not change the permitted uses stated in [LICENSE](LICENSE). Every published artifact uses an immutable SemVer tag, including RC tags; `dev` builds are local-only.

Compose Media Player is resolved directly from Maven Central:

```kotlin
dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}
```

Then include the dependency in your module `build.gradle.kts` file:

```kotlin
dependencies {
    implementation("io.github.shusek:composemediaplayer:<version>")
}
```

Pipeline integrations are separate modules:

```text
composemediaplayer-ads-core ────────────────────────────────────────────────> optional ad orchestration
composemediaplayer-ass ──────────┐
composemediaplayer-dolbyvision ──┼──> composemediaplayer-extension-api ──> composemediaplayer-core
composemediaplayer-kmediabridge ─┘
composemediaplayer-desktop-tao ───────────────────────────────────────────────> composemediaplayer-core
composemediaplayer-mpv ─────────────────────────────────> composemediaplayer-desktop-tao
composemediaplayer ──────────────┬──> composemediaplayer-extension-api
                                 └──> composemediaplayer-desktop-tao
```

Add advertising only in products that enable it:

```kotlin
dependencies {
    implementation("io.github.shusek:composemediaplayer-ads-core:<version>")
}
```

The MPV and KMediaBridge adapters are optional. When both are present they transitively select their
own thin native clients and the same exact KMediaFfmpegRuntime; the base `composemediaplayer`
artifact still contains neither backend.

JVM applications use `composemediaplayer-desktop-tao` for transactional
playback sessions and Nucleus Tao native-surface interop. It is available
transitively from the default player and MPV JVM artifacts, or can be declared
directly by a coordinator module:

```kotlin
dependencies {
    implementation("io.github.shusek:composemediaplayer-desktop-tao:<version>")
}
```

`DesktopPlaybackSession` owns one full player, selects platform direct →
KMediaBridge remux → MPV → libVLC → KMediaBridge transcode, and switches
transactionally with rollback. `DesktopPlaybackSurface` renders that session in
the application's current Tao window and reports native-surface attachment back
to the session. It never creates an OS window. `VideoPlayerSurface` remains the
right API when the application owns a single player state directly.

The desktop application owns its one Nucleus Tao window. Playback is ordinary
content inside that window:

```kotlin
fun main(args: Array<String>) = nucleusApplication(args, backend = NucleusBackend.Tao) {
    val app = this
    app.DecoratedWindow(
        onCloseRequest = app::exitApplication,
        title = "My app",
        nativePopupLayers = true,
    ) {
        App()
    }
}
```

The current `4.0.x` desktop line is verified with Nucleus `2.2.0` and Compose Multiplatform `1.11.1`.
It deliberately does not initialize AWT, Swing, JAWT, or a JetBrains Runtime window toolkit.
See [Migrating from 3.x to 4.0](docs/migration-4.0.md) for the required desktop bootstrap and API changes.

Normal applications receive `composemediaplayer-extension-api` transitively. Add it directly only
when authoring an extension. An optional artifact has no effect until its stable extension instance
is registered in `VideoPlaybackOptions.extensions`; `extensionStatuses` exposes its typed
`AVAILABLE`, `DEGRADED`, or `UNAVAILABLE` runtime state without turning recognition into a
capability claim.

Full ASS/SSA presentation is an optional component. Add it with the same version as the core
artifact and install its extension in the player options:

```kotlin
dependencies {
    implementation("io.github.shusek:composemediaplayer-ass:<version>")
}
```

```kotlin
val playbackOptions = remember {
    VideoPlaybackOptions(
        extensions = listOf(AssSubtitleExtension()),
    )
}
val playerState = rememberVideoPlayerState(playbackOptions = playbackOptions)
```

The dependency supplies the platform integration (and the bundled runtime on platforms documented
as bundled) but does not take over subtitle rendering.
Without `AssSubtitleExtension()`, ASS/SSA keeps the lightweight core fallback and the application
does not receive libass or JASSUB from the core artifact. See the
[ASS component documentation](mediaplayer-ass/README.md) for platform behavior and configuration.

Android applications that opt into the KMediaBridge HDR-to-SDR source bridge add only the adapter
with the same version as KMediaPlayer:

```kotlin
dependencies {
    implementation("io.github.shusek:composemediaplayer-kmediabridge:<version>")
}
```

Install `KMediaBridgeAndroidExtension()` in `VideoPlaybackOptions.extensions`. The adapter is also
published for desktop JVM, where `KMediaBridgeDesktopExtension()` enables bounded container,
sample-copy and controlled SDR routes. The default player has no KMediaBridge dependency. The
client and shared KMediaFfmpegRuntime are transitive dependencies with their own license notices,
source, SBOM and relinking materials;
an explicitly selected external compatible runtime remains the application's responsibility. See the
[KMediaBridge adapter documentation](mediaplayer-kmediabridge/README.md).

The optional pinned `libdovi` bridge is published separately:

```kotlin
dependencies {
    implementation("io.github.shusek:composemediaplayer-dolbyvision:<version>")
}
```

It provides the pinned `libdovi` converter plus bounded playback bridges for unencrypted flat MP4,
fMP4 HLS VOD, and Matroska on Android, iOS, JVM, and browser Wasm. The common Matroska path preserves
AAC, Opus, AC-3, and E-AC-3; JVM can additionally use FFmpeg for compatible inputs outside that
subset. Install `DolbyVisionExtension()` in `VideoPlaybackOptions.extensions` to opt in; live HLS
and DRM fail closed. See [the component contract](mediaplayer-dolbyvision/README.md).
With `DolbyVisionPolicy.AUTO`, confirmed native Profile 7 remains first choice; the bridge maps P7
to P8.1 only when the active platform confirms P8 but not P7, before HDR10-base and managed-SDR
fallbacks. Planned and confirmed profile/mapping fields expose the decision without claiming an
unverified P8.1 output.

The conversion bridge accepts selected fMP4 media playlists and master HLS VOD. Android, iOS, and
JVM retain the selected variant's referenced VOD audio/subtitle renditions through their local HLS
proxy. Browser Wasm preserves the declared default external fMP4 audio rendition with a separate,
bounded Media Source buffer when its codec is supported; unsupported layouts fail closed instead
of silently dropping audio. Its video/audio buffers use parsed fMP4 clocks for initial, seek, and
discontinuity timestamp mapping, and a non-independent seek restarts only from a verified sync sample.

### Build and runtime requirements

- **Build JDK and desktop runtime:** Java 25 or newer. Desktop JVM classes are intentionally published as Java 25 bytecode; Java 17/21 are not supported.
- **Gradle:** use the checked-in Gradle 9.6.1 wrapper, whose distribution checksum is verified.
- **Kotlin/Compose:** Kotlin 2.4.10 and Compose Multiplatform 1.11.1 for the current `4.0.x` line.
- **Android:** the library is built and verified for `arm64-v8a` and `armeabi-v7a` with AGP 9.3.1, `compileSdk = 37`, and `minSdk = 23`. Android x86/x86_64 is unsupported. Consumer projects should use the same or a newer compatible Android toolchain and must have `compileSdk >= 37`; HDR still depends on the device and active display.
- **Android MPV adapter:** `composemediaplayer-mpv` uses `minSdk = 28` and publishes ARM runtimes only.
- **iOS MPV adapter:** publishes `iosArm64` and `iosSimulatorArm64`. Its generated CocoaPod has an
  exact dependency on the code-signed KMediaMpv client pod, which in turn pins the shared runtime;
  native code is embedded by the application build and is never extracted at playback time.
- **Windows MPV adapter:** the verified KMediaMpv desktop artifact supplies its 64-bit client DLLs
  transitively on Java 25. Windows ARM64 is unsupported.
- **iOS:** iOS 16.2 or newer is required. Published targets are `iosArm64` and `iosSimulatorArm64` only; Intel simulators are unsupported. An Apple Silicon macOS host and a current Xcode/iOS Simulator are required for native compilation and tests.
- **macOS JVM:** Apple Silicon and macOS 14 or newer are required by the native AVFoundation/Metal bridge.
- **Windows JVM:** Windows 10 1809 (build 17763) or newer is required; confirmed HDR additionally requires an active Advanced Color output and the packaged P010/D3D11 presenter.
- **Linux desktop runtime:** Nucleus Tao owns the GTK3 window. Ordinary SDR playback uses system GStreamer 1.x core, base libraries, and the plugins required by the media formats you use. On Debian/Ubuntu the CI baseline is `libgtk-3-0`, `libgstreamer1.0-0`, `libgstreamer-plugins-base1.0-0`, `gstreamer1.0-plugins-base`, and `gstreamer1.0-plugins-good`. Confirmed HDR additionally requires GStreamer 1.28.5+, a native Wayland session, `color-management-v1`, and the advertised Vulkan/DMABuf/output capabilities. X11/XWayland is not a confirmed HDR route.
- **Building the Linux JNI bridge:** additionally install CMake, `pkg-config`, a C compiler, full JDK 25 JNI headers, `libgtk-3-dev`, `libgstreamer1.0-dev`, and `libgstreamer-plugins-base1.0-dev`.
- **Building the macOS/Windows bridges:** use full JDK 25 JNI headers plus Xcode/Swift on macOS or Visual Studio with CMake on Windows.

### Optional MPV backend

MPV is a separate adapter artifact. The dependency direction is:

`composemediaplayer` → `composemediaplayer-core` ← `composemediaplayer-mpv`

The main artifact never imports MPV. Both modules share only the state, event,
capability, backend-factory, and renderer SPI from `composemediaplayer-core`.
On Android and desktop JVM, the MPV adapter carries the matching KMediaMpv client transitively, so
the application does not add KMediaMpv coordinates separately. On iOS, the exact `KMediaMpv` pod
contains the two client XCFrameworks and depends on the exact shared runtime pod. The adapter never
downloads native code at playback time.

This dependency inversion is the reason for the `2.0.0` major version. Existing
applications can keep depending on `composemediaplayer`; it re-exports `core`
transitively. Backend authors may depend directly on `composemediaplayer-core`
and do not pull the default Media3/AVPlayer/JNI implementations.

Add the adapter only to supported platform source sets:

```kotlin
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation("io.github.shusek:composemediaplayer:<player-version>")
        }
        androidMain.dependencies {
            implementation("io.github.shusek:composemediaplayer-mpv:<player-version>")
        }
        jvmMain.dependencies {
            implementation("io.github.shusek:composemediaplayer-mpv:<player-version>")
        }
        iosMain.dependencies {
            implementation("io.github.shusek:composemediaplayer-mpv:<player-version>")
        }
    }
}
```

The MPV-specific wiring below belongs in `androidMain`, `jvmMain`, or `iosMain`.
A library whose complete target matrix is supported by the adapter may put the
dependency in `commonMain` instead.

An MPV-only application does not need `composemediaplayer` at all. Depend on
`composemediaplayer-mpv` (which brings `composemediaplayer-core` transitively)
and render with `BackendVideoPlayerSurface`. This avoids pulling Media3,
AVPlayer, or the default desktop JNI implementations:

```kotlin
val playerState = rememberMpvVideoPlayerState()

BackendVideoPlayerSurface(
    playerState = playerState,
    modifier = Modifier.fillMaxSize(),
)
```

Then select MPV explicitly:

```kotlin
val playerState = rememberMpvVideoPlayerState()

VideoPlayerSurface(
    playerState = playerState,
    modifier = Modifier.fillMaxSize(),
    contentScale = ContentScale.Fit,
)
```

Call `inspectMpvBackend()` before creating the state when the application wants
to present its own unsupported-device message.

For dependency injection, select the implementation in the application
composition root without exposing it to the rest of the app:

```kotlin
val backend: VideoPlayerBackend = mpvVideoPlayerBackend()
val playerState = rememberVideoPlayerState(backend)
```

The default runtime source is the verified KMediaMpv bundle. Keep that default
on Android, Linux, Apple Silicon macOS, and Windows x86_64. An application may
still override it with an absolute, application-supplied libmpv path:

```kotlin
val options = MpvPlaybackOptions(
    runtimeSource = MpvRuntimeSource.ExplicitPath(
        "C:\\Program Files\\MyApp\\runtime\\mpv-2.dll",
    ),
)
```

The macOS MPV renderer defaults to `MpvMacRenderer.MOLTENVK`. A libmpv carrying
KMediaMpv's versioned embedded-macvk capability uses the windowless
`gpu-next`/Vulkan route in the Compose-owned Metal surface:

```kotlin
val options = MpvPlaybackOptions(
    runtimeSource = MpvRuntimeSource.ExplicitPath("/Applications/MyApp.app/Contents/Frameworks/libmpv.dylib"),
    macRenderer = MpvMacRenderer.MOLTENVK,
)
```

This is already the default, so the explicit option is needed only when making
the renderer policy visible in application configuration. An unmarked or
failing runtime falls back to OpenGL; stock mpv is never allowed to create a
separate macOS window. Enabling the public projection transform also switches
that player to OpenGL because the projection pass currently uses the libmpv
OpenGL render API. The bundled runtime must carry MoltenVK and KMediaMpv's
versioned macvk capability to keep the default route on Vulkan.

On iOS, use the generated `ComposeMediaPlayerMpv` pod so CocoaPods embeds and
code-signs the exact `KMediaMpv`, `KMediaFfmpegRuntime`, and `KMediaAssRuntime`
framework graph. The default `Bundled` source resolves that graph. An
application-supplied framework can still be selected with `System` or an
`ExplicitPath` inside the signed application bundle.

The iOS MPV renderer defaults to `MpvIosRenderer.MOLTENVK`: KMediaPlayer owns
the `UIView`/`CAMetalLayer`, while KMediaMpv's versioned `iosvk` context runs
mpv `gpu-next` through Vulkan and the packaged MoltenVK framework. This route
does not use OpenGL ES. A missing capability or failed Vulkan video output
falls back to the bounded software render API; `MpvIosRenderer.SOFTWARE`
selects that fallback explicitly.

`inspectMpvBackend()` reports a missing runtime, unsupported target, or rejected
native payload without falling back silently. `rememberVideoPlayerState()`
continues to select the normal platform backend.

The verified bundled runtime supports Android API 28+ on `arm64-v8a` and
`armeabi-v7a`, Linux on x86_64 and ARM64, macOS on ARM64, and Windows x86_64.
The exact iOS pod supports ARM64 device/simulator. Android x86, Android x86_64,
macOS x86_64, Intel iOS simulators, and Windows ARM64 are not supported. The
bundled runtime rejects direct remote input. On macOS ARM64,
`DesktopPlaybackSession` can nevertheless play internet HLS through
`JvmHttpHlsMediaProxyFactory`: HTTPS, redirects, headers, cookies, and manifest
rewriting stay in the JVM, while libmpv receives only an uncredentialed numeric
loopback URL. Other bundled targets continue to accept local media and subtitle
files only.
Desktop applications run on Java 25 with `--enable-native-access=ALL-UNNAMED`.

The core, default player, and adapter code remain under this repository's own
license. The separately
licensed [KMediaMpv](https://github.com/Shusek/KMediaMpv) artifacts carry the
reviewed native payload, notices, exact corresponding source, and recipient
relinking path required for the LGPL components. Only the KMediaMpv/native
boundary is subject to those separate terms; the application and the
backend-neutral player modules are not relicensed as LGPL.

See [Player backend architecture](docs/backend-architecture.md) for the module
rules and the checklist for implementing another backend.

### Optional bundled libVLC 4 backend

The optional `composemediaplayer-libvlc` adapter consumes an audited KMediaVlc runtime and never
downloads VLC at playback time. On desktop, `inspectLibVlcBackend()` checks the payload manifest
before native code is loaded. Flat playback uses the platform GPU transport selected by KMediaVlc:
D3D11 on Windows, IOSurface/OpenGL on Apple Silicon macOS, and DMA-BUF/GLES2 on Linux.

Projected, stereo, rotated, or cropped video currently uses the bounded RGBA8 CPU-pull route and
the shared Skia projection shader. Configure the initial projection in `LibVlcPlaybackOptions` so
`AUTO` can choose that route:

```kotlin
val playerState = rememberLibVlcVideoPlayerState(
    LibVlcPlaybackOptions(
        projection = VideoProjectionSettings(
            projectionType = VideoProjectionType.Equirect360,
        ),
        projectionViewControlMode = VideoProjectionViewControlMode.MANUAL,
    ),
)
```

This fallback is intentionally SDR and therefore rejects `REQUIRE_HDR`. `GPU_PUSH` with a
projection also fails closed until KMediaVlc exposes a native GPU projection pass. If projection
will be changed after creating the state, select `CPU_PULL` explicitly; a GPU player is not silently
recreated or allowed to display the wrong flat image.

Android uses the bundled KMediaVlc AAR and libVLC's direct Surface transport on API 28+ ARM64 and
ARMv7 devices. It accepts local paths, `file:`, `content:`, and HTTP(S) sources and exposes
automatic or software-only decoding through `LibVlcAndroidDecodeMode`. The Android probe loads the
native graph and checks its path-free build identity before creating a player. Explicit
`GPU_PUSH`/`CPU_PULL`, projected or cropped video, non-`AUTO` dynamic-range policies, and non-default
Dolby Vision policies all fail closed because this first Surface bridge cannot render projection or verify
the final color pipeline. Its first bounded layout contract accepts `ContentScale.Fit`; other
scales fail closed instead of silently displaying different geometry. A delayed callback from an
old Surface cannot detach a newer fullscreen Surface.

iOS ARM64 device and simulator variants consume the exact KMediaVlc CocoaPod and resolve its signed
XCFramework graph only from the application's private `Frameworks` directory; there is no system
VLC or user-installed fallback. The first iOS transport is bounded CPU pull: KMediaVlc supplies
premultiplied RGBA8/sRGB frames, the adapter copies them into owned CoreGraphics images, and Compose
renders those images through UIKit. Local paths, `file:`, and HTTP(S) sources are accepted.
Projection/crop, `GPU_PUSH`, non-`AUTO` dynamic-range policies, and non-default Dolby Vision
policies fail closed until the native output and color pipeline can be verified.

The Android runtime and Apple XCFramework graph remain release candidates until their legal and
physical-device gates are complete; successful source and host tests do not make those payloads
publication-eligible.

The desktop host acceptance gate decodes a real fixture through a relocated bundled libVLC 4
runtime, waits for multiple CPU frames, and sends one of those frames through the production Skia
stereo/360 projection shader. It verifies visible output for both eyes and a changed output after a
90-degree yaw. Run it only against clean, exact KMediaPlayer and KMediaVlc commits:

```shell
./.github/scripts/run_libvlc_vr_acceptance.sh \
  /absolute/path/to/KMediaVlc \
  /absolute/path/to/relocated-kmediavlc-runtime \
  /absolute/path/to/fixture.mkv \
  <full-kmediaplayer-commit> \
  <full-kmediavlc-commit>
```

The runner derives the platform bridge from the relocated runtime and rejects partial inputs,
symlinks, dirty trees, commit mismatches, and unsupported hosts. This is deterministic offscreen
consumer evidence; it is not a physical headset, display, GPU-import, or render-node acceptance
result.

Before KMediaVlc reaches a public version, its published-adapter consumer check uses the local
composite explicitly and runs in two Gradle invocations:

```shell
./gradlew publishLibVlcConsumerSmokeArtifacts \
  -PpublicationVersion=0.0.0-consumer.local \
  -PkmediaVlcProjectDir=/absolute/path/to/KMediaVlc \
  --no-configuration-cache
python3 .github/scripts/verify_maven_poms.py \
  build/consumer-repository \
  0.0.0-consumer.local
./gradlew libVlcConsumerSmokeTest \
  -PpublicationVersion=0.0.0-consumer.local \
  -PkmediaVlcProjectDir=/absolute/path/to/KMediaVlc \
  --no-configuration-cache
```

The repository commits Kotlin/Wasm Yarn lockfiles. CI fails instead of silently replacing them when an npm dependency changes; run `./gradlew kotlinWasmUpgradeYarnLock` after an intentional npm update and review the resulting lockfile diff.

Gradle dependency verification pins external build artifacts by SHA-256. The only checksum exception is restricted to the locally generated `dev` and `0.0.0-consumer.*` smoke-test publications under this project's own coordinates, whose binaries change with the source under test.

## 📊 Compatibility Table

| Library Version | Kotlin Version | Compose Version | Java | Android toolchain |
|-----------------|----------------|-----------------|------|-------------------|
| 4.0.x           | 2.4.10         | 1.11.1          | 25   | AGP 9.3.1 / compileSdk 37 / minSdk 23; MPV/libVLC minSdk 28 |
| 3.0.x           | 2.4.10         | 1.12.0-beta03   | 25   | AGP 9.3.1 / compileSdk 37 / minSdk 23; MPV minSdk 28 |
| 2.0.x           | 2.4.0          | 1.11.1          | 25   | AGP 9.2.1 / compileSdk 37 / minSdk 23; MPV minSdk 28 |
| 1.0.x           | 2.4.0          | 1.11.1          | 25   | AGP 9.2.1 / compileSdk 37 / minSdk 23 |
| 0.9.0           | 2.3.20         | 1.10.3          | Not recorded | Not recorded |
| 0.8.6           | 2.3.0          | 1.9.3           | Not recorded | Not recorded |
| 0.8.3           | 2.2.20         | 1.9.0           | Not recorded | Not recorded |
| 0.7.11          | 2.2.0          | 1.8.2           | Not recorded | Not recorded |
| 0.7.10          | 2.1.21         | 1.8.2           | Not recorded | Not recorded |

The current build records the public Kotlin ABI in version control. `checkKotlinAbi`, the published-consumer smoke project, platform tests, and the Java 25 classfile check must all pass before the release workflow publishes the GAV and native payload to Maven Central. To reproduce the isolated consumer check locally, first run `./gradlew publishConsumerSmokeArtifacts -PpublicationVersion=0.0.0-consumer.local -PcomposeMediaPlayer.skipNativeBuild=true --no-configuration-cache`, then run `./gradlew consumerSmokeTest -PpublicationVersion=0.0.0-consumer.local -PcomposeMediaPlayer.skipNativeBuild=true --no-configuration-cache` in a separate Gradle invocation. The split is intentional: the Maven fixtures must exist before Gradle resolves the consumer configurations.

The release workflow first publishes every module into a runner-local staging repository and validates the complete coordinates and POM metadata. It then performs the irreversible Maven Central publication and waits for every expected artifact to become available. Do **not** rerun publication for a version already accepted by Maven Central.

## 🚀 Getting Started

### Initialization

Before using Compose Media Player, you need to create a state for the video player using the `rememberVideoPlayerState` function:

```kotlin
val playerState = rememberVideoPlayerState()
```

### Wasm playback engine

Wasm links the typed `io.github.shusek:kmedia-wasm-engine:0.4.0-alpha.2` KLIB directly. The public
state, Compose surface, controls, overlays and fullscreen API stay in KMediaPlayer, while source
routing, browser media elements, decoding, clocks and rendering stay in the engine. Matching
`kmedia-wasm.js`, `kmedia-wasm.wasm`, and `kmedia-wasm-runtime.json` files come from
`kmedia-wasm-engine-runtime-assets` and are packaged transitively as Compose resources.

```kotlin
val options = remember {
    VideoPlaybackOptions(
        webDecoderPreference = WebDecoderPreference.AUTO,
    )
}
val playerState = rememberVideoPlayerState(playbackOptions = options)
```

The generated Compose resource URL is used by default. Offline, strict-CSP, or controlled-hosting
deployments can copy the same immutable runtime files elsewhere and replace their base URL before
opening the first Wasm source:

```kotlin
WebMediaDependencyConfig.kmediaWasmRuntimeAssetBaseUrl = "/vendor/kmedia-wasm-runtime/"
```

Keep the KLIB and runtime ZIP versions identical. The configured location is public runtime content;
never place credentials in its URL.

There is one Wasm playback route. Progressive and local sources use the engine demuxer; adaptive
and DRM sources use its browser backend. DRM combined with projection, crop, or forced pixel
transforms fails closed because protected pixels cannot be read into the controlled canvas.

For adaptive DRM, media headers and license headers are intentionally separate:

```kotlin
val options = VideoPlaybackOptions(
    webDrmConfiguration = WebDrmConfiguration(
        licenseUrl = "https://license.example.invalid/widevine",
        licenseRequestHeaders = mapOf("Authorization" to "<runtime value>"),
    ),
)

playerState.openUri(
    uri = "https://media.example.invalid/manifest.mpd",
    requestHeaders = mapOf("X-Media-Token" to "<runtime value>"),
)
```

DRM values are retained only by the runtime objects. `WebDrmConfiguration.toString()`, diagnostics
and adapter errors redact license URLs and headers. Applications must likewise avoid logging or
persisting their input values. DRM uses the engine/Shaka protected native surface.

The engine reports source metadata, decoded material, and confirmed output dynamic range
separately. Projection, crop, rotation and `FORCE_SDR` are performed by its controlled renderer.
External ASS/SSA can use the optional JASSUB overlay; embedded ASS/SSA packets and bounded font
attachments are delivered through the engine's typed renderer contract. See
[Wasm engine integration](docs/wasm-engine.md).

The Wasm sample starts with a bundled MKV containing `en` and `pl` Opus tracks and has no engine
switch.

### Displaying the Video Surface

After initializing the player state, you can display the surface of the video using `VideoPlayerSurface`:

```kotlin
// Video Surface
Box(
    modifier = Modifier.weight(1f).fillMaxWidth(),
    contentAlignment = Alignment.Center
) {
    VideoPlayerSurface(
        playerState = playerState,
        modifier = Modifier.fillMaxSize()
    )
}
```

#### Content Scaling

> [!WARNING]
> Content scaling support is experimental. The behavior may vary across different platforms.

You can control how the video content is scaled inside the surface using the `contentScale` parameter:

```kotlin
VideoPlayerSurface(
    playerState = playerState,
    modifier = Modifier.fillMaxSize(),
    contentScale = ContentScale.Crop // Default is ContentScale.Fit
)
```

Available content scale options:
- `ContentScale.Fit` (default): Scales the video to fit within the surface while maintaining aspect ratio
- `ContentScale.Crop`: Scales the video to fill the surface while maintaining aspect ratio, potentially cropping parts
- `ContentScale.FillBounds`: Stretches the video to fill the surface, may distort the aspect ratio
- `ContentScale.Inside`: Similar to Fit, but won't scale up if the video is smaller than the surface
- `ContentScale.None`: No scaling applied

#### Surface type

> [!WARNING]
> Surface type parameter is supported only for Android target.

Available surface type options:
- `SurfaceType.Auto` (default): uses `SurfaceView` for flat video, which is Android's HDR-eligible system path, and the controlled Media3 frame processor for projected/stereo video.
- `SurfaceType.SurfaceView`: uses SurfaceView for the player view, which is more performant for video playback but has limitations in terms of composability and animations.
- `SurfaceType.TextureView`: uses TextureView for the player view, which allows for more complex composable layouts and animations.
- `SurfaceType.SphericalGlSurfaceView`: uses Media3's spherical GL surface explicitly. This is intended for equirectangular 360 projection on Android.
- `SurfaceType.ProjectedGlSurfaceView`: uses `DefaultVideoFrameProcessor` and the Android projection GL effect explicitly. This supports flat stereo, equirectangular, fisheye and EAC projection.

```kotlin
VideoPlayerSurface(
    playerState = playerState,
    modifier = Modifier.fillMaxSize(),
    surfaceType = SurfaceType.SurfaceView // Default is SurfaceType.Auto
)
```

#### 3D and VR projection

Projection settings live in `VideoPlaybackOptions`. By default, the player auto-detects common projection tokens from filenames, URLs and metadata when the configured projection is left as flat 2D:

```kotlin
val playerState = rememberVideoPlayerState()

// Example_VR180_SBS.mp4 is detected as equirectangular 180 side-by-side.
playerState.openUri("https://example.com/Example_VR180_SBS.mp4")
```

Apps can pass a known mode when creating the player state:

```kotlin
val playerState = rememberVideoPlayerState(
    playbackOptions = VideoPlaybackOptions(
        projection = VideoProjectionSettings(
            projectionType = VideoProjectionType.Equirect180,
            stereoLayout = VideoStereoLayout.SideBySide,
            eyeOrder = VideoEyeOrder.LeftRight,
        )
    )
)
```

The projection can also be changed while the player is alive, which is useful for manual projection controls:

```kotlin
playerState.projection = playerState.projection.nextVideoProjectionPreset().projection
```

For sources that include padded or misaligned active video, `projectionTextureCrop` crops the source texture before
stereo splitting and projection. This is separate from `contentScale`, which scales the final surface, and from
`projectionView.zoom`, which changes the projected viewport:

```kotlin
val playerState = rememberVideoPlayerState(
    playbackOptions = VideoPlaybackOptions(
        projection = VideoProjectionSettings(
            projectionType = VideoProjectionType.Equirect180,
            stereoLayout = VideoStereoLayout.SideBySide,
        ),
        projectionTextureCrop = VideoTextureCrop(
            left = 0.02f,
            top = 0.01f,
            right = 0.02f,
            bottom = 0.01f,
        ),
    )
)

playerState.projectionTextureCrop = playerState.projectionTextureCrop.copy(left = 0.03f)
```

For projected renderers, apps can steer the viewport independently from the source projection:

```kotlin
playerState.projectionView = playerState.projectionView.copy(
    yawDegrees = playerState.projectionView.yawDegrees + 15f,
    pitchDegrees = 0f,
    zoom = 1.2f,
)
```

By default, `VideoProjectionViewControlMode.AUTO` uses device motion on platforms where it is available for surround projections. It uses Android rotation vector sensors, iOS CoreMotion device motion, and browser `DeviceOrientationEvent`; this is more stable than using the accelerometer alone. Apps can force or disable sensor control:

```kotlin
val playerState = rememberVideoPlayerState(
    playbackOptions = VideoPlaybackOptions(
        projectionViewControlMode = VideoProjectionViewControlMode.DEVICE_MOTION,
    )
)

playerState.projectionViewControlMode = VideoProjectionViewControlMode.MANUAL
```

The common API includes presets and detection helpers:

```kotlin
val detection = detectVideoProjection(
    VideoProjectionDetectionInput(
        title = "Example_VR180_SBS.mp4",
        videoSizes = listOf(VideoProjectionVideoSize(width = 7680, height = 3840)),
    )
)

val nextPreset = playerState.projection.nextVideoProjectionPreset()
```

Supported projection metadata includes flat 2D/3D, equirectangular 180/360, fisheye 180/190/200/220, and EAC 360 with mono, side-by-side, and over-under stereo layouts. A non-default `projectionTextureCrop` switches `SurfaceType.Auto` and canvas paths to projection renderers so the crop can be applied.

On Android, `SurfaceType.Auto` sends every projection mode through Media3's `DefaultVideoFrameProcessor` so PQ/HLG input cannot accidentally be drawn as washed-out SDR. HDR projection requires GLES3 plus `EXT_YUV_target`; HDR10/PQ and HLG outputs additionally require the matching EGL colorspace and use RGBA16F intermediates with an RGBA_1010102 output surface. `AUTO` and `PREFER_HDR` retry Media3's managed SDR tone mapping if HDR output setup fails, while `REQUIRE_HDR` reports a typed error. Flat video uses `SurfaceView`; `TextureView` is an explicit compositing opt-out and is not advertised as HDR. Android projected surfaces can use device rotation automatically unless `projectionViewControlMode` is set to `MANUAL`. iOS projections use `AVPlayerItemVideoOutput` to pull P010/NV12 frames into CoreVideo Metal textures. One `rgba16Float` shader handles flat stereo, equirectangular, fisheye, EAC, crops and CoreMotion-backed viewport control; HDR10/HLG is confirmed only after an EDR Metal frame completes, and runtime failure replans to controlled SDR unless `REQUIRE_HDR` was selected. Flat iOS playback keeps `AVPlayerLayer`; `FORCE_SDR` explicitly disables EDR on that layer so AVFoundation performs the SDR presentation. On macOS desktop, the P010/NV12 and FP16 Metal child layer follows the active `NSScreen` while Compose controls/subtitles remain in the same application-owned Tao `NSWindow` above it.

Windows requests Media Foundation hardware transforms and requires a decoded P010 GPU surface before
its D3D11 projection/color shader feeds a flip-model swapchain; it does not invent a decoder
component name that Media Foundation has not exposed. HDR uses an Advanced Color PQ/BT.2020 or
scRGB swapchain. `FORCE_SDR`, and the retry after a non-strict HDR-output failure, instead run
BT.2390 tone mapping and BT.2020-to-BT.709 gamut conversion in that same renderer before presenting
an 8-bit dithered G22/BT.709 swapchain. These native MP4 routes do not depend on KMediaBridge. A
route is confirmed only after the active `IDXGIOutput6`, requested swapchain color space, P010 input
and first successful `Present` agree. Linux flat HDR uses GStreamer `waylandsink`; projection uses
the optional Vulkan renderer with an exact 10-bit PQ/HLG WSI colorspace. Its current linear P010
DMA-BUF path maps the buffer before the Vulkan upload, so it is bounded but not advertised as
zero-copy. Nucleus Tao mounts the renderer-owned `GtkWidget` directly in its GTK hierarchy and
keeps Compose controls and native popup layers above it. Missing GTK/Wayland integration,
GStreamer, Wayland color management, DMA-BUF, Vulkan, or output support replans to verified SDR;
`FORCE_SDR` explicitly bypasses the HDR Wayland surface.

Web attempts HDR projection only when WebGPU configuration readback and an HDR display are present. It imports a bounded set of current `VideoFrame` objects, asks the browser to color-manage tagged PQ/HLG into an extended Display-P3 working encoding, and projects them into an `rgba16float` canvas with extended tone mapping. HDR is confirmed only after the display still reports high dynamic range, the configuration is retained, and the first GPU submission completes; this confirms an HDR canvas element rather than claiming a raw PQ/HLG display transport. If an HDR canvas is rejected, `AUTO`/`PREFER_HDR` recreates the route as WebGPU controlled SDR: the extended sRGB texture is converted to linear light, tone-mapped with BT.2390, gamut-mapped through the common ICtCp LUT, dithered, and written to an FP16 standard sRGB canvas. WebGL is reserved for browser-managed SDR projection and is never reported as an HDR-to-SDR converter; without WebGPU the controlled HDR fallback fails closed. `REQUIRE_HDR` fails instead of falling back. Flat HDR normally stays browser-managed and keeps output status unknown, while `FORCE_SDR` switches its visible output to the same controlled WebGPU SDR canvas because a native `<video>` cannot be forced to SDR on an HDR display. Remote web videos must be served with CORS headers whenever a canvas renderer is active, because browsers block cross-origin video texture sampling otherwise. Some mobile browsers, especially iOS Safari, require a user permission gesture before device orientation events are delivered. A libVLC native view is never used as proof of HDR output.

#### Custom Overlay UI

You can add a custom overlay UI that will always be visible, even in fullscreen mode, by using the `overlay` parameter:

```kotlin
VideoPlayerSurface(
    playerState = playerState,
    modifier = Modifier.fillMaxSize()) {
        // This overlay will always be visible
        Box(modifier = Modifier.fillMaxSize()) {
            // You can customize the UI based on fullscreen state
            if (playerState.isFullscreen) {
                // Fullscreen UI
                IconButton(
                    onClick = { playerState.toggleFullscreen() },
                    modifier = Modifier.align(Alignment.TopEnd).padding(16.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.FullscreenExit,
                        contentDescription = "Exit Fullscreen",
                        tint = Color.White
                    )
                }
            } else {
                // Regular UI
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.5f))
                        .padding(8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    // Your custom controls here
                    IconButton(onClick = { 
                        if (playerState.isPlaying) playerState.pause() else playerState.play() 
                    }) {
                        Icon(
                            imageVector = if (playerState.isPlaying) 
                                Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = "Play/Pause",
                            tint = Color.White
                        )
                    }
                }
            }
        }
    }
```

### Video Playback via URL or Local Files

You can play a video by providing a direct URL:

```kotlin
// Open a video and automatically start playing (default behavior)
playerState.openUri("http://example.com/video.mp4")

// Open a video but keep it paused initially
playerState.openUri("http://example.com/video.mp4", InitialPlayerState.PAUSE)
```

To play a local video file you can use [PlatformFile](https://filekit.mintlify.app/core/platform-file) from [FileKit](https://github.com/vinceglb/FileKit).

```kotlin
val file = FileKit.openFilePicker(type = FileKitType.Video)

// Open a file and automatically start playing (default behavior)
file?.let { playerState.openFile(it) }

// Open a file but keep it paused initially
file?.let { playerState.openFile(it, InitialPlayerState.PAUSE) }
```

The `initializePlayerState` parameter controls whether the video automatically starts playing after opening:
- `InitialPlayerState.PLAY` (default): The video will automatically start playing after opening
- `InitialPlayerState.PAUSE`: The video will be loaded but remain paused until you call `play()`

On Android, apps that need a custom Media3 source can pass an `AndroidMediaSourceProvider`. The normal player state
reset, metadata extraction, repeat mode, volume and initial play/pause handling stay inside the library:

```kotlin
@OptIn(UnstableApi::class)
val playerState = rememberVideoPlayerState(
    androidMediaSourceProvider = AndroidMediaSourceProvider { request ->
        val uri = request.mediaItem.localConfiguration?.uri?.toString().orEmpty()
        if (uri.contains("example-cdn.com")) {
            customMediaSourceFactory(request.requestHeaders).createMediaSource(request.mediaItem)
        } else {
            null
        }
    },
)
```

Check the [sample project](sample/composeApp/src/commonMain/kotlin/sample/app/App.kt) for a complete example.

### HLS Quality Selection

Browser playback exposes adaptive video variants from the Wasm engine. Its track id `-1` is
represented as automatic selection rather than a selectable variant. The list is populated after
the manifest has loaded, so custom controls should handle an empty list as "not available yet" or
"not supported by this backend".

```kotlin
val quality = playerState.availableHlsQualities.firstOrNull { it.height == 720 }

when (val result = quality?.let { playerState.selectHlsQuality(it.id) } ?: playerState.selectAutoHlsQuality()) {
    is HlsQualitySelectionResult.Selected -> println("HLS quality selected: ${result.quality.label}")
    HlsQualitySelectionResult.Auto -> println("Automatic HLS quality selection enabled")
    is HlsQualitySelectionResult.NotFound -> println("HLS quality not found: ${result.variantId}")
    HlsQualitySelectionResult.NotSupported -> println("HLS quality selection is not supported here")
}
```

Use `currentHlsQuality` and `hlsQualityMode` to reflect the current state in custom controls. `Selected` and `Auto` mean the request was applied; `NotFound` means the variant id was stale or from another source; `NotSupported` means the current platform or source does not expose HLS variants. Android, iOS and desktop targets currently report `NotSupported` for this API.

### Chapters

Chapters are exposed through the common `VideoPlayerState` API and arrive asynchronously after a
source is opened:

```kotlin
playerState.chapters.forEach { chapter ->
    println("${chapter.startMs}..${chapter.endMs}: ${chapter.title}")
}

playerState.currentChapter?.let { chapter ->
    println("Now playing: ${chapter.title}")
}

playerState.chapters.firstOrNull()?.let(playerState::seekToChapter)
```

`MediaChapter` contains `start`, optional exclusive `end`, `title`, BCP 47 `language`, and
`isHidden`, plus millisecond and duration convenience properties. Missing ends are inferred from
the next distinct chapter start or the media duration when available. Hidden chapters remain in
the list so the application can decide whether to show them. `chapters` is cleared when the
logical source changes; internal remux or color-conversion source swaps retain it.

| Source | Android | iOS | Desktop JVM | Web |
|---|---:|---:|---:|---:|
| Matroska/WebM editions and chapter atoms | Media3 | AVFoundation when supported | Built-in parser | Browser/backend dependent |
| MP4/MOV/3GP Nero `chpl` and QuickTime chapter tracks | Media3 | AVFoundation | Built-in parser | Browser/backend dependent |
| ID3 `CHAP`/`CTOC` | Media3 | AVFoundation when supported | Built-in parser | Browser/backend dependent |
| ASF/WMV markers | Backend dependent | — | Built-in parser | — |
| Apple HLS JSON chapters | VOD/Event | VOD/Event | VOD/Event | Engine/backend dependent |
| WebVTT `<track kind="chapters">` | Backend dependent | AVFoundation when supported | Backend dependent | Built in |
| Optional MPV backend | Backend API dependent | — | Native `chapter-list` | — |

Matroska selects the default edition, or the first edition when none is marked default, chooses
the best available localized display label, and flattens nested atoms into the public list.
Apple HLS JSON discovery intentionally excludes sliding live playlists; generic HLS
`EXT-X-DATERANGE` metadata is not treated as chapters.

### Full Controls

- **Play and Pause**:

You can detect the current playback state via `playerState.isPlaying` and configure a Play/Pause button as follows:

```kotlin
Button(onClick = {
    if (playerState.isPlaying) {
        playerState.pause()
        println("Playback paused")
    } else {
        playerState.play()
        println("Playback started")
    }
}) {
    Text(if (playerState.isPlaying) "Pause" else "Play")
}
```

- **Stop**:

```kotlin
playerState.stop()
println("Playback stopped")
```

- **Volume**:

```kotlin
playerState.volume = 0.5f // Set volume to 50%
println("Volume set to 50%")
```

- **Loop Playback**:

```kotlin
playerState.loop = true // Enable loop playback
```

You can listen for loop restarts via the `onRestart` callback:

```kotlin
playerState.loop = true
playerState.onRestart = {
    println("Video restarted from the beginning")
}
```

- **Restart**:

Restart playback from the beginning. Works reliably from any state, including when the video has ended:

```kotlin
playerState.restart()
```

- **Playback End Callback**:

Get notified when playback reaches the end (only called when `loop` is `false`):

```kotlin
playerState.onPlaybackEnded = {
    println("Video finished")
}
```

- **Playback Speed**:

```kotlin
playerState.playbackSpeed = 1.5f // Set playback speed to 1.5x
println("Playback speed set to 1.5x")
```

You can adjust the playback speed between 0.5x (slower) and 2.0x (faster). The default value is 1.0x (normal speed).

### Progress Indicators

To display and control playback progress, use `seekStart` and `seekFinished` for slider interactions:

```kotlin
Slider(
    value = playerState.sliderPos,
    onValueChange = { playerState.seekStart(it) },
    onValueChangeFinished = { playerState.seekFinished() },
    valueRange = 0f..1000f
)
```

- `seekStart(value)` updates the slider position visually without performing the actual seek, allowing smooth dragging.
- `seekFinished()` commits the seek to the player and ends the drag interaction.

For programmatic seeking (e.g. skip forward/backward), use `seekTo`, `seekToMs`, `seekBy` or `seekByMs`:

```kotlin
playerState.seekToMs(60_000)
playerState.seekByMs(10_000)
```

### Error Handling

In case of an error, you can display it using `println`:

```kotlin
playerState.error?.let { error ->
    println("Error detected: ${error.message}")
    playerState.clearError()
}
```

### Diagnostics and Logging

Compose Media Player internal logging is disabled by default. Enable it only while diagnosing playback behavior:

```kotlin
import io.github.kdroidfilter.composemediaplayer.util.ComposeMediaPlayerLoggingLevel
import io.github.kdroidfilter.composemediaplayer.util.allowComposeMediaPlayerLogging
import io.github.kdroidfilter.composemediaplayer.util.composeMediaPlayerLoggingLevel

allowComposeMediaPlayerLogging = true
composeMediaPlayerLoggingLevel = ComposeMediaPlayerLoggingLevel.DEBUG
```

JVM native backends keep their own diagnostic output disabled by default. Set `COMPOSE_MEDIA_PLAYER_NATIVE_LOGGING=true` before starting the app to enable native messages from the macOS, Windows and Linux backends.

### Loading Indicator

To detect if the video is buffering:

```kotlin
if (playerState.isLoading) {
    CircularProgressIndicator()
}
```

### Using Subtitles

Compose Media Player supports adding subtitles from both URLs and local files. SRT and VTT subtitles are rendered using Compose, providing a uniform appearance across all platforms. The core also parses ASS/SSA timing and dialogue text, but authored styles, positioning and effects require the optional `composemediaplayer-ass` artifact plus `AssSubtitleExtension()`. On Android, Apple, Windows and Linux that adapter uses the separately published, process-wide `KMediaAssRuntime`; the MPV and KMediaBridge adapters reach the same text stack transitively through `KMediaFfmpegRuntime`. Browser Wasm keeps its JASSUB/libass integration. Windows and Linux users do not install libass separately. Other native routes keep the plain-dialogue fallback unless their playback backend supplies styled subtitle rendering. On desktop JVM, the libVLC MKV/WebM backends can expose and select embedded subtitle tracks. For the optional HLS container fallback, the macOS KMediaBridge runtime can burn selected embedded text tracks into controlled BT.709 SDR; remux-only platform runtimes use the optional VLC fallback when it is available.

#### 🎯 Supported Formats

The player supports SRT, VTT, ASS and SSA subtitle formats with automatic format detection. With `composemediaplayer-ass` installed and activated, Android, browser Wasm, Apple targets, and Windows/Linux JVM use the libass-backed routes described below. Android and JVM resolve the exact shared runtime transitively; Apple uses the matching `KMediaAssRuntime` pod. Without the optional adapter, the core keeps its lightweight ASS/SSA dialogue fallback. A platform-specific backend, such as a native libVLC surface, may independently provide styled rendering.

#### 🎯 Adding Subtitles from URL or Local File

You can add subtitles by specifying a URL:

```kotlin
val track = SubtitleTrack(
    label = "English Subtitles",
    language = "en",
    src = "https://example.com/subtitles.vtt" // Works with .srt, .vtt, .ass and .ssa files
)
playerState.addSubtitleTrack(track)
when (val result = playerState.selectSubtitleTrack(track)) {
    is TrackSelectionResult.Selected -> println("Subtitle track selected: ${result.trackId}")
    TrackSelectionResult.Disabled -> println("Subtitles disabled")
    is TrackSelectionResult.NotFound -> println("Subtitle track not found: ${result.trackId}")
    TrackSelectionResult.NotSupported -> println("Subtitle track selection is not supported")
    is TrackSelectionResult.Failed -> println("Failed to select subtitle track: ${result.message}")
    TrackSelectionResult.Auto -> println("Automatic track selection restored")
}
```

#### Android ASS/SSA subtitles with libass

The Android variant of the optional `composemediaplayer-ass` artifact contains one thin renderer bridge for `arm64-v8a` and `armeabi-v7a`; Intel Android ABIs are not published. Its exact transitive dependency supplies `KMediaAssRuntime` 0.1.0-rc.6 with libass 0.17.5, FreeType, FriBidi and HarfBuzz. Applications do not need another Maven repository or a manual `runtimeOnly` dependency. Add `AssSubtitleExtension()` to `VideoPlaybackOptions.extensions` to activate it. Media3 still owns playback and demuxing. The extension intercepts only the raw Matroska ASS/SSA packet format and renders it into a transparent screen-space overlay, while other Media3 subtitle formats keep their normal path.

The libass route supports:

- external `.ass` and `.ssa` tracks selected through `addSubtitleTrack(...)`;
- embedded Matroska ASS/SSA tracks, including supported font attachments;
- ASS styles, layers, positioning, karaoke, transforms and animation;
- seeking, positive or negative subtitle offsets, resize and crop-to-fill geometry.

ASS script styles control this overlay, so `subtitleTextStyle` and `subtitleBackgroundColor` apply to the Compose subtitle renderer rather than restyling libass output. A custom `AndroidMediaSourceProvider` that returns a fully constructed `MediaSource` must preserve KMediaPlayer's subtitle parser/extractor setup; otherwise that custom source can flatten ASS into ordinary Media3 cues before the libass renderer sees it. Side-loaded SSA supplied directly to Media3 and HLS SSA retain Media3's fallback path.

The thin renderer bridge lives in the optional adapter. Native text libraries,
their notices, corresponding source and LGPL replacement material live once in
`kmedia-ass-runtime-android`, not in `composemediaplayer-ass` or the base player.

#### Windows and Linux ASS/SSA subtitles with libass

On a 64-bit JVM, the optional component initializes the exact
`kmedia-ass-runtime-desktop` dependency and resolves libass through Java's
Foreign Function API. The adapter JAR itself contains no native libraries. The
runtime publishes libass 0.17.5 for macOS ARM64, Linux x86_64/ARM64 and Windows
x86_64, with SHA-256 verification before native loading. Windows uses
DirectWrite font discovery; Linux uses the desktop's normal fontconfig
configuration. External ASS/SSA files are composited into writable BGRA video
frames. On Windows and Linux, embedded Matroska ASS/SSA tracks exposed by the
libVLC canvas backend use the built-in extractor and pass container font
attachments to libass. macOS maps libVLC selection to its native NSView path.

Users do not install libass or copy its native files. Register
`AssSubtitleExtension()` and launch the application with native access enabled:

```text
--enable-native-access=ALL-UNNAMED
```

There is no system-libass fallback or single-library override. An advanced
deployment can initialize one complete, compatible replacement directory with
`KMediaAssRuntime.initialize(RuntimeSource.externalDirectory(...))` before any
native client loads. A second runtime ID is rejected process-wide. If loading,
track extraction, or rendering fails, the player keeps its Compose or libVLC
subtitle fallback. Native HDR/color and `LIBVLC_NATIVE` surfaces retain their
platform subtitle path because they do not expose a writable CPU video frame.

#### Browser ASS/SSA subtitles with JASSUB/libass

Full ASS/SSA rendering on `wasmJs` is provided by the optional `composemediaplayer-ass` artifact.
Register `AssSubtitleExtension()` in `VideoPlaybackOptions.extensions`; external ASS/SSA tracks then
use JASSUB with the Wasm engine. For embedded ASS/SSA, the engine
mounts the same extension as its pluggable renderer, forwards the codec header, timed Matroska
packets and bounded font attachments, and drives JASSUB from its media clock. Unsupported embedded
formats remain on the engine's built-in subtitle path. Protected DRM playback uses the engine's
native video clock. The optional artifact pins JASSUB 2.5.14, so applications do not add JASSUB,
libass, worker scripts or WASM files manually.

Create stable player options and then select an `.ass` or `.ssa` subtitle track:

```kotlin
val playbackOptions = remember {
    VideoPlaybackOptions(
        extensions = listOf(AssSubtitleExtension()),
    )
}
val playerState = rememberVideoPlayerState(playbackOptions = playbackOptions)

LaunchedEffect(Unit) {
    playerState.openUri("https://example.com/video.mp4")
    val subtitleTrack = SubtitleTrack(
        label = "English ASS",
        language = "en",
        src = "https://example.com/subtitles.ass",
    )
    playerState.addSubtitleTrack(subtitleTrack)
    playerState.selectSubtitleTrack(subtitleTrack)
}
```

If you use `VideoPlayerControls`, the selected track is rendered automatically by `VideoPlayerSurface`. If you build custom controls, keep using the same `playerState.selectSubtitleTrack(...)` and `playerState.disableSubtitles()` APIs and inspect the returned `TrackSelectionResult` when you need to handle unsupported or missing tracks.

Browser renderer configuration is immutable and belongs to the installed extension instance:

```kotlin
import io.github.kdroidfilter.composemediaplayer.AssSubtitleRendererConfig
import io.github.kdroidfilter.composemediaplayer.AssFontQueryMode

val playbackOptions = remember {
    VideoPlaybackOptions(
        extensions = listOf(
            AssSubtitleExtension(
                config = AssSubtitleRendererConfig(
                    fontQueryMode = AssFontQueryMode.DISABLED,
                    debug = false,
                ),
            ),
        ),
    )
}
```

Configuration options:

| Option | Default | Description |
| :--- | :--- | :--- |
| `enabled` | `true` | Enables or disables browser ASS/SSA rendering. When disabled, ASS/SSA tracks are ignored by the JASSUB renderer. |
| `workerUrl` | `null` | Optional URL for a self-hosted JASSUB worker. Leave unset to use the bundled npm asset. |
| `wasmUrl` | `null` | Optional URL for `jassub-worker.wasm`. Leave unset to use the bundled npm asset. |
| `modernWasmUrl` | `null` | Optional URL for `jassub-worker-modern.wasm`. Leave unset to use the bundled npm asset. |
| `fallbackFontUrl` | `null` | Optional URL for the fallback font used by libass. Leave unset to use JASSUB defaults. |
| `fallbackFontFamily` | `"liberation sans"` | Font family name mapped to `fallbackFontUrl`. |
| `preloadFontUrls` | `emptyList()` | Font URLs fetched eagerly when the renderer starts. |
| `availableFontUrls` | `emptyMap()` | Case-insensitive font-family-to-URL mappings fetched only when required. |
| `fontQueryMode` | `DISABLED` | `LOCAL` enables browser font discovery; `LOCAL_AND_REMOTE` also enables JASSUB's remote font lookup and its privacy/network implications. |
| `debug` | `false` | Enables JASSUB debug logging. |

For production deployments you can optionally self-host the worker, WASM and fallback font assets. This is useful when your hosting/CDN needs explicit cache headers, predictable URLs, or a strict Content Security Policy:

```kotlin
AssSubtitleExtension(
    config = AssSubtitleRendererConfig(
        workerUrl = "/jassub/worker/worker.js",
        wasmUrl = "/jassub/wasm/jassub-worker.wasm",
        modernWasmUrl = "/jassub/wasm/jassub-worker-modern.wasm",
        fallbackFontUrl = "/jassub/default.woff2",
    ),
)
```

The URLs may be relative to your app base URL or absolute. Prefer same-origin URLs. If you host the video, subtitle, worker, WASM or font files on another domain, configure CORS headers so the browser can load them from your app origin.

Browser notes:

- ASS/SSA rendering requires Worker, WebAssembly, `OffscreenCanvas`,
  `canvas.transferControlToOffscreen()` and the other browser primitives reported by extension
  availability. The video-backed route uses `requestVideoFrameCallback()` (JASSUB includes its
  polyfill); the clear Wasm route uses canvas-only manual rendering. Missing support leaves the
  Compose fallback active.
- For JASSUB's multithreaded path, serve the document with
  `Cross-Origin-Embedder-Policy: require-corp` and
  `Cross-Origin-Opener-Policy: same-origin`. Without them JASSUB automatically uses its
  single-threaded fallback.
- Raw embedded ASS/SSA packets and supported font attachments are delivered directly by the engine
  to JASSUB, with limits of 16 MiB per font, 32 MiB total and 64 files.
- Subtitle geometry follows `Fit`, `Crop`, `FillBounds`, `FillWidth` and `FillHeight`. Projection
  subtitles remain a flat screen-space overlay over the projected canvas.
- Web fullscreen should keep the same video element alive; entering fullscreen should not restart playback.
- SRT and VTT do not use JASSUB. They continue to use the Compose subtitle renderer.
- Android, iOS and desktop JVM use the same extension entry point but do not use this JASSUB
  configuration. Their native behavior is described in the ASS component documentation.

#### Selecting Embedded Audio and Subtitle Tracks

When the backend exposes tracks from the media container, they are available through the player
state. The Wasm route maps the engine's numeric ids to stable KMediaPlayer string ids and calls
its real track-selection API; a rejected switch returns `Failed` without changing state or
emitting `TrackChanged`. Passing `null` restores the initially selected/default audio track rather
than muting it. Android reads Media3 track groups, and the desktop JVM libVLC/external HLS fallbacks expose
embedded audio/subtitle tracks when probing is available.

```kotlin
val audioTrack = playerState.availableAudioTracks.firstOrNull()
if (audioTrack != null) {
    when (val result = playerState.selectAudioTrack(audioTrack)) {
        is TrackSelectionResult.Selected -> println("Audio track selected: ${result.trackId}")
        TrackSelectionResult.Auto -> println("Automatic audio track selection restored")
        is TrackSelectionResult.NotFound -> println("Audio track not found: ${result.trackId}")
        TrackSelectionResult.NotSupported -> println("Audio track selection is not supported")
        is TrackSelectionResult.Failed -> println("Failed to select audio track: ${result.message}")
        TrackSelectionResult.Disabled -> Unit
    }
}

val subtitleTrack = playerState.availableSubtitleTracks.firstOrNull { it.isEmbedded }
if (subtitleTrack != null) {
    playerState.selectSubtitleTrack(subtitleTrack)
}
```

`availableSubtitleTracks` can contain both external tracks added by your app through `addSubtitleTrack(...)` and embedded tracks discovered from the current media. Use `replaceExternalSubtitleTracks(...)` to replace app-managed tracks while preserving embedded tracks, or `removeSubtitleTrack(...)` / `clearExternalSubtitleTracks()` to remove app-managed tracks.

Applications can also attach a timeline-aligned replacement or overlay audio source to the primary
media. This is available on Android, iOS, desktop JVM, and browser Wasm. Check
`capabilities.supportsExternalAudioTracks`, open the primary source, and then register the external
track:

```kotlin
if (playerState.capabilities.supportsExternalAudioTracks) {
    val narration = ExternalAudioTrack(
        id = "narration-pl",
        label = "Polish narration",
        language = "pl",
        source = MediaSourceSpec(
            uri = "https://example.com/narration.m4a",
            mimeType = "audio/mp4",
        ),
        playbackMode = ExternalAudioPlaybackMode.OVERLAY,
        duckingIntervals = listOf(
            ExternalAudioDuckingInterval(12.seconds, 16.seconds),
        ),
        duckingVolumeMultiplier = 0.35f,
    )
    playerState.addExternalAudioTrack(narration)
    playerState.selectAudioTrack(narration.id)
}
```

`REPLACE` tracks are alternative full mixes and mute the programme audio after the auxiliary transport
is ready. `OVERLAY` tracks are mixed locally with programme audio; configured ducking intervals lower
the programme volume with short attack/release ramps while keeping the overlay at the requested player
volume. Both modes require timestamps aligned to the primary content. The registry is bound to the
current source and is cleared when another primary source is opened or the source is released. Use
`replaceExternalAudioTracks(...)` to update app-managed tracks while preserving embedded tracks.
Native Android replacement tracks can be merged into the Media3 timeline. Overlay tracks, iOS and
desktop JVM use a second headless platform transport, while browser Wasm uses a separate
`HTMLAudioElement`. On synchronized routes the primary player remains the timeline authority: play,
pause, seek, speed, looping, volume, and drift are synchronized. Optional backends can opt into the same behavior with
`withSynchronizedExternalAudioPlayback()`.

Request headers are sanitized through the same policy as primary media headers; avoid putting
long-lived credentials in URLs or diagnostic output. Browser external audio cannot attach custom
request headers and therefore requires a short-lived, self-authorizing URL. External audio bypasses
the primary media disk cache so session-bound URLs are not retained in its cache index. Actual codec
and channel-layout support still depends on the device, OS, browser, and selected backend; stereo AAC
is the broadest compatibility profile.

`selectAudioTrack(...)`, `selectSubtitleTrack(...)` and `disableSubtitles()` return `TrackSelectionResult`. `Selected`, `Auto` and `Disabled` mean the request was applied; `NotFound`, `NotSupported` and `Failed` mean the player state was not changed.

#### 🎨 Customizing Subtitle Appearance

You can customize the appearance of subtitles using the following properties:

```kotlin
// Customize subtitle text style
playerState.subtitleTextStyle = TextStyle(
    color = Color.White,
    fontSize = 20.sp,
    fontWeight = FontWeight.Bold,
    textAlign = TextAlign.Center
)

// Customize subtitle background color
playerState.subtitleBackgroundColor = Color.Black.copy(alpha = 0.7f)
```

#### ❌ Disabling Subtitles

To disable subtitles:

```kotlin
when (playerState.disableSubtitles()) {
    TrackSelectionResult.Disabled -> println("Subtitles disabled")
    else -> println("Subtitles were not disabled")
}
```

### 🖥️ Fullscreen Mode

> [!WARNING]
> Fullscreen support is experimental. The behavior may vary across different platforms.

You can toggle between windowed and fullscreen modes using the `toggleFullscreen()` method:

```kotlin
// Toggle fullscreen mode
playerState.toggleFullscreen()

// Check current fullscreen state
if (playerState.isFullscreen) {
    println("Player is in fullscreen mode")
} else {
    println("Player is in windowed mode")
}
```

The player doesn't display any UI by default in fullscreen mode - you need to create your own custom UI using the `overlay` parameter of `VideoPlayerSurface`. The overlay will be displayed even in fullscreen mode, and you can customize it based on the fullscreen state:

```kotlin
VideoPlayerSurface(
    playerState = playerState,
    modifier = Modifier.fillMaxSize(),
    overlay = {
        Box(modifier = Modifier.fillMaxSize()) {
            // Customize UI based on fullscreen state
            if (playerState.isFullscreen) {
                // Fullscreen UI
                // ...
            } else {
                // Regular UI
                // ...
            }
        }
    }
)
```

See the "Custom Overlay UI" section under "Displaying the Video Surface" for a complete example.

### 📺 Picture-in-Picture (PiP)

> [!WARNING]
> PiP is supported on **Android** (8.0+) and **iOS** only. On desktop and web, it is a no-op.

The player supports Picture-in-Picture mode, allowing users to continue watching video in a floating window while using other apps.

| Platform | Status | Notes |
| :--- | :---: | :--- |
| **Android** | ✅ | Requires Android 8.0+ (API 26). Add `android:supportsPictureInPicture="true"` to your Activity in the manifest. |
| **iOS** | ✅ | Uses `AVPictureInPictureController`. Enable "Audio, AirPlay, and Picture in Picture" in Background Modes. |
| **Desktop** | ❌ | No-op |
| **Web** | ❌ | No-op |

```kotlin
// Check if PiP is supported on the current device
if (playerState.isPipSupported) {
    // Enable automatic PiP when the app goes to background
    playerState.isPipEnabled = true

    // Or enter PiP programmatically
    val result = playerState.enterPip()
}
```

On Android, you can use the `AutoPipEffect` composable to automatically enter PiP mode when the app goes to the background while a video is playing:

```kotlin
AutoPipEffect(playerState)
```

You also need to forward PiP mode changes from your Activity:

```kotlin
class MainActivity : ComponentActivity() {
    override fun onPictureInPictureModeChanged(isInPipMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPipMode, newConfig)
        DefaultVideoPlayerState.onPictureInPictureModeChanged(isInPipMode)
    }
}
```

### 🔊 Audio Mode

You can configure how the media player interacts with other apps' audio using the `AudioMode` parameter:

```kotlin
// Default: exclusive playback, pauses other apps' audio
val playerState = rememberVideoPlayerState()

// Mix with other apps' audio
val playerState = rememberVideoPlayerState(
    audioMode = AudioMode(interruptionMode = InterruptionMode.MixWithOthers)
)

// Duck other apps' audio (lower their volume)
val playerState = rememberVideoPlayerState(
    audioMode = AudioMode(interruptionMode = InterruptionMode.DuckOthers)
)

// Ambient mode (iOS): respect silent switch, mix with others
val playerState = rememberVideoPlayerState(
    audioMode = AudioMode(
        interruptionMode = InterruptionMode.MixWithOthers,
        playsInSilentMode = false,
    )
)
```

| Parameter | Description | Default |
| :--- | :--- | :---: |
| `interruptionMode` | `DoNotMix`, `MixWithOthers`, or `DuckOthers` | `DoNotMix` |
| `playsInSilentMode` | iOS only: whether audio plays when the silent switch is on | `true` |

> [!NOTE]
> Audio mode is only effective on **Android** and **iOS**. On desktop and web, the parameter is accepted but ignored.

On iOS the default process-wide manager reference-counts active players so one player cannot deactivate `AVAudioSession` while another is using it. Applications that own audio-session configuration can opt out before creating any players:

```kotlin
IosAudioSessionPolicy.automaticManagementEnabled = false
```

### 💾 Video Caching

On Android you can enable disk-based caching so that video data fetched via `openUri()` is stored locally. Subsequent plays of the same URL load from the cache instead of re-downloading, which is especially useful for scroll-based UIs like TikTok/Reels-style `VerticalPager`.

```kotlin
val playerState = rememberVideoPlayerState(
    cacheConfig = CacheConfig(
        enabled = true,
        maxCacheSizeBytes = 200L * 1024L * 1024L // 200 MB
    )
)
```

| Parameter | Description | Default |
| :--- | :--- | :---: |
| `enabled` | Whether caching is active | `false` |
| `maxCacheSizeBytes` | Maximum disk space for the cache (LRU eviction) | `100 MB` |

To clear the cache programmatically:

```kotlin
when (val result = playerState.clearCache()) {
    CacheClearResult.Cleared -> println("Video cache cleared")
    CacheClearResult.Disabled -> println("Video cache is disabled")
    CacheClearResult.NotSupported -> println("Video cache is not supported on this platform")
    is CacheClearResult.Failed -> println("Failed to clear video cache: ${result.message}")
}
```

| Platform | Status | Implementation |
| :--- | :---: | :--- |
| **Android** | ✅ | Media3 `SimpleCache` with `LeastRecentlyUsedCacheEvictor` |
| **iOS** | ❌ | Returns `CacheClearResult.NotSupported`; the library does not mutate the host application's process-global `NSURLCache` |
| **Desktop** | ❌ | Returns `CacheClearResult.NotSupported` |
| **Web** | ❌ | Returns `CacheClearResult.NotSupported`; browser manages its own HTTP cache |

> [!NOTE]
> Android caching only applies to URIs opened via `openUri()`. Local files and assets are not cached. The cache is shared across Android player instances, so multiple players benefit from the same cached data.

## 🔍 Metadata Support

> [!WARNING]
> Metadata support is experimental. There may be inconsistencies between platforms. The Wasm route maps the fields exposed by `kmedia-wasm-engine`; individual containers may omit them.

The player can extract the following metadata:
- Title
- Duration (`kotlin.time.Duration`)
- Video resolution (width and height)
- Bitrate (in bits per second)
- Frame rate
- MIME type
- Audio channels
- Audio sample rate

### Example Usage

You can access video metadata through the `metadata` property of the player state:

```kotlin
// Access metadata after loading a video
playerState.openUri("http://example.com/video.mp4") // Auto-plays by default
// Or load without auto-playing:
// playerState.openUri("http://example.com/video.mp4", InitialPlayerState.PAUSE)

// Display metadata information
val metadata = playerState.metadata

println("Video Metadata:")
metadata.title?.let { println("Title: $it") }
metadata.duration?.let { println("Duration: $it") }
metadata.width?.let { width ->
    metadata.height?.let { height ->
        println("Resolution: ${width}x${height}")
    }
}
metadata.bitrate?.let { println("Bitrate: ${it}bps") }
metadata.frameRate?.let { println("Frame Rate: ${it}fps") }
metadata.mimeType?.let { println("MIME Type: $it") }
metadata.audioChannels?.let { println("Audio Channels: $it") }
metadata.audioSampleRate?.let { println("Audio Sample Rate: ${it}Hz") }

```

### Playback timing precision

`VideoPlayerState.currentTime` is the observed playback position intended for UI state. If you need the freshest
available playback position for external synchronization or millisecond-level actions, use:

```kotlin
val currentTimeMs = playerState.preciseCurrentTime.inWholeMilliseconds
```

### 📋 Basic Example

Here is a minimal example of how to integrate the Compose Media Player into your Compose application with a hardcoded URL:

```kotlin
@Composable
fun App() {
    val playerState = rememberVideoPlayerState()

    MaterialTheme {
        Column(modifier = Modifier.fillMaxSize().padding(8.dp)) {

            // Video Surface
            Box(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                VideoPlayerSurface(
                    playerState = playerState,
                    modifier = Modifier.fillMaxSize()
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Playback Controls
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Button(onClick = { playerState.play() }) { Text("Play") }
                Button(onClick = { playerState.pause() }) { Text("Pause") }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Open Video URL buttons
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Button(
                    onClick = {
                        val url = "http://commondatastorage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4"
                        playerState.openUri(url) // Default: auto-play
                    }
                ) {
                    Text("Play Video")
                }
                
                Button(
                    onClick = {
                        val url = "http://commondatastorage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4"
                        playerState.openUri(url, InitialPlayerState.PAUSE) // Open paused
                    }
                ) {
                    Text("Load Video Paused")
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Volume Control
            Text("Volume: ${(playerState.volume * 100).toInt()}%")
            Slider(
                value = playerState.volume,
                onValueChange = { playerState.volume = it },
                valueRange = 0f..1f
            )
        }
    }
}
```

## 📊 Roadmap

- **Application-composed Separate Audio and Video Sources**: Add a public API for combining arbitrary independent source URLs. HLS alternate renditions used by the bounded Dolby Vision VOD bridge are already handled internally and are not this roadmap item.
