import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import time
import unittest
from unittest import mock

import gx10_editor as deployment


def source_pair():
    return {role: {"repository": repo, "sha": "a" * 40}
            for role, repo in deployment.REPOS.items()}


class DeploymentTests(unittest.TestCase):
    def test_quality_baselines_follow_the_previous_verified_pair(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "current").mkdir()
            baseline = {"backend": {"sha": "b" * 40}, "frontend": {"sha": "f" * 40}}
            (root / "current/release.json").write_text(json.dumps({"pair": baseline}))
            with mock.patch.object(deployment, "ROOT", root), \
                 mock.patch.object(deployment, "service_environment", return_value={"PATH": "/bin"}), \
                 mock.patch.object(deployment, "run") as run:
                deployment.run_ci(root / "candidate")
            self.assertEqual("b" * 40, run.call_args_list[0].kwargs["env"]["LAIN_CI_BASE_SHA"])
            self.assertEqual("f" * 40, run.call_args_list[1].kwargs["env"]["LAIN_CI_BASE_SHA"])
            self.assertEqual("/bin", run.call_args_list[0].kwargs["env"]["PATH"])
            self.assertEqual("1", run.call_args_list[0].kwargs["env"]["LAIN_CI_BASE_REQUIRED"])

    def test_missing_verified_baseline_does_not_inherit_an_override(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            with mock.patch.object(deployment, "ROOT", root), \
                 mock.patch.object(deployment, "service_environment", return_value={"LAIN_CI_BASE_SHA": "a" * 40}), \
                 mock.patch.object(deployment, "run") as run:
                deployment.run_ci(root / "candidate")
            for call in run.call_args_list:
                self.assertEqual("1", call.kwargs["env"]["LAIN_CI_BASE_REQUIRED"])
                self.assertNotIn("LAIN_CI_BASE_SHA", call.kwargs["env"])

    def test_source_heads_are_resolved_for_each_configured_repository(self):
        response = mock.Mock(stdout=json.dumps({"sha": "a" * 40}))
        with mock.patch.object(deployment, "run", return_value=response) as run:
            self.assertEqual(source_pair(), deployment.source_pair())
            self.assertEqual([mock.call(["gh", "api", f"repos/{repo}/commits/main"], capture=True)
                              for repo in deployment.REPOS.values()], run.call_args_list)

    def test_invalid_source_revision_is_rejected(self):
        with mock.patch.object(deployment, "run", return_value=mock.Mock(stdout='{"sha":"main"}')):
            with self.assertRaises(ValueError):
                deployment.source_pair()

    def test_ci_failure_prevents_preflight_activation_and_automatic_retry(self):
        self.check_failure("run_ci", "ci")

    def test_preflight_failure_is_recorded_after_successful_ci(self):
        self.check_failure("build", "deploy")

    def check_failure(self, failing_step, phase):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory).resolve()
            with mock.patch.object(deployment, "ROOT", root), \
                 mock.patch.object(deployment, "source_pair", return_value=source_pair()), \
                 mock.patch.object(deployment, "checkout"), \
                 mock.patch.object(deployment, "report_status") as status, \
                 mock.patch.object(deployment, "run_ci") as ci, \
                 mock.patch.object(deployment, "build") as build, \
                 mock.patch.object(deployment, "activate") as activate:
                failed = ci if failing_step == "run_ci" else build
                failed.side_effect = RuntimeError("check failed")
                with self.assertRaisesRegex(RuntimeError, "check failed"):
                    deployment.poll()
                receipt = json.loads((root / "last-attempt.json").read_text())
                self.assertEqual(("failed", phase, source_pair()),
                                 (receipt["status"], receipt["phase"], receipt["pair"]))
                status.assert_any_call(source_pair(), phase, "failure", mock.ANY)
                deployment.poll()
                failed.assert_called_once()
                activate.assert_not_called()
                if phase == "ci":
                    build.assert_not_called()

    def test_status_report_rejection_is_swallowed_and_logged(self):
        error = subprocess.CalledProcessError(1, ["gh", "api"],
                                              stderr="maximum number of statuses")
        with mock.patch.object(deployment, "run", side_effect=error), \
             mock.patch("builtins.print") as printed:
            deployment.report_status(source_pair(), "deploy", "pending", "Verifying")
        warnings = [call[0][0] for call in printed.call_args_list]
        self.assertEqual(len(deployment.REPOS), len(warnings))
        for repository, warning in zip(deployment.REPOS.values(), warnings):
            self.assertIn(f"could not report gx10/deploy=pending for {repository}@", warning)

    def test_status_api_rejection_does_not_prevent_activation(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory).resolve()
            order = []

            def runner(args, **_kwargs):
                if "statuses" in " ".join(args):
                    raise subprocess.CalledProcessError(1, args, stderr="maximum number of statuses")
                return mock.Mock(stdout="")

            with mock.patch.object(deployment, "ROOT", root), \
                 mock.patch.object(deployment, "source_pair", return_value=source_pair()), \
                 mock.patch.object(deployment, "checkout"), \
                 mock.patch.object(deployment, "run", side_effect=runner), \
                 mock.patch.object(deployment, "run_ci", side_effect=lambda _: order.append("ci")), \
                 mock.patch.object(deployment, "build", side_effect=lambda _: order.append("preflight")), \
                 mock.patch.object(deployment, "activate", side_effect=lambda *_: order.append("activate")), \
                 mock.patch("builtins.print"):
                deployment.poll()
            self.assertEqual(["ci", "preflight", "activate"], order)
            self.assertEqual("active", json.loads((root / "last-attempt.json").read_text())["status"])

    def test_success_runs_ci_then_preflight_then_activation(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory).resolve()
            order = []
            with mock.patch.object(deployment, "ROOT", root), \
                 mock.patch.object(deployment, "source_pair", return_value=source_pair()), \
                 mock.patch.object(deployment, "checkout"), \
                 mock.patch.object(deployment, "report_status"), \
                 mock.patch.object(deployment, "run"), \
                 mock.patch.object(deployment, "run_ci", side_effect=lambda _: order.append("ci")), \
                 mock.patch.object(deployment, "build", side_effect=lambda _: order.append("preflight")), \
                 mock.patch.object(deployment, "activate", side_effect=lambda *_: order.append("activate")):
                deployment.poll()
            self.assertEqual(["ci", "preflight", "activate"], order)
            receipt = json.loads((root / "last-attempt.json").read_text())
            self.assertEqual("active", receipt["status"])
            release = json.loads((Path(receipt["release"]) / "release.json").read_text())
            self.assertEqual("gx10-local", release["ci"])

    def test_main_advancing_during_preflight_prevents_activation(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory).resolve()
            newer = source_pair()
            newer["backend"]["sha"] = "b" * 40
            with mock.patch.object(deployment, "ROOT", root), \
                 mock.patch.object(deployment, "source_pair", side_effect=[source_pair(), newer]), \
                 mock.patch.object(deployment, "checkout"), \
                 mock.patch.object(deployment, "report_status"), \
                 mock.patch.object(deployment, "run_ci"), \
                 mock.patch.object(deployment, "build"), \
                 mock.patch.object(deployment, "activate") as activate:
                deployment.poll()
                activate.assert_not_called()
                receipt = json.loads((root / "last-attempt.json").read_text())
                self.assertEqual("superseded", receipt["status"])

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

    def test_failed_health_check_restores_previous_release(self):
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

    def test_failed_first_activation_stops_services(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory).resolve()
            candidate = root / "candidate"
            candidate.mkdir()
            with mock.patch.object(deployment, "run") as run:
                with self.assertRaisesRegex(RuntimeError, "failed"):
                    deployment.activate(root, candidate, mock.Mock(side_effect=RuntimeError("failed")))
                run.assert_called_once_with(["systemctl", "--user", "stop", *deployment.UNITS])
                self.assertFalse((root / "current").is_symlink())

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


class PruneTests(unittest.TestCase):
    @staticmethod
    def make_release(root, name, age_seconds):
        release = root / "releases" / name
        (release / "backend").mkdir(parents=True)
        (release / "backend" / "blob").write_bytes(b"x" * 1024)
        stamp = time.time() - age_seconds
        os.utime(release, (stamp, stamp))
        return release

    def test_prune_keeps_the_live_release_and_the_newest_revisions(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory).resolve()
            oldest = self.make_release(root, "a" * 32, 300)
            middle = self.make_release(root, "b" * 32, 200)
            newest = self.make_release(root, "c" * 32, 100)
            deployment.point_current(root, middle)
            removed = deployment.prune_releases(root, keep=2)
            self.assertEqual([oldest.name], [path.name for path, _size in removed])
            self.assertTrue(middle.is_dir() and newest.is_dir())

    def test_prune_never_removes_the_live_release(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory).resolve()
            oldest = self.make_release(root, "a" * 32, 300)
            middle = self.make_release(root, "b" * 32, 200)
            newest = self.make_release(root, "c" * 32, 100)
            deployment.point_current(root, oldest)
            removed = deployment.prune_releases(root, keep=1)
            self.assertEqual([middle.name], [path.name for path, _size in removed])
            self.assertTrue(oldest.is_dir() and newest.is_dir())

    def test_prune_ignores_entries_the_driver_did_not_create(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory).resolve()
            old = self.make_release(root, "a" * 32, 300)
            newest = self.make_release(root, "c" * 32, 100)
            manual = root / "releases" / "manual-snapshot"
            manual.mkdir()
            (root / "releases" / "notes.txt").write_text("keep me")
            deployment.point_current(root, newest)
            removed = deployment.prune_releases(root, keep=1)
            self.assertEqual([old.name], [path.name for path, _size in removed])
            self.assertTrue(manual.is_dir() and (root / "releases" / "notes.txt").is_file())

    def test_prune_reports_reclaimed_space_and_survives_an_unstoppable_release(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory).resolve()
            old = self.make_release(root, "a" * 32, 300)
            newest = self.make_release(root, "c" * 32, 100)
            deployment.point_current(root, newest)
            with mock.patch.object(deployment.shutil, "rmtree", side_effect=OSError("disk busy")), \
                 mock.patch("builtins.print") as printed:
                self.assertEqual([], deployment.prune_releases(root, keep=1))
            self.assertTrue(old.is_dir())
            self.assertIn("disk busy", printed.call_args[0][0])

    def test_poll_prunes_superseded_releases_after_activation(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory).resolve()
            order = []
            with mock.patch.object(deployment, "ROOT", root), \
                 mock.patch.object(deployment, "source_pair", return_value=source_pair()), \
                 mock.patch.object(deployment, "checkout"), \
                 mock.patch.object(deployment, "run_ci"), \
                 mock.patch.object(deployment, "build"), \
                 mock.patch.object(deployment, "report_status"), \
                 mock.patch.object(deployment, "run"), \
                 mock.patch.object(deployment, "activate", side_effect=lambda *_: order.append("activate")), \
                 mock.patch.object(deployment, "prune_releases", side_effect=lambda *_: order.append("prune")):
                deployment.poll()
            self.assertEqual(["activate", "prune"], order)
            self.assertEqual("active", json.loads((root / "last-attempt.json").read_text())["status"])

    def test_a_failing_prune_does_not_undo_an_activation(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory).resolve()
            with mock.patch.object(deployment, "ROOT", root), \
                 mock.patch.object(deployment, "source_pair", return_value=source_pair()), \
                 mock.patch.object(deployment, "checkout"), \
                 mock.patch.object(deployment, "run_ci"), \
                 mock.patch.object(deployment, "build"), \
                 mock.patch.object(deployment, "report_status"), \
                 mock.patch.object(deployment, "run"), \
                 mock.patch.object(deployment, "activate"), \
                 mock.patch.object(deployment, "prune_releases", side_effect=OSError("disk busy")), \
                 mock.patch("builtins.print"):
                deployment.poll()
            self.assertEqual("active", json.loads((root / "last-attempt.json").read_text())["status"])


if __name__ == "__main__":
    unittest.main()
