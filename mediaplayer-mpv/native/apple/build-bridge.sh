#!/usr/bin/env bash
# SPDX-License-Identifier: LicenseRef-KMediaPlayer-Proprietary

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TARGET="${1:-}"
OUTPUT_DIR="${2:-}"

if [[ -z "$TARGET" || -z "$OUTPUT_DIR" ]]; then
    echo "Usage: $0 <ios-arm64|ios-simulator-arm64> <output-dir>" >&2
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
        echo "Unsupported Apple MPV bridge target: $TARGET" >&2
        exit 64
        ;;
esac

for tool in xcrun; do
    command -v "$tool" >/dev/null || {
        echo "Required build tool is missing: $tool" >&2
        exit 69
    }
done

SDK_PATH="$(xcrun --sdk "$SDK" --show-sdk-path)"
CC="$(xcrun --sdk "$SDK" --find clang)"
LIBTOOL="$(xcrun --sdk "$SDK" --find libtool)"

mkdir -p "$OUTPUT_DIR"
OBJECT="$OUTPUT_DIR/ComposeMediaPlayerMpvBridge.o"
LIBRARY="$OUTPUT_DIR/libcomposemediaplayer_mpv_bridge.a"

"$CC" \
    -target "$TRIPLE" \
    -isysroot "$SDK_PATH" \
    -std=c11 \
    -O2 \
    -fvisibility=hidden \
    -Wall \
    -Wextra \
    -Werror \
    -I"$SCRIPT_DIR/include" \
    -c "$SCRIPT_DIR/ComposeMediaPlayerMpvBridge.c" \
    -o "$OBJECT"

"$LIBTOOL" -static -o "$LIBRARY" "$OBJECT"
