# macOS color pipeline

The old opt-in HDR experiment has been replaced by the 2.0 color-pipeline policy. There is no
`composemediaplayer.macos.hdrMetal` feature flag.

The macOS implementation follows the production color-pipeline contract described below. Hardware
qualification results are intentionally kept outside the repository.

For a flat source that AVFoundation can open, every player owns its own native `AVPlayerLayer`.
The layer requests extended dynamic range, follows the `NSScreen` containing its host view, and is
kept separate from the transparent Compose controls window. Moving the player between monitors
re-evaluates display capabilities. Multiple players never share a global layer.

For an HDR source on an SDR display, AVFoundation may provide the system tone-mapped surface. The
Compose BGRA frame-copy fallback is only eligible when the native bridge explicitly enables its
color conversion; it is not described as HDR. libVLC remains a container/decoder fallback and is
never accepted as HDR evidence.

`VideoColorPipelineStatus` is the authority for the active source and surface. A planned HDR route
does not become `outputDynamicRange = HDR10`, `HLG`, or `DOLBY_VISION` until the native layer is
attached and `readyForDisplay`. Dolby Vision additionally requires AVPlayer HDR eligibility and a
positive VideoToolbox hardware-DV decode query on the active EDR screen. EDR headroom alone is not
treated as Dolby Vision support and is not converted into invented luminance values.

Projected video uses `AVPlayerItemVideoOutput` to request P010 or NV12 and exposes both planes as
zero-copy CoreVideo Metal textures. A per-player `rgba16Float` shader performs projection plus the
PQ/HLG → linear-light color transform. HDR10/HLG targets an extended-linear BT.2020 EDR
`CAMetalLayer`; SDR applies the controlled tone/gamut mapping path and targets extended-linear
BT.709. The renderer is not confirmed until its first command buffer completes.

If P010, Metal, EDR, the current monitor, or JAWT layer attachment fails, `AUTO`/`PREFER_HDR`
replans to the verified AVFoundation-to-BT.709 canvas path. `REQUIRE_HDR` reports the runtime reason
instead of displaying an unmanaged frame. Dolby Vision is intentionally excluded from the custom
projection renderer until a base-layer/conversion path is independently verified.
