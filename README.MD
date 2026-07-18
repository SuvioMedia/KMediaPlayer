# 🎥 Compose Media Player

<img src="/assets/banner.jpg" style="border-radius: 10px;"/>

[![Maven](https://img.shields.io/maven-metadata/v?metadataUrl=https%3A%2F%2Fshusek.github.io%2FKMediaPlayer%2Fmaven%2Fio%2Fgithub%2Fshusek%2Fcomposemediaplayer%2Fmaven-metadata.xml&label=maven)](https://shusek.github.io/KMediaPlayer/maven/io/github/shusek/composemediaplayer/maven-metadata.xml)
[![Kotlin](https://img.shields.io/badge/kotlin-multiplatform-blue.svg?logo=kotlin)](https://kotlinlang.org/docs/multiplatform.html)
[![Platforms](https://img.shields.io/badge/platforms-Android%20|%20iOS%20|%20macOS%20JVM%20|%20Windows%20JVM%20|%20Linux%20JVM%20|%20Web-lightgrey.svg)](https://github.com/Shusek/KMediaPlayer)
[![Contributions welcome](https://img.shields.io/badge/contributions-welcome-brightgreen.svg?style=flat)](https://github.com/Shusek/KMediaPlayer/issues)
[![Documentation](https://img.shields.io/badge/docs-Dokka-blue.svg)](https://shusek.github.io/KMediaPlayer/)
[![GitHub Workflow Status](https://img.shields.io/github/actions/workflow/status/Shusek/KMediaPlayer/publish-documentation-and-sample.yml?branch=master)](https://github.com/Shusek/KMediaPlayer/actions/workflows/publish-documentation-and-sample.yml)
[![GitHub last commit](https://img.shields.io/github/last-commit/Shusek/KMediaPlayer)](https://github.com/Shusek/KMediaPlayer/commits/master)

**Compose Media Player** is a video player library designed for Compose Multiplatform, supporting multiple platforms including Android, macOS, Windows, and Linux. Desktop native bridges are packaged in the JVM artifact and communicate through **pure JNI** — no JNA and no Java media bindings. Linux additionally requires a compatible GStreamer 1.x runtime and plugins to be installed on the host. The library leverages:

- **GStreamer** (via JNI) for Linux
- **Media Foundation** (via JNI) for Windows
- **AVPlayer** (via JNI) for macOS and iOS
- **Media3** for Android
- **HTML5 Player** for WASM

## Project Status

This repository is a personal fork maintained for my own projects and integration needs. Source code and tagged artifacts are published for transparency and reproducible internal deployments; publication is not an additional grant beyond the permissions in [LICENSE](LICENSE). This is not a supported public distribution, and releases may change without compatibility guarantees. JitPack builds are intentionally not provided because their generated coordinates and mutable commit builds are not part of the verified release pipeline. If you need a supported library for external use, use the upstream project instead: [kdroidFilter/ComposeMediaPlayer](https://github.com/kdroidFilter/ComposeMediaPlayer).

## Table of Contents
- [Project Status](#project-status)
- [Live Demo](#-live-demo)
- [Features](#-features)
- [Supported Video Formats](#-supported-video-formats)
- [Supported Codec Checks](#-supported-codec-checks)
- [Installation](#-installation)
  - [Optional MPV backend](#optional-mpv-backend)
- [Compatibility Table](#-compatibility-table)
- [Getting Started](#-getting-started)
  - [Initialization](#initialization)
  - [Displaying the Video Surface](#displaying-the-video-surface)
    - [Content Scaling](#content-scaling)
    - [Surface type](#surface-type)
    - [3D and VR projection](#3d-and-vr-projection)
    - [Custom Overlay UI](#custom-overlay-ui)
  - [Video Playback via URL or Local Files](#video-playback-via-url-or-local-files)
  - [HLS Quality Selection](#hls-quality-selection)
  - [Full Controls](#full-controls)
  - [Progress Indicators](#progress-indicators)
  - [Error Handling](#error-handling)
  - [Diagnostics and Logging](#diagnostics-and-logging)
  - [Loading Indicator](#loading-indicator)
  - [Using Subtitles](#using-subtitles)
    - [Supported Formats](#-supported-formats)
    - [Adding Subtitles from URL or Local File](#-adding-subtitles-from-url-or-local-file)
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

## 🚀 Live Demo

Try the online demo here : [🎥 Live Demo](https://shusek.github.io/KMediaPlayer/sample/)

## ✨ Features

- **Multiplatform Support**: Works seamlessly on Android, macOS, Windows, Linux and Compose Web (Wasm).
- **File and URL Support**: Play videos from local files or directly from URLs.
- **Media Controls**: Includes play, pause, loop toggle, volume control, playback speed, loop playback and timeline slider.
- **Initial Playback Control**: Choose whether videos automatically play or remain paused after opening.
- **Custom Video Player UI**: Fully customizable using Compose Multiplatform, with support for custom overlays that display even in fullscreen mode.
- **HLS Streaming**: Native HLS playback on Android, iOS, macOS, Windows, and Linux, with the pinned `hls.js` fallback on compatible Wasm browsers.
- **Fullscreen Mode**: Toggle between windowed and fullscreen playback modes.
- **Picture-in-Picture (PiP)**: Continue watching in a floating window on Android (8.0+) and iOS.
- **Audio Mode**: Configure audio interruption behavior and iOS silent switch handling.
- **Video Caching**: Opt-in Media3 disk caching on Android, ideal for scroll-based UIs.
- **3D/VR Projection**: Configure and auto-detect flat 3D, VR180, VR360, fisheye, and EAC projection modes. Android renders VR360 through Media3's spherical GL surface and other projected modes through an OpenGL shader surface, iOS renders projected modes through SceneKit, Web renders projected sources through WebGL, and desktop JVM canvas paths use a Skia shader renderer.
- **Error handling** Simple error handling for network or playback issues.
- **Optional MPV backend**: `composemediaplayer-mpv` is an Android/JVM adapter
  with libass-based ASS/SSA subtitle support. The default artifact has no MPV
  dependency; applications add the adapter only to the platform source sets
  where they select it.

## ✨ Supported Video Formats
| Format | Windows                                                                                                           | Linux  | macOS & iOS                                                       | Android | WASM |
|--------|-------------------------------------------------------------------------------------------------------------------|-------------------|-----------------------------------------------------------------------------|-----------------|-------------------|
| **Player** | [MediaFoundation](https://learn.microsoft.com/en-us/windows/win32/medfound/microsoft-media-foundation-sdk) | [GStreamer](https://gstreamer.freedesktop.org/) | [AVPlayer](https://developer.apple.com/documentation/avfoundation/avplayer) | [Media 3](https://developer.android.com/media/media3) | [HTML5 Video](https://www.w3schools.com/html/html5_video.asp) |
| MP4 (H.264) | ✅                                                                                                                 | ✅ | ✅                                                                           | ✅ | ✅ |
| AVI | ❌                                                                                                                 | ✅ | ❌                                                                           | ❌ | ❌ |
| MKV | ⚠️†                                                                                                                | ✅ | ⚠️†                                                                          | ✅ | ❌ |
| MOV | ✅                                                                                                                 | ✅ | ✅                                                                           | ❌ | ✅ |
| FLV | ❌                                                                                                                 | ✅ | ❌                                                                           | ❌ | ❌ |
| WEBM | ⚠️†                                                                                                                | ✅ | ⚠️†                                                                          | ✅ | ✅ |
| WMV | ✅                                                                                                                 | ✅ | ❌                                                                           | ❌ | ❌ |
| 3GP | ✅                                                                                                                 | ✅ | ✅                                                                           | ✅ | ❌ |
| HLS (M3U8) | ✅                                                                                                                 | ✅ | ✅                                                                           | ✅ | ✅‡ |

> The Android artifact includes the matching Media3 HLS module; consumers do not need to add it separately.

> ‡ Browser HLS support uses the browser's native implementation when available and the bundled, version-locked `hls.js` dependency when Media Source Extensions are available.

> † On desktop JVM, MKV/WebM can use native playback, optional in-process libVLC playback, or external HLS fallback backends when the platform backend cannot demux the source directly. libVLC supports both the Compose canvas path and an opt-in direct native-view path (`libvlc-native-view`) on macOS, Windows, and Linux. VLC/libVLC is preferred in `auto` mode; `ffmpeg` is the last fallback. See [JVM MKV/WebM fallback support](docs/macos-mkv-webm-support/README.md).

> macOS JVM also has an experimental opt-in HDR Metal rendering POC for AVFoundation-supported sources. Enable it with `-Dcomposemediaplayer.macos.hdrMetal=true` or `COMPOSE_MEDIA_PLAYER_MACOS_HDR_METAL=true`. See [macOS HDR Metal POC](docs/macos-hdr-metal-poc.md).

> On Windows and Linux JVM, `VideoOutputMode.NATIVE_HDR` selects the libVLC native-view path in `DesktopVideoBackend.AUTO` mode. This avoids copying frames into Compose and is the intended best-effort path for HDR sources, but actual HDR passthrough depends on VLC, the OS compositor, GPU drivers, display mode, and an HDR-capable display. `PlayerCapabilities.hdr` stays `UNKNOWN` there until a platform-specific HDR output detector is available.

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
```

`MediaSupport.query()` checks the current platform contract for URI schemes and known container/adaptive formats such as MKV and HLS, plus codec support reported by the backend. On browser targets it also asks `HTMLVideoElement.canPlayType` for MIME types that the browser can report before playback.

> [!NOTE]
> Media support can still depend on the device, OS version, browser, installed system components, or GStreamer plugins. Android and web targets query the current runtime where possible. Desktop and iOS expose conservative backend capability lists. Query methods are suspend functions so callers can run them from `LaunchedEffect`, `async`, `produceState`, or their own coroutine/lazy strategy.

## 🔧 Installation

> [!IMPORTANT]
> These coordinates reproduce the maintainer's tagged internal artifacts. Their availability does not change the permitted uses stated in [LICENSE](LICENSE). Every remote release uses an immutable `vMAJOR.MINOR.PATCH` tag; `dev` builds are local-only.

To add Compose Media Player to your project, add the GitHub Pages Maven repository in your `settings.gradle.kts` file:

```kotlin
dependencyResolutionManagement {
    repositories {
        mavenCentral()
        maven("https://shusek.github.io/KMediaPlayer/maven")
    }
}
```

Then include the dependency in your module `build.gradle.kts` file:

```kotlin
dependencies {
    implementation("io.github.shusek:composemediaplayer:<version>")
}
```

### Build and runtime requirements

- **Build JDK and desktop runtime:** Java 25 or newer. Desktop JVM classes are intentionally published as Java 25 bytecode; Java 17/21 are not supported.
- **Gradle:** use the checked-in Gradle 9.6.1 wrapper, whose distribution checksum is verified.
- **Kotlin/Compose:** Kotlin 2.4.0 and Compose Multiplatform 1.11.1 for the current `2.0.x` line.
- **Android:** the library is built and verified with AGP 9.2.1, `compileSdk = 37`, and `minSdk = 23`. Consumer projects should use the same or a newer compatible Android toolchain and must have `compileSdk >= 37`.
- **Android MPV adapter:** `composemediaplayer-mpv` uses `minSdk = 28` and publishes ARM runtimes only.
- **iOS:** an Apple Silicon macOS host and a current Xcode/iOS Simulator are required for native compilation and tests.
- **Linux desktop runtime:** install GStreamer 1.x core, base libraries, and the plugins required by the media formats you use. On Debian/Ubuntu the CI baseline is `libgstreamer1.0-0`, `libgstreamer-plugins-base1.0-0`, `gstreamer1.0-plugins-base`, and `gstreamer1.0-plugins-good`.
- **Building the Linux JNI bridge:** additionally install CMake, `pkg-config`, a C compiler, full JDK 25 JNI headers, `libgstreamer1.0-dev`, and `libgstreamer-plugins-base1.0-dev`.
- **Building the macOS/Windows bridges:** use full JDK 25 JNI headers plus Xcode/Swift on macOS or Visual Studio with CMake on Windows.

### Optional MPV backend

MPV is a separate adapter artifact. The dependency direction is:

`composemediaplayer` → `composemediaplayer-core` ← `composemediaplayer-mpv`

The main artifact never imports MPV. Both modules share only the state, event,
capability, backend-factory, and renderer SPI from `composemediaplayer-core`.
The MPV adapter carries the matching KMediaMpv runtime transitively, so the
application does not add KMediaMpv coordinates separately.

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
    }
}
```

The MPV-specific wiring below belongs in `androidMain` or `jvmMain`. If a
library targets only Android and desktop JVM, it may put the adapter in
`commonMain` instead.

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

`inspectMpvBackend()` reports a missing runtime, unsupported target, or rejected
native payload without falling back silently. `rememberVideoPlayerState()`
continues to select the normal platform backend.

The verified runtime supports Android API 28+ on `arm64-v8a` and
`armeabi-v7a`, Linux on x86_64 and ARM64, and macOS on ARM64. Android x86,
Android x86_64, and macOS x86_64 are intentionally not published. The bundled
runtime has networking disabled, so use local media and subtitle files. Desktop
applications run on Java 25 with `--enable-native-access=ALL-UNNAMED`.

The core, default player, and adapter code remain under this repository's own
license. The separately
licensed [KMediaMpv](https://github.com/Shusek/KMediaMpv) artifacts carry the
reviewed native payload, notices, exact corresponding source, and recipient
relinking path required for the LGPL components. Only the KMediaMpv/native
boundary is subject to those separate terms; the application and the
backend-neutral player modules are not relicensed as LGPL.

See [Player backend architecture](docs/backend-architecture.md) for the module
rules and the checklist for implementing another backend.

The repository commits Kotlin/Wasm Yarn lockfiles. CI fails instead of silently replacing them when an npm dependency changes; run `./gradlew kotlinWasmUpgradeYarnLock` after an intentional npm update and review the resulting lockfile diff.

Gradle dependency verification pins external build artifacts by SHA-256. The only checksum exception is restricted to the locally generated `dev` and `0.0.0-consumer.*` smoke-test publications under this project's own coordinates, whose binaries change with the source under test.

## 📊 Compatibility Table

| Library Version | Kotlin Version | Compose Version | Java | Android toolchain |
|-----------------|----------------|-----------------|------|-------------------|
| 2.0.x           | 2.4.0          | 1.11.1          | 25   | AGP 9.2.1 / compileSdk 37 / minSdk 23; MPV minSdk 28 |
| 1.0.x           | 2.4.0          | 1.11.1          | 25   | AGP 9.2.1 / compileSdk 37 / minSdk 23 |
| 0.9.0           | 2.3.20         | 1.10.3          | See release | See release |
| 0.8.6           | 2.3.0          | 1.9.3           | See release | See release |
| 0.8.3           | 2.2.20         | 1.9.0           | See release | See release |
| 0.7.11          | 2.2.0          | 1.8.2           | See release | See release |
| 0.7.10          | 2.1.21         | 1.8.2           | See release | See release |

The current build records the public Kotlin ABI in version control. `checkKotlinAbi`, the published-consumer smoke project, platform tests, and the Java 25 classfile check must all pass before the single release workflow publishes the same GAV and native payload to Maven Central and the GitHub Pages Maven mirror. To reproduce the isolated consumer check locally, first run `./gradlew publishConsumerSmokeArtifacts -PcomposeMediaPlayer.skipNativeBuild=true --no-configuration-cache`, then `./gradlew consumerSmokeTest -PcomposeMediaPlayer.skipNativeBuild=true --no-configuration-cache`.

The release workflow verifies the complete GitHub Pages staging repository and uploads an immutable release-delta artifact before it performs the irreversible Maven Central release. The delta intentionally excludes mutable `maven-metadata.xml` files. If Maven Central succeeds but the final `gh-pages` push fails after its retries, overlay that delta on the latest Pages repository, run `python3 .github/scripts/rebuild_maven_metadata.py <pages-worktree>/maven`, and publish the resulting commit; do **not** run the Maven Central publication again for the same version.

## 🚀 Getting Started

### Initialization

Before using Compose Media Player, you need to create a state for the video player using the `rememberVideoPlayerState` function:

```kotlin
val playerState = rememberVideoPlayerState()
```

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
- `SurfaceType.Auto` (default): uses TextureView for normal videos, Media3's spherical GL surface for equirectangular 360 Android playback, and the OpenGL projection surface for flat stereo, VR180, fisheye, and EAC playback.
- `SurfaceType.SurfaceView`: uses SurfaceView for the player view, which is more performant for video playback but has limitations in terms of composability and animations.
- `SurfaceType.TextureView`: uses TextureView for the player view, which allows for more complex composable layouts and animations.
- `SurfaceType.SphericalGlSurfaceView`: uses Media3's spherical GL surface explicitly. This is intended for equirectangular 360 projection on Android.
- `SurfaceType.ProjectedGlSurfaceView`: uses the Android OpenGL projection shader explicitly. This supports flat stereo, equirectangular, fisheye and EAC projection.

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

On Android, `SurfaceType.Auto` switches equirectangular 360 sources with default texture crop to Media3's spherical GL surface and maps mono/SBS/OU stereo modes into Media3. Flat stereo, equirectangular 180, fisheye, EAC and cropped sources use the Android OpenGL projection surface. Android projected surfaces can use device rotation automatically unless `projectionViewControlMode` is set to `MANUAL`. iOS uses a SceneKit projection view for flat stereo, equirectangular, fisheye, EAC and cropped sources, with CoreMotion-backed viewport control in `AUTO`/`DEVICE_MOTION` modes. Web uses a WebGL projection canvas for flat stereo, equirectangular, fisheye, EAC and cropped sources, rendering stereo material as side-by-side output and reading `DeviceOrientationEvent` when the browser allows it. Remote web videos must be served with CORS headers when WebGL projection is enabled, because browsers block cross-origin video texture sampling otherwise. Some mobile browsers, especially iOS Safari, require a user permission gesture before device orientation events are delivered; the web renderer requests that permission on the first click, tap, or pointer release while device-motion projection control is active. Desktop JVM uses the same common projection settings, detection and presets; macOS, Windows and Linux Compose Canvas frame paths render flat stereo, equirectangular, fisheye, EAC and cropped sources through a Skia shader. Native desktop surfaces such as libVLC native-view and macOS HDR/EDR Metal remain native renderer paths and report that limitation through diagnostics.

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

Browser playback exposes HLS quality variants when the source is handled through `hls.js`. The list is populated after the HLS manifest has been loaded, so custom controls should render quality options from `availableHlsQualities` and handle an empty list as "not available yet" or "not supported by this backend".

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

### Browser MKV parser loading

Embedded MKV subtitle extraction on Wasm loads the exact `matroska-subtitles` 3.3.2 browser bundle with a pinned SHA-384 subresource-integrity value. Strict-CSP or offline deployments can self-host that same file; configure both values before creating a player:

```kotlin
WebMediaDependencyConfig.matroskaSubtitlesScriptUrl = "/vendor/matroska-subtitles-3.3.2.min.js"
WebMediaDependencyConfig.matroskaSubtitlesScriptIntegrity = "sha384-..."
```

Setting the URL to an empty string disables embedded MKV subtitle extraction without affecting ordinary browser video playback.

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

Compose Media Player supports adding subtitles from both URLs and local files. SRT and VTT subtitles are rendered using Compose, providing a uniform appearance across all platforms. ASS/SSA subtitles are rendered as plain dialogue text by Compose on native targets and with full styling on browser targets through JASSUB/libass. On desktop JVM, the libVLC MKV/WebM backends can expose and select embedded subtitle tracks; on macOS the canvas path can also dynamically load user-installed libass and blend full ASS/SSA rendering into the Compose/Skia frame. The external JVM HLS fallback can burn embedded subtitle tracks through user-installed VLC or `ffmpeg`.

#### 🎯 Supported Formats

The player supports SRT, VTT, ASS and SSA subtitle formats with automatic format detection. Browser targets use JASSUB/libass for styled ASS/SSA rendering; native targets parse ASS/SSA timing and dialogue text in Compose unless a platform-specific backend, such as the desktop JVM libVLC canvas backend, provides full libass rendering.

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

#### Browser ASS/SSA subtitles with JASSUB/libass

ASS/SSA subtitle rendering is enabled by default on the `wasmJs` browser target. As a library user, you do not need to add `jassub`, libass, worker scripts, or WASM files manually. The library declares the `jassub` npm dependency and the browser bundle includes the default JASSUB worker, WASM and fallback font assets.

For a basic browser app, just select an `.ass` or `.ssa` subtitle track:

```kotlin
val playerState = rememberVideoPlayerState()

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

You can configure the renderer once in your browser entry point before rendering your Compose app:

```kotlin
import io.github.kdroidfilter.composemediaplayer.AssSubtitleRendererConfig

fun main() {
    AssSubtitleRendererConfig.debug = false
    AssSubtitleRendererConfig.queryFonts = false

    // Start your Compose app here.
}
```

Configuration options:

| Option | Default | Description |
| :--- | :--- | :--- |
| `enabled` | `true` | Enables or disables browser ASS/SSA rendering. When disabled, ASS/SSA tracks are ignored by the JASSUB renderer. |
| `workerUrl` | `""` | Optional URL for a self-hosted JASSUB worker. Leave empty to use the bundled npm asset. |
| `wasmUrl` | `""` | Optional URL for `jassub-worker.wasm`. Leave empty to use the bundled npm asset. |
| `modernWasmUrl` | `""` | Optional URL for `jassub-worker-modern.wasm`. Leave empty to use the bundled npm asset. |
| `fallbackFontUrl` | `""` | Optional URL for the fallback font used by libass. Leave empty to use JASSUB defaults. |
| `fallbackFontFamily` | `"liberation sans"` | Font family name mapped to `fallbackFontUrl`. |
| `queryFonts` | `false` | Lets JASSUB query browser fonts when supported. This may prompt or vary by browser. |
| `debug` | `false` | Enables JASSUB debug logging. |

For production deployments you can optionally self-host the worker, WASM and fallback font assets. This is useful when your hosting/CDN needs explicit cache headers, predictable URLs, or a strict Content Security Policy:

```kotlin
AssSubtitleRendererConfig.workerUrl = "/jassub/worker/worker.js"
AssSubtitleRendererConfig.wasmUrl = "/jassub/wasm/jassub-worker.wasm"
AssSubtitleRendererConfig.modernWasmUrl = "/jassub/wasm/jassub-worker-modern.wasm"
AssSubtitleRendererConfig.fallbackFontUrl = "/jassub/default.woff2"
```

The URLs may be relative to your app base URL or absolute. Prefer same-origin URLs. If you host the video, subtitle, worker, WASM or font files on another domain, configure CORS headers so the browser can load them from your app origin.

Browser notes:
- ASS/SSA rendering requires browser support for `OffscreenCanvas` and `canvas.transferControlToOffscreen()`.
- Web fullscreen should keep the same video element alive; entering fullscreen should not restart playback.
- SRT and VTT do not use JASSUB. They continue to use the Compose subtitle renderer.
- Native Android, iOS and desktop targets do not use this JASSUB configuration.

#### Selecting Embedded Audio and Subtitle Tracks

When the backend exposes tracks from the media container, they are available through the player state. Browser targets read native `audioTracks` and `textTracks` from the `<video>` element, Android reads Media3 track groups, and the desktop JVM libVLC/external HLS fallbacks expose embedded audio/subtitle tracks when probing is available.

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
> Metadata support is experimental. There may be inconsistencies between platforms, and on WASM it's currently limited to width and height only.

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

- **Player with Separate Audio and Video Streams**: Add functionality to support different audio and video streams for advanced playback scenarios.
