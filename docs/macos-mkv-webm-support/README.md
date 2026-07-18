# JVM MKV/WebM Fallback Support

On desktop JVM targets, MKV/WebM support can use native playback or optional fallback backends when the platform player cannot demux a source directly. macOS uses these fallbacks for formats AVPlayer cannot demux; Windows can use the in-process libVLC backends or external HLS fallback for Matroska/WebM; Linux keeps native GStreamer playback first and can use the same libVLC backends or retry through the external HLS fallback if native open fails. KMediaBridge fallback exists only when the application adds `composemediaplayer-kmediabridge` and registers `KMediaBridgeDesktopExtension()` in `VideoPlaybackOptions.extensions`.

The desktop backend selector may use a compatible user-installed libVLC before a container bridge. Once the HLS container fallback is selected, `auto` can use the explicitly configured KMediaBridge extension for compatible local input. A manifest-only capability check decides whether that runtime can burn subtitles without loading native code; VLC is selected as the explicit `BEST_EFFORT` subtitle fallback only when the selected platform runtime cannot do it. KMediaBridge loads dynamically linked LGPL FFmpeg libraries in-process, exposes typed track/color metadata, and never launches or searches for `ffmpeg`/`ffprobe` executables. Remote input is not advertised by the bundled runtime; an explicitly available VLC path may still handle it when the color policy permits that fallback.

KMediaBridge and VLC are never started for the same HLS fallback session. The packaged FFmpeg libraries have private `-kmb` filenames and dependency identities, so they do not satisfy or replace VLC's FFmpeg dependencies. On macOS and Windows those private identities also keep the native loader bindings separate; an external VLC executable is isolated by the operating-system process boundary. The runtime manifest is inspected first, and KMediaBridge's JNA/native libraries are loaded only after the planner has selected that backend.

The opt-in `libvlc-native-view` backend lets VLC render directly into a native desktop child window instead of copying decoded frames into Compose. It uses an NSView on macOS, an HWND on Windows, and an X11/XWayland xwindow on Linux. Compose controls render above it in a separate overlay layer, so the video path stays native while the UI stays Compose.

The Linux native-view backend is deliberately X11/XWayland today. It is hosted by an AWT `Canvas`; JAWT exposes that surface as an X11 drawable, and the supported libVLC embedding call is `libvlc_media_player_set_xwindow`. A real native Wayland path needs a separate `wl_surface` host plus a stable libVLC Wayland embedding API, so it should be added as its own backend instead of pretending the X11 path is Wayland-compatible.

The libVLC native-view backend is a container/decoder fallback, not an HDR contract. Neither codec recognition nor ownership of a native child window proves its swapchain color space or the active display mode. The 2.0 planner therefore never reports that path as HDR on Windows or Linux. HDR input uses a verified tone-mapped SDR fallback when available; `DynamicRangePolicy.REQUIRE_HDR` fails with a concrete runtime requirement instead of showing un-managed color.

## macOS HDR Matroska route

On macOS, `AUTO`, `PREFER_HDR`, and `REQUIRE_HDR` first try a color-preserving CMAF route for a
strictly validated Matroska video track. It requires HEVC Main 10, BT.2020 primaries and matrix, and
a matching PQ (`HDR10`/`HDR10+`) or HLG transfer. Ambiguous or missing stream-level color evidence
does not qualify as an HDR sample-copy route.

KMediaBridge copies compatible HEVC picture samples and the selected compatible audio stream into
bounded-memory fMP4/CMAF fragments, serves them from a loopback HLS origin, and reports the exact
handling it selected. Picture samples and in-band static/dynamic HDR metadata are not re-encoded.
AVFoundation decodes that local CMAF stream; flat playback uses the native HDR/EDR layer and
projections use the FP16 Metal renderer. A sample-copy report is not output evidence: public status
becomes HDR only after the active display, native layer/Metal route, and output readiness are confirmed.

`DynamicRangePolicy.FORCE_SDR` selects a different, explicit operation. Confirmed SDR remains a
sample-copy route, while HDR10, HDR10+, and HLG are decoded by the in-process KMediaBridge runtime,
converted to limited-range 8-bit BT.709, encoded with VideoToolbox, and streamed as bounded CMAF.
The public status retains the original HDR source, reports SDR as the decoder input, uses
`SOURCE_BRIDGE_SDR`, and confirms `APPLIED_BY_SOURCE_BRIDGE` only after the generated stream is
playable. Dolby Vision and ambiguous color descriptions are rejected by this operation.

Dolby Vision, non-HEVC video, unconfirmed color tags, non-10-bit input, and a selected subtitle that
must be burned into the picture do not qualify for the confirmed HDR sample-copy route. The platform
renderer may still perform a verified SDR conversion after decoding a compatible copied stream;
otherwise `AUTO` falls back only to a color-safe backend and `REQUIRE_HDR` returns a typed error.
Profile 7 conversion remains part of the optional Dolby Vision artifact.

On macOS, ASS/SSA subtitle rendering in the memory-callback path can load a user-installed `libass.dylib` dynamically, render ASS/SSA to pixels, and blend those pixels into the Compose/Skia video frame. This path is activated only by adding `composemediaplayer-ass` and registering `AssSubtitleExtension()`; the default player no longer discovers libass on its own. Embedded ASS/SSA tracks are extracted with the built-in Matroska reader.

For remote MKV files with cues, the built-in subtitle reader first uses HTTP byte ranges to load the header, cue table, and subtitle clusters around the current playback time, then completes the full subtitle track in the background and caches it per `(URI, stream)`. It does not spawn FFmpeg when that parser rejects a layout.

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

To require direct native libVLC rendering, use `libvlc-native-view` instead of `libvlc` in the same environment variables or JVM properties. The aliases `libvlc-native`, `libvlc-view`, `libvlc-nsview`, `libvlc-hwnd`, and `libvlc-xwindow` are also accepted on their matching desktop targets. On Linux, this path requires an X11/XWayland `DISPLAY`; `libvlc-wayland` is intentionally rejected until native Wayland embedding is implemented.

The sample app can still request direct libVLC rendering for container compatibility with:

```shell
-Dsample.app.desktopVideoBackend=LIBVLC_NATIVE
```

To point at a specific libVLC install on macOS, Windows, or Linux, set:

```shell
COMPOSE_MEDIA_PLAYER_LIBVLC=/path/to/libvlc
COMPOSE_MEDIA_PLAYER_LIBVLC_PLUGINS=/path/to/plugins
```

or the equivalent JVM system properties. The generic properties apply to every desktop JVM target; the macOS-specific aliases are kept for compatibility:

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

After `AssSubtitleExtension()` is registered, Homebrew's common `/opt/homebrew` and `/usr/local`
libass paths are auto-detected.

If native playback or compatible libVLC is not available, the HLS fallback can use a configured `KMediaBridgeDesktopExtension` for compatible local files. Its FFmpeg runtime is a dynamically linked library, not an executable. It probes and selects tracks, copies compatible compressed video/audio into CMAF or performs an explicitly requested, manifest-advertised HDR-to-SDR operation, applies backpressure, retains a bounded number of fragments, and restarts from the preceding keyframe after seek. Capabilities come exclusively from the reviewed runtime manifest; callers must not infer transcoding, tone mapping, or subtitle support from the presence of an FFmpeg codec.

On macOS, the `SUBTITLE_BURN_IN_SDR` runtime can burn an embedded text subtitle track such as ASS/SSA/SRT/WebVTT through libass, normalize the picture to limited-range 8-bit BT.709, encode it with VideoToolbox, and stream bounded CMAF to AVFoundation. Selecting a track restarts the fallback at the preceding keyframe. Burning changes picture samples and therefore never remains on the lossless HDR sample-copy route; HDR/HLG/Dolby Vision input and `REQUIRE_HDR` are rejected instead of producing un-managed color. The current Windows and Linux packaged runtimes remain `REMUX_ONLY`; `auto` uses the optional VLC `soverlay` path there when VLC is installed, otherwise selection fails with a concrete unsupported-capability error.

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

The default JVM artifact has no KMediaBridge or FFmpeg dependency. The optional `composemediaplayer-kmediabridge` adapter selects a separately versioned runtime artifact that bundles dynamically linked LGPL FFmpeg libraries together with exact corresponding source, notices, checksums, SBOM inputs, and replacement instructions. The macOS subtitle flavor also builds FreeType, the LGPL FriBidi library (without its GPL tools), HarfBuzz, libunibreak, and libass from pinned sources into the dynamically replaceable `libavfilter-kmb` boundary. It does **not** bundle an `ffmpeg` executable, GPL/nonfree FFmpeg components, VLC, libVLC, mpv, IINA, or `vlcj`. User-installed libass is available only through the separate `composemediaplayer-ass` extension for the macOS Compose/Skia canvas renderer. The optional Dolby Vision component is a separate artifact and is not implied by this fallback.

To disable the external HLS fallback, set one of:

```shell
COMPOSE_MEDIA_PLAYER_HLS_FALLBACK=false
COMPOSE_MEDIA_PLAYER_MACOS_FFMPEG_FALLBACK=false
```

```shell
-Dcomposemediaplayer.hlsFallback=false
-Dcomposemediaplayer.macos.ffmpegFallback=false
```
