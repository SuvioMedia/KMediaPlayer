# FFmpeg/WebAssembly source and relinking

The `io.github.shusek:kmedia-wasm-engine-runtime-assets` Maven artifact contains
`kmedia-wasm-runtime/kmedia-wasm.wasm` and its Emscripten loader. The WebAssembly binary combines
the open native C/C++ shim with FFmpeg libraries configured under LGPL-2.1-or-later. GPL and
non-free FFmpeg components are not enabled. The proprietary Kotlin/Wasm player KLIB is a separate
artifact and is not linked into this native WebAssembly binary.

The corresponding source and relinking materials are in the kmedia-wasm-engine Git source snapshot
whose version matches the Maven runtime artifact:

- `wasm/` — the open native C/C++ shim and JavaScript I/O library;
- `docker/Dockerfile` — pinned FFmpeg and dav1d source versions plus the Emscripten environment;
- `docker/build-ffmpeg.sh` — the complete configure, compile, export, and link commands;
- `compose.yaml` — the reproducible native-build entrypoint;
- `package.json` — the `build:wasm` command that invokes that container build.

From the corresponding source tree, run:

```shell
npm ci
npm run build:wasm
```

The native build produces `dist/wasm/movi.js` and `dist/wasm/movi.wasm`. To prepare a new
Kotlin/Wasm runtime release, copy those outputs to `cdn/chunks/kmedia-wasm.js` and
`cdn/chunks/kmedia-wasm.wasm`, update their entries in `cdn/SHA256SUMS`, and run:

```shell
./gradlew :runtime-assets:runtimeArchive
```

The Gradle task verifies the pinned bytes before creating the Maven ZIP. The release tag and source
archive are published at:

`https://github.com/Shusek/kmedia-wasm-engine/releases`

FFmpeg source is available from `https://github.com/FFmpeg/FFmpeg`; its licensing information is at
`https://ffmpeg.org/legal.html`. Nothing in this document changes the terms of
LGPL-2.1-or-later or any other included license.
