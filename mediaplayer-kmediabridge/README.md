# Compose Media Player KMediaBridge

Optional Android and desktop JVM adapter between KMediaPlayer's typed extension pipeline and KMediaBridge. The default `composemediaplayer` artifact contains neither KMediaBridge nor FFmpeg.

Add only the adapter:

```kotlin
implementation("io.github.shusek:composemediaplayer-kmediabridge:<player-version>")
```

It transitively supplies the matching KMediaBridge API/backend, one thin platform client and the exact shared KMediaFfmpegRuntime. Do not add `runtimeOnly`, a native client, or a Git submodule.

Activation remains explicit:

```kotlin
val options = VideoPlaybackOptions(
    extensions = listOf(KMediaBridgeAndroidExtension()),
)
```

Desktop uses `KMediaBridgeDesktopExtension()`. Android supports only `arm64-v8a` and `armeabi-v7a` with `minSdk 23`; Linux supports x86_64/ARM64, Windows x86_64 and macOS ARM64. There is no iOS KMediaBridge backend.

The adapter owns no FFmpeg distribution. KMediaBridge's bridge client and KMediaFfmpegRuntime validate the exact runtime ID before loading. If the MPV adapter is also present, both clients use the same process runtime and a mismatched runtime ID is rejected deterministically.

The Android route is local, unencrypted VOD only. It can perform declared HDR10/HDR10+/HLG to BT.709 source conversion; remote/live/DRM and Dolby Vision tone mapping fail closed. Desktop sessions remain separately owned and bounded.

Public availability does not alter the licenses attached to KMediaPlayer, KMediaBridge or the shared runtime.
