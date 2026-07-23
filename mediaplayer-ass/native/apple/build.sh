#!/usr/bin/env bash
# SPDX-License-Identifier: LicenseRef-KMediaPlayer-Internal
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MODULE_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
COMMON_DIR="$MODULE_DIR/native/common"

TARGET="${1:-}"
OUTPUT_DIR="${2:-}"
RUNTIME_TARGET_OUTPUT="${3:-${KMEDIA_ASS_RUNTIME_TARGET_OUTPUT:-}}"
if [[ -z "$TARGET" || -z "$OUTPUT_DIR" || -z "$RUNTIME_TARGET_OUTPUT" ]]; then
    echo "Usage: $0 <ios-arm64|ios-simulator-arm64> <output-dir> <runtime-target-output>" >&2
    exit 64
fi

case "$TARGET" in
    ios-arm64)
        SDK="iphoneos"
        TRIPLE="arm64-apple-ios16.2"
        ;;
    ios-simulator-arm64)
        SDK="iphonesimulator"
        TRIPLE="arm64-apple-ios16.2-simulator"
        ;;
    *)
        echo "Unsupported Apple ASS target: $TARGET" >&2
        exit 64
        ;;
esac

RUNTIME_SDK="$RUNTIME_TARGET_OUTPUT/sdk/$TARGET"
[[ -f "$RUNTIME_SDK/include/ass/ass.h" ]] || {
    echo "The exact KMediaAssRuntime SDK headers are missing for $TARGET." >&2
    exit 66
}

SDK_PATH="$(xcrun --sdk "$SDK" --show-sdk-path)"
CC="$(xcrun --sdk "$SDK" --find clang)"
LIBTOOL="$(xcrun --sdk "$SDK" --find libtool)"
BUILD_DIR="$MODULE_DIR/build/shared-ass-apple/$TARGET"
mkdir -p "$BUILD_DIR" "$OUTPUT_DIR"

FLAGS=(
    -O2
    -fvisibility=hidden
    -target "$TRIPLE"
    -isysroot "$SDK_PATH"
    -I"$SCRIPT_DIR/include"
    -I"$COMMON_DIR"
    -I"$RUNTIME_SDK/include"
)

"$CC" "${FLAGS[@]}" -c \
    "$SCRIPT_DIR/KMediaAssRenderer.c" \
    -o "$BUILD_DIR/KMediaAssRenderer.o"
"$CC" "${FLAGS[@]}" -c \
    "$COMMON_DIR/AssRgbaCompositor.c" \
    -o "$BUILD_DIR/AssRgbaCompositor.o"
"$LIBTOOL" -static \
    -o "$OUTPUT_DIR/libcomposemediaplayer_ass.a" \
    "$BUILD_DIR/KMediaAssRenderer.o" \
    "$BUILD_DIR/AssRgbaCompositor.o"

cp "$SCRIPT_DIR/include/KMediaAssRenderer.h" "$OUTPUT_DIR/KMediaAssRenderer.h"
(
    cd "$OUTPUT_DIR"
    shasum -a 256 libcomposemediaplayer_ass.a >CHECKSUMS.sha256
)
