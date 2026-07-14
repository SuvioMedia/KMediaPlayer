#!/usr/bin/env python3
"""Rebuild Maven release metadata from immutable version directories."""

from __future__ import annotations

import argparse
import hashlib
import re
from datetime import datetime, timezone
from functools import cmp_to_key
from pathlib import Path
from xml.sax.saxutils import escape


SEMVER_PATTERN = re.compile(
    r"^(0|[1-9][0-9]*)\."
    r"(0|[1-9][0-9]*)\."
    r"(0|[1-9][0-9]*)"
    r"(?:-((?:0|[1-9][0-9]*|[0-9]*[A-Za-z-][0-9A-Za-z-]*)"
    r"(?:\.(?:0|[1-9][0-9]*|[0-9]*[A-Za-z-][0-9A-Za-z-]*))*))?"
    r"(?:\+([0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*))?$"
)


def compare_semver(left: str, right: str) -> int:
    """Compare two strict SemVer values, using build metadata only as a stable tie-breaker."""
    left_match = SEMVER_PATTERN.fullmatch(left)
    right_match = SEMVER_PATTERN.fullmatch(right)
    if left_match is None or right_match is None:
        raise ValueError(f"Not a strict SemVer pair: {left!r}, {right!r}")

    for index in range(3):
        comparison = int(left_match.group(index + 1)) - int(right_match.group(index + 1))
        if comparison:
            return 1 if comparison > 0 else -1

    prerelease_comparison = _compare_prerelease(left_match.group(4), right_match.group(4))
    if prerelease_comparison:
        return prerelease_comparison

    left_build = left_match.group(5) or ""
    right_build = right_match.group(5) or ""
    return (left_build > right_build) - (left_build < right_build)


def _compare_prerelease(left: str | None, right: str | None) -> int:
    if left is None or right is None:
        if left is right:
            return 0
        return 1 if left is None else -1

    left_identifiers = left.split(".")
    right_identifiers = right.split(".")
    for left_identifier, right_identifier in zip(left_identifiers, right_identifiers):
        if left_identifier == right_identifier:
            continue
        left_numeric = left_identifier.isdigit()
        right_numeric = right_identifier.isdigit()
        if left_numeric and right_numeric:
            return (int(left_identifier) > int(right_identifier)) - (
                int(left_identifier) < int(right_identifier)
            )
        if left_numeric != right_numeric:
            return -1 if left_numeric else 1
        return (left_identifier > right_identifier) - (left_identifier < right_identifier)

    return (len(left_identifiers) > len(right_identifiers)) - (
        len(left_identifiers) < len(right_identifiers)
    )


def rebuild_repository(repository: Path, last_updated: str | None = None) -> int:
    repository = repository.resolve()
    group_root = repository / "io" / "github" / "shusek"
    if not group_root.is_dir():
        raise ValueError(f"Expected Maven group directory does not exist: {group_root}")

    timestamp = last_updated or datetime.now(timezone.utc).strftime("%Y%m%d%H%M%S")
    rebuilt = 0
    for artifact_directory in sorted(path for path in group_root.rglob("*") if path.is_dir()):
        versions = _artifact_versions(artifact_directory)
        if not versions:
            continue
        _write_metadata(repository, artifact_directory, versions, timestamp)
        rebuilt += 1

    if rebuilt == 0:
        raise ValueError(f"No Maven artifacts were discovered below {group_root}")
    return rebuilt


def _artifact_versions(artifact_directory: Path) -> list[str]:
    artifact_id = artifact_directory.name
    versions = []
    for candidate in artifact_directory.iterdir():
        if not candidate.is_dir() or SEMVER_PATTERN.fullmatch(candidate.name) is None:
            continue
        if (candidate / f"{artifact_id}-{candidate.name}.pom").is_file():
            versions.append(candidate.name)
    return sorted(versions, key=cmp_to_key(compare_semver))


def _write_metadata(
    repository: Path,
    artifact_directory: Path,
    versions: list[str],
    last_updated: str,
) -> None:
    group_id = ".".join(artifact_directory.parent.relative_to(repository).parts)
    artifact_id = artifact_directory.name
    latest = versions[-1]
    version_rows = "\n".join(f"      <version>{escape(version)}</version>" for version in versions)
    contents = (
        '<?xml version="1.0" encoding="UTF-8"?>\n'
        "<metadata>\n"
        f"  <groupId>{escape(group_id)}</groupId>\n"
        f"  <artifactId>{escape(artifact_id)}</artifactId>\n"
        "  <versioning>\n"
        f"    <latest>{escape(latest)}</latest>\n"
        f"    <release>{escape(latest)}</release>\n"
        "    <versions>\n"
        f"{version_rows}\n"
        "    </versions>\n"
        f"    <lastUpdated>{last_updated}</lastUpdated>\n"
        "  </versioning>\n"
        "</metadata>\n"
    ).encode("utf-8")

    metadata_path = artifact_directory / "maven-metadata.xml"
    metadata_path.write_bytes(contents)
    for algorithm in ("md5", "sha1", "sha256", "sha512"):
        digest = hashlib.new(algorithm, contents).hexdigest()
        metadata_path.with_name(f"{metadata_path.name}.{algorithm}").write_text(
            digest,
            encoding="ascii",
        )


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("repository", type=Path, help="Root of the Maven repository")
    parser.add_argument(
        "--last-updated",
        help="Deterministic UTC timestamp in yyyyMMddHHmmss form (mainly for tests)",
    )
    arguments = parser.parse_args()
    rebuilt = rebuild_repository(arguments.repository, arguments.last_updated)
    print(f"Rebuilt Maven metadata for {rebuilt} artifacts.")


if __name__ == "__main__":
    main()
