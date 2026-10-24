"""Exercise the real Pi/plugin parent without loading an inference model."""
import argparse
import fcntl
import json
import subprocess
import threading
import time
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

from admission import command, save
from inference_proxy import MODEL, ProxyServer, RequestBook
import security


class FixtureProvider(BaseHTTPRequestHandler):
    def log_message(self, *_args):
        pass

    def do_POST(self):
        self.rfile.read(int(self.headers['Content-Length']))
        self.server.arrived.set()
        if self.server.mode == 'auth-error':
            self.send_response(401)
            self.end_headers()
            self.wfile.write(b'{"error":{"message":"401 invalid api key"}}')
            return
        if self.server.mode == 'interrupt':
            self.server.release.wait(20)
        self.send_response(200)
        self.send_header('Content-Type', 'text/event-stream')
        self.end_headers()
        frames = [
            {'choices': [{'index': 0, 'delta': {'role': 'assistant', 'content': 'FIXTURE_COMPLETE'}, 'finish_reason': None}]},
            {'choices': [{'index': 0, 'delta': {}, 'finish_reason': 'stop'}]},
            {'choices': [], 'usage': {'prompt_tokens': 1, 'completion_tokens': 1, 'total_tokens': 2}},
        ]
        for index, frame in enumerate(frames):
            frame.update(id='fixture-completion', object='chat.completion.chunk', model=MODEL, created=int(time.time()))
            self.wfile.write(b'data: ' + json.dumps(frame).encode() + b'\n\n')
        self.wfile.write(b'data: [DONE]\n\n')
        self.wfile.flush()


def exercise(image, source, base, root, mode='normal'):
    root.mkdir(parents=True, exist_ok=False)
    name = 'gx10-assisted-control-' + uuid.uuid4().hex[:12]
    provider = ThreadingHTTPServer(('127.0.0.1', 0), FixtureProvider)
    provider.daemon_threads = True
    provider.mode = mode
    provider.arrived = threading.Event()
    provider.release = threading.Event()
    book = RequestBook(['fixture'], root / 'requests.jsonl')
    proxy = ProxyServer(('127.0.0.1', 0), book, provider.server_port)
    for server in [provider, proxy]:
        threading.Thread(target=server.serve_forever, daemon=True).start()
    config = {'task': 'fixture', 'proxy': f'http://127.0.0.1:{proxy.server_port}',
        'tools': ['read', 'bash', 'edit', 'write'], 'toolsAdmitted': True, 'extensions': [],
        'instruction': 'Synthetic non-coding control fixture. Reply FIXTURE_COMPLETE only. Do not call tools.'}
    save(root / 'worker-config.json', config)
    argv = ['docker', 'run', '--rm', '--name', name, '--network', 'host', '--memory', '2g',
            '-e', 'PATH=/usr/local/lib/pilot/bin:/usr/local/lib/pilot/node_modules/.bin:/usr/local/lib/pilot/java/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin',
            '--user', 'agent', *security.options(base, root),
            '--mount', f'type=bind,src={root},dst=/logs/agent']
    for filename in ['parent.mjs', 'execution.mjs', 'companion.ts']:
        argv += ['--mount', f'type=bind,src={source / filename},dst=/usr/local/lib/pilot/{filename},readonly']
    argv += [image, 'node', '/usr/local/lib/pilot/parent.mjs', '/logs/agent/worker-config.json']
    result = {'synthetic_fixture': True, 'mode': mode, 'inference_models_loaded': 0}
    try:
        with (root / 'driver.stdout').open('w') as stdout, (root / 'driver.stderr').open('w') as stderr:
            process = subprocess.Popen(argv, stdout=stdout, stderr=stderr)
            if mode == 'interrupt':
                if not provider.arrived.wait(60):
                    raise RuntimeError('Synthetic child never reached its provider')
                command(['docker', 'kill', '--signal', 'TERM', name])
            code = process.wait(timeout=90)
        execution = None
        path = root / 'parent/execution.json'
        if path.exists():
            execution = json.loads(path.read_text())
        controller = json.loads((root / 'parent/controller.json').read_text())
        result.update(driver_exit=code, execution=execution, registry_empty=controller['runs'] == [],
                      parent_model_requests=book.parent_requests, worker_provider_requests=book.workers['fixture']['requests'])
        if book.parent_requests != 0 or controller['runs']:
            raise RuntimeError('Parent inference or uncertain plugin cleanup')
        if mode == 'normal':
            if code != 0 or not execution or execution['outcome'] != 'completed':
                raise RuntimeError('Synthetic normal worker did not complete')
        elif mode == 'auth-error':
            if code == 0 or not execution or execution['outcome'] != 'authentication_error':
                raise RuntimeError('Authentication error was hidden')
        elif mode == 'interrupt':
            failure = json.loads((root / 'parent/failure.json').read_text())
            if code == 0 or 'interrupted' not in failure['error']:
                raise RuntimeError('Interruption was hidden')
        result['passed'] = True
    except BaseException as error:
        result.update(passed=False, error=repr(error))
    finally:
        provider.release.set()
        removal = command(['docker', 'rm', '-f', name])
        remaining = command(['docker', 'ps', '-aq', '--filter', 'name=^/' + name + '$'])
        result['cleanup_verified'] = remaining['exit_code'] == 0 and not remaining['stdout'].strip()
        save(root / 'cleanup.json', {'removal': removal, 'remaining': remaining})
        save(root / 'result.json', result)
        proxy.shutdown()
        provider.shutdown()
    return result


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--image', required=True)
    parser.add_argument('--source', type=Path, default=Path(__file__).resolve().parent)
    parser.add_argument('--seccomp-base', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--mode', choices=['normal', 'auth-error', 'interrupt'], default='normal')
    args = parser.parse_args()
    lock = Path('/home/lain/.local/state/gx10-benchmark/experiment.lock')
    with lock.open('a') as handle:
        fcntl.flock(handle, fcntl.LOCK_EX | fcntl.LOCK_NB)
        result = exercise(args.image, args.source, args.seccomp_base, args.output, args.mode)
    print(json.dumps(result, indent=2))
    if not result.get('passed') or not result['cleanup_verified']:
        raise SystemExit(1)
