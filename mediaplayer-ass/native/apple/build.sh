#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MODULE_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
COMMON_DIR="$MODULE_DIR/native/common"
ANDROID_FRIBIDI_SOURCE="$MODULE_DIR/src/androidMain/native/ass/corresponding-source/fribidi-1.0.16-source.tar.xz"

TARGET="${1:-}"
OUTPUT_DIR="${2:-}"
if [[ -z "$TARGET" || -z "$OUTPUT_DIR" ]]; then
    echo "Usage: $0 <macos-arm64|ios-arm64|ios-simulator-arm64> <output-dir>" >&2
    exit 64
fi

case "$TARGET" in
    macos-arm64)
        SDK="macosx"
        ARCH="arm64"
        CPU_FAMILY="aarch64"
        TRIPLE="arm64-apple-macos14.0"
        HOST_TRIPLE="aarch64-apple-darwin"
        PLATFORM="macos"
        ;;
    ios-arm64)
        SDK="iphoneos"
        ARCH="arm64"
        CPU_FAMILY="aarch64"
        TRIPLE="arm64-apple-ios16.2"
        HOST_TRIPLE="aarch64-apple-darwin"
        PLATFORM="ios"
        ;;
    ios-simulator-arm64)
        SDK="iphonesimulator"
        ARCH="arm64"
        CPU_FAMILY="aarch64"
        TRIPLE="arm64-apple-ios16.2-simulator"
        HOST_TRIPLE="aarch64-apple-darwin"
        PLATFORM="ios"
        ;;
    *)
        echo "Unsupported Apple ASS target: $TARGET" >&2
        exit 64
        ;;
esac

for tool in curl meson ninja pkg-config shasum tar make xcrun; do
    command -v "$tool" >/dev/null || {
        echo "Required build tool is missing: $tool" >&2
        exit 69
    }
done

JOBS="${KMEDIA_ASS_BUILD_JOBS:-}"
if [[ -z "$JOBS" ]]; then
    JOBS="$(sysctl -n hw.logicalcpu 2>/dev/null || true)"
fi
if [[ ! "$JOBS" =~ ^[1-9][0-9]*$ ]]; then
    JOBS=4
fi

SDK_PATH="$(xcrun --sdk "$SDK" --show-sdk-path)"
CC="$(xcrun --sdk "$SDK" --find clang)"
CXX="$(xcrun --sdk "$SDK" --find clang++)"
AR="$(xcrun --sdk "$SDK" --find ar)"
RANLIB="$(xcrun --sdk "$SDK" --find ranlib)"
STRIP="$(xcrun --sdk "$SDK" --find strip)"
LIBTOOL="$(xcrun --sdk "$SDK" --find libtool)"
PKG_CONFIG="$(command -v pkg-config)"

BUILD_ROOT="${KMEDIA_ASS_APPLE_BUILD_ROOT:-$MODULE_DIR/build/apple-ass-native}"
DOWNLOAD_DIR="$BUILD_ROOT/downloads"
SOURCE_DIR="$BUILD_ROOT/sources"
TARGET_ROOT="$BUILD_ROOT/$TARGET"
PREFIX="$TARGET_ROOT/prefix"
CROSS_FILE="$TARGET_ROOT/apple-cross.ini"
mkdir -p "$DOWNLOAD_DIR" "$SOURCE_DIR" "$TARGET_ROOT" "$OUTPUT_DIR"

download_verified() {
    local filename="$1"
    local url="$2"
    local expected_sha="$3"
    local destination="$DOWNLOAD_DIR/$filename"
    if [[ -f "$destination" ]] &&
        [[ "$(shasum -a 256 "$destination" | awk '{print $1}')" == "$expected_sha" ]]; then
        printf '%s\n' "$destination"
        return
    fi

    local temporary="$destination.tmp.$$"
    curl --fail --location --retry 3 --output "$temporary" "$url"
    local actual_sha
    actual_sha="$(shasum -a 256 "$temporary" | awk '{print $1}')"
    if [[ "$actual_sha" != "$expected_sha" ]]; then
        rm -f "$temporary"
        echo "SHA-256 mismatch for $filename: expected $expected_sha, got $actual_sha" >&2
        exit 65
    fi
    mv -f "$temporary" "$destination"
    printf '%s\n' "$destination"
}

extract_once() {
    local archive="$1"
    local directory_name="$2"
    local destination="$SOURCE_DIR/$directory_name"
    if [[ -f "$destination/.kmedia-source-ready" ]]; then
        printf '%s\n' "$destination"
        return
    fi

    local temporary="$SOURCE_DIR/.$directory_name.tmp.$$"
    rm -rf "$temporary"
    mkdir -p "$temporary"
    tar -xf "$archive" --strip-components=1 -C "$temporary"
    touch "$temporary/.kmedia-source-ready"
    if ! mv "$temporary" "$destination" 2>/dev/null; then
        rm -rf "$temporary"
    fi
    [[ -f "$destination/.kmedia-source-ready" ]] || {
        echo "Could not prepare source tree $directory_name" >&2
        exit 73
    }
    printf '%s\n' "$destination"
}

LIBASS_ARCHIVE="$(download_verified \
    libass-0.17.5.tar.xz \
    https://github.com/libass/libass/releases/download/0.17.5/libass-0.17.5.tar.xz \
    2dca25c0e0c837ddf00b52011b3f82cac1e4ddd3ad018227806b0c2288864acc)"
FREETYPE_ARCHIVE="$(download_verified \
    freetype-2.14.3.tar.xz \
    https://downloads.sourceforge.net/project/freetype/freetype2/2.14.3/freetype-2.14.3.tar.xz \
    36bc4f1cc413335368ee656c42afca65c5a3987e8768cc28cf11ba775e785a5f)"
HARFBUZZ_ARCHIVE="$(download_verified \
    harfbuzz-14.2.1.tar.xz \
    https://github.com/harfbuzz/harfbuzz/releases/download/14.2.1/harfbuzz-14.2.1.tar.xz \
    a54a5d8e9380a41fbb762ce367bcbf7704792dfca0d93f1bbca86c5a57902e0e)"
UNIBREAK_ARCHIVE="$(download_verified \
    libunibreak-7.0.tar.gz \
    https://github.com/adah1972/libunibreak/releases/download/libunibreak_7_0/libunibreak-7.0.tar.gz \
    8c9a6e121736cd0d5c890ae3ae96f3f4010a19aa040f1dbded833a62a87717d3)"

[[ -f "$ANDROID_FRIBIDI_SOURCE" ]] || {
    echo "The exact FriBidi corresponding-source archive is missing." >&2
    exit 66
}
FRIBIDI_SHA="$(shasum -a 256 "$ANDROID_FRIBIDI_SOURCE" | awk '{print $1}')"
[[ "$FRIBIDI_SHA" == "9f1af7a082dcf280b0a97c5617af9dfc73db2fef93ca45290f34a3a6702ad09a" ]] || {
    echo "The pinned FriBidi corresponding-source archive has changed." >&2
    exit 65
}

LIBASS_SOURCE="$(extract_once "$LIBASS_ARCHIVE" libass-0.17.5)"
FREETYPE_SOURCE="$(extract_once "$FREETYPE_ARCHIVE" freetype-2.14.3)"
HARFBUZZ_SOURCE="$(extract_once "$HARFBUZZ_ARCHIVE" harfbuzz-14.2.1)"
UNIBREAK_SOURCE="$(extract_once "$UNIBREAK_ARCHIVE" libunibreak-7.0)"
FRIBIDI_SOURCE="$(extract_once "$ANDROID_FRIBIDI_SOURCE" fribidi-1.0.16-kmedia)"

COMMON_FLAGS=("-target" "$TRIPLE" "-isysroot" "$SDK_PATH")
cat >"$CROSS_FILE" <<EOF
[binaries]
c = ['$CC']
cpp = ['$CXX']
ar = ['$AR']
strip = ['$STRIP']
pkg-config = ['$PKG_CONFIG']

[host_machine]
system = 'darwin'
cpu_family = '$CPU_FAMILY'
cpu = '$ARCH'
endian = 'little'

[built-in options]
c_args = ['-target', '$TRIPLE', '-isysroot', '$SDK_PATH']
cpp_args = ['-target', '$TRIPLE', '-isysroot', '$SDK_PATH']
c_link_args = ['-target', '$TRIPLE', '-isysroot', '$SDK_PATH']
cpp_link_args = ['-target', '$TRIPLE', '-isysroot', '$SDK_PATH']
EOF

rm -rf \
    "$TARGET_ROOT/freetype" \
    "$TARGET_ROOT/harfbuzz" \
    "$TARGET_ROOT/fribidi" \
    "$TARGET_ROOT/libunibreak" \
    "$TARGET_ROOT/libass" \
    "$PREFIX"
mkdir -p "$PREFIX"

meson setup "$TARGET_ROOT/freetype" "$FREETYPE_SOURCE" \
    --cross-file "$CROSS_FILE" \
    --prefix "$PREFIX" \
    --default-library static \
    --buildtype release \
    -Dbrotli=disabled \
    -Dbzip2=disabled \
    -Dharfbuzz=disabled \
    -Dpng=disabled \
    -Dzlib=disabled \
    -Dtests=disabled
meson compile -C "$TARGET_ROOT/freetype"
meson install -C "$TARGET_ROOT/freetype"

meson setup "$TARGET_ROOT/harfbuzz" "$HARFBUZZ_SOURCE" \
    --cross-file "$CROSS_FILE" \
    --prefix "$PREFIX" \
    --default-library static \
    --buildtype release \
    -Dglib=disabled \
    -Dgobject=disabled \
    -Dcairo=disabled \
    -Dchafa=disabled \
    -Dpng=disabled \
    -Dzlib=disabled \
    -Dicu=disabled \
    -Dfreetype=disabled \
    -Dgraphite2=disabled \
    -Dcoretext=disabled \
    -Draster=disabled \
    -Dvector=disabled \
    -Dgpu=disabled \
    -Dsubset=disabled \
    -Dtests=disabled \
    -Dintrospection=disabled \
    -Ddocs=disabled \
    -Dutilities=disabled
meson compile -C "$TARGET_ROOT/harfbuzz"
meson install -C "$TARGET_ROOT/harfbuzz"

FRIBIDI_LIBRARY_KIND="static"
if [[ "$PLATFORM" == "macos" ]]; then
    FRIBIDI_LIBRARY_KIND="shared"
fi
meson setup "$TARGET_ROOT/fribidi" "$FRIBIDI_SOURCE" \
    --cross-file "$CROSS_FILE" \
    --prefix "$PREFIX" \
    --default-library "$FRIBIDI_LIBRARY_KIND" \
    --buildtype release \
    -Ddocs=false \
    -Dbin=false \
    -Dtests=false
meson compile -C "$TARGET_ROOT/fribidi"
meson install -C "$TARGET_ROOT/fribidi"

mkdir -p "$TARGET_ROOT/libunibreak"
(
    cd "$TARGET_ROOT/libunibreak"
    env \
        CC="$CC" \
        AR="$AR" \
        RANLIB="$RANLIB" \
        CFLAGS="${COMMON_FLAGS[*]} -O2 -fvisibility=hidden" \
        LDFLAGS="${COMMON_FLAGS[*]}" \
        "$UNIBREAK_SOURCE/configure" \
            --host="$HOST_TRIPLE" \
            --prefix="$PREFIX" \
            --disable-shared \
            --enable-static
    make -j"$JOBS"
    make install
)

env PKG_CONFIG_LIBDIR="$PREFIX/lib/pkgconfig" PKG_CONFIG_PATH="" \
    meson setup "$TARGET_ROOT/libass" "$LIBASS_SOURCE" \
        --cross-file "$CROSS_FILE" \
        --prefix "$PREFIX" \
        --default-library static \
        --buildtype release \
        -Dfontconfig=disabled \
        -Dcoretext=enabled \
        -Ddirectwrite=disabled \
        -Dasm=enabled \
        -Dlibunibreak=enabled \
        -Dtest=disabled \
        -Dcompare=disabled \
        -Dprofile=disabled \
        -Dfuzz=disabled \
        -Dcheckasm=disabled
env PKG_CONFIG_LIBDIR="$PREFIX/lib/pkgconfig" PKG_CONFIG_PATH="" \
    meson compile -C "$TARGET_ROOT/libass"
env PKG_CONFIG_LIBDIR="$PREFIX/lib/pkgconfig" PKG_CONFIG_PATH="" \
    meson install -C "$TARGET_ROOT/libass"

INCLUDES=(
    "-I$SCRIPT_DIR/include"
    "-I$COMMON_DIR"
    "-I$PREFIX/include"
    "-I$PREFIX/include/freetype2"
    "-I$PREFIX/include/harfbuzz"
)

if [[ "$PLATFORM" == "macos" ]]; then
    JAVA_HOME_FOR_BUILD="${JAVA_HOME:-$(/usr/libexec/java_home -v 25)}"
    FRIBIDI_DYLIB="$PREFIX/lib/libfribidi.0.dylib"
    [[ -f "$FRIBIDI_DYLIB" ]] || {
        echo "The macOS FriBidi dynamic library was not generated." >&2
        exit 70
    }
    install_name_tool -id @loader_path/libkmediafribidi.dylib "$FRIBIDI_DYLIB"

    "$CC" -dynamiclib -O2 -fvisibility=hidden \
        "${COMMON_FLAGS[@]}" \
        "${INCLUDES[@]}" \
        "-I$JAVA_HOME_FOR_BUILD/include" \
        "-I$JAVA_HOME_FOR_BUILD/include/darwin" \
        "$SCRIPT_DIR/KMediaAssRenderer.c" \
        "$SCRIPT_DIR/AppleAssJni.c" \
        "$COMMON_DIR/AssRgbaCompositor.c" \
        "$PREFIX/lib/libass.a" \
        "$PREFIX/lib/libfreetype.a" \
        "$PREFIX/lib/libharfbuzz.a" \
        "$PREFIX/lib/libunibreak.a" \
        -L"$PREFIX/lib" \
        -lfribidi \
        -framework CoreText \
        -framework CoreFoundation \
        -framework CoreGraphics \
        -liconv \
        -lc++ \
        -lpthread \
        -Wl,-install_name,@rpath/libcomposemediaplayer_ass.dylib \
        -Wl,-exported_symbols_list,"$SCRIPT_DIR/macos.exports" \
        -o "$OUTPUT_DIR/libcomposemediaplayer_ass.dylib"
    cp "$FRIBIDI_DYLIB" "$OUTPUT_DIR/libkmediafribidi.dylib"
    "$STRIP" -S "$OUTPUT_DIR/libcomposemediaplayer_ass.dylib"
    "$STRIP" -S "$OUTPUT_DIR/libkmediafribidi.dylib"
else
    "$CC" -O2 -fvisibility=hidden -c \
        "${COMMON_FLAGS[@]}" \
        "${INCLUDES[@]}" \
        "$SCRIPT_DIR/KMediaAssRenderer.c" \
        -o "$TARGET_ROOT/KMediaAssRenderer.o"
    "$CC" -O2 -fvisibility=hidden -c \
        "${COMMON_FLAGS[@]}" \
        "${INCLUDES[@]}" \
        "$COMMON_DIR/AssRgbaCompositor.c" \
        -o "$TARGET_ROOT/AssRgbaCompositor.o"
    "$LIBTOOL" -static \
        -o "$OUTPUT_DIR/libcomposemediaplayer_ass.a" \
        "$TARGET_ROOT/KMediaAssRenderer.o" \
        "$TARGET_ROOT/AssRgbaCompositor.o" \
        "$PREFIX/lib/libass.a" \
        "$PREFIX/lib/libfreetype.a" \
        "$PREFIX/lib/libharfbuzz.a" \
        "$PREFIX/lib/libunibreak.a"
    cp "$PREFIX/lib/libfribidi.a" "$OUTPUT_DIR/libkmediafribidi.a"
fi

cp "$SCRIPT_DIR/include/KMediaAssRenderer.h" "$OUTPUT_DIR/KMediaAssRenderer.h"
(
    cd "$OUTPUT_DIR"
    shasum -a 256 \
        libcomposemediaplayer_ass.* \
        libkmediafribidi.* \
        >CHECKSUMS.sha256
)
