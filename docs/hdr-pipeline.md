# HDR, HLG and Dolby Vision pipeline

KMediaPlayer 2.0 uses one pure planner on every platform. `AUTO` evaluates, in order, a confirmed
system-native surface, a controlled HDR renderer, a controlled HDR-to-SDR renderer, and finally an
unsupported result. `REQUIRE_HDR` never selects an SDR route. `FORCE_SDR` never relies on an
un-managed HDR canvas.

## What the status proves

`VideoColorPipelineStatus` deliberately separates:

- compressed source color information and static/dynamic metadata;
- the selected decoder and its compatible dynamic ranges;
- the decoder, renderer, and optional conversion capabilities used for this exact decision;
- the display currently containing the player;
- the active native or controlled surface;
- the planned and confirmed output dynamic range;
- the planned and confirmed Dolby Vision output profile and any P7 to P8.1 source mapping;
- planned metadata handling and separately confirmed passthrough, renderer application, conversion,
  or dropping;
- the verification level and exact fallback reason.

`plannedOutputDynamicRange` is a routing decision. `outputDynamicRange` is populated only at
`RENDERER_CONFIGURED` or `SYSTEM_REPORTED` verification. A screenshot is not HDR evidence.
Likewise, `plannedMetadataHandling` is only a routing decision. `metadataHandling` stays `NONE`
until that output route is confirmed.

For Dolby Vision Profile 7, `AUTO` uses a confirmed native Profile 7 decoder first. With the
optional Dolby Vision artifact installed, a platform that rejects P7 but confirms P8 may rewrite
the timestamped RPU to Profile 8.1 without re-encoding the base-layer picture. The status keeps the
original P7 source, reports P8.1 as planned until output verification, and exposes MEL/FEL loss in
`DolbyVisionProfileMapping`. HDR10 base-layer and SDR routes remain later fallbacks.

When a controlled renderer applies HDR10+ metadata, the physical surface is an HDR10-compatible PQ
surface; the status therefore reports `outputDynamicRange = HDR10` together with
`metadataHandling = APPLIED_BY_RENDERER`. Native metadata passthrough is the only route that reports
an HDR10+ output. This lets renderer-applied HDR10+ work on a confirmed HDR10 display without
pretending that the display accepts dynamic metadata.

Timestamped ST 2094-40 application is wired into the Android and iOS controlled renderers, the
macOS Metal renderer, the Windows Media Foundation/D3D11 route, and the Linux GStreamer/Vulkan
route. Each path fails closed when the current frame has no valid payload: it removes HDR10+
application from the active capabilities, replans to the static HDR10-compatible signal, and
reports `metadataHandling = DROPPED`. Linux requires GStreamer 1.30+ for `GstVideoHDRMeta`; 1.28.5
remains sufficient for static HDR. Web does not expose frame-associated ST 2094-40 to this pipeline
and therefore never advertises controlled HDR10+ application.

## Shared color processing

The reference math is expressed as linear BT.2020 floating point. It includes PQ/HLG transfer,
BT.2390/BT.2446 tone mapping, ICtCp-based gamut compression, ST 2094-40 curve application, and
dither before reducing precision. CPU reference tests cover limited/full range and 1,000/4,000-nit
inputs. The ST 2094-40 path converts normalized MaxSCL into a BT.2020 luminance estimate, applies
Bezier anchors relative to the post-knee segment in linear luminance, and adapts the reference OOTF
to the measured display peak. Android projections use Media3's color pipeline: HDR input requires GLES3 and
`EXT_YUV_target`, intermediates are RGBA16F, and the final surface is either explicit PQ/HLG
RGBA_1010102 or a Media3 tone-mapped SDR output. Web projection first attempts a WebGPU
`texture_external` path only for a frame whose browser-exposed transfer is tagged PQ or HLG. The
WebGPU `PredefinedColorSpace` contract does not expose Rec.2100 canvas names: the browser converts
the tagged video into an extended Display-P3 working encoding and preserves values outside
`[0, 1]`. A controlled HDR route therefore requires an `rgba16float` canvas that retains
`toneMapping: extended`, a currently high-dynamic-range display, and a completed first GPU
submission. This confirms an HDR element, not a raw PQ/HLG display transport.

If that HDR canvas is unavailable, WebGPU imports the same tagged source into the extended sRGB
working encoding, decodes it to linear light, applies BT.2390 plus the common ICtCp gamut LUT, and
writes dithered BT.709/sRGB into an `rgba16float` standard-tone-mapping canvas. HLG is first
color-managed by the browser according to its tagged transfer before the shared extended-linear
tone-map stage. WebGL is used only for projection of video the browser has already delivered as
managed SDR; its ambiguous video-upload conversion is never advertised as HDR-to-SDR processing.
Without WebGPU there is no confirmed controlled HDR-to-SDR canvas route.

iOS projections pull P010 or NV12 frames from `AVPlayerItemVideoOutput`, keep them as CoreVideo
Metal plane textures, and render into an `rgba16Float` `CAMetalLayer`. HDR10/HLG is converted to
linear BT.2020 EDR; a non-HDR output applies the controlled transfer, BT.2390/BT.2446 and gamut
mapping path before the frame reaches an extended-linear BT.709 layer. The route is confirmed only
after the first Metal command buffer completes successfully.

Windows keeps P010 decoder surfaces on the D3D11 device and samples them in the controlled
projection/color shader. HDR output is an Advanced Color flip-model swapchain configured with an
explicit DXGI color space and, for PQ, HDR10 static metadata plus triangular-PDF dither before the
10-bit target. Controlled SDR uses the same decoded surface and shader, applies BT.2390 followed by
BT.2020-to-BT.709 conversion, and presents an 8-bit dithered G22/BT.709 swapchain. Linux projection
uses an optional Vulkan library so a missing Vulkan loader cannot prevent the core SDR backend from
loading. It requires linear P010 DMA-BUF, maps that buffer, uploads its Y/UV planes, and presents
through an exact 10-bit PQ or HLG Wayland WSI color space with the same triangular-PDF dither. This
path is deliberately not described as zero-copy DMA-BUF import.

The optional desktop JVM `KMediaBridgeDesktopExtension` loads a reviewed, dynamically linked LGPL
FFmpeg runtime as a library; the default player has no KMediaBridge/FFmpeg dependency, and the
extension never searches for or launches `ffmpeg`/`ffprobe` executables. Its controlled native
pipeline accepts only explicitly tagged PQ/HLG BT.2020 input, performs the HDR-to-SDR transform,
encodes AVC with limited-range BT.709 tags, and reports `TONE_MAP_TO_SDR` separately from sample
copy. Dolby Vision profiles without a profile-aware mapper are rejected instead of being fed
through a generic HEVC decoder. Ambiguous 10-bit, wide-gamut, or completely unprobed input is
rejected rather than guessed to be SDR.

## Current runtime gates

| Platform | Flat video | Projection | Unavailable HDR behavior |
| --- | --- | --- | --- |
| Android | Media3 + `SurfaceView`; display, selected format, codec-output static HDR metadata, and real decoder name are read from the active runtime. The decoder remains the dataspace producer. On API 28+, a read-only JNI bridge checks `ANativeWindow_getBuffersDataSpace()` after the first frame and accepts only an exact PQ/HLG match as `RENDERER_CONFIGURED`. Android 14+'s active HDR/SDR composition ratio is independent `SYSTEM_REPORTED` evidence, latched only for the current decoded output signal/source/surface/display. Dolby Vision retains the decoder's vendor signaling | Media3 `DefaultVideoFrameProcessor` for projections; linear projection shader, RGBA16F intermediates, PQ/HLG RGBA_1010102 output after EGL feature detection, or Media3 HDR-to-SDR tone mapping | `AUTO`/`PREFER_HDR` use only a renderer or optional source bridge that can confirm its SDR output when native HDR cannot be confirmed. Flat-video capabilities do not advertise the projection-only GL graph. `REQUIRE_HDR` reports the missing public confirmation instead of claiming inferred HDR; a controlled projection route is `RENDERER_CONFIGURED` only after its first processed output frame |
| iOS | `AVPlayerLayer`; active `UIScreen`; output waits for `readyForDisplay`; HDR additionally requires AVPlayer's eligibility signal, while DV also requires its explicit available-mode bit; both capability changes are observed. `FORCE_SDR` disables EDR on the layer | `AVPlayerItemVideoOutput` P010/NV12 → CoreVideo Metal textures → one `rgba16Float` projection/color shader; linear BT.2020 EDR for HDR10/HLG or controlled SDR tone mapping | `AUTO`/`PREFER_HDR` replan to controlled SDR after an EDR, output or Metal failure; `REQUIRE_HDR` returns a typed error. A route is `RENDERER_CONFIGURED` only after the first completed Metal frame |
| macOS JVM | per-player `AVPlayerLayer`/EDR layer on the current `NSScreen`; native DV requires AVPlayer HDR eligibility plus VideoToolbox hardware DV decode and `readyForDisplay`. With the optional configured KMediaBridge extension, strictly tagged HEVC Main 10 HDR10/HDR10+/HLG Matroska is copied to bounded CMAF/fMP4 before entering the same AVFoundation route | `AVPlayerItemVideoOutput` P010/NV12 → zero-copy CoreVideo Metal planes → per-player `rgba16Float` projection layer; linear BT.2020 EDR for HDR10/HLG or controlled SDR. For local Matroska, `FORCE_SDR` can use KMediaBridge's in-process HDR-to-BT.709 source bridge | monitor changes trigger a two-phase replan before output is reconfirmed; incompatible/ambiguous Matroska, Metal/P010/EDR failure, or unavailable source conversion fails closed, while `REQUIRE_HDR` returns a typed error |
| Windows JVM | Media Foundation P010 GPU decode → D3D11 shader → Advanced Color flip-model swapchain; active `IDXGIOutput6`, color space, and first `Present` must agree | the same P010 shader handles flat/stereo/equirectangular/fisheye/EAC projection and controlled BT.2390 HDR-to-SDR output through a G22/BT.709 swapchain | `FORCE_SDR` and a non-strict HDR failure use the native controlled SDR route for Media Foundation-readable sources; a container fallback can still use an explicitly configured bridge, while `REQUIRE_HDR` reports the exact DXGI/D3D failure |
| Linux JVM | GStreamer `waylandsink` in the video member of a JBR `WLToolkit` subsurface pair after GStreamer 1.28.5+, `color-management-v1`, compositor, and output negotiation pass | optional Vulkan renderer in the video subtree; linear P010 DMA-BUF is mapped and uploaded, projected in linear BT.2020, then presented to an exact 10-bit PQ/HLG WSI colorspace. A bounded triple-buffered `wl_shm` Compose scene occupies the sibling overlay subsurface | any JBR, GStreamer, Wayland, DMA-BUF, Vulkan, renderer-library, overlay, or output negotiation failure selects the safe fallback; explicit `FORCE_SDR` uses a manifest-confirmed KMediaBridge bridge when available; `REQUIRE_HDR` reports the missing requirement |
| Web | native `<video>` remains browser-managed and therefore inferred, not claimed as confirmed HDR; explicit `FORCE_SDR` for flat HDR switches display to the controlled SDR canvas | progressive WebGPU `importExternalTexture` projection for tagged PQ/HLG; HDR requires an FP16 extended-tone-mapping canvas plus a high-dynamic-range display, while controlled HDR-to-SDR uses an FP16 standard sRGB canvas and WebGL handles managed SDR projection only | HDR or controlled SDR is `RENDERER_CONFIGURED` only after configuration readback and the first completed GPU submission. HDR-canvas failure recreates the route as controlled SDR WebGPU; loss of WebGPU fails closed, and `REQUIRE_HDR` returns a typed error |

The Android processor, Apple Metal renderer, Windows presenter, Linux Wayland/Vulkan paths and
WebGPU renderer still need their complete physical HDR/SDR/DV device matrix and independent output
verification. The optional libdovi artifact contains the pinned converter and bounded platform
bridges for unencrypted MP4/fMP4 HLS VOD and Matroska. The common Matroska route preserves AAC,
Opus, AC-3, and E-AC-3 on every target; JVM additionally supports a bounded FFmpeg spool for
compatible inputs outside that subset. Live HLS and DRM remain rejected, and platform-specific
container limits are reported instead of guessed. The repository must not publish a stable 2.0
artifact while any hardware-matrix row or remaining overlay/accessibility release row is incomplete.

Android API 30 devices that expose neither native HLG nor the GLES YUV-target path can install the
optional KMediaBridge Android extension. For local, unencrypted VOD it decodes HDR10/HDR10+/HLG,
applies the explicit transfer, emits limited-range BT.709 SDR as bounded fMP4, and reports
`SOURCE_BRIDGE_SDR` only after the replacement decoder renders its first frame. Missing runtime,
unsupported input, live media and DRM still fail closed. The current ARM64 hardware observations
prove the functional route, not sustained 4K60 or release-matrix performance.

The HLS VOD bridge accepts selected media playlists and strictly parsed masters. Android, iOS, and
JVM expose referenced VOD audio/subtitle rendition playlists through the same local bridge. Wasm
uses a second MSE buffer for the declared default external fMP4 audio rendition after codec feature
detection. All four transports enforce their declared byte cap during network reception; malformed,
oversized, live, encrypted, nested-master, or unsafe variant layouts are rejected rather than
partially rewritten. The Wasm route parses the selected video/audio fMP4 clocks and maps their raw
timestamps to the playlist timeline on initial append, seek, and every discontinuity. Non-independent
HLS seek scans backwards through a bounded 32-segment window for a real sync sample and fails closed
instead of appending a dependent frame as a random-access point.

Linux uses two JBR `WLSubSurface` objects per player. Video is stacked above the parent Compose
surface and the overlay sibling is stacked above the complete video subtree. `ImageComposeScene`
renders premultiplied BGRA into at most three `wl_shm` buffers; resize waits for compositor buffer
release instead of growing memory. Both native children have empty input regions, while a
transparent copy in the normal Compose tree retains hit testing and accessibility semantics.
Failure to create or upload that native overlay switches back to the transparent top-level overlay
without claiming the two-subsurface route. JBR 25.0.3 plus Weston smoke tests cover construction,
resize and upload; GNOME/KDE input and assistive-technology verification remains in the hardware
matrix.

The Apple display probes deliberately do not advertise Dolby Vision from EDR headroom alone. iOS
requires the explicit AVPlayer Dolby Vision mode bit. macOS requires the active screen's EDR,
AVPlayer HDR eligibility, VideoToolbox hardware Dolby Vision decode and a ready per-player layer.
Codec recognition in the asset is still source metadata, not output proof.

## Release gate

The release matrix must cover SDR, HDR10, HDR10+, HLG, and relevant Dolby Vision profiles across
flat/projection, fullscreen, monitor changes, multiple players, adaptive SDR↔HDR, subtitles,
overlays, 4K60, A/V sync, and bounded memory. Native color-space/dataspace APIs plus an independent
output channel—system/compositor/HDMI diagnostics, a display HDR/DV indicator, or an externally
recorded test sequence—are required for HDR confirmation. Capture-card evidence is optional and a
colorimeter is not required; consequently the release does not claim calibrated panel luminance or
gamut accuracy. No intermediate preview is published as 2.0. The breaking branch rejects a
misleading `1.x` publication version. Hardware qualification remains part of the release process,
but device inventories and test evidence are intentionally kept outside the source repository and
are not used as a Gradle publication gate.
