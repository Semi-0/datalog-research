import tempfile
from pathlib import Path
import unittest
from unittest.mock import Mock, patch

import lain_host
from lifecycle import Failure


class LainHostTests(unittest.TestCase):
    def host(self):
        host = lain_host.Host.__new__(lain_host.Host)
        host.backend = None
        host.model = None
        host.clean = False
        host.seen = set()
        host.final = None
        host.active_guard = None
        return host

    def test_existing_attempt_is_never_overwritten(self):
        with tempfile.TemporaryDirectory() as directory:
            marker = Path(directory) / 'result.json'
            marker.write_text('original')
            with patch.object(lain_host.sys, 'argv', ['lain_host.py', directory]):
                with self.assertRaises(FileExistsError):
                    lain_host.main()
            self.assertEqual('original', marker.read_text())
            self.assertFalse((Path(directory) / 'host-failure.json').exists())

    def test_setup_failure_has_a_durable_result(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / 'new-attempt'
            with patch.object(lain_host.sys, 'argv', ['lain_host.py', str(output)]), \
                 patch.object(lain_host, 'serve', side_effect=Failure('setup_declined', 'busy host')):
                with self.assertRaises(Failure):
                    lain_host.main()
            self.assertEqual('setup_declined', lain_host.json.loads((output / 'result.json').read_text())['outcome'])

    def test_unknown_operation_and_repeated_effect_are_rejected(self):
        host = self.host()
        request = {'path': [0], 'operation': 'shell/run', 'arguments': None}
        with self.assertRaises(Failure):
            host.dispatch(request)
        with self.assertRaises(Failure):
            host.dispatch(request)

    def test_unverified_cleanup_cannot_admit_a_following_model(self):
        host = self.host()
        with self.assertRaises(Failure):
            host.dispatch({'path': [1], 'operation': 'model/verify-unloaded', 'arguments': 'flash'})
        host.backend = Mock()
        with self.assertRaises(Failure):
            host.enter('ornith-nvfp4')

    def test_guard_failure_still_runs_cleanup(self):
        host = self.host()
        backend = Mock(attempt_unit='owned.service')
        host.backend = backend
        host.model = 'flash'
        host.active_guard = Mock()
        host.active_guard.__exit__ = Mock(side_effect=Failure('resource_stopped', 'reserve'))
        with self.assertRaises(Failure):
            host.exit('flash')
        backend.stop.assert_called_once_with('owned.service')
        backend.cleanup.assert_called_once()
        self.assertFalse(host.clean)

    def test_cleanup_failure_has_no_clean_receipt(self):
        host = self.host()
        host.backend = Mock(attempt_unit=None)
        host.backend.cleanup.side_effect = Failure('transition_failed', 'still resident')
        host.model = 'flash'
        with self.assertRaises(Failure):
            host.exit('flash')
        self.assertFalse(host.clean)
        self.assertIsNotNone(host.backend)

    def test_final_result_requires_measurement_and_cleanup(self):
        host = self.host()
        with tempfile.TemporaryDirectory() as directory:
            host.output = Path(directory)
            host.final = {'outcome': 'passed'}
            request = {'path': ['complete'], 'operation': 'workflow/complete', 'arguments': None}
            with self.assertRaises(Failure):
                host.dispatch(request)
            host.clean = True
            host.seen.clear()
            with self.assertRaises(Failure):
                host.dispatch(request)
            self.assertFalse((host.output / 'result.json').exists())


if __name__ == '__main__':
    unittest.main()
