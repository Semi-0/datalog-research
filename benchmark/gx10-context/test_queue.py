"""Queue gates, immutable completion evidence and resource-only smoke fallback."""
import argparse
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from lifecycle import Failure, save
import pilot
import run_queue as queue
from suite import MANIFEST


class QueueTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name).resolve()

    def valid_pilot(self):
        return {'complete': True, 'all_tasks_passed': False, 'rows': [
            {'model': MANIFEST['coding']['pilot_model'], 'task': task,
             'result': {'outcome': 'task_failed', 'agent_end_observed': True,
                        'agent_final_response': True, 'pi_exit': 0,
                        'acceptance_counts': {'complete': True},
                        'inference': {'requests': 1, 'records': [{'complete': True,
                            'prefill_tokens': 10, 'prefill_seconds': 1,
                            'generated_tokens': 5, 'generation_seconds': 1}]}}}
            for task in MANIFEST['coding']['tasks']]}

    def test_genuine_incorrect_implementations_unlock_reruns_but_runtime_failures_do_not(self):
        output = self.root / 'pilot'
        proof = self.valid_pilot()
        save(output / 'pilot.json', proof)
        self.assertTrue(queue.stage_evidence('flash-pilot', output)['sha256'])
        for invalid in [{'outcome': 'runtime_error'}, {'agent_end_observed': False},
                        {'stream_failures': [{'stop_reason': 'length'}]},
                        {'stream_failures': [{'stop_reason': None}]},
                        {'acceptance_counts': {'complete': False}}]:
            changed = self.valid_pilot()
            changed['rows'][0]['result'].update(invalid)
            save(output / 'pilot.json', changed)
            with self.assertRaisesRegex(Failure, 'gate unresolved'):
                queue.stage_evidence('flash-pilot', output)

    def test_owned_memory_omits_unrelated_process(self):
        from resource_snapshot import owned_memory
        import os
        snapshot = owned_memory(['gx10-nonexistent-test-sentinel.service'])
        self.assertEqual([], snapshot['processes'])
        self.assertNotIn(os.getpid(), [item['pid'] for item in snapshot['processes']])

    def test_matrix_requires_every_cell_once_and_the_declared_context(self):
        output = self.root / 'matrix'
        rows = [{'phase': 'coding', 'model': model['id'], 'task': task,
                 'context': MANIFEST['coding']['context'], 'result': {'outcome': 'setup_declined'}}
                for model in MANIFEST['models'] for task in MANIFEST['coding']['tasks']]
        save(output / 'status.json', {'stage': 'complete'})
        save(output / 'summary.json', {'rows': rows})
        self.assertTrue(queue.stage_evidence('matrix', output)['sha256'])
        for invalid in [rows[:-1], rows + [rows[0]]]:
            save(output / 'summary.json', {'rows': invalid})
            with self.assertRaises(Failure):
                queue.stage_evidence('matrix', output)
        rows[0]['context'] += 8192
        save(output / 'summary.json', {'rows': rows})
        with self.assertRaisesRegex(Failure, 'contexts differ'):
            queue.stage_evidence('matrix', output)

    def test_failed_or_stale_proof_cannot_unlock_next_stage(self):
        output = self.root / 'proof'
        save(output / 'deterministic.json', {'passed': True, 'harness_hashes': {}})
        with self.assertRaisesRegex(Failure, 'stale'):
            queue.stage_evidence('deterministic', output)
        save(output / 'pilot.json', {'complete': True, 'all_tasks_passed': False})
        with self.assertRaisesRegex(Failure, 'all three tasks'):
            queue.stage_evidence('flash-pilot', output)

    def test_completed_stage_is_reused_only_with_unchanged_evidence(self):
        output = self.root / 'proof'
        save(output / 'pilot.json', self.valid_pilot())
        rows = [{'name': 'flash-pilot', 'state': 'complete', 'output': str(output),
                 'evidence': queue.stage_evidence('flash-pilot', output)}]
        with patch.object(queue.subprocess, 'run') as process:
            self.assertEqual(output, queue.run_stage(self.root, 'flash-pilot', [], rows, resume=True))
            process.assert_not_called()
        save(output / 'pilot.json', dict(self.valid_pilot(), changed=True))
        with self.assertRaisesRegex(Failure, 'evidence changed'):
            queue.run_stage(self.root, 'flash-pilot', [], rows, resume=True)

    def test_pilot_failure_prevents_live_validation_and_matrix(self):
        context = self.root / 'context'
        save(context / 'status.json', {'stage': 'context_complete'})
        args = argparse.Namespace(output=self.root / 'queue', context_run=context,
                                  context_service='unused', persistence=self.root / 'persistence.json', resume=False)
        names = []
        def stage(root, name, arguments, rows, resume):
            names.append(name)
            if name == 'flash-pilot':
                raise Failure('task_failed', 'fixture pilot failure')
            return self.root / name
        with patch.object(queue, 'run_stage', side_effect=stage):
            with self.assertRaises(Failure):
                queue.run(args)
        self.assertEqual(['deterministic', 'flash-pilot'], names)

    def context_rows(self):
        return [{'context': context, 'seed': seed, 'result': {'protocol_pass': True, 'timing_valid': True}}
                for context in [32768, 65536] for seed in MANIFEST['seeds']]

    def test_resource_only_smoke_failure_can_try_lower_validated_context(self):
        model = {'id': 'flash-iq4'}
        results = [{'outcome': 'resource_stopped'}, {'outcome': 'passed', 'protocol_pass': True}]
        with patch.object(pilot, 'run_cell', side_effect=results) as cell:
            selected = pilot.smoke_context(self.root, model, self.context_rows(), 65536, True)
        self.assertEqual(32768, selected)
        self.assertEqual([65536, 32768], [call.kwargs['context'] for call in cell.call_args_list])
        self.assertEqual(2, len(json.loads((self.root / 'smoke-contexts.json').read_text())))
        self.assertEqual(32768, json.loads((self.root / 'coding-context.json').read_text())['context'])

    def test_unknown_smoke_error_does_not_trigger_resource_fallback(self):
        with patch.object(pilot, 'run_cell', return_value={'outcome': 'runtime_error'}) as cell:
            with self.assertRaisesRegex(Failure, 'must fully pass'):
                pilot.smoke_context(self.root, {'id': 'flash-iq4'}, self.context_rows(), 65536, True)
        self.assertEqual(1, cell.call_count)


if __name__ == '__main__':
    unittest.main()
