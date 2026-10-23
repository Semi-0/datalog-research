from contextlib import nullcontext
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import context_tail
from lifecycle import Failure, save


class ContextTailTests(unittest.TestCase):
    def test_running_orchestrator_does_not_admit_scan(self):
        with patch.object(context_tail.subprocess, 'run') as run:
            run.return_value.returncode = 0
            run.return_value.stdout = 'running\n'
            self.assertFalse(context_tail.predecessor_ready('queue.service', Path('/absent')))

    def test_missing_orchestration_result_blocks_finished_queue(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            save(root / 'status.json', {'stage': 'finished', 'exit': 0})
            with patch.object(context_tail.subprocess, 'run') as run:
                run.return_value.returncode = 0
                run.return_value.stdout = 'exited\n'
                with self.assertRaises(Failure):
                    context_tail.predecessor_ready('queue.service', root)

    def test_retains_completed_probe_and_retries_interrupted_probe_fresh(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp) / 'new'
            root.mkdir()
            old = Path(tmp) / 'old'
            old.mkdir()
            result = {'outcome': 'passed'}
            save(old / 'result.json', result)
            rows = [{'phase': 'context', 'model': 'ornith-nvfp4', 'context': 32768,
                     'seed': 42, 'path': str(old), 'result': result},
                    {'phase': 'context', 'model': 'ornith-nvfp4', 'context': 65536,
                     'seed': 44, 'path': str(old), 'result': {'outcome': 'interrupted'}}]
            def scan(_root, models, schedule):
                self.assertEqual([m['id'] for m in models], ['ornith-nvfp4'])
                self.assertEqual(schedule('context', models[0], 'retrieval', 32768, 42, 'tokenizer'), result)
                schedule('context', models[0], 'retrieval', 65536, 44, 'tokenizer')
            with patch.object(context_tail, 'experiment_lock', return_value=nullcontext()), \
                 patch.object(context_tail, 'Backend') as backend, \
                 patch.object(context_tail.suite, 'retained_rows', return_value=rows), \
                 patch.object(context_tail.suite, 'scan_contexts', side_effect=scan), \
                 patch.object(context_tail.suite, 'run_cell', return_value=result) as run:
                context_tail.scan(root, old)
                backend.return_value.cleanup.assert_called_once()
                run.assert_called_once()
                self.assertTrue(run.call_args.args[2].is_relative_to(root / 'cells'))
                self.assertFalse(run.call_args.args[2].exists())
            self.assertEqual(len(json.loads((root / 'context-results.json').read_text())), 2)


if __name__ == '__main__':
    unittest.main()
