import copy
import json
from pathlib import Path
import tempfile
import unittest

import follow_on
import repetition_recovery as recovery
from lifecycle import Failure, save


class RecoveryTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name) / 'cell'
        self.calls = []
        self.model = {'id': 'ornith', 'mtp': True}

    def result(self, repeat=False):
        result = {'outcome': 'passed', 'execution_outcome': 'passed', 'correct': True,
                  'workflow_pass': True, 'timing_valid': True, 'coding_wall_s': 3,
                  'durations': {'load_s': 2, 'total_s': 8},
                  'inference': {'records': [{'complete': True, 'generated_tokens': 10, 'generation_seconds': 1}]}}
        if repeat:
            result.update(execution_outcome='runtime_error', workflow_pass=False, timing_valid=False,
                          stream_failures=[{'stop_reason': 'aborted', 'error': 'Request was aborted'}])
        return result

    def write_attempt(self, root, result, repeat=False, clean=True):
        root.mkdir(parents=True)
        save(root / 'result.json', result)
        save(root / 'checkpoint.json', {'stage': 'complete'})
        sample = {'models': [], 'old_processes': [], 'endpoint_alive': False,
                  'memory': {'available': 100, 'reserve': 20}}
        if not clean:
            sample['old_processes'] = [{'pid': 123}]
        events = [{'operation': 'settlement_sample', 'details': sample}, {'operation': 'transition_verified'}]
        (root / 'lifecycle.jsonl').write_text(''.join(json.dumps(row) + '\n' for row in events))
        if repeat:
            artifact = root / 'attempt/artifacts'
            artifact.mkdir(parents=True)
            events = [{'kind': 'anti_repeat', 'type': 'corrected'},
                      {'kind': 'anti_repeat', 'type': 'stopped', 'detection': {'kind': 'reasoning_repeat'}}]
            (artifact / 'anti-repeat.jsonl').write_text(''.join(json.dumps(row) + '\n' for row in events))
            (artifact / 'changes.patch').write_text('preserved failed candidate\n')

    def runner(self, results, clean=True):
        def run_cell(model, task, root, **kwargs):
            index = len(self.calls)
            if index == 1:
                prior = recovery.read_json(self.root / 'recovery.json')
                self.assertEqual(prior['state'], 'reload_pending')
                self.assertTrue((self.root / 'attempt-1/result.json').is_file())
                self.assertTrue(prior['attempts'][0]['cleanup_verified'])
            self.calls.append((model, task, root, kwargs))
            result = copy.deepcopy(results[index])
            self.write_attempt(root, result, bool(result.get('stream_failures')), clean)
            return result
        return run_cell

    def test_repetition_reloads_once_with_fresh_lifecycle_and_preserved_evidence(self):
        first = self.result(repeat=True)
        final = recovery.run_with_reload(self.runner([first, self.result()]), self.model, 'smoke', self.root, smoke=True)
        self.assertEqual(len(self.calls), 2)
        self.assertEqual([row[2].name for row in self.calls], ['attempt-1', 'attempt-2'])
        self.assertTrue(all(row[0] == self.model and row[3] == {'smoke': True} for row in self.calls))
        self.assertEqual(recovery.read_json(self.root / 'attempt-1/result.json'), first)
        self.assertEqual((self.root / 'attempt-1/attempt/artifacts/changes.patch').read_text(), 'preserved failed candidate\n')
        self.assertEqual(final['execution_outcome'], 'passed')
        self.assertEqual(final['recovery']['reloads_used'], 1)
        self.assertFalse(final['recovery']['exhausted'])
        self.assertFalse(final['timing_valid'])
        self.assertEqual(final['coding_wall_s'], 6)
        self.assertEqual(final['durations']['load_s'], 4)
        self.assertEqual(final['recovery']['completion_wall_s'], final['durations']['total_s'])
        self.assertEqual(len(final['inference']['records']), 2)
        self.assertEqual(recovery.read_json(self.root / 'checkpoint.json')['stage'], 'complete')

    def test_second_repetition_exhausts_bound_without_third_load(self):
        final = recovery.run_with_reload(self.runner([self.result(True), self.result(True)]),
                                         self.model, 'distinct', self.root)
        self.assertEqual(len(self.calls), 2)
        self.assertTrue(final['recovery']['exhausted'])
        self.assertEqual(final['execution_outcome'], 'runtime_error')

    def test_no_reload_after_normal_completion(self):
        final = recovery.run_with_reload(self.runner([self.result()]), self.model, 'distinct', self.root)
        self.assertEqual(len(self.calls), 1)
        self.assertTrue(final['timing_valid'])

    def test_runtime_error_without_explicit_terminal_repetition_is_not_retried(self):
        result = self.result()
        result.update(execution_outcome='runtime_error', error='backend unavailable')
        recovery.run_with_reload(self.runner([result]), self.model, 'distinct', self.root)
        self.assertEqual(len(self.calls), 1)

    def test_resource_timeout_swap_and_other_stream_errors_are_not_retried(self):
        for changes in [{'resource_stopped': True}, {'execution_outcome': 'resource_stopped'},
                        {'evaluation_timeouts': ['acceptance']}, {'swap_out_pages_delta': 1},
                        {'assistant_errors': ['backend error']},
                        {'stream_failures': [{'stop_reason': 'length'}]}]:
            with self.subTest(changes=changes):
                root = Path(self.temp.name) / ('blocked-' + str(len(list(Path(self.temp.name).iterdir()))))
                result = self.result(True)
                result.update(changes)
                self.calls = []
                recovery.run_with_reload(self.runner([result]), self.model, 'distinct', root)
                self.assertEqual(len(self.calls), 1)

    def test_correctness_failure_is_not_a_reload_reason(self):
        result = self.result()
        result.update(outcome='task_failed', execution_outcome='task_failed', correct=False)
        recovery.run_with_reload(self.runner([result]), self.model, 'distinct', self.root)
        self.assertEqual(len(self.calls), 1)

    def test_lingering_process_or_cleanup_failure_prevents_reload(self):
        with self.assertRaises(Failure):
            recovery.run_with_reload(self.runner([self.result(True)], clean=False), self.model, 'smoke', self.root, smoke=True)
        self.assertEqual(len(self.calls), 1)
        self.assertEqual(recovery.read_json(self.root / 'checkpoint.json')['stage'], 'failed')

    def test_cleanup_failure_marker_and_corrupt_lifecycle_are_rejected(self):
        self.write_attempt(self.root, self.result(True), repeat=True)
        save(self.root / 'cleanup-failure.json', {'error': 'uncertain'})
        self.assertFalse(recovery.cleanup_verified(self.root))
        (self.root / 'cleanup-failure.json').unlink()
        (self.root / 'lifecycle.jsonl').write_text('{broken')
        self.assertFalse(recovery.cleanup_verified(self.root))

    def test_correction_without_terminal_stop_does_not_reload(self):
        result = self.result(True)
        self.write_attempt(self.root, result, repeat=True)
        (self.root / 'attempt/artifacts/anti-repeat.jsonl').write_text(json.dumps({'kind': 'anti_repeat', 'type': 'corrected'}) + '\n')
        self.assertFalse(recovery.reload_decision(self.root, result)['eligible'])

    def test_interruption_never_retries_and_is_durable(self):
        calls = []
        def interrupted(model, task, root):
            calls.append(root)
            raise KeyboardInterrupt()
        with self.assertRaises(KeyboardInterrupt):
            recovery.run_with_reload(interrupted, self.model, 'distinct', self.root)
        self.assertEqual(len(calls), 1)
        self.assertEqual(recovery.read_json(self.root / 'result.json')['outcome'], 'interrupted')
        self.assertEqual(recovery.read_json(self.root / 'checkpoint.json')['stage'], 'failed')

    def test_interruption_during_reload_preserves_first_attempt_and_spent_retry(self):
        initial = self.result(True)
        def interrupted(model, task, root):
            self.calls.append(root)
            if len(self.calls) == 1:
                self.write_attempt(root, initial, repeat=True)
                return initial
            raise KeyboardInterrupt()
        with self.assertRaises(KeyboardInterrupt):
            recovery.run_with_reload(interrupted, self.model, 'distinct', self.root)
        audit = recovery.read_json(self.root / 'recovery.json')
        self.assertEqual(len(self.calls), 2)
        self.assertEqual(audit['reloads_used'], 1)
        self.assertEqual(audit['state'], 'stopped')
        self.assertEqual(recovery.read_json(self.root / 'attempt-1/result.json'), initial)

    def test_nonempty_inventory_live_endpoint_and_insufficient_reserve_block_cleanup(self):
        self.write_attempt(self.root, self.result(True), repeat=True)
        original = recovery.read_events(self.root / 'lifecycle.jsonl')
        for changes in [{'models': ['old-model']}, {'endpoint_alive': True},
                        {'memory': {'available': 10, 'reserve': 20}}]:
            with self.subTest(changes=changes):
                events = copy.deepcopy(original)
                events[0]['details'].update(changes)
                (self.root / 'lifecycle.jsonl').write_text(''.join(json.dumps(row) + '\n' for row in events))
                self.assertFalse(recovery.cleanup_verified(self.root))

    def test_plan_rejects_unbounded_or_ambiguous_retry_policies(self):
        for policy in [{'maximum_model_reloads': 2}, {'maximum_model_reloads': True}, {}, {'retries': 1}]:
            with self.subTest(policy=policy):
                with self.assertRaises(ValueError):
                    recovery.validate_policy(policy)
        recovery.validate_policy(None)
        recovery.validate_policy({'maximum_model_reloads': 1})

    def test_follow_on_dispatches_opt_in_recovery(self):
        manifest = {'repetition_recovery': {'maximum_model_reloads': 1}}
        final = follow_on.run_configured_cell(self.runner([self.result(True), self.result()]),
                                              self.model, 'distinct', self.root, manifest)
        self.assertEqual(final['recovery']['reloads_used'], 1)


if __name__ == '__main__':
    unittest.main()
