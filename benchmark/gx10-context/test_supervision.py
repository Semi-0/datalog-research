"""Progress policy and measured inference boundaries; no live model is needed."""
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
from pathlib import Path
import subprocess
import tempfile
import threading
import unittest
import urllib.request

from inference_metrics import InferenceProxy, counters, counter_delta
from supervision import ProgressSupervisor

POLICY = {'warn_no_progress_seconds': 10, 'steer_no_progress_seconds': 15,
          'stop_no_progress_seconds': 30, 'initial_evidence_limit': 2,
          'maximum_requests': 128, 'maximum_generated_tokens': 200000}


class ProgressTests(unittest.TestCase):
    def test_warn_steer_stop_without_classifying_nontermination(self):
        progress = ProgressSupervisor(POLICY, 0)
        self.assertEqual('warn', progress.check(10, 0, 0)['action'])
        self.assertIsNone(progress.check(11, 0, 0))
        self.assertEqual('steer', progress.check(15, 0, 0)['action'])
        self.assertEqual('stalled', progress.check(30, 0, 0)['outcome'])

    def test_tokens_compaction_and_file_rewrites_do_not_reset_progress(self):
        progress = ProgressSupervisor(POLICY, 0)
        for i in range(1, 30):
            self.assertIsNone(progress.observe_tool('write', 'changed ' + str(i), False, i))
        self.assertEqual('stalled', progress.check(30, 20, 10000)['outcome'])

    def test_reference_evidence_is_novel_bounded_and_requires_success(self):
        progress = ProgressSupervisor(POLICY, 0)
        self.assertIsNone(progress.observe_tool('read', 'missing', True, 5))
        self.assertTrue(progress.observe_tool('read', 'first', False, 10))
        self.assertIsNone(progress.observe_tool('read', 'first', False, 20))
        self.assertTrue(progress.observe_tool('fetch_content', 'second', False, 25))
        self.assertIsNone(progress.observe_tool('read', 'third', False, 40))
        self.assertEqual('stalled', progress.check(55, 0, 0)['outcome'])

    def test_improved_assertions_allow_productive_work_past_any_fixed_deadline(self):
        progress = ProgressSupervisor(POLICY, 0)
        for i in range(1, 200):
            now = i * 20
            result = f'Ran 1 tests containing {i} assertions.\n0 failures, 0 errors.'
            self.assertTrue(progress.observe_tool('bash', result, False, now))
            self.assertIsNone(progress.check(now, 50, 10000))
        self.assertIsNone(progress.observe_tool('bash', result, False, now + 10))
        self.assertEqual('stalled', progress.check(now + 30, 50, 10000)['outcome'])

    def test_zero_assertions_and_budget_exhaustion(self):
        progress = ProgressSupervisor(POLICY, 0)
        self.assertIsNone(progress.observe_tool('bash', 'Ran 0 tests containing 0 assertions.\n0 failures, 0 errors.', False, 20))
        self.assertEqual('budget_exhausted', progress.check(20, 128, 0)['outcome'])
        self.assertEqual('budget_exhausted', progress.check(20, 0, 200000)['outcome'])

    def test_runtime_delivers_progress_steer_without_restarting_agent(self):
        node = __import__('shutil').which('node')
        if node is None:
            self.skipTest('Node not installed')
        runtime = Path(__file__).with_name('pi-runtime.mjs')
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / 'agent').mkdir()
            (root / 'artifacts').mkdir()
            (root / 'agent/benchmark-policy.json').write_text(json.dumps({'supervision': POLICY}))
            script = '''import runtime from %s;
import {writeFileSync,existsSync} from 'node:fs';
const handlers=new Map(); let delivered;
const pi={on:(name,fn)=>handlers.set(name,[...(handlers.get(name)||[]),fn]),
 sendUserMessage:(message,options)=>{delivered={message,options};}};
runtime(pi);
for(const fn of handlers.get('session_start')){fn();}
writeFileSync(process.env.STEER_PATH,JSON.stringify({message:'repair the measured failure'}));
await new Promise(resolve=>setTimeout(resolve,1200));
for(const fn of handlers.get('session_shutdown')){fn();}
if(delivered?.message!=='repair the measured failure'||delivered.options.deliverAs!=='steer'||existsSync(process.env.STEER_PATH)){process.exit(2);}
''' % json.dumps(runtime.as_uri())
            import os
            environment = dict(os.environ, PI_CODING_AGENT_DIR=str(root / 'agent'),
                STEER_PATH=str(root / 'artifacts/supervision-steer-request.json'))
            subprocess.run([node, '--input-type=module', '-e', script], env=environment, check=True, timeout=10)


class MetricsTests(unittest.TestCase):
    def test_labels_are_summed_and_missing_or_reset_counters_rejected(self):
        self.assertEqual({'x': 5}, counters('x{worker="a"} 2\nx{worker="b"} 3\n# ignored'))
        with self.assertRaises(ValueError):
            counter_delta({}, {}, 'ollama')
        before = {'llamacpp:prompt_tokens_total': 2, 'llamacpp:prompt_seconds_total': 1,
                  'llamacpp:tokens_predicted_total': 2, 'llamacpp:tokens_predicted_seconds_total': 1}
        after = dict(before, **{'llamacpp:tokens_predicted_total': 1})
        with self.assertRaisesRegex(ValueError, 'reset'):
            counter_delta(before, after, 'ollama')

    def test_proxy_forwards_stream_unchanged_and_measures_every_request(self):
        state = {'requests': 0}
        stream = b'data: {"choices":[{"finish_reason":"stop"}],"usage":{"prompt_tokens":4,"completion_tokens":10}}\n\ndata: [DONE]\n\n'
        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *args):
                pass
            def do_GET(self):
                n = state['requests']
                text = f'llamacpp:prompt_tokens_total {4*n}\nllamacpp:prompt_seconds_total {0.25*n}\nllamacpp:tokens_predicted_total {10*n}\nllamacpp:tokens_predicted_seconds_total {0.5*n}\n'
                self.send_response(200)
                self.end_headers()
                self.wfile.write(text.encode())
            def do_POST(self):
                state['body'] = self.rfile.read(int(self.headers['Content-Length']))
                state['requests'] += 1
                self.send_response(200)
                self.send_header('Content-Type', 'text/event-stream')
                self.end_headers()
                self.wfile.write(stream)
        server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        server.daemon_threads = True
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            with tempfile.TemporaryDirectory() as directory:
                url = f'http://127.0.0.1:{server.server_port}'
                proxy = InferenceProxy(url + '/v1', url + '/metrics', 'ollama', Path(directory),
                    budget={'maximum_requests': 2, 'maximum_generated_tokens': 200000})
                try:
                    body = b'{"messages":[],"stream":true}'
                    for i in range(2):
                        request = urllib.request.Request(proxy.base_url + '/chat/completions', data=body)
                        with urllib.request.urlopen(request, timeout=5) as response:
                            self.assertEqual(stream, response.read())
                    with self.assertRaises(urllib.error.HTTPError) as blocked:
                        urllib.request.urlopen(urllib.request.Request(proxy.base_url + '/chat/completions', data=body), timeout=5)
                    self.assertEqual(429, blocked.exception.code)
                    blocked.exception.close()
                    self.assertEqual(2, state['requests'])
                    self.assertEqual(body, state['body'])
                    self.assertEqual((2, 20), proxy.totals())
                    records = proxy.summary()['records']
                    self.assertEqual([16, 16], [r['prefill_tokens_per_second'] for r in records])
                    self.assertEqual([20, 20], [r['generation_tokens_per_second'] for r in records])
                    self.assertTrue(all(r['complete'] for r in records))
                finally:
                    proxy.close()
        finally:
            server.shutdown()
            server.server_close()
            thread.join(timeout=5)


if __name__ == '__main__':
    unittest.main()
