# Reproducible Apple ASS native build

The build requires Xcode, Meson, Ninja, pkg-config and a JDK 25 installation
for the macOS JNI shim. NASM 2.10 or newer is additionally required for the
Intel macOS target. It downloads only the pinned permissively licensed
dependencies. FriBidi is built from the exact corresponding-source archive
committed in this repository.

Pinned downloads:

| Component | URL version | SHA-256 |
| --- | ---: | --- |
| libass | 0.17.5 | `2dca25c0e0c837ddf00b52011b3f82cac1e4ddd3ad018227806b0c2288864acc` |
| FreeType | 2.14.3 | `36bc4f1cc413335368ee656c42afca65c5a3987e8768cc28cf11ba775e785a5f` |
| HarfBuzz | 14.2.1 | `a54a5d8e9380a41fbb762ce367bcbf7704792dfca0d93f1bbca86c5a57902e0e` |
| libunibreak | 7.0 | `8c9a6e121736cd0d5c890ae3ae96f3f4010a19aa040f1dbded833a62a87717d3` |
| FriBidi | 1.0.16 exact source | `9f1af7a082dcf280b0a97c5617af9dfc73db2fef93ca45290f34a3a6702ad09a` |

Build one target:

```bash
mediaplayer-ass/native/apple/build.sh macos-arm64 /tmp/kmedia-ass/macos-arm64
mediaplayer-ass/native/apple/build.sh ios-arm64 /tmp/kmedia-ass/ios-arm64
mediaplayer-ass/native/apple/build.sh ios-simulator-arm64 /tmp/kmedia-ass/ios-simulator-arm64
```

Gradle invokes the same script through:

```bash
./gradlew \
  :mediaplayer-ass:buildMacosArm64AppleAss \
  :mediaplayer-ass:buildIosArm64AppleAss \
  :mediaplayer-ass:buildIosSimulatorArm64AppleAss
```

The build uses CoreText, complex HarfBuzz shaping, linear CPU rasterization from
libass and the shared bounded RGBA compositor. Fontconfig is not included.
Every output directory contains a SHA-256 manifest.

Inspect macOS linkage:

```bash
otool -L /tmp/kmedia-ass/macos-arm64/libcomposemediaplayer_ass.dylib
otool -D /tmp/kmedia-ass/macos-arm64/libkmediafribidi.dylib
```

The only non-system dylib dependency must be
`@loader_path/libkmediafribidi.dylib`.
