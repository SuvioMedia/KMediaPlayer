# Apple libass payload notice

`composemediaplayer-ass` builds the Apple subtitle payload from pinned upstream
releases. The Kotlin bridge and KMediaPlayer compositor are covered by the
repository license. The bundled third-party components retain their own terms:

| Component | Version | Selected license |
| --- | ---: | --- |
| libass | 0.17.5 | ISC |
| FreeType | 2.14.3 | FreeType License (FTL) |
| HarfBuzz | 14.2.1 | Old MIT |
| libunibreak | 7.0 | zlib |
| FriBidi | 1.0.16 | LGPL-2.1-or-later |

The build does not modify FriBidi source. It gives its output a private
KMediaPlayer filename so it cannot collide with a system or application copy.
The exact source used for FriBidi is
`src/androidMain/native/ass/corresponding-source/fribidi-1.0.16-source.tar.xz`
with SHA-256
`9f1af7a082dcf280b0a97c5617af9dfc73db2fef93ca45290f34a3a6702ad09a`.

On macOS JVM, FriBidi remains the independent
`libkmediafribidi.dylib`; the renderer refers to it through `@loader_path`.
On iOS, Kotlin/Native receives the independent
`libkmediafribidi.a` archive next to the renderer archive. See
`LGPL-RELINK.md` before distributing an iOS application that enables this
extension.

No Apple Intel payload is produced. Supported Apple targets are:

- macOS arm64 for JVM applications;
- iOS arm64;
- iOS Simulator arm64.
