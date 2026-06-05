# macOS HDR Metal POC

This POC adds an opt-in macOS rendering path for HDR-capable playback:

```bash
-Dcomposemediaplayer.macos.hdrMetal=true
```

or:

```bash
COMPOSE_MEDIA_PLAYER_MACOS_HDR_METAL=true
```

When enabled for AVFoundation-supported sources, the JVM macOS surface uses a native AWT/JAWT-hosted
`CAMetalLayer` instead of the Compose `ImageBitmap` frame path. The native player asks AVFoundation
for 10-bit `kCVPixelFormatType_420YpCbCr10BiPlanarVideoRange` frames and renders them through
CoreImage into a Metal `rgba16Float` drawable with `wantsExtendedDynamicRangeContent = true`.

The Compose overlay remains in Compose. The POC sets `compose.interop.blending=true` automatically
when HDR Metal is requested so controls can render above the Swing interop component.

Current scope:

- AVFoundation-supported files and streams only.
- Not used for libVLC memory playback.
- Not used for external ffmpeg/VLC HLS fallback, because that path currently transcodes to SDR H.264.
- Not used while the macOS libass bitmap overlay is active, because that overlay blends into the old
  BGRA/Skia frame buffer.
- Intended for validation on a Mac and display with EDR/HDR headroom.

The existing Compose Canvas renderer remains the default and fallback.
