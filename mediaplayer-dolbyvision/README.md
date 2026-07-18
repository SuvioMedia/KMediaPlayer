# Compose Media Player Dolby Vision

Optional KMediaPlayer 2.0 component for bounded Dolby Vision Profile 7 to Profile 8.1 RPU
conversion. It pins the MIT `libdovi` implementation to an immutable dovi_tool commit and never
decodes or re-encodes picture data.

```kotlin
implementation("io.github.shusek:composemediaplayer-dolbyvision:<version>")
```

Install the extension and keep the default `AUTO` policy for compatibility mapping only when it is
needed:

```kotlin
val options = VideoPlaybackOptions(
    dolbyVisionPolicy = DolbyVisionPolicy.AUTO,
    extensions = listOf(DolbyVisionExtension()),
)
```

For Profile 7, `AUTO` keeps a confirmed native P7 path first. If P7 is unavailable but the active
platform confirms a Profile 8 decoder/display path, it prepares P8.1; otherwise it falls back to a
verified HDR10 base layer or color-managed SDR. Use
`DolbyVisionPolicy.CONVERT_PROFILE_7_TO_8_1` only when conversion itself is an application
requirement. The extension never takes ownership of an `AUTO` source unless the platform planner
explicitly authorizes that mapping.

JVM, Android, iOS, and browser Wasm use the same pinned Rust implementation. Browser builds package
`composemediaplayer_libdovi.wasm`; `LibDoviWasmConfiguration.moduleUrl` may be changed when an app
serves assets from a custom base path.

The platform playback bridges support unencrypted flat MP4, fMP4 HLS VOD, and Matroska on JVM,
Android, iOS, and browser Wasm. The common Matroska bridge accepts Profile 7 HEVC with AAC, Opus,
AC-3, or E-AC-3 audio and lazily remuxes bounded fragments to CMAF. Unsupported, compressed, or
encrypted Matroska tracks fail closed instead of being dropped. JVM can additionally fall back to
an installed FFmpeg for compatible inputs outside that common subset. The browser bridge requires
a supported Dolby Vision Media Source MIME type.

Android queries MediaCodec Profile 7 and Profile 8 support separately. Apple paths first observe a
working native AVFoundation route; macOS automatically enables the bridge for probed P7 containers
that AVFoundation cannot open directly, including Matroska. A browser cannot reliably probe a
source profile before native `<video>`/MSE selection, so browser P7 conversion currently requires
the explicit conversion policy.

Both selected HLS media playlists and ordinary master playlists are accepted. A master is parsed
strictly, filtered to a declared Profile 7 variant, and rewritten to local bounded resources.
Android, iOS, and JVM proxy every referenced VOD audio/subtitle rendition so the platform HLS
player retains its track model. Browser Wasm keeps the declared default external fMP4 audio
rendition in a second synchronized Media Source buffer; additional alternate-language selection is
left to native/browser HLS playback outside the conversion bridge. Live/low-latency playlists,
session/media encryption, nested masters, explicit non-Profile-7 variants, malformed ranges, and
elementary external audio on the browser path fail closed with typed HLS failure reasons.
The Wasm bridge reads each fMP4 track clock, maps raw presentation timestamps onto the HLS timeline
with independent video/audio `SourceBuffer.timestampOffset` values, and renegotiates that mapping
after seek and `EXT-X-DISCONTINUITY`. A seek without `EXT-X-INDEPENDENT-SEGMENTS` scans backwards
through at most 32 bounded fragments for a verified sync sample; it fails closed if none is found.

Every bridge is bounded and preserves sample timing and supported audio tracks while rewriting only
the HEVC RPU/signaling needed for Profile 8.1. It restarts from a keyframe on seek. Live HLS, DRM,
non-Profile-7 input, missing RPU data, out-of-order fragments, and oversized payloads fail closed.
Flat MP4 and Matroska enforce one configurable sample-count budget across all tracks, assemble
initialization and media fragments linearly, and preserve multiple audio tracks as alternatives.
Profile 7 FEL conversion deliberately discards the enhancement layer and FEL mapping, and reports
that loss. `VideoColorPipelineStatus` exposes the planned and confirmed output `DolbyVisionInfo`
plus `DolbyVisionProfileMapping`; Profile 8 with an HDR10-compatible base layer is Profile 8.1.
The core advertises conversion only while an installed `DolbyVisionExtension` confirms
both the pinned converter and the current platform playback bridge.

Custom HLS transports implement `DolbyVisionMediaDataSource.read(uri, byteRange, maximumBytes)`.
They must enforce `maximumBytes` while receiving data, not after materializing an unbounded body,
and return exactly the requested byte range. The bundled Android/JVM streams, iOS data delegate,
and Wasm `ReadableStream` all cancel oversized responses during transfer.
