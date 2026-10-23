"""Concurrent local inference routing with per-agent budgets and wire measurements.

Never attributes shared native counters to individual workers. Every Pi request,
including compaction, must traverse a worker-specific immutable provider URL.
"""
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
from pathlib import Path
import threading
import time
import urllib.request


class BoundedProxy:
    def __init__(self, upstream, jobs, policy, admission=None):
        self.upstream = upstream.rstrip('/')
        self.policy = policy
        self.jobs = {job['lane']: Path(job['root']) for job in jobs}
        self.state = {lane: {'requests': 0, 'tokens': 0, 'incomplete': 0} for lane in self.jobs}
        self.lock = threading.Lock()
        owner = self
        class Handler(BaseHTTPRequestHandler):
            protocol_version = 'HTTP/1.0'
            def log_message(self, *args):
                pass
            def do_POST(self):
                parts = self.path.strip('/').split('/')
                if len(parts) != 4 or parts[0] not in owner.jobs or parts[1:] != ['v1', 'chat', 'completions']:
                    self.send_error(404)
                    return
                lane = parts[0]
                if admission is not None:
                    try:
                        admission(owner.jobs[lane])
                    except Exception as error:
                        failure = {'outcome': 'setup_declined', 'error': repr(error), 'epoch': time.time()}
                        (owner.jobs[lane] / 'artifacts/startup-failure.json').write_text(json.dumps(failure))
                        self.send_error(412, 'Required worker tools missing; no inference admitted')
                        return
                with owner.lock:
                    state = owner.state[lane]
                    if state['requests'] >= policy['maximum_requests'] or state['tokens'] >= policy['maximum_generated_tokens']:
                        self.send_error(429, 'Per-agent request/token budget exhausted')
                        return
                    state['requests'] += 1
                    number = state['requests']
                started = time.monotonic()
                record = {'request': number, 'epoch': time.time(), 'lane': lane, 'stream_done': False}
                headers_sent = False
                try:
                    body = self.rfile.read(int(self.headers.get('Content-Length', '0')))
                    payload = json.loads(body)
                    record['stream'] = bool(payload.get('stream'))
                    record['max_tokens'] = payload.get('max_tokens')
                    record['messages'] = len(payload.get('messages', []))
                    request = urllib.request.Request(owner.upstream + '/chat/completions', body,
                        headers={'Content-Type': 'application/json', 'Authorization': 'Bearer local-benchmark'})
                    with urllib.request.urlopen(request, timeout=1800) as response:
                        self.send_response(response.status)
                        self.send_header('Content-Type', response.headers.get('Content-Type', 'text/event-stream'))
                        self.end_headers()
                        headers_sent = True
                        first = True
                        for line in response:
                            if first:
                                record['first_stream_event_s'] = time.monotonic() - started
                                first = False
                            self.wfile.write(line)
                            self.wfile.flush()
                            data = line.decode(errors='replace').strip()
                            if data.startswith('data: '):
                                data = data[6:]
                            if data == '[DONE]':
                                record['stream_done'] = True
                            elif data.startswith('{'):
                                value = json.loads(data)
                                if value.get('usage'):
                                    record['usage'] = value['usage']
                                for choice in value.get('choices', []):
                                    if choice.get('finish_reason') is not None:
                                        record['finish_reason'] = choice['finish_reason']
                except Exception as error:
                    record['error'] = repr(error)
                    if not headers_sent:
                        self.send_error(502, 'Inference forwarding failed')
                finally:
                    record['wall_s'] = time.monotonic() - started
                    record['complete'] = bool(record.get('finish_reason')) and (not record.get('stream') or record['stream_done']) and bool(record.get('usage'))
                    with owner.lock:
                        owner.state[lane]['tokens'] += record.get('usage', {}).get('completion_tokens', 0)
                        if not record['complete']:
                            owner.state[lane]['incomplete'] += 1
                        with (owner.jobs[lane] / 'artifacts/inference-requests.jsonl').open('a') as stream:
                            stream.write(json.dumps(record) + '\n')
        self.server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        self.server.daemon_threads = True
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

    def url(self, lane):
        if lane not in self.jobs:
            raise ValueError('Unknown worker identity')
        return 'http://127.0.0.1:' + str(self.server.server_port) + '/' + lane + '/v1'

    def totals(self, lane):
        with self.lock:
            return dict(self.state[lane])

    def close(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=5)
