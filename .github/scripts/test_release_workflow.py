import unittest
from pathlib import Path


class ReleaseWorkflowTest(unittest.TestCase):
    def test_shared_runtime_consumer_installs_android_native_toolchains(self) -> None:
        repository_root = Path(__file__).resolve().parents[2]
        workflow = (
            repository_root / ".github/workflows/publish-on-maven-central.yml"
        ).read_text(encoding="utf-8")
        consumer_job = workflow.split("  shared-runtime-consumer:", 1)[1].split(
            "\n  release:", 1
        )[0]

        self.assertIn('"ndk;29.0.14206865"', consumer_job)
        self.assertIn(
            "rustup target add armv7-linux-androideabi aarch64-linux-android",
            consumer_job,
        )

    def test_release_workflows_wait_for_public_maven_central(self) -> None:
        repository_root = Path(__file__).resolve().parents[2]
        verifier = ".github/scripts/verify_public_maven_release.sh"
        for relative_path in (
            ".github/workflows/publish-on-maven-central.yml",
            ".github/workflows/publish-existing-release-to-maven-central.yml",
        ):
            workflow = (repository_root / relative_path).read_text(encoding="utf-8")
            publish_position = workflow.index("publishAndReleaseToMavenCentral")
            verification_position = workflow.index(verifier)
            self.assertGreater(verification_position, publish_position)

    def test_all_apple_ass_consumers_restore_the_complete_build_tree(self) -> None:
        repository_root = Path(__file__).resolve().parents[2]
        build_test = (
            repository_root / ".github/workflows/build-test.yml"
        ).read_text(encoding="utf-8")
        self.assertEqual(2, build_test.count("path: mediaplayer-ass/build/"))
        self.assertNotIn("path: mediaplayer-ass/build/generated/", build_test)

    def test_all_apple_mpv_consumers_restore_the_exact_pod_graph(self) -> None:
        repository_root = Path(__file__).resolve().parents[2]
        build_test = (
            repository_root / ".github/workflows/build-test.yml"
        ).read_text(encoding="utf-8")
        build_natives = (
            repository_root / ".github/workflows/build-natives.yml"
        ).read_text(encoding="utf-8")
        self.assertEqual(2, build_test.count("path: mediaplayer-mpv/build/"))
        for pod in (
            "kmediaMpvPod",
            "kmediaFfmpegRuntimePod",
            "kmediaAssRuntimePod",
        ):
            self.assertIn(f"mediaplayer-mpv/build/{pod}/", build_natives)
        for relative_path in (
            ".github/workflows/publish-on-maven-central.yml",
            ".github/workflows/publish-existing-release-to-maven-central.yml",
        ):
            workflow = (repository_root / relative_path).read_text(encoding="utf-8")
            self.assertIn("pattern: apple-mpv-", workflow)
            self.assertIn("verify_apple_mpv_payload.sh", workflow)

    def test_manual_public_maven_verifier_is_available(self) -> None:
        repository_root = Path(__file__).resolve().parents[2]
        workflow = (
            repository_root / ".github/workflows/verify-public-maven-central.yml"
        ).read_text(encoding="utf-8")
        settings = (
            repository_root / ".github/public-maven-consumer/settings.gradle.kts"
        ).read_text(encoding="utf-8")
        build = (
            repository_root / ".github/public-maven-consumer/build.gradle.kts"
        ).read_text(encoding="utf-8")

        self.assertIn("workflow_dispatch:", workflow)
        self.assertIn("verify_public_maven_release.sh", workflow)
        self.assertIn("mavenCentral()", settings)
        self.assertIn("composemediaplayer-mpv-jvm", build)
        self.assertIn("composemediaplayer-kmediabridge-jvm", build)
        self.assertIn("composemediaplayer-mpv-android", build)
        self.assertIn("composemediaplayer-kmediabridge-android", build)
        self.assertIn("kmedia-ffmpeg-runtime-desktop", build)
        self.assertIn("kmedia-ffmpeg-runtime-android", build)
        self.assertIn("kmedia-ass-runtime-desktop", build)
        self.assertIn("kmedia-ass-runtime-android", build)


if __name__ == "__main__":
    unittest.main()
