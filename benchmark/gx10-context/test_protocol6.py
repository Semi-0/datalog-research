"""Manifest defaults and exactly-once handoff from Flash pilot to the 12-cell matrix."""
import argparse
from contextlib import nullcontext
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import pilot
import suite
from lifecycle import save


class Protocol6Tests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)

    def test_explicit_context_command_limits_and_absence_of_task_deadline(self):
        config = suite.MANIFEST
        self.assertEqual(131072, config['coding']['context'])
        self.assertEqual(131072, config['pi_phase']['coding_context_tokens'])
        self.assertIsNone(config['coding']['wall_limit_seconds'])
        self.assertIsNone(config['pi_phase']['wall_limit_seconds'])
        self.assertEqual(120, config['coding']['tool_timeout_seconds'])
        self.assertEqual(120, config['coding']['evaluation_timeout_seconds'])

    def test_workspace_estimate_is_explicit_and_does_not_change_physical_reserve(self):
        from model_setup import budget_projection
        info = {'general.architecture': 'fixture', 'fixture.block_count': 48,
                'fixture.full_attention_interval': 4, 'fixture.attention.head_count_kv': 2,
                'fixture.attention.key_length': 256, 'fixture.attention.value_length': 256}
        reserve = 26118804275
        old = budget_projection(info, 93682584546, 131072, 127284080640, reserve)
        bounded = budget_projection(info, 93682584546, 131072, 127284080640, reserve,
                                    workspace_allowance=3758096384)
        self.assertFalse(old['allowed'])
        self.assertTrue(bounded['allowed'])
        self.assertEqual(reserve, bounded['reserve_bytes'])
        self.assertEqual(3758096384, bounded['workspace_allowance_bytes'])

    def test_coding_only_pilot_keeps_requested_context_and_does_not_scan_or_fallback(self):
        proof = self.root / 'deterministic.json'
        save(proof, {'passed': True, 'harness_hashes': suite.harness_hashes()})
        args = argparse.Namespace(output=self.root / 'pilot', deterministic=proof,
            model='flash-iq4', coding_only=True, continue_context_from=None)
        with patch.object(pilot, 'experiment_lock', return_value=nullcontext()), \
             patch.object(pilot, 'run_cell', return_value={'outcome': 'passed', 'protocol_pass': True}) as cell, \
             patch.object(pilot, 'scan_contexts') as scan:
            pilot.run(args)
        scan.assert_not_called()
        self.assertEqual(4, cell.call_count)
        self.assertTrue(all(call.kwargs['context'] == 131072 for call in cell.call_args_list))
        rows = json.loads((args.output / 'results.json').read_text())
        self.assertEqual(['distinct', 'order', 'events'], [row['task'] for row in rows])

    def test_completed_flash_cells_are_retained_and_only_nine_more_coding_cells_run(self):
        prior = self.root / 'pilot'
        save(prior / 'manifest.json', suite.MANIFEST)
        save(prior / 'harness-hashes.json', suite.harness_hashes())
        flash = suite.model_by_id('flash-iq4')
        for task in suite.MANIFEST['coding']['tasks']:
            cell = prior / 'cells' / task
            save(cell / 'cell.json', {'model': flash, 'task': task, 'context': 131072, 'seed': None})
            save(cell / 'checkpoint.json', {'stage': 'complete'})
            save(cell / 'result.json', {'outcome': 'passed', 'protocol_pass': True})
        args = argparse.Namespace(output=self.root / 'matrix', continue_from=prior, validation=self.root / 'unused')
        with patch.object(suite, 'check_validation'), patch.object(suite.Backend, 'cleanup'), \
             patch.object(suite, 'scan_contexts'), \
             patch.object(suite, 'run_cell', return_value={'outcome': 'passed', 'protocol_pass': True}) as cell:
            suite.run(args)
        self.assertEqual(9, cell.call_count)
        self.assertTrue(all(call.args[0]['id'] != 'flash-iq4' and call.args[3] == 131072
                            for call in cell.call_args_list))
        rows = json.loads((args.output / 'results.json').read_text())
        self.assertEqual(12, len(rows))
        self.assertEqual(12, len({(row['model'], row['task']) for row in rows}))


if __name__ == '__main__':
    unittest.main()
