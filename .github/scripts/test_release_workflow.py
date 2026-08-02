import unittest
from pathlib import Path


class ReleaseWorkflowTest(unittest.TestCase):
    def test_automatic_builds_run_only_for_release_tags(self) -> None:
        repository_root = Path(__file__).resolve().parents[2]
        build_test = (
            repository_root / ".github/workflows/build-test.yml"
        ).read_text(encoding="utf-8")
        release = (
            repository_root / ".github/workflows/publish-on-maven-central.yml"
        ).read_text(encoding="utf-8")

        self.assertIn("on:\n  workflow_call:\n", build_test)
        self.assertNotIn("\n  push:", build_test)
        self.assertNotIn("\n  pull_request:", build_test)
        self.assertIn("on:\n  push:\n    tags:\n      - 'v*'\n", release)
        self.assertNotIn("\n    branches:", release)

        for relative_path in (
            ".github/workflows/build-documentation-and-sample.yml",
            ".github/workflows/check-kotlin-js-npm-dependencies.yml",
            ".github/workflows/publish-existing-tag-to-maven-central.yml",
            ".github/workflows/verify-maven-central.yml",
        ):
            workflow = (repository_root / relative_path).read_text(encoding="utf-8")
            self.assertIn("on:\n  workflow_dispatch:\n", workflow)
            self.assertNotIn("\n  push:", workflow)
            self.assertNotIn("\n  pull_request:", workflow)
            self.assertNotIn("\n  schedule:", workflow)

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

    def test_release_workflows_wait_for_maven_central(self) -> None:
        repository_root = Path(__file__).resolve().parents[2]
        verifier = ".github/scripts/verify_maven_central_release.sh"
        for relative_path in (
            ".github/workflows/publish-on-maven-central.yml",
            ".github/workflows/publish-existing-tag-to-maven-central.yml",
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
            ".github/workflows/publish-existing-tag-to-maven-central.yml",
        ):
            workflow = (repository_root / relative_path).read_text(encoding="utf-8")
            self.assertIn("pattern: apple-mpv-", workflow)
            self.assertIn("verify_apple_mpv_payload.sh", workflow)

    def test_apple_mpv_graph_uses_the_runtime_required_by_kmedia_mpv(self) -> None:
        repository_root = Path(__file__).resolve().parents[2]
        build_natives = (
            repository_root / ".github/workflows/build-natives.yml"
        ).read_text(encoding="utf-8")
        verifier = (
            repository_root / ".github/scripts/verify_apple_mpv_payload.sh"
        ).read_text(encoding="utf-8")
        mpv_build = (
            repository_root / "mediaplayer-mpv/build.gradle.kts"
        ).read_text(encoding="utf-8")
        podspec = (
            repository_root / "mediaplayer-mpv/ComposeMediaPlayerMpv.podspec"
        ).read_text(encoding="utf-8")

        self.assertIn("MPV_VERSION: 0.3.0-rc.6", build_natives)
        self.assertIn("RUNTIME_VERSION: 0.1.0-rc.5", build_natives)
        self.assertIn("KMEDIA_FFMPEG_RUNTIME_VERSION:-0.1.0-rc.5", verifier)
        self.assertIn('kmediaFfmpegRuntimeVersion = "0.1.0-rc.5"', mpv_build)
        self.assertIn("KMediaAssRuntime', '0.1.0-rc.5'", podspec)
        self.assertIn("KMediaFfmpegRuntime', '0.1.0-rc.5'", podspec)

    def test_every_native_ass_consumer_uses_one_runtime_version(self) -> None:
        repository_root = Path(__file__).resolve().parents[2]
        runtime_consumers = (
            ".github/scripts/verify_apple_ass_payload.sh",
            ".github/scripts/verify_apple_mpv_payload.sh",
            ".github/scripts/verify_maven_poms.py",
            ".github/workflows/build-natives.yml",
            "mediaplayer-ass/ComposeMediaPlayerAss.podspec",
            "mediaplayer-ass/build.gradle.kts",
            "mediaplayer-mpv/ComposeMediaPlayerMpv.podspec",
            "mediaplayer-mpv/build.gradle.kts",
        )
        source = "\n".join(
            (repository_root / relative_path).read_text(encoding="utf-8")
            for relative_path in runtime_consumers
        )

        self.assertIn("0.1.0-rc.5", source)
        self.assertNotIn("0.1.0-rc.3", source)

    def test_windows_jvm_job_verifies_packaged_dll_dependency_closure(self) -> None:
        repository_root = Path(__file__).resolve().parents[2]
        workflow = (
            repository_root / ".github/workflows/build-test.yml"
        ).read_text(encoding="utf-8")
        ass_build = (
            repository_root / "mediaplayer-ass/build.gradle.kts"
        ).read_text(encoding="utf-8")

        stage_position = workflow.index("Stage Windows native runtimes for DLL closure verification")
        verify_position = workflow.index("Verify packaged Windows DLL dependency closure")
        test_position = workflow.index("Test desktop JVM on ${{ matrix.label }}")

        self.assertLess(stage_position, verify_position)
        self.assertLess(verify_position, test_position)
        self.assertIn(":mediaplayer-ass:stageWindowsAssRuntimeForVerification", workflow)
        self.assertIn(":mediaplayer-mpv:stageWindowsMpvRuntimeForVerification", workflow)
        self.assertIn("verify_windows_dll_closure.py", workflow)
        self.assertIn("if: runner.os == 'Windows'", workflow[stage_position:test_position])
        self.assertIn("stageWindowsAssRuntimeForVerification", ass_build)
        mpv_build = (
            repository_root / "mediaplayer-mpv/build.gradle.kts"
        ).read_text(encoding="utf-8")
        self.assertIn("stageWindowsMpvRuntimeForVerification", mpv_build)

    def test_consumer_smoke_trusts_the_generated_desktop_window_fixtures(self) -> None:
        repository_root = Path(__file__).resolve().parents[2]
        verification = (
            repository_root / "gradle/verification-metadata.xml"
        ).read_text(encoding="utf-8")
        trusted_artifacts = verification.split("<trusted-artifacts>", 1)[1].split(
            "</trusted-artifacts>", 1
        )[0]

        self.assertIn("desktop-window", trusted_artifacts)
        self.assertIn("0[.]0[.]0-consumer", trusted_artifacts)

    def test_all_apple_release_downloads_are_authenticated(self) -> None:
        repository_root = Path(__file__).resolve().parents[2]
        workflow = (
            repository_root / ".github/workflows/build-natives.yml"
        ).read_text(encoding="utf-8")
        self.assertEqual(2, workflow.count("GH_TOKEN: ${{ github.token }}"))

    def test_manual_maven_central_verifier_is_available(self) -> None:
        repository_root = Path(__file__).resolve().parents[2]
        workflow = (
            repository_root / ".github/workflows/verify-maven-central.yml"
        ).read_text(encoding="utf-8")
        settings = (
            repository_root / ".github/public-maven-consumer/settings.gradle.kts"
        ).read_text(encoding="utf-8")
        build = (
            repository_root / ".github/public-maven-consumer/build.gradle.kts"
        ).read_text(encoding="utf-8")

        self.assertIn("workflow_dispatch:", workflow)
        self.assertIn("verify_maven_central_release.sh", workflow)
        self.assertIn("mavenCentral()", settings)
        self.assertIn("composemediaplayer-mpv-jvm", build)
        self.assertIn("composemediaplayer-kmediabridge-jvm", build)
        self.assertIn("composemediaplayer-mpv-android", build)
        self.assertIn("composemediaplayer-kmediabridge-android", build)
        self.assertIn("kmedia-ffmpeg-runtime-desktop", build)
        self.assertIn("kmedia-ffmpeg-runtime-android", build)
        self.assertIn("kmedia-ass-runtime-desktop", build)
        self.assertIn("kmedia-ass-runtime-android", build)

    def test_release_workflow_is_maven_central_only(self) -> None:
        repository_root = Path(__file__).resolve().parents[2]
        release = (
            repository_root / ".github/workflows/publish-on-maven-central.yml"
        ).read_text(encoding="utf-8")
        documentation = (
            repository_root / ".github/workflows/build-documentation-and-sample.yml"
        ).read_text(encoding="utf-8")

        self.assertIn("publishAllPublicationsToReleaseStagingRepository", release)
        self.assertIn("publishAndReleaseToMavenCentral", release)
        self.assertIn("Require Maven Central credentials", release)
        for removed in (
            "gh-pages",
            "githubPagesMavenRepository",
            "GithubPagesRepository",
            "rebuild_maven_metadata.py",
        ):
            self.assertNotIn(removed, release)
            self.assertNotIn(removed, documentation)

        for build_file in repository_root.glob("*/build.gradle.kts"):
            build = build_file.read_text(encoding="utf-8")
            self.assertNotIn("githubPagesMavenRepository", build)
            self.assertNotIn("GithubPagesRepository", build)


if __name__ == "__main__":
    unittest.main()
