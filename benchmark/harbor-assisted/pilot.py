"""Run the admitted one-worker smoke, then exactly one assisted three-task cohort."""
import argparse
import hashlib
import json
import shutil
import time
from pathlib import Path

from admission import save, sha256
import backend as shared
from cohort import run_phase

HOME = Path('/home/lain')
EXPERIMENTS = HOME / 'experiments'
HERE = Path(__file__).resolve().parent


def admitted_receipt(path, **expected):
    receipt = json.loads(path.read_text())
    for name, value in expected.items():
        if receipt.get(name) != value:
            raise ValueError(f'Admission receipt mismatch: {path}: {name}')
    return receipt


def verify_frozen(tasks, frozen):
    if set(frozen['tasks']) != {'distinct', 'order', 'events'}:
        raise ValueError('Frozen cohort must contain exactly three tasks')
    if frozen['outlines_source_sha256'] != sha256(HERE / 'outlines.json'):
        raise ValueError('Outlines changed after freezing')
    for name, record in frozen['tasks'].items():
        if not 0 < record['qwen_token_count'] <= 128:
            raise ValueError('Invalid frozen Qwen outline count')
        for relative, expected in record['files_sha256'].items():
            if sha256(tasks / name / relative) != expected:
                raise ValueError('Frozen task or evaluator changed: ' + name + '/' + relative)


def worker_timings(record, phase_root):
    trial = Path(record['trial'])
    rows = [json.loads(line) for line in (trial / 'agent/parent/timings.jsonl').read_text().splitlines()]
    selected = {}
    for row in rows:
        if row['kind'] in ['parent_start', 'parent_ready', 'worker_launched', 'worker_terminal', 'parent_collection_finished']:
            selected[row['kind']] = int(row['monotonic_ns'])
    requests = [json.loads(line) for line in (phase_root / 'requests.jsonl').read_text().splitlines()]
    ready = next((row['monotonic_ns'] for row in requests if row['kind'] == 'worker_ready' and row['worker'] == record['task']), None)
    release = next((row['monotonic_ns'] for row in requests if row['kind'] == 'barrier_release'), None)
    timings = {'boundaries_monotonic_ns': selected, 'request_ready_ns': ready, 'barrier_release_ns': release}
    if 'parent_ready' in selected and 'parent_start' in selected:
        timings['parent_startup_s'] = (selected['parent_ready'] - selected['parent_start']) / 1e9
    if ready is not None and 'worker_launched' in selected:
        timings['worker_startup_s'] = (ready - selected['worker_launched']) / 1e9
    if ready is not None and release is not None:
        timings['readiness_wait_s'] = (release - ready) / 1e9
    terminal = selected.get('worker_terminal', selected.get('parent_collection_finished'))
    if terminal is not None and release is not None:
        timings['coding_to_terminal_or_stop_s'] = (terminal - release) / 1e9
    if 'worker_terminal' in selected and 'parent_collection_finished' in selected:
        timings['collection_and_plugin_cleanup_s'] = (selected['parent_collection_finished'] - selected['worker_terminal']) / 1e9
    timings['successful_completion_time'] = record['correct'] and record['completed']
    return timings


def run(args):
    started = time.monotonic_ns()
    args.output.mkdir(parents=True, exist_ok=False)
    result = {'cohort': 'codex-assisted-qwen36-concurrent-pilot-v1',
              'started_monotonic_ns': started, 'outcome': 'setup_declined', 'cohort_started': False}
    backend = None
    tool = admitted_receipt(args.tool_admission, outcome='tools_verified', cleanup_verified=True, model_requests=0)
    for path, mode in [(args.normal_fixture, 'normal'), (args.auth_fixture, 'auth-error'),
                       (args.interrupt_fixture, 'interrupt')]:
        admitted_receipt(path, synthetic_fixture=True, mode=mode, passed=True,
                         parent_model_requests=0, cleanup_verified=True)
    frozen = json.loads(args.frozen.read_text())
    verify_frozen(args.tasks, frozen)
    save(args.output / 'frozen-outlines.json', frozen)
    snapshot = args.output / 'harness-source'
    snapshot.mkdir()
    sources = [path for path in HERE.iterdir()
               if path.is_file() and path.suffix in ['.py', '.mjs', '.ts', '.json']]
    for path in sources:
        shutil.copy2(path, snapshot / path.name)
    save(args.output / 'source-hashes.json', {path.name: sha256(path) for path in sources})
    save(args.output / 'admissions.json', {'tools': str(args.tool_admission),
        'normal_fixture': str(args.normal_fixture), 'authentication_fixture': str(args.auth_fixture),
        'interruption_fixture': str(args.interrupt_fixture), 'frozen_receipt_sha256': sha256(args.frozen)})
    _Backend, experiment_lock, memory = shared.lifecycle(args.harness)
    with experiment_lock(HOME / '.local/state/gx10-benchmark/experiment.lock'):
        try:
            save(args.output / 'status.json', {'stage': 'preparing_backend', 'epoch': time.time()})
            backend, command, model = shared.prepare(args.output / 'backend', args.harness)
            result['model'] = model
            with backend.guard('pilot'):
                save(args.output / 'status.json', {'stage': 'loading_three_sequence_backend', 'epoch': time.time()})
                shared.load(backend, command)
                save(args.output / 'status.json', {'stage': 'unscored_smoke', 'epoch': time.time()})
                smoke_source = EXPERIMENTS / 'harbor-pilot-20261023'
                smoke_tasks = {'smoke': {'public_contract': (smoke_source / 'instruction.md').read_text(), 'outline': ''}}
                smoke = run_phase(backend, args.output / 'smoke', 'smoke', {'smoke': smoke_source},
                                  smoke_tasks, tool['image'], args.seccomp_base, tool)
                result['smoke'] = smoke
                if not smoke['accepted']:
                    raise RuntimeError('Unscored Harbor integration smoke failed; scored cohort not released')
                save(args.output / 'status.json', {'stage': 'three_task_cohort', 'epoch': time.time()})
                result['cohort_started'] = True
                save(args.output / 'cohort-launch-intent.json', {'single_cohort': True,
                     'monotonic_ns': time.monotonic_ns(), 'frozen_receipt_sha256': sha256(args.frozen)})
                sources = {name: args.tasks / name for name in frozen['tasks']}
                cohort = run_phase(backend, args.output / 'cohort', 'cohort', sources,
                                   frozen['tasks'], tool['image'], args.seccomp_base, tool)
                for record in cohort['trials']:
                    record['timing'] = worker_timings(record, args.output / 'cohort')
                result['cohort_result'] = cohort
                result['correctness'] = str(cohort['correct_tasks']) + '/3'
                result['outcome'] = 'completed'
                result['accepted'] = cohort['accepted']
                samples = [json.loads(line) for line in (backend.root / 'pilot-memory.jsonl').read_text().splitlines()]
                result['resources'] = {'peak_global_used_bytes': max(sample['used'] for sample in samples),
                    'minimum_available_bytes': min(sample['available'] for sample in samples),
                    'reserve_bytes': samples[0]['reserve'], 'monitor_interval_seconds': .2,
                    'swap_in_delta': samples[-1]['swap_in'] - samples[0]['swap_in'],
                    'swap_out_delta': samples[-1]['swap_out'] - samples[0]['swap_out']}
                result['timing_valid'] = result['resources']['swap_in_delta'] == 0 and result['resources']['swap_out_delta'] == 0
        except BaseException as error:
            result['error'] = repr(error)
            result['outcome'] = 'stopped'
            result['accepted'] = False
        finally:
            if backend is not None:
                try:
                    backend.cleanup()
                    result['cleanup_verified'] = True
                except BaseException as error:
                    result['cleanup_verified'] = False
                    result['cleanup_error'] = repr(error)
                    result['outcome'] = 'cleanup_uncertain'
                    result['accepted'] = False
            result['finished_monotonic_ns'] = time.monotonic_ns()
            result['total_pilot_wall_s'] = (result['finished_monotonic_ns'] - started) / 1e9
            save(args.output / 'result.json', result)
            save(args.output / 'status.json', {'stage': result['outcome'], 'epoch': time.time()})
    return result


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ['tool-admission', 'normal-fixture', 'auth-fixture', 'interrupt-fixture', 'frozen', 'output', 'seccomp-base']:
        parser.add_argument('--' + name, type=Path, required=True)
    parser.add_argument('--tasks', type=Path, default=EXPERIMENTS / 'gx10-harbor-tasks-v1-protected')
    parser.add_argument('--harness', type=Path, default=HOME / 'projects/KIROSHI')
    args = parser.parse_args()
    result = run(args)
    print(json.dumps(result, indent=2))
    if not result.get('accepted') or not result.get('cleanup_verified'):
        raise SystemExit(1)
