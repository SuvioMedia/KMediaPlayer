# JVM MKV/WebM Fallback Support

On desktop JVM targets, MKV/WebM support can use native playback or optional fallback backends when the platform player cannot demux a source directly. macOS uses these fallbacks for formats AVPlayer cannot demux; Windows can use the same in-process libVLC canvas backend or external HLS fallback for Matroska/WebM; Linux keeps native GStreamer playback first and can use the same libVLC canvas backend or retry through the external HLS fallback if native open fails.

In `auto` mode, Compose Media Player treats a user-installed VLC/libVLC as the preferred JVM fallback. It first looks for libVLC matching the current app/JVM architecture. When found, it loads libVLC dynamically at runtime, uses libVLC video callbacks inside the Compose surface, passes request headers/cookies directly to libVLC, exposes embedded audio/subtitle tracks, reports native progress/duration, and keeps playback inside the app. The app never launches the visible VLC UI.

For ASS/SSA subtitle rendering, the memory-callback path can optionally load a user-installed `libass.dylib` dynamically, render ASS/SSA to pixels, and blend those pixels into the Compose/Skia video frame. Embedded ASS/SSA tracks are extracted with the built-in Matroska reader.

For remote MKV files with cues, the reader first uses HTTP byte ranges to load the header, cue table, and subtitle clusters around the current playback time, then completes the full subtitle track in the background and caches it per `(URI, stream)`. A user-installed `ffmpeg` process is only an optional fallback for unsupported Matroska layouts.

To require the in-process libVLC backend, set one of:

```shell
COMPOSE_MEDIA_PLAYER_FALLBACK_BACKEND=libvlc
COMPOSE_MEDIA_PLAYER_MACOS_FALLBACK_BACKEND=libvlc
COMPOSE_MEDIA_PLAYER_WINDOWS_FALLBACK_BACKEND=libvlc
COMPOSE_MEDIA_PLAYER_LINUX_FALLBACK_BACKEND=libvlc
```

```shell
-Dcomposemediaplayer.fallbackBackend=libvlc
-Dcomposemediaplayer.macos.fallbackBackend=libvlc
-Dcomposemediaplayer.windows.fallbackBackend=libvlc
-Dcomposemediaplayer.linux.fallbackBackend=libvlc
```

To point at a specific libVLC install, set:

```shell
COMPOSE_MEDIA_PLAYER_LIBVLC=/path/to/libvlc
COMPOSE_MEDIA_PLAYER_LIBVLC_PLUGINS=/path/to/plugins
```

or the equivalent JVM system properties:

```shell
-Dcomposemediaplayer.libvlc=/path/to/libvlc
-Dcomposemediaplayer.libvlc.plugins=/path/to/plugins
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

If native playback or compatible libVLC is not available, the external HLS fallback can use user-installed VLC or `ffmpeg`. In `auto` mode, VLC is preferred and `ffmpeg` is only the last fallback. The ffmpeg backend transcodes the first video stream and selected audio stream to H.264/AAC HLS in a bounded temporary buffer, serves that HLS source from `127.0.0.1`, and hands it to the platform player. macOS prefers `h264_videotoolbox` when available; Windows and Linux use `libx264` or another available H.264 encoder.

Selecting an embedded text subtitle track such as ASS/SSA/SRT/WebVTT restarts the HLS fallback with the selected backend. VLC uses its `soverlay` transcode path; the ffmpeg backend uses the `subtitles` filter, so ASS/SSA is rendered by libass and burned into the temporary HLS video. When forcing ffmpeg, the configured `ffmpeg` must expose that filter. On Homebrew, the regular `ffmpeg` formula may not include libass; install `ffmpeg-full` or point `COMPOSE_MEDIA_PLAYER_FFMPEG` to another ffmpeg build that exposes the `subtitles` filter. Homebrew's keg-only `ffmpeg-full` is auto-detected for embedded subtitle rendering when it is installed.

To use VLC as the external HLS fallback, set one of:

```shell
COMPOSE_MEDIA_PLAYER_HLS_FALLBACK_BACKEND=vlc
COMPOSE_MEDIA_PLAYER_MACOS_HLS_FALLBACK_BACKEND=vlc
COMPOSE_MEDIA_PLAYER_WINDOWS_HLS_FALLBACK_BACKEND=vlc
COMPOSE_MEDIA_PLAYER_LINUX_HLS_FALLBACK_BACKEND=vlc
```

```shell
-Dcomposemediaplayer.hlsFallbackBackend=vlc
-Dcomposemediaplayer.macos.hlsFallbackBackend=vlc
-Dcomposemediaplayer.windows.hlsFallbackBackend=vlc
-Dcomposemediaplayer.linux.hlsFallbackBackend=vlc
```

That backend uses VLC's `soverlay` transcode path for embedded subtitle rendering. To choose a specific executable, set one of:

```shell
COMPOSE_MEDIA_PLAYER_VLC=/path/to/VLC
```

```shell
-Dcomposemediaplayer.vlc=/path/to/VLC
-Dcomposemediaplayer.macos.vlc=/path/to/VLC
```

The library does **not** bundle, compile-link, or redistribute `ffmpeg`, VLC, libVLC, libass, mpv, IINA, `vlcj`, JNA, or GPL code. VLC/libVLC/libass remain user-installed runtime components; if you bundle or redistribute external binaries yourself, their license terms remain your responsibility.

To disable the external HLS fallback, set one of:

```shell
COMPOSE_MEDIA_PLAYER_HLS_FALLBACK=false
COMPOSE_MEDIA_PLAYER_MACOS_FFMPEG_FALLBACK=false
```

```shell
-Dcomposemediaplayer.hlsFallback=false
-Dcomposemediaplayer.macos.ffmpegFallback=false
```
