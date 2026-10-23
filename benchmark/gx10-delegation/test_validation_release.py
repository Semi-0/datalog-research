import json
from pathlib import Path
import tempfile
import unittest
from unittest import mock
import schedule


class ValidationReleaseTests(unittest.TestCase):
    def test_verified_validation_release_is_distinct_from_benchmark_completion(self):
        with tempfile.TemporaryDirectory() as name:
            root = Path(name)
            receipt = {'stage': 'finished', 'exit': 0, 'validation_complete': True, 'cleanup_verified': True}
            (root / 'status.json').write_text(json.dumps(receipt))
            with mock.patch.object(schedule.subprocess, 'run', return_value=mock.Mock(returncode=0, stdout='exited\n')):
                self.assertEqual('ready', schedule.benchmark_state('validation.service', root))
                for field in ['validation_complete', 'cleanup_verified', 'exit']:
                    invalid = dict(receipt)
                    invalid.pop(field)
                    (root / 'status.json').write_text(json.dumps(invalid))
                    with self.assertRaises(RuntimeError):
                        schedule.benchmark_state('validation.service', root)

    def test_running_or_failed_validation_service_cannot_release_queue(self):
        with mock.patch.object(schedule.subprocess, 'run', return_value=mock.Mock(returncode=0, stdout='running\n')):
            self.assertEqual('waiting_for_benchmark', schedule.benchmark_state('validation.service', Path('/absent')))
        with mock.patch.object(schedule.subprocess, 'run', return_value=mock.Mock(returncode=0, stdout='failed\n')):
            with self.assertRaises(RuntimeError):
                schedule.benchmark_state('validation.service', Path('/absent'))
