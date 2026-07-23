#!/usr/bin/env python3
# SPDX-License-Identifier: LicenseRef-KMediaPlayer-Internal

"""Verify that an APK/AAB contains two clients and one shared ARM runtime graph."""

from __future__ import annotations

import argparse
import json
import re
import zipfile
from pathlib import Path


ABIS = {"arm64-v8a", "armeabi-v7a"}
SHARED = {
    "libkmediaffmpeg_ass.so",
    "libkmediaffmpeg_avcodec.so",
    "libkmediaffmpeg_avfilter.so",
    "libkmediaffmpeg_avformat.so",
    "libkmediaffmpeg_avutil.so",
    "libkmediaffmpeg_freetype.so",
    "libkmediaffmpeg_fribidi.so",
    "libkmediaffmpeg_harfbuzz.so",
    "libkmediaffmpeg_probe.so",
    "libkmediaffmpeg_swresample.so",
    "libkmediaffmpeg_swscale.so",
}
MPV = {"libkmediampv_jni.so", "libkmediampv_mpv.so", "libkmediampv_placebo.so"}
BRIDGE = {"libkmediabridge.so"}
OWNED = SHARED | MPV | BRIDGE
LEGACY = re.compile(r"(?:libkmediampv_av|libav[^/]*-kmb|libkmediabridge_av)")
NATIVE_ENTRY = re.compile(r"(?:^|/)lib/([^/]+)/([^/]+\.so)$")


def verify(archive_path: Path) -> dict[str, object]:
    if not archive_path.is_file() or archive_path.is_symlink():
        raise ValueError("archive must be a real APK or AAB")
    by_abi: dict[str, list[tuple[str, int]]] = {}
    with zipfile.ZipFile(archive_path) as archive:
        for info in archive.infolist():
            match = NATIVE_ENTRY.search(info.filename)
            if not match or info.is_dir():
                continue
            abi, name = match.groups()
            by_abi.setdefault(abi, []).append((name, info.file_size))
            if LEGACY.search(name):
                raise ValueError(f"legacy duplicated FFmpeg library is present: {name}")
    if set(by_abi) != ABIS:
        raise ValueError(f"native ABI set differs: {sorted(by_abi)}")
    graph_bytes: dict[str, int] = {}
    for abi in sorted(ABIS):
        entries = by_abi[abi]
        counts = {name: sum(1 for value, _ in entries if value == name) for name in OWNED}
        missing = sorted(name for name, count in counts.items() if count != 1)
        if missing:
            raise ValueError(f"{abi} must contain every shared/client library exactly once: {missing}")
        graph_bytes[abi] = sum(size for name, size in entries if name in OWNED)
    return {
        "schemaVersion": 1,
        "archiveBytes": archive_path.stat().st_size,
        "abis": sorted(ABIS),
        "sharedRuntimeLibrariesPerAbi": len(SHARED),
        "mpvClientLibrariesPerAbi": len(MPV),
        "bridgeClientLibrariesPerAbi": len(BRIDGE),
        "ownedGraphBytesPerAbi": graph_bytes,
        "singleSharedRuntimeGraph": True,
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--archive", type=Path, required=True)
    parser.add_argument("--report", type=Path, required=True)
    arguments = parser.parse_args()
    report = verify(arguments.archive.resolve())
    arguments.report.parent.mkdir(parents=True, exist_ok=True)
    arguments.report.write_text(json.dumps(report, indent=2, sort_keys=True) + "\n")
    print(json.dumps(report, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
