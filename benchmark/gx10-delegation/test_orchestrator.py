import contextlib
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import orchestrator
from lifecycle import Failure, save


class FakeHost:
    def __init__(self, output, fail=None, admission=None):
        self.output = output
        self.backend = None
        self.model = None
        self.calls = []
        self.fail = fail
        self.admission = admission
        if admission is None:
            self.admission = [True, True, True, True]

    def dispatch(self, request):
        self.calls.append(request)
        identity = request['path'][0]
        operation = request['operation']
        if identity == self.fail:
            raise Failure('transition_failed', 'controlled failure')
        if operation == 'model/enter':
            self.backend = object()
            self.model = request['arguments']
            return {'admission': self.admission}
        if operation == 'model/exit':
            self.backend = None
            self.model = None
        if operation == 'workflow/complete':
            save(self.output / 'result.json', {'outcome': 'passed'})
        return {}


class OrchestratorTests(unittest.TestCase):
    def run_case(self, directory, fail=None, admission=None):
        output = Path(directory) / 'attempt'
        host = FakeHost(output, fail, admission)
        with patch.object(orchestrator, 'experiment_lock', return_value=contextlib.nullcontext()):
            orchestrator.execute(output, lambda _: host)
        return host

    def test_full_schedule_uses_one_backend_for_smoke_and_workers(self):
        with tempfile.TemporaryDirectory() as directory:
            host = self.run_case(directory)
            models = [r['arguments'] for r in host.calls if r['operation'] == 'model/enter']
            self.assertEqual(['flash', 'ornith-nvfp4', 'flash'], models)
            self.assertEqual('passed', json.loads((host.output / 'result.json').read_text())['outcome'])
            self.assertEqual('complete', json.loads((host.output / 'status.json').read_text())['state'])
            self.assertEqual(len(orchestrator.STEPS), len(list((host.output / 'steps').glob('*.json'))))

    def test_cleanup_failure_prevents_next_load(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / 'attempt'
            host = FakeHost(output, fail='planning-cleanup')
            with patch.object(orchestrator, 'experiment_lock', return_value=contextlib.nullcontext()):
                with self.assertRaises(Failure):
                    orchestrator.execute(output, lambda _: host)
            models = [r['arguments'] for r in host.calls if r['operation'] == 'model/enter']
            self.assertEqual(['flash'], models)
            self.assertTrue((output / 'steps/planning-cleanup.json').exists())
            self.assertEqual('transition_failed', json.loads((output / 'result.json').read_text())['outcome'])

    def test_worker_failure_cleans_up_without_loading_integrator(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / 'attempt'
            host = FakeHost(output, fail='four-workers')
            with patch.object(orchestrator, 'experiment_lock', return_value=contextlib.nullcontext()):
                with self.assertRaises(Failure):
                    orchestrator.execute(output, lambda _: host)
            self.assertIsNone(host.backend)
            self.assertEqual('emergency-cleanup', host.calls[-1]['path'][0])
            self.assertNotIn('integration-load', [r['path'][0] for r in host.calls])

    def test_unknown_or_nonboolean_admission_never_starts_workers(self):
        for admission in [[True, None, True, True], [1, 1, 1, 1], [True, False, True, True]]:
            with self.subTest(admission=admission), tempfile.TemporaryDirectory() as directory:
                output = Path(directory) / 'attempt'
                host = FakeHost(output, admission=admission)
                with patch.object(orchestrator, 'experiment_lock', return_value=contextlib.nullcontext()):
                    with self.assertRaises(Failure):
                        orchestrator.execute(output, lambda _: host)
                self.assertNotIn('two-worker-smoke', [r['path'][0] for r in host.calls])
                self.assertIsNone(host.backend)

    def test_failed_prior_service_blocks_even_with_complete_marker(self):
        import schedule
        from types import SimpleNamespace
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory)
            (output / 'status.json').write_text('{"stage":"complete"}')
            with patch.object(schedule.subprocess, 'run', return_value=SimpleNamespace(returncode=0, stdout='failed\n')) as run:
                with self.assertRaises(RuntimeError):
                    schedule.ready_state('previous.service', output)
                self.assertEqual(1, run.call_count)

    def test_existing_result_is_preserved(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / 'attempt'
            output.mkdir()
            (output / 'result.json').write_text('original')
            with self.assertRaises(FileExistsError):
                orchestrator.execute(output, FakeHost)
            self.assertEqual('original', (output / 'result.json').read_text())


if __name__ == '__main__':
    unittest.main()
