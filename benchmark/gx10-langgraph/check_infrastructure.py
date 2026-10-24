#!/usr/bin/env python3
"""Verify the API, lgview proxy and model-free checkpoint persistence."""
import argparse
import json
from pathlib import Path
import subprocess
import time
import urllib.request
import uuid


def request(url, data=None, headers=None):
    payload = None
    effective_headers = dict(headers or {})
    if data is not None:
        payload = json.dumps(data).encode()
        effective_headers['Content-Type'] = 'application/json'
    with urllib.request.urlopen(urllib.request.Request(url, data=payload, headers=effective_headers), timeout=20) as response:
        return json.load(response)


def check(output, restart=False):
    base = 'http://127.0.0.1:2024'
    assistants = request(base + '/assistants/search', {})
    assistant = next(row for row in assistants if row['graph_id'] == 'gx10_delegation')
    graph = request(base + '/assistants/' + assistant['assistant_id'] + '/graph')
    required = {'Admission', 'Implementation worker', 'Test worker', 'Harbor independent verification'}
    if not required.issubset({node['id'] for node in graph['nodes']}):
        raise RuntimeError('Expected visible stages are missing')
    proxy = request('http://127.0.0.1:4141/__lg/ok', headers={'x-lgview-upstream': base})
    if proxy.get('ok') is not True:
        raise RuntimeError('lgview proxy failed API health request')
    thread = request(base + '/threads', {})
    identity = 'infrastructure-' + uuid.uuid4().hex
    result = request(base + '/threads/' + thread['thread_id'] + '/runs/wait', {
        'assistant_id': assistant['assistant_id'], 'input': {'run_id': identity, 'mode': 'infrastructure'}})
    if result.get('stage') != 'infrastructure_inspected':
        raise RuntimeError('Model-free graph invocation failed: ' + json.dumps(result))
    persisted = False
    if restart:
        subprocess.run(['systemctl', '--user', 'restart', 'gx10-langgraph-api.service'], check=True, timeout=30)
        for _ in range(30):
            try:
                request(base + '/ok')
                break
            except Exception:
                time.sleep(1)
        else:
            raise RuntimeError('API failed to recover after restart')
        restored = request(base + '/threads/' + thread['thread_id'] + '/state')
        if restored.get('values', {}).get('run_id') != identity:
            raise RuntimeError('Infrastructure checkpoint did not survive API restart')
        persisted = True
    linger = subprocess.check_output(['loginctl', 'show-user', 'lain', '-p', 'Linger'], text=True, timeout=10).strip()
    result = {'scope': 'infrastructure_only', 'health': proxy, 'thread_id': thread['thread_id'],
              'graph_nodes': [node['id'] for node in graph['nodes']], 'checkpoint_survived_api_restart': persisted,
              'linger': linger, 'models_loaded_by_check': False, 'epoch': time.time()}
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(result, indent=2) + '\n')
    print(json.dumps(result, indent=2))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--restart', action='store_true')
    args = parser.parse_args()
    check(args.output, args.restart)
