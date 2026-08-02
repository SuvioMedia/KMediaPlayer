#!/usr/bin/env python3
"""Verify that a packaged Windows native runtime has a closed DLL dependency graph."""

from __future__ import annotations

import argparse
import os
import re
import subprocess
import sys
import tempfile
import zipfile
from collections.abc import Callable, Iterable
from pathlib import Path


DEFAULT_RUNTIME_PREFIX = "META-INF/kmediaass/native/windows-x86_64/lib/"
WINDOWS_API_SET_PREFIXES = ("api-ms-win-", "ext-ms-win-")
DLL_IMPORT_PATTERN = re.compile(r"^\s*([A-Za-z0-9_.+-]+\.dll)\s*$", re.IGNORECASE)
VSWHERE_RELATIVE_PATH = Path("Microsoft Visual Studio/Installer/vswhere.exe")
VS_COMPONENT = "Microsoft.VisualStudio.Component.VC.Tools.x86.x64"
VS_DUMPBIN_PATTERN = r"VC\Tools\MSVC\**\bin\Hostx64\x64\dumpbin.exe"


class DllClosureError(RuntimeError):
    """Raised when the runtime archive cannot prove its Windows DLL closure."""


def parse_dumpbin_dependencies(output: str) -> set[str]:
    """Extract imported DLL basenames from ``dumpbin /dependents`` output."""
    dependencies: set[str] = set()
    for line in output.splitlines():
        match = DLL_IMPORT_PATTERN.fullmatch(line)
        if match:
            dependencies.add(match.group(1))
    return dependencies


def locate_dumpbin(explicit_path: Path | None = None) -> Path:
    """Locate dumpbin through Visual Studio metadata without consulting PATH."""
    if explicit_path is not None:
        resolved = explicit_path.resolve(strict=True)
        if not resolved.is_file():
            raise DllClosureError(f"dumpbin is not a file: {resolved}")
        return resolved

    program_files_x86 = os.environ.get("ProgramFiles(x86)")
    if not program_files_x86:
        raise DllClosureError("ProgramFiles(x86) is unavailable; cannot locate Visual Studio.")
    vswhere = Path(program_files_x86) / VSWHERE_RELATIVE_PATH
    if not vswhere.is_file():
        raise DllClosureError(f"Visual Studio locator is missing: {vswhere}")

    result = subprocess.run(
        [
            str(vswhere),
            "-latest",
            "-products",
            "*",
            "-requires",
            VS_COMPONENT,
            "-find",
            VS_DUMPBIN_PATTERN,
        ],
        check=False,
        capture_output=True,
        text=True,
        errors="replace",
    )
    candidates = [Path(line.strip()) for line in result.stdout.splitlines() if line.strip()]
    if result.returncode != 0 or not candidates:
        detail = result.stderr.strip() or "no matching Visual Studio installation"
        raise DllClosureError(f"Unable to locate dumpbin with vswhere: {detail}")
    resolved = candidates[0].resolve(strict=True)
    if not resolved.is_file():
        raise DllClosureError(f"vswhere returned a non-file dumpbin path: {resolved}")
    return resolved


def inspect_dependencies(dumpbin_path: Path, dll_path: Path) -> set[str]:
    """Read the direct PE imports of one DLL."""
    result = subprocess.run(
        [str(dumpbin_path), "/nologo", "/dependents", str(dll_path)],
        check=False,
        capture_output=True,
        text=True,
        errors="replace",
    )
    if result.returncode != 0:
        detail = result.stderr.strip() or result.stdout.strip() or "unknown dumpbin failure"
        raise DllClosureError(f"dumpbin failed for {dll_path.name}: {detail}")
    return parse_dumpbin_dependencies(result.stdout)


def is_windows_system_dependency(dependency: str, system_root: Path) -> bool:
    """Accept Windows API-set contracts and concrete 64-bit System32 libraries."""
    normalized = dependency.casefold()
    if normalized.startswith(WINDOWS_API_SET_PREFIXES):
        return True
    return (system_root / "System32" / dependency).is_file()


def extract_packaged_dlls(
    archive_path: Path,
    destination: Path,
    runtime_prefix: str = DEFAULT_RUNTIME_PREFIX,
) -> list[Path]:
    """Extract only runtime DLLs, rejecting ambiguous duplicate basenames."""
    extracted: list[Path] = []
    seen_names: set[str] = set()
    with zipfile.ZipFile(archive_path) as archive:
        entries = [
            entry
            for entry in archive.infolist()
            if not entry.is_dir()
            and entry.filename.startswith(runtime_prefix)
            and entry.filename.casefold().endswith(".dll")
        ]
        if not entries:
            raise DllClosureError(
                f"No Windows runtime DLLs found below {runtime_prefix!r} in {archive_path}."
            )
        for entry in entries:
            file_name = Path(entry.filename).name
            normalized_name = file_name.casefold()
            if normalized_name in seen_names:
                raise DllClosureError(f"Duplicate packaged DLL basename: {file_name}")
            seen_names.add(normalized_name)
            target = destination / file_name
            with archive.open(entry) as source, target.open("wb") as output:
                while chunk := source.read(1024 * 1024):
                    output.write(chunk)
            extracted.append(target)
    return sorted(extracted, key=lambda path: path.name.casefold())


def verify_archive(
    archive_path: Path,
    dumpbin_path: Path,
    system_root: Path,
    runtime_prefix: str = DEFAULT_RUNTIME_PREFIX,
    dependency_reader: Callable[[Path, Path], Iterable[str]] | None = None,
) -> tuple[int, int]:
    """Verify one archive while preserving the original single-runtime API."""
    return verify_archives(
        archives=[(archive_path, runtime_prefix)],
        dumpbin_path=dumpbin_path,
        system_root=system_root,
        dependency_reader=dependency_reader,
    )


def verify_archives(
    archives: Iterable[tuple[Path, str]],
    dumpbin_path: Path,
    system_root: Path,
    dependency_reader: Callable[[Path, Path], Iterable[str]] | None = None,
) -> tuple[int, int]:
    """Verify the combined DLL closure of one or more runtime archives."""
    reader = dependency_reader or inspect_dependencies
    missing_edges: set[tuple[str, str]] = set()
    checked_imports = 0
    with tempfile.TemporaryDirectory(prefix="kmediaplayer-dll-closure-") as temporary:
        packaged_paths: list[Path] = []
        packaged_names: set[str] = set()
        archive_count = 0
        for archive_count, (archive_path, runtime_prefix) in enumerate(archives, start=1):
            destination = Path(temporary) / f"archive-{archive_count}"
            destination.mkdir()
            extracted = extract_packaged_dlls(archive_path, destination, runtime_prefix)
            for dll_path in extracted:
                normalized_name = dll_path.name.casefold()
                if normalized_name in packaged_names:
                    raise DllClosureError(
                        f"Duplicate packaged DLL basename across runtime archives: {dll_path.name}"
                    )
                packaged_names.add(normalized_name)
                packaged_paths.append(dll_path)
        if archive_count == 0:
            raise DllClosureError("At least one Windows runtime archive is required.")
        for dll_path in packaged_paths:
            for dependency in reader(dumpbin_path, dll_path):
                checked_imports += 1
                if dependency.casefold() in packaged_names:
                    continue
                if is_windows_system_dependency(dependency, system_root):
                    continue
                missing_edges.add((dll_path.name, dependency))

    if checked_imports == 0:
        raise DllClosureError(
            "dumpbin reported no DLL imports; refusing to accept an unverified dependency graph."
        )
    if missing_edges:
        details = "\n".join(
            f"  {source} -> {dependency}"
            for source, dependency in sorted(
                missing_edges,
                key=lambda edge: (edge[0].casefold(), edge[1].casefold()),
            )
        )
        raise DllClosureError(
            "Unpackaged non-system Windows DLL dependencies:\n"
            f"{details}\n"
            "Dependencies found only through PATH are deliberately rejected."
        )
    return len(packaged_paths), checked_imports


def parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--jar",
        required=True,
        action="append",
        type=Path,
        help="Published runtime JAR to inspect; repeat to verify a combined graph.",
    )
    parser.add_argument("--dumpbin", type=Path, help="Optional explicit dumpbin.exe path.")
    parser.add_argument(
        "--runtime-prefix",
        action="append",
        help=(
            "Archive prefix containing the Windows runtime DLLs; when supplied, repeat once "
            "for every --jar in the same order."
        ),
    )
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv or sys.argv[1:])
    try:
        archive_paths = [archive.resolve(strict=True) for archive in args.jar]
        runtime_prefixes = args.runtime_prefix or [DEFAULT_RUNTIME_PREFIX] * len(archive_paths)
        if len(runtime_prefixes) != len(archive_paths):
            raise DllClosureError(
                "The number of --runtime-prefix values must match the number of --jar values."
            )
        system_root_value = os.environ.get("SystemRoot")
        if not system_root_value:
            raise DllClosureError("SystemRoot is unavailable; cannot classify Windows system DLLs.")
        system_root = Path(system_root_value).resolve(strict=True)
        dumpbin_path = locate_dumpbin(args.dumpbin)
        packaged_count, import_count = verify_archives(
            archives=list(zip(archive_paths, runtime_prefixes, strict=True)),
            dumpbin_path=dumpbin_path,
            system_root=system_root,
        )
    except (DllClosureError, OSError, zipfile.BadZipFile) as failure:
        print(f"Windows DLL closure verification failed: {failure}", file=sys.stderr)
        return 1

    print(
        f"Windows DLL closure verified: {packaged_count} packaged DLLs, "
        f"{import_count} direct imports."
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
