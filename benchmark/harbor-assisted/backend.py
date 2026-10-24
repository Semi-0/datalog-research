"""Admit and load one pinned Qwen copy for three full-context requests."""
import hashlib
import json
import re
import subprocess
import sys
import time
import urllib.request
import uuid
from pathlib import Path

from admission import save

HOME = Path('/home/lain')
EXPERIMENTS = HOME / 'experiments'
MODEL_DIRECTORY = EXPERIMENTS / 'qwen36-35b-a3b-nvfp4-20261023/model'
REVISION = '1355db6a052410cfd62085d94b58866fd0f2c3c5'
CONTEXT = 131072
CONCURRENCY = 3
KV_BYTES = 17179869184
CONTAINER_BYTES = 4 * 1024 ** 3


def request(path, body=None):
    data = None
    if body is not None:
        data = json.dumps(body).encode()
    request = urllib.request.Request('http://127.0.0.1:8001' + path, data=data,
        headers={'Authorization': 'Bearer local-benchmark', 'Content-Type': 'application/json'})
    with urllib.request.urlopen(request, timeout=120) as response:
        return json.load(response)


def artifact_hashes():
    manifest = json.loads((MODEL_DIRECTORY.parent / 'artifacts.json').read_text())
    if manifest['sha'] != REVISION:
        raise ValueError('Pinned model revision mismatch')
    hashes = {}
    for entry in manifest['siblings']:
        path = MODEL_DIRECTORY / entry['rfilename']
        path.resolve().relative_to(MODEL_DIRECTORY.resolve())
        expected = entry['lfs']['sha256']
        digest = hashlib.sha256()
        with path.open('rb') as handle:
            for block in iter(lambda: handle.read(8 * 1024 ** 2), b''):
                digest.update(block)
        if digest.hexdigest() != expected:
            raise ValueError('Pinned artifact hash mismatch: ' + entry['rfilename'])
        hashes[entry['rfilename']] = expected
    return hashes


def lifecycle(harness):
    sys.path.insert(0, str(harness / 'benchmark/gx10-context'))
    from lifecycle import Backend, experiment_lock, memory
    return Backend, experiment_lock, memory


def prepare(root, harness):
    Backend, _lock, memory = lifecycle(harness)
    prior = json.loads((EXPERIMENTS / 'gx10-qwen36-priority-1/plan.json').read_text())
    model = json.loads((EXPERIMENTS / 'gx10-harbor-pi-authfix-1/result.json').read_text())['model']
    manifest = prior['manifest']
    manifest['models'] = [model]
    root.mkdir(parents=True, exist_ok=False)
    backend = Backend(root, manifest)
    backend.cleanup()
    physical = memory()
    allowance = CONCURRENCY * CONTAINER_BYTES + 4 * 1024 ** 3
    projected = physical['available'] - model['weight_bytes'] - KV_BYTES - allowance
    budget = {'physical': physical, 'weight_bytes': model['weight_bytes'], 'kv_bytes': KV_BYTES,
              'container_parent_worker_tool_allowance_bytes': CONCURRENCY * CONTAINER_BYTES,
              'backend_workspace_allowance_bytes': 4 * 1024 ** 3,
              'projected_available_bytes': projected, 'concurrency': CONCURRENCY, 'context_per_request': CONTEXT}
    save(root / 'budget.json', budget)
    if projected < physical['reserve']:
        raise RuntimeError('Three-worker full-context capacity could not be admitted')
    save(root / 'artifact-hashes.json', artifact_hashes())
    command = json.loads((EXPERIMENTS / 'gx10-harbor-pi-authfix-1/backend/server-command.json').read_text())
    command[command.index('--max-num-seqs') + 1] = str(CONCURRENCY)
    command[command.index('--max-model-len') + 1] = str(CONTEXT)
    if any('speculat' in argument for argument in command):
        raise ValueError('MTP/speculative configuration is forbidden')
    save(root / 'server-command.json', command)
    return backend, command, model


def load(backend, command):
    started = time.monotonic_ns()
    unit = 'gx10-assisted-backend-' + uuid.uuid4().hex[:12] + '.service'
    backend.unit = unit
    toolchain = EXPERIMENTS / 'ornith-nvfp4-mtp-20261021/toolchain/extracted/usr/include'
    environment = {'PATH': str(HOME / '.local/opt/vllm-ornith-nvfp4/bin') + ':/usr/local/cuda/bin:/usr/bin:/bin',
        'CUDA_HOME': '/usr/local/cuda', 'CPATH': str(toolchain) + ':' + str(toolchain / 'python3.12'),
        'CUTE_DSL_ARCH': 'sm_121a', 'MAX_JOBS': '2', 'FLASHINFER_NVCC_THREADS': '1',
        'VLLM_MARLIN_USE_ATOMIC_ADD': '1'}
    backend.launch(unit, command, environment=environment)
    backend.wait_ready('http://127.0.0.1:8001/health')
    models = request('/v1/models')
    if len(models['data']) != 1 or models['data'][0]['id'] != 'qwen36-35b-a3b-nvfp4':
        raise RuntimeError('Unexpected served model copy or identity')
    if models['data'][0]['max_model_len'] != CONTEXT:
        raise RuntimeError('Backend reduced the requested context')
    log = subprocess.check_output(['journalctl', '--user', '-u', unit, '-o', 'cat', '--no-pager'],
                                  text=True, timeout=30)
    (backend.root / 'backend-startup.log').write_text(log)
    match = re.search(r'GPU KV cache size:\s*([\d,]+) tokens', log)
    if not match or int(match[1].replace(',', '')) < CONCURRENCY * CONTEXT:
        raise RuntimeError('Fresh KV-cache capacity does not establish three full contexts')
    if 'speculative_config=None' not in log or "'max_num_seqs': 3" not in log:
        raise RuntimeError('Fresh backend did not confirm MTP off and three sequences')
    ready = time.monotonic_ns()
    preload = request('/v1/chat/completions', {'model': 'qwen36-35b-a3b-nvfp4',
        'messages': [{'role': 'user', 'content': 'Think briefly and reply READY.'}],
        'temperature': 1.0, 'max_tokens': 64, 'seed': 42})
    finished = time.monotonic_ns()
    save(backend.root / 'preload.json', preload)
    save(backend.root / 'configuration-verification.json', {'models': models,
        'max_num_seqs': CONCURRENCY, 'context_per_request': CONTEXT,
        'kv_capacity_tokens': int(match[1].replace(',', '')), 'mtp': False,
        'loading_started_ns': started, 'model_ready_ns': ready, 'warmup_finished_ns': finished,
        'load_s': (ready - started) / 1e9, 'warmup_s': (finished - ready) / 1e9})
    return unit
