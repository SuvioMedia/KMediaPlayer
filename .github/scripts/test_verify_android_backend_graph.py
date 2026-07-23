#!/usr/bin/env python3
# SPDX-License-Identifier: LicenseRef-KMediaPlayer-Internal

from __future__ import annotations

import importlib.util
import tempfile
import unittest
import zipfile
from collections.abc import Callable
from pathlib import Path


SCRIPT = Path(__file__).with_name("verify_android_backend_graph.py")
SPEC = importlib.util.spec_from_file_location("android_graph", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(MODULE)


class AndroidBackendGraphTest(unittest.TestCase):
    def archive(
        self,
        root: Path,
        *,
        duplicate: bool = False,
        legacy: bool = False,
        client_transform: Callable[[bytes], bytes] | None = None,
    ) -> Path:
        path = root / "consumer.apk"
        repository_root = SCRIPT.parents[2]
        with zipfile.ZipFile(path, "w") as archive:
            for abi in MODULE.ABIS:
                for name in MODULE.OWNED:
                    if name == "libkmediaass.so":
                        payload = (
                            repository_root
                            / "mediaplayer-ass"
                            / "src"
                            / "androidMain"
                            / "jniLibs"
                            / abi
                            / name
                        ).read_bytes()
                        if client_transform is not None:
                            payload = client_transform(payload)
                    else:
                        payload = name.encode()
                    archive.writestr(f"lib/{abi}/{name}", payload)
                if duplicate:
                    archive.writestr(f"base/lib/{abi}/libkmediaffmpeg_avcodec.so", b"duplicate")
                if legacy:
                    archive.writestr(f"lib/{abi}/libavcodec-kmb.so", b"legacy")
        return path

    def test_accepts_three_clients_and_one_shared_runtime(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            report = MODULE.verify(self.archive(Path(temporary)))
            self.assertTrue(report["singleSharedRuntimeGraph"])
            self.assertEqual(5, report["assRuntimeLibrariesPerAbi"])
            self.assertEqual(7, report["ffmpegRuntimeLibrariesPerAbi"])
            self.assertEqual(12, report["sharedRuntimeLibrariesPerAbi"])
            self.assertEqual(1, report["assClientLibrariesPerAbi"])
            for needed in report["assClientNeededByAbi"].values():
                self.assertIn("libkmediaffmpeg_ass.so", needed)

    def test_rejects_duplicate_shared_runtime(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            with self.assertRaisesRegex(ValueError, "exactly once"):
                MODULE.verify(self.archive(Path(temporary), duplicate=True))

    def test_rejects_legacy_runtime(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            with self.assertRaisesRegex(ValueError, "legacy"):
                MODULE.verify(self.archive(Path(temporary), legacy=True))

    def test_rejects_ass_client_without_shared_libass_edge(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            with self.assertRaisesRegex(ValueError, "does not link the shared libass"):
                MODULE.verify(
                    self.archive(
                        Path(temporary),
                        client_transform=lambda payload: payload.replace(
                            b"libkmediaffmpeg_ass.so",
                            b"libkmediaffmpeg_bad.so",
                        ),
                    ),
                )

    def test_rejects_ass_client_with_private_text_runtime_edge(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            with self.assertRaisesRegex(ValueError, "private text runtime"):
                MODULE.verify(
                    self.archive(
                        Path(temporary),
                        client_transform=lambda payload: payload.replace(
                            b"liblog.so",
                            b"libass.so",
                        ),
                    ),
                )


if __name__ == "__main__":
    unittest.main()
