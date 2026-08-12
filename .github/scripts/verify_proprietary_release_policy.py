#!/usr/bin/env python3
# SPDX-License-Identifier: LicenseRef-KMediaPlayer-Internal

from __future__ import annotations

import argparse
from pathlib import Path


REQUIRED_LICENSE_MARKERS = (
    "Suvio Proprietary Component License\nVersion 1.1",
    "2. Public repository access",
    "Public availability is\nnot an offer of an open-source license.",
    "To the extent the GitHub Terms of\nService grant GitHub users rights",
    "This license grants no additional\nright to use the proprietary material",
    "you may not build, execute, copy, modify, publish, distribute,",
    "All rights not expressly granted above are reserved.",
)
REQUIRED_README_MARKERS = (
    "public, source-visible, proprietary",
    "Repository visibility is not an open-source grant.",
    "GitHub-hosted forking",
    "verifyClosedSourcePublications",
)
FORBIDDEN_README_MARKERS = (
    "This is a private, standalone SuvioMedia-maintained derivative",
    "Do not release `4.1.7` while this implementation remains in a public source repository.",
    "KMediaPlayer can therefore remain private",
)
WORKFLOWS = (
    ".github/workflows/publish-on-maven-central.yml",
    ".github/workflows/publish-existing-tag-to-maven-central.yml",
)


def require_file(root: Path, relative: str) -> Path:
    path = root / relative
    if not path.is_file() or path.is_symlink():
        raise ValueError(
            f"Required release-policy file is missing or symbolic: {relative}"
        )
    return path


def verify(root: Path) -> None:
    root = root.resolve(strict=True)
    license_text = require_file(root, "LICENSE").read_text(encoding="utf-8")
    embedded_text = require_file(root, "legal/publication/META-INF/LICENSE").read_text(
        encoding="utf-8"
    )
    if license_text != embedded_text:
        raise ValueError("The repository and embedded proprietary licenses differ.")
    if any(marker not in license_text for marker in REQUIRED_LICENSE_MARKERS):
        raise ValueError("The proprietary license omits the public-repository rights boundary.")

    readme = require_file(root, "README.MD").read_text(encoding="utf-8")
    if any(marker not in readme for marker in REQUIRED_README_MARKERS):
        raise ValueError("README does not disclose the public proprietary distribution model.")
    if any(marker in readme for marker in FORBIDDEN_README_MARKERS):
        raise ValueError(
            "README still claims that proprietary releases require private source control."
        )

    invocation = "python3 .github/scripts/verify_proprietary_release_policy.py --root ."
    for relative in WORKFLOWS:
        workflow = require_file(root, relative).read_text(encoding="utf-8")
        if invocation not in workflow:
            raise ValueError(
                f"Release workflow does not verify the proprietary license: {relative}"
            )
        if "Require private source control for proprietary releases" in workflow:
            raise ValueError(
                f"Release workflow still requires private source control: {relative}"
            )
        if "github.event.repository.visibility" in workflow:
            raise ValueError(
                f"Release workflow still gates publication on visibility: {relative}"
            )


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Verify KMediaPlayer's public proprietary release policy."
    )
    parser.add_argument("--root", type=Path, required=True)
    args = parser.parse_args()
    try:
        verify(args.root)
    except (OSError, UnicodeError, ValueError) as error:
        parser.error(str(error))
    print("Public proprietary release policy verified.")


if __name__ == "__main__":
    main()
