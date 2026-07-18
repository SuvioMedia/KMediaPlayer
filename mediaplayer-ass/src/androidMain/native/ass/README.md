# Android ASS native backend

KMediaPlayer keeps Media3 as the player and demuxer. Only raw Matroska ASS/SSA
packets and external ASS/SSA scripts are routed through this bundled libass
backend. There is no Media3 fork and no additional Maven repository.

The runtime chain is deliberately private and collision-safe:

```text
libkmediaass.so
  -> libkmediaasscore.so  (LIBASS_0.17.5)
       -> libkmediafribidi.so  (KMEDIAFRIBIDI_1.0.16)
```

`libass` performs shaping and rasterization on the CPU. The JNI layer combines
the returned `ASS_Image` list into one cropped, premultiplied RGBA8 buffer. The
Android view currently uploads that buffer with GLES 2.0. The native API is not
tied to GLES, so a future Vulkan uploader can consume the same buffer without
changing or forking libass.

## Layout

- `jni/` contains the private JNI bridge and independently testable RGBA
  compositor.
- `packager/` links private-SONAME libass and a separately replaceable FriBidi
  shared object.
- `corresponding-source/` contains the exact FriBidi 1.0.16 source archive.
- `BUILD.md` records source pins and the reproducible Android build.
- `LGPL-RELINK.md` explains how to rebuild and replace only FriBidi.
- `CHECKSUMS.sha256` identifies every binary shipped in the AAR.

The Android artifact also carries the license texts, notices, FriBidi source,
and relink material below `META-INF/kmediaplayer/android-ass/`.

## Release properties

- Android API 23; NDK r29.
- `arm64-v8a` and `armeabi-v7a`.
- 16 KiB LOAD alignment for the arm64 ABI.
- No `libc++_shared.so`, generic `libass.so`, generic `libfribidi.so`, GLES,
  Vulkan, or Android Bitmap dependency in the native libraries.
- The arm64 output of all three layers was reproduced byte-for-byte in a clean
  build.
- The compositor passed AddressSanitizer and UndefinedBehaviorSanitizer smoke
  tests. A physical Android device smoke remains a release gate.

See [BUILD.md](BUILD.md) for the full source and toolchain provenance.
