# Player backend architecture

## Dependency graph

```text
application
├── composemediaplayer ──────────────┬──> composemediaplayer-extension-api ──┐
│                                    └────────────────────────────────────────┤
├── composemediaplayer-ass ─────────────> composemediaplayer-extension-api ──┤
├── composemediaplayer-dolbyvision ─────> composemediaplayer-extension-api ──┤
├── composemediaplayer-kmediabridge ────> composemediaplayer-extension-api ──┤
└── composemediaplayer-mpv ───────────────────────────────────────────────────┴──> composemediaplayer-core
```

Backend and extension implementations never depend on the default player:

- `composemediaplayer-core` owns backend-neutral state, events, capabilities,
  backend factories, and the rendering SPI.
- `composemediaplayer-extension-api` owns lightweight common and platform
  contracts for source, subtitle, color-conversion, and scoped desktop bridge
  extensions. It depends only on core.
- `composemediaplayer` owns the default Media3, AVPlayer, browser, and desktop
  JNI implementations plus the public `VideoPlayerSurface`. It consumes
  extension contracts but contains no ASS, Dolby Vision, KMediaBridge, or
  FFmpeg implementation dependency.
- `composemediaplayer-ass`, `composemediaplayer-dolbyvision`, and
  `composemediaplayer-kmediabridge` implement extension contracts directly.
  They can be compiled and published without the default player.
- `composemediaplayer-mpv` owns the optional Android/iOS/JVM adapter. Android
  and all bundled desktop paths depend directly on the matching KMediaMpv
  runtime. On iOS, the matching KMediaMpv CocoaPod embeds the signed
  XCFramework graph at build time. Custom native runtimes remain opt-in.

The `verifyBackendModuleBoundaries` Gradle task rejects a dependency from the
default player to MPV or KMediaBridge/FFmpeg, from an extension implementation
to the default player, from MPV to the default player, or from core and the
extension API to implementation modules.

## Pipeline extensions

Applications install stable extension instances in the player configuration:

```kotlin
val options = VideoPlaybackOptions(
    extensions = listOf(
        AssSubtitleExtension(),
        DolbyVisionExtension(),
        KMediaBridgeDesktopExtension(),
    ),
)
```

`VideoPlaybackOptions` rejects blank and duplicate extension identifiers. Each
extension reports `AVAILABLE`, `DEGRADED`, or `UNAVAILABLE`; an unavailable
extension remains visible in `extensionStatuses` for diagnostics but contributes
no capabilities and is not called by a source or renderer selector.

Extension objects are reusable, thread-safe configuration providers. Mutable
per-player or per-source state belongs in scoped runtime objects. In particular,
every desktop bridge open returns a `DesktopPlaybackBridgeSession` that the
default player closes when the source is replaced or the player is disposed.
This prevents one extension instance from coupling the lifetime of multiple
players.

Browser subtitle extensions can keep implementing the original
`WebSubtitlePipelineExtension.SubtitleOverlay` overload. Extensions that need exact projection and
`ContentScale` geometry should also override the overload that receives `displayElement` and
`contentScale`; the default implementation delegates to the original hook for source compatibility.

The isolated extension consumer test resolves published ASS, Dolby Vision, and
KMediaBridge coordinates plus the extension API while deliberately excluding
`composemediaplayer`. It fails if the default player appears on the runtime
classpath.

## Application selection

An application selects a backend in its composition root:

```kotlin
val backend: VideoPlayerBackend = mpvVideoPlayerBackend()
val playerState = rememberVideoPlayerState(backend)
```

The rest of the application can depend only on `VideoPlayerBackend` and
`VideoPlayerState`. Selecting MPV does not change the default backend and there
is no global backend registry.

`rememberVideoPlayerState(backend)` owns the state lifecycle and calls
`dispose()` when the composition leaves. `VideoPlayerSurface` detects the
backend-neutral `VideoPlayerSurfaceProvider` SPI before trying the default
platform renderer.

An application that uses only an optional backend can omit
`composemediaplayer` entirely and render through `BackendVideoPlayerSurface`
from core. The isolated MPV consumer test is intentionally compiled with only
the published `composemediaplayer-mpv` coordinate; its transitive graph contains
core and the platform runtime where one is published, but no default-player
implementation.

## Implementing another backend

An optional backend module should:

1. Depend on `composemediaplayer-core`, not on `composemediaplayer`.
2. Implement `VideoPlayerState`; shared command and observable-state behavior
   should live in common code.
3. Implement `VideoPlayerSurfaceProvider` on states that render their own
   surface.
4. Expose a small `VideoPlayerBackend` factory and a read-only availability
   probe.
5. Keep native runtime types internal so they do not leak into the public ABI.
6. Put target-specific integrations only in the source sets they support.
7. Add an isolated published-artifact consumer test, including verification of
   any transitive runtime dependency.

An optional pipeline extension follows the same isolation rules but depends on
`composemediaplayer-extension-api`, implements the narrowest platform hook it
needs, exposes runtime state through typed availability, and keeps third-party
runtime types out of its public ABI.

## MPV split

`AbstractMpvVideoPlayerState` contains the shared state machine, value
normalization, seek semantics, source lifecycle, events, metadata, callbacks,
and audio/subtitle bookkeeping. Android, iOS, and desktop implementations retain
only native source resolution, runtime commands/events, polling, and rendering.

The adapter publishes Android, JVM, `iosArm64`, and `iosSimulatorArm64`
variants. Its verified KMediaMpv runtime supports Android API 28+ on
`arm64-v8a` and `armeabi-v7a`, Linux x86_64/ARM64, macOS ARM64, Windows
x86_64, and iOS device/simulator ARM64. Android
x86/x86_64, macOS x86_64, Windows ARM64, and Intel iOS simulators are not
release targets.

The iOS variant links only a small dynamic-loader bridge into the KLIB. The
default source resolves `KMediaMpv.framework` from the signed application
bundle after CocoaPods embeds the audited graph. This keeps signing and App
Store packaging under the application's control and avoids an invalid
extract-and-load design inside the iOS sandbox.

## Distribution and licensing boundary

The core, extension API, default player, and adapters use this repository's license. The
separately published KMediaMpv runtime is the boundary that carries the native
license notices, corresponding source, and recipient relinking materials. The
adapter consumes desktop/Android runtimes as normal transitive dependencies
and the Apple runtime as a build-time CocoaPod; it does not relicense the
application-facing contracts. A caller-selected custom libmpv remains the
application's distribution and license-compliance responsibility.

KMediaBridge follows the same separation: the KMediaPlayer adapter owns only
the extension-facing API and translation layer. The separately published
KMediaBridge runtime owns the dynamically linked FFmpeg payload, notices,
corresponding source, SBOM inputs, and relinking instructions. A caller-selected
external compatible runtime is outside that audited payload and remains the
caller's licensing responsibility.
