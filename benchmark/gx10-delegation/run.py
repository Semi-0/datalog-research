#!/usr/bin/env python3
"""Separate fourth delegation test. Never mutates or joins the active solo matrix."""
import argparse
import json
from pathlib import Path
import signal
import re
import subprocess
import sys
import time
import uuid

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent / 'gx10-context'))
from lifecycle import Backend, Failure, http, memory, save
from model_setup import start, file_hash
from runtime import node_environment, base_spec, NODE
from suite import wait_attempt
from preflight import sandbox_check
from contracts import seed_checkout


def git(checkout, *args):
    return subprocess.check_output(['git', *args], cwd=checkout, text=True).rstrip('\n')


def bootstrap(root):
    root.mkdir(parents=True)
    seed_checkout(root)
    (root / 'deps.edn').write_text('{:paths ["." "test"] :deps {org.clojure/clojure {:mvn/version "1.12.4"}}}\n')
    (root / '.gitignore').write_text('.cpcache/\n.clj-kondo/.cache/\ntarget/\n')
    git(root, 'init', '-q')
    git(root, 'add', '.')
    git(root, '-c', 'user.name=GX10 fixture', '-c', 'user.email=gx10@localhost', 'commit', '-qm', 'Fixed delegation fixture')
    return git(root, 'rev-parse', 'HEAD')


def clone(base, destination):
    subprocess.run(['git', 'clone', '--quiet', '--no-hardlinks', str(base), str(destination)], check=True)


def parallel_start(backend, model, protocol, count):
    """NVFP4-only setup with explicit concurrency; no change to the solo loader."""
    root = backend.root
    original = HERE.parent / 'ornith-nvfp4-mtp'
    artifact = json.loads((original / 'ornith-artifacts.json').read_text())
    if artifact['sha'] != model['revision']:
        raise Failure('setup_declined', 'NVFP4 revision differs')
    experiment = Path.home() / 'experiments/ornith-nvfp4-mtp-20261021'
    for item in artifact['siblings']:
        if file_hash(experiment / 'model' / item['rfilename']) != item['lfs']['sha256']:
            raise Failure('setup_declined', 'NVFP4 artifact hash differs')
    initial = memory()
    cache = 16 * 1024 ** 3
    allowance = (4 + 2 * count) * 1024 ** 3
    allowed = initial['available'] - model['weight_bytes'] - cache - allowance >= initial['reserve']
    save(root / 'budget.json', {'allowed': allowed, 'weight_bytes': model['weight_bytes'],
        'cache_bytes': cache, 'workspace_and_tools_allowance': allowance, 'memory': initial,
        'limitations': 'Conservative allowance; live 200 ms RAM guard remains authoritative.'})
    if not allowed:
        raise Failure('setup_declined', 'Clean parallel memory budget declined')
    command = json.loads((original / 'gx10-server-command.json').read_text())
    for flag, value in [('--max-model-len', 131072), ('--kv-cache-memory-bytes', cache), ('--max-num-seqs', count)]:
        command[command.index(flag) + 1] = str(value)
    generation_index = command.index('--override-generation-config') + 1
    generation = json.loads(command[generation_index])
    generation['temperature'] = protocol['sampling']['temperature']
    command[generation_index] = json.dumps(generation)
    unit = 'gx10-delegation-backend-' + uuid.uuid4().hex[:12] + '.service'
    backend.unit = unit
    save(root / 'server-command.json', command)
    backend.launch(unit, command, {'PATH': str(Path.home() / '.local/opt/vllm-ornith-nvfp4/bin') + ':/usr/local/cuda/bin:/usr/bin:/bin',
        'CUDA_HOME': '/usr/local/cuda', 'CPATH': str(experiment / 'toolchain/extracted/usr/include') + ':' + str(experiment / 'toolchain/extracted/usr/include/python3.12'),
        'CUTE_DSL_ARCH': 'sm_121a', 'MAX_JOBS': '2', 'FLASHINFER_NVCC_THREADS': '1', 'VLLM_MARLIN_USE_ATOMIC_ADD': '1'})
    registered = backend.wait_ready('http://127.0.0.1:8001/v1/models')
    models = registered.get('data', [])
    if len(models) != 1 or models[0]['id'] != model['source'] or models[0].get('max_model_len') != 131072:
        raise Failure('setup_declined', 'Parallel backend identity/context mismatch')
    warm = http('http://127.0.0.1:8001/v1/chat/completions', {'model': model['source'],
        'messages': [{'role': 'user', 'content': 'Think briefly and reply READY.'}],
        'reasoning_effort': 'high', 'temperature': protocol['sampling']['temperature'],
        'max_tokens': 64, 'stream': False, 'seed': 42}, timeout=1800)
    choice = warm['choices'][0]
    if not choice.get('finish_reason') or not (choice['message'].get('reasoning_content') or choice['message'].get('reasoning')):
        raise Failure('setup_declined', 'Preload thinking/finish marker missing')
    journal = subprocess.check_output(['journalctl', '--user', '-u', unit, '--no-pager'], text=True)
    (root / 'backend-startup.log').write_text(journal)
    capacity = re.search(r'Maximum concurrency for 131,072 tokens per request: ([0-9.]+)x', journal)
    if 'GPU' not in journal or capacity is None or float(capacity.group(1)) < count:
        raise Failure('setup_declined', 'GPU cache capacity for requested concurrency was not established')
    save(root / 'configuration-verification.json', {'model': model, 'context': 131072, 'concurrency': count,
         'sampling': protocol['sampling'], 'cache_estimated_max_concurrency': float(capacity.group(1)),
         'registered': registered, 'preload': warm})
    return model['source'], 'http://127.0.0.1:8001/v1'


def model_phase(root, protocol, model, execute, concurrency=1):
    root.mkdir(parents=True)
    backend = Backend(root, protocol)
    clean = False
    result = None
    transition_started = time.monotonic()
    durations = {}
    try:
        backend.cleanup()
        durations['transition_before_s'] = time.monotonic() - transition_started
        clean = True
        started = time.monotonic()
        with backend.guard('setup'):
            if model['backend'] == 'vllm':
                name, url = parallel_start(backend, model, protocol, concurrency)
            else:
                name, url, _ = start(backend, model, 131072, uuid.uuid4().hex[:12])
        durations['setup_load_preload_s'] = time.monotonic() - started
        execution_started = time.monotonic()
        result = execute(backend, name, url)
        durations['execute_verify_collect_s'] = time.monotonic() - execution_started
        result['phase_wall_s'] = time.monotonic() - started
        result['durations'] = durations
        save(root / 'result.json', result)
        return result
    finally:
        if clean:
            cleanup_started = time.monotonic()
            if backend.attempt_unit:
                backend.stop(backend.attempt_unit)
            backend.cleanup()
            durations['cleanup_after_s'] = time.monotonic() - cleanup_started
            if result is not None:
                save(root / 'result.json', result)
        else:
            save(root / 'cleanup-failure.json', {'outcome': 'transition_failed'})


def plan_prompt():
    return ('Read TASK.md. Plan only; do not implement. Write plan.json with exactly '
            '{"assignments":[{"lane":"normalize","instructions":"..."}, ...]}. '
            'Use exactly the lanes normalize, project, order, batch once each. '
            'Each lane owns only its matching delegation/<lane>.clj and test/delegation/<lane>_test.clj. '
            'State interfaces, finite tests, error contracts and integration dependencies. '
            'The batch worker must report stub-dependent test failures honestly. Only plan.json may change.')


def finite_check(backend, checkout, command, output):
    identity = uuid.uuid4().hex[:12]
    result_path = output.with_suffix('.result.json')
    spec = output.with_suffix('.launch.json')
    env = node_environment(backend.root)
    job = {'root': str(backend.root), 'cwd': str(checkout), 'env': env}
    launch = base_spec([job], result_path, 'finite-check')
    launch.update(command=command, output=str(output), evaluator=str(HERE / 'acceptance.clj'))
    save(spec, launch)
    unit = 'gx10-delegation-attempt-eval-' + identity + '.service'
    backend.attempt_unit = unit
    backend.state['attempt_results'][unit] = {'result_path': str(result_path), 'cell': str(backend.root)}
    backend.persist()
    try:
        with backend.guard('finite-evaluation-' + identity):
            backend.launch(unit, [str(NODE), str(HERE / 'agents.mjs'), str(spec)], env)
            result = wait_attempt(backend, unit, result_path)
    finally:
        backend.stop(unit)
    return result['exit'], output.read_text()



def execute(output):
    """Run the orchestrator through the existing Python harness."""
    from orchestrator import execute as run_orchestrator
    run_orchestrator(output)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=['check', 'run'])
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    output = args.output.resolve()
    if output.exists():
        parser.error('Output must be a new directory')
    def interrupt(signum, frame):
        raise KeyboardInterrupt('Signal ' + str(signum))
    signal.signal(signal.SIGINT, interrupt)
    signal.signal(signal.SIGTERM, interrupt)
    try:
        if args.command == 'check':
            sandbox_check(output)
        else:
            execute(output)
    except BaseException as error:
        if output.exists():
            outcome = getattr(error, 'outcome', 'runtime_error')
            if isinstance(error, (KeyboardInterrupt, SystemExit)):
                outcome = 'interrupted'
            save(output / 'failure.json', {'outcome': outcome, 'error': repr(error)})
        raise


if __name__ == '__main__':
    main()
