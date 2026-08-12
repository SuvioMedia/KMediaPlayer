# Non-UI engine parity

Matrix version: **2026-08-12 / Kotlin API 0.4.0-alpha.3 / runtime ABI 4**

This is the release gate for replacing the historical TypeScript engine with the Kotlin/Wasm
engine. “Implemented” is not enough to remove the old engine: every required row must also have a
repeatable acceptance gate.

Status:

- **PASS** — implemented and covered by an automated fixture/contract gate;
- **PROVISIONAL** — implemented, but one of the required real-browser or media-fixture gates is
  still missing;
- **EXCLUDED** — intentionally outside the headless library;
- **BLOCKED** — must not be performed until every required row is PASS.

## API, sources and runtime

| Capability | Kotlin API / implementation | Acceptance gate | Status |
|---|---|---|---|
| URL + headers + MIME | `MediaSource.Url` | `WasmMediaPlayerTest.progressiveSourcesAlwaysUseTheDemuxerWhileAdaptiveAndDrmUseBrowserEngines` | PASS |
| Browser file | `MediaSource.BrowserFile`, `FileSource` | `SourceAdapterTest.revokedBrowserFileNotifiesTheHost` | PASS |
| Custom source | `MediaSource.Adapter`, `SourceAdapter` key/position/seek/fork/close/stats | `WasmMediaPlayerTest.configurationAndCompositeSourceValidateSafetyLimits`, source tests | PASS |
| AES-256-GCM HTTP source | `MediaSource.Encrypted`, `EncryptedHttpSource` | local fake ECDH/HKDF/AES-GCM in `EncryptedSourceTest` | PASS |
| DRM/EME source | `MediaSource.Drm`, per-key-system Shaka license servers and isolated headers | contract/redaction tests; no real EME license-session fixture | PROVISIONAL |
| Split/composite media | `MediaSource.Composite` with audio, subtitles and premuxed qualities | `NativeFixtureTest.compositeSourceSwitchesExternalAudioSubtitlesAndPremuxedQuality` | PASS |
| Runtime ABI | `KMEDIA_WASM_RUNTIME_ABI_VERSION`, `kmedia-wasm-runtime.json` | typed mismatch in `NativeFixtureTest.httpCacheAndRuntimeAbiFailuresAreTyped` | PASS |
| Secret-safe diagnostics | redacted `toString`, error sanitization, separate media/license maps | `WasmMediaPlayerTest.publicErrorsAndSourcesDoNotExposeRequestCredentials` | PASS |

## Demux and decode

| Capability | Kotlin API / implementation | Acceptance gate | Status |
|---|---|---|---|
| Progressive/local/blob/data through FFmpeg | `DemuxedPlaybackBackend`, `requiresDemuxedPlayback` | route test plus real MP4/MKV/WebM/MOV/TS/AVI fixtures; AVI uses MPEG-4 Part 2 B-frames and asserts strictly increasing decoded PTS | PASS |
| Container seek/replay | `Demuxer.seek`, flush and restart; serialized per-module Asyncify I/O ownership across immediate source replacement | real container matrix seek and `NativeFixtureTest.sharedRuntimeKeepsDemuxerIoIsolatedAcrossImmediateReplacement` | PASS |
| H.264, AV1, VP8/9, MPEG-2/4 software video | FFmpeg/dav1d decoder path | real container matrix and software AV1/H.264 decode | PASS |
| VC-1 family software video | FFmpeg `wmv3` Simple/Main and `vc1` Advanced Profile decoders; direct software routing | real WMV3/WMAv2 ASF and 1080p VC-1 Matroska decode fixtures | PASS |
| HEVC Open-GOP and Rext software video | CRA/RASL flags, safe packet handling, FFmpeg fallback | `hevc-open-gop.mp4`, `hevc-rext-flac.mkv` | PASS |
| AVC/HEVC/AV1 WebCodecs strings | avcC/hvcC/AV1 configuration parsing | real AV1 AUTO load; deterministic HEVC hardware rejection/recovery still browser-dependent | PROVISIONAL |
| Hardware-first video recovery | hardware preference, metadata retry, codec fallbacks, permanent per-track software fallback | unit/fixture recovery paths; no deterministic browser fixture for every HEVC failure mode | PROVISIONAL |
| Per-stream mixed backends | independent `DecoderBackend` for video/audio | AV1 AUTO + forced software Opus fixture | PASS |
| AC-3/E-AC-3/DTS/TrueHD/MLP/Opus/FLAC/WMA 2/WMA Pro/multichannel policy | forced software audio selection | six-track software-audio fixture plus real WMV3/WMAv2, mono WMA Pro and VC-1/WMA Pro 5.1 ASF fixtures | PASS |
| Batch planar PCM and downmix | native batch ABI, 80–180 ms adaptive batches and downmix | batch, continuous-timeline and stereo signal assertions | PASS |
| No silent active audio | typed decoder exhaustion errors | every forced family must produce PCM in fixture test | PASS |
| Decoder flush and skip-frame | native flush, `AVDISCARD_NONREF`, seek reset | seek/replay and backpressure contract tests | PASS |
| Software RGBA and planar YUV | bounded/scaled RGBA fallback plus direct WebGL2 YUV420/422/444/NV12/NV21 texture path | native planes/PTS/linesize, direct-GPU-path and renderer pixel tests | PASS |
| 10-bit/unsupported software pixel formats | swscale RGBA fallback | HEVC Rext 10-bit/4:4:4 fixture | PASS |
| Optimized software runtime | FFmpeg/dav1d/Signalsmith built with `-O3`, LTO and Wasm SIMD128 | runtime feature manifest, SIMD opcode inspection and browser fixture suite | PASS |

## Clock, audio and rendering

| Capability | Kotlin API / implementation | Acceptance gate | Status |
|---|---|---|---|
| Audio-master media clock | strict running-AudioContext master, continuous PCM timeline, 350 ms rebuffer threshold and autoplay wall-clock fallback | continuous scheduling test and browser suite; dedicated long drift signal test is missing | PROVISIONAL |
| Adaptive backpressure | resolution/FPS/queue/audio-priority limits, audio-clock late-frame rejection, `AVDISCARD_NONREF`, correct presented-vs-dropped accounting and ~8 ms cooperative slices | queue/skip tests plus full software AVI playback/EOF fixture | PASS |
| Decode execution isolation | single-threaded Asyncify FFmpeg ABI with cooperative slices; Web Audio rendering is isolated and pre-scheduled, but FFmpeg is not in a DedicatedWorker | architecture assertion and long manual VC-1/AVI QoE probes | PROVISIONAL |
| Rotation, crop and fit | shared media pipeline | pixel rotation/crop test and KMedia mapping | PASS |
| Projection modes | equirectangular, half, VR180, fisheye, SBS, little-planet | all projection shader modes in `MediaPipelineTest` | PASS |
| HDR/P3/PQ/HLG and SDR tone map | WebGL2 shader and `ColorPipelineDiagnostics` | HDR pixel delta and output-color-space assertions | PASS |
| Pitch-preserving speed | Signalsmith Stretch | synthetic 440 Hz tests at 0.75x and 1.5x | PASS |
| Stable volume and perceptual volume | compressor graph and squared gain curve | audio-graph assertions | PASS |
| Audio output routing | `listAudioOutputs`, `setAudioOutput`, `AudioContext.setSinkId` | capability/error contract only; CI cannot confirm a physical sink switch | PROVISIONAL |
| Audio-only mode | video discard and adaptive audio-only selection | real software-decoder disable/restore fixture passes; adaptive audio-only rendition fixture is still missing | PROVISIONAL |
| Snapshot | `snapshot()` for canvas/native video | typed contract and renderer tests; native-video pixel capture fixture missing | PROVISIONAL |
| Isolated thumbnail/poster | `thumbnail(position)`, independent demuxer and rotation | real rotated thumbnail fixture | PASS |
| Cover art | attachment extraction | real attached-picture MP3 fixture | PASS |

## HTTP, cache and adaptive streaming

| Capability | Kotlin API / implementation | Acceptance gate | Status |
|---|---|---|---|
| Range + prefetch + LRU | `HttpSource` | repeated range/cache fixture | PASS |
| No-Range bounded linear mode | sliding window, explicit seek restriction | 3 MiB fake CDN with 1 MiB limit | PASS |
| Small complete-body cache | full-body fast path and random access | HTTP cache test | PASS |
| Abort on seek/close | grouped `AbortController` | close/seek source contracts | PASS |
| CDN retry and throughput | bounded retry/backoff and `NetworkStats` | transient 503→206 fixture | PASS |
| Offline recovery | retry path and later network success | transient transport test; explicit browser offline/online event gate missing | PROVISIONAL |
| Optional SharedArrayBuffer fast path | not required for correctness | plain-buffer path is release baseline | EXCLUDED |
| Shaka HLS/DASH | browser adaptive backend | real HLS and DASH manifests | PASS |
| hls.js/dash.js fallback | secondary adaptive engines | implementation present; forced Shaka-failure browser gates missing | PROVISIONAL |
| MSS through Shaka | adaptive route recognition | route contract only; safe public MSS fixture missing | PROVISIONAL |
| HLS FFmpeg fallback | VOD, master, split A/V/text, BYTERANGE and variant sources | finite TS, master and single-file fMP4 fixtures | PASS |
| DASH FFmpeg fallback | single-file/SegmentBase, representation choice; reject SegmentTemplate | real SegmentBase and negative SegmentTemplate fixtures | PASS |
| Confirmed track/quality selection | backend acknowledgements and request generations | race, rejected-selection/no-mutation and composite selection tests | PASS |
| Live edge and DVR | `LivePlaybackWindow`, `seekToLive()` | real EVENT HLS browser fixture | PASS |
| File access revocation | `PlaybackEvent.FileAccessRevoked` | revoked `File` fixture | PASS |

## Subtitles and metadata

| Capability | Kotlin API / implementation | Acceptance gate | Status |
|---|---|---|---|
| Embedded SRT and ASS/SSA | native subtitle ABI | real Matroska SRT+ASS prefetch | PASS |
| External VTT/SRT/TTML | Kotlin parsers and composite sources | parser timing tests and real external VTT selection | PASS |
| Segmented adaptive text | HLS/DASH text-track publication and cue collection | HLS split-text source construction; end-to-end segmented cue timing gate missing | PROVISIONAL |
| PGS/DVB/DVD bitmap subtitles | native RGBA bitmap ABI, position, timing and overlay | implementation present; redistributable real bitmap fixture missing | PROVISIONAL |
| Embedded fonts/libass host renderer | `EmbeddedSubtitleRenderer`, attachment list and delay | contract present; valid attached-font rendering fixture missing | PROVISIONAL |
| Delay, seek, flush and cue ownership | native/external cue reset and bitmap release | text delay/seek paths; bitmap lifetime awaits bitmap fixture | PROVISIONAL |
| Full cue prefetch | `prefetchSubtitleCues`, `subtitleCues` | native SRT/ASS and external parser tests | PASS |
| Metadata/title/chapters | `MediaInfo.metadata`, `Chapter` | synthetic titled two-chapter Matroska | PASS |
| Content-Disposition filename | `SourceAdapter.contentDispositionFilename` | fake CDN source test | PASS |

## Optional engine services and diagnostics

| Capability | Kotlin API / implementation | Acceptance gate | Status |
|---|---|---|---|
| LCEVC adapter | lazy `LcevcAdapter`/`LcevcSession` | mock attach/enable/close test | PASS |
| Host PiP/fullscreen surface | typed `PlayerSurface.Canvas/NativeVideo` | public state/close contract | PASS |
| Cache/network/decoder/renderer stats | `RenderingDiagnostics`, `CacheStats`, `NetworkStats` | source and renderer statistics tests | PASS |
| Presented/dropped/FPS/queue health | media pipeline counters plus scheduled-audio, underrun, timestamp-fix and maximum A/V drift diagnostics | browser renderer/PCM tests and KMedia mapping; long QoE sampling gate missing | PROVISIONAL |

## KMediaPlayer consumer

| Capability | Integration | Acceptance gate | Status |
|---|---|---|---|
| Kotlin/Wasm KLIB + runtime ZIP | Gradle dependency, runtime-asset unpacking and composite development build | composite and published-artifact browser suites | PASS |
| MIME-aware source opening | `openSource(MediaSourceSpec, …)`; `openUri` shortcut | real HLS/DASH consumer test passes MIME through `MediaSourceSpec` | PASS |
| Decoder preference | `WebDecoderPreference.AUTO/SOFTWARE` | typed mapping test plus real forced-software consumer session | PASS |
| Projection/HDR/crop/rotation | real `applyProjection()` → `WasmMediaPlayer` controls | KMedia mapping test plus engine projection/HDR pixel gates | PASS |
| Adaptive quality API | primary `AdaptiveQuality*`, deprecated `HlsQuality*` wrappers | confirmed primary-API selection test and request-generation race tests | PASS |
| Wasm-only advanced controls | `WebMediaAdvancedControls` | real browser consumer controls/surface/diagnostics/lifecycle test | PASS |
| Source vs output HDR diagnostics | mapped `WasmMediaPlayer` color diagnostics | consumer assertion keeps HDR10 source distinct from unverified/unknown output | PASS |

## Browser and release gates

| Gate | Required result | Current state | Status |
|---|---|---|---|
| Chromium | full `:player:wasmJsBrowserTest` | 51/51 tests pass locally | PASS |
| Firefox | full browser suite | CI job configured; local browser could not be captured reliably | PROVISIONAL |
| WebKit/Safari | full browser suite | macOS CI job configured; not yet executed here | PROVISIONAL |
| Long seek/replay/track-switch leak test | bounded heap and no retained frames/AudioNodes | not implemented | PROVISIONAL |
| Published-artifact consumer | KLIB + runtime ZIP, no composite source substitution | publication succeeds and KMedia runs 225/225 browser tests from the local Maven artifacts | PASS |
| Remove active TypeScript engine | all required rows PASS | parity is not yet 100% | BLOCKED |

## Intentional exclusions

The web component, controls, menus, skins, gestures, shortcuts, ambient mode, Document PiP
controls, framework wrappers, preference persistence, resume dialog and transcript/timeline UI are
**EXCLUDED**. The Kotlin library exposes the media state and frames needed by a host UI, but does
not implement that UI.

Suvio is not a test consumer and is not modified by this work.
