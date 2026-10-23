import copy
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import follow_on
import continuation
import repetition_recovery as recovery
from lifecycle import Failure, save


class ContinuationTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.base = Path(self.temp.name)
        self.previous = self.base / 'prior-run' / 'ornith'
        self.previous.mkdir(parents=True)
        self.model = {'id': 'ornith'}
        self.manifest = {'models': [self.model], 'coding': {'tasks': follow_on.TASKS}, 'sampling': {'temperature': 1.0}}
        save(self.previous / 'manifest.json', self.manifest)

    def cell(self, task, result=None, repeat=False):
        cell = self.previous / 'smoke'
        if task != 'smoke':
            cell = self.previous / 'solo' / task
        cell.mkdir(parents=True)
        if result is None:
            result = {'outcome': 'passed', 'execution_outcome': 'passed', 'correct': True}
        result = copy.deepcopy(result)
        save(cell / 'manifest.json', self.manifest)
        save(cell / 'cell.json', {'task': task})
        save(cell / 'checkpoint.json', {'stage': 'complete'})
        physical = cell
        if repeat:
            physical = cell / 'attempt-1'
            physical.mkdir()
            result.update(execution_outcome='runtime_error', stream_failures=[{'stop_reason': 'length'}, {'stop_reason': 'aborted'}])
            save(physical / 'result.json', result)
            artifacts = physical / 'attempt/artifacts'
            artifacts.mkdir(parents=True)
            (artifacts / 'anti-repeat.jsonl').write_text('{"kind":"anti_repeat","type":"corrected"}\n{"kind":"anti_repeat","type":"stopped","detection":{"kind":"reasoning_repeat"}}\n')
            save(physical / 'checkpoint.json', {'stage': 'complete'})
            result['recovery'] = {'selected_result': str(physical / 'result.json'), 'exhausted': False}
        save(cell / 'result.json', result)
        (physical / 'lifecycle.jsonl').write_text('{"operation":"settlement_sample","details":{"models":[],"old_processes":[],"endpoint_alive":false,"memory":{"available":100,"reserve":20}}}\n{"operation":"transition_verified"}\n')
        return cell

    def test_correct_repetition_stop_and_smoke_are_retained(self):
        self.cell('smoke')
        distinct = self.cell('distinct', repeat=True)
        found = continuation.collect_retained(self.previous, self.manifest, lambda unit: {'SubState': 'failed'})
        self.assertEqual(set(found), {'smoke', 'distinct'})
        self.assertEqual(found['distinct']['cell_root'], str(distinct))
        follow_on.require_complete(self.previous, ['distinct'])
        self.assertTrue(recovery.reload_decision(distinct / 'attempt-1', recovery.read_json(distinct / 'attempt-1/result.json'))['eligible'])

    def test_predecessor_gate_resolves_retained_cell_references(self):
        cell = self.cell('distinct', repeat=True)
        newer = self.base / 'newer-run' / 'ornith'
        newer.mkdir(parents=True)
        save(newer / 'retained-cells.json', {'distinct': str(cell)})
        follow_on.require_complete(newer, ['distinct'])
        self.assertEqual(continuation.cell_path(newer, 'distinct'), cell)

    def test_only_missing_tasks_execute_during_resume(self):
        self.cell('smoke')
        self.cell('distinct', repeat=True)
        manifest = dict(self.manifest, continuation_from=str(self.previous))
        output = self.base / 'new-run'
        output.mkdir()
        calls = []
        def run_cell(model, task, root, **kwargs):
            calls.append(task)
            root.mkdir(parents=True)
            result = {'outcome': 'task_failed', 'execution_outcome': 'task_failed', 'correct': False}
            save(root / 'result.json', result)
            save(root / 'checkpoint.json', {'stage': 'complete'})
            return result
        current = []
        with patch.object(follow_on, 'systemd_state', return_value={'SubState': 'failed'}), patch.object(follow_on.reporting, 'report'):
            follow_on.run_cohort(output, {'label': 'ornith', 'model': self.model}, manifest, [], current, run_cell)
        self.assertEqual(calls, ['order', 'events'])
        self.assertEqual(len(current), 3)
        self.assertEqual(current[0]['result_path'], str(self.previous / 'solo/distinct/result.json'))
        self.assertFalse((output / 'ornith/solo/distinct').exists())

    def test_active_controller_and_changed_config_are_rejected(self):
        with self.assertRaises(Failure):
            continuation.collect_retained(self.previous, self.manifest, lambda unit: {'SubState': 'running'})
        changed = copy.deepcopy(self.manifest)
        changed['sampling']['temperature'] = 0.6
        with self.assertRaises(Failure):
            continuation.collect_retained(self.previous, changed, lambda unit: {'SubState': 'failed'})

    def test_partial_cell_does_not_silently_retry(self):
        self.cell('smoke')
        (self.previous / 'solo/order').mkdir(parents=True)
        with self.assertRaises(Failure):
            continuation.collect_retained(self.previous, self.manifest, lambda unit: {'SubState': 'exited'})

    def test_unknown_runtime_error_is_not_admitted(self):
        self.cell('smoke')
        self.cell('distinct', {'outcome': 'runtime_error', 'correct': None})
        with self.assertRaises(Failure):
            continuation.collect_retained(self.previous, self.manifest, lambda unit: {'SubState': 'failed'})

    def test_exhausted_incorrect_repetition_may_advance_only_with_evidence(self):
        cell = self.cell('distinct', {'outcome': 'runtime_error', 'correct': False}, repeat=True)
        result = recovery.read_json(cell / 'result.json')
        self.assertFalse(recovery.can_continue_model_stop(cell, result))
        result['recovery']['exhausted'] = True
        self.assertTrue(recovery.can_continue_model_stop(cell, result))
        (cell / 'attempt-1/cleanup-failure.json').write_text('{}')
        self.assertFalse(recovery.can_continue_model_stop(cell, result))


if __name__ == '__main__':
    unittest.main()
