import hashlib
import json
from pathlib import Path
import sys
import tempfile
import unittest
sys.path.insert(0, str(Path(__file__).resolve().parent.parent / 'gx10-context'))
from outcomes import classify
from continuation import completed_cell

PASS = 'Ran 3 tests containing 14 assertions.\n0 failures, 0 errors.\n'


class CorrectnessPolicyTests(unittest.TestCase):
    def test_correct_solution_passes_despite_missing_workflow_tools(self):
        result = {'task': 'distinct', 'acceptance_exit': 0,
                  'agent_end_observed': True, 'agent_final_response': True, 'pi_exit': 0,
                  'workflow_diagnostics': [{'error': 'Missing Lens diagnostics'}],
                  'swap_in_pages_delta': 0, 'swap_out_pages_delta': 0}
        classify(result, '', PASS)
        self.assertEqual('passed', result['outcome'])
        self.assertTrue(result['correct'])
        self.assertFalse(result['workflow_pass'])
        self.assertFalse(result['protocol_pass'])

    def test_incomplete_execution_retains_invalid_timing_even_if_correct(self):
        result = {'task': 'distinct', 'acceptance_exit': 0, 'pi_exit': 1,
                  'swap_in_pages_delta': 0, 'swap_out_pages_delta': 0}
        classify(result, '', PASS)
        self.assertEqual('passed', result['outcome'])
        self.assertEqual('runtime_error', result['execution_outcome'])
        self.assertFalse(result['timing_valid'])

    def test_zero_assertions_cannot_establish_correctness(self):
        result = {'task': 'distinct', 'acceptance_exit': 0}
        classify(result, '', 'Ran 0 tests containing 0 assertions.\n0 failures, 0 errors.')
        self.assertFalse(result['correct'])
        self.assertNotEqual('passed', result['outcome'])

    def test_interrupted_cell_is_retried_and_completed_pass_is_retained(self):
        with tempfile.TemporaryDirectory() as name:
            cell = Path(name) / 'distinct'
            cell.mkdir()
            (cell / 'checkpoint.json').write_text(json.dumps({'stage': 'complete'}))
            raw = cell / 'result.json'
            raw.write_text(json.dumps({'outcome': 'interrupted', 'model': 'ornith-nvfp4-mtp'}))
            self.assertIsNone(completed_cell(name, 'distinct', 'ornith-nvfp4'))
            raw.write_text(json.dumps({'outcome': 'task_failed', 'model': 'ornith-nvfp4-mtp', 'correct': True}))
            digest = hashlib.sha256(raw.read_bytes()).hexdigest()
            result = completed_cell(name, 'distinct', 'ornith-nvfp4')
            self.assertEqual('passed', result['outcome'])
            self.assertEqual(digest, hashlib.sha256(raw.read_bytes()).hexdigest())
