#!/usr/bin/env bash
set -euo pipefail

root="${1:-mediaplayer-mpv/build}"
mpv_version="${KMEDIA_MPV_VERSION:-0.3.0-rc.15}"
runtime_version="${KMEDIA_FFMPEG_RUNTIME_VERSION:-0.1.0-rc.10}"
mpv="$root/kmediaMpvPod"
ffmpeg="$root/kmediaFfmpegRuntimePod"
ass="$root/kmediaAssRuntimePod"

test -f "$mpv/KMediaMpv.podspec"
test -f "$ffmpeg/KMediaFfmpegRuntime.podspec"
test -f "$ass/KMediaAssRuntime.podspec"
grep -F "spec.version             = '$mpv_version'" "$mpv/KMediaMpv.podspec"
grep -F "spec.dependency 'KMediaFfmpegRuntime', '= $runtime_version'" "$mpv/KMediaMpv.podspec"
grep -F "spec.version              = '$runtime_version'" "$ffmpeg/KMediaFfmpegRuntime.podspec"
grep -F "spec.dependency           'KMediaAssRuntime', '= $runtime_version'" \
  "$ffmpeg/KMediaFfmpegRuntime.podspec"
grep -F "spec.version              = '$runtime_version'" "$ass/KMediaAssRuntime.podspec"

expected_mpv=$'KMediaMpv\nKMediaMpvMoltenVK\nKMediaMpvPlacebo'
expected_ffmpeg=$'KMediaFfmpegAvcodec\nKMediaFfmpegAvfilter\nKMediaFfmpegAvformat\nKMediaFfmpegAvutil\nKMediaFfmpegRuntime\nKMediaFfmpegSwresample\nKMediaFfmpegSwscale'
expected_ass=$'KMediaAssRuntime\nKMediaFfmpegAss\nKMediaFfmpegFreetype\nKMediaFfmpegFribidi\nKMediaFfmpegHarfbuzz'

verify_framework_set() {
  local directory="$1"
  local expected="$2"
  local actual
  actual="$(
    find "$directory/Frameworks" -mindepth 1 -maxdepth 1 -type d -name '*.xcframework' \
      -exec basename {} .xcframework \; | sort
  )"
  test "$actual" = "$expected" || {
    echo "Unexpected Apple framework inventory below $directory:" >&2
    printf '%s\n' "$actual" >&2
    exit 1
  }

  while IFS= read -r xcframework; do
    name="$(basename "$xcframework" .xcframework)"
    binary_count="$(find "$xcframework" -type f -name "$name" | wc -l | tr -d ' ')"
    test "$binary_count" -eq 2 || {
      echo "$name must contain exactly ARM64 device and simulator binaries." >&2
      exit 1
    }
    while IFS= read -r binary; do
      test "$(lipo -archs "$binary")" = arm64 || {
        echo "Unexpected architecture in $binary" >&2
        exit 1
      }
    done < <(find "$xcframework" -type f -name "$name" | sort)
  done < <(find "$directory/Frameworks" -mindepth 1 -maxdepth 1 -type d -name '*.xcframework' | sort)
}

verify_framework_set "$mpv" "$expected_mpv"
verify_framework_set "$ffmpeg" "$expected_ffmpeg"
verify_framework_set "$ass" "$expected_ass"

grep -F "\"runtimeVersion\": \"$runtime_version\"" "$mpv/manifest.json"
grep -F "\"version\": \"$mpv_version\"" "$mpv/manifest.json"
if find "$mpv" "$ffmpeg" "$ass" -iname '*x86_64*' -print -quit | grep -q .; then
  echo "The Apple MPV pod graph contains an x86_64 slice." >&2
  exit 1
fi
