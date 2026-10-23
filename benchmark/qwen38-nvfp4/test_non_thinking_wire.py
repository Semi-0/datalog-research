"""Opt-in actual Pi wire check, using a scripted local provider and no model load."""
import http.server
import json
import os
from pathlib import Path
import subprocess
import tempfile
import threading
import types
import unittest

import follow_on
from lifecycle import save
from test_model_profiles import coding


@unittest.skipUnless(os.environ.get('GX10_NON_THINKING_WIRE') == '1', 'Opt-in actual Pi non-thinking payload check')
class NonThinkingWireTests(unittest.TestCase):
    def test_actual_pi_omits_reasoning_effort_and_finishes(self):
        requests = []
        class Provider(http.server.BaseHTTPRequestHandler):
            def log_message(self, *args):
                pass

            def do_POST(self):
                body = json.loads(self.rfile.read(int(self.headers['Content-Length'])))
                requests.append(body)
                self.send_response(200)
                self.send_header('Content-Type', 'text/event-stream')
                self.end_headers()
                chunks = [
                    {'id': 'wire', 'object': 'chat.completion.chunk', 'created': 1, 'model': 'wire-coder',
                     'choices': [{'index': 0, 'delta': {'role': 'assistant', 'content': 'DONE'}, 'finish_reason': None}]},
                    {'id': 'wire', 'object': 'chat.completion.chunk', 'created': 1, 'model': 'wire-coder',
                     'choices': [{'index': 0, 'delta': {}, 'finish_reason': 'stop'}],
                     'usage': {'prompt_tokens': 3, 'completion_tokens': 1, 'total_tokens': 4}}]
                for chunk in chunks:
                    self.wfile.write(('data: ' + json.dumps(chunk) + '\n\n').encode())
                self.wfile.write(b'data: [DONE]\n\n')
                self.wfile.flush()
        server = http.server.ThreadingHTTPServer(('127.0.0.1', 0), Provider)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            with tempfile.TemporaryDirectory(prefix='gx10-non-thinking-wire-') as directory:
                cell = Path(directory)
                save(cell / 'manifest.json', {'models': [{'source': 'wire-coder', 'supports_thinking': False}],
                     'sampling': {'thinking': True, 'temperature': 1.0, 'top_p': 0.95}})
                root = cell / 'attempt'
                provider = coding.prepare(root, 'wire-coder', 'http://127.0.0.1:' + str(server.server_port) + '/v1', 131072)
                command = coding.agent_command(root, types.SimpleNamespace(model='wire-coder'), provider, 'Reply DONE.', [])
                # This fixture checks provider capabilities, with only native tools loaded.
                command[command.index('--tools') + 1] = 'read,bash'
                result = subprocess.run(command, env=coding.environment(root), cwd=cell,
                                        text=True, capture_output=True, timeout=40)
                self.assertEqual(result.returncode, 0, result.stderr)
                events = [json.loads(line) for line in result.stdout.splitlines() if line.strip()]
                endings = [row['message'] for row in events if row.get('type') == 'message_end'
                           and row.get('message', {}).get('role') == 'assistant']
                self.assertTrue(endings)
                self.assertEqual(endings[-1]['stopReason'], 'stop')
                self.assertEqual(len(requests), 1)
                self.assertNotIn('reasoning_effort', requests[0])
                self.assertEqual(requests[0]['temperature'], 1.0)
        finally:
            server.shutdown()
            server.server_close()
            thread.join(timeout=2)


if __name__ == '__main__':
    unittest.main()
