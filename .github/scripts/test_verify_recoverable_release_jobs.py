import copy
import unittest

from verify_recoverable_release_jobs import (
    REQUIRED_SUCCESSFUL_JOBS,
    SKIPPED_RELEASE_JOBS,
    VERIFICATION_GATE,
    validate,
)


def recoverable_jobs() -> list[dict[str, object]]:
    jobs: list[dict[str, object]] = [
        {"name": name, "conclusion": "success", "runner_name": "hosted", "steps": [{}]}
        for name in sorted(REQUIRED_SUCCESSFUL_JOBS)
    ]
    jobs.append(
        {
            "name": VERIFICATION_GATE,
            "conclusion": "failure",
            "runner_name": "",
            "steps": [],
        }
    )
    jobs.extend(
        {"name": name, "conclusion": "skipped", "runner_name": "", "steps": []}
        for name in sorted(SKIPPED_RELEASE_JOBS)
    )
    return jobs


class RecoverableReleaseJobsTest(unittest.TestCase):
    def test_accepts_only_unstarted_aggregate_gate_after_green_verification(self) -> None:
        validate(recoverable_jobs())

    def test_rejects_a_failed_concrete_verification_job(self) -> None:
        jobs = recoverable_jobs()
        jobs[0]["conclusion"] = "failure"
        with self.assertRaisesRegex(ValueError, "Required verification jobs"):
            validate(jobs)

    def test_rejects_an_aggregate_gate_that_ran_steps(self) -> None:
        jobs = recoverable_jobs()
        gate = next(job for job in jobs if job["name"] == VERIFICATION_GATE)
        gate["runner_name"] = "runner"
        gate["steps"] = [{"name": "Require every verification job"}]
        with self.assertRaisesRegex(ValueError, "unstarted runner failure"):
            validate(jobs)

    def test_rejects_a_release_that_may_have_started(self) -> None:
        jobs = recoverable_jobs()
        release = next(job for job in jobs if job["name"] == "release")
        release["conclusion"] = "failure"
        with self.assertRaisesRegex(ValueError, "refusing to publish again"):
            validate(jobs)

    def test_rejects_an_incomplete_job_graph(self) -> None:
        jobs = copy.deepcopy(recoverable_jobs())
        jobs.pop(0)
        with self.assertRaisesRegex(ValueError, "Unexpected release job graph"):
            validate(jobs)


if __name__ == "__main__":
    unittest.main()
