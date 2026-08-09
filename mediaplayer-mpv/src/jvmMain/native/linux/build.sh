#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "$0")" && pwd)"
jdk_home="$1"
resource_root="$2"
machine="$(uname -m)"

case "$machine" in
    x86_64|amd64) resource_arch="linux-x86-64" ;;
    aarch64|arm64) resource_arch="linux-arm64" ;;
    *) echo "Unsupported Linux architecture: $machine" >&2; exit 1 ;;
esac

build_dir="$script_dir/build-$machine"
cmake -S "$script_dir" -B "$build_dir" -DCMAKE_BUILD_TYPE=Release -DJAVA_HOME="$jdk_home"
cmake --build "$build_dir" --config Release --parallel
install_dir="$resource_root/composemediaplayer/native/$resource_arch"
mkdir -p "$install_dir"
cp "$build_dir/libComposeMediaPlayerMpvLinux.so" "$install_dir/libComposeMediaPlayerMpvLinux.so"
