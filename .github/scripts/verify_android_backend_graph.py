#!/usr/bin/env python3
# SPDX-License-Identifier: LicenseRef-KMediaPlayer-Internal

"""Verify three optional clients and one split shared ARM runtime graph."""

from __future__ import annotations

import argparse
import json
import re
import struct
import zipfile
from pathlib import Path


ABIS = {"arm64-v8a", "armeabi-v7a"}
ASS_RUNTIME = {
    "libkmediaffmpeg_ass.so",
    "libkmediaffmpeg_ass_probe.so",
    "libkmediaffmpeg_freetype.so",
    "libkmediaffmpeg_fribidi.so",
    "libkmediaffmpeg_harfbuzz.so",
}
FFMPEG_RUNTIME = {
    "libkmediaffmpeg_avcodec.so",
    "libkmediaffmpeg_avfilter.so",
    "libkmediaffmpeg_avformat.so",
    "libkmediaffmpeg_avutil.so",
    "libkmediaffmpeg_probe.so",
    "libkmediaffmpeg_swresample.so",
    "libkmediaffmpeg_swscale.so",
}
SHARED = ASS_RUNTIME | FFMPEG_RUNTIME
MPV = {"libkmediampv_jni.so", "libkmediampv_mpv.so", "libkmediampv_placebo.so"}
BRIDGE = {"libkmediabridge.so"}
ASS_CLIENT = {"libkmediaass.so"}
OWNED = SHARED | MPV | BRIDGE | ASS_CLIENT
LEGACY = re.compile(
    r"(?:libkmediampv_av|libav[^/]*-kmb|libkmediabridge_av|"
    r"libkmediaasscore|libkmediafribidi)",
)
NATIVE_ENTRY = re.compile(r"(?:^|/)lib/([^/]+)/([^/]+\.so)$")
ELF_MACHINE = {
    "arm64-v8a": 183,
    "armeabi-v7a": 40,
}
FORBIDDEN_ASS_CLIENT_NEEDED = {
    "libass.so",
    "libfribidi.so",
    "libkmediaasscore.so",
    "libkmediafribidi.so",
}


def _elf_needed_libraries(payload: bytes) -> tuple[int, set[str]]:
    if len(payload) < 64 or payload[:4] != b"\x7fELF":
        raise ValueError("ASS client is not an ELF library")
    elf_class = payload[4]
    if payload[5] != 1:
        raise ValueError("ASS client must use little-endian ELF")
    if elf_class == 1:
        bits = 32
        header_size = 52
        section_offset = struct.unpack_from("<I", payload, 32)[0]
        section_entry_size = struct.unpack_from("<H", payload, 46)[0]
        section_count = struct.unpack_from("<H", payload, 48)[0]
        section_layout = "<IIIIIIIIII"
        dynamic_layout = "<iI"
        dynamic_type_index = 1
        dynamic_offset_index = 4
        dynamic_size_index = 5
        dynamic_link_index = 6
        dynamic_entry_size_index = 9
    elif elf_class == 2:
        bits = 64
        header_size = 64
        section_offset = struct.unpack_from("<Q", payload, 40)[0]
        section_entry_size = struct.unpack_from("<H", payload, 58)[0]
        section_count = struct.unpack_from("<H", payload, 60)[0]
        section_layout = "<IIQQQQIIQQ"
        dynamic_layout = "<qQ"
        dynamic_type_index = 1
        dynamic_offset_index = 4
        dynamic_size_index = 5
        dynamic_link_index = 6
        dynamic_entry_size_index = 9
    else:
        raise ValueError(f"ASS client has unsupported ELF class {elf_class}")

    expected_section_size = struct.calcsize(section_layout)
    if (
        len(payload) < header_size
        or section_count == 0
        or section_entry_size < expected_section_size
        or section_offset + section_entry_size * section_count > len(payload)
    ):
        raise ValueError("ASS client has an invalid ELF section table")

    sections: list[tuple[int, ...]] = []
    for index in range(section_count):
        offset = section_offset + index * section_entry_size
        sections.append(struct.unpack_from(section_layout, payload, offset))
    dynamic_sections = [
        section for section in sections if section[dynamic_type_index] == 6
    ]
    if len(dynamic_sections) != 1:
        raise ValueError("ASS client must contain exactly one ELF dynamic section")

    dynamic = dynamic_sections[0]
    string_table_index = dynamic[dynamic_link_index]
    if string_table_index >= len(sections):
        raise ValueError("ASS client dynamic string-table link is invalid")
    string_table = sections[string_table_index]
    string_offset = string_table[dynamic_offset_index]
    string_size = string_table[dynamic_size_index]
    if string_offset + string_size > len(payload):
        raise ValueError("ASS client dynamic string table is out of bounds")
    strings = payload[string_offset : string_offset + string_size]

    dynamic_offset = dynamic[dynamic_offset_index]
    dynamic_size = dynamic[dynamic_size_index]
    dynamic_entry_size = dynamic[dynamic_entry_size_index]
    expected_dynamic_size = struct.calcsize(dynamic_layout)
    if (
        dynamic_entry_size < expected_dynamic_size
        or dynamic_size % dynamic_entry_size != 0
        or dynamic_offset + dynamic_size > len(payload)
    ):
        raise ValueError("ASS client has an invalid ELF dynamic table")

    needed: set[str] = set()
    for offset in range(dynamic_offset, dynamic_offset + dynamic_size, dynamic_entry_size):
        tag, value = struct.unpack_from(dynamic_layout, payload, offset)
        if tag == 0:
            break
        if tag != 1:
            continue
        if value >= len(strings):
            raise ValueError("ASS client DT_NEEDED string is out of bounds")
        end = strings.find(b"\0", value)
        if end < 0:
            raise ValueError("ASS client DT_NEEDED string is unterminated")
        needed.add(strings[value:end].decode("utf-8"))
    return bits, needed


def verify(archive_path: Path) -> dict[str, object]:
    if not archive_path.is_file() or archive_path.is_symlink():
        raise ValueError("archive must be a real APK or AAB")
    by_abi: dict[str, list[tuple[str, int]]] = {}
    ass_client_needed: dict[str, list[str]] = {}
    with zipfile.ZipFile(archive_path) as archive:
        for info in archive.infolist():
            match = NATIVE_ENTRY.search(info.filename)
            if not match or info.is_dir():
                continue
            abi, name = match.groups()
            by_abi.setdefault(abi, []).append((name, info.file_size))
            if LEGACY.search(name):
                raise ValueError(f"legacy duplicated FFmpeg library is present: {name}")
            if name == "libkmediaass.so":
                bits, needed = _elf_needed_libraries(archive.read(info))
                expected_bits = 64 if abi == "arm64-v8a" else 32
                if bits != expected_bits or ELF_MACHINE.get(abi) is None:
                    raise ValueError(f"{abi} ASS client has another ELF class")
                machine = struct.unpack_from("<H", archive.read(info), 18)[0]
                if machine != ELF_MACHINE[abi]:
                    raise ValueError(f"{abi} ASS client has another ELF machine")
                if "libkmediaffmpeg_ass.so" not in needed:
                    raise ValueError(f"{abi} ASS client does not link the shared libass")
                forbidden = sorted(needed & FORBIDDEN_ASS_CLIENT_NEEDED)
                if forbidden:
                    raise ValueError(
                        f"{abi} ASS client links a private text runtime: {forbidden}",
                    )
                ass_client_needed[abi] = sorted(needed)
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
        "assRuntimeLibrariesPerAbi": len(ASS_RUNTIME),
        "ffmpegRuntimeLibrariesPerAbi": len(FFMPEG_RUNTIME),
        "sharedRuntimeLibrariesPerAbi": len(SHARED),
        "assClientLibrariesPerAbi": len(ASS_CLIENT),
        "mpvClientLibrariesPerAbi": len(MPV),
        "bridgeClientLibrariesPerAbi": len(BRIDGE),
        "assClientNeededByAbi": ass_client_needed,
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
