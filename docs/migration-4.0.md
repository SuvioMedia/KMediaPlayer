# Migrating from 3.x to 4.0

KMediaPlayer 4.0 replaces the desktop JVM AWT/JBR window path with Nucleus Tao
and renderer-owned native views. Android, iOS, and browser Wasm keep their
existing embedded platform surfaces.

## Dependencies

Keep every KMediaPlayer artifact on the same immutable version:

```kotlin
implementation("io.github.shusek:composemediaplayer:4.0.1")
implementation("io.github.shusek:composemediaplayer-desktop-window:4.0.1")

// Optional backends; add only those selected by the application.
implementation("io.github.shusek:composemediaplayer-mpv:4.0.1")
implementation("io.github.shusek:composemediaplayer-kmediabridge:4.0.1")
```

The default player and MPV JVM artifacts already expose the desktop-window
contract transitively. A direct desktop-window dependency is useful when the
application owns its desktop bootstrap explicitly.

## Desktop application bootstrap

Desktop JVM applications must run through Nucleus Tao and provide the Nucleus
application scope to the player:

```kotlin
fun main(args: Array<String>) = nucleusApplication(args, backend = NucleusBackend.Tao) {
    val app = this
    app.ProvideDesktopVideoApplicationScope {
        app.DecoratedWindow(
            onCloseRequest = app::exitApplication,
            title = "My app",
            nativePopupLayers = true,
        ) {
            App()
        }
    }
}
```

The verified desktop pair is Nucleus `2.2.0` with Compose Multiplatform
`1.11.1`, running on Java 25. The production path does not initialize AWT,
Swing, JAWT, or a JetBrains Runtime window toolkit.

## Player windows and surfaces

Create one `DesktopPlaybackSession` for full-size playback and render it with
`DesktopVideoPlayerWindow`. Continue using `VideoPlayerSurface` for embedded
feed, gallery, and mini-player content.

Tao owns the platform window. Each backend supplies a native child view:

- macOS: `NSView*` for AVFoundation/Metal, MPV, or libVLC;
- Windows: child `HWND` for Media Foundation, MPV, or libVLC;
- Linux: `GtkWidget*` for GStreamer, MPV, or libVLC.

Compose controls stay in Nucleus' overlay scene above the native renderer.
Fullscreen changes the Tao-owned window placement instead of moving a renderer
between Java window peers.

`JvmNativeVideoHost`, the JBR Wayland surface host, and the AWT/JAWT window
bridge have been removed. Applications that imported those internal JVM APIs
must migrate to `DesktopNativeVideoSurface` and `DesktopVideoPlayerWindow`.

## Backend switching

Backend changes are transactional. A replacement backend opens the source and
restores playback position, play/pause state, volume, speed, audio selection,
and subtitles before the old native surface is retired. A failed replacement
leaves the previous backend active.

The automatic desktop route is:

```text
platform direct -> KMediaBridge remux -> MPV -> libVLC -> KMediaBridge transcode
```

Use `VideoPlaybackOptions.desktopMediaSourcePolicy` or an explicit session
backend id instead of changing the former process-global JVM fallback setting.

## Native overlays and Material dialogs

Nucleus 2.2.0 renders `NativeView` overlay content in a bounded secondary
`ComposeScene`. Keep player sheets in that scene so they remain above native
video. Material3 `ModalBottomSheet` currently opens a separate desktop dialog
with incorrect virtual-desktop bounds in this configuration; the sample uses
Material3 `BottomSheetScaffold` and `SheetState` inline until the upstream
Nucleus dialog-host issue is fixed.

## Platform notes

- macOS JVM requires Apple Silicon and macOS 14 or newer.
- Windows requires Windows 10 1809 or newer; confirmed HDR additionally needs
  an active Advanced Color output.
- Linux requires GTK3 and GStreamer. Confirmed HDR additionally needs a native
  Wayland session, GStreamer 1.28.5+, `color-management-v1`, and the advertised
  Vulkan, DMA-BUF, and output capabilities.
