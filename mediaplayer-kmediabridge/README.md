# Compose Media Player KMediaBridge

Optional Android and desktop JVM source bridges between KMediaPlayer's typed pipeline and
KMediaBridge. This adapter depends on `composemediaplayer-extension-api`, never on the default
player, and does not expose KMediaBridge or FFmpeg types in its public API.

The default `composemediaplayer` artifact contains neither KMediaBridge nor FFmpeg. Merely resolving
this adapter also does not activate it: the application must put exactly one platform extension in
`VideoPlaybackOptions.extensions`.

## Android

Add the adapter and the separately published audited runtime:

```kotlin
dependencies {
    implementation("io.github.shusek:composemediaplayer-kmediabridge:<version>")
    runtimeOnly("io.github.shusek:kmedia-bridge-ffmpeg-runtime-android:0.4.2")
}

val options = VideoPlaybackOptions(
    extensions = listOf(KMediaBridgeAndroidExtension()),
)
```

`KMediaBridgeAndroidRuntimeSelection.Bundled` loads that runtime AAR. An application can instead
pass `KMediaBridgeAndroidRuntimeSelection.ExternalDirectory(directory)` for a compatible
application-controlled payload. That directory is not an arbitrary system FFmpeg installation;
it must implement KMediaBridge's reviewed native ABI. The application owns the licensing and
relinking obligations of any replacement it supplies.

The Android transport is published for `arm64-v8a` and `armeabi-v7a` only and is deliberately
fail-closed: local, unencrypted VOD only. It can perform a declared HDR10, HDR10+, or HLG to BT.709
source conversion. It does not claim remote, live, DRM, Dolby Vision tone mapping, or HDR10+
dynamic-metadata application.

## Desktop JVM

The JVM publication carries the audited desktop runtime as a runtime dependency. Activation is
still explicit. macOS support is Apple Silicon only; Windows and Linux retain their existing x64
and ARM64 matrices:

```kotlin
dependencies {
    implementation("io.github.shusek:composemediaplayer-kmediabridge:<version>")
}

val options = VideoPlaybackOptions(
    extensions = listOf(KMediaBridgeDesktopExtension()),
)
```

The default selection is `BUNDLED_ONLY`. A compatible external payload can be selected without
changing the player API:

```kotlin
val bridge = KMediaBridgeDesktopExtension(
    runtimeSelection =
        KMediaBridgeDesktopRuntimeSelection.fromExternalDirectory(runtimeDirectory),
)
```

`EXTERNAL_ONLY`, `PREFER_EXTERNAL`, and `PREFER_BUNDLED` are also available through
`KMediaBridgeDesktopRuntimeSelection`. An external runtime must be a compatible KMediaBridge
runtime directory; the adapter never launches `ffmpeg.exe`, `ffmpeg`, or `ffprobe`, and it does not
bind to arbitrary libraries found on the system path.

Each opened source gets an owned `DesktopPlaybackBridgeSession`. Closing or replacing the source
closes only that session, so multiple players do not share mutable playback state. The extension
reports runtime availability and manifest-backed probe, copy, tone-map, and subtitle-burn
capabilities before the planner uses it.

## Capability meaning

`supportsStreamingVOD` means that the adapter can feed bounded sequential fragments to the player.
It is not a 4K60, latency, power, or thermal-performance claim; those properties are qualified per
device by KMediaPlayer's hardware release matrix.
