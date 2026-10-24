import importlib.util
import json
from pathlib import Path
import tempfile
import types
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('finish_events', Path(__file__).with_name('finish_events.py'))
runner = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runner)


class FinishEventsTests(unittest.TestCase):
    def exercise(self, pid='0', failure=None):
        from contextlib import nullcontext
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory)
            previous = base / 'previous'
            previous.mkdir()
            plan = {'cohorts': [{'label': 'qwen36', 'model': {'id': 'qwen36-35b-a3b-nvfp4'}}],
                    'manifest': {'coding': {'context': 131072}}}
            (previous / 'plan.json').write_text(json.dumps(plan))
            originals = {}
            for task in ['distinct', 'order']:
                path = previous / 'qwen36/solo' / task / 'result.json'
                path.parent.mkdir(parents=True)
                path.write_text(json.dumps({'correct': True, 'outcome': 'passed',
                                            'execution_outcome': 'runtime_error'}))
                originals[path] = path.read_bytes()
            def save(path, value):
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(json.dumps(value))
            calls = []
            def run(*args):
                calls.append(args)
                if failure is not None:
                    raise failure
                return {'correct': True, 'outcome': 'passed', 'execution_outcome': 'passed'}
            modules = {'suite': types.SimpleNamespace(run_cell=object()),
                       'lifecycle': types.SimpleNamespace(save=save, experiment_lock=lambda _: nullcontext()),
                       'repetition_recovery': types.SimpleNamespace(run_with_reload=run)}
            output = base / 'output'
            with patch.dict('sys.modules', modules), patch.object(runner.subprocess, 'check_output', return_value=pid):
                if failure is not None or pid != '0':
                    with self.assertRaises(RuntimeError):
                        runner.execute(base, previous, output)
                else:
                    runner.execute(base, previous, output)
            for path, raw in originals.items():
                self.assertEqual(raw, path.read_bytes())
            return calls, json.loads((output / 'status.json').read_text()), output.joinpath('summary.json').exists()

    def test_runs_only_events_and_preserves_prior_runtime_error(self):
        calls, status, summary = self.exercise()
        self.assertEqual([args[2] for args in calls], ['events'])
        self.assertEqual(status['stage'], 'finished')
        self.assertTrue(summary)

    def test_running_predecessor_prevents_execution(self):
        calls, status, summary = self.exercise(pid='123')
        self.assertEqual(calls, [])
        self.assertEqual(status['stage'], 'stopped')
        self.assertFalse(summary)

    def test_uncertain_cleanup_is_not_converted_to_completion(self):
        calls, status, summary = self.exercise(failure=RuntimeError('cleanup uncertain'))
        self.assertEqual(len(calls), 1)
        self.assertEqual(status['stage'], 'stopped')
        self.assertFalse(summary)


if __name__ == '__main__':
    unittest.main()
