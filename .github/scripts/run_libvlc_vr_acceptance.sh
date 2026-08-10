#!/usr/bin/env bash

set -euo pipefail

fail() {
    echo "libVLC VR acceptance: $*" >&2
    exit 1
}

if [[ $# -ne 5 ]]; then
    echo "usage: $0 <KMediaVlc-checkout> <relocated-runtime> <fixture> <KMediaPlayer-commit> <KMediaVlc-commit>" >&2
    exit 64
fi

readonly script_directory="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P)"
readonly player_checkout="$(CDPATH= cd -- "$script_directory/../.." && pwd -P)"
readonly vlc_checkout_input="$1"
readonly runtime_input="$2"
readonly fixture_input="$3"
readonly expected_player_commit="$4"
readonly expected_vlc_commit="$5"

[[ -d "$vlc_checkout_input" && ! -L "$vlc_checkout_input" ]] ||
    fail "KMediaVlc checkout must be a real directory, not a symlink."
[[ -d "$runtime_input" && ! -L "$runtime_input" ]] ||
    fail "relocated runtime must be a real directory, not a symlink."
[[ -f "$fixture_input" && ! -L "$fixture_input" ]] ||
    fail "fixture must be a real file, not a symlink."
[[ "$expected_player_commit" =~ ^[0-9a-f]{40}$ ]] ||
    fail "KMediaPlayer commit must be a full lowercase 40-character hash."
[[ "$expected_vlc_commit" =~ ^[0-9a-f]{40}$ ]] ||
    fail "KMediaVlc commit must be a full lowercase 40-character hash."

readonly vlc_checkout="$(CDPATH= cd -- "$vlc_checkout_input" && pwd -P)"
readonly runtime_directory="$(CDPATH= cd -- "$runtime_input" && pwd -P)"
readonly fixture_directory="$(CDPATH= cd -- "$(dirname -- "$fixture_input")" && pwd -P)"
readonly fixture="$fixture_directory/$(basename -- "$fixture_input")"

case "$(uname -s)" in
    Darwin)
        readonly bridge="$runtime_directory/bin/libkmediavlc_bridge.dylib"
        readonly libvlc="$runtime_directory/bin/libvlc.12.dylib"
        ;;
    Linux)
        readonly bridge="$runtime_directory/bin/libkmediavlc_bridge.so"
        readonly libvlc="$runtime_directory/bin/libvlc.so.12"
        ;;
    MINGW* | MSYS* | CYGWIN*)
        readonly bridge="$runtime_directory/bin/kmediavlc_bridge.dll"
        readonly libvlc="$runtime_directory/bin/libvlc.dll"
        ;;
    *)
        fail "unsupported desktop host: $(uname -s)"
        ;;
esac

[[ -f "$bridge" && ! -L "$bridge" ]] || fail "bundled platform bridge is missing or is a symlink."
[[ -f "$libvlc" && ! -L "$libvlc" ]] || fail "bundled libVLC is missing or is a symlink."
[[ -d "$runtime_directory/lib/vlc/plugins" && ! -L "$runtime_directory/lib/vlc/plugins" ]] ||
    fail "bundled VLC plugin directory is missing or is a symlink."

require_clean_commit() {
    local checkout="$1"
    local expected_commit="$2"
    local name="$3"
    local actual_commit

    actual_commit="$(git -C "$checkout" rev-parse --verify HEAD^{commit})" ||
        fail "$name checkout does not have a valid HEAD commit."
    [[ "$actual_commit" == "$expected_commit" ]] ||
        fail "$name commit mismatch: expected $expected_commit, found $actual_commit."
    git -C "$checkout" diff --quiet --ignore-submodules -- || fail "$name checkout has unstaged changes."
    git -C "$checkout" diff --cached --quiet --ignore-submodules -- || fail "$name checkout has staged changes."
    [[ -z "$(git -C "$checkout" ls-files --others --exclude-standard)" ]] ||
        fail "$name checkout has untracked files."
}

require_clean_commit "$player_checkout" "$expected_player_commit" "KMediaPlayer"
require_clean_commit "$vlc_checkout" "$expected_vlc_commit" "KMediaVlc"

cd "$player_checkout"
exec bash ./gradlew \
    :mediaplayer-libvlc:jvmTest \
    --tests 'io.github.kdroidfilter.composemediaplayer.libvlc.RealLibVlcVrProjectionIntegrationTest' \
    "-PkmediaVlcProjectDir=$vlc_checkout" \
    -PkmediaVlcDesktopOnly=true \
    "-PkmediaVlcVrRuntimeDirectory=$runtime_directory" \
    "-PkmediaVlcVrBridgePath=$bridge" \
    "-PkmediaVlcVrFixture=$fixture" \
    --no-daemon \
    --no-configuration-cache \
    --rerun-tasks
