# Rebuilding and replacing FriBidi on Windows and Linux

FriBidi 1.0.16 is the LGPL-2.1-or-later component in the bundled desktop ASS
runtime. libass itself is ISC licensed. The exact unmodified FriBidi source is:

```text
src/androidMain/native/ass/corresponding-source/fribidi-1.0.16-source.tar.xz
SHA-256 9f1af7a082dcf280b0a97c5617af9dfc73db2fef93ca45290f34a3a6702ad09a
upstream commit 68162babff4f39c4e2dc164a5e825af93bda9983
```

## Windows replacement

Run `build-windows.ps1` with the supplied pinned vcpkg port material. Replace
the FriBidi DLL in the matching `windows-x86-64` or `windows-aarch64`
directory with an ABI-compatible build, keep its original payload filename,
then update that file's SHA-256 in `runtime.properties`. The loader extracts
the complete directory and loads libass with sibling dependency discovery, so
no application or libass relink is needed for a compatible replacement.

## Linux replacement

Run `build-linux.sh` for the matching architecture. Replace
`libkmediafribidi.so.0` beside `libass.so.9` with an ABI-compatible FriBidi
1.0.16 build retaining SONAME `libkmediafribidi.so.0`, then update its digest
in `runtime.properties`. libass uses an `$ORIGIN` runpath and therefore loads
the replacement from the same extracted private directory.

After either replacement, rebuild and re-sign the final application package
as required by its distribution format.
