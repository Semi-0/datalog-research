"""Serial transparent inference proxy measuring backend counter deltas for every request.

All Pi calls, including compaction, pass through this proxy. Counter times are
backend inference times; proxy wall time includes prompt processing and transport.
"""
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
from pathlib import Path
import re
import threading
import time
import urllib.error
import urllib.request
from urllib.parse import urlsplit


FAMILIES = {
    'ollama': {'prefill_tokens': 'llamacpp:prompt_tokens_total',
               'prefill_seconds': 'llamacpp:prompt_seconds_total',
               'generated_tokens': 'llamacpp:tokens_predicted_total',
               'generation_seconds': 'llamacpp:tokens_predicted_seconds_total'},
    'vllm': {'prefill_tokens': 'vllm:prompt_tokens_total',
             'prefill_seconds': 'vllm:request_prefill_time_seconds_sum',
             'generated_tokens': 'vllm:generation_tokens_total',
             'generation_seconds': 'vllm:request_decode_time_seconds_sum'}}


def counters(text):
    values = {}
    for line in text.splitlines():
        if not line or line.startswith('#'):
            continue
        match = re.match(r'^([\w:]+)(?:\{[^}]*\})?\s+([\d.eE+\-]+)(?:\s|$)', line)
        if match:
            name, value = match.groups()
            values[name] = values.get(name, 0) + float(value)
    return values


def counter_delta(before, after, backend):
    result = {}
    for name, metric in FAMILIES[backend].items():
        if metric not in before or metric not in after or after[metric] < before[metric]:
            raise ValueError('Missing or reset inference counter: ' + metric)
        result[name] = after[metric] - before[metric]
    for phase, tokens, seconds in [('prefill', 'prefill_tokens', 'prefill_seconds'),
                                   ('generation', 'generated_tokens', 'generation_seconds')]:
        result[phase + '_tokens_per_second'] = None
        if result[seconds] > 0:
            result[phase + '_tokens_per_second'] = result[tokens] / result[seconds]
    return result


def completed_rates(rows):
    eligible = [row for row in rows if row.get('complete') and not row.get('error') and not row.get('metrics_error')]
    totals = {key: sum(row.get(key, 0) for row in eligible) for key in
              ['prefill_tokens', 'prefill_seconds', 'generated_tokens', 'generation_seconds']}
    for phase, tokens, seconds in [('prefill', 'prefill_tokens', 'prefill_seconds'),
                                   ('generation', 'generated_tokens', 'generation_seconds')]:
        totals[phase + '_tokens_per_second'] = None
        if totals[seconds] > 0:
            totals[phase + '_tokens_per_second'] = totals[tokens] / totals[seconds]
    return dict(totals, completed_requests=len(eligible))


def throughput_lines(rows):
    lines = ['', '## Measured inference throughput', '',
             '| Model | Task | Completed requests | Prefill/refill tokens/s | Generated tokens/s | Compaction seconds |',
             '| --- | --- | ---: | ---: | ---: | ---: |']
    for row in rows:
        if row['phase'] != 'coding':
            continue
        result = row['result']
        rates = result.get('inference', {}).get('completed_request_rates', {})
        values = [row['model'], row['task'], rates.get('completed_requests'),
                  rates.get('prefill_tokens_per_second'), rates.get('generation_tokens_per_second'),
                  result.get('compaction_wall_s')]
        lines.append('| ' + ' | '.join(str(value) for value in values) + ' |')
    lines += ['', 'Rates are counter/time ratios across completed requests, including auxiliary compaction calls; aborted requests are excluded. Setup, tools and cleanup are excluded from inference times.',
              'llama.cpp prefill measures processed uncached tokens; vLLM prefill counts submitted prompt tokens including cache hits, so those prefill rates are not directly comparable. Per-request records retain the basis and limitations.', '']
    return lines


def measurements_complete(result):
    summary = result.get('inference', {})
    rows = summary.get('records', [])
    return (bool(rows) and summary.get('requests') == len(rows) and all(
        row.get('complete') and not row.get('error') and not row.get('metrics_error') and
        row.get('prefill_tokens') is not None and row.get('prefill_seconds') is not None and
        (row['prefill_tokens'] == 0 or row['prefill_seconds'] > 0) and
        row.get('generated_tokens', 0) > 0 and row.get('generation_seconds', 0) > 0
        for row in rows))


class InferenceProxy:
    def __init__(self, upstream, metrics_url, backend, artifacts, budget=None, admission=None):
        self._upstream = upstream.rstrip('/')
        self._metrics_url = metrics_url
        self._backend = backend
        self._artifacts = artifacts
        self._budget = budget
        self._admission = admission
        self._serial = threading.Lock()
        self._totals_lock = threading.Lock()
        self._requests = 0
        self._generated_tokens = 0
        self._records = []
        # Fail setup before coding if the backend does not expose the required counters.
        initial = self._snapshot()
        counter_delta(initial, initial, backend)
        owner = self

        class Handler(BaseHTTPRequestHandler):
            protocol_version = 'HTTP/1.0'

            def log_message(self, *args):
                pass

            def do_GET(self):
                self._forward(False)

            def do_POST(self):
                self._forward(True)

            def _forward(self, post):
                with owner._serial:
                    body = None
                    if post:
                        body = self.rfile.read(int(self.headers.get('Content-Length', '0')))
                    parsed = urlsplit(owner._upstream)
                    url = parsed.scheme + '://' + parsed.netloc + self.path
                    request = urllib.request.Request(url, data=body, method=self.command,
                        headers={'Content-Type': self.headers.get('Content-Type', 'application/json'),
                                 'Authorization': self.headers.get('Authorization', 'Bearer local-benchmark')})
                    if not post:
                        try:
                            with urllib.request.urlopen(request, timeout=30) as response:
                                self.send_response(response.status)
                                self.end_headers()
                                self.wfile.write(response.read())
                        except Exception as error:
                            self.send_error(502, str(error))
                        return
                    if owner._admission is not None:
                        try:
                            owner._admission()
                        except Exception as error:
                            failure = {'outcome': 'setup_declined', 'error': repr(error), 'epoch': time.time()}
                            (owner._artifacts / 'startup-failure.json').write_text(json.dumps(failure))
                            self.send_error(412, 'Required tool startup failed; no inference admitted')
                            return
                    number = owner._next_request()
                    if number is None:
                        self.send_error(429, 'Benchmark request/token budget exhausted; no inference admitted')
                        return
                    record = {'started_epoch': time.time(), 'request': number,
                              'backend': owner._backend, 'path': self.path, 'complete': False,
                              'prefill_basis': 'uncached_processed_tokens', 'includes_thinking': True}
                    if owner._backend == 'vllm':
                        record['prefill_basis'] = 'submitted_prompt_tokens_including_cache_hits'
                        record['limitation'] = 'vLLM prompt counter includes cached tokens; decode duration excludes prefill/first-token work. Retain usage/cache data; do not compare this prefill rate with uncached llama.cpp rate.'
                    started = time.monotonic()
                    before = None
                    first = None
                    try:
                        before = owner._snapshot()
                        payload = json.loads(body)
                        record['requested_max_tokens'] = payload.get('max_tokens', payload.get('max_completion_tokens'))
                        record['submitted_messages'] = len(payload.get('messages', []))
                        with urllib.request.urlopen(request, timeout=1800) as response:
                            self.send_response(response.status)
                            self.send_header('Content-Type', response.headers.get('Content-Type', 'application/json'))
                            self.end_headers()
                            for line in response:
                                if first is None:
                                    first = time.monotonic() - started
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
                            record['complete'] = bool(record.get('finish_reason'))
                    except Exception as error:
                        record['error'] = repr(error)
                        if first is None:
                            try:
                                self.send_error(502, 'Inference forwarding failed')
                            except OSError:
                                pass
                    finally:
                        record['wall_s'] = time.monotonic() - started
                        record['first_stream_event_s'] = first
                        try:
                            if before is None:
                                raise ValueError('Initial metrics snapshot unavailable')
                            delta = counter_delta(before, owner._snapshot(), owner._backend)
                            settlement = time.monotonic() + 5
                            while record.get('complete') and (delta['generated_tokens'] <= 0 or delta['generation_seconds'] <= 0) and time.monotonic() < settlement:
                                time.sleep(.05)
                                delta = counter_delta(before, owner._snapshot(), owner._backend)
                            record.update(delta)
                        except Exception as error:
                            record['metrics_error'] = repr(error)
                        owner._record(record)

        self._server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        self._server.daemon_threads = True
        self._thread = threading.Thread(target=self._server.serve_forever, daemon=True)
        self._thread.start()
        self.base_url = 'http://127.0.0.1:' + str(self._server.server_port) + '/v1'

    def _snapshot(self):
        with urllib.request.urlopen(self._metrics_url, timeout=5) as response:
            return counters(response.read().decode())

    def _next_request(self):
        with self._totals_lock:
            if self._budget is not None and (self._requests >= self._budget['maximum_requests'] or
                    self._generated_tokens >= self._budget['maximum_generated_tokens']):
                return None
            self._requests += 1
            return self._requests

    def _record(self, record):
        with self._totals_lock:
            self._generated_tokens += record.get('generated_tokens', 0)
            self._records.append(record)
            with (self._artifacts / 'inference-requests.jsonl').open('a') as stream:
                stream.write(json.dumps(record) + '\n')

    def totals(self):
        with self._totals_lock:
            return self._requests, self._generated_tokens

    def summary(self):
        with self._totals_lock:
            return {'requests': self._requests, 'generated_tokens': self._generated_tokens,
                    'records': list(self._records), 'includes_compaction_requests': True,
                    'completed_request_rates': completed_rates(self._records),
                    'limitations': 'Incomplete/aborted requests retain partial counters and are excluded from completed-request speed comparisons. vLLM prefill counts include cache hits.'}

    def close(self):
        self._server.shutdown()
        self._server.server_close()
        self._thread.join(timeout=5)


def metrics_endpoint(base_url, cell):
    parsed = urlsplit(base_url)
    if parsed.port == 8001:
        return 'http://127.0.0.1:8001/metrics', 'vllm'
    runners = json.loads((cell / 'runner-identities.json').read_text())
    ports = {re.search(r'--port\s+(\d+)', runner['command']).group(1) for runner in runners
             if re.search(r'--port\s+(\d+)', runner['command'])}
    if len(ports) != 1:
        raise ValueError('Cannot establish a unique owned runner metrics endpoint')
    return 'http://127.0.0.1:' + ports.pop() + '/metrics', 'ollama'
