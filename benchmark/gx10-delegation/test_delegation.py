import copy
import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch, MagicMock

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
from contracts import LANES, owned_paths, validate_plan, collect_patch, tests_passed
import run
from lifecycle import Failure


def plan():
    return {'assignments': [{'lane': lane, 'instructions': 'Implement with finite tests'} for lane in LANES]}


class ContractTests(unittest.TestCase):
    def test_four_complete_disjoint_assignments(self):
        self.assertEqual(set(LANES), set(validate_plan(plan())))
        paths = [path for lane in LANES for path in owned_paths(lane)]
        self.assertEqual(8, len(set(paths)))

    def test_missing_duplicate_unknown_and_extra_assignments_rejected(self):
        cases = []
        missing = plan(); missing['assignments'].pop(); cases.append(missing)
        duplicate = plan(); duplicate['assignments'][1]['lane'] = 'normalize'; cases.append(duplicate)
        unknown = plan(); unknown['assignments'][1]['lane'] = '../escape'; cases.append(unknown)
        extra = plan(); extra['unbounded'] = True; cases.append(extra)
        for value in cases:
            with self.subTest(value=value), self.assertRaises(ValueError):
                validate_plan(value)

    def test_arbitrary_command_and_model_fields_rejected(self):
        for field in ['command', 'model', 'cwd', 'tools']:
            value = plan(); value['assignments'][0][field] = 'override'
            with self.assertRaises(ValueError):
                validate_plan(value)

    def test_invalid_instruction_bounds(self):
        for text in ['', ' ', None, 'x' * 4001]:
            value = plan(); value['assignments'][0]['instructions'] = text
            with self.assertRaises(ValueError):
                validate_plan(value)

    def test_zero_assertions_and_exit_zero_error_do_not_pass(self):
        for code, text in [(0, 'Ran 0 tests containing 0 assertions.\n0 failures, 0 errors.'),
                           (0, 'Syntax error: unmatched delimiter'),
                           (0, 'Ran 1 tests containing 2 assertions.\n1 failures, 0 errors.'),
                           (124, 'Ran 1 tests containing 2 assertions.\n0 failures, 0 errors.')]:
            self.assertFalse(tests_passed(code, text))
        self.assertTrue(tests_passed(0, 'Ran 2 tests containing 3 assertions.\n0 failures, 0 errors.'))

    def test_owned_patch_applies_to_fresh_checkout(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp); base = root / 'base'; run.bootstrap(base)
            worker = root / 'worker'; run.clone(base, worker)
            path = worker / LANES['normalize']['source']; path.write_text(path.read_text() + '\n;; changed\n')
            artifact = root / 'worker.patch'
            row = collect_patch(worker, 'normalize', artifact)
            self.assertEqual([LANES['normalize']['source']], row['paths'])
            target = root / 'integration'; run.clone(base, target)
            subprocess.run(['git', 'apply', str(artifact)], cwd=target, check=True)
            self.assertEqual(path.read_text(), (target / LANES['normalize']['source']).read_text())

    def test_unowned_and_symlink_artifacts_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp); base = root / 'base'; run.bootstrap(base)
            worker = root / 'worker'; run.clone(base, worker)
            extra = worker / 'scratch.clj'; extra.write_text('extra')
            with self.assertRaises(ValueError):
                collect_patch(worker, 'normalize', root / 'bad.patch')
            extra.unlink()
            path = worker / LANES['normalize']['source']; path.unlink(); path.symlink_to(base / LANES['normalize']['source'])
            with self.assertRaises(ValueError):
                collect_patch(worker, 'normalize', root / 'bad.patch')

    def test_empty_patch_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp); run.bootstrap(root / 'base')
            with self.assertRaises(ValueError):
                collect_patch(root / 'base', 'normalize', root / 'empty.patch')

    def test_git_status_preserves_leading_space(self):
        with patch.object(run.subprocess, 'check_output', return_value=' M delegation/normalize.clj\n'):
            self.assertTrue(run.git(Path('/tmp'), 'status').startswith(' M '))

    def test_transition_failure_prevents_loading(self):
        with tempfile.TemporaryDirectory() as tmp, patch.object(run, 'Backend') as cls, patch.object(run, 'start') as start:
            cls.return_value.cleanup.side_effect = Failure('transition_failed', 'uncertain cleanup')
            with self.assertRaises(Failure):
                run.model_phase(Path(tmp) / 'phase', {}, {'backend': 'ollama'}, MagicMock())
            start.assert_not_called()

    def test_exception_cleans_up_before_later_phase(self):
        with tempfile.TemporaryDirectory() as tmp, patch.object(run, 'Backend') as cls, patch.object(run, 'start', return_value=('name', 'url', 'unit')):
            backend = cls.return_value; backend.attempt_unit = None
            with self.assertRaisesRegex(RuntimeError, 'worker failure'):
                run.model_phase(Path(tmp) / 'phase', {}, {'backend': 'ollama'}, MagicMock(side_effect=RuntimeError('worker failure')))
            self.assertEqual(2, backend.cleanup.call_count)

    def test_interruption_stops_owned_attempt_before_cleanup(self):
        with tempfile.TemporaryDirectory() as tmp, patch.object(run, 'Backend') as cls, patch.object(run, 'start', return_value=('name', 'url', 'unit')):
            backend = cls.return_value; backend.attempt_unit = 'owned-attempt'
            with self.assertRaises(KeyboardInterrupt):
                run.model_phase(Path(tmp) / 'phase', {}, {'backend': 'ollama'}, MagicMock(side_effect=KeyboardInterrupt()))
            backend.stop.assert_called_once_with('owned-attempt')
            self.assertEqual(2, backend.cleanup.call_count)

    def test_finite_verification_is_owned_and_guarded(self):
        with tempfile.TemporaryDirectory() as tmp, patch.object(run, 'wait_attempt', return_value={'exit': 0}):
            root = Path(tmp); output = root / 'check.txt'; output.write_text('test output')
            backend = MagicMock(); backend.root = root; backend.state = {'attempt_results': {}}
            code, text = run.finite_check(backend, root, ['clojure', '-M', 'fixture.clj'], output)
            self.assertEqual((0, 'test output'), (code, text))
            backend.guard.assert_called_once(); backend.launch.assert_called_once(); backend.stop.assert_called_once()
            self.assertIn('attempt-eval-', backend.attempt_unit)


class QueueTests(unittest.TestCase):
    def setUp(self):
        spec = importlib.util.spec_from_file_location('delegation_queue', HERE / 'schedule.py')
        self.queue = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(self.queue)

    def test_running_benchmark_prevents_sandbox_or_model_work(self):
        with patch.object(self.queue.subprocess, 'run', return_value=MagicMock(returncode=0, stdout='running\n')) as effect:
            self.assertEqual('waiting_for_benchmark', self.queue.ready_state('original', Path('/unused')))
            self.assertEqual(1, effect.call_count)

    def test_missing_durable_completion_blocks_following_test(self):
        with tempfile.TemporaryDirectory() as tmp, patch.object(self.queue.subprocess, 'run', return_value=MagicMock(returncode=0, stdout='dead\n')):
            with self.assertRaisesRegex(RuntimeError, 'durable complete'):
                self.queue.ready_state('original', Path(tmp))

    def test_benchmark_failure_does_not_launch_delegation(self):
        with tempfile.TemporaryDirectory() as tmp, patch.object(self.queue.subprocess, 'run', return_value=MagicMock(returncode=0, stdout='failed\n')):
            root = Path(tmp); (root / 'failure.json').write_text('{}')
            with self.assertRaisesRegex(RuntimeError, 'infrastructure failed'):
                self.queue.ready_state('original', root)

    def test_sandbox_refusal_waits_for_administrator(self):
        with tempfile.TemporaryDirectory() as tmp, patch.object(self.queue.subprocess, 'run', side_effect=[MagicMock(returncode=0, stdout='dead\n'), MagicMock(returncode=1)]):
            root = Path(tmp); (root / 'status.json').write_text('{"stage":"complete"}')
            self.assertEqual('waiting_for_sandbox_administrator', self.queue.ready_state('original', root))


if __name__ == '__main__':
    unittest.main()
