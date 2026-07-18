#!/usr/bin/env bash
set -euo pipefail

REPOSITORY_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
GENERATED_ROOT="${1:-$REPOSITORY_ROOT/mediaplayer-ass/build/generated}"
JVM_ROOT="$GENERATED_ROOT/appleAssJvmResources/composemediaplayer/ass/native"
IOS_ROOT="$GENERATED_ROOT/appleAssIos"

for tool in cmp grep lipo nm otool shasum vtool xcrun; do
    command -v "$tool" >/dev/null || {
        echo "Required Apple ASS verification tool is missing: $tool" >&2
        exit 69
    }
done

require_file() {
    local path="$1"
    [[ -s "$path" ]] || {
        echo "Missing Apple ASS payload: $path" >&2
        exit 66
    }
}

verify_checksums() {
    local directory="$1"
    require_file "$directory/CHECKSUMS.sha256"
    (
        cd "$directory"
        shasum -a 256 -c CHECKSUMS.sha256
    )
}

verify_macos_target() {
    local directory="$1"
    local expected_arch="$2"
    local renderer="$directory/libcomposemediaplayer_ass.dylib"
    local fribidi="$directory/libkmediafribidi.dylib"
    require_file "$renderer"
    require_file "$fribidi"
    require_file "$directory/KMediaAssRenderer.h"
    verify_checksums "$directory"

    [[ "$(lipo -archs "$renderer")" == "$expected_arch" ]] || {
        echo "Unexpected renderer architecture in $renderer" >&2
        exit 65
    }
    [[ "$(lipo -archs "$fribidi")" == "$expected_arch" ]] || {
        echo "Unexpected FriBidi architecture in $fribidi" >&2
        exit 65
    }

    local linkage
    linkage="$(otool -L "$renderer")"
    grep -Fq "@loader_path/libkmediafribidi.dylib" <<<"$linkage" || {
        echo "The macOS renderer does not use the replaceable @loader_path FriBidi library." >&2
        exit 65
    }
    local unexpected_renderer_dependencies
    unexpected_renderer_dependencies="$(
        sed -n '2,$s/^[[:space:]]*\\([^[:space:]]*\\).*/\\1/p' <<<"$linkage" |
            grep -Ev '^(@rpath/libcomposemediaplayer_ass\.dylib|@loader_path/libkmediafribidi\.dylib|/System/Library/Frameworks/(CoreText|CoreFoundation|CoreGraphics)\.framework/Versions/A/(CoreText|CoreFoundation|CoreGraphics)|/usr/lib/(libiconv\.2|libc\+\+\.1|libSystem\.B)\.dylib)$' ||
            true
    )"
    [[ -z "$unexpected_renderer_dependencies" ]] || {
        echo "The macOS renderer has unexpected dynamic dependencies:" >&2
        echo "$unexpected_renderer_dependencies" >&2
        exit 65
    }

    local fribidi_id
    local fribidi_linkage
    fribidi_linkage="$(otool -L "$fribidi")"
    fribidi_id="$(otool -D "$fribidi" | sed -n '2s/^[[:space:]]*//p')"
    [[ "$fribidi_id" == "@loader_path/libkmediafribidi.dylib" ]] || {
        echo "Unexpected FriBidi install name: $fribidi_id" >&2
        exit 65
    }
    local unexpected_fribidi_dependencies
    unexpected_fribidi_dependencies="$(
        sed -n '2,$s/^[[:space:]]*\\([^[:space:]]*\\).*/\\1/p' <<<"$fribidi_linkage" |
            grep -Ev '^(@loader_path/libkmediafribidi\.dylib|/usr/lib/libSystem\.B\.dylib)$' ||
            true
    )"
    [[ -z "$unexpected_fribidi_dependencies" ]] || {
        echo "The replaceable FriBidi library has unexpected dynamic dependencies:" >&2
        echo "$unexpected_fribidi_dependencies" >&2
        exit 65
    }

    local unexpected_exports
    unexpected_exports="$(
        nm -gjU "$renderer" |
            grep -Ev '^_(JNI_OnLoad|kmedia_ass_(frame_copy_cg_image|library_version|renderer_(add_font|blend_bgra|create|destroy|render_rgba|set_track)))$' ||
            true
    )"
    [[ -z "$unexpected_exports" ]] || {
        echo "Unexpected exported symbols in $renderer:" >&2
        echo "$unexpected_exports" >&2
        exit 65
    }
    [[ "$(nm -gjU "$renderer" | wc -l | tr -d '[:space:]')" == "9" ]] || {
        echo "The macOS renderer export set is incomplete." >&2
        exit 65
    }

    local build_version
    build_version="$(vtool -show-build "$renderer")"
    grep -Eq 'platform[[:space:]]+MACOS' <<<"$build_version" &&
        grep -Eq 'minos[[:space:]]+14\.0' <<<"$build_version" || {
        echo "The macOS renderer does not target macOS 14.0." >&2
        exit 65
    }
}

TEMP_ROOT="$(mktemp -d "${TMPDIR:-/tmp}/verify-kmedia-ass.XXXXXX")"
trap 'rm -rf "$TEMP_ROOT"' EXIT

verify_ios_target() {
    local directory="$1"
    local expected_platform="$2"
    local renderer="$directory/libcomposemediaplayer_ass.a"
    local fribidi="$directory/libkmediafribidi.a"
    require_file "$renderer"
    require_file "$fribidi"
    require_file "$directory/KMediaAssRenderer.h"
    verify_checksums "$directory"

    [[ "$(lipo -archs "$renderer")" == "arm64" ]] || {
        echo "The iOS renderer is not arm64-only: $renderer" >&2
        exit 65
    }
    [[ "$(lipo -archs "$fribidi")" == "arm64" ]] || {
        echo "The iOS FriBidi archive is not arm64-only: $fribidi" >&2
        exit 65
    }
    if nm -g "$renderer" | grep -E '[[:space:]][TDBS][[:space:]]+_fribidi_' >/dev/null; then
        echo "FriBidi was merged into the iOS renderer archive: $renderer" >&2
        exit 65
    fi
    nm -gu "$renderer" | grep '_fribidi_' >/dev/null || {
        echo "The iOS renderer has no replaceable FriBidi references: $renderer" >&2
        exit 65
    }
    nm -g "$fribidi" | grep -E '[[:space:]][TDBS][[:space:]]+_fribidi_' >/dev/null || {
        echo "The separate iOS FriBidi archive exports no FriBidi ABI." >&2
        exit 65
    }
    if nm -g "$renderer" | grep '_JNI_OnLoad' >/dev/null; then
        echo "The iOS archive unexpectedly contains the JVM bridge." >&2
        exit 65
    fi

    local object_directory="$TEMP_ROOT/$expected_platform"
    mkdir -p "$object_directory"
    (
        cd "$object_directory"
        xcrun ar -x "$renderer" KMediaAssRenderer.o
    )
    local build_version
    build_version="$(vtool -show-build "$object_directory/KMediaAssRenderer.o")"
    grep -Eq "platform[[:space:]]+$expected_platform" <<<"$build_version" &&
        grep -Eq 'minos[[:space:]]+16\.2' <<<"$build_version" || {
        echo "The iOS renderer has an unexpected platform or deployment target." >&2
        exit 65
    }
}

[[ -d "$JVM_ROOT" && -d "$IOS_ROOT" ]] || {
    echo "The generated Apple ASS payload is incomplete under $GENERATED_ROOT." >&2
    exit 66
}

mapfile_supported=false
if type mapfile >/dev/null 2>&1; then
    mapfile_supported=true
fi
if [[ "$mapfile_supported" == "true" ]]; then
    mapfile -t macos_directories < <(find "$JVM_ROOT" -mindepth 1 -maxdepth 1 -type d -print | sort)
    mapfile -t ios_directories < <(find "$IOS_ROOT" -mindepth 1 -maxdepth 1 -type d -print | sort)
else
    macos_directories=()
    ios_directories=()
    while IFS= read -r directory; do macos_directories+=("$directory"); done < <(
        find "$JVM_ROOT" -mindepth 1 -maxdepth 1 -type d -print | sort
    )
    while IFS= read -r directory; do ios_directories+=("$directory"); done < <(
        find "$IOS_ROOT" -mindepth 1 -maxdepth 1 -type d -print | sort
    )
fi

[[ "${#macos_directories[@]}" == "1" ]] || {
    echo "Unexpected macOS ASS target directory set under $JVM_ROOT." >&2
    exit 65
}
[[ "${#ios_directories[@]}" == "2" ]] || {
    echo "Unexpected iOS ASS target directory set under $IOS_ROOT." >&2
    exit 65
}
[[ ! -e "$JVM_ROOT/darwin-x86-64" ]] || {
    echo "An unsupported Intel macOS ASS payload was generated." >&2
    exit 65
}
[[ ! -e "$IOS_ROOT/ios-x64" && ! -e "$IOS_ROOT/ios-x86_64" ]] || {
    echo "An unsupported Intel iOS ASS payload was generated." >&2
    exit 65
}

verify_macos_target "$JVM_ROOT/darwin-aarch64" arm64
verify_ios_target "$IOS_ROOT/ios-arm64" IOS
verify_ios_target "$IOS_ROOT/ios-simulator-arm64" IOSSIMULATOR

cmp \
    "$JVM_ROOT/darwin-aarch64/KMediaAssRenderer.h" \
    "$IOS_ROOT/ios-arm64/KMediaAssRenderer.h"
cmp \
    "$JVM_ROOT/darwin-aarch64/KMediaAssRenderer.h" \
    "$IOS_ROOT/ios-simulator-arm64/KMediaAssRenderer.h"

echo "Verified macOS arm64 and iOS device/simulator arm64 ASS payloads."
