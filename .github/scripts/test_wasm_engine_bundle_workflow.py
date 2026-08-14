from pathlib import Path
import unittest


REPOSITORY_ROOT = Path(__file__).resolve().parents[2]
WORKFLOW = (
    REPOSITORY_ROOT
    / ".github"
    / "workflows"
    / "publish-kmedia-wasm-engine-bundle.yml"
).read_text(encoding="utf-8")


class WasmEngineBundleWorkflowTest(unittest.TestCase):
    def test_hash_binds_binary_bundle_and_both_source_commits(self) -> None:
        self.assertIn("bundle_sha256:", WORKFLOW)
        self.assertIn("private_source_commit:", WORKFLOW)
        self.assertIn("corresponding_source_commit:", WORKFLOW)
        self.assertIn('test "$BUNDLE_SHA256" = "$(sha256sum "$bundle"', WORKFLOW)

    def test_rejects_sources_metadata_and_stale_signatures(self) -> None:
        self.assertIn("closed-source boundary", WORKFLOW)
        self.assertIn("maven-metadata*", WORKFLOW)
        self.assertIn("*.asc", WORKFLOW)
        self.assertIn(r"\.(kt|kts|java)$", WORKFLOW)

    def test_signs_and_submits_only_the_expected_coordinates(self) -> None:
        for artifact in (
            "kmedia-wasm-engine",
            "kmedia-wasm-engine-wasm-js",
            "kmedia-wasm-engine-runtime-assets",
        ):
            self.assertIn(artifact, WORKFLOW)
        self.assertIn("gpg --batch --yes", WORKFLOW)
        self.assertIn("central.sonatype.com/api/v1/publisher/upload", WORKFLOW)


if __name__ == "__main__":
    unittest.main()
