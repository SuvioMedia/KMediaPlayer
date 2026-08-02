# Migrating from 2.x to 3.0

KMediaPlayer 3.0 changes the optional MPV/KMediaBridge native boundary. The base player remains backend-free.

## Dependencies

Add either or both adapters normally:

```kotlin
implementation("io.github.shusek:composemediaplayer-mpv:3.0.0-rc.1")
implementation("io.github.shusek:composemediaplayer-kmediabridge:3.0.0-rc.1")
```

Remove direct KMediaMpv, KMediaBridge client and `runtimeOnly` FFmpeg coordinates. Each adapter now transitively supplies its client, and both clients strictly select the same `KMediaFfmpegRuntime` release. Mixing a 2.x private runtime with the 3.0 clients fails before a client is loaded.

## Android MPV decoding

`MpvPlaybackOptions.androidDecodeMode` defaults to `MpvAndroidDecodeMode.MEDIA_CODEC_COPY`. It asks MPV to use `mediacodec-copy` and automatically falls back to software decoding after a hardware failure. Use `SOFTWARE_ONLY` when deterministic software decoding is required.

Android distributions contain only `arm64-v8a` and `armeabi-v7a`. KMediaBridge keeps `minSdk 23`; MPV keeps `minSdk 28`.

## Desktop and Apple

macOS is ARM64-only. Linux supports x86_64 and ARM64, Windows supports x86_64. iOS embeds the ARM64 device/simulator `KMediaMpv` pod and its exact `KMediaFfmpegRuntime` pod dependency; KMediaBridge remains unavailable as an iOS backend.

Full-size JVM playback no longer relies on an implicitly created native window
inside `VideoPlayerSurface`. Add `composemediaplayer-desktop-window` (it is also
transitive from the default player and MPV JVM artifacts), create one
`DesktopPlaybackSession`, and render it with `DesktopVideoPlayerWindow`.
Continue to use `VideoPlayerSurface` for feed/gallery/mini-player embedding.

The session's default route is:

```text
platform direct -> KMediaBridge remux -> MPV -> libVLC -> KMediaBridge transcode
```

Backend changes are transactional. The replacement restores playback state
before the old native surface is disposed and rolls back on failure. Applications
that formerly changed `composemediaplayer.fallbackBackend` globally should set
`VideoPlaybackOptions.desktopMediaSourcePolicy` or select a session backend id
instead.

Android, iOS, and Web keep their embedded platform surfaces. Normal Android does
not need a desktop-style second window; Meta Quest spatial playback also keeps
its existing Android/VR surface ownership.

## Licensing

The shared LGPL runtime is a separately replaceable distribution with corresponding source, build recipes, SBOM and checksums. This does not relicense KMediaPlayer or the independent KMediaMpv/KMediaBridge client code.
