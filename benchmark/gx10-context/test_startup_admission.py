"""Missing startup evidence must never reach the inference backend."""
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
from pathlib import Path
import tempfile
import threading
import unittest
from unittest.mock import patch
import urllib.error
import urllib.request

from inference_metrics import InferenceProxy


class AdmissionTests(unittest.TestCase):
    def test_missing_tools_refuse_first_request_without_spending_budget(self):
        calls = []
        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *_args):
                pass
            def do_POST(self):
                calls.append(self.path)
                self.send_response(200)
                self.end_headers()
                self.wfile.write(b'{}')
        server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        def decline():
            raise RuntimeError('Required tool unavailable: clj_kondo')
        try:
            with tempfile.TemporaryDirectory() as tmp, \
                 patch.object(InferenceProxy, '_snapshot', return_value={}), \
                 patch('inference_metrics.counter_delta', return_value={}):
                root = Path(tmp)
                proxy = InferenceProxy('http://127.0.0.1:' + str(server.server_port) + '/v1',
                                       'unused', 'ollama', root, admission=decline)
                try:
                    request = urllib.request.Request(proxy.base_url + '/chat/completions', data=b'{}',
                                                     headers={'Content-Type': 'application/json'})
                    with self.assertRaises(urllib.error.HTTPError) as error:
                        urllib.request.urlopen(request, timeout=3)
                    self.assertEqual(error.exception.code, 412)
                    error.exception.close()
                    self.assertEqual(calls, [])
                    self.assertEqual(proxy.totals(), (0, 0))
                    failure = json.loads((root / 'startup-failure.json').read_text())
                    self.assertEqual(failure['outcome'], 'setup_declined')
                    self.assertIn('clj_kondo', failure['error'])
                finally:
                    proxy.close()
        finally:
            server.shutdown()
            server.server_close()
            thread.join(timeout=3)


if __name__ == '__main__':
    unittest.main()
