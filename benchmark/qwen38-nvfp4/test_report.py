import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location('qwen_trial', HERE / 'run.py')
trial = importlib.util.module_from_spec(spec)
spec.loader.exec_module(trial)


class ReportTests(unittest.TestCase):
    def test_rates_use_measured_token_totals_and_unknown_stays_unknown(self):
        self.assertEqual(trial.rates({})['generation_tokens_per_second'], None)
        result = {'inference': {'records': [
            {'complete': True, 'generated_tokens': 20, 'generation_seconds': 2},
            {'complete': True, 'generated_tokens': 90, 'generation_seconds': 3},
            {'complete': True, 'metrics_error': 'invalid timing', 'generated_tokens': 1000, 'generation_seconds': 0},
            {'complete': False, 'generated_tokens': 5000, 'generation_seconds': 1}]}}
        self.assertEqual(trial.rates(result)['generation_tokens_per_second'], 22)

    def test_cached_prefill_is_explicitly_labelled(self):
        result = {'inference': {'records': [{'complete': True,
                  'prefill_basis': 'submitted_prompt_tokens_including_cache_hits',
                  'prefill_tokens': 20000, 'prefill_seconds': 1}]}}
        measured = trial.rates(result)
        self.assertEqual(measured['prefill_tokens_per_second'], 20000)
        self.assertEqual(measured['prefill_basis'], 'submitted_prompt_tokens_including_cache_hits')
        self.assertNotIn('uncached_prefill_tokens_per_second', measured)

    def test_controller_uses_smoke_contract_then_all_three_cells(self):
        import contextlib
        import copy
        from unittest.mock import patch
        calls = []
        def cell(model, task, path, **options):
            calls.append((task, options.get('smoke', False)))
            if options.get('smoke'):
                contracts = json.loads((HERE.parent / 'gx10-context/smoke-contract/tasks.json').read_text())
                self.assertIn(task, [item['id'] for item in contracts])
            return {'outcome': 'passed', 'execution_outcome': 'passed', 'correct': True}
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory) / 'fresh'
            with patch.object(trial.suite, 'MANIFEST', copy.deepcopy(trial.suite.MANIFEST)), \
                 patch.object(trial.suite, 'run_cell', side_effect=cell), \
                 patch.object(trial, 'wait_download'), \
                 patch.object(trial, 'collect_baselines', return_value=[]), \
                 patch.object(trial, 'experiment_lock', return_value=contextlib.nullcontext()), \
                 patch.object(trial.sys, 'argv', ['run.py', '--output', str(root)]):
                trial.main()
            self.assertEqual(calls, [('smoke', True), ('distinct', False), ('order', False), ('events', False)])
            self.assertEqual(json.loads((root / 'status.json').read_text())['completed_cells'], 3)

    def test_historical_correctness_does_not_rewrite_execution_failure(self):
        row = {'cohort': 'old', 'cell': {'task': 'events', 'model': {'id': 'ornith-q4'}},
               'result_path': '/original/result.json', 'result': {
                   'correct': True, 'outcome': 'budget_exhausted', 'timing_valid': False}}
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            trial.report(root, [row], [])
            saved = json.loads((root / 'comparison.json').read_text())
            self.assertEqual(saved['historical'][0]['result']['outcome'], 'budget_exhausted')
            self.assertTrue(saved['historical'][0]['result']['correct'])
            self.assertIn('unknown', (root / 'REPORT.md').read_text())


if __name__ == '__main__':
    unittest.main()
