from __future__ import annotations

import hashlib
import sys
import tempfile
import unittest
import xml.etree.ElementTree as element_tree
from pathlib import Path


sys.path.insert(0, str(Path(__file__).parent))
import rebuild_maven_metadata  # noqa: E402


class RebuildMavenMetadataTest(unittest.TestCase):
    def test_rebuilds_sorted_versions_and_checksums_from_immutable_directories(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            repository = Path(temporary_directory)
            versions = [
                "1.1.0-rc.1",
                "1.0.0-alpha.10",
                "1.0.0",
                "1.1.0",
                "1.0.0-alpha.2",
                "1.0.0+build.2",
            ]
            for version in versions:
                self._add_publication(repository, "composemediaplayer-jvm", version)

            rebuilt = rebuild_maven_metadata.rebuild_repository(
                repository,
                last_updated="20260714120000",
            )

            self.assertEqual(1, rebuilt)
            metadata = (
                repository
                / "io/github/shusek/composemediaplayer-jvm/maven-metadata.xml"
            )
            root = element_tree.parse(metadata).getroot()
            self.assertEqual("io.github.shusek", root.findtext("groupId"))
            self.assertEqual("composemediaplayer-jvm", root.findtext("artifactId"))
            self.assertEqual("1.1.0", root.findtext("versioning/latest"))
            self.assertEqual("1.1.0", root.findtext("versioning/release"))
            self.assertEqual(
                [
                    "1.0.0-alpha.2",
                    "1.0.0-alpha.10",
                    "1.0.0",
                    "1.0.0+build.2",
                    "1.1.0-rc.1",
                    "1.1.0",
                ],
                [node.text for node in root.findall("versioning/versions/version")],
            )
            self.assertEqual("20260714120000", root.findtext("versioning/lastUpdated"))

            contents = metadata.read_bytes()
            for algorithm in ("md5", "sha1", "sha256", "sha512"):
                checksum = metadata.with_name(f"maven-metadata.xml.{algorithm}").read_text(
                    encoding="ascii",
                )
                self.assertEqual(hashlib.new(algorithm, contents).hexdigest(), checksum)

    def test_ignores_non_semver_and_incomplete_directories(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            repository = Path(temporary_directory)
            self._add_publication(repository, "composemediaplayer", "2.0.0")
            artifact = repository / "io/github/shusek/composemediaplayer"
            (artifact / "dev").mkdir(parents=True)
            (artifact / "2.1.0").mkdir(parents=True)

            rebuild_maven_metadata.rebuild_repository(repository, "20260714120000")

            root = element_tree.parse(artifact / "maven-metadata.xml").getroot()
            self.assertEqual(
                ["2.0.0"],
                [node.text for node in root.findall("versioning/versions/version")],
            )

    @staticmethod
    def _add_publication(repository: Path, artifact_id: str, version: str) -> None:
        version_directory = repository / "io/github/shusek" / artifact_id / version
        version_directory.mkdir(parents=True, exist_ok=True)
        (version_directory / f"{artifact_id}-{version}.pom").write_text(
            "<project />\n",
            encoding="utf-8",
        )


if __name__ == "__main__":
    unittest.main()
