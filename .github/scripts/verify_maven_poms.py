#!/usr/bin/env python3
"""Validate Maven Central metadata in every POM for one publication version."""

from __future__ import annotations

import argparse
import xml.etree.ElementTree as element_tree
from pathlib import Path


EXPECTED_BACKEND_DEPENDENCIES = {
    "composemediaplayer-mpv-android": ("kmedia-mpv-runtime-android", "0.3.0-rc.1"),
    "composemediaplayer-mpv-jvm": ("kmedia-mpv-runtime-desktop", "0.3.0-rc.1"),
    "composemediaplayer-kmediabridge-android": ("kmedia-bridge-ffmpeg-android", "0.5.0-rc.1"),
    "composemediaplayer-kmediabridge-jvm": ("kmedia-bridge-ffmpeg-jvm", "0.5.0-rc.1"),
}


def _local_name(tag: str) -> str:
    return tag.rsplit("}", maxsplit=1)[-1]


def _child(parent: element_tree.Element, name: str) -> element_tree.Element | None:
    return next((child for child in parent if _local_name(child.tag) == name), None)


def _require_text(
    parent: element_tree.Element,
    path: tuple[str, ...],
    pom: Path,
) -> element_tree.Element:
    current = parent
    for segment in path:
        child = _child(current, segment)
        if child is None:
            raise ValueError(f"{pom}: missing <{'/'.join(path)}>")
        current = child
    if not (current.text or "").strip():
        raise ValueError(f"{pom}: empty <{'/'.join(path)}>")
    return current


def validate_pom(pom: Path, expected_version: str) -> None:
    project = element_tree.parse(pom).getroot()
    _require_text(project, ("groupId",), pom)
    _require_text(project, ("artifactId",), pom)
    version = _require_text(project, ("version",), pom)
    if (version.text or "").strip() != expected_version:
        raise ValueError(
            f"{pom}: expected version {expected_version!r}, found {(version.text or '').strip()!r}",
        )

    for field in ("name", "description", "url"):
        _require_text(project, (field,), pom)
    for path in (
        ("developers", "developer", "id"),
        ("developers", "developer", "name"),
        ("licenses", "license", "name"),
        ("licenses", "license", "url"),
        ("licenses", "license", "distribution"),
        ("scm", "connection"),
        ("scm", "developerConnection"),
        ("scm", "url"),
    ):
        _require_text(project, path, pom)

    artifact_id = (_child(project, "artifactId").text or "").strip()
    expected_dependency = EXPECTED_BACKEND_DEPENDENCIES.get(artifact_id)
    if expected_dependency is not None:
        dependencies = _child(project, "dependencies")
        values = set()
        if dependencies is not None:
            for dependency in dependencies:
                if _local_name(dependency.tag) != "dependency":
                    continue
                group = _child(dependency, "groupId")
                artifact = _child(dependency, "artifactId")
                dependency_version = _child(dependency, "version")
                if group is not None and artifact is not None and dependency_version is not None:
                    values.add(((group.text or "").strip(), (artifact.text or "").strip(), (dependency_version.text or "").strip()))
        expected_artifact, expected_dependency_version = expected_dependency
        expected = ("io.github.shusek", expected_artifact, expected_dependency_version)
        if expected not in values:
            raise ValueError(f"{pom}: missing exact transitive backend dependency {expected}")


def validate_repository(repository: Path, version: str) -> int:
    group_root = repository.resolve() / "io" / "github" / "shusek"
    if not group_root.is_dir():
        raise ValueError(f"Expected Maven group directory does not exist: {group_root}")

    poms = sorted(group_root.glob(f"*/{version}/*.pom"))
    if not poms:
        raise ValueError(f"No POMs for version {version!r} were found below {group_root}")
    for pom in poms:
        validate_pom(pom, version)
    return len(poms)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("repository", type=Path, help="Root of the Maven repository")
    parser.add_argument("version", help="Exact publication version to validate")
    arguments = parser.parse_args()
    validated = validate_repository(arguments.repository, arguments.version)
    print(f"Validated Maven Central metadata in {validated} POMs.")


if __name__ == "__main__":
    main()
