"""Private graph ownership, pinning, and real Pi tool registration checks."""
import hashlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import subprocess
import tempfile
import threading
import unittest
from unittest.mock import patch

import gortex_profile as profile
from lifecycle import save


class ProfileTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.base = Path(self.temp.name).resolve()
        self.package = self.base / 'package'
        self.package.mkdir()
        (self.package / 'index.ts').write_text('fixture')
        self.home = self.base / 'host-home'
        self.home.mkdir()
        binary = self.home / 'bin/gortex'
        binary.parent.mkdir()
        binary.write_text('#!/bin/bash\nprintf "%s\\n" "$HOME" "$GORTEX_DAEMON_SOCKET" "$@"\n')
        binary.chmod(0o755)
        node = self.home / 'bin/node'
        node.write_text('fixture-node')
        lock = {'package': 'pi-gortex', 'version': 'fixture',
                'files': {'index.ts': profile.sha256(self.package / 'index.ts')},
                'runtime': {name: {'path': 'bin/' + name, 'sha256': profile.sha256(self.home / 'bin' / name)}
                            for name in ['node', 'gortex']}}
        save(self.base / 'pi-gortex-lock.json', lock)
        self.root = self.base / 'attempt'
        (self.root / 'artifacts').mkdir(parents=True)
        self.checkout = self.root / 'checkout'
        self.checkout.mkdir()
        self.env = {'PATH': '/usr/bin:/bin', 'HOME': str(self.home)}

    def prepare(self):
        with patch.object(profile, 'HERE', self.base):
            return profile.prepare_gortex(self.root, self.checkout, self.env, self.package, self.home)

    def test_private_state_candidate_index_and_parent_home_are_preserved(self):
        extension = self.prepare()
        self.assertTrue(extension.exists())
        wrapper = self.root / 'gortex/gortex'
        result = subprocess.run([str(wrapper), 'mcp'], env=self.env, capture_output=True, text=True, timeout=10)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn(str(self.root / 'gortex/home'), result.stdout)
        log = (self.root / 'gortex/startup.log').read_text()
        self.assertIn(str(self.checkout), log)
        self.assertTrue((self.root / 'gortex/index-ready').exists())
        self.assertEqual(str(self.home), self.env['HOME'])
        self.assertFalse((self.home / '.gortex').exists())
        self.assertEqual('readonly', self.env['GORTEX_TOOLS'])
        self.assertTrue(self.env['PATH'].startswith(str(self.home / 'bin')))
        config = json.loads((self.root / 'agent/extensions/gortex.json').read_text())
        self.assertFalse(config['native_mcp'])
        self.assertFalse(config['enforce'])

    def test_modified_package_extra_file_and_modified_runtime_are_rejected(self):
        index = self.package / 'index.ts'
        index.write_text('changed')
        with self.assertRaisesRegex(RuntimeError, 'file differs'):
            self.prepare()
        index.write_text('fixture')
        extra = self.package / 'untracked.ts'
        extra.write_text('unexpected')
        with self.assertRaisesRegex(RuntimeError, 'inventory differs'):
            self.prepare()
        extra.unlink()
        (self.home / 'bin/node').write_text('changed')
        with self.assertRaisesRegex(RuntimeError, 'runtime differs'):
            self.prepare()

    def test_success_requires_both_private_index_and_exposed_graph_tools(self):
        self.prepare()
        with self.assertRaisesRegex(RuntimeError, 'indexing'):
            profile.verify_gortex(self.root)
        (self.root / 'gortex/index-ready').touch()
        audit = self.root / 'artifacts/pi-runtime.jsonl'
        audit.write_text(json.dumps({'kind': 'gortex_unavailable'}) + '\n')
        with self.assertRaisesRegex(RuntimeError, 'expose'):
            profile.verify_gortex(self.root)
        audit.write_text(json.dumps({'kind': 'gortex_ready', 'tools': ['workspace', 'gortex_read']}) + '\n')
        profile.verify_gortex(self.root)
        self.assertTrue(json.loads((self.root / 'artifacts/gortex-verification.json').read_text())['passed'])


@unittest.skipUnless((Path.home() / '.local/opt/gortex-v0.64.8/gortex').exists(), 'GX10 pinned Gortex runtime required')
class RealPiGraphTests(unittest.TestCase):
    def test_real_pi_can_query_only_its_private_candidate_graph(self):
        requests = []
        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *args):
                pass
            def do_POST(self):
                requests.append(json.loads(self.rfile.read(int(self.headers['Content-Length']))))
                delta = {'role': 'assistant', 'content': 'OK'}
                finish = 'stop'
                if len(requests) == 1:
                    delta = {'role': 'assistant', 'tool_calls': [{'index': 0, 'id': 'graph-1', 'type': 'function',
                             'function': {'name': 'workspace_info', 'arguments': '{}'}}]}
                    finish = 'tool_calls'
                self.send_response(200)
                self.send_header('Content-Type', 'text/event-stream')
                self.end_headers()
                value = {'id': 'mock', 'object': 'chat.completion.chunk', 'created': 1, 'model': 'mock',
                         'choices': [{'index': 0, 'delta': delta, 'finish_reason': finish}]}
                self.wfile.write(('data: ' + json.dumps(value) + '\n\ndata: [DONE]\n\n').encode())
                self.wfile.flush()
        server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        threading.Thread(target=server.serve_forever, daemon=True).start()
        self.addCleanup(server.server_close)
        self.addCleanup(server.shutdown)
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary).resolve() / 'attempt'
            (root / 'agent').mkdir(parents=True)
            (root / 'artifacts').mkdir()
            checkout = root / 'checkout'
            checkout.mkdir()
            subprocess.run(['git', 'init', '-q', str(checkout)], check=True)
            (checkout / 'hello.clj').write_text('(ns hello)\n(defn hello [] :world)\n')
            env = {'HOME': str(Path.home()), 'PATH': str(Path.home() / '.local/bin') + ':/usr/bin:/bin',
                   'PI_CODING_AGENT_DIR': str(root / 'agent'), 'PI_TELEMETRY': '0'}
            extension = profile.prepare_gortex(root, checkout, env)
            save(root / 'agent/benchmark-policy.json', {'allowed_tools': ['read', 'bash'],
                 'tool_timeout_seconds': 2, 'continuation_owner': 'pi-vigilant', 'gortex_tools': profile.GRAPH_TOOLS})
            save(root / 'agent/settings.json', {'packages': [], 'extensions': [], 'retry': {'enabled': False}})
            save(root / 'agent/models.json', {'providers': {'mock': {'baseUrl': f'http://127.0.0.1:{server.server_port}/v1',
                 'api': 'openai-completions', 'apiKey': 'fixture', 'models': [{'id': 'mock', 'name': 'mock',
                 'reasoning': False, 'input': ['text'], 'contextWindow': 32768, 'maxTokens': 128,
                 'cost': {'input': 0, 'output': 0, 'cacheRead': 0, 'cacheWrite': 0},
                 'compat': {'supportsFinishReason': True}}]}}})
            command = [str(Path.home() / '.local/bin/pi'), '--mode', 'json', '--provider', 'mock', '--model', 'mock',
                       '--no-extensions', '--extension', str(extension), '--extension', str(profile.HERE / 'pi-runtime.mjs'),
                       '--no-mcp', '--no-skills', '--no-prompt-templates', '--no-context-files', '--no-approve',
                       '--offline', '--tools', ','.join(['read', 'bash', *profile.GRAPH_TOOLS]), '--session-dir', str(root / 'sessions'), 'Query the workspace.']
            try:
                result = subprocess.run(command, cwd=checkout, env=env, capture_output=True, text=True, timeout=120)
                self.assertEqual(0, result.returncode, result.stderr + result.stdout[-3000:])
                try:
                    profile.verify_gortex(root)
                except RuntimeError as error:
                    audit = (root / 'artifacts/pi-runtime.jsonl').read_text()
                    startup = (root / 'gortex/startup.log').read_text()
                    registration = root / 'artifacts/gortex-registration.jsonl'
                    if registration.exists():
                        startup += '\nREGISTRATION\n' + registration.read_text()
                    self.fail(str(error) + '\nAUDIT\n' + audit + '\nSTARTUP\n' + startup +
                              '\nPI\n' + result.stderr + result.stdout[-5000:])
                self.assertGreaterEqual(len(requests), 2)
                names = {tool['function']['name'] for tool in requests[0]['tools']}
                self.assertIn('workspace_info', names)
                self.assertIn('read_file', names)
                self.assertNotIn('edit', names)
                messages = json.dumps(requests[-1]['messages'])
                self.assertIn(str(checkout), messages)
                self.assertNotIn('/home/lain/projects/KIROSHI', messages)
            finally:
                stopped = subprocess.run([str(root / 'gortex/gortex'), 'daemon', 'stop'], env=env,
                                         capture_output=True, text=True, timeout=30)
                self.assertEqual(0, stopped.returncode, stopped.stderr)
                socket = Path(json.loads((root / 'artifacts/gortex-profile.json').read_text())['socket'])
                self.assertFalse(socket.exists(), 'Private Gortex socket remained after stop')
