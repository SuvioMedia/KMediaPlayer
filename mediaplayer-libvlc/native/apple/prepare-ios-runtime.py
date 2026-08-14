#!/usr/bin/env python3
# SPDX-License-Identifier: LicenseRef-KMediaPlayer-Proprietary

from __future__ import annotations

import argparse
import hashlib
import json
import os
import shutil
import stat
import zipfile
from pathlib import Path, PurePosixPath


EXPECTED_FRAMEWORKS = 87
MAX_FILES = 10_000
MAX_TOTAL_BYTES = 2 * 1024 * 1024 * 1024


def fail(message: str) -> None:
    raise ValueError(message)


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def safe_members(archive: zipfile.ZipFile) -> dict[str, zipfile.ZipInfo]:
    members = archive.infolist()
    if not members or len(members) > MAX_FILES:
        fail("The iOS KMediaVlc archive has an invalid file count.")
    result: dict[str, zipfile.ZipInfo] = {}
    total = 0
    for member in members:
        path = PurePosixPath(member.filename)
        file_type = stat.S_IFMT(member.external_attr >> 16)
        if (
            not member.filename
            or "\\" in member.filename
            or path.is_absolute()
            or any(part in {"", ".", ".."} for part in path.parts)
            or member.filename in result
            or member.is_dir()
            or file_type not in {0, stat.S_IFREG}
            or member.file_size < 0
        ):
            fail("The iOS KMediaVlc archive contains an unsafe member.")
        total += member.file_size
        if total > MAX_TOTAL_BYTES:
            fail("The iOS KMediaVlc archive is oversized.")
        result[member.filename] = member
    return result


def load_inventory(archive: zipfile.ZipFile, members: dict[str, zipfile.ZipInfo]) -> dict:
    member = members.get("compliance/xcframework-inventory.json")
    if member is None or member.file_size > 16 * 1024 * 1024:
        fail("The iOS KMediaVlc archive omits its bounded framework inventory.")
    try:
        value = json.loads(archive.read(member).decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise ValueError("The iOS KMediaVlc framework inventory is invalid.") from error
    if (
        not isinstance(value, dict)
        or value.get("schemaVersion") != 1
        or value.get("frameworkCount") != EXPECTED_FRAMEWORKS
        or not isinstance(value.get("frameworks"), list)
        or len(value["frameworks"]) != EXPECTED_FRAMEWORKS
    ):
        fail("The iOS KMediaVlc framework inventory is incomplete.")
    return value


def extract_framework(
    archive: zipfile.ZipFile,
    members: dict[str, zipfile.ZipInfo],
    record: dict,
    variant: str,
    output: Path,
) -> dict[str, str | int]:
    name = record.get("frameworkName")
    xcframework = record.get("xcframework")
    slices = record.get("slices")
    if (
        not isinstance(name, str)
        or not name
        or not name.replace("_", "a").isalnum()
        or xcframework != f"{name}.xcframework"
        or not isinstance(slices, list)
    ):
        fail("The iOS KMediaVlc inventory contains an invalid framework record.")
    selected = [item for item in slices if isinstance(item, dict) and item.get("variant") == variant]
    if len(selected) != 1:
        fail(f"The iOS KMediaVlc archive omits the {variant} slice for {name}.")
    selected_slice = selected[0]
    identifier = selected_slice.get("identifier")
    expected_hash = selected_slice.get("sha256")
    expected_size = selected_slice.get("size")
    if (
        not isinstance(identifier, str)
        or PurePosixPath(identifier).name != identifier
        or not isinstance(expected_hash, str)
        or len(expected_hash) != 64
        or not isinstance(expected_size, int)
        or expected_size <= 0
    ):
        fail("The iOS KMediaVlc slice identity is invalid.")
    prefix = f"Frameworks/{xcframework}/{identifier}/{name}.framework/"
    selected_members = sorted(
        (path, member) for path, member in members.items() if path.startswith(prefix)
    )
    if not selected_members:
        fail(f"The iOS KMediaVlc archive omits {name}.framework.")
    framework_output = output / "Frameworks" / f"{name}.framework"
    for path, member in selected_members:
        relative = PurePosixPath(path.removeprefix(prefix))
        if not relative.parts or any(part in {"", ".", ".."} for part in relative.parts):
            fail("The iOS KMediaVlc framework contains an unsafe path.")
        destination = framework_output.joinpath(*relative.parts)
        destination.parent.mkdir(parents=True, exist_ok=True)
        with archive.open(member) as source, destination.open("xb") as target:
            shutil.copyfileobj(source, target, length=1024 * 1024)
        os.chmod(destination, 0o755 if relative.as_posix() == name else 0o644)
    binary = framework_output / name
    if (
        not binary.is_file()
        or binary.stat().st_size != expected_size
        or sha256(binary) != expected_hash
        or not (framework_output / "Info.plist").is_file()
    ):
        fail(f"The extracted iOS KMediaVlc framework differs from its inventory: {name}.")
    return {"framework": f"{name}.framework", "sha256": expected_hash, "size": expected_size}


def prepare(
    archive_path: Path,
    expected_version: str,
    expected_client_header: Path,
    output: Path,
) -> None:
    if archive_path.is_symlink() or not archive_path.is_file():
        fail("The resolved iOS KMediaVlc Maven artifact must be a real file.")
    if expected_client_header.is_symlink() or not expected_client_header.is_file():
        fail("The pinned KMediaVlc client header must be a real file.")
    if output.exists() or output.is_symlink() or not output.parent.is_dir():
        fail("The iOS KMediaVlc output must be a new path with a real parent.")
    temporary = output.with_name(f".{output.name}.tmp")
    if temporary.exists() or temporary.is_symlink():
        fail("The temporary iOS KMediaVlc output already exists.")
    temporary.mkdir()
    try:
        with zipfile.ZipFile(archive_path) as archive:
            members = safe_members(archive)
            inventory = load_inventory(archive, members)
            if inventory.get("version") != expected_version:
                fail("The iOS KMediaVlc Maven version differs from its embedded inventory.")
            records = [
                extract_framework(archive, members, record, "simulator", temporary)
                for record in inventory["frameworks"]
            ]
        names = [record["framework"] for record in records]
        if len(set(names)) != EXPECTED_FRAMEWORKS:
            fail("The selected iOS KMediaVlc framework set contains a duplicate.")
        runtime_header = temporary / "Frameworks/KMediaVlc.framework/Headers/kmediavlc_client.h"
        if runtime_header.read_bytes() != expected_client_header.read_bytes():
            fail("KMediaPlayer's KMediaVlc client header differs from the Maven runtime ABI.")
        manifest = {
            "auditCandidate": inventory.get("auditCandidate"),
            "frameworkCount": EXPECTED_FRAMEWORKS,
            "frameworks": sorted(records, key=lambda item: item["framework"]),
            "platform": "iphonesimulator",
            "schemaVersion": 1,
            "version": expected_version,
        }
        (temporary / "runtime-manifest.json").write_text(
            json.dumps(manifest, indent=2, sort_keys=True) + "\n",
            encoding="utf-8",
        )
        temporary.rename(output)
    except BaseException:
        if temporary.exists() and not temporary.is_symlink():
            shutil.rmtree(temporary)
        raise


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--archive", type=Path, required=True)
    parser.add_argument("--platform", choices=("iphonesimulator",), required=True)
    parser.add_argument("--expected-version", required=True)
    parser.add_argument("--client-header", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    arguments = parser.parse_args()
    prepare(
        arguments.archive.resolve(strict=True),
        arguments.expected_version,
        arguments.client_header.resolve(strict=True),
        arguments.output.absolute(),
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
