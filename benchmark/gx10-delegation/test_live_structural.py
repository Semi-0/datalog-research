"""Opt-in Pi wire and real cljform transactional-edit checks on GX10."""
import hashlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import threading
import unittest

PREFIX = Path('/home/lain/experiments/gx10-structural-tools-1')


@unittest.skipUnless(os.environ.get('GX10_LIVE_STRUCTURAL') == '1', 'Installed GX10 structural tools required')
class LiveStructuralTests(unittest.TestCase):
    def test_real_pi_calls_pinned_extension_and_edits_a_form(self):
        root = Path(tempfile.mkdtemp(prefix='gx10-structural-wire-', dir='/home/lain/experiments'))
        agent = root / 'agent'
        agent.mkdir()
        fixture = root / 'fixture.clj'
        fixture.write_text('(ns fixture)\n\n(defn answer []\n  1)\n\n(def untouched :sentinel)\n')
        profile = json.loads((PREFIX / 'manifest.json').read_text())
        requests = []
        errors = []
        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *_args):
                pass
            def do_POST(self):
                try:
                    body = json.loads(self.rfile.read(int(self.headers['Content-Length'])))
                    requests.append(body)
                    number = len(requests)
                    if number == 1:
                        names = {tool['function']['name'] for tool in body['tools']}
                        assert set(profile['tools']).issubset(names), names
                        name, args = 'clj_get', {'path': str(fixture), 'name': 'answer'}
                    elif number == 2:
                        content = next(row['content'] for row in reversed(body['messages']) if row['role'] == 'tool')
                        if not isinstance(content, str):
                            content = json.dumps(content, ensure_ascii=False)
                        handle = re.search(r'handle ⟦([^⟧]+)⟧', content).group(1)
                        name, args = 'clj_edit', {'path': str(fixture), 'handle': handle,
                                                'content': '(defn answer []\n  42)'}
                    elif number == 3:
                        name, args = 'clj_forms', {'path': str(fixture)}
                    else:
                        name, args = None, None
                    delta = {'role': 'assistant', 'content': 'Structural editing smoke finished.'}
                    finish = 'stop'
                    if name is not None:
                        delta = {'role': 'assistant', 'tool_calls': [{'index': 0, 'id': 'structural-' + str(number),
                                 'type': 'function', 'function': {'name': name, 'arguments': json.dumps(args)}}]}
                        finish = 'tool_calls'
                    self.send_response(200)
                    self.send_header('Content-Type', 'text/event-stream')
                    self.end_headers()
                    for change, reason in [(delta, None), ({}, finish)]:
                        chunk = {'id': 'fixture', 'object': 'chat.completion.chunk', 'created': 1,
                                 'model': 'fixture', 'choices': [{'index': 0, 'delta': change, 'finish_reason': reason}]}
                        self.wfile.write(('data: ' + json.dumps(chunk) + '\n\n').encode())
                    self.wfile.write(b'data: [DONE]\n\n')
                    self.wfile.flush()
                except Exception as error:
                    errors.append(repr(error))
                    raise
        server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        model = {'id': 'fixture', 'name': 'fixture', 'reasoning': False, 'input': ['text'],
                 'contextWindow': 131072, 'maxTokens': 1024,
                 'cost': {'input': 0, 'output': 0, 'cacheRead': 0, 'cacheWrite': 0}}
        (agent / 'models.json').write_text(json.dumps({'providers': {'structural-fixture': {
            'baseUrl': 'http://127.0.0.1:' + str(server.server_port) + '/v1',
            'api': 'openai-completions', 'apiKey': 'fixture', 'models': [model]}}}))
        (agent / 'settings.json').write_text(json.dumps({'extensions': [], 'packages': [], 'retry': {'enabled': False}}))
        env = dict(os.environ, PI_CODING_AGENT_DIR=str(agent), CLJFORM_BIN=profile['binary'])
        env['PATH'] = '/home/lain/.local/opt/node-v24.22.1-linux-arm64/bin:/home/lain/.local/bin:/usr/bin:/bin'
        command = ['pi', '--mode', 'json', '--provider', 'structural-fixture', '--model', 'fixture',
                   '--no-extensions', '--extension', profile['extension'], '--no-mcp', '--no-skills',
                   '--no-prompt-templates', '--no-context-files', '--approve', '--offline',
                   '--tools', ','.join(profile['tools']), 'Change answer to 42 with structural editing.']
        try:
            process = subprocess.run(command, cwd=root, env=env, capture_output=True, text=True, timeout=60)
        finally:
            server.shutdown()
            server.server_close()
            thread.join(timeout=5)
        (root / 'events.jsonl').write_text(process.stdout)
        (root / 'stderr.txt').write_text(process.stderr)
        (root / 'requests.json').write_text(json.dumps(requests, indent=2))
        self.assertFalse(errors, errors)
        self.assertEqual(0, process.returncode, process.stderr)
        self.assertEqual(4, len(requests), process.stdout[-2000:])
        self.assertIn('(defn answer []\n  42)', fixture.read_text())
        self.assertIn('(def untouched :sentinel)', fixture.read_text())
        events = [json.loads(line) for line in process.stdout.splitlines() if line.strip()]
        completed = [row for row in events if row.get('type') == 'tool_execution_end']
        self.assertEqual(3, len(completed))
        self.assertTrue(all(not row.get('isError') for row in completed))
        self.assertTrue(any(row.get('type') == 'agent_end' for row in events))
        print('STRUCTURAL_WIRE_EVIDENCE=' + str(root), flush=True)

    def test_malformed_content_is_refused_without_writing_and_repair_is_explicit(self):
        binary = str(PREFIX / 'bin/cljform')
        with tempfile.TemporaryDirectory() as name:
            path = Path(name) / 'fixture.clj'
            path.write_text('(ns fixture)\n\n(defn answer []\n  1)\n')
            before = path.read_bytes()
            tree = subprocess.run([binary, 'tree', str(path), '--json'], capture_output=True, text=True, check=True)
            nodes = json.loads(tree.stdout)['result']['nodes']
            handle = next(row['handle'] for row in nodes if row.get('name') == 'answer')
            command = [binary, 'edit', str(path), '--handle', handle, '--json',
                       '--content', '(defn answer []\n  (+ 1 2)']
            refused = subprocess.run(command, capture_output=True, text=True)
            self.assertNotEqual(0, refused.returncode)
            self.assertEqual(before, path.read_bytes())
            repaired = subprocess.run(command + ['--repair'], capture_output=True, text=True)
            self.assertEqual(0, repaired.returncode, repaired.stdout + repaired.stderr)
            result = json.loads(repaired.stdout)['result']
            self.assertTrue(result['repaired'])
            self.assertTrue(result['repairDiff'])
            check = subprocess.run([binary, 'check', str(path), '--json'], capture_output=True, text=True)
            self.assertEqual(0, check.returncode, check.stdout)
