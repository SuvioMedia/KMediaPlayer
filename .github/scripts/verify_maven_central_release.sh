#!/usr/bin/env bash

set -euo pipefail

version="${1:-}"
repository="${2:-https://repo.maven.apache.org/maven2}"
max_attempts="${3:-180}"
poll_seconds="${4:-20}"

semver='^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)(-(0|[1-9][0-9]*|[0-9]*[A-Za-z-][0-9A-Za-z-]*)(\.(0|[1-9][0-9]*|[0-9]*[A-Za-z-][0-9A-Za-z-]*))*)?(\+[0-9A-Za-z-]+(\.[0-9A-Za-z-]+)*)?$'
if [[ ! "$version" =~ $semver ]]; then
  echo "Usage: $0 <immutable-semver> [repository-url] [max-attempts] [poll-seconds]" >&2
  exit 2
fi
if [[ ! "$max_attempts" =~ ^[1-9][0-9]*$ || ! "$poll_seconds" =~ ^[0-9]+$ ]]; then
  echo "Polling limits must be non-negative integers, with at least one attempt." >&2
  exit 2
fi

repository="${repository%/}"
group_path="io/github/shusek"
artifacts=(
  composemediaplayer-core
  composemediaplayer-ads-core
  composemediaplayer-ads-core-android
  composemediaplayer-ads-core-iosarm64
  composemediaplayer-ads-core-iossimulatorarm64
  composemediaplayer-ads-core-jvm
  composemediaplayer-ads-core-wasm-js
  composemediaplayer-desktop-tao
  composemediaplayer-desktop-tao-jvm
  composemediaplayer-extension-api
  composemediaplayer-extension-api-android
  composemediaplayer-extension-api-jvm
  composemediaplayer
  composemediaplayer-mpv
  composemediaplayer-mpv-android
  composemediaplayer-mpv-iosarm64
  composemediaplayer-mpv-iossimulatorarm64
  composemediaplayer-mpv-jvm
  composemediaplayer-ass
  composemediaplayer-ass-android
  composemediaplayer-ass-iosarm64
  composemediaplayer-ass-iossimulatorarm64
  composemediaplayer-ass-jvm
  composemediaplayer-dolbyvision
  composemediaplayer-dolbyvision-android
  composemediaplayer-dolbyvision-jvm
  composemediaplayer-kmediabridge
  composemediaplayer-kmediabridge-android
  composemediaplayer-kmediabridge-jvm
)

pom_url() {
  local artifact="$1"
  printf '%s/%s/%s/%s/%s-%s.pom' \
    "$repository" "$group_path" "$artifact" "$version" "$artifact" "$version"
}

missing_artifacts() {
  local artifact
  local missing=()
  for artifact in "${artifacts[@]}"; do
    if ! curl --fail --silent --show-error --location --head \
      --connect-timeout 10 --max-time 30 "$(pom_url "$artifact")" >/dev/null 2>&1; then
      missing+=("$artifact")
    fi
  done
  if (( ${#missing[@]} > 0 )); then
    printf '%s\n' "${missing[@]}"
  fi
}

for ((attempt = 1; attempt <= max_attempts; attempt++)); do
  missing=()
  while IFS= read -r artifact; do
    if [[ -n "$artifact" ]]; then
      missing+=("$artifact")
    fi
  done < <(missing_artifacts)
  if (( ${#missing[@]} == 0 )); then
    echo "All ${#artifacts[@]} KMediaPlayer $version POMs are available at $repository."
    break
  fi
  if (( attempt == max_attempts )); then
    echo "Maven Central publication $version was incomplete after $attempt attempts." >&2
    printf 'Missing artifact: %s\n' "${missing[@]}" >&2
    exit 1
  fi
  echo "Attempt $attempt/$max_attempts: waiting for ${#missing[@]} Maven Central POM(s)."
  sleep "$poll_seconds"
done

repository_root="$(cd "$(dirname "$0")/../.." && pwd)"
consumer_args=(
  -p "$repository_root/.github/public-maven-consumer"
  verifyPublicBackends
  "-PtestedVersion=$version"
  --refresh-dependencies
  --no-daemon
)
if [[ "$repository" != "https://repo.maven.apache.org/maven2" ]]; then
  consumer_args+=("-PpublicRepositoryUrl=$repository")
fi

"$repository_root/gradlew" "${consumer_args[@]}"
