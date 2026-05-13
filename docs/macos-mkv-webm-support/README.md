# macOS MKV/WebM Fallback Support

On macOS, MKV/WebM support is an optional fallback for formats that AVPlayer cannot demux directly.

In `auto` mode, Compose Media Player first looks for a user-installed VLC.app with a `libVLC` dylib matching the current app/JVM architecture. When found, it loads `libvlc.5.dylib` and the sibling `libvlccore.dylib` dynamically at runtime, uses libVLC video callbacks inside the Compose surface, exposes embedded audio/subtitle tracks, reports native progress/duration, and keeps playback inside the app. The app never launches the visible VLC UI.

For ASS/SSA subtitle rendering, the memory-callback path can optionally load a user-installed `libass.dylib` dynamically, render ASS/SSA to pixels, and blend those pixels into the Compose/Skia video frame. Embedded ASS/SSA tracks are extracted with the built-in Matroska reader.

For remote MKV files with cues, the reader first uses HTTP byte ranges to load the header, cue table, and subtitle clusters around the current playback time, then completes the full subtitle track in the background and caches it per `(URI, stream)`. A user-installed `ffmpeg` process is only an optional fallback for unsupported Matroska layouts.

To require the in-process libVLC backend, set one of:

```shell
COMPOSE_MEDIA_PLAYER_MACOS_FALLBACK_BACKEND=libvlc
```

```shell
-Dcomposemediaplayer.macos.fallbackBackend=libvlc
```

To point at a specific libVLC install, set:

```shell
COMPOSE_MEDIA_PLAYER_LIBVLC=/path/to/libvlc.5.dylib
COMPOSE_MEDIA_PLAYER_LIBVLC_PLUGINS=/path/to/plugins
```

or the equivalent JVM system properties:

```shell
-Dcomposemediaplayer.macos.libvlc=/path/to/libvlc.5.dylib
-Dcomposemediaplayer.macos.libvlc.plugins=/path/to/plugins
```

To choose the ASS renderer, set one of:

```shell
COMPOSE_MEDIA_PLAYER_LIBASS=/path/to/libass.dylib
```

```shell
-Dcomposemediaplayer.macos.libass=/path/to/libass.dylib
```

Homebrew's common `/opt/homebrew` and `/usr/local` libass paths are auto-detected.

If compatible libVLC is not available, the external HLS fallback can use user-installed `ffmpeg` or VLC. The ffmpeg backend transcodes the first video stream and selected audio stream to H.264/AAC HLS in a bounded temporary buffer, serves that HLS source from `127.0.0.1`, and hands it to AVPlayer.

Selecting an embedded text subtitle track such as ASS/SSA/SRT/WebVTT restarts the HLS fallback with ffmpeg's `subtitles` filter, so ASS/SSA is rendered by libass and burned into the temporary HLS video. The configured `ffmpeg` must expose that filter. On Homebrew, the regular `ffmpeg` formula may not include libass; install `ffmpeg-full` or point `COMPOSE_MEDIA_PLAYER_FFMPEG` to another ffmpeg build that exposes the `subtitles` filter. Homebrew's keg-only `ffmpeg-full` is auto-detected for embedded subtitle rendering when it is installed.

To use VLC as the external HLS fallback, set one of:

```shell
COMPOSE_MEDIA_PLAYER_MACOS_HLS_FALLBACK_BACKEND=vlc
```

```shell
-Dcomposemediaplayer.macos.hlsFallbackBackend=vlc
```

That backend uses VLC's `soverlay` transcode path for embedded subtitle rendering. To choose a specific executable, set one of:

```shell
COMPOSE_MEDIA_PLAYER_VLC=/path/to/VLC
```

```shell
-Dcomposemediaplayer.macos.vlc=/path/to/VLC
```

The library does **not** bundle, compile-link, or redistribute `ffmpeg`, VLC, libVLC, libass, mpv, IINA, `vlcj`, JNA, or GPL code. VLC/libVLC/libass remain user-installed runtime components; if you bundle or redistribute external binaries yourself, their license terms remain your responsibility.

To disable the macOS fallback, set one of:

```shell
COMPOSE_MEDIA_PLAYER_MACOS_FFMPEG_FALLBACK=false
```

```shell
-Dcomposemediaplayer.macos.ffmpegFallback=false
```
