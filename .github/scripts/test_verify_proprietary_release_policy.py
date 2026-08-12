# SPDX-License-Identifier: LicenseRef-KMediaPlayer-Internal

from __future__ import annotations

import importlib.util
import shutil
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location(
    "verify_proprietary_release_policy",
    ROOT / ".github/scripts/verify_proprietary_release_policy.py",
)
assert SPEC is not None and SPEC.loader is not None
POLICY = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(POLICY)


class VerifyProprietaryReleasePolicyTest(unittest.TestCase):
    def test_repository_policy_is_closed(self) -> None:
        POLICY.verify(ROOT)

    def test_rejects_divergent_embedded_license(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            copy = Path(directory) / "repository"
            for relative in (
                "LICENSE",
                "README.MD",
                "legal/publication/META-INF/LICENSE",
                ".github/workflows/publish-on-maven-central.yml",
                ".github/workflows/publish-existing-tag-to-maven-central.yml",
            ):
                source = ROOT / relative
                destination = copy / relative
                destination.parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(source, destination)
            embedded = copy / "legal/publication/META-INF/LICENSE"
            embedded.write_text(
                embedded.read_text(encoding="utf-8") + "changed\n",
                encoding="utf-8",
            )
            with self.assertRaisesRegex(ValueError, "licenses differ"):
                POLICY.verify(copy)


if __name__ == "__main__":
    unittest.main()
