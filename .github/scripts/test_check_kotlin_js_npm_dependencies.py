from __future__ import annotations

import sys
import tempfile
import unittest
from pathlib import Path


sys.path.insert(0, str(Path(__file__).parent))
import check_kotlin_js_npm_dependencies as npm_check  # noqa: E402


class CheckKotlinJsNpmDependenciesTest(unittest.TestCase):
    def test_discovers_literal_dependencies_and_ignores_comments_and_build_outputs(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            self._write(
                root / "module/build.gradle.kts",
                """\
dependencies {
    implementation(
        npm(
            "jassub",
            "2.5.7",
        ),
    )
    implementation(devNpm("vite", "7.0.0"))
    // implementation(npm("commented", "1.0.0"))
    /*
    implementation(optionalNpm("also-commented", "1.0.0"))
    */
}
""",
            )
            self._write(
                root / "module/build/generated.gradle.kts",
                'implementation(npm("generated", "1.0.0"))\n',
            )

            dependencies = npm_check.discover_dependencies([root])

            self.assertEqual(
                [("jassub", "2.5.7", 3), ("vite", "7.0.0", 8)],
                [
                    (dependency.name, dependency.version, dependency.line)
                    for dependency in dependencies
                ],
            )

    def test_reports_outdated_dependencies_and_resolves_each_package_once(self) -> None:
        dependencies = [
            npm_check.NpmDependency("jassub", "2.5.1", Path("a.gradle.kts"), 1),
            npm_check.NpmDependency("jassub", "2.5.7", Path("b.gradle.kts"), 2),
            npm_check.NpmDependency("vite", "7.0.0", Path("c.gradle.kts"), 3),
        ]
        calls: list[str] = []

        def latest_version(package_name: str) -> str:
            calls.append(package_name)
            return {"jassub": "2.5.7", "vite": "7.0.0"}[package_name]

        outdated = npm_check.find_outdated_dependencies(
            dependencies,
            latest_version=latest_version,
        )

        self.assertEqual(["jassub", "vite"], calls)
        self.assertEqual(
            [("jassub", "2.5.1", "2.5.7")],
            [
                (
                    item.dependency.name,
                    item.dependency.version,
                    item.latest_version,
                )
                for item in outdated
            ],
        )

    @staticmethod
    def _write(path: Path, contents: str) -> None:
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(contents, encoding="utf-8")


if __name__ == "__main__":
    unittest.main()
