#!/bin/bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
OUTPUT_DIR="${NATIVE_LIBS_OUTPUT_DIR:-$SCRIPT_DIR/../../resources/composemediaplayer/native}"

SWIFT_SOURCE="$SCRIPT_DIR/NativeVideoPlayer.swift"
HDR_RENDERER_SOURCE="$SCRIPT_DIR/HdrMetalVideoRenderer.swift"
HDR_SHADER_SOURCE="$SCRIPT_DIR/HdrMetalProjectionShader.swift"
JNI_BRIDGE="$SCRIPT_DIR/jni_bridge.c"
HDR10_PLUS_DIR="$SCRIPT_DIR/../common"
HDR10_PLUS_SOURCE="$HDR10_PLUS_DIR/Hdr10PlusToneCurve.c"
HDR10_PLUS_TEST="$HDR10_PLUS_DIR/Hdr10PlusToneCurveTest.c"
HDR10_PLUS_BRIDGE="$HDR10_PLUS_DIR/Hdr10PlusToneCurveBridge.h"
ICTCP_GAMUT_LUT_SOURCE="$HDR10_PLUS_DIR/IctcpGamutLut.c"
ICTCP_GAMUT_LUT_TEST="$HDR10_PLUS_DIR/IctcpGamutLutTest.c"
HDR_METAL_REFERENCE_TEST="$SCRIPT_DIR/HdrMetalShaderReferenceTest.swift"
BUILD_TMP_DIR="$(mktemp -d "${TMPDIR:-/tmp}/kmp-macos-native.XXXXXX")"
trap 'rm -rf "$BUILD_TMP_DIR"' EXIT

echo "=== Building macOS NativeVideoPlayer ==="
echo "Output dir: $OUTPUT_DIR"

# Resolve JDK include paths (required to compile jni_bridge.c).
# IntelliJ/Android Studio may launch Gradle with JAVA_HOME pointing to a bundled
# runtime that does not ship JNI headers, so validate before using it.
has_jni_headers() {
    local CANDIDATE="$1"
    [ -n "$CANDIDATE" ] &&
        [ -f "$CANDIDATE/include/jni.h" ] &&
        [ -f "$CANDIDATE/include/darwin/jni_md.h" ]
}

has_supported_jdk() {
    local CANDIDATE="$1"
    local RELEASE_FILE="$CANDIDATE/release"
    local MAJOR_VERSION=""

    has_jni_headers "$CANDIDATE" || return 1
    [ -f "$RELEASE_FILE" ] || return 1

    MAJOR_VERSION="$(sed -n 's/^JAVA_VERSION="\([0-9][0-9]*\).*"$/\1/p' "$RELEASE_FILE" | head -n 1)"
    [ -n "$MAJOR_VERSION" ] && [ "$MAJOR_VERSION" -ge 25 ]
}

resolve_java_home() {
    local CANDIDATE="${JAVA_HOME:-}"
    if has_supported_jdk "$CANDIDATE"; then
        echo "$CANDIDATE"
        return 0
    fi

    CANDIDATE="$(/usr/libexec/java_home 2>/dev/null || echo '')"
    if has_supported_jdk "$CANDIDATE"; then
        echo "$CANDIDATE"
        return 0
    fi

    CANDIDATE="$(/usr/libexec/java_home -v 25 2>/dev/null || echo '')"
    if has_supported_jdk "$CANDIDATE"; then
        echo "$CANDIDATE"
        return 0
    fi

    return 1
}

RESOLVED_JAVA_HOME="$(resolve_java_home || true)"
if [ -z "$RESOLVED_JAVA_HOME" ]; then
    echo "ERROR: Could not find a full JDK 25 or newer with JNI headers."
    echo "       Configure IntelliJ Gradle JVM to JDK 25+, not a bundled runtime/JRE."
    echo "       Required files: include/jni.h and include/darwin/jni_md.h"
    exit 1
fi
echo "Using JDK for JNI headers: $RESOLVED_JAVA_HOME"
JNI_INCLUDES=("-I${RESOLVED_JAVA_HOME}/include" "-I${RESOLVED_JAVA_HOME}/include/darwin")

# Output directories
ARM64_DIR="$OUTPUT_DIR/darwin-arm64"
UNSUPPORTED_INTEL_DIR="$OUTPUT_DIR/darwin-x86-64"

rm -rf -- "$UNSUPPORTED_INTEL_DIR"
mkdir -p "$ARM64_DIR"

echo "=== Testing shared HDR10+ parser and OOTF ==="
clang -std=c11 -Wall -Wextra -Werror \
    "$HDR10_PLUS_SOURCE" "$HDR10_PLUS_TEST" \
    -I"$HDR10_PLUS_DIR" -o "$BUILD_TMP_DIR/kmp_hdr10_plus_test"
"$BUILD_TMP_DIR/kmp_hdr10_plus_test"

echo "=== Testing shared ICtCp gamut-mapping LUT ==="
clang -std=c11 -Wall -Wextra -Werror \
    "$ICTCP_GAMUT_LUT_SOURCE" "$ICTCP_GAMUT_LUT_TEST" \
    -I"$HDR10_PLUS_DIR" -o "$BUILD_TMP_DIR/kmp_ictcp_gamut_lut_test"
"$BUILD_TMP_DIR/kmp_ictcp_gamut_lut_test"

echo "=== Testing production Metal HDR math against CPU references ==="
HOST_ARCH="$(uname -m)"
HOST_TARGET="${HOST_ARCH}-apple-macosx14.0"
clang -std=c11 -Wall -Wextra -Werror -c -arch "$HOST_ARCH" -target "$HOST_TARGET" \
    -I"$HDR10_PLUS_DIR" \
    "$HDR10_PLUS_SOURCE" -o "$BUILD_TMP_DIR/hdr10_plus_reference.o"
swiftc -target "$HOST_TARGET" \
    -import-objc-header "$HDR10_PLUS_BRIDGE" \
    -Xcc -I"$HDR10_PLUS_DIR" \
    "$HDR_SHADER_SOURCE" \
    "$HDR_METAL_REFERENCE_TEST" \
    "$BUILD_TMP_DIR/hdr10_plus_reference.o" \
    -framework Foundation \
    -framework Metal \
    -o "$BUILD_TMP_DIR/kmp_metal_reference_test"
"$BUILD_TMP_DIR/kmp_metal_reference_test"

build_arch() {
    local ARCH="$1"
    local TARGET="${ARCH}-apple-macosx14.0"
    local OUTPUT_DIR="$2"
    local BRIDGE_OBJ="$BUILD_TMP_DIR/jni_bridge_${ARCH}.o"
    local HDR10_PLUS_OBJ="$BUILD_TMP_DIR/hdr10_plus_${ARCH}.o"
    local ICTCP_GAMUT_LUT_OBJ="$BUILD_TMP_DIR/ictcp_gamut_lut_${ARCH}.o"

    echo "=== Compiling JNI bridge for ${ARCH} ==="
    clang -c -x objective-c -arch "$ARCH" -target "$TARGET" \
        "${JNI_INCLUDES[@]}" \
        "$JNI_BRIDGE" -o "$BRIDGE_OBJ"

    echo "=== Compiling shared HDR10+ parser for ${ARCH} ==="
    clang -std=c11 -Wall -Wextra -Werror -c -arch "$ARCH" -target "$TARGET" \
        -I"$HDR10_PLUS_DIR" \
        "$HDR10_PLUS_SOURCE" -o "$HDR10_PLUS_OBJ"

    echo "=== Compiling shared ICtCp gamut-mapping LUT for ${ARCH} ==="
    clang -std=c11 -Wall -Wextra -Werror -c -arch "$ARCH" -target "$TARGET" \
        -I"$HDR10_PLUS_DIR" \
        "$ICTCP_GAMUT_LUT_SOURCE" -o "$ICTCP_GAMUT_LUT_OBJ"

    echo "=== Building NativeVideoPlayer dylib for ${ARCH} ==="
    swiftc -emit-library -emit-module -module-name NativeVideoPlayer \
        -target "$TARGET" \
        -import-objc-header "$HDR10_PLUS_BRIDGE" \
        -Xcc -I"$HDR10_PLUS_DIR" \
        -o "$OUTPUT_DIR/libNativeVideoPlayer.dylib" \
        "$SWIFT_SOURCE" \
        "$HDR_RENDERER_SOURCE" \
        "$HDR_SHADER_SOURCE" \
        "$BRIDGE_OBJ" \
        "$HDR10_PLUS_OBJ" \
        "$ICTCP_GAMUT_LUT_OBJ" \
        -framework AppKit \
        -framework AVFoundation \
        -framework CoreImage \
        -framework CoreVideo \
        -framework Metal \
        -framework QuartzCore \
        -framework VideoToolbox \
        -O -whole-module-optimization

    # Clean up Swift build artifacts
    rm -f "$OUTPUT_DIR"/NativeVideoPlayer.abi.json \
          "$OUTPUT_DIR"/NativeVideoPlayer.swiftdoc \
          "$OUTPUT_DIR"/NativeVideoPlayer.swiftmodule \
          "$OUTPUT_DIR"/NativeVideoPlayer.swiftsourceinfo
    rm -f "$BRIDGE_OBJ" "$HDR10_PLUS_OBJ" "$ICTCP_GAMUT_LUT_OBJ"
}

build_arch "arm64"   "$ARM64_DIR"

echo "=== Build completed ==="
echo "arm64:  $ARM64_DIR/libNativeVideoPlayer.dylib"
