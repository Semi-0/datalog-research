"""Exercise the installed Pi CLI against controlled streams, without an inference model."""
from contextlib import contextmanager
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import threading
import unittest

from outcomes import apply_recovery_evidence, classify
from pilot import select_context, recover_contexts
from lifecycle import Failure
import suite
from pi_extensions import prepare_vigilant

HERE = Path(__file__).resolve().parent
PI = Path.home() / '.local/bin/pi'


class RecoveryClassificationTests(unittest.TestCase):
    def test_finished_context_worker_is_retained_after_parent_interruption_but_source_drift_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory)
            source = base / 'previous'
            cell = source / 'context/32768-44'
            cell.mkdir(parents=True)
            (source / 'manifest.json').write_text(json.dumps(suite.MANIFEST))
            (cell / 'cell.json').write_text(json.dumps({'model': {'id': 'flash-iq4'}, 'context': 32768, 'seed': 44}))
            hashes = suite.harness_hashes()
            (cell / 'harness-hashes.json').write_text(json.dumps(hashes))
            (cell / 'result.json').write_text(json.dumps({'outcome': 'interrupted'}))
            (cell / 'attempt').mkdir()
            (cell / 'attempt/result.json').write_text(json.dumps({'outcome': 'passed', 'protocol_pass': True}))
            recovered = recover_contexts(source, base / 'new')
            self.assertEqual(1, len(recovered))
            self.assertEqual('passed', recovered[0]['result']['outcome'])
            self.assertTrue(recovered[0]['recovered_result_path'].endswith('attempt/result.json'))
            hashes['benchmark/gx10-context/context_probe.py'] = 'different'
            (cell / 'harness-hashes.json').write_text(json.dumps(hashes))
            with self.assertRaises(Failure):
                recover_contexts(source, base / 'rejected')

    def test_resolved_length_retains_evidence_and_cannot_make_speed_valid(self):
        with tempfile.TemporaryDirectory() as directory:
            audit = Path(directory) / 'audit.jsonl'
            audit.write_text(json.dumps({'kind': 'length_recovered', 'timestamps': [123]}) + '\n')
            result = {'task': 'distinct', 'stream_failures': [{'stop_reason': 'length', 'timestamp': 123}],
                'assistant_errors': [], 'own_tests_exit': 0, 'acceptance_exit': 0, 'confined': True,
                'thinking_observed': True, 'search_succeeded': True, 'fetch_succeeded': True,
                'agent_final_response': True, 'agent_end_observed': True, 'pi_exit': 0,
                'swap_in_pages_delta': 0, 'swap_out_pages_delta': 0}
            apply_recovery_evidence(result, audit)
            text = 'Ran 3 tests containing 14 assertions.\n0 failures, 0 errors.'
            classify(result, text, text)
            self.assertEqual('passed', result['outcome'])
            self.assertEqual(1, len(result['stream_failures']))
            self.assertFalse(result['timing_valid'])
            result['stream_failures'].append({'stop_reason': 'error', 'error': 'Stream ended without finish_reason'})
            apply_recovery_evidence(result, audit)
            classify(result, text, text)
            self.assertEqual('runtime_error', result['execution_outcome'])

    def test_deadline_is_censored_and_not_proven_nontermination(self):
        result = classify({'task': 'events', 'timed_out': True}, '', '')
        self.assertEqual('timed_out', result['outcome'])
        self.assertTrue(result['elapsed_censored'])
        self.assertFalse(result['timing_valid'])

    def test_select_context_requires_all_seeds_and_rejects_swap_or_failed_retrieval(self):
        rows = []
        for size in [32768, 65536, 131072]:
            for seed in suite.MANIFEST['seeds']:
                rows.append({'context': size, 'seed': seed, 'result': {'protocol_pass': size != 131072,
                    'timing_valid': size != 65536}})
        self.assertEqual(32768, select_context(rows))
        with self.assertRaises(Failure):
            select_context(rows[3:])
        self.assertEqual(32768, select_context(rows[:3] + [
            {'context': 262144, 'seed': 42, 'result': {'protocol_pass': True, 'timing_valid': True}}]))


@unittest.skipUnless(PI.exists(), 'Installed Pi required for wire-level integration')
class PiWireIntegrationTests(unittest.TestCase):
    def invoke(self, behavior, maximum=3, tool_timeout=1, vigilant=False):
        requests = []
        class Handler(BaseHTTPRequestHandler):
            protocol_version = 'HTTP/1.1'
            def log_message(self, *_):
                pass
            def do_POST(self):
                body = json.loads(self.rfile.read(int(self.headers['Content-Length'])))
                requests.append(body)
                events = behavior(len(requests), body)
                self.send_response(200)
                self.send_header('Content-Type', 'text/event-stream')
                self.send_header('Connection', 'close')
                self.end_headers()
                for item in events:
                    self.wfile.write(('data: ' + json.dumps(item) + '\n\n').encode())
                    self.wfile.flush()
                self.wfile.write(b'data: [DONE]\n\n')
                self.wfile.flush()
                self.close_connection = True
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            agent = root / 'agent'
            agent.mkdir()
            (root / 'artifacts').mkdir()
            server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
            server.daemon_threads = True
            worker = threading.Thread(target=server.serve_forever)
            worker.start()
            model = {'id': 'mock', 'name': 'mock', 'reasoning': True, 'input': ['text'],
                     'contextWindow': 65536, 'maxTokens': 12288,
                     'cost': dict.fromkeys(['input', 'output', 'cacheRead', 'cacheWrite'], 0),
                     'compat': {'maxTokensField': 'max_tokens', 'supportsFinishReason': True}}
            (agent / 'models.json').write_text(json.dumps({'providers': {'mock': {
                'baseUrl': 'http://127.0.0.1:' + str(server.server_port) + '/v1',
                'api': 'openai-completions', 'apiKey': 'dummy', 'models': [model]}}}))
            (agent / 'settings.json').write_text(json.dumps({'cacheWarming': 'off',
                'retry': {'enabled': True, 'maxRetries': 2, 'baseDelayMs': 1, 'maxAgentDelayMs': 1,
                          'provider': {'maxRetries': 0}},
                'compaction': {'enabled': False}}))
            (agent / 'benchmark-policy.json').write_text(json.dumps({'tool_timeout_seconds': tool_timeout,
                'length_continuations': maximum, 'allowed_tools': ['bash']}))
            vigilant_entry = None
            if vigilant:
                vigilant_entry = prepare_vigilant(root, suite.MANIFEST, overrides={
                    'lengthContinuationMaxConsecutive': maximum, 'hostRetryBudget': 2,
                    'compactionFallback': False, 'prematureStopDetection': False, 'finalVerification': False})
                (agent / 'benchmark-policy.json').write_text(json.dumps({'tool_timeout_seconds': tool_timeout,
                    'length_continuations': maximum, 'continuation_owner': 'pi-vigilant', 'allowed_tools': ['bash']}))
            env = dict(os.environ, PI_CODING_AGENT_DIR=str(agent), PI_TELEMETRY='0')
            command = [str(PI), '--mode', 'json', '--offline', '--no-extensions', '--extension', str(HERE/'pi-runtime.mjs'),
                       '--no-mcp', '--no-skills', '--no-prompt-templates', '--no-context-files', '--no-approve',
                       '--provider', 'mock', '--model', 'mock', '--thinking', 'high', '--tools', 'bash',
                       '--session-dir', str(root/'sessions'), 'Perform the mock task.']
            if vigilant_entry is not None:
                command[1:1] = ['--extension', str(vigilant_entry)]
            try:
                result = subprocess.run(command, cwd=root, env=env, text=True, capture_output=True, timeout=40)
                self.assertEqual(0, result.returncode, result.stderr)
                self.assertNotIn('Failed to load extension', result.stderr)
                self.assertNotIn('Extension error', result.stderr)
                events = [json.loads(line) for line in result.stdout.splitlines() if line.strip()]
                audit = [json.loads(line) for line in (root/'artifacts/pi-runtime.jsonl').read_text().splitlines()]
                return requests, events, audit
            finally:
                server.shutdown()
                server.server_close()
                worker.join()

    @staticmethod
    def completion(reason='stop', content='Complete', output=10):
        return [{'id': 'test', 'object': 'chat.completion.chunk', 'model': 'mock',
                 'choices': [{'index': 0, 'delta': {'content': content}, 'finish_reason': reason}],
                 'usage': {'prompt_tokens': 50, 'completion_tokens': output, 'total_tokens': 50 + output}}]

    def test_output_limit_continues_same_session_with_recorded_recovery(self):
        requests, events, audit = self.invoke(lambda n, _: self.completion('length', 'Partial', 12288)
                                             if n == 1 else self.completion())
        self.assertEqual(2, len(requests))
        self.assertEqual(12288, requests[0]['max_tokens'])
        self.assertIn('saved files', json.dumps(requests[1]['messages']))
        self.assertTrue(any(e['kind'] == 'length_recovered' for e in audit))
        self.assertTrue(any(e['type'] == 'agent_settled' for e in events))

    def test_repeated_output_limits_have_a_finite_continuation_budget(self):
        requests, _, audit = self.invoke(lambda *_: self.completion('length', 'Partial', 12288), maximum=2)
        self.assertEqual(3, len(requests))
        self.assertTrue(any(e['kind'] == 'length_recovery_exhausted' for e in audit))
        self.assertFalse(any(e['kind'] == 'length_recovered' for e in audit))

    def test_transient_missing_finish_reason_retries_without_accepting_eof(self):
        requests, events, _ = self.invoke(lambda n, _: self.completion(None) if n == 1 else self.completion())
        self.assertEqual(2, len(requests))
        self.assertTrue(any(e['type'] == 'auto_retry_end' and e.get('success') for e in events))
        self.assertTrue(any(e['type'] == 'message_end' and e.get('message', {}).get('stopReason') == 'error' for e in events))

    def test_persistent_missing_finish_reason_remains_an_error(self):
        requests, events, _ = self.invoke(lambda *_: self.completion(None))
        self.assertEqual(3, len(requests))
        messages = [e['message'] for e in events if e['type'] == 'message_end' and e.get('message', {}).get('role') == 'assistant']
        self.assertEqual('error', messages[-1]['stopReason'])
        self.assertIn('without finish_reason', messages[-1]['errorMessage'])

    def test_hanging_bash_is_bounded_even_when_model_asks_for_long_timeout(self):
        def behavior(n, _):
            if n == 1:
                return [{'id': 'test', 'model': 'mock', 'choices': [{'index': 0, 'finish_reason': 'tool_calls',
                    'delta': {'tool_calls': [{'index': 0, 'id': 'hang', 'type': 'function', 'function': {
                        'name': 'bash', 'arguments': json.dumps({'command': 'sleep 60', 'timeout': 999})}}]}}]}]
            return self.completion()
        requests, events, audit = self.invoke(behavior)
        self.assertEqual(2, len(requests))
        self.assertTrue(any(e['kind'] == 'tool_timeout' and e['effective'] == 1 for e in audit))
        results = [e for e in events if e['type'] == 'tool_execution_end']
        self.assertTrue(results and results[0]['isError'])


@unittest.skipUnless(PI.exists(), 'Installed Pi required for wire-level integration')
class VigilantWireIntegrationTests(PiWireIntegrationTests):
    def invoke(self, behavior, maximum=3, tool_timeout=1):
        return super().invoke(behavior, maximum, tool_timeout, vigilant=True)

    def test_output_limit_continues_same_session_with_recorded_recovery(self):
        def behavior(n, _):
            if n == 1:
                return self.completion('length', 'Partial', 12288)
            return self.completion()
        requests, _, audit = self.invoke(behavior)
        self.assertEqual(2, len(requests))
        self.assertIn('previous response was cut off', json.dumps(requests[1]['messages']))
        self.assertTrue(any(e['kind'] == 'length_recovered' for e in audit))
        self.assertEqual({'bash'}, {tool['function']['name'] for tool in requests[0]['tools']})
        self.assertFalse(any(e['kind'] == 'length_continuation' for e in audit))

    def test_repeated_output_limits_have_a_finite_continuation_budget(self):
        requests, _, audit = self.invoke(lambda *_: self.completion('length', 'Partial', 12288), maximum=2)
        self.assertEqual(3, len(requests))
        self.assertFalse(any(e['kind'] == 'length_recovered' for e in audit))


if __name__ == '__main__':
    unittest.main()
