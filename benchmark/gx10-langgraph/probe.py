#!/usr/bin/env python3
"""Real Pi/plugin control fixture using only a synthetic local HTTP provider."""
import argparse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import subprocess
import threading
import time
from outcomes import normalize

HERE = Path(__file__).resolve().parent
PREFIX = Path.home() / 'experiments/gx10-langgraph-install-1'
NODE = Path.home() / '.local/opt/node-v24.22.1-linux-arm64/bin/node'
PI = PREFIX / 'packages/node_modules/@earendil-works/pi-coding-agent/dist/bundle/cli.js'


def save(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2) + '\n')


def probe(root):
    root.mkdir(parents=True, exist_ok=False)
    agent = root / 'agent'
    agent.mkdir()
    temporary = root / 'tmp'
    temporary.mkdir()
    workspaces = [root / 'worker-1', root / 'worker-2']
    for workspace in workspaces:
        workspace.mkdir()
    requests = []
    request_lock = threading.Lock()

    class Provider(BaseHTTPRequestHandler):
        def do_POST(self):
            body = json.loads(self.rfile.read(int(self.headers['Content-Length'])))
            with request_lock:
                requests.append(body)
                save(root / 'provider-requests.json', requests)
            self.send_response(200)
            self.send_header('Content-Type', 'text/event-stream')
            self.end_headers()
            for delta, reason in [({'role': 'assistant', 'content': 'Synthetic worker fixture completed.'}, None), ({}, 'stop')]:
                result = {'id': 'chatcmpl-fixture', 'object': 'chat.completion.chunk', 'created': int(time.time()),
                          'model': body['model'], 'choices': [{'index': 0, 'delta': delta, 'finish_reason': reason}]}
                self.wfile.write(('data: ' + json.dumps(result) + '\n\n').encode())
            self.wfile.write(b'data: [DONE]\n\n')
            self.wfile.flush()

        def log_message(self, *args):
            pass

    server = ThreadingHTTPServer(('127.0.0.1', 0), Provider)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    model = {'id': 'worker-fixture', 'name': 'worker-fixture', 'reasoning': False, 'input': ['text'],
             'contextWindow': 8192, 'maxTokens': 256,
             'cost': {'input': 0, 'output': 0, 'cacheRead': 0, 'cacheWrite': 0}}
    parent = dict(model, id='parent-must-not-request', name='parent-must-not-request')
    save(agent / 'models.json', {'providers': {'fixture': {'api': 'openai-completions',
         'baseUrl': f'http://127.0.0.1:{server.server_port}/v1', 'apiKey': 'synthetic-only', 'models': [model, parent]}}})
    save(agent / 'settings.json', {'packages': [], 'extensions': [], 'cacheWarming': 'off',
         'retry': {'enabled': False}, 'compaction': {'enabled': False}})
    policy = {'root': str(root), 'workspaces': list(map(str, workspaces)),
              'workerModel': 'fixture/worker-fixture', 'tools': ['read', 'write', 'edit', 'bash']}
    save(root / 'policy.json', policy)
    env = {**os.environ, 'PATH': str(PREFIX / 'packages/node_modules/.bin') + ':' + str(NODE.parent) + ':' + str(Path.home() / '.local/bin') + ':/usr/bin:/bin',
           'PI_CODING_AGENT_DIR': str(agent), 'TMPDIR': str(temporary),
           'PI_CODING_AGENT_SESSION_DIR': str(root / 'sessions'),
           'GX10_DELEGATION_POLICY': str(root / 'policy.json'), 'PI_TELEMETRY': '0'}
    command = [str(NODE), str(PI), '--mode', 'rpc', '--no-extensions', '--extension', str(HERE / 'companion.ts'),
               '--no-mcp', '--no-skills', '--no-context-files', '--no-prompt-templates', '--no-approve',
               '--offline', '--provider', 'fixture', '--model', 'parent-must-not-request', '--thinking', 'off']
    with (root / 'stderr.txt').open('w') as errors:
        process = subprocess.Popen(command, cwd=root, env=env, text=True, stdin=subprocess.PIPE,
                                   stdout=subprocess.PIPE, stderr=errors, start_new_session=True)
        def collect():
            with (root / 'rpc.jsonl').open('w') as output:
                for line in process.stdout:
                    output.write(line)
                    output.flush()
        reader = threading.Thread(target=collect, daemon=True)
        reader.start()

        def control(identity, operation, **args):
            request = {'id': identity, 'operation': operation, **args}
            process.stdin.write(json.dumps({'id': identity, 'type': 'prompt',
                'message': '/gx10-control ' + json.dumps(request)}) + '\n')
            process.stdin.flush()
            receipt = root / 'control' / (identity + '.json')
            for _ in range(300):
                if receipt.exists():
                    value = json.loads(receipt.read_text())
                    if value['state'] != 'started':
                        if value['state'] != 'completed':
                            raise RuntimeError(value)
                        return value['result']
                if process.poll() is not None:
                    raise RuntimeError('Pi exited before control receipt; inspect stderr.txt and rpc.jsonl')
                time.sleep(.1)
            raise RuntimeError('Finite RPC fixture timeout; inspect stderr.txt and rpc.jsonl')

        try:
            inventory = control('inventory', 'inventory')
            if 'subagent_spawn_batch' not in inventory['controls']:
                raise RuntimeError('Pinned plugin failed to register control tools')
            control('list-before', 'subagent_list')
            control('worker-phase', 'phase', phase='workers')
            launch = control('launch', 'subagent_spawn_batch', jobs=[
                {'worker': index, 'prompt': 'Synthetic test only. Respond DONE without tools.'} for index in range(2)])
            runs = launch['structuredContent']['launched']
            for run in runs:
                meta = temporary / 'pi-better-subagents/runs' / run['id'] / 'meta.json'
                for _ in range(300):
                    if meta.exists() and json.loads(meta.read_text()).get('status') != 'running':
                        break
                    time.sleep(.1)
                else:
                    raise RuntimeError('Synthetic child failed to settle')
                metadata = json.loads(meta.read_text())
                events = []
                for line in (meta.parent / 'output.log').read_text().splitlines():
                    if line.startswith('Warning: No project session found with id '):
                        continue
                    if line.strip():
                        events.append(json.loads(line))
                execution = normalize(events, metadata.get('exitCode'))
                save(root / ('execution-' + run['id'] + '.json'), execution.__dict__)
                if metadata.get('status') != 'completed' or execution.outcome != 'completed':
                    raise RuntimeError('Synthetic worker did not complete normally: ' + str(meta))
                control('result-' + run['id'], 'subagent_result', runId=run['id'])
                control('stop-' + run['id'], 'subagent_stop', runId=run['id'])
            control('integration-phase', 'phase', phase='integration')
            parent_requests = sum(row['model'] == 'parent-must-not-request' for row in requests)
            if parent_requests != 0 or len(requests) != 2:
                raise RuntimeError('Expected two synthetic worker requests and zero parent requests')
            result = {'synthetic_fixture': True, 'parent_model_requests': parent_requests,
                      'worker_provider_requests': len(requests), 'workers': runs,
                      'production_admission': False,
                      'pending': ['trusted tool workspace guards', 'model lifecycle adapter',
                                  'Harbor Distinct adapter', 'resource and recovery fixtures'],
                      'inventory': inventory}
            save(root / 'result.json', result)
            return result
        finally:
            process.terminate()
            try:
                process.wait(timeout=15)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=5)
            reader.join(timeout=5)
            server.shutdown()
            server.server_close()


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    print(json.dumps(probe(args.output.resolve()), indent=2))
