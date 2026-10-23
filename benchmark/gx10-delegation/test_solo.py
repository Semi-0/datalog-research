import copy
from contextlib import nullcontext
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import solo
import schedule
from lifecycle import Failure, save


class SoloTests(unittest.TestCase):
    def run_case(self, root, outcomes):
        manifest = copy.deepcopy(solo.suite.MANIFEST)
        with patch.object(solo.suite, 'MANIFEST', manifest), \
             patch.object(solo, 'experiment_lock', return_value=nullcontext()), \
             patch.object(solo.suite, 'run_cell', side_effect=[{'outcome': value} for value in outcomes]) as run:
            result = solo.execute(root)
            self.assertEqual(manifest['sampling']['temperature'], 1.0)
            self.assertEqual(manifest['coding']['tool_profile'], 'enhanced')
            self.assertTrue(all(call.args[0]['id'] == 'ornith-bf16' for call in run.call_args_list))
            self.assertTrue(all(call.args[2].parent == root for call in run.call_args_list))
            return result

    def test_nvfp4_selection_applies_to_all_three_cells(self):
        with tempfile.TemporaryDirectory() as tmp:
            manifest = copy.deepcopy(solo.suite.MANIFEST)
            with patch.object(solo.suite, 'MANIFEST', manifest), \
                 patch.object(solo, 'experiment_lock', return_value=nullcontext()), \
                 patch.object(solo.suite, 'run_cell', return_value={'outcome': 'passed'}) as run:
                solo.execute(Path(tmp) / 'solo', 'ornith-nvfp4')
                self.assertEqual(run.call_count, 3)
                self.assertEqual({call.args[0]['id'] for call in run.call_args_list}, {'ornith-nvfp4'})

    def test_task_failure_or_clean_budget_decline_retains_all_three_outcomes(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp) / 'solo'
            rows = self.run_case(root, ['setup_declined', 'task_failed', 'passed'])
            self.assertEqual(len(rows), 3)
            self.assertEqual(json.loads((root / 'results.json').read_text()), rows)
            self.assertEqual(json.loads((root / 'status.json').read_text())['stage'], 'complete')

    def test_reserve_breach_stops_before_next_cell(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp) / 'solo'
            with self.assertRaises(Failure):
                self.run_case(root, ['resource_stopped'])
            self.assertEqual(len(json.loads((root / 'results.json').read_text())), 1)
            self.assertFalse((root / 'status.json').exists())

    def test_startup_decline_stops_without_cascading_to_other_cells(self):
        with tempfile.TemporaryDirectory() as tmp:
            manifest = copy.deepcopy(solo.suite.MANIFEST)
            with patch.object(solo.suite, 'MANIFEST', manifest), \
                 patch.object(solo, 'experiment_lock', return_value=nullcontext()), \
                 patch.object(solo.suite, 'run_cell', return_value={'outcome': 'setup_declined', 'startup_failure': True}) as run:
                with self.assertRaises(Failure):
                    solo.execute(Path(tmp) / 'solo', 'ornith-nvfp4')
                self.assertEqual(run.call_count, 1)

    def test_infrastructure_error_stops_before_next_cell(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp) / 'solo'
            with self.assertRaises(Failure):
                self.run_case(root, ['runtime_error'])
            self.assertEqual(len(json.loads((root / 'results.json').read_text())), 1)

    def test_solo_admission_does_not_require_bubblewrap(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            save(root / 'status.json', {'stage': 'complete'})
            with patch.object(schedule.subprocess, 'run') as run:
                run.return_value.returncode = 0
                run.return_value.stdout = 'dead\n'
                self.assertEqual(schedule.benchmark_state('previous.service', root), 'ready')
                self.assertEqual(run.call_count, 1)
                self.assertEqual(run.call_args.args[0][0], 'systemctl')


if __name__ == '__main__':
    unittest.main()
