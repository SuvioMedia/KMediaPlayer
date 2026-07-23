# Wasm MoviPlayer integration

## Runtime contract

The Wasm target imports the headless KMediaPlayer integration fork on demand from the exact,
immutable jsDelivr URL
`https://cdn.jsdelivr.net/gh/Shusek/movi-player@v0.3.5-kmp.2/cdn/engine.js`. The fork is based on
upstream's `feat/pluggable-subtitle-renderer` branch at commit
`5a8e796e1c04b61b59412580b3a2bbb3ea6ba3ac` and adds stable host integration contracts while
preserving the upstream Apache-2.0 license and attribution. KMediaPlayer does not use Movi's web
component and does not vendor, patch, install, or bundle the package. The external module import
promise is cached by URL for the page lifetime, while each media source receives a new
`MoviPlaybackSession`.

Applications can replace the URL before opening the first Movi source:

```kotlin
WebMediaDependencyConfig.moviPlayerModuleUrl = "/vendor/movi-player-0.3.5-kmp.2/engine.js"
```

The configured resource is executable code. It must be a public, compatible ES module at an
immutable URL without credentials. Cross-origin deployments must permit module CORS, and CSP must
allow the selected origin.

`DefaultVideoPlayerState`, Compose controls, overlays, fullscreen, external subtitles, events and
diagnostics remain the application-facing API. `renderingInfo.backend` identifies the active route
as either `@shusek/movi-player 0.3.5-kmp.2` or `HTML5 video (legacy)`.

| Request | Effective Wasm route |
|---|---|
| MP4, WebM, MKV, AVI, MPEG-TS, HLS, DASH or MSS with default options | Movi canvas |
| DRM adaptive source | Movi/Shaka native video |
| `WebPlaybackEngine.LEGACY` with a non-adaptive source | Native HTML video |
| `WebPlaybackEngine.LEGACY` with a recognized HLS, DASH or MSS manifest | Rejected with `SourceError` |
| Non-adaptive clear source with `REQUIRE_HDR`, `FORCE_SDR`, or non-`AUTO` Dolby Vision | Legacy |
| Adaptive clear source with a strict color policy | Rejected with `ColorPipelineError` |
| DRM plus strict color, projection, or non-default texture crop | Rejected with `DrmError` |

A Movi error is terminal for that session. It never causes an automatic legacy retry. KMediaPlayer
does not ship `hls.js`, dash.js, Shaka Player, or another adaptive-streaming implementation for the
legacy route; HLS, DASH and MSS are delegated exclusively to the externally loaded Movi module.

## Adapter boundaries

All JavaScript values and Movi API calls are confined to `MoviPlaybackSession.web.kt`. The adapter:

- accepts a URL or the browser `File` retained by FileKit;
- materializes `blob:` and `data:` sources as browser `File` objects before constructing Movi,
  avoiding Movi 0.3.5's HTTP-source path for non-HTTP URLs;
- forwards `openUri()` headers only as media headers;
- forwards `WebDrmConfiguration.licenseRequestHeaders` only as license headers;
- supplies a silent structured logger and exposes only redacted, typed adapter errors;
- maps playback state, time, duration, seek state, buffered ranges, tracks, chapters, metadata and
  errors to KMediaPlayer models;
- maps Movi's numeric track ids to source-scoped stable string ids;
- commits an audio selection and emits `TrackChanged` only after Movi returns `true`;
- destroys the player idempotently and rejects callbacks from an obsolete `mediaSessionId`.

Passing `null` to audio selection restores the track that was active/default when the session
loaded. It does not mute audio. Adaptive video track `-1` is KMediaPlayer's automatic quality mode.

Movi renders clear content to its canvas. DRM attaches `getHLSVideoElement()` to the Compose-owned
container and hides the canvas. For SDR projection, KMediaPlayer samples the hidden Movi canvas
through the existing WebGL projection renderer. Movi source color metadata is exposed, but decoder,
surface and output color evidence remain unknown; an `isHDR` flag alone never confirms HDR output.

Embedded bitmap subtitles stay inside Movi. External SRT/VTT timing remains in KMediaPlayer's
Compose overlay. When `AssSubtitleExtension()` is installed, external ASS/SSA uses JASSUB with both
engines: clear Movi playback drives JASSUB's canvas-only mode from the Movi clock, and DRM uses the
native video element. For embedded ASS/SSA, Movi mounts the extension through its pluggable
subtitle-renderer contract, passes codec headers and bounded font attachments once, then forwards
each raw Matroska packet with its PTS and duration. KMediaPlayer converts the packet to a complete
ASS `Dialogue:` event in memory and feeds it to JASSUB; no Blob export or second demux pass exists.

## Verification matrix

The following checks are part of the repository:

| Check | Gate |
|---|---|
| Routing, fail-closed DRM/color combinations, redaction and model mapping | Automated Wasm unit test |
| Audio accept/reject semantics and rapid repeated switches | Automated Wasm unit test |
| External or pluggable embedded ASS selection gives one renderer ownership; JASSUB receives raw packets, the Movi clock, canvas dimensions and bounded container fonts | Automated Wasm browser tests |
| Real CDN module with a generated MKV containing `en` and `pl` Opus tracks, real switch, continued playback and seek | Blocking Chrome test |
| Real CDN module with MP4, WebM, a browser Blob URL and a direct FileKit browser `File` | Blocking Chrome test |
| Real CDN module with HLS and DASH served from deterministic local manifests and segments | Blocking Chrome test |
| HTTP Range, a server that ignores Range, and custom media headers | Blocking Chrome test |
| Same real-package smoke test with Microsoft Edge as the Karma Chromium binary | Blocking CI job |
| Same real-package smoke test with Firefox Headless | Non-blocking CI job; result artifact retained |
| Same real-package smoke test with Safari | Non-blocking CI job; result artifact retained |
| Full `mediaplayer:wasmJsBrowserTest` repeated with `-PcomposeMediaPlayer.wasmTestPlaybackEngine=legacy` | Blocking Chrome job |

Before a release, a Shaka/EME DRM smoke test must also be recorded using credentials supplied
outside the repository and CI. No license URL, header, token, response, or derived identifier may
enter source control, CI output, diagnostics, or saved test artifacts.

### Production sample size

The production KMediaPlayer build must contain no MoviPlayer, FFmpeg, or dav1d payload and no
generated Movi chunk. Its only Movi payload is the small adapter and the external versioned URL.
The external `engine.js` entrypoint is fetched only after a Movi source is opened; the fork then
loads its demux/decoder Wasm and adaptive-engine chunks on demand for the selected route. A session
forced to `LEGACY` performs no Movi request.

The production sample built from this change contains only the pinned URL and adapter code: no
Movi, FFmpeg, dav1d or adaptive-engine payload is present. At Brotli quality 11, the main JavaScript
plus four emitted Wasm assets total 5,081,983 bytes. That is 6,945 bytes above the recorded
`0.3.5-kmp.1` adapter build and 9,786 bytes above baseline commit `b78c3076`.

Chrome and Edge are release-blocking. Firefox and Safari run the same exact-CDN-module smoke class in
dedicated `continue-on-error` jobs for the first integration release. Their reports are retained as
CI artifacts so observed failures and browser limitations are visible without blocking publication.
The browser can also be selected locally with
`-PcomposeMediaPlayer.wasmTestBrowser=chrome|firefox|safari`. Canvas PiP and full Movi
HDR/projection parity are explicitly non-blocking.

For the preceding `0.3.5-kmp.1` engine release, verification on 2026-07-20 passed the complete
Chrome suite with both `MOVI` and `LEGACY`; the `MOVI` run imported the external module from the
configured CDN. The blocking Microsoft Edge job also passed the exact-CDN-module smoke suite.
Firefox Headless passed four of five real-package scenarios, including MKV dual-audio switching and
seek; only the combined adaptive test failed when the DASH route rejected H.264/AAC representations
that the runner reported as unsupported. The hosted Safari runner did not capture Safari within
Karma's retry budget, so it executed no playback tests.

## Release and dependency policy

KMediaPlayer publishes only its adapter. MoviPlayer, FFmpeg and dav1d are not npm dependencies and
are absent from the Maven artifacts, sample bundle and KMediaPlayer hosting. The default points at
an exact release of the maintained integration fork rather than either repository's mutable
`main` branch; there is no vendoring, `patch-package`, Git dependency or binary mirror.

The fork release contains its own `NOTICE`, full third-party license texts, pinned corresponding
source/build recipes and `LGPL_RELINKING.md`. Its versioned CDN directory, Git tag, source snapshot
and attached npm-compatible tarball are produced from the same gated build. Publication is blocked
if those materials or the reproducible distribution checks fail.

Anyone replacing the default with a self-hosted module is responsible for the security, licensing,
notices and corresponding-source obligations of the bytes served from that URL.
