#!/usr/bin/env bash
# SPDX-License-Identifier: LicenseRef-KMediaPlayer-Proprietary

set -euo pipefail

if [[ $# -ne 3 ]]; then
    echo "usage: $0 <test-kexe> <maven-simulator-frameworks-directory> <new-work-directory>" >&2
    exit 2
fi

test_executable="$1"
frameworks="$2"
work_directory="$3"
apple_directory="$(cd "${BASH_SOURCE[0]%/*}" && pwd -P)"

if [[ "$(uname -s)" != "Darwin" ]]; then
    echo "the iOS simulator integration test must run on macOS" >&2
    exit 2
fi

simulator_udid="$({ /usr/bin/xcrun simctl list devices booted -j || true; } | /usr/bin/python3 -c '
import json, sys
try:
    data = json.load(sys.stdin)
except json.JSONDecodeError:
    raise SystemExit(0)
devices = [device for values in data.get("devices", {}).values() for device in values]
booted = [device for device in devices if device.get("state") == "Booted" and device.get("isAvailable", True)]
preferred = next((device for device in booted if "iPhone" in device.get("name", "")), None)
selected = preferred or (booted[0] if booted else None)
if selected:
    print(selected["udid"])
')"

if [[ -z "$simulator_udid" ]]; then
    simulator_udid="$(/usr/bin/xcrun simctl list devices available -j | /usr/bin/python3 -c '
import json, sys
data = json.load(sys.stdin)
devices = [device for values in data.get("devices", {}).values() for device in values]
available = [device for device in devices if device.get("state") == "Shutdown" and device.get("isAvailable", True)]
preferred = next((device for device in available if "iPhone" in device.get("name", "")), None)
selected = preferred or (available[0] if available else None)
if selected:
    print(selected["udid"])
')"
    if [[ -z "$simulator_udid" ]]; then
        echo "no available iOS simulator device was found" >&2
        exit 1
    fi
    /usr/bin/xcrun simctl boot "$simulator_udid"
    /usr/bin/xcrun simctl bootstatus "$simulator_udid" -b
fi

/bin/bash "$apple_directory/run-ios-simulator-integration-test.sh" \
    "$test_executable" \
    "$frameworks" \
    "$work_directory" \
    "$simulator_udid"
