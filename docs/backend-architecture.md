# Player backend architecture

## Dependency graph

```text
application
├── composemediaplayer ──────┐
└── composemediaplayer-mpv ──┴──> composemediaplayer-core
```

The implementation modules are siblings. They never depend on each other:

- `composemediaplayer-core` owns backend-neutral state, events, capabilities,
  backend factories, and the rendering SPI.
- `composemediaplayer` owns the default Media3, AVPlayer, browser, and desktop
  JNI implementations plus the public `VideoPlayerSurface`.
- `composemediaplayer-mpv` owns the optional Android/JVM adapter and depends
  directly on the matching KMediaMpv runtime.

The `verifyBackendModuleBoundaries` Gradle task rejects a dependency from the
default player to MPV, from MPV to the default player, or from core to either
implementation.

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
core and KMediaMpv, but no default-player implementation.

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

## MPV split

`AbstractMpvVideoPlayerState` contains the shared state machine, value
normalization, seek semantics, source lifecycle, events, metadata, callbacks,
and audio/subtitle bookkeeping. Android and desktop implementations retain only
native source resolution, runtime commands/events, polling, and rendering.

The adapter is published only for Android and JVM. Its KMediaMpv runtime
supports Android API 28+ on `arm64-v8a` and `armeabi-v7a`, Linux x86_64/ARM64,
and macOS ARM64. Android x86/x86_64 and macOS x86_64 are not release targets.

## Distribution and licensing boundary

The core, default player, and adapter use this repository's license. The
separately published KMediaMpv runtime is the boundary that carries the native
license notices, corresponding source, and recipient relinking materials. The
adapter consumes that runtime as a normal transitive dependency; it does not
copy the native payload or relicense the application-facing contracts.
