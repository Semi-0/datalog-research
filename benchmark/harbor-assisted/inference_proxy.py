"""Local worker request budgets, a first-request barrier, and auditable stream timing."""
import http.client
import json
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

MODEL = 'qwen36-35b-a3b-nvfp4'
KEY = 'local-benchmark'


class RequestBook:
    def __init__(self, workers, log):
        self.workers = {name: {'requests': 0, 'generated_tokens': 0, 'usage_complete': True,
                               'budget_closed': False} for name in workers}
        self.ready = set()
        self.condition = threading.Condition()
        self.released_ns = None
        self.barrier_failed = False
        self.parent_requests = 0
        self.log = Path(log)

    def event(self, kind, **fields):
        with self.condition:
            with self.log.open('a') as handle:
                handle.write(json.dumps({'kind': kind, 'monotonic_ns': time.monotonic_ns(), **fields}) + '\n')

    def admit(self, worker, body):
        with self.condition:
            state = self.workers[worker]
            remaining = 200000 - state['generated_tokens']
            if state['requests'] >= 128 or remaining <= 0 or state['budget_closed']:
                raise ValueError('request_or_token_budget_exhausted')
            if body.get('model') != MODEL:
                raise ValueError('unexpected_model')
            body.update(temperature=1.0, top_p=0.95, top_k=20, min_p=0,
                        repetition_penalty=1, presence_penalty=0, seed=42)
            body['max_tokens'] = min(int(body.get('max_tokens', 16384)), remaining, 32768)
            if body['max_tokens'] <= 0:
                raise ValueError('invalid_output_budget')
            if body.get('stream'):
                body['stream_options'] = {'include_usage': True}
            state['requests'] += 1
            return state['requests']

    def barrier(self, worker, timeout=300):
        deadline = time.monotonic() + timeout
        with self.condition:
            if self.barrier_failed:
                raise TimeoutError('readiness_barrier_failed')
            self.ready.add(worker)
            self.event('worker_ready', worker=worker)
            if self.ready == set(self.workers):
                self.released_ns = time.monotonic_ns()
                self.event('barrier_release', workers=sorted(self.ready))
                self.condition.notify_all()
            while self.released_ns is None:
                remaining = deadline - time.monotonic()
                if self.barrier_failed:
                    raise TimeoutError('readiness_barrier_failed')
                if remaining <= 0:
                    self.barrier_failed = True
                    self.condition.notify_all()
                    raise TimeoutError('readiness_barrier_timeout')
                self.condition.wait(remaining)

    def finish(self, worker, tokens):
        with self.condition:
            if tokens is None:
                self.workers[worker]['budget_closed'] = True
                self.workers[worker]['usage_complete'] = False
                self.event('usage_missing_budget_closed', worker=worker)
            else:
                self.workers[worker]['generated_tokens'] += tokens


class ProxyServer(ThreadingHTTPServer):
    daemon_threads = True

    def __init__(self, address, book, upstream_port=8001):
        self.book = book
        self.upstream_port = upstream_port
        super().__init__(address, ProxyHandler)


class ProxyHandler(BaseHTTPRequestHandler):
    def log_message(self, *_args):
        pass

    def fail(self, code, message):
        payload = json.dumps({'error': {'message': message}}).encode()
        self.send_response(code)
        self.send_header('Content-Type', 'application/json')
        self.send_header('Content-Length', str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    def do_GET(self):
        if self.path.endswith('/models'):
            payload = json.dumps({'object': 'list', 'data': [{'id': MODEL, 'object': 'model',
                'max_model_len': 131072}]}).encode()
        elif self.path == '/health':
            payload = b'{"ready":true}'
        else:
            self.fail(404, 'unknown_route')
            return
        self.send_response(200)
        self.send_header('Content-Type', 'application/json')
        self.send_header('Content-Length', str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    def do_POST(self):
        book = self.server.book
        parts = self.path.strip('/').split('/')
        if parts[0] == 'parent':
            book.parent_requests += 1
            book.event('parent_model_request_rejected', path=self.path)
            self.fail(403, 'parent_inference_prohibited')
            return
        if len(parts) < 4 or parts[0] != 'worker' or parts[1] not in book.workers:
            self.fail(404, 'unknown_worker')
            return
        worker = parts[1]
        if self.headers.get('Authorization') != 'Bearer ' + KEY:
            book.event('authentication_rejected', worker=worker)
            self.fail(401, 'invalid_api_key')
            return
        try:
            length = int(self.headers.get('Content-Length', 0))
            if length <= 0 or length > 16 * 1024 * 1024:
                raise ValueError('invalid_request_size')
            body = json.loads(self.rfile.read(length))
            number = book.admit(worker, body)
            if worker not in book.ready:
                book.barrier(worker)
        except (ValueError, KeyError, TimeoutError) as error:
            book.event('request_rejected', worker=worker, error=str(error))
            self.fail(429, str(error))
            return
        connection = http.client.HTTPConnection('127.0.0.1', self.server.upstream_port, timeout=120)
        tokens = None
        upstream_started = time.monotonic_ns()
        first_data_ns = None
        status = None
        error = None
        try:
            book.event('upstream_start', worker=worker, request=number)
            connection.request('POST', '/' + '/'.join(parts[2:]), json.dumps(body),
                {'Content-Type': 'application/json', 'Authorization': 'Bearer ' + KEY})
            response = connection.getresponse()
            status = response.status
            self.send_response(status)
            self.send_header('Content-Type', response.getheader('Content-Type', 'application/json'))
            self.end_headers()
            if body.get('stream'):
                for line in response:
                    if first_data_ns is None and line.startswith(b'data:'):
                        first_data_ns = time.monotonic_ns()
                    if line.startswith(b'data:') and line.strip() != b'data: [DONE]':
                        event = json.loads(line[5:])
                        if event.get('usage'):
                            tokens = int(event['usage']['completion_tokens'])
                    self.wfile.write(line)
                    self.wfile.flush()
            else:
                payload = response.read()
                data = json.loads(payload)
                if data.get('usage'):
                    tokens = int(data['usage']['completion_tokens'])
                self.wfile.write(payload)
        except Exception as caught:
            error = repr(caught)
        finally:
            connection.close()
            book.finish(worker, tokens)
            book.event('upstream_end', worker=worker, request=number,
                upstream_started_ns=upstream_started, first_data_ns=first_data_ns,
                status=status, generated_tokens=tokens, error=error)
