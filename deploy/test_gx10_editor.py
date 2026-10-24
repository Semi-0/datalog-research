import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest import mock

import gx10_editor as deployment


def passed_run(**changes):
    row = {"head_sha": "abc", "head_branch": "main", "event": "push", "status": "completed",
           "conclusion": "success", "head_repository": {"full_name": "Semi-0/lain-editor"},
           "run_number": 2, "run_attempt": 1, "html_url": "https://github.com/example/run/2"}
    return dict(row, **changes)


class DeploymentTests(unittest.TestCase):
    def test_exact_revision_and_trusted_main_push_are_required(self):
        rejected = [passed_run(head_sha="old"), passed_run(head_branch="feature"),
                    passed_run(event="pull_request"), passed_run(status="in_progress"),
                    passed_run(conclusion="failure"),
                    passed_run(head_repository={"full_name": "another/repository"})]
        self.assertIsNone(deployment.successful_run("Semi-0/lain-editor", "abc", rejected))
        good = passed_run()
        self.assertEqual(good, deployment.successful_run("Semi-0/lain-editor", "abc", rejected + [good]))

    def test_most_recent_successful_attempt_is_selected(self):
        newest = passed_run(run_number=3, run_attempt=2)
        self.assertEqual(newest, deployment.successful_run("Semi-0/lain-editor", "abc",
                         [passed_run(), passed_run(run_number=3), newest]))

    def test_pending_ci_does_not_build_or_change_the_current_release(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory).resolve()
            with mock.patch.object(deployment, "ROOT", root), \
                 mock.patch.object(deployment, "approved_pair", return_value=None), \
                 mock.patch.object(deployment, "checkout") as checkout:
                deployment.poll()
                checkout.assert_not_called()
                self.assertFalse((root / "current").exists())
                self.assertFalse((root / "releases").exists())

    def test_activation_keeps_old_release_and_uses_new_one(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory).resolve()
            old, candidate = root / "old", root / "candidate"
            old.mkdir()
            candidate.mkdir()
            deployment.point_current(root, old)
            seen = []
            deployment.activate(root, candidate, lambda: seen.append((root / "current").resolve()))
            self.assertEqual([candidate], seen)
            self.assertTrue(old.is_dir())
            self.assertEqual(candidate, (root / "current").resolve())

    def test_failed_health_check_restores_and_restarts_the_previous_release(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory).resolve()
            old, candidate = root / "old", root / "candidate"
            old.mkdir()
            candidate.mkdir()
            deployment.point_current(root, old)
            seen = []

            def restart():
                seen.append((root / "current").resolve())
                if len(seen) == 1:
                    raise RuntimeError("candidate health failed")

            with self.assertRaisesRegex(RuntimeError, "candidate health failed"):
                deployment.activate(root, candidate, restart)
            self.assertEqual([candidate, old], seen)
            self.assertEqual(old, (root / "current").resolve())

    def test_failed_first_activation_stops_services_and_removes_current(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory).resolve()
            candidate = root / "candidate"
            candidate.mkdir()
            with mock.patch.object(deployment, "run") as run:
                with self.assertRaisesRegex(RuntimeError, "failed"):
                    deployment.activate(root, candidate, mock.Mock(side_effect=RuntimeError("failed")))
                run.assert_called_once_with(["systemctl", "--user", "stop", *deployment.UNITS])
                self.assertFalse((root / "current").is_symlink())

    def test_build_failure_is_recorded_without_promotion_or_automatic_retry(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory).resolve()
            pair = {role: {"repository": repo, "sha": "abc", "ci_run": "ci"}
                    for role, repo in deployment.REPOS.items()}
            with mock.patch.object(deployment, "ROOT", root), \
                 mock.patch.object(deployment, "approved_pair", return_value=pair), \
                 mock.patch.object(deployment, "checkout"), \
                 mock.patch.object(deployment, "build", side_effect=RuntimeError("build failed")) as build, \
                 mock.patch.object(deployment, "activate") as activate:
                with self.assertRaisesRegex(RuntimeError, "build failed"):
                    deployment.poll()
                receipt = json.loads((root / "last-attempt.json").read_text())
                self.assertEqual("failed", receipt["status"])
                self.assertEqual(pair, receipt["pair"])
                deployment.poll()
                build.assert_called_once()
                activate.assert_not_called()

    def test_candidate_children_are_reaped_when_preflight_fails(self):
        children = []
        real_popen = subprocess.Popen

        def child(*_args, **_kwargs):
            process = real_popen([sys.executable, "-c", "import time; time.sleep(120)"], start_new_session=True)
            children.append(process)
            return process

        with tempfile.TemporaryDirectory() as directory:
            with mock.patch.object(deployment.subprocess, "Popen", side_effect=child), \
                 mock.patch.object(deployment.socket, "create_connection", return_value=mock.MagicMock()):
                with self.assertRaisesRegex(RuntimeError, "probe failed"):
                    with deployment.processes(Path(directory), 12345, 12346):
                        raise RuntimeError("probe failed")
        self.assertEqual(2, len(children))
        self.assertTrue(all(process.poll() is not None for process in children))


if __name__ == "__main__":
    unittest.main()
