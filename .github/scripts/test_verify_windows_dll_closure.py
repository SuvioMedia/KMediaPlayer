import importlib.util
import os
import tempfile
import unittest
import zipfile
from pathlib import Path
from unittest.mock import patch


SCRIPT = Path(__file__).with_name("verify_windows_dll_closure.py")
SPEC = importlib.util.spec_from_file_location("windows_dll_closure", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(MODULE)


class WindowsDllClosureTest(unittest.TestCase):
    def test_parses_only_dependency_rows_from_dumpbin_output(self) -> None:
        output = """
Dump of file C:\\runtime\\player.dll

  Image has the following dependencies:

    KERNEL32.dll
    libdependency-1.dll

  Summary
        1000 .data
"""

        self.assertEqual(
            {"KERNEL32.dll", "libdependency-1.dll"},
            MODULE.parse_dumpbin_dependencies(output),
        )

    def test_accepts_packaged_system_and_api_set_dependencies(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            archive = root / "runtime.jar"
            self._write_archive(archive, "player.dll", "codec.dll")
            system_root = root / "Windows"
            system32 = system_root / "System32"
            system32.mkdir(parents=True)
            (system32 / "KERNEL32.dll").write_bytes(b"system")

            dependencies = {
                "player.dll": {"codec.dll", "KERNEL32.dll", "api-ms-win-crt-runtime-l1-1-0.dll"},
                "codec.dll": {"KERNEL32.dll"},
            }

            packaged_count, import_count = MODULE.verify_archive(
                archive_path=archive,
                dumpbin_path=root / "unused-dumpbin.exe",
                system_root=system_root,
                dependency_reader=lambda _dumpbin, dll: dependencies[dll.name],
            )

            self.assertEqual(2, packaged_count)
            self.assertEqual(4, import_count)

    def test_rejects_dependency_available_only_through_path(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            archive = root / "runtime.jar"
            self._write_archive(archive, "player.dll")
            system_root = root / "Windows"
            (system_root / "System32").mkdir(parents=True)
            path_only = root / "mingw-bin"
            path_only.mkdir()
            (path_only / "libgcc_s_seh-1.dll").write_bytes(b"incidental")

            with patch.dict(os.environ, {"PATH": str(path_only)}):
                with self.assertRaisesRegex(
                    MODULE.DllClosureError,
                    r"player\.dll -> libgcc_s_seh-1\.dll",
                ):
                    MODULE.verify_archive(
                        archive_path=archive,
                        dumpbin_path=root / "unused-dumpbin.exe",
                        system_root=system_root,
                        dependency_reader=lambda _dumpbin, _dll: {"libgcc_s_seh-1.dll"},
                    )

    def test_accepts_dependency_packaged_in_another_runtime_archive(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            client = root / "client.jar"
            shared = root / "shared.jar"
            self._write_archive(client, "client.dll")
            self._write_archive(shared, "shared.dll")
            system_root = root / "Windows"
            (system_root / "System32").mkdir(parents=True)

            dependencies = {
                "client.dll": {"shared.dll"},
                "shared.dll": {"api-ms-win-core-libraryloader-l1-2-0.dll"},
            }

            packaged_count, import_count = MODULE.verify_archives(
                archives=[
                    (client, MODULE.DEFAULT_RUNTIME_PREFIX),
                    (shared, MODULE.DEFAULT_RUNTIME_PREFIX),
                ],
                dumpbin_path=root / "unused-dumpbin.exe",
                system_root=system_root,
                dependency_reader=lambda _dumpbin, dll: dependencies[dll.name],
            )

            self.assertEqual(2, packaged_count)
            self.assertEqual(2, import_count)

    def test_rejects_duplicate_dll_names_across_runtime_archives(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            first = root / "first.jar"
            second = root / "second.jar"
            self._write_archive(first, "shared.dll")
            self._write_archive(second, "shared.dll")

            with self.assertRaisesRegex(MODULE.DllClosureError, "across runtime archives"):
                MODULE.verify_archives(
                    archives=[
                        (first, MODULE.DEFAULT_RUNTIME_PREFIX),
                        (second, MODULE.DEFAULT_RUNTIME_PREFIX),
                    ],
                    dumpbin_path=root / "unused-dumpbin.exe",
                    system_root=root / "Windows",
                    dependency_reader=lambda _dumpbin, _dll: {"KERNEL32.dll"},
                )

    def test_rejects_archive_without_windows_runtime_dlls(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            archive = Path(temporary) / "runtime.jar"
            with zipfile.ZipFile(archive, "w") as output:
                output.writestr("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\n")

            with self.assertRaisesRegex(MODULE.DllClosureError, "No Windows runtime DLLs"):
                MODULE.verify_archive(
                    archive_path=archive,
                    dumpbin_path=Path("unused-dumpbin.exe"),
                    system_root=Path(temporary) / "Windows",
                    dependency_reader=lambda _dumpbin, _dll: set(),
                )

    def test_rejects_empty_dumpbin_dependency_graph(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            archive = root / "runtime.jar"
            self._write_archive(archive, "player.dll")

            with self.assertRaisesRegex(MODULE.DllClosureError, "reported no DLL imports"):
                MODULE.verify_archive(
                    archive_path=archive,
                    dumpbin_path=root / "unused-dumpbin.exe",
                    system_root=root / "Windows",
                    dependency_reader=lambda _dumpbin, _dll: set(),
                )

    @staticmethod
    def _write_archive(archive: Path, *dll_names: str) -> None:
        with zipfile.ZipFile(archive, "w") as output:
            for dll_name in dll_names:
                output.writestr(f"{MODULE.DEFAULT_RUNTIME_PREFIX}{dll_name}", b"MZ")


if __name__ == "__main__":
    unittest.main()
