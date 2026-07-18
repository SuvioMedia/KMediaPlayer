#!/usr/bin/env bash
set -euo pipefail

TEST_DIR="$(cd "$(dirname "$0")" && pwd)"
NATIVE_DIR="$(cd "$TEST_DIR/.." && pwd)"
WORK_DIR="${RUNNER_TEMP:-/tmp}/compose-media-player-jbr-wayland-smoke"

case "$(uname -m)" in
    x86_64|amd64)
        JBR_ARCH="x64"
        ;;
    aarch64|arm64)
        JBR_ARCH="aarch64"
        ;;
    *)
        echo "Unsupported JBR smoke-test architecture: $(uname -m)" >&2
        exit 1
        ;;
esac

JBR_URL="${JBR_URL:-https://cache-redirector.jetbrains.com/intellij-jbr/jbrsdk-25.0.3-linux-${JBR_ARCH}-b496.62.tar.gz}"
rm -rf "$WORK_DIR"
mkdir -p "$WORK_DIR/jbr" "$WORK_DIR/runtime" "$WORK_DIR/classes"
chmod 700 "$WORK_DIR/runtime"

curl --fail --location --retry 3 --silent --show-error \
    "$JBR_URL" \
    --output "$WORK_DIR/jbr.tar.gz"
tar -xzf "$WORK_DIR/jbr.tar.gz" -C "$WORK_DIR/jbr" --strip-components=1

export XDG_RUNTIME_DIR="$WORK_DIR/runtime"
export WAYLAND_DISPLAY="compose-media-player-test"
export XDG_SESSION_TYPE="wayland"
export DISPLAY=":99"

XVFB_PID=""
WESTON_PID=""
cleanup() {
    if [[ -n "$WESTON_PID" ]]; then
        kill "$WESTON_PID" 2>/dev/null || true
        wait "$WESTON_PID" 2>/dev/null || true
    fi
    if [[ -n "$XVFB_PID" ]]; then
        kill "$XVFB_PID" 2>/dev/null || true
        wait "$XVFB_PID" 2>/dev/null || true
    fi
}
trap cleanup EXIT

Xvfb "$DISPLAY" -screen 0 1280x720x24 >"$WORK_DIR/xvfb.log" 2>&1 &
XVFB_PID="$!"
for _ in $(seq 1 100); do
    [[ -S /tmp/.X11-unix/X99 ]] && break
    sleep 0.1
done
[[ -S /tmp/.X11-unix/X99 ]] || {
    cat "$WORK_DIR/xvfb.log" >&2
    exit 1
}

weston \
    --backend=x11 \
    --renderer=pixman \
    --socket="$WAYLAND_DISPLAY" \
    --idle-time=0 \
    --no-config \
    --width=800 \
    --height=600 \
    --log="$WORK_DIR/weston.log" &
WESTON_PID="$!"
for _ in $(seq 1 100); do
    [[ -S "$XDG_RUNTIME_DIR/$WAYLAND_DISPLAY" ]] && break
    sleep 0.1
done
[[ -S "$XDG_RUNTIME_DIR/$WAYLAND_DISPLAY" ]] || {
    cat "$WORK_DIR/weston.log" >&2
    exit 1
}

cc \
    -shared \
    -fPIC \
    -Wall \
    -Wextra \
    -Werror \
    -I"$WORK_DIR/jbr/include" \
    -I"$WORK_DIR/jbr/include/linux" \
    -I"$NATIVE_DIR" \
    "$NATIVE_DIR/JbrWaylandSurface.c" \
    "$NATIVE_DIR/WaylandOverlaySurface.c" \
    "$NATIVE_DIR/WaylandColorProbe.c" \
    "$TEST_DIR/JbrWaylandSurfaceSmokeTest.c" \
    $(pkg-config --cflags --libs wayland-client) \
    -ldl \
    -pthread \
    -o "$WORK_DIR/libJbrWaylandSurfaceSmokeTest.so"

"$WORK_DIR/jbr/bin/javac" \
    -d "$WORK_DIR/classes" \
    "$TEST_DIR/JbrWaylandSurfaceSmokeTest.java"
"$WORK_DIR/jbr/bin/java" \
    --enable-native-access=ALL-UNNAMED \
    -Dawt.toolkit.name=WLToolkit \
    -cp "$WORK_DIR/classes" \
    JbrWaylandSurfaceSmokeTest \
    "$WORK_DIR/libJbrWaylandSurfaceSmokeTest.so"
