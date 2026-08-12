#!/usr/bin/env python3

import json
import sys
from pathlib import Path


REQUIRED_SUCCESSFUL_JOBS = {
    "verify / abi",
    "verify / android",
    "verify / build-natives / linux (ubuntu-24.04, x86-64, x86_64-unknown-linux-gnu)",
    "verify / build-natives / linux (ubuntu-24.04-arm, aarch64, aarch64-unknown-linux-gnu)",
    "verify / build-natives / macos",
    "verify / build-natives / windows",
    "verify / consumer",
    "verify / ios",
    "verify / jvm (macos-15, macos, ./gradlew)",
    "verify / jvm (ubuntu-24.04, linux, ./gradlew)",
    "verify / jvm (windows-2025, windows, ./gradlew.bat)",
    "verify / quality",
    "verify / wasm-edge-smoke",
    "verify / wasm-firefox-smoke",
    "verify / wasm-js",
    "verify / wasm-webkit-smoke",
}
VERIFICATION_GATE = "verify / verification-complete"
SKIPPED_RELEASE_JOBS = {"shared-runtime-consumer", "release"}


def validate(jobs: list[dict[str, object]]) -> None:
    jobs_by_name: dict[str, list[dict[str, object]]] = {}
    for job in jobs:
        name = job.get("name")
        if isinstance(name, str):
            jobs_by_name.setdefault(name, []).append(job)

    expected_names = REQUIRED_SUCCESSFUL_JOBS | {VERIFICATION_GATE} | SKIPPED_RELEASE_JOBS
    missing = sorted(expected_names - jobs_by_name.keys())
    duplicates = sorted(name for name in expected_names if len(jobs_by_name.get(name, [])) > 1)
    unexpected_verification_jobs = sorted(
        name
        for name in jobs_by_name
        if name.startswith("verify / ") and name not in expected_names
    )
    if missing or duplicates or unexpected_verification_jobs:
        raise ValueError(
            "Unexpected release job graph: "
            f"missing={missing}, duplicates={duplicates}, "
            f"unexpected_verification_jobs={unexpected_verification_jobs}"
        )

    failed_required_jobs = sorted(
        name
        for name in REQUIRED_SUCCESSFUL_JOBS
        if jobs_by_name[name][0].get("conclusion") != "success"
    )
    if failed_required_jobs:
        raise ValueError(f"Required verification jobs did not succeed: {failed_required_jobs}")

    gate = jobs_by_name[VERIFICATION_GATE][0]
    if not (
        gate.get("conclusion") == "failure"
        and not gate.get("runner_name")
        and gate.get("steps") == []
    ):
        raise ValueError(
            "The verification gate was not an unstarted runner failure; "
            "automatic recovery is unsafe"
        )

    non_skipped_release_jobs = sorted(
        name
        for name in SKIPPED_RELEASE_JOBS
        if jobs_by_name[name][0].get("conclusion") != "skipped"
    )
    if non_skipped_release_jobs:
        raise ValueError(
            "The original release may have started; refusing to publish again: "
            f"{non_skipped_release_jobs}"
        )


def main() -> None:
    if len(sys.argv) != 2:
        raise SystemExit("usage: verify_recoverable_release_jobs.py JOBS_JSON")

    payload = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
    jobs = payload.get("jobs")
    if not isinstance(jobs, list):
        raise ValueError("GitHub jobs payload does not contain a jobs array")
    validate(jobs)
    print("All concrete verification jobs passed; only the unstarted aggregate gate failed.")


if __name__ == "__main__":
    main()
