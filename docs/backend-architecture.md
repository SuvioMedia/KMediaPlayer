# Player backend architecture

## Dependency graph

```text
application
├── composemediaplayer ──────────────┬──> composemediaplayer-extension-api ──┐
│                                    └────────────────────────────────────────┤
├── composemediaplayer-ass ─────────────> composemediaplayer-extension-api ──┤
├── composemediaplayer-dolbyvision ─────> composemediaplayer-extension-api ──┤
├── composemediaplayer-kmediabridge ────> composemediaplayer-extension-api ──┤
├── composemediaplayer-desktop-tao ────────────────────────────────────> composemediaplayer-core
├── composemediaplayer-mpv ───────────────────────────────────────────────> desktop-tao ──> core
└── composemediaplayer-libvlc ────────────────────────────────────────────> desktop-tao ──> core
```

Backend and extension implementations never depend on the default player:

- `composemediaplayer-core` owns backend-neutral state, events, capabilities,
  backend factories, and the rendering SPI.
- `composemediaplayer-extension-api` owns lightweight common and platform
  contracts for source, subtitle, color-conversion, and scoped desktop bridge
  extensions. It depends only on core.
- `composemediaplayer-desktop-tao` owns Tao native-surface interop, ordered
  routing, single-session ownership, and transactional backend switching. It
  does not create windows, depends only on core plus Tao, and never chooses a
  platform implementation itself.
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
- `composemediaplayer-libvlc` owns the optional Android/JVM/iOS KMediaVlc adapter.
  Android uses the runtime's direct `Surface` boundary, while JVM uses the
  shared Tao renderer for GPU TextureView output or the bounded CPU/Skia
  projection path. iOS dynamically opens only Maven-delivered frameworks from
  the signed application's private framework directory. Each target uses its
  matching KMediaVlc runtime artifact; this adapter never searches for a
  user-installed VLC.

Both native adapters inherit their command normalization, observable state,
source lifecycle, event/error handling, metadata, chapter, and track bookkeeping
from `AbstractBackendVideoPlayerState` in core. Backend modules must not fork
that state machine. They retain only runtime-specific commands, events,
availability checks, source resolution, and rendering.

The `verifyBackendModuleBoundaries` Gradle task rejects a dependency from the
default player to MPV or KMediaBridge/FFmpeg, from an optional implementation
to the default player, from MPV or libVLC to the default player, or from core and the
extension API to implementation modules.

## Full desktop playback

Full playback on JVM desktop is rendered inside the application's existing Tao
window. `DesktopPlaybackSurface` coordinates a `DesktopPlaybackSession`, while
`VideoPlayerSurface` renders an app-owned state directly. Neither creates an OS
window as a side effect.

```kotlin
val options = VideoPlaybackOptions(
    extensions = listOf(KMediaBridgeDesktopExtension()),
)
val session = remember {
    DesktopPlaybackSession(
        backends = listOf(
            platformDesktopPlaybackBackend(options),
            kMediaBridgeRemuxDesktopPlaybackBackend(options),
            mpvDesktopPlaybackBackend(),
            libVlcDesktopPlaybackBackend(options),
            kMediaBridgeTranscodeDesktopPlaybackBackend(options),
        ),
        hlsMediaProxyFactory = JvmHttpHlsMediaProxyFactory(),
    )
}

LaunchedEffect(uri) {
    session.open(DesktopPlaybackRequest(MediaSourceSpec(uri)))
}

DesktopPlaybackSurface(
    session = session,
    modifier = Modifier.fillMaxSize(),
)
```

The HLS proxy remains an optional source adapter for platform backends that need
it. The verified MPV backend receives normal `http`/`https` URLs directly,
passes sanitized request headers as a structured libmpv string array, clears
them between sources, and keeps TLS peer verification enabled.

The automatic order is platform direct, KMediaBridge bounded remux, native MPV,
native libVLC, then KMediaBridge compatibility transcode. Unavailable or
unsupported stages are skipped. A forced backend switch creates and restores a
paused replacement first, preserves position, volume, rate, projection, audio
and subtitle selection, and releases the old renderer only after the new surface
is attached. If replacement fails, the previous backend resumes.

Only one full desktop session may own playback in a process. Opening another
session closes the previous one. Renderer selection and source adaptation are
separate: `DesktopMediaSourcePolicy` controls direct/remux/transcode input while
the backend controls the output surface.

The application window uses the Nucleus Tao backend. On macOS Tao owns the
`NSWindow`; AVFoundation/Metal, libVLC, and native MPV provide a direct `NSView`
below the Compose control layer. Windows embeds a renderer-owned child `HWND`, and
Linux embeds a renderer-owned `GtkWidget` for Wayland HDR or X11/XWayland libVLC.
Resize and fullscreen stay inside that one native hierarchy on every platform.
No AWT/Swing/JAWT/JBR window peer is initialized. A software Skia route remains
a valid SDR fallback and is never promoted to an HDR claim.

MPV direct input is restricted to local files and reviewed HTTP(S) protocols.
Apple uses the system SecureTransport trust path, Windows uses SChannel, and
Android/Linux receive an explicit CA PEM from the platform/JVM trust store.
Header names and values are bounded and reject injection before crossing the
native boundary. Diagnostics and failures must continue to redact the URI and
all header values.

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
from core. The isolated MPV and libVLC consumer tests are intentionally compiled
with only their published adapter coordinate; each transitive Android/JVM graph
contains core and its platform runtime, but no default-player implementation.
The iOS libVLC integration gate resolves the separately published KMediaVlc
runtime ZIP from Maven Central, validates its inventory and ABI header, and
exercises real playback in a signed simulator application.

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

The MPV Android, iOS, and desktop states extend core's
`AbstractBackendVideoPlayerState`, the same base used by libVLC. They retain
only native source resolution, runtime commands/events, polling, and rendering;
there is no MPV-specific copy of the backend-neutral state machine.

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

## Bundled libVLC 4 split

The libVLC adapter keeps target-neutral options, backend selection, and public
availability types in `commonMain`. Its JVM actual retains the existing desktop
API facade and selects KMediaVlc GPU push for flat playback or CPU pull for
projected, stereo, rotated, and cropped SDR playback. The CPU route feeds the
same Skia projection shader as the other Tao desktop backends. Native GPU VR is
not claimed until the runtime and host expose a verified projection pass.

The Android actual supports API 28+ on `arm64-v8a` and `armeabi-v7a`. It owns a
single direct libVLC `Surface`, preserves source intent across surface and
fullscreen replacement, accepts path, `file:`, `content:`, and HTTP(S) input,
and supports automatic or software-only decoding. Its first layout contract is
`ContentScale.Fit`. Projection, texture crop, other content scales, explicit
desktop frame transports, non-default dynamic-range policies, and non-default Dolby
Vision fail closed rather than silently selecting different geometry or color.

The iOS actual publishes ARM64 device and simulator variants through CocoaPods.
It loads the stable KMediaVlc ABI only from the signed application's private
framework directory and never searches for a system or user-installed VLC.
The first transport is CPU pull: bounded premultiplied RGBA8/sRGB frames are
copied into adapter-owned CoreGraphics images before the native frame is
released. Media generations fence stale snapshots and frames, while looping
reopens the exact source request. GPU push, projection/crop, unverified HDR or
Dolby Vision policies, and desktop runtime paths fail closed.

The isolated consumer publishes core, desktop Tao, and all current libVLC
adapter variants to a runner-local Maven repository in one Gradle invocation,
then compiles Android and runs JVM tests in a second invocation. KMediaVlc is
resolved from its immutable Maven Central releases; local composites remain
optional development overrides only.

## Distribution and licensing boundary

The core, extension API, default player, and adapters use this repository's license. They contain
no copied KMediaMpv or KMediaVlc runtime implementation. The separately published KMediaMpv
packages contain the MPV-specific client and consume
KMediaFfmpegRuntime as an exact dependency. The shared runtime carries FFmpeg/libass notices,
corresponding source and recipient relinking materials. Android, desktop, and
Apple resolve exact Maven releases; none of these boundaries relicense the
application-facing contracts.

KMediaBridge follows the same separation: the KMediaPlayer adapter owns only the extension-facing
API and translation layer. The separately published KMediaBridge client contains only the bridge
library and binds the same KMediaFfmpegRuntime ID as KMediaMpv. An application can therefore contain
both backends but only one shared FFmpeg/libass graph. A caller-selected external compatible runtime
remains the caller's licensing responsibility.

KMediaVlc follows the same adapter/runtime boundary. KMediaPlayer publishes only the independently
implemented backend-facing Kotlin adapter and uses KMediaVlc's ISC client ABI. The separately
versioned, LGPL-2.1-or-later KMediaVlc repository and packages own the runtime clients, native
bridges, pinned libVLC 4 binaries, manifests, source/relinking material, notices, and platform
eligibility decisions. Making KMediaPlayer private does not change those public LGPL artifacts or
their recipients' replacement and relinking rights. Desktop and Android use
KMediaVlc `0.1.0-rc.6`; Apple uses the separately published
`kmedia-vlc-runtime-ios:0.1.0-rc.7` ZIP and embeds its selected XCFramework
slices during the application build.
