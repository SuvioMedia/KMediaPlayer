#!/usr/bin/env bash
# SPDX-License-Identifier: LicenseRef-KMediaPlayer-Internal
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MODULE_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
JNI_DIR="$MODULE_DIR/src/androidMain/native/ass/jni"
OUTPUT_ROOT="$MODULE_DIR/src/androidMain/jniLibs"

RUNTIME_OUTPUT="${1:-}"
NDK="${2:-${ANDROID_NDK_HOME:-}}"
if [[ -z "$RUNTIME_OUTPUT" || -z "$NDK" ]]; then
    echo "Usage: $0 <KMediaAssRuntime target-output root> <Android NDK>" >&2
    exit 64
fi

CMAKE="$(command -v cmake || true)"
if [[ ! -x "$CMAKE" && -n "${ANDROID_SDK_ROOT:-}" ]]; then
    CMAKE="$ANDROID_SDK_ROOT/cmake/4.1.2/bin/cmake"
fi
[[ -x "$CMAKE" ]] || {
    echo "CMake 3.22+ is required." >&2
    exit 69
}
[[ -f "$NDK/build/cmake/android.toolchain.cmake" ]] || {
    echo "The Android NDK toolchain is missing." >&2
    exit 66
}
PREBUILT_DIR="$(find "$NDK/toolchains/llvm/prebuilt" -mindepth 1 -maxdepth 1 -type d -print -quit)"
LLVM_STRIP="$PREBUILT_DIR/bin/llvm-strip"
[[ -x "$LLVM_STRIP" ]] || {
    echo "The Android NDK llvm-strip tool is missing." >&2
    exit 66
}

build_one() {
    local abi="$1"
    local target="$2"
    local sdk="$RUNTIME_OUTPUT/$target/sdk/$target"
    local build="$MODULE_DIR/build/shared-ass-android/$abi"
    local output="$OUTPUT_ROOT/$abi"
    local libass="$sdk/lib/libkmediaffmpeg_ass.so"

    [[ -f "$sdk/include/ass/ass.h" && -f "$libass" ]] || {
        echo "The exact KMediaAssRuntime SDK is incomplete for $target." >&2
        exit 66
    }
    "$CMAKE" -S "$JNI_DIR" -B "$build" \
        -DCMAKE_BUILD_TYPE=Release \
        -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
        -DANDROID_ABI="$abi" \
        -DANDROID_PLATFORM=23 \
        -DLIBASS_INCLUDE_DIR="$sdk/include" \
        -DLIBASS_SHARED_LIBRARY="$libass"
    "$CMAKE" --build "$build" --target kmediaass --parallel
    mkdir -p "$output"
    cp "$build/libkmediaass.so" "$output/libkmediaass.so"
    "$LLVM_STRIP" --strip-unneeded "$output/libkmediaass.so"
}

build_one arm64-v8a android-arm64-v8a
build_one armeabi-v7a android-armeabi-v7a

(
    cd "$OUTPUT_ROOT"
    shasum -a 256 \
        arm64-v8a/libkmediaass.so \
        armeabi-v7a/libkmediaass.so \
        >"$MODULE_DIR/src/androidMain/native/ass/CHECKSUMS.sha256"
)
