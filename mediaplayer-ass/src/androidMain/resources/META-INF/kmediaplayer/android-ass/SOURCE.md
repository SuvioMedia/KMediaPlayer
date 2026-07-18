# FriBidi corresponding source and replacement

This directory accompanies the LGPL-2.1-or-later FriBidi object code in
`libkmediafribidi.so`.

- `corresponding-source/fribidi-1.0.16-source.tar.xz` is the complete source at
  commit `68162babff4f39c4e2dc164a5e825af93bda9983`.
- Its SHA-256 is
  `9f1af7a082dcf280b0a97c5617af9dfc73db2fef93ca45290f34a3a6702ad09a`.
- `corresponding-source/packager/` contains the CMake linker recipe and export
  map that create the required private filename, SONAME, and symbol version.
- `corresponding-source/LGPL-RELINK.md` describes rebuilding and replacing only
  that DSO without relinking libass, JNI, or the application object code.

The full reproducible native build description and local build patch are also
in the KMediaPlayer source distribution at
`mediaplayer-ass/src/androidMain/native/ass/`, in the source tag matching the
artifact version.
