import copy
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import follow_on
from lifecycle import Failure


class FollowOnTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)

    def complete(self, root, task, outcome='passed', checkpoint='complete'):
        cell = root / 'solo' / task
        cell.mkdir(parents=True)
        (cell / 'result.json').write_text(json.dumps({'outcome': outcome}))
        (cell / 'checkpoint.json').write_text(json.dumps({'stage': checkpoint}))

    def test_predecessor_waits_for_service_exit_even_after_results_exist(self):
        predecessor = self.root / 'previous'
        predecessor.mkdir()
        (predecessor / 'status.json').write_text(json.dumps({'stage': 'finished', 'completed_cells': 3}))
        for task in follow_on.TASKS:
            self.complete(predecessor, task)
        states = iter([{'SubState': 'running', 'ExecMainStatus': '0'}, {'SubState': 'exited', 'ExecMainStatus': '0'}])
        with patch.object(follow_on.time, 'sleep') as sleep:
            follow_on.wait_predecessor(self.root, predecessor, 'previous.service', lambda unit: next(states))
        sleep.assert_called_once_with(5)

    def test_stopped_predecessor_cannot_admit_next_model(self):
        predecessor = self.root / 'previous'
        predecessor.mkdir()
        (predecessor / 'status.json').write_text(json.dumps({'stage': 'stopped'}))
        with self.assertRaises(Failure):
            follow_on.wait_predecessor(self.root, predecessor, 'previous.service',
                                      lambda unit: {'SubState': 'failed', 'ExecMainStatus': '1'})

    def test_missing_result_or_cleanup_blocks_admission(self):
        for outcome, checkpoint in [('passed', 'cleanup'), ('transition_failed', 'complete'),
                                    ('runtime_error', 'complete'), ('interrupted', 'complete')]:
            with self.subTest(outcome=outcome, checkpoint=checkpoint):
                root = self.root / (outcome + checkpoint)
                self.complete(root, 'distinct', outcome, checkpoint)
                with self.assertRaises(Failure):
                    follow_on.require_complete(root, ['distinct'])
        with self.assertRaises(Failure):
            follow_on.require_complete(self.root, ['events'])

    def test_cohort_uses_shared_lifecycle_and_retains_failures(self):
        manifest = {'coding': {'tasks': follow_on.TASKS, 'context': 131072, 'tool_profile': 'enhanced'},
                    'sampling': {'temperature': 1.0}}
        original = copy.deepcopy(manifest)
        cohort = {'label': 'ornith-baseline', 'model': {'id': 'ornith'}}
        calls = []
        def run_cell(model, task, root, **kwargs):
            calls.append((task, kwargs))
            if task == 'smoke':
                return {'outcome': 'passed'}
            self.complete(root.parent.parent, task, 'task_failed')
            return {'outcome': 'task_failed'}
        current = []
        with patch.object(follow_on.reporting, 'report'), patch.object(follow_on.reporting.suite, 'MANIFEST', {}):
            follow_on.run_cohort(self.root, cohort, manifest, [], current, run_cell)
        self.assertEqual(calls, [('smoke', {'smoke': True}), ('distinct', {}), ('order', {}), ('events', {})])
        self.assertEqual(len(current), 3)
        self.assertEqual(manifest, original)
        self.assertTrue(all(row['result']['outcome'] == 'task_failed' for row in current))

    def test_infrastructure_failure_prevents_next_cell(self):
        calls = []
        def run_cell(model, task, root, **kwargs):
            calls.append(task)
            if task == 'smoke':
                return {'outcome': 'passed'}
            self.complete(root.parent.parent, task, 'resource_stopped')
            return {'outcome': 'resource_stopped'}
        with patch.object(follow_on.reporting, 'report'), patch.object(follow_on.reporting.suite, 'MANIFEST', {}):
            with self.assertRaises(Failure):
                follow_on.run_cohort(self.root, {'label': 'ornith', 'model': {}}, {}, [], [], run_cell)
        self.assertEqual(calls, ['smoke', 'distinct'])


if __name__ == '__main__':
    unittest.main()
