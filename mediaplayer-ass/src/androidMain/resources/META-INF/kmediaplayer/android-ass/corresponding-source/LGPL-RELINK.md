# Rebuilding and replacing FriBidi

`libkmediaasscore.so` dynamically links `libkmediafribidi.so`. No FriBidi
object code is copied into the core or JNI bridge, so a modified compatible
FriBidi can replace only that DSO.

The exact complete source used here is
`fribidi-1.0.16-source.tar.xz`, SHA-256
`9f1af7a082dcf280b0a97c5617af9dfc73db2fef93ca45290f34a3a6702ad09a`,
from commit `68162babff4f39c4e2dc164a5e825af93bda9983`. The same archive, LGPL text,
packager, and this recipe are included in the Android artifact under
`META-INF/kmediaplayer/android-ass/`.

To rebuild a replacement:

1. Unpack the source archive, modify it if desired, and run
   `NOCONFIGURE=1 ./autogen.sh`.
2. Put that tree at `src/fribidi` in the pinned `libass-cmake` checkout
   described by `BUILD.md`, apply `libass-cmake-android.patch`, and configure
   the static build exactly as in `BUILD.md`.
3. Build only the PIC archive with
   `<CMAKE> --build <STATIC_BUILD> --target ep_fribidi -j 8`.
4. Wrap the archive without linking the libass core:

```sh
<CMAKE> \
  -S <KMEDIAPLAYER>/mediaplayer-ass/src/androidMain/native/ass/packager \
  -B <FRIBIDI_SHARED_BUILD> -G Ninja \
  -DCMAKE_TOOLCHAIN_FILE=<NDK>/build/cmake/android.toolchain.cmake \
  -DANDROID_ABI=ABI -DANDROID_PLATFORM=android-23 \
  -DCMAKE_BUILD_TYPE=Release \
  '-DCMAKE_C_FLAGS_RELEASE=-O3 -DNDEBUG -g0' \
  -DCMAKE_MAKE_PROGRAM=<NINJA> \
  -DBUILD_KMEDIA_ASS_CORE=OFF \
  -DFRIBIDI_STATIC_ARCHIVE=<STATIC_BUILD>/lib/libfribidi.a

<CMAKE> --build <FRIBIDI_SHARED_BUILD> --target kmediafribidi -j 8
```

The replacement must retain filename and SONAME `libkmediafribidi.so`, the
compatible public `fribidi_*` API, and version node
`KMEDIAFRIBIDI_1.0.16`. `packager/fribidi.exports.map` supplies that dynamic
interface. Replace the matching ABI's `libkmediafribidi.so` and rebuild/sign
the application package; neither core nor JNI needs to be relinked.
