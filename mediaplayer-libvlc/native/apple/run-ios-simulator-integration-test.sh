#!/usr/bin/env bash
# SPDX-License-Identifier: LicenseRef-KMediaPlayer-Proprietary

set -euo pipefail

readonly BUNDLE_ID="io.github.shusek.composemediaplayer.libvlc.tests"
readonly EXECUTABLE_NAME="ComposeMediaPlayerLibVlcTests"

if [[ $# -ne 4 ]]; then
    echo "usage: $0 <test-kexe> <simulator-frameworks-directory> <new-absolute-work-directory> <booted-simulator-udid>" >&2
    exit 2
fi

test_executable="$1"
frameworks="$2"
work_directory="$3"
simulator_udid="$4"
apple_directory="$(cd "${BASH_SOURCE[0]%/*}" && pwd -P)"
plist_file="$apple_directory/ios-test-app/Info.plist"

if [[ "$(uname -s)" != "Darwin" ]]; then
    echo "the iOS simulator integration test must run on macOS" >&2
    exit 2
fi
if [[ "$test_executable" != /* || "$frameworks" != /* ]] ||
   [[ ! -f "$test_executable" || -L "$test_executable" ]] ||
   [[ ! -d "$frameworks" || -L "$frameworks" ]]; then
    echo "the test executable and simulator framework directory must be safe absolute paths" >&2
    exit 2
fi
if [[ "$work_directory" != /* ]] || [[ -e "$work_directory" ]] ||
   [[ ! -d "$(dirname "$work_directory")" ]]; then
    echo "the integration work directory must be a new absolute path with an existing parent" >&2
    exit 2
fi
if [[ ! "$simulator_udid" =~ ^[0-9A-Fa-f-]{36}$ ]]; then
    echo "the simulator UDID is invalid" >&2
    exit 2
fi
if [[ ! -f "$plist_file" || -L "$plist_file" ]]; then
    echo "the checked-in test application plist is missing or unsafe" >&2
    exit 1
fi
for framework in KMediaVlc KMediaVlcLibVlc KMediaVlcCore libvmem_plugin libaudiounit_ios_plugin; do
    if [[ ! -f "$frameworks/$framework.framework/$framework" ]]; then
        echo "required simulator framework is missing: $framework" >&2
        exit 1
    fi
done
if ! /usr/bin/xcrun simctl list devices booted | /usr/bin/grep -Fq "$simulator_udid"; then
    echo "the requested simulator must already be booted" >&2
    exit 2
fi

/bin/mkdir "$work_directory"
app="$work_directory/ComposeMediaPlayerLibVlcTests.app"
/bin/mkdir "$app"
/bin/mkdir "$app/Frameworks"
/bin/cp "$plist_file" "$app/Info.plist"
/bin/cp "$test_executable" "$app/$EXECUTABLE_NAME"
/bin/chmod +x "$app/$EXECUTABLE_NAME"
/bin/cp -R "$frameworks/." "$app/Frameworks/"

while IFS= read -r framework; do
    /usr/bin/codesign --force --sign - --timestamp=none "$framework" >/dev/null
done < <(/usr/bin/find "$app/Frameworks" -mindepth 1 -maxdepth 1 -type d -name '*.framework' -print | /usr/bin/sort)
/usr/bin/codesign --force --sign - --timestamp=none "$app" >/dev/null

/usr/bin/xcrun simctl install "$simulator_udid" "$app"
result_file="$work_directory/test-output.txt"
set +e
/usr/bin/xcrun simctl launch --terminate-running-process --console "$simulator_udid" "$BUNDLE_ID" 2>&1 |
    /usr/bin/tee "$result_file"
launch_status="${PIPESTATUS[0]}"
set -e

if [[ "$launch_status" -ne 0 ]] ||
   /usr/bin/grep -Fq '[  FAILED  ]' "$result_file" ||
   ! /usr/bin/grep -Eq '^\[  PASSED  \] [1-9][0-9]* tests?\.$' "$result_file"; then
    echo "the iOS simulator integration test failed" >&2
    exit 1
fi
