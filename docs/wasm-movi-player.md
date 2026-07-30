# Kotlin/Wasm Movi integration

KMediaPlayer's default Wasm engine is the Kotlin/Wasm-only
[`Shusek/movi-player`](https://github.com/Shusek/movi-player) fork. Version `0.4.0-alpha.1` is linked
as a typed KLIB:

```text
io.github.shusek:movi-player:0.4.0-alpha.1
```

There is no Kotlin/JS target, npm player dependency, dynamic `engine.js` import, `JsAny` player
facade, or compatibility layer for the earlier JavaScript API.

## Dependency and runtime layout

The integration has two matching Maven dependencies:

| Artifact | Consumer role |
|---|---|
| `io.github.shusek:movi-player` | Compiled Kotlin/Wasm API and implementation |
| `io.github.shusek:movi-player-runtime-assets` | ZIP with the Emscripten `movi.js` and `movi.wasm` payload |

`mediaplayer` unpacks only the ZIP's `movi-runtime/` directory into a generated Compose resources
directory. Compose publishes that directory transitively and copies it into browser distributions.
The default runtime base URL is therefore:

```kotlin
WebMediaDependencyConfig.moviRuntimeAssetBaseUrl =
    "composeResources/io.github.shusek.mediaplayer.generated.resources/files/movi-runtime/"
```

An application may copy the same immutable files to another public path and replace the base URL
before opening the first Movi source:

```kotlin
WebMediaDependencyConfig.moviRuntimeAssetBaseUrl = "/vendor/movi-runtime/"
```

The KLIB and runtime ZIP versions must stay identical. The runtime location must not contain
credentials.

## Local composite development

The KMediaPlayer settings file can substitute both Maven artifacts from a movi-player checkout:

```shell
./gradlew :mediaplayer:wasmJsBrowserTest \
  -PmoviPlayerProjectDir=/absolute/path/to/movi-player
```

This is the required workflow while changing both repositories before the alpha artifacts are
published. A normal consumer resolves the immutable Maven coordinates and does not need the
property.

## Typed adapter boundary

`MoviPlaybackSession.web.kt` constructs `MoviPlayer` directly and maps:

- URL and browser `File` sources to `MediaSource`;
- media headers and DRM license headers to separate typed maps;
- player state, position, duration, buffered ranges, tracks, chapters, metadata, surfaces,
  diagnostics, and errors to KMediaPlayer models;
- KMediaPlayer track IDs to `TrackSelectionRequest` and confirms the returned
  `TrackSelectionOutcome`;
- Compose lifecycle disposal to idempotent `MoviPlayer.close()`.

No untyped player method lookup crosses this boundary. Error text is redacted before it reaches
application diagnostics, and request values are not logged.

Passing `null` to audio selection restores the source's initial/default track. Adaptive video track
`-1` remains KMediaPlayer's automatic-quality mode.

## Playback routing

| Request | Effective Wasm route |
|---|---|
| MP4 or WebM with default options | Browser media element managed by Movi |
| MKV, MKA, AVI, MPEG-TS, M2TS, MTS, Blob, or matching browser `File` | FFmpeg demuxer plus WebCodecs/Canvas/Web Audio |
| HLS | hls.js, with native HLS fallback where available |
| DASH | dash.js |
| Smooth Streaming (MSS) | Shaka |
| Adaptive DRM | Shaka/EME native video |
| `WebPlaybackEngine.LEGACY` with a non-adaptive source | KMediaPlayer native HTML video |
| `WebPlaybackEngine.LEGACY` with a recognized adaptive manifest | Rejected with `SourceError` |
| Clear source requiring a strict unsupported color route | Legacy or a typed color-pipeline rejection |
| DRM plus strict color, projection, or non-default texture crop | Rejected with `DrmError` |

There is no automatic fallback from Movi after a Movi session error. Browser/container support still
depends on the codecs and WebCodecs/media primitives available at runtime.

Movi may report source color metadata, but KMediaPlayer keeps decoder, surface, and output dynamic
range `UNKNOWN` until the browser can provide stronger evidence. An `isHDR` source flag alone never
confirms HDR output.

## Subtitle ownership

External SRT/VTT remains in KMediaPlayer's Compose overlay. The optional `AssSubtitleExtension`
implements movi-player's typed `EmbeddedSubtitleRenderer` interface:

- Movi supplies the codec header, canvas dimensions, and bounded font attachments once;
- raw timed ASS/SSA packets are forwarded as `SubtitlePacket`;
- the existing JASSUB renderer receives those packets and follows Movi's clock;
- no Blob export, JavaScript callback object, or second media demux pass is used.

Embedded bitmap subtitles remain owned by the Movi pipeline.

## Verification

The browser test suite covers typed adapter state mapping, seek behavior, source and header
handling, track selection, errors, redaction, subtitle ownership, packaged runtime loading, MKV
dual-audio switching, continued playback after switching, MP4, WebM, browser `File`, Blob, HLS,
DASH, HTTP Range, and servers that ignore Range. MSS routing is covered by adapter tests; its
network playback remains dependent on a compatible manifest and browser codecs.

Run the release-blocking Chrome checks with:

```shell
./gradlew :mediaplayer:wasmJsBrowserTest \
  -PmoviPlayerProjectDir=/absolute/path/to/movi-player

./gradlew :mediaplayer-ass:wasmJsBrowserTest \
  -PmoviPlayerProjectDir=/absolute/path/to/movi-player

./gradlew :sample:composeApp:wasmJsBrowserDistribution \
  -PmoviPlayerProjectDir=/absolute/path/to/movi-player
```

The distribution check must contain both:

```text
composeResources/io.github.shusek.mediaplayer.generated.resources/files/movi-runtime/movi.js
composeResources/io.github.shusek.mediaplayer.generated.resources/files/movi-runtime/movi.wasm
composeResources/io.github.shusek.mediaplayer.generated.resources/files/movi-runtime/META-INF/NOTICE
composeResources/io.github.shusek.mediaplayer.generated.resources/files/movi-runtime/META-INF/LGPL_RELINKING.md
```

Chrome and Edge are release-blocking for the initial Kotlin/Wasm integration. Firefox and Safari
remain best-effort until their required browser primitives and the same test matrix are stable.

## Release order

1. Test and publish `movi-player` and `movi-player-runtime-assets` `0.4.0-alpha.1`.
2. Confirm both immutable artifacts are available from the release repository.
3. Test KMediaPlayer without the composite-build property.
4. Publish KMediaPlayer with the pinned coordinate.

The movi-player runtime archive carries its own `NOTICE`, corresponding-source/build information,
and `LGPL_RELINKING.md`; consumers redistributing the native payload must retain the applicable
materials.
