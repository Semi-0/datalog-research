from contextlib import nullcontext
import json
from pathlib import Path
import tempfile
import threading
import unittest
from unittest.mock import patch
import urllib.error
import urllib.request

import runtime
from lifecycle import Failure, save
from proxy import BoundedProxy


class WorkerStartupTests(unittest.TestCase):
    def test_missing_tools_block_worker_proxy_without_spending_budget(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / 'artifacts').mkdir()
            def missing(_root):
                raise Failure('setup_declined', 'Missing worker tool readiness')
            proxy = BoundedProxy('http://127.0.0.1:1/v1', [{'lane': 'worker', 'root': str(root)}],
                                 {'maximum_requests': 4, 'maximum_generated_tokens': 100}, admission=missing)
            try:
                request = urllib.request.Request(proxy.url('worker') + '/chat/completions', data=b'{}')
                with self.assertRaises(urllib.error.HTTPError) as error:
                    urllib.request.urlopen(request, timeout=3)
                self.assertEqual(error.exception.code, 412)
                error.exception.close()
                self.assertEqual(proxy.totals('worker')['requests'], 0)
                self.assertEqual(json.loads((root / 'artifacts/startup-failure.json').read_text())['outcome'], 'setup_declined')
            finally:
                proxy.close()

    def test_startup_failure_stops_owned_batch_and_preserves_failure_classification(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            candidate = root / 'worker'
            save(candidate / 'agent/models.json', {'providers': {'local-benchmark': {}}})
            stopped = threading.Event()
            units = []
            class Backend:
                state = {'attempt_results': {}}
                def __init__(self):
                    self.root = root
                def persist(self):
                    pass
                def guard(self, _name):
                    return nullcontext()
                def launch(self, _unit, _command, _environment):
                    save(candidate / 'artifacts/startup-failure.json', {'outcome': 'setup_declined'})
                def stop(self, unit):
                    units.append(unit)
                    stopped.set()
            def supervise(*_args):
                if not stopped.wait(3):
                    raise AssertionError('Startup monitor failed to stop the owned batch')
                raise Failure('runtime_error', 'Owned batch exited before a result')
            job = {'lane': 'worker', 'root': str(candidate)}
            with patch.object(runtime, 'metrics_endpoint', return_value=('fixture', 'vllm')), \
                 patch.object(runtime, 'metrics', return_value=''), \
                 patch.object(runtime, 'counters', return_value={}), \
                 patch.object(runtime, 'BoundedProxy') as proxy, \
                 patch.object(runtime, 'supervise', side_effect=supervise):
                proxy.return_value.url.return_value = 'http://127.0.0.1:1/v1'
                with self.assertRaises(Failure) as error:
                    runtime.run_agents(Backend(), [job], root / 'batch', {'coding': {'supervision': {}}}, 'fixture')
                self.assertEqual(error.exception.outcome, 'setup_declined')
                self.assertTrue(units)
                self.assertEqual(len(set(units)), 1)
                proxy.return_value.close.assert_called_once()


if __name__ == '__main__':
    unittest.main()
