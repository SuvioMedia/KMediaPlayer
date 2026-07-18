# iOS best-effort readiness

KMediaPlayer 2.0 supports iPhone and iPad on a software-verified, non-blocking best-effort basis.
The project does not own physical iOS hardware and does not claim physical HDR, Dolby Vision
interoperability, peak luminance, sustained 4K60 performance or Apple/Dolby certification.

## What is enforced

- The library, optional Dolby Vision module and sample consumer compile for device `iosArm64`.
- The player and Dolby Vision suites execute on iOS Simulator; the frozen baseline contains 265
  passing tests and no failures.
- The sample links complete frameworks for both device arm64 and Simulator arm64, catching native
  symbol and cinterop integration failures.
- The embedded iOS Metal shader must remain byte-identical to the macOS production shader, compile
  with the iPhone Simulator Metal compiler and pass the shared macOS Metal CPU/GPU reference suite
  for PQ, HLG, BT.2390, BT.2020 primaries, limited/full range and HDR10+.
- The dynamic-range layer bridge uses `wantsExtendedDynamicRangeContent` on iOS 16.2–25 and
  `preferredDynamicRange` plus content headroom on iOS 26+, with configuration readback tests.
- EDR headroom and general HDR eligibility never invent a format. HDR10, HLG and Dolby Vision are
  exposed only when the corresponding AVPlayer mode bit is present.

## Runtime contract

- Flat video uses `AVPlayerLayer`. HDR status additionally requires the active screen's EDR
  capability, AVPlayer eligibility, a format-specific AVPlayer mode, a compatible decoded source,
  retained layer dynamic-range configuration and `readyForDisplay`.
- Projection uses P010/NV12 from `AVPlayerItemVideoOutput`, an FP16 Metal layer and the shared color
  shader. `RENDERER_CONFIGURED` is emitted only after the layer retains the requested range and the
  first Metal command completes.
- `AUTO` may use native HDR, controlled HDR or controlled SDR according to the capabilities Apple
  reports at runtime. `REQUIRE_HDR` fails with a typed error if confirmation is missing.
- Dolby Vision remains system-managed for supported flat sources. Profile 7 to 8.1 conversion is
  provided by the optional LGPL-compatible module, but its successful conversion does not prove
  that a particular device displays Dolby Vision.
- No source is reported as active HDR merely because its transfer function, codec or metadata says
  HDR. Unknown hardware behavior remains `UNKNOWN`, falls back to verified SDR where possible, or
  fails under a strict policy.

## Release policy

`apple-iphone-xdr` and `apple-ipad-xdr` are intentionally not physical hardware-matrix rows and do
not block KMediaPlayer 2.0. This is a cost/availability exception, not a test waiver disguised as a
pass. Device reports from users can strengthen the baseline later, but absence of such reports does
not reopen the implementation unless a reproducible defect or false positive is found. Hardware
qualification records are intentionally kept outside the repository.
