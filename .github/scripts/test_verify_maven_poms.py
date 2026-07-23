from __future__ import annotations

import sys
import tempfile
import unittest
from pathlib import Path


sys.path.insert(0, str(Path(__file__).parent))
import verify_maven_poms  # noqa: E402


class VerifyMavenPomsTest(unittest.TestCase):
    def test_accepts_complete_maven_central_metadata(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            repository = Path(temporary_directory)
            self._write_pom(repository, "composemediaplayer-test", "2.0.4")

            self.assertEqual(
                1,
                verify_maven_poms.validate_repository(repository, "2.0.4"),
            )

    def test_rejects_missing_developer_metadata(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            repository = Path(temporary_directory)
            pom = self._write_pom(repository, "composemediaplayer-test", "2.0.4")
            pom.write_text(
                pom.read_text(encoding="utf-8").replace(
                    "<developers><developer><id>Shusek</id><name>Shusek</name></developer></developers>",
                    "",
                ),
                encoding="utf-8",
            )

            with self.assertRaisesRegex(ValueError, "developers/developer/id"):
                verify_maven_poms.validate_repository(repository, "2.0.4")

    def test_requires_exact_mpv_runtime_dependency(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            repository = Path(temporary_directory)
            pom = self._write_pom(repository, "composemediaplayer-mpv-android", "3.0.0-rc.1")
            with self.assertRaisesRegex(ValueError, "exact transitive"):
                verify_maven_poms.validate_pom(pom, "3.0.0-rc.1")
            pom.write_text(
                pom.read_text().replace(
                    "</project>",
                    "<dependencies><dependency><groupId>io.github.shusek</groupId><artifactId>kmedia-mpv-runtime-android</artifactId><version>0.3.0-rc.3</version></dependency></dependencies></project>",
                ),
            )
            verify_maven_poms.validate_pom(pom, "3.0.0-rc.1")

    def test_requires_platform_specific_kmediabridge_dependency(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            repository = Path(temporary_directory)
            pom = self._write_pom(repository, "composemediaplayer-kmediabridge-android", "3.0.0-rc.1")
            with self.assertRaisesRegex(ValueError, "kmedia-bridge-ffmpeg-android"):
                verify_maven_poms.validate_pom(pom, "3.0.0-rc.1")
            pom.write_text(
                pom.read_text().replace(
                    "</project>",
                    "<dependencies><dependency><groupId>io.github.shusek</groupId><artifactId>kmedia-bridge-ffmpeg-android</artifactId><version>0.5.0-rc.2</version></dependency></dependencies></project>",
                ),
            )
            verify_maven_poms.validate_pom(pom, "3.0.0-rc.1")

    @staticmethod
    def _write_pom(repository: Path, artifact_id: str, version: str) -> Path:
        version_directory = repository / "io/github/shusek" / artifact_id / version
        version_directory.mkdir(parents=True)
        pom = version_directory / f"{artifact_id}-{version}.pom"
        pom.write_text(
            f"""\
<project>
  <groupId>io.github.shusek</groupId>
  <artifactId>{artifact_id}</artifactId>
  <version>{version}</version>
  <name>Test publication</name>
  <description>Test publication metadata.</description>
  <url>https://github.com/Shusek/KMediaPlayer</url>
  <developers><developer><id>Shusek</id><name>Shusek</name></developer></developers>
  <licenses><license><name>Test</name><url>https://example.test/license</url><distribution>repo</distribution></license></licenses>
  <scm>
    <connection>scm:git:https://github.com/Shusek/KMediaPlayer.git</connection>
    <developerConnection>scm:git:ssh://git@github.com/Shusek/KMediaPlayer.git</developerConnection>
    <url>https://github.com/Shusek/KMediaPlayer</url>
  </scm>
</project>
""",
            encoding="utf-8",
        )
        return pom


if __name__ == "__main__":
    unittest.main()
