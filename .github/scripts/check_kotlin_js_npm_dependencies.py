#!/usr/bin/env python3
"""Check literal Kotlin/JS npm dependencies against the npm latest dist-tag."""

from __future__ import annotations

import argparse
import json
import re
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Callable, Iterable
from urllib.parse import quote
from urllib.request import Request, urlopen


COMMENT_PATTERN = re.compile(r"//[^\n]*|/\*.*?\*/", re.DOTALL)
NPM_DECLARATION_PATTERN = re.compile(
    r"\b(?:devNpm|optionalNpm|peerNpm|npm)\s*\(\s*"
    r'"(?P<name>[^"\\]+)"\s*,\s*"(?P<version>[^"\\]+)"\s*,?\s*\)',
    re.DOTALL,
)
IGNORED_DIRECTORIES = {".git", ".gradle", "build"}
NPM_REGISTRY = "https://registry.npmjs.org"


@dataclass(frozen=True)
class NpmDependency:
    name: str
    version: str
    path: Path
    line: int


@dataclass(frozen=True)
class OutdatedDependency:
    dependency: NpmDependency
    latest_version: str


def _mask_comments(contents: str) -> str:
    return COMMENT_PATTERN.sub(
        lambda match: "".join(
            "\n" if character == "\n" else " " for character in match.group()
        ),
        contents,
    )


def parse_dependencies(path: Path) -> list[NpmDependency]:
    contents = _mask_comments(path.read_text(encoding="utf-8"))
    return [
        NpmDependency(
            name=match.group("name"),
            version=match.group("version"),
            path=path,
            line=contents.count("\n", 0, match.start()) + 1,
        )
        for match in NPM_DECLARATION_PATTERN.finditer(contents)
    ]


def _build_files(paths: Iterable[Path]) -> list[Path]:
    files: set[Path] = set()
    for path in paths:
        if path.is_file():
            files.add(path)
            continue
        if not path.is_dir():
            raise ValueError(f"Dependency path does not exist: {path}")
        files.update(
            candidate
            for candidate in path.rglob("*.gradle.kts")
            if not IGNORED_DIRECTORIES.intersection(candidate.relative_to(path).parts)
        )
    return sorted(files)


def discover_dependencies(paths: Iterable[Path]) -> list[NpmDependency]:
    return [
        dependency
        for path in _build_files(paths)
        for dependency in parse_dependencies(path)
    ]


def fetch_latest_version(package_name: str) -> str:
    encoded_name = quote(package_name, safe="")
    request = Request(
        f"{NPM_REGISTRY}/{encoded_name}/latest",
        headers={
            "Accept": "application/json",
            "User-Agent": "KMediaPlayer-kotlin-js-npm-check",
        },
    )
    with urlopen(request, timeout=20) as response:
        metadata = json.load(response)
    if not isinstance(metadata, dict):
        raise ValueError(f"npm returned invalid metadata for {package_name!r}")
    version = metadata.get("version")
    if not isinstance(version, str) or not version:
        raise ValueError(f"npm returned no latest version for {package_name!r}")
    return version


def find_outdated_dependencies(
    dependencies: Iterable[NpmDependency],
    latest_version: Callable[[str], str] = fetch_latest_version,
) -> list[OutdatedDependency]:
    latest_versions: dict[str, str] = {}
    outdated: list[OutdatedDependency] = []
    for dependency in dependencies:
        if dependency.name not in latest_versions:
            latest_versions[dependency.name] = latest_version(dependency.name)
        resolved_latest = latest_versions[dependency.name]
        if dependency.version != resolved_latest:
            outdated.append(
                OutdatedDependency(
                    dependency=dependency,
                    latest_version=resolved_latest,
                )
            )
    return outdated


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "paths",
        nargs="*",
        type=Path,
        default=[Path(".")],
        help="Gradle Kotlin files or directories to scan (default: repository root)",
    )
    arguments = parser.parse_args()

    try:
        dependencies = discover_dependencies(arguments.paths)
        if not dependencies:
            raise ValueError("No literal Kotlin/JS npm dependencies were found")
        outdated = find_outdated_dependencies(dependencies)
    except (OSError, ValueError, json.JSONDecodeError) as error:
        print(f"Kotlin/JS npm dependency check failed: {error}", file=sys.stderr)
        return 2

    if outdated:
        print("Outdated Kotlin/JS npm dependencies:", file=sys.stderr)
        for item in outdated:
            dependency = item.dependency
            print(
                f"  {dependency.name}: {dependency.version} -> "
                f"{item.latest_version} ({dependency.path}:{dependency.line})",
                file=sys.stderr,
            )
        print(
            "Update the Gradle declaration and run "
            "./gradlew kotlinWasmUpgradeYarnLock.",
            file=sys.stderr,
        )
        return 1

    print(f"All {len(dependencies)} Kotlin/JS npm dependencies are current.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
