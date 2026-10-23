from concurrent.futures import ThreadPoolExecutor
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
from pathlib import Path
import tempfile
import threading
import time
import unittest
import urllib.error
import urllib.request

from proxy import BoundedProxy


class ProxyTests(unittest.TestCase):
    def test_parallel_wire_preservation_usage_and_per_worker_request_cap(self):
        barrier = threading.Barrier(2)
        lock = threading.Lock()
        calls = []
        body = ('data: ' + json.dumps({'choices': [{'finish_reason': 'stop'}],
                  'usage': {'prompt_tokens': 16, 'completion_tokens': 3,
                            'prompt_tokens_details': {'cached_tokens': 8}}}) + '\n\ndata: [DONE]\n\n').encode()
        class Backend(BaseHTTPRequestHandler):
            def log_message(self, *args): pass
            def do_POST(self):
                data = self.rfile.read(int(self.headers['Content-Length']))
                with lock: calls.append(data)
                barrier.wait(timeout=3)
                self.send_response(200); self.send_header('Content-Type', 'text/event-stream'); self.end_headers()
                self.wfile.write(body)
        server = ThreadingHTTPServer(('127.0.0.1', 0), Backend)
        thread = threading.Thread(target=server.serve_forever, daemon=True); thread.start()
        try:
            with tempfile.TemporaryDirectory() as tmp:
                root = Path(tmp); jobs = []
                for lane in ['a', 'b']:
                    folder = root / lane; (folder / 'artifacts').mkdir(parents=True)
                    jobs.append({'lane': lane, 'root': str(folder)})
                proxy = BoundedProxy('http://127.0.0.1:' + str(server.server_port) + '/v1', jobs,
                                     {'maximum_requests': 1, 'maximum_generated_tokens': 100})
                payload = json.dumps({'model': 'fixture', 'stream': True, 'messages': [], 'max_tokens': 8}).encode()
                def request(lane):
                    req = urllib.request.Request(proxy.url(lane) + '/chat/completions', data=payload,
                                                 headers={'Content-Type': 'application/json'})
                    with urllib.request.urlopen(req, timeout=5) as response: return response.read()
                try:
                    with ThreadPoolExecutor(max_workers=2) as pool:
                        futures = [pool.submit(request, lane) for lane in ['a', 'b']]
                        self.assertEqual([body, body], [future.result() for future in futures])
                    limit = time.monotonic() + 3
                    while any(proxy.totals(lane)['tokens'] != 3 for lane in ['a', 'b']) and time.monotonic() < limit:
                        time.sleep(.01)
                    for lane in ['a', 'b']:
                        self.assertEqual({'requests': 1, 'tokens': 3, 'incomplete': 0}, proxy.totals(lane))
                        record = json.loads((root / lane / 'artifacts/inference-requests.jsonl').read_text())
                        self.assertTrue(record['complete'])
                        self.assertEqual(8, record['usage']['prompt_tokens_details']['cached_tokens'])
                        with self.assertRaises(urllib.error.HTTPError) as caught: request(lane)
                        self.assertEqual(429, caught.exception.code)
                        caught.exception.close()
                    self.assertEqual([payload, payload], calls)
                    with self.assertRaises(ValueError): proxy.url('unknown')
                finally: proxy.close()
        finally:
            server.shutdown(); server.server_close(); thread.join(timeout=5)

    def test_token_budget_declines_without_forwarding(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp); (root / 'artifacts').mkdir()
            proxy = BoundedProxy('http://127.0.0.1:1/v1', [{'lane': 'worker', 'root': str(root)}],
                                 {'maximum_requests': 128, 'maximum_generated_tokens': 0})
            try:
                req = urllib.request.Request(proxy.url('worker') + '/chat/completions', b'{}')
                with self.assertRaises(urllib.error.HTTPError) as caught: urllib.request.urlopen(req, timeout=3)
                self.assertEqual(429, caught.exception.code)
                caught.exception.close()
                self.assertEqual(0, proxy.totals('worker')['requests'])
            finally: proxy.close()


if __name__ == '__main__': unittest.main()
