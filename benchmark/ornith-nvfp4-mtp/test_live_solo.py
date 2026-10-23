"""GX10 opt-in: exact solo lifecycle, real Pi/tools/evaluators, scripted inference."""
from contextlib import ExitStack
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import threading
from types import SimpleNamespace
import unittest
from unittest.mock import patch

import coding
from inference_metrics import InferenceProxy
sys.path.insert(0, str(coding.HERE.parent / 'gx10-delegation'))
import tooling

IMPLEMENTATION = '''(ns benchmark.stable-distinct)
(defn stable-distinct-by [key-fn coll]
  (:items
   (reduce (fn [{:keys [seen items] :as state} item]
             (let [k (key-fn item)]
               (if (contains? seen k)
                 state
                 {:seen (conj seen k) :items (conj items item)})))
           {:seen #{} :items []} coll)))
'''
OWN_TESTS = '''(ns benchmark.stable-distinct-test
 (:require [clojure.test :refer [deftest is]]
           [benchmark.stable-distinct :refer [stable-distinct-by]]))
(deftest finite-examples
 (is (= [1 2] (stable-distinct-by identity [1 1 2])))
 (is (= [nil false] (stable-distinct-by identity [nil false nil false])))
 (is (= [] (stable-distinct-by identity nil)))
 (is (= [{:k 1 :v :first}] (stable-distinct-by :k [{:k 1 :v :first} {:k 1 :v :later}]))))
'''


@unittest.skipUnless(os.environ.get('GX10_LIVE_SOLO') == '1', 'GX10 real Pi/tool integration opt-in')
class LiveSoloTests(unittest.TestCase):
    def exercise(self, corruption=None):
        base = Path(tempfile.mkdtemp(prefix='gx10-exact-solo-', dir=str(Path.home() / 'experiments')))
        source = base / 'source'
        source.mkdir()
        (source / 'deps.edn').write_text('{:paths ["." "test"] :aliases {:test {:main-opts ["-m" "run-tests"]}}}')
        (source / 'run_tests.clj').write_text('''(ns run-tests (:require [clojure.test :as t]))
(defn -main [& names]
 (doseq [n names] (require (symbol n)))
 (let [r (apply t/run-tests (map symbol names))]
  (System/exit (if (zero? (+ (:fail r) (:error r))) 0 1))))
''')
        subprocess.run(['git', 'init', '-q', str(source)], check=True)
        subprocess.run(['git', '-C', str(source), 'add', '.'], check=True)
        subprocess.run(['git', '-C', str(source), '-c', 'user.name=Harness fixture',
                        '-c', 'user.email=fixture@localhost', 'commit', '-qm', 'Finite fixture'], check=True)
        commit = subprocess.check_output(['git', '-C', str(source), 'rev-parse', 'HEAD'], text=True).strip()
        root = base / 'attempt'
        file = str(root / 'checkout/benchmark/stable_distinct.clj')
        test = str(root / 'checkout/test/benchmark/stable_distinct_test.clj')
        calls = [('workspace_info', {}), ('read', {'path': file}), ('read', {'path': test}),
                 ('write', {'path': file, 'content': IMPLEMENTATION}),
                 ('write', {'path': test, 'content': OWN_TESTS}),
                 ('clj_kondo', {'path': file}),
                 ('lens_diagnostics', {'source': 'lsp', 'path': file, 'waitMs': 30000}),
                 ('lsp_navigation', {'operation': 'documentSymbol', 'path': file}),
                 ('web_search', {'query': 'Clojure official sequences reference', 'provider': 'duckduckgo', 'numResults': 2}),
                 ('fetch_content', {'url': 'https://clojure.org/reference/sequences', 'mode': 'raw'}),
                 ('bash', {'command': 'timeout --signal=TERM --kill-after=5s 110s clojure -M:test benchmark.stable-distinct-test', 'timeout': 120})]
        requests = []
        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *_args):
                pass
            def do_POST(self):
                body = json.loads(self.rfile.read(int(self.headers['Content-Length'])))
                number = len(requests)
                requests.append(body)
                (base / 'requests.json').write_text(json.dumps(requests, indent=2))
                self.send_response(200)
                self.send_header('Content-Type', 'text/event-stream')
                self.end_headers()
                if number < len(calls):
                    name, args = calls[number]
                    delta = {'role': 'assistant', 'reasoning_content': 'Verify ' + name,
                             'tool_calls': [{'index': 0, 'id': 'fixture-' + str(number), 'type': 'function',
                                             'function': {'name': name, 'arguments': json.dumps(args)}}]}
                    finish = 'tool_calls'
                else:
                    delta = {'role': 'assistant', 'content': 'Finite implementation and measured tests completed.'}
                    finish = 'stop'
                for part, reason in [(delta, None), ({}, finish)]:
                    chunk = {'id': 'fixture-' + str(number), 'object': 'chat.completion.chunk',
                             'created': 1, 'model': 'fixture',
                             'choices': [{'index': 0, 'delta': part, 'finish_reason': reason}]}
                    self.wfile.write(('data: ' + json.dumps(chunk) + '\n\n').encode())
                self.wfile.write(b'data: [DONE]\n\n')
        server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        args = SimpleNamespace(output=root, model='fixture', task='distinct', source=source,
                               context=131072, base_url='http://127.0.0.1:' + str(server.server_port) + '/v1',
                               server_unit='unused-fixture.service', web_extension=tooling.WEB / 'dist/index.js',
                               web_guard=Path.home() / 'experiments/pi-gx10-capability-20261021/benchmark-web-policy.ts')
        original_configure = tooling.configure
        original_command = coding.agent_command
        def configure(*items):
            result = original_configure(*items)
            if corruption == 'graph-policy':
                policy_path = items[0] / 'agent/benchmark-policy.json'
                policy = json.loads(policy_path.read_text())
                policy.pop('gortex_tools')
                policy_path.write_text(json.dumps(policy))
            return result
        def command(*items):
            result = original_command(*items)
            if corruption == 'cli-tool':
                index = result.index('--tools') + 1
                result[index] = ','.join(name for name in result[index].split(',') if name != 'clj_kondo')
            return result
        delta = {'prefill_tokens': 1, 'prefill_seconds': 1, 'generated_tokens': 1, 'generation_seconds': 1,
                 'prefill_tokens_per_second': 1, 'generation_tokens_per_second': 1}
        error = None
        try:
            with ExitStack() as stack:
                stack.enter_context(patch.dict(os.environ, {'GX10_TOOL_PROFILE': 'enhanced'}))
                stack.enter_context(patch.object(coding, 'PIN', commit))
                stack.enter_context(patch.object(coding, 'metrics_endpoint', return_value=('fixture', 'ollama')))
                stack.enter_context(patch.object(InferenceProxy, '_snapshot', return_value={}))
                stack.enter_context(patch('inference_metrics.counter_delta', return_value=delta))
                stack.enter_context(patch.object(tooling, 'configure', side_effect=configure))
                stack.enter_context(patch.object(coding, 'agent_command', side_effect=command))
                try:
                    coding.execute_attempt(args)
                except Exception as failure:
                    error = failure
            result = json.loads((root / 'result.json').read_text())
            if corruption is None:
                self.assertIsNone(error)
                self.assertEqual(result['outcome'], 'passed')
                self.assertEqual(result['acceptance_counts']['assertions'], 14)
                self.assertEqual(result['own_test_counts']['assertions'], 4)
                self.assertTrue(json.loads((root / 'artifacts/tooling-verification.json').read_text())['passed'])
                self.assertGreater(len(requests), 0)
                self.assertTrue(all(r.get('temperature') == 1.0 for r in requests))
            else:
                self.assertIsNotNone(error)
                self.assertEqual(result['outcome'], 'setup_declined')
                self.assertIsNone(result['correct'])
                self.assertTrue(result['startup_failure'])
                self.assertEqual(requests, [])
                self.assertTrue((root / 'artifacts/startup-failure.json').is_file())
            print('EXACT_SOLO_EVIDENCE', str(base), corruption, result['outcome'], len(requests), flush=True)
        finally:
            server.shutdown()
            server.server_close()
            thread.join(timeout=3)
            wrapper = root / 'gortex/gortex'
            if wrapper.exists():
                subprocess.run([str(wrapper), 'daemon', 'stop'], timeout=30)

    def test_real_solo_passes_tools_own_tests_and_independent_evaluator(self):
        self.exercise()

    def test_missing_graph_policy_admits_zero_generation_requests(self):
        self.exercise('graph-policy')

    def test_filtered_cli_tool_admits_zero_generation_requests(self):
        self.exercise('cli-tool')


if __name__ == '__main__':
    unittest.main()
