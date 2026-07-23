#!/usr/bin/env python3
# SPDX-License-Identifier: LicenseRef-KMediaPlayer-Internal

from __future__ import annotations

import importlib.util
import tempfile
import unittest
import zipfile
from pathlib import Path


SCRIPT = Path(__file__).with_name("verify_android_backend_graph.py")
SPEC = importlib.util.spec_from_file_location("android_graph", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(MODULE)


class AndroidBackendGraphTest(unittest.TestCase):
    def archive(self, root: Path, *, duplicate: bool = False, legacy: bool = False) -> Path:
        path = root / "consumer.apk"
        with zipfile.ZipFile(path, "w") as archive:
            for abi in MODULE.ABIS:
                for name in MODULE.OWNED:
                    archive.writestr(f"lib/{abi}/{name}", name.encode())
                if duplicate:
                    archive.writestr(f"base/lib/{abi}/libkmediaffmpeg_avcodec.so", b"duplicate")
                if legacy:
                    archive.writestr(f"lib/{abi}/libavcodec-kmb.so", b"legacy")
        return path

    def test_accepts_two_clients_and_one_shared_runtime(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            report = MODULE.verify(self.archive(Path(temporary)))
            self.assertTrue(report["singleSharedRuntimeGraph"])
            self.assertEqual(11, report["sharedRuntimeLibrariesPerAbi"])

    def test_rejects_duplicate_shared_runtime(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            with self.assertRaisesRegex(ValueError, "exactly once"):
                MODULE.verify(self.archive(Path(temporary), duplicate=True))

    def test_rejects_legacy_runtime(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            with self.assertRaisesRegex(ValueError, "legacy"):
                MODULE.verify(self.archive(Path(temporary), legacy=True))


if __name__ == "__main__":
    unittest.main()
