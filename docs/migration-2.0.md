# Migrating from 1.x to 2.0

KMediaPlayer 2.0 intentionally replaces the experimental dynamic-range API instead of retaining
aliases whose meaning could be confused with confirmed HDR output.

## Playback options

Replace `VideoOutputMode` with `DynamicRangePolicy`:

| 1.x | 2.0 |
| --- | --- |
| `AUTO` | `DynamicRangePolicy.AUTO` |
| `NATIVE_HDR` | `DynamicRangePolicy.PREFER_HDR` or `REQUIRE_HDR` |
| `COMPOSE_SDR` | `DynamicRangePolicy.FORCE_SDR` |
| `TONE_MAPPED_SDR` | `DynamicRangePolicy.FORCE_SDR` |

Replace `DolbyVisionMode` with `DolbyVisionPolicy`:

| 1.x | 2.0 |
| --- | --- |
| `AUTO` | `DolbyVisionPolicy.AUTO` |
| `PASSTHROUGH` | `DolbyVisionPolicy.REQUIRE_NATIVE` |
| `PREFER_HDR10_COMPATIBLE` | `DolbyVisionPolicy.PREFER_HDR10_BASE_LAYER` |
| `TRANSCODE_PROFILE_7_TO_8_1` | `DolbyVisionPolicy.CONVERT_PROFILE_7_TO_8_1` |

```kotlin
val state = rememberVideoPlayerState(
    playbackOptions = VideoPlaybackOptions(
        dynamicRangePolicy = DynamicRangePolicy.AUTO,
        dolbyVisionPolicy = DolbyVisionPolicy.AUTO,
    ),
)
```

`DesktopVideoBackend.LIBVLC_NATIVE` still means “render into a native child view”. It no longer
means or implies HDR.

## Optional pipeline modules

Pipeline hooks moved to the lightweight `composemediaplayer-extension-api` artifact. The default
player receives it transitively, but no longer contains KMediaBridge, FFmpeg, libass/JASSUB, or
Dolby Vision implementation code.

Register every optional component explicitly and keep the options instance stable:

```kotlin
val options = VideoPlaybackOptions(
    extensions = listOf(
        AssSubtitleExtension(),
        DolbyVisionExtension(),
        KMediaBridgeDesktopExtension(),
    ),
)
```

Blank or duplicate extension identifiers are rejected. Check `options.extensionStatuses` to
distinguish an installed and available runtime from a degraded or unavailable one. Unavailable
extensions remain diagnostic entries but contribute no capabilities and are not invoked.

Browser ASS configuration is no longer a mutable process-wide singleton. Replace assignments such
as `AssSubtitleRendererConfig.debug = true` or `queryFonts = true` with an immutable configuration
owned by the extension:

```kotlin
val assExtension =
    AssSubtitleExtension(
        config =
            AssSubtitleRendererConfig(
                debug = true,
                fontQueryMode = AssFontQueryMode.LOCAL,
            ),
    )
```

The old Boolean `queryFonts = false/true` maps to
`AssFontQueryMode.DISABLED/LOCAL`. Use `LOCAL_AND_REMOTE` only when remote font lookup and its
network/privacy behavior are explicitly intended. Empty override URL strings become `null`; the
zero-argument `AssSubtitleExtension()` remains available and uses bundled JASSUB assets.
Configuration is a snapshot rather than a live global: create and install a new extension instance
when these settings need to change. This is an intentional binary break for previously compiled
Kotlin/Native and Wasm consumers of the old extension constructor; recompile them against 2.0.

The Android KMediaBridge entry point is `KMediaBridgeAndroidExtension`; the desktop JVM entry point
is `KMediaBridgeDesktopExtension`. The latter defaults to its audited bundled runtime and can be
given a compatible external KMediaBridge runtime directory. It never treats a system
`ffmpeg`/`ffprobe` executable as the library backend.

## Capabilities and live status

The old aggregate HDR/EDR fields are gone. Read the independent
`decoderColorCapabilities`, `displayColorCapabilities`, `rendererColorCapabilities`, and
`colorConversionCapabilities` fields from `PlayerCapabilities`.

Catalogs can query HDR support before creating a player:

```kotlin
val mediaSupport = MediaSupport.query()
val showHdr10Plus =
    mediaSupport.dynamicRangeSupport(VideoDynamicRange.HDR10_PLUS) ==
        VideoDynamicRangeSupport.SUPPORTED
```

Use the tri-state result for filtering. An absent range is `UNSUPPORTED` only when the active
display capability set is known; otherwise it remains `UNKNOWN`.

Collect `VideoPlayerState.colorPipelineStatus` for the active source:

```kotlin
val status by playerState.colorPipelineStatus.collectAsState()

val confirmedHdr =
    status.outputDynamicRange != VideoDynamicRange.UNKNOWN &&
        status.outputDynamicRange != VideoDynamicRange.SDR
```

Do not use `source.isHdr`, codec names, `plannedOutputDynamicRange`, or EDR headroom as a substitute
for `outputDynamicRange`. `outputDynamicRange` stays `UNKNOWN` until the platform or controlled
renderer confirms its surface.

On Web, projected HDR is progressive: `WEB_GPU_CANVAS` remains inferred while the browser is
initializing. Current WebGPU uses a standard Display-P3 or sRGB working encoding rather than a
Rec.2100 canvas name. It becomes `RENDERER_CONFIGURED` only after the browser retains an
`rgba16float` canvas with extended tone mapping, the active display reports high dynamic range,
and the first GPU frame completes. On an SDR display the same tagged PQ/HLG source is rendered by
the controlled FP16 SDR path instead. Render an HDR badge from `outputDynamicRange`, never from
`source.dynamicRange` or `plannedOutputDynamicRange`.

On iOS, projection no longer uses an `AVPlayer` material inside SceneKit. The 2.0 renderer pulls
P010/NV12 frames through `AVPlayerItemVideoOutput` and confirms HDR10/HLG only after its FP16 Metal
command buffer completes on an EDR screen. This is an implementation detail; applications should
continue to observe the same `colorPipelineStatus` contract.

## Optional Dolby Vision conversion

Profile 7 to 8.1 conversion is no longer an experimental core flag. Add the optional artifact and
install its source extension explicitly:

```kotlin
implementation("io.github.shusek:composemediaplayer-dolbyvision:<version>")

val options = VideoPlaybackOptions(
    dolbyVisionPolicy = DolbyVisionPolicy.AUTO,
    extensions = listOf(DolbyVisionExtension()),
)
```

With the module installed, `AUTO` selects native Profile 7 first, then a confirmed Profile 7 to
Profile 8.1 bridge, then the HDR10-compatible base layer, then managed SDR. The explicit conversion
policy remains available when conversion itself is required. Read
`plannedOutputDolbyVision`/`outputDolbyVision` and
`plannedDolbyVisionProfileMapping`/`dolbyVisionProfileMapping` with the same planned-versus-active
rules as the dynamic-range fields. Profile 8 plus `hasHdr10CompatibleBaseLayer = true` identifies
Profile 8.1; the mapping object reports whether the enhancement layer and FEL mapping were lost.

The extension converts RPU data without re-encoding picture samples. Android, iOS, JVM, and browser
Wasm bridge unencrypted flat MP4, fMP4 HLS VOD, and Matroska. The common Matroska route preserves
AAC, Opus, AC-3, and E-AC-3; JVM can additionally use FFmpeg for compatible inputs outside that
subset. Live HLS and DRM are rejected. Profile 7 FEL loses its enhancement layer and FEL mapping,
and that loss is exposed in the prepared-source detail/status.

HLS conversion now accepts a selected fMP4 media playlist or a master VOD playlist. Native bridges
proxy referenced audio/subtitle renditions; browser Wasm preserves the declared default external
fMP4 audio rendition with a second bounded MSE buffer. A custom `DolbyVisionMediaDataSource` must
implement the new `maximumBytes` argument while receiving a resource and return an exact requested
byte range. This is a source-breaking 2.0 contract change made to prevent an oversized response
from being allocated before validation. The Wasm MSE bridge now maps raw fMP4 clocks at initial
append, seek, and discontinuities; non-independent seek performs a bounded backwards keyframe scan.

The status also snapshots `decoderCapabilities`, `rendererCapabilities`, and
`conversionCapabilities`. These are the exact inputs used by the active planner decision; they are
more useful for diagnostics than the platform-wide preflight values.

Dynamic metadata follows the same two-phase contract: `plannedMetadataHandling` explains what the
planner intends to do, while `metadataHandling` remains `NONE` until the output route is confirmed.
Only the latter is suitable for an active HDR10+/Dolby Vision diagnostic badge.

`REQUIRE_HDR` failures are returned as `VideoPlayerError.ColorPipelineError`, including a typed
`ColorPipelineFallbackReason` and a human-readable runtime requirement.

On Android 14+ a native HDR surface reaches `SYSTEM_REPORTED` only while the active display reports
an HDR/SDR composition ratio. Older Android versions may decode and present HDR correctly, but the
2.0 contract keeps that native result unconfirmed. Strict `REQUIRE_HDR` switches a compatible
HDR10/HLG/HDR10+ source to the controlled Media3 renderer when its GLES/EGL route is available,
and otherwise fails instead of returning a false-positive status.

## Android surfaces

`SurfaceType.Auto` now chooses `SurfaceView` for flat video. Every projection mode uses Media3's
controlled `DefaultVideoFrameProcessor`: HDR input is accepted only with GLES3 + `EXT_YUV_target`,
and PQ/HLG output additionally requires its corresponding EGL colorspace. Choose `TextureView`
explicitly only when its Compose/animation trade-off is more important than retaining the system
HDR surface path.

## Removed flags

Remove `composemediaplayer.macos.hdrMetal` and
`COMPOSE_MEDIA_PLAYER_MACOS_HDR_METAL`. The macOS path is selected by `DynamicRangePolicy` and the
runtime planner.
