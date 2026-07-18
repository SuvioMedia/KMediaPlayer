#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MODULE_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
FRIBIDI_SOURCE_ARCHIVE="$MODULE_DIR/src/androidMain/native/ass/corresponding-source/fribidi-1.0.16-source.tar.xz"

TARGET="${1:-}"
OUTPUT_ROOT="${2:-}"
if [[ -z "$TARGET" || -z "$OUTPUT_ROOT" ]]; then
    echo "Usage: $0 <linux-x86-64|linux-aarch64> <JVM-resource-root>" >&2
    exit 64
fi

case "$TARGET" in
    linux-x86-64)
        EXPECTED_MACHINE="x86_64"
        ;;
    linux-aarch64)
        EXPECTED_MACHINE="aarch64"
        ;;
    *)
        echo "Unsupported Linux ASS target: $TARGET" >&2
        exit 64
        ;;
esac

ACTUAL_MACHINE="$(uname -m)"
if [[ "$ACTUAL_MACHINE" == "arm64" ]]; then
    ACTUAL_MACHINE="aarch64"
fi
if [[ "$ACTUAL_MACHINE" != "$EXPECTED_MACHINE" ]]; then
    echo "$TARGET must be built natively on $EXPECTED_MACHINE, not $ACTUAL_MACHINE." >&2
    exit 64
fi

for tool in curl meson ninja pkg-config sha256sum tar make cc c++ patchelf readelf strip; do
    command -v "$tool" >/dev/null || {
        echo "Required build tool is missing: $tool" >&2
        exit 69
    }
done
if [[ "$TARGET" == "linux-x86-64" ]] && ! command -v nasm >/dev/null; then
    echo "NASM is required for the x86_64 libass renderer." >&2
    exit 69
fi

JOBS="${KMEDIA_ASS_BUILD_JOBS:-$(nproc 2>/dev/null || echo 4)}"
if [[ ! "$JOBS" =~ ^[1-9][0-9]*$ ]]; then
    JOBS=4
fi

BUILD_ROOT="${KMEDIA_ASS_DESKTOP_BUILD_ROOT:-$MODULE_DIR/build/desktop-ass-native}"
DOWNLOAD_DIR="$BUILD_ROOT/downloads"
SOURCE_DIR="$BUILD_ROOT/sources"
TARGET_ROOT="$BUILD_ROOT/$TARGET"
PREFIX="$TARGET_ROOT/prefix"
PLATFORM_DIR="$OUTPUT_ROOT/composemediaplayer/ass/native/$TARGET"
mkdir -p "$DOWNLOAD_DIR" "$SOURCE_DIR" "$TARGET_ROOT" "$PLATFORM_DIR"

download_verified() {
    local filename="$1"
    local url="$2"
    local expected_sha="$3"
    local destination="$DOWNLOAD_DIR/$filename"
    if [[ -f "$destination" ]] &&
        [[ "$(sha256sum "$destination" | awk '{print $1}')" == "$expected_sha" ]]; then
        printf '%s\n' "$destination"
        return
    fi

    local temporary="$destination.tmp.$$"
    curl --fail --location --retry 3 --output "$temporary" "$url"
    local actual_sha
    actual_sha="$(sha256sum "$temporary" | awk '{print $1}')"
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

[[ -f "$FRIBIDI_SOURCE_ARCHIVE" ]] || {
    echo "The exact FriBidi corresponding-source archive is missing." >&2
    exit 66
}
FRIBIDI_SHA="$(sha256sum "$FRIBIDI_SOURCE_ARCHIVE" | awk '{print $1}')"
[[ "$FRIBIDI_SHA" == "9f1af7a082dcf280b0a97c5617af9dfc73db2fef93ca45290f34a3a6702ad09a" ]] || {
    echo "The pinned FriBidi corresponding-source archive has changed." >&2
    exit 65
}

LIBASS_SOURCE="$(extract_once "$LIBASS_ARCHIVE" libass-0.17.5)"
FREETYPE_SOURCE="$(extract_once "$FREETYPE_ARCHIVE" freetype-2.14.3)"
HARFBUZZ_SOURCE="$(extract_once "$HARFBUZZ_ARCHIVE" harfbuzz-14.2.1)"
UNIBREAK_SOURCE="$(extract_once "$UNIBREAK_ARCHIVE" libunibreak-7.0)"
FRIBIDI_SOURCE="$(extract_once "$FRIBIDI_SOURCE_ARCHIVE" fribidi-1.0.16-kmedia)"

rm -rf \
    "$TARGET_ROOT/freetype" \
    "$TARGET_ROOT/harfbuzz" \
    "$TARGET_ROOT/fribidi" \
    "$TARGET_ROOT/libunibreak" \
    "$TARGET_ROOT/libass" \
    "$PREFIX"
mkdir -p "$PREFIX"

meson setup "$TARGET_ROOT/freetype" "$FREETYPE_SOURCE" \
    --prefix "$PREFIX" \
    --libdir lib \
    --default-library static \
    --buildtype release \
    -Dbrotli=disabled \
    -Dbzip2=disabled \
    -Dharfbuzz=disabled \
    -Dpng=disabled \
    -Dzlib=disabled \
    -Dtests=disabled
meson compile -C "$TARGET_ROOT/freetype" -j "$JOBS"
meson install -C "$TARGET_ROOT/freetype"

meson setup "$TARGET_ROOT/harfbuzz" "$HARFBUZZ_SOURCE" \
    --prefix "$PREFIX" \
    --libdir lib \
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
meson compile -C "$TARGET_ROOT/harfbuzz" -j "$JOBS"
meson install -C "$TARGET_ROOT/harfbuzz"

meson setup "$TARGET_ROOT/fribidi" "$FRIBIDI_SOURCE" \
    --prefix "$PREFIX" \
    --libdir lib \
    --default-library shared \
    --buildtype release \
    -Ddocs=false \
    -Dbin=false \
    -Dtests=false
meson compile -C "$TARGET_ROOT/fribidi" -j "$JOBS"
meson install -C "$TARGET_ROOT/fribidi"

FRIBIDI_SHARED="$(find "$PREFIX" -type f -name 'libfribidi.so.*' | sort | tail -n 1)"
[[ -n "$FRIBIDI_SHARED" ]] || {
    echo "The replaceable Linux FriBidi shared library was not generated." >&2
    exit 70
}
patchelf --set-soname libkmediafribidi.so.0 "$FRIBIDI_SHARED"

mkdir -p "$TARGET_ROOT/libunibreak"
(
    cd "$TARGET_ROOT/libunibreak"
    env CFLAGS="-O2 -fPIC -fvisibility=hidden" \
        "$UNIBREAK_SOURCE/configure" \
            --prefix="$PREFIX" \
            --disable-shared \
            --enable-static
    make -j"$JOBS"
    make install
)

PKG_CONFIG_PATH_VALUE="$PREFIX/lib/pkgconfig:$PREFIX/lib64/pkgconfig"
env PKG_CONFIG_PATH="$PKG_CONFIG_PATH_VALUE" \
    meson setup "$TARGET_ROOT/libass" "$LIBASS_SOURCE" \
        --prefix "$PREFIX" \
        --libdir lib \
        --default-library shared \
        --buildtype release \
        -Dfontconfig=enabled \
        -Dcoretext=disabled \
        -Ddirectwrite=disabled \
        -Dasm=enabled \
        -Dlibunibreak=enabled \
        -Dtest=disabled \
        -Dcompare=disabled \
        -Dprofile=disabled \
        -Dfuzz=disabled \
        -Dcheckasm=disabled
env PKG_CONFIG_PATH="$PKG_CONFIG_PATH_VALUE" \
    meson compile -C "$TARGET_ROOT/libass" -j "$JOBS"
env PKG_CONFIG_PATH="$PKG_CONFIG_PATH_VALUE" \
    meson install -C "$TARGET_ROOT/libass"

LIBASS_SHARED="$(find "$PREFIX" -type f -name 'libass.so.9*' | sort | tail -n 1)"
[[ -n "$LIBASS_SHARED" ]] || {
    echo "The Linux libass shared library was not generated." >&2
    exit 70
}

rm -f "$PLATFORM_DIR"/*.so "$PLATFORM_DIR"/*.so.* "$PLATFORM_DIR/runtime.properties"
cp -L "$LIBASS_SHARED" "$PLATFORM_DIR/libass.so.9"
cp -L "$FRIBIDI_SHARED" "$PLATFORM_DIR/libkmediafribidi.so.0"
strip --strip-unneeded "$PLATFORM_DIR/libass.so.9" "$PLATFORM_DIR/libkmediafribidi.so.0"
patchelf --set-rpath '$ORIGIN' "$PLATFORM_DIR/libass.so.9"

readelf -d "$PLATFORM_DIR/libass.so.9" | grep -q 'libkmediafribidi.so.0' || {
    echo "The bundled Linux libass does not refer to the replaceable private FriBidi SONAME." >&2
    exit 70
}

LIBASS_DIGEST="$(sha256sum "$PLATFORM_DIR/libass.so.9" | awk '{print $1}')"
FRIBIDI_DIGEST="$(sha256sum "$PLATFORM_DIR/libkmediafribidi.so.0" | awk '{print $1}')"
cat >"$PLATFORM_DIR/runtime.properties" <<EOF
# Generated by native/desktop/build-linux.sh from pinned upstream sources.
version=0.17.5
mainLibrary=libass.so.9
file.count=2
file.0.name=libass.so.9
file.0.sha256=$LIBASS_DIGEST
file.1.name=libkmediafribidi.so.0
file.1.sha256=$FRIBIDI_DIGEST
EOF

echo "Bundled Linux libass runtime was written to $PLATFORM_DIR."
