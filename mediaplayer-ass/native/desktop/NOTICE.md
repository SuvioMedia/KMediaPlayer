# Windows and Linux libass payload notice

`composemediaplayer-ass` publishes 64-bit libass runtimes inside its JVM
artifact. Applications do not need to install libass separately.

| Component | Windows | Linux | Selected license |
| --- | ---: | ---: | --- |
| libass | 0.17.4 | 0.17.5 | ISC |
| FreeType | vcpkg-pinned | 2.14.3 | FreeType License (FTL) |
| HarfBuzz | vcpkg-pinned | 14.2.1 | Old MIT |
| libunibreak | not bundled | 7.0 | zlib |
| FriBidi | 1.0.16 | 1.0.16 | LGPL-2.1-or-later |

The Windows payload is built by the pinned official vcpkg commit
`d015e31e90838a4c9dfa3eed45979bc70d9357fc` (the commit referenced by the
official `2026.05.25` tag). Its generated legal directory
contains the copyright file for every shipped vcpkg runtime package. The
Linux payload builds permissively licensed dependencies into libass and keeps
FriBidi as the independent, replaceable `libkmediafribidi.so.0`.

The exact FriBidi source used by both builds is
`src/androidMain/native/ass/corresponding-source/fribidi-1.0.16-source.tar.xz`
with SHA-256
`9f1af7a082dcf280b0a97c5617af9dfc73db2fef93ca45290f34a3a6702ad09a`.
The Windows vcpkg port files and its cross-build patch are included beside the
build scripts.

Supported JVM payloads are:

- Windows x86_64 and ARM64;
- Linux x86_64 and ARM64.
