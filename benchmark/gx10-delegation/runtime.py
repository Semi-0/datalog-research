"""Effects for isolated Pi sessions; model ownership remains with the outer lifecycle."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import threading
import time
import urllib.request
import uuid

HERE = Path(__file__).resolve().parent
CORE = (HERE.parent / 'gx10-context').resolve()
sys.path.insert(0, str(CORE))
sys.path.insert(0, str(HERE.parent / 'ornith-nvfp4-mtp'))
from lifecycle import Failure, save
from coding import prepare, environment
from pi_extensions import prepare_vigilant
from supervision import ProgressSupervisor
from inference_metrics import counters, counter_delta, metrics_endpoint
from proxy import BoundedProxy
from tooling import configure as configure_tooling, verify as verify_tooling, TOOLS

PACKAGES = Path.home() / 'experiments/gx10-delegation-packages-1'
NODE = Path.home() / '.local/opt/node-v24.22.1-linux-arm64/bin/node'
PI = Path.home() / '.local/lib/node_modules/@earendil-works/pi-coding-agent/dist/bundle/cli.js'


def node_environment(root):
    env = environment(root)
    env['PATH'] = str(NODE.parent) + ':' + str(PACKAGES / 'native/usr/bin') + ':' + env['PATH']
    env['LD_LIBRARY_PATH'] = str(PACKAGES / 'native/usr/lib/aarch64-linux-gnu')
    (root / 'tmp').mkdir(parents=True, exist_ok=True)
    env['GX10_AGENT_ROOT'] = str(root)
    return env


def agent_job(root, checkout, lane, prompt, model, url, protocol):
    prepare(root, model, url, 131072)
    vigilant = prepare_vigilant(root, protocol)
    env = node_environment(root)
    env['GX10_TOOL_ROLE'] = 'controller' if lane in ('planner', 'reviewer', 'integrator') else 'implementor'
    extensions = configure_tooling(root, checkout, env, protocol)
    settings = json.loads((root / 'agent/settings.json').read_text())
    settings['extensions'] = extensions + [str(vigilant), str(CORE / 'pi-runtime.mjs'), str(HERE / 'tooling.mjs'), str(HERE / 'observe.mjs')]
    save(root / 'agent/settings.json', settings)
    trust_flag = '--no-approve'
    if env['GX10_TOOL_ROLE'] == 'implementor':
        trust_flag = '--approve'
    extension_args = [value for entry in settings['extensions'] for value in ['--extension', entry]]
    invocation = {'command': str(NODE), 'args': [str(PI), '--no-extensions'] + extension_args +
        ['--no-mcp', '--no-skills', '--no-prompt-templates', '--no-context-files', trust_flag, '--offline',
         '--provider', 'local-benchmark', '--thinking', 'high']}
    role_instructions = 'Use web_search and fetch_content for relevant public documentation. Plan and assign work only; do not implement code.'
    if lane == 'integrator':
        role_instructions = 'Integrate the bounded worker patches and resolve integration issues within the assigned files. Use finite tests and inspect the worker assessment reports.'
    if env['GX10_TOOL_ROLE'] == 'implementor':
        role_instructions = 'Use web_search and fetch_content, query your private Gortex workspace, and run lens_diagnostics with source=lsp and a changed .clj path (an empty session cache is not a check). Use lsp_navigation documentSymbol on that path. Run clj_kondo before final tests and repair errors. Stay within the assigned lane; finish with an assessment of assertions run, remaining defects and handoff risks.'
    full_prompt = prompt + '\n\n' + role_instructions + '\nNo overall deadline. Bash commands are limited to 120 seconds. Use focused finite tests; never treat command exit zero without assertions as success. No delegation, dependency installation, commits or pushes. Write only the assigned files.'
    if protocol['coding'].get('structural_editing') is not None:
        from structural_editing import GUIDANCE
        full_prompt += '\n' + GUIDANCE
    (root / 'artifacts/prompt.txt').write_text(full_prompt)
    policy = json.loads((root / 'agent/benchmark-policy.json').read_text())
    tools = list(dict.fromkeys(policy['allowed_tools'] + policy.get('gortex_tools', [])))
    return {'lane': lane, 'root': str(root), 'cwd': str(checkout), 'task': full_prompt,
            'model': 'local-benchmark/' + model, 'env': env, 'invocation': invocation, 'tools': tools,
            'publicWeb': True, 'gortexSocket': (json.loads((root / 'artifacts/gortex-profile.json').read_text())['socket']
                if env['GX10_TOOL_ROLE'] == 'implementor' else None)}


def base_spec(jobs, result, mode='agents'):
    home = Path.home()
    return {'mode': mode, 'packagePrefix': str(PACKAGES), 'result': str(result), 'jobs': jobs,
        'readPaths': [str(PACKAGES), str(NODE.parent.parent), str(PI.parents[2]),
            str(home / '.local/bin'), str(home / '.local/opt/java21'),
            str(home / '.clojure'), str(home / '.m2'), str(home / '.gitlibs'),
            str(home / '.local/lib/clojure'), str(home / '.local/opt/clj-kondo-2026.10.20'),
            str(CORE / 'pi-runtime.mjs'), str(HERE / 'observe.mjs'), str(HERE / 'tooling.mjs'), str(TOOLS),
            str(home / 'experiments/gx10-pi-packages')] +
            [entry for job in jobs for entry in json.loads(job.get('env', {}).get('GX10_STRUCTURAL_READ_PATHS', '[]'))]}


def metrics(url):
    with urllib.request.urlopen(url, timeout=5) as response:
        return response.read().decode()


def activity(root, offset):
    p = root / 'artifacts/activity.jsonl'
    if not p.exists():
        return [], offset
    with p.open() as stream:
        stream.seek(offset)
        lines = []
        while True:
            position = stream.tell()
            line = stream.readline()
            if not line:
                return lines, position
            try:
                lines.append(json.loads(line))
            except json.JSONDecodeError:
                return lines, position


def supervise(backend, unit, jobs, result_path, protocol, inference):
    states = {job['lane']: {'offset': 0, 'requests': 0, 'tokens': 0, 'normal_end': False,
              'ready': False, 'agent_end': False, 'progress': ProgressSupervisor(protocol['coding']['supervision'], time.monotonic())}
              for job in jobs}
    while True:
        for job in jobs:
            state = states[job['lane']]
            rows, state['offset'] = activity(Path(job['root']), state['offset'])
            for row in rows:
                if row['type'] == 'runtime_ready':
                    state['ready'] = True
                elif row['type'] == 'agent_end':
                    state['agent_end'] = True
                elif row['type'] == 'provider_request':
                    state['requests'] += 1
                elif row['type'] == 'assistant_end':
                    state['tokens'] += row.get('usage', {}).get('output', 0)
                    state['normal_end'] = row.get('stopReason') == 'stop'
                elif row['type'] == 'tool_end':
                    state['progress'].observe_tool(row['tool'], row['text'], row['failed'], time.monotonic())
            terminal = Path(job['root']) / 'terminal.json'
            if terminal.exists():
                continue
            measured = inference.totals(job['lane'])
            state['requests'] = measured['requests']
            state['tokens'] = measured['tokens']
            decision = state['progress'].check(time.monotonic(), state['requests'], state['tokens'])
            if decision is not None:
                save(Path(job['root']) / 'artifacts' / ('supervision-' + decision['action'] + '.json'), decision)
                if decision['action'] == 'steer':
                    save(Path(job['root']) / 'artifacts/supervision-steer-request.json',
                         {'message': 'Progress supervisor: stop repeated analysis. Run finite focused tests and repair concrete errors; report blockers explicitly.'})
                elif decision['action'] == 'stop':
                    backend.stop(unit)
                    raise Failure(decision['outcome'], 'Worker made no qualifying progress or exhausted budget: ' + job['lane'])
        if backend.breached.is_set():
            raise Failure('resource_stopped', 'RAM reserve crossed')
        status = backend.system('show', unit, '-p', 'SubState', '--value')
        if status in ['exited', 'failed', 'dead']:
            if not result_path.exists():
                raise Failure('runtime_error', 'Delegation controller ended without durable terminal results')
            result = json.loads(result_path.read_text())
            result['agents'] = {lane: {key: value for key, value in state.items() if key != 'progress'}
                                for lane, state in states.items()}
            if result['outcome'] != 'completed' or any(not s['ready'] or not s['normal_end'] or not s['agent_end'] for s in states.values()):
                raise Failure('runtime_error', 'Missing runtime acknowledgement or normal agent ending')
            save(result_path, result)
            return result
        time.sleep(.2)


def run_agents(backend, jobs, folder, protocol, url):
    folder.mkdir(parents=True, exist_ok=True)
    result_path = folder / 'result.json'
    spec_path = folder / 'launch.json'
    endpoint, kind = metrics_endpoint(url, backend.root)
    before = metrics(endpoint)
    (folder / 'metrics-before.txt').write_text(before)
    unit = 'gx10-delegation-attempt-' + uuid.uuid4().hex[:12] + '.service'
    backend.attempt_unit = unit
    backend.state['attempt_results'][unit] = {'result_path': str(result_path), 'cell': str(folder)}
    backend.persist()
    started = time.monotonic()
    done = threading.Event()
    observed_running = []
    startup_error = [None]
    def watch_metrics():
        with (folder / 'concurrency.jsonl').open('w') as log:
            while not done.is_set():
                failures = [Path(job['root']) / 'artifacts/startup-failure.json' for job in jobs]
                failed = next((path for path in failures if path.is_file()), None)
                if failed is not None:
                    startup_error[0] = str(failed)
                    backend.stop(unit)
                    return
                try:
                    sample = counters(metrics(endpoint))
                    running = sample.get('vllm:num_requests_running')
                    if running is None:
                        running = sample.get('llamacpp:requests_processing')
                    if running is not None:
                        observed_running.append(running)
                    log.write(json.dumps({'epoch': time.time(), 'running': running}) + '\n')
                    log.flush()
                except Exception as error:
                    log.write(json.dumps({'epoch': time.time(), 'error': repr(error)}) + '\n')
                done.wait(.2)
    from tooling import require_ready
    inference = BoundedProxy(url, jobs, protocol['coding']['supervision'], admission=require_ready)
    for job in jobs:
        model_path = Path(job['root']) / 'agent/models.json'
        model_config = json.loads(model_path.read_text())
        job['endpoint'] = inference.url(job['lane'])
        model_config['providers']['local-benchmark']['baseUrl'] = job['endpoint']
        save(model_path, model_config)
    save(spec_path, base_spec(jobs, result_path))
    monitor = threading.Thread(target=watch_metrics, daemon=True)
    monitor.start()
    try:
        with backend.guard(folder.name):
            backend.launch(unit, [str(NODE), str(HERE / 'agents.mjs'), str(spec_path)], node_environment(folder))
            result = supervise(backend, unit, jobs, result_path, protocol, inference)
            if startup_error[0] is not None:
                raise Failure('setup_declined', 'Worker tool startup failed: ' + startup_error[0])
    except BaseException as error:
        if startup_error[0] is not None:
            raise Failure('setup_declined', 'Worker tool startup failed: ' + startup_error[0]) from error
        raise
    finally:
        done.set()
        monitor.join(timeout=6)
        try:
            backend.stop(unit)
        finally:
            inference.close()
    result['workflow_diagnostics'] = []
    for job in jobs:
        try:
            verify_tooling(Path(job['root']))
        except Failure as error:
            diagnostic = {'lane': job['lane'], 'check': 'post_run_tooling',
                          'classification': error.outcome, 'error': str(error)}
            result['workflow_diagnostics'].append(diagnostic)
            save(Path(job['root']) / 'artifacts/workflow-diagnostics.json', diagnostic)
    result['wire_usage'] = {job['lane']: inference.totals(job['lane']) for job in jobs}
    if any(value['requests'] == 0 or value['incomplete'] for value in result['wire_usage'].values()):
        save(result_path, result)
        raise Failure('runtime_error', 'Missing complete per-agent inference usage evidence')
    result['peak_running_requests'] = max(observed_running, default=0)
    if len(jobs) > 1 and result['peak_running_requests'] < len(jobs):
        save(result_path, result)
        raise Failure('setup_declined', 'Requested concurrent inference was not observed; inspect concurrency.jsonl')
    after = metrics(endpoint)
    (folder / 'metrics-after.txt').write_text(after)
    result['backend_counter_ratios'] = counter_delta(counters(before), counters(after), kind)
    result['wall_s'] = time.monotonic() - started
    result['aggregate_generated_tokens_per_wall_second'] = result['backend_counter_ratios']['generated_tokens'] / result['wall_s']
    result['limitations'] = 'Counters cover the whole phase; overlapping worker counters are not individual speeds. vLLM prompt counts include cache hits. Counter/time ratios are not aggregate wall throughput.'
    save(result_path, result)
    return result
