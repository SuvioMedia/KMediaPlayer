# Rebuilding the Android ASS backend

This is a pinned, patch-based build of upstream projects. It does not require a
KMediaPlayer-owned fork.

## Pinned inputs

| Project | Revision |
|---|---|
| `peerless2012/libass-android` | `07b447fabceee6a0811e58652a468bb4b5429163` |
| `peerless2012/libass-cmake` | `d3f00a43ca66e42a2c34de964b1a7dbbfa9dbc8b` |
| libass 0.17.5 | `4a05d8127f525943ebf45fdc6497c9e665947f0d` |
| FriBidi 1.0.16 | `68162babff4f39c4e2dc164a5e825af93bda9983` |
| fontconfig 2.16.2 | `daa175d234b8a362eedd4c18c33537cc2d19cd98` |
| FreeType 2.13.3 | `42608f77f20749dd6ddc9e0536788eaad70ea4b5` |
| HarfBuzz 11.3.3 | `c3fcbffa651cea70400552f2a8bd695ad11023c1` |
| Expat 2.7.1 | `f9a3eeb3e09fbea04b1c451ffc422ab2f1e45744` |
| libunibreak 6.1 | `304585d8e2d63187507368d612c3d5fff1486368` |

The release toolchain was Android NDK `29.0.14206865`, Clang 21, CMake 4.1.2,
Ninja 1.12.1, API 23, and Release flags `-O3 -DNDEBUG -g0`.

## Prepare sources

Clone `peerless2012/libass-android` at the pinned revision with recursive
submodules. Check out every nested project at the revision above, including
libass 0.17.5, then apply `libass-cmake-android.patch` from the
`libass-cmake` directory with whitespace-tolerant patching:

```sh
patch -l -p1 < <KMEDIAPLAYER>/mediaplayer-ass/src/androidMain/native/ass/libass-cmake-android.patch
```

Bootstrap the Autotools projects without running a host configure. These paths
are relative to `lib_ass/src/main/cpp/libass-cmake`:

```sh
(cd src/unibreak && NOCONFIGURE=1 ./autogen.sh)
(cd src/fribidi && NOCONFIGURE=1 ./autogen.sh)
(cd src/fontconfig && NOCONFIGURE=1 ./autogen.sh)
(cd src/expat/expat && ./buildconf.sh)
(cd src/ass && ./autogen.sh)
```

## Build one ABI

Repeat the following for `arm64-v8a` and `armeabi-v7a`.
Replace bracketed paths and `ABI`; always use fresh build directories.

First build the PIC static dependency set:

```sh
<CMAKE> \
  -S <LIBASS_CMAKE> -B <STATIC_BUILD> -G Ninja \
  -DCMAKE_TOOLCHAIN_FILE=<NDK>/build/cmake/android.toolchain.cmake \
  -DANDROID_ABI=ABI -DANDROID_PLATFORM=android-23 \
  -DCMAKE_BUILD_TYPE=Release \
  '-DCMAKE_C_FLAGS_RELEASE=-O3 -DNDEBUG -g0' \
  '-DCMAKE_CXX_FLAGS_RELEASE=-O3 -DNDEBUG -g0' \
  -DCMAKE_MAKE_PROGRAM=<NINJA> \
  -DCMAKE_NM=<NDK>/toolchains/llvm/prebuilt/<HOST_TAG>/bin/llvm-nm \
  -DCMAKE_OBJDUMP=<NDK>/toolchains/llvm/prebuilt/<HOST_TAG>/bin/llvm-objdump

<CMAKE> --build <STATIC_BUILD> -j 8
```

Link the private libass core and independently replaceable FriBidi DSO:

```sh
<CMAKE> \
  -S <KMEDIAPLAYER>/mediaplayer-ass/src/androidMain/native/ass/packager \
  -B <PACKAGER_BUILD> -G Ninja \
  -DCMAKE_TOOLCHAIN_FILE=<NDK>/build/cmake/android.toolchain.cmake \
  -DANDROID_ABI=ABI -DANDROID_PLATFORM=android-23 \
  -DCMAKE_BUILD_TYPE=Release \
  '-DCMAKE_C_FLAGS_RELEASE=-O3 -DNDEBUG -g0' \
  -DCMAKE_MAKE_PROGRAM=<NINJA> \
  -DCORE_STATIC_ROOT=<STATIC_BUILD>

<CMAKE> --build <PACKAGER_BUILD> -j 8
```

Build the JNI bridge against that exact core:

```sh
<CMAKE> \
  -S <KMEDIAPLAYER>/mediaplayer-ass/src/androidMain/native/ass/jni \
  -B <JNI_BUILD> -G Ninja \
  -DCMAKE_TOOLCHAIN_FILE=<NDK>/build/cmake/android.toolchain.cmake \
  -DANDROID_ABI=ABI -DANDROID_PLATFORM=android-23 \
  -DCMAKE_BUILD_TYPE=Release \
  '-DCMAKE_C_FLAGS_RELEASE=-O3 -DNDEBUG -g0' \
  -DCMAKE_MAKE_PROGRAM=<NINJA> \
  -DLIBASS_INCLUDE_DIR=<STATIC_BUILD>/include \
  -DLIBASS_SHARED_LIBRARY=<PACKAGER_BUILD>/libkmediaasscore.so

<CMAKE> --build <JNI_BUILD> --target kmediaass -j 8
```

Strip only with the matching NDK `llvm-strip --strip-unneeded`. Package the
three outputs as `libkmediaass.so`, `libkmediaasscore.so`, and
`libkmediafribidi.so` below the matching `jniLibs/ABI` directory.

## Required audit

Before publishing, verify the hashes in `CHECKSUMS.sha256`, Android API note
23, private SONAMEs, exact version needs `LIBASS_0.17.5` and
`KMEDIAFRIBIDI_1.0.16`, and 16 KiB LOAD alignment on 64-bit ABIs. The JNI DSO
must export only `JNI_OnLoad`; the core must export only the 50 upstream libass
symbols. No generic libass/FriBidi SONAME or host build path may remain.
