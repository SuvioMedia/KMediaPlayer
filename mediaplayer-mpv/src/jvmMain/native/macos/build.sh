#!/bin/bash
set -euo pipefail

if [ "$#" -ne 2 ]; then
    echo "usage: build.sh <jdk-home> <resources-output-directory>" >&2
    exit 2
fi

JDK_HOME="$1"
OUTPUT_ROOT="$2"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
SOURCE="$SCRIPT_DIR/MpvMacVideoBridge.m"
OUTPUT_DIRECTORY="$OUTPUT_ROOT/composemediaplayer/native/darwin-arm64"
OUTPUT_LIBRARY="$OUTPUT_DIRECTORY/libComposeMediaPlayerMpvMac.dylib"

if [ ! -f "$JDK_HOME/include/jni.h" ] || [ ! -f "$JDK_HOME/include/darwin/jni_md.h" ]; then
    echo "A full JDK with macOS JNI headers is required: $JDK_HOME" >&2
    exit 1
fi

mkdir -p "$OUTPUT_DIRECTORY"

clang \
    -x objective-c \
    -fblocks \
    -fno-objc-arc \
    -Wall \
    -Wextra \
    -Werror \
    -Wno-deprecated-declarations \
    -arch arm64 \
    -target arm64-apple-macosx13.0 \
    -dynamiclib \
    -install_name "@rpath/libComposeMediaPlayerMpvMac.dylib" \
    -I"$JDK_HOME/include" \
    -I"$JDK_HOME/include/darwin" \
    "$SOURCE" \
    -framework AppKit \
    -framework CoreFoundation \
    -framework CoreGraphics \
    -framework CoreVideo \
    -framework OpenGL \
    -framework QuartzCore \
    -o "$OUTPUT_LIBRARY"

echo "$OUTPUT_LIBRARY"
