#!/usr/bin/env bash
set -euo pipefail

REPOSITORY_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
IOS_ROOT="${1:-$REPOSITORY_ROOT/mediaplayer-ass/build/generated/appleAssIos}"
RUNTIME_ROOT="${2:-$REPOSITORY_ROOT/mediaplayer-ass/build/kmediaAssRuntimeAppleOutputs}"
POD_ROOT="${3:-$REPOSITORY_ROOT/mediaplayer-ass/build/kmediaAssRuntimePod}"

for tool in cmp find grep lipo nm otool shasum vtool xcrun; do
    command -v "$tool" >/dev/null || {
        echo "Required Apple ASS verification tool is missing: $tool" >&2
        exit 69
    }
done

require_file() {
    local path="$1"
    [[ -s "$path" ]] || {
        echo "Missing thin Apple ASS client file: $path" >&2
        exit 66
    }
}

verify_target() {
    local target="$1"
    local expected_platform="$2"
    local directory="$IOS_ROOT/$target"
    local archive="$directory/libcomposemediaplayer_ass.a"

    [[ -d "$directory" ]] || {
        echo "Missing thin Apple ASS target: $target" >&2
        exit 66
    }
    require_file "$archive"
    require_file "$directory/KMediaAssRenderer.h"
    require_file "$directory/CHECKSUMS.sha256"
    (
        cd "$directory"
        shasum -a 256 -c CHECKSUMS.sha256
    )

    [[ "$(lipo -archs "$archive")" == "arm64" ]] || {
        echo "The thin ASS client is not ARM64-only: $archive" >&2
        exit 65
    }
    if find "$directory" -maxdepth 1 -type f \
        \( -name '*ffmpeg*' -o -name '*libass*' -o -name '*fribidi*' \
           -o -name '*freetype*' -o -name '*harfbuzz*' -o -name '*.dylib' \) \
        -print -quit | grep -q .; then
        echo "The ASS adapter contains a private text runtime: $directory" >&2
        exit 65
    fi
    local unexpected_ass_definitions
    unexpected_ass_definitions="$(
        nm -g "$archive" |
            grep -E '[[:space:]][TDBS][[:space:]]+_ass_' |
            grep -Ev '[[:space:]]_ass_rgba_(buffer_release|composite)$' ||
            true
    )"
    if [[ -n "$unexpected_ass_definitions" ]]; then
        echo "libass was merged into the thin client archive: $archive" >&2
        printf '%s\n' "$unexpected_ass_definitions" >&2
        exit 65
    fi
    nm -gu "$archive" | grep -Eq '^_ass_library_init$' || {
        echo "The thin client does not link to the external libass ABI." >&2
        exit 65
    }
    nm -gu "$archive" | grep -Eq '^_kmediaass_runtime_id$' || {
        echo "The thin client does not verify KMediaAssRuntime identity." >&2
        exit 65
    }

    local object_directory
    object_directory="$(mktemp -d "${TMPDIR:-/tmp}/verify-kmedia-ass.XXXXXX")"
    (
        cd "$object_directory"
        xcrun ar -x "$archive"
    )
    require_file "$object_directory/KMediaAssRenderer.o"
    local build_version
    build_version="$(vtool -show-build "$object_directory/KMediaAssRenderer.o")"
    rm -rf "$object_directory"
    grep -Eq "platform[[:space:]]+$expected_platform" <<<"$build_version" &&
        grep -Eq 'minos[[:space:]]+16\.2' <<<"$build_version" || {
        echo "The thin client has an unexpected platform or deployment target." >&2
        exit 65
    }
}

verify_runtime_target() {
    local target="$1"
    local directory="$RUNTIME_ROOT/$target"
    local expected=(
        KMediaAssRuntime
        KMediaFfmpegAss
        KMediaFfmpegFreetype
        KMediaFfmpegFribidi
        KMediaFfmpegHarfbuzz
    )

    require_file "$directory/ass-runtime-id.txt"
    [[ "$(<"$directory/ass-runtime-id.txt")" == "kmediaass-0.17.5-132a1d9ab8838bbd" ]] || {
        echo "The Apple ASS runtime ID differs for $target." >&2
        exit 65
    }
    local framework_root="$directory/Frameworks"
    [[ -d "$framework_root" ]] || {
        echo "The Apple ASS runtime frameworks are missing for $target." >&2
        exit 66
    }
    local actual
    actual="$(
        find "$framework_root" -mindepth 1 -maxdepth 1 -type d -name '*.framework' \
            -exec basename {} .framework \; | sort
    )"
    local expected_sorted
    expected_sorted="$(printf '%s\n' "${expected[@]}" | sort)"
    [[ "$actual" == "$expected_sorted" ]] || {
        echo "Unexpected Apple ASS runtime framework inventory for $target:" >&2
        printf '%s\n' "$actual" >&2
        exit 65
    }
    local name
    for name in "${expected[@]}"; do
        local binary="$framework_root/$name.framework/$name"
        require_file "$binary"
        [[ "$(lipo -archs "$binary")" == "arm64" ]] || {
            echo "The shared ASS framework is not ARM64-only: $binary" >&2
            exit 65
        }
    done
    otool -L "$framework_root/KMediaAssRuntime.framework/KMediaAssRuntime" |
        grep -Fq '@rpath/KMediaFfmpegAss.framework/KMediaFfmpegAss' || {
            echo "The ASS identity framework does not bind the shared libass framework." >&2
            exit 65
        }
}

[[ -d "$IOS_ROOT" ]] || {
    echo "The generated thin Apple ASS client root is missing: $IOS_ROOT" >&2
    exit 66
}
[[ -d "$RUNTIME_ROOT" ]] || {
    echo "The shared Apple ASS runtime root is missing: $RUNTIME_ROOT" >&2
    exit 66
}
require_file "$POD_ROOT/KMediaAssRuntime.podspec"
grep -Fq "spec.version              = '0.1.0-rc.10'" \
    "$POD_ROOT/KMediaAssRuntime.podspec" || {
        echo "The local KMediaAssRuntime podspec has another version." >&2
        exit 65
    }
pod_frameworks="$(
    find "$POD_ROOT/Frameworks" -mindepth 1 -maxdepth 1 -type d -name '*.xcframework' \
        -exec basename {} .xcframework \; | sort
)"
expected_pod_frameworks="$(
    printf '%s\n' \
        KMediaAssRuntime KMediaFfmpegAss KMediaFfmpegFreetype \
        KMediaFfmpegFribidi KMediaFfmpegHarfbuzz | sort
)"
[[ "$pod_frameworks" == "$expected_pod_frameworks" ]] || {
    echo "The local ASS pod has an unexpected XCFramework inventory." >&2
    printf '%s\n' "$pod_frameworks" >&2
    exit 65
}

actual_targets="$(find "$IOS_ROOT" -mindepth 1 -maxdepth 1 -type d -exec basename {} \; | sort)"
expected_targets=$'ios-arm64\nios-simulator-arm64'
[[ "$actual_targets" == "$expected_targets" ]] || {
    echo "Unexpected Apple ASS target set under $IOS_ROOT:" >&2
    printf '%s\n' "$actual_targets" >&2
    exit 65
}

verify_target ios-arm64 IOS
verify_target ios-simulator-arm64 IOSSIMULATOR
verify_runtime_target ios-arm64
verify_runtime_target ios-simulator-arm64
cmp \
    "$IOS_ROOT/ios-arm64/KMediaAssRenderer.h" \
    "$IOS_ROOT/ios-simulator-arm64/KMediaAssRenderer.h"

echo "Verified thin ARM64 ASS clients and one shared Apple text runtime."
