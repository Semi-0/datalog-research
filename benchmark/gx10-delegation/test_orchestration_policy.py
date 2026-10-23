import contextlib
import json
from pathlib import Path
import tempfile
import unittest
from unittest import mock
import lain_host
import orchestrator
from lifecycle import Failure

PASS = 'Ran 3 tests containing 14 assertions.\n0 failures, 0 errors.\n'
FAIL = 'Ran 1 tests containing 1 assertions.\n1 failures, 0 errors.\n'


class OrchestrationPolicyTests(unittest.TestCase):
    def host(self, root):
        host = lain_host.Host.__new__(lain_host.Host)
        host.output = root
        host.backend = mock.Mock()
        host.require_model = mock.Mock()
        return host

    def test_own_failure_does_not_reject_correct_integrated_implementation(self):
        with tempfile.TemporaryDirectory() as name:
            host = self.host(Path(name))
            with mock.patch.object(lain_host.pilot, 'finite_check', side_effect=[(1, FAIL), (0, PASS)]) as check:
                result = host.evaluate()
            self.assertEqual(2, check.call_count)
            self.assertEqual('passed', result['outcome'])
            self.assertTrue(result['correct'])
            self.assertFalse(result['workflow_pass'])
            self.assertFalse(result['protocol_pass'])
            self.assertEqual(14, result['acceptance_counts']['assertions'])

    def test_independent_failure_or_zero_assertions_cannot_pass(self):
        for output in [FAIL, 'Ran 0 tests containing 0 assertions.\n0 failures, 0 errors.']:
            with self.subTest(output=output), tempfile.TemporaryDirectory() as name:
                host = self.host(Path(name))
                with mock.patch.object(lain_host.pilot, 'finite_check', side_effect=[(0, PASS), (0, output)]):
                    with self.assertRaises(Failure):
                        host.evaluate()
                self.assertFalse(host.final['correct'])
                self.assertEqual('task_failed', host.final['outcome'])

    def test_worker_test_failure_is_available_for_integration_repair(self):
        with tempfile.TemporaryDirectory() as name:
            host = self.host(Path(name))
            host.worker_model = 'ornith-nvfp4'
            host.jobs = [{'lane': lane, 'cwd': str(host.output / lane), 'root': str(host.output / lane)}
                         for lane in lain_host.LANES]
            self.assertEqual(4, len(host.jobs))
            with mock.patch.object(lain_host, 'collect_patch', return_value={'patch': 'owned'}), \
                 mock.patch.object(lain_host.pilot, 'finite_check', return_value=(1, FAIL)):
                result = host.collect()
            self.assertEqual(4, len(result['handoffs']))
            rows = json.loads((host.output / 'worker-test-results.json').read_text())
            self.assertTrue(rows)
            self.assertTrue(all(not row['passed'] for row in rows))

    def test_later_failure_preserves_measured_correctness(self):
        class Host:
            backend = None
            model = None
            final = None
            def __init__(self, output):
                self.output = output
            def dispatch(self, request):
                if request['operation'] == 'evaluate':
                    self.final = {'outcome': 'passed', 'correct': True, 'workflow_pass': True}
                    return self.final
                raise Failure('task_failed', 'Reviewer did not finish')
        steps = [{'path': ['evaluate'], 'operation': 'evaluate', 'arguments': None},
                 {'path': ['review'], 'operation': 'pi/review', 'arguments': None}]
        with tempfile.TemporaryDirectory() as name:
            root = Path(name) / 'attempt'
            with mock.patch.object(orchestrator, 'plan', return_value={'steps': steps}), \
                 mock.patch.object(orchestrator, 'experiment_lock', return_value=contextlib.nullcontext()):
                with self.assertRaises(Failure):
                    orchestrator.execute(root, host_factory=Host)
            result = json.loads((root / 'result.json').read_text())
            self.assertEqual('passed', result['outcome'])
            self.assertTrue(result['correct'])
            self.assertFalse(result['workflow_pass'])
            self.assertFalse(result['timing_valid'])
            self.assertEqual('task_failed', result['execution_outcome'])
            self.assertTrue((root / 'failure.json').exists())
