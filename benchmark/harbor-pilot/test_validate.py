import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('harbor_validate', Path(__file__).with_name('validate.py'))
pilot = importlib.util.module_from_spec(spec)
spec.loader.exec_module(pilot)


class HarborPilotTests(unittest.TestCase):
    def result(self, reward, error=None):
        with tempfile.TemporaryDirectory() as directory:
            job = Path(directory)
            trial = job / 'trial'
            trial.mkdir()
            (trial / 'result.json').write_text(json.dumps({'trial_name': 'trial',
                'exception_info': error, 'verifier_result': {'rewards': {'reward': reward}}}))
            return pilot.trial_reward(job)

    def test_independent_reward_controls_correctness(self):
        self.assertTrue(self.result(1))
        self.assertFalse(self.result(0))

    def test_runtime_error_is_not_hidden_by_reward(self):
        with self.assertRaisesRegex(RuntimeError, 'trial error'):
            self.result(1, {'exception_type': 'ApiConnectionClosedError'})

    def test_missing_trial_is_not_success(self):
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaisesRegex(RuntimeError, 'exactly one'):
                pilot.trial_reward(Path(directory))

    def test_cleanup_removes_only_label_selected_containers(self):
        with patch.object(pilot, 'owned_containers', side_effect=[['owned-id'], []]), \
             patch.object(pilot.subprocess, 'run') as run:
            pilot.remove_containers()
        run.assert_called_once_with(['docker', 'rm', '-f', 'owned-id'], check=True, timeout=60)

    def test_lingering_container_blocks_cleanup(self):
        with patch.object(pilot, 'owned_containers', return_value=['owned-id']), \
             patch.object(pilot.subprocess, 'run'):
            with self.assertRaisesRegex(RuntimeError, 'remain'):
                pilot.remove_containers()

    def test_endpoint_does_not_duplicate_api_prefix(self):
        self.assertEqual(pilot.api_url('http://127.0.0.1:8000/v1/'), 'http://127.0.0.1:8000/v1')
        self.assertEqual(pilot.api_url('http://127.0.0.1:8000'), 'http://127.0.0.1:8000/v1')


if __name__ == '__main__':
    unittest.main()
