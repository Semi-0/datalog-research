"""Harbor task staging, execution and independent result collection."""
import hashlib
import json
import re
import shutil
import subprocess
import threading
import time
import urllib.request
import uuid
from pathlib import Path

from admission import command, save
from inference_proxy import ProxyServer, RequestBook
import security

HERE = Path(__file__).resolve().parent
HARBOR = '/home/lain/.local/opt/harbor-eval/bin/harbor'
EXPECTED = {'distinct': 14, 'order': 17, 'events': 42, 'smoke': 4}


def stage_task(source, target, image, owner, seccomp):
    shutil.copytree(source, target)
    environment = target / 'environment'
    (environment / 'Dockerfile').write_text(f'''FROM {image}
USER root
RUN rm -rf /app && mkdir /app
WORKDIR /app
ENV PATH=/usr/local/lib/pilot/bin:/usr/local/lib/pilot/node_modules/.bin:/usr/local/lib/pilot/java/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
COPY project/ /app/
RUN git init /app && git -C /app -c user.name=benchmark -c user.email=benchmark@localhost add . && git -C /app -c user.name=benchmark -c user.email=benchmark@localhost commit -m 'Frozen task scaffold' && chown -R agent:agent /app
''')
    (environment / 'docker-compose.yaml').write_text(f'''services:
  main:
    network_mode: host
    networks: !reset []
    labels:
      gx10.assisted.owner: {owner}
    security_opt:
      - apparmor={security.PROFILE}
      - seccomp={seccomp}
      - no-new-privileges=true
''')
    config = (target / 'task.toml').read_text()
    config = config.replace('memory_mb = 2048', 'memory_mb = 4096')
    config = re.sub(r'(?m)^cohort = .*$', 'cohort = "codex-assisted-qwen36-concurrent-pilot-v1"', config)
    (target / 'task.toml').write_text(config)
    # The original independent evaluator is transported unchanged.
    original = {str(p.relative_to(source / 'tests')): hashlib.sha256(p.read_bytes()).hexdigest()
                for p in (source / 'tests').rglob('*') if p.is_file()}
    staged = {str(p.relative_to(target / 'tests')): hashlib.sha256(p.read_bytes()).hexdigest()
              for p in (target / 'tests').rglob('*') if p.is_file()}
    if original != staged:
        raise RuntimeError('Independent evaluator changed during staging')
    return original


def owned_containers(owner):
    result = command(['docker', 'ps', '-aq', '--filter', 'label=gx10.assisted.owner=' + owner])
    if result['exit_code'] != 0:
        raise RuntimeError('Could not inspect owned Harbor containers')
    return result['stdout'].split()


def cleanup(owner, root):
    before = owned_containers(owner)
    removal = None
    if before:
        removal = command(['docker', 'rm', '-f', *before])
    remaining = owned_containers(owner)
    receipt = {'selected': before, 'removal': removal, 'remaining': remaining,
               'cleanup_verified': not remaining, 'monotonic_ns': time.monotonic_ns()}
    save(root / 'container-cleanup.json', receipt)
    if remaining:
        raise RuntimeError('Owned Harbor containers remain; cleanup uncertain')
    return receipt


def metrics(root, done):
    with (root / 'backend-metrics.jsonl').open('a') as log:
        while not done.is_set():
            sample = {'monotonic_ns': time.monotonic_ns()}
            try:
                request = urllib.request.Request('http://127.0.0.1:8001/metrics',
                    headers={'Authorization': 'Bearer local-benchmark'})
                with urllib.request.urlopen(request, timeout=1) as response:
                    text = response.read().decode()
                for key in ['num_requests_running', 'num_requests_waiting']:
                    values = re.findall(r'^vllm:' + key + r'(?:\{[^\n]*\})?\s+([0-9.eE+-]+)', text, re.MULTILINE)
                    if not values:
                        raise ValueError('Backend metric unavailable: ' + key)
                    sample[key] = sum(float(value) for value in values)
            except Exception as error:
                sample['error'] = repr(error)
            log.write(json.dumps(sample) + '\n')
            log.flush()
            done.wait(.2)


def counts(text):
    matches = re.findall(r'Ran\s+(\d+)\s+tests? containing\s+(\d+)\s+assertions?\.\s*(\d+)\s+failures?,\s*(\d+)\s+errors?\.', text)
    if not matches:
        raise ValueError('No inspectable assertion summary')
    return {'tests': sum(int(row[0]) for row in matches), 'assertions': sum(int(row[1]) for row in matches),
            'failures': sum(int(row[2]) for row in matches), 'errors': sum(int(row[3]) for row in matches)}


def trial_result(trial, task):
    result = json.loads((trial / 'result.json').read_text())
    assertions = counts((trial / 'verifier/test-stdout.txt').read_text())
    reward = result.get('verifier_result', {}).get('rewards', {}).get('reward')
    correct = reward == 1 and assertions['assertions'] == EXPECTED[task]
    correct = correct and assertions['failures'] == 0 and assertions['errors'] == 0
    agent = trial / 'agent/parent'
    execution = {'outcome': 'runtime_error'}
    if (agent / 'execution.json').exists():
        execution = json.loads((agent / 'execution.json').read_text())
    collection = {}
    if (agent / 'collection.json').exists():
        collection = json.loads((agent / 'collection.json').read_text())
    workflow = {'parent_model_requests': execution.get('parent_model_requests'),
                'plugin_registry_cleanup': collection.get('cleanup_verified', False)}
    transcript = agent / 'worker-session.jsonl'
    workflow['transcript_preserved'] = transcript.is_file()
    if transcript.exists():
        messages = [json.loads(line).get('message', {}) for line in transcript.read_text().splitlines() if line.strip()]
        used = [message for message in messages if message.get('role') == 'toolResult' and not message.get('isError')]
        names = {message.get('toolName') for message in used}
        workflow['successful_tools'] = sorted(name for name in names if name)
        own_assertions = []
        for message in used:
            text = '\n'.join(item.get('text', '') for item in message.get('content', []) if item.get('type') == 'text')
            try:
                own_assertions.append(counts(text))
            except ValueError:
                pass
        workflow['positive_own_test_assertions'] = any(row['assertions'] > 0 and row['failures'] == 0 and row['errors'] == 0 for row in own_assertions)
        if task != 'smoke':
            workflow['public_web_requirement'] = {'web_search', 'fetch_content'} <= names
    compliant = workflow['parent_model_requests'] == 0 and workflow['plugin_registry_cleanup'] and workflow['transcript_preserved']
    if task == 'smoke' and transcript.exists():
        calls = {}
        for message in messages:
            if message.get('role') == 'assistant':
                for item in message.get('content', []):
                    if item.get('type') == 'toolCall' and item.get('name') == 'bash':
                        calls[item.get('id')] = item.get('arguments', {}).get('command', '')
        workflow['finite_assertion_command_succeeded'] = any(
            re.search(r'assert|run-tests', calls.get(message.get('toolCallId'), '')) for message in used)
        compliant = compliant and (workflow.get('positive_own_test_assertions', False)
                                   or workflow['finite_assertion_command_succeeded'])
    else:
        compliant = compliant and workflow.get('positive_own_test_assertions', False)
    if task != 'smoke':
        compliant = compliant and workflow.get('public_web_requirement', False)
    return {'task': task, 'correct': correct, 'verifier': assertions, 'execution': execution,
            'workflow': workflow, 'workflow_compliant': compliant, 'trial': str(trial),
            'completed': execution['outcome'] == 'completed'}


def run_phase(backend, root, phase, sources, tasks, image, base, tool_receipt):
    root.mkdir(parents=True, exist_ok=False)
    owner = 'gx10-assisted-' + phase + '-' + uuid.uuid4().hex[:12]
    seccomp = root / 'seccomp.json'
    security.options(base, root)
    stage = root / 'tasks'
    stage.mkdir()
    evaluator_hashes = {}
    for name in tasks:
        evaluator_hashes[name] = stage_task(sources[name], stage / name, image, owner, seccomp)
    save(root / 'evaluator-hashes.json', evaluator_hashes)
    book = RequestBook(tasks, root / 'requests.jsonl')
    proxy = ProxyServer(('127.0.0.1', 8003), book)
    proxy_thread = threading.Thread(target=proxy.serve_forever, daemon=True)
    proxy_thread.start()
    done = threading.Event()
    metric_thread = threading.Thread(target=metrics, args=(root, done), daemon=True)
    metric_thread.start()
    manifest = {'phase': phase, 'source_dir': str(HERE), 'tasks': tasks,
        'proxy': 'http://127.0.0.1:8003', 'tools_admitted': tool_receipt['outcome'] == 'tools_verified'}
    save(root / 'manifest.json', manifest)
    unit = 'gx10-assisted-attempt-' + phase + '-' + uuid.uuid4().hex[:12] + '.service'
    backend.attempt_unit = unit
    backend.state['attempt_results'][unit] = {'result_path': str(root / 'result.json'), 'cell': str(root)}
    backend.persist()
    argv = [HARBOR, 'run', '-p', str(stage), '-a', 'harbor_agent:AssistedPi',
            '-m', 'pilot/qwen36-35b-a3b-nvfp4', '-n', '1', '-k', str(len(tasks)), '-r', '0',
            '-o', str(root / 'jobs'), '--job-name', phase,
            '--ae', 'GX10_PILOT_MANIFEST=' + str(root / 'manifest.json')]
    save(root / 'command.json', argv)
    started = time.monotonic_ns()
    try:
        backend.launch(unit, argv, environment={'PYTHONPATH': str(HERE), 'HARBOR_TELEMETRY': '0'})
        while backend.system('show', unit, '-p', 'SubState', '--value') not in ['dead', 'failed', 'exited']:
            if backend.breached.is_set():
                raise RuntimeError('RAM reserve crossed during Harbor phase')
            time.sleep(.2)
        exit_code = int(backend.system('show', unit, '-p', 'ExecMainStatus', '--value'))
        records = []
        job = root / 'jobs' / phase
        for trial in sorted(job.iterdir()):
            if not trial.is_dir() or not (trial / 'result.json').exists():
                continue
            native = json.loads((trial / 'result.json').read_text())
            name = native.get('task_name')
            if name not in tasks:
                candidates = [name for name in tasks if trial.name.startswith(name + '__')]
                if len(candidates) != 1:
                    raise RuntimeError('Unrecognized native Harbor task: ' + trial.name)
                name = candidates[0]
            records.append(trial_result(trial, name))
        samples = [json.loads(line) for line in (root / 'backend-metrics.jsonl').read_text().splitlines()]
        maximum = max((sample.get('num_requests_running', 0) for sample in samples), default=0)
        result = {'phase': phase, 'harbor_exit': exit_code, 'trials': records,
            'correct_tasks': sum(record['correct'] for record in records), 'expected_tasks': len(tasks),
            'parent_model_requests': book.parent_requests, 'workers': book.workers,
            'barrier_released_ns': book.released_ns, 'maximum_backend_running_requests': maximum,
            'started_monotonic_ns': started, 'finished_monotonic_ns': time.monotonic_ns()}
        result['accepted'] = exit_code == 0 and len(records) == len(tasks) and book.parent_requests == 0
        result['accepted'] = result['accepted'] and all(record['completed'] and record['workflow_compliant'] for record in records)
        if phase == 'smoke':
            result['accepted'] = result['accepted'] and result['correct_tasks'] == 1
        else:
            result['accepted'] = result['accepted'] and maximum >= 2 and book.released_ns is not None
        save(root / 'result.json', result)
        return result
    finally:
        backend.stop(unit)
        cleanup(owner, root)
        done.set()
        metric_thread.join(timeout=3)
        proxy.shutdown()
        proxy.server_close()
