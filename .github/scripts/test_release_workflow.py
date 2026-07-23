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


if __name__ == "__main__":
    unittest.main()
