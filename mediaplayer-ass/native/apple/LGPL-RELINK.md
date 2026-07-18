# Rebuilding and replacing FriBidi on Apple platforms

FriBidi 1.0.16 is the only LGPL component in the Apple ASS payload. libass is
ISC licensed. HarfBuzz, FreeType and libunibreak use permissive licenses.

## Exact corresponding source

The build consumes the unmodified archive:

```text
src/androidMain/native/ass/corresponding-source/fribidi-1.0.16-source.tar.xz
SHA-256 9f1af7a082dcf280b0a97c5617af9dfc73db2fef93ca45290f34a3a6702ad09a
upstream commit 68162babff4f39c4e2dc164a5e825af93bda9983
```

`build.sh` verifies this digest before compiling anything.

## macOS JVM replacement

Run the reproducible build documented in `BUILD.md`, then replace only
`libkmediafribidi.dylib` beside `libcomposemediaplayer_ass.dylib`. A compatible
replacement must:

- export the public FriBidi 1.0.16 ABI needed by libass;
- retain filename and install name `libkmediafribidi.dylib`;
- match the renderer architecture and the macOS 14 deployment target.

The renderer links it through `@loader_path`, so no libass or KMediaPlayer
rebuild is required for a compatible macOS replacement. Re-sign the final
application bundle after replacement.

## iOS and iOS Simulator replacement

The Maven/KLIB payload uses two distinct archives:

```text
libcomposemediaplayer_ass.a
libkmediafribidi.a
```

Rebuild `libkmediafribidi.a` from the source above, place it in the matching
target output directory, then rebuild the `composemediaplayer-ass` KLIB and the
final application. The renderer archive intentionally contains unresolved
`fribidi_*` references; FriBidi is not merged into it.

Static linking has obligations beyond shipping this library's source.
An organization distributing a final iOS application must independently
satisfy LGPL-2.1 section 6, including giving recipients the notices,
corresponding FriBidi source and a practical way to relink the application with
a modified compatible FriBidi. Depending on how the application is delivered,
that normally means retaining and offering the required relinkable application
object code and build/signing instructions. Publishing this KMP artifact alone
does not automatically satisfy the final application's distribution
obligations.

If those obligations do not fit an application's distribution model, do not
register `AssSubtitleExtension()` in the iOS source set. The base player keeps
its dialogue-level subtitle fallback.
