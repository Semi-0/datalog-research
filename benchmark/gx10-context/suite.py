#!/usr/bin/env python3
"""Protocol v3: independent cells with verified transitions and durable continuation."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import signal
import subprocess
import sys
import time
import uuid

from lifecycle import Backend, Failure, experiment_lock, http, memory, save
from model_setup import start, budget_projection
from outcomes import smoke_capabilities_verified
from inference_metrics import throughput_lines

HERE = Path(__file__).resolve().parent
NVFP4 = HERE.parent / 'ornith-nvfp4-mtp'
MANIFEST = json.loads((HERE / 'config.json').read_text())


def harness_hashes():
    paths = list(HERE.glob('*.py')) + list(HERE.glob('*.json')) + list(HERE.glob('*.mjs'))
    paths += list((HERE / 'prompts').glob('*')) + list((HERE / 'fixtures').glob('*'))
    paths += [p for p in (HERE / 'smoke-contract').rglob('*') if p.is_file()]
    paths += list((HERE / 'validation-fixtures/benchmark').glob('*.clj'))
    paths += [NVFP4 / 'coding.py', NVFP4 / 'nontermination.py', NVFP4 / 'gx10-server-command.json',
              NVFP4 / 'ornith-artifacts.json', HERE.parents[1] / 'scripts/run-background.sh']
    return {str(path.relative_to(HERE.parents[1])): hashlib.sha256(path.read_bytes()).hexdigest()
            for path in paths if path.name != 'validation.json'}


def runtime_versions():
    home = Path.home()
    commands = {'python': ['/usr/bin/python3', '--version'],
                'pi': [str(home / '.local/bin/pi'), '--version'],
                'ollama': [str(home / '.local/bin/ollama'), '--version'],
                'node': [str(home / '.local/bin/node'), '--version'],
                'java': [str(home / '.local/opt/java21/bin/java'), '--version'],
                'clojure': [str(home / '.local/bin/clojure'), '-Sdescribe'],
                'vllm': [str(home / '.local/opt/vllm-ornith-nvfp4/bin/python'), '-c',
                         'from importlib.metadata import version; print(version("vllm"))']}
    records = {}
    for name, command in commands.items():
        try:
            result = subprocess.run(command, text=True, capture_output=True, timeout=30)
            records[name] = {'command': command, 'exit': result.returncode,
                             'stdout': result.stdout, 'stderr': result.stderr}
        except (OSError, subprocess.TimeoutExpired) as error:
            records[name] = {'command': command, 'error': repr(error)}
    return records


def model_by_id(name):
    return next(model for model in MANIFEST['models'] if model['id'] == name)


def phase(root, stage, **details):
    save(root / 'checkpoint.json', {'stage': stage, 'epoch': time.time(), **details})


def wait_attempt(backend, unit, result_file):
    while True:
        if backend.breached.is_set():
            raise Failure('resource_stopped', 'RAM reserve crossed')
        state = backend.system('show', unit, '-p', 'SubState', '--value')
        if state in ['exited', 'failed', 'dead']:
            if result_file.exists():
                return json.loads(result_file.read_text())
            exit_kind = backend.system('show', unit, '-p', 'ExecMainCode', '--value')
            if exit_kind == '2':
                raise Failure('interrupted', 'Attempt service was killed before a durable result')
            raise Failure('runtime_error', 'Attempt service ended without a durable result')
        if backend.breached.is_set():
            raise Failure('resource_stopped', 'RAM reserve crossed')
        time.sleep(.2)


def run_cell(model, task, output, context=None, seed=None, tokenizer=None, smoke=False):
    """The single lifecycle used by standalone, suite, smoke and context attempts.

    Caller holds the host experiment lock. A cleanup failure is fatal to scheduling.
    """
    if context is None:
        context = MANIFEST['coding']['context']
    output.mkdir(parents=True, exist_ok=False)
    identity = uuid.uuid4().hex[:12]
    backend = Backend(output, MANIFEST)
    result = None
    transition_verified = False
    durations = {}
    started = time.monotonic()
    save(output / 'manifest.json', MANIFEST)
    save(output / 'harness-hashes.json', harness_hashes())
    save(output / 'cell.json', {'model': model, 'task': task, 'context': context, 'seed': seed,
                               'identity': identity, 'unscored': smoke})
    try:
        phase(output, 'metadata')
        save(output / 'runtime-versions.json', runtime_versions())
        phase(output, 'cleanup_before')
        tick = time.monotonic()
        backend.cleanup()
        transition_verified = True
        durations['transition_before_s'] = time.monotonic() - tick
        phase(output, 'setup')
        tick = time.monotonic()
        with backend.guard('setup'):
            name, base_url, server_unit = start(backend, model, context, identity)
        durations['setup_load_preload_s'] = time.monotonic() - tick
        if (output / 'phase-durations.json').exists():
            durations.update(json.loads((output / 'phase-durations.json').read_text()))
        if task == 'transition':
            result = {'outcome': 'passed', 'protocol_pass': True, 'unscored': True}
        else:
            output_child = output / 'attempt'
            unit = 'gx10-v3-attempt-' + identity + '.service'
            backend.attempt_unit = unit
            backend.state['attempt_results'][unit] = {'result_path': str(output_child / 'result.json'), 'cell': str(output)}
            backend.persist()
            if seed is None:
                runner = NVFP4 / 'coding.py'
                if smoke:
                    runner = HERE / 'smoke.py'
                command = ['/usr/bin/python3', str(runner), '--task', task, '--model', name,
                           '--base-url', base_url, '--server-unit', server_unit, '--output', str(output_child),
                           '--context', str(context)]
            else:
                command = [str(Path.home() / '.local/opt/vllm-ornith-nvfp4/bin/python'),
                           str(HERE / 'context_probe.py'), '--backend', model['backend'], '--model', name,
                           '--context', str(context), '--seed', str(seed), '--tokenizer', str(tokenizer),
                           '--server-unit', server_unit, '--output', str(output_child)]
            if seed is None and not smoke and MANIFEST['coding'].get('tool_profile') == 'enhanced':
                command = ['/usr/bin/env', 'GX10_TOOL_PROFILE=enhanced'] + command
            stage = 'coding'
            if seed is not None:
                stage = 'context'
            phase(output, stage, unit=unit)
            tick = time.monotonic()
            with backend.guard('execute-evaluate'):
                backend.launch(unit, command)
                result = wait_attempt(backend, unit, output_child / 'result.json')
            durations['execute_evaluate_s'] = time.monotonic() - tick
        save(output / 'result.json', dict(result, durations=durations))
        phase(output, 'result_durable')
    except BaseException as error:
        outcome = getattr(error, 'outcome', 'runtime_error')
        if isinstance(error, (KeyboardInterrupt, SystemExit)):
            outcome = 'interrupted'
        result = {'outcome': outcome, 'error': repr(error), 'correct': None, 'workflow_pass': False,
                  'protocol_pass': False, 'timing_valid': False, 'elapsed_censored': True, 'durations': durations}
        save(output / 'result.json', result)
        backend.event('cell_failure', result)
    finally:
        if not transition_verified:
            save(output / 'cleanup-failure.json', {'outcome': 'transition_failed', 'primary_result': result})
            raise Failure('transition_failed', 'Initial cleanup gate failed; scheduling stopped')
        phase(output, 'cleanup_after', result=result)
        tick = time.monotonic()
        try:
            # Stop tools before requesting model unload, even after interrupted generation.
            if backend.attempt_unit:
                backend.stop(backend.attempt_unit)
            if backend.unit:
                try:
                    journal = subprocess.run(['journalctl', '--user', '-u', backend.unit, '--no-pager'],
                                             text=True, capture_output=True, timeout=15)
                    (output / 'backend-execution.log').write_text(journal.stdout + journal.stderr)
                except Exception as error:
                    backend.event('journal_capture_failed', {'error': repr(error)})
            backend.cleanup()
        except BaseException as error:
            save(output / 'cleanup-failure.json', {'outcome': 'transition_failed', 'error': repr(error),
                                                  'primary_result': result})
            phase(output, 'cleanup_failed')
            raise Failure('transition_failed', 'Cleanup uncertain; no further scheduling permitted') from error
        durations['cleanup_after_s'] = time.monotonic() - tick
        durations['total_s'] = time.monotonic() - started
        result['durations'] = durations
        save(output / 'result.json', result)
        phase(output, 'complete')
    if result['outcome'] in ['transition_failed', 'interrupted']:
        raise Failure(result['outcome'], 'Scheduling stopped at ' + str(output))
    return result


def row_key(row):
    return (row['phase'], row['model'], row.get('task'), row.get('context'), row.get('seed'))


def retained_rows(root, cleanup_verified=False):
    """Recover a completed cell even if interrupted before results.json publication."""
    rows = {}
    for record in sorted(root.glob('cells/*/cell.json')):
        directory = record.parent
        checkpoint = directory / 'checkpoint.json'
        if not checkpoint.exists():
            continue
        stage = json.loads(checkpoint.read_text())['stage']
        if stage != 'complete' and not cleanup_verified:
            continue
        result_path = directory / 'result.json'
        if not result_path.exists() and cleanup_verified:
            result_path = directory / 'attempt/result.json'
        if not result_path.exists():
            continue
        cell = json.loads(record.read_text())
        result = json.loads(result_path.read_text())
        if result['outcome'] in ['interrupted', 'transition_failed']:
            continue
        kind = 'coding'
        if cell['seed'] is not None:
            kind = 'context'
        row = {'phase': kind, 'model': cell['model']['id'], 'task': cell['task'],
               'context': cell['context'], 'seed': cell['seed'], 'path': str(directory), 'result': result}
        rows[row_key(row)] = row
    previous = root / 'results.json'
    if previous.exists():
        for row in json.loads(previous.read_text()):
            rows.setdefault(row_key(row), row)
    return list(rows.values())


def report(root, rows):
    lines = ['# GX10 protocol ' + str(MANIFEST['protocol_version']) + ' results', '', 'One attempt per coding cell. No speed medians. Agent attempts use progress supervision and finite request/token budgets; there is no overall wall-clock deadline.',
             'Correctness, agent workflow and timing validity are independent. Setup declines are excluded from correctness denominators.',
             'Interrupted and nonterminating elapsed times are censored. Earlier exploratory results are separate.', '',
             '| Phase | Model | Task/context/seed | Outcome | Correct | Workflow | Timing valid | Raw result |',
             '| --- | --- | --- | --- | --- | --- | --- | --- |']
    for row in rows:
        r = row['result']
        label = row['task']
        if row['phase'] == 'context':
            label = str(row['context']) + '/' + str(row['seed'])
        lines.append('| ' + ' | '.join(map(str, [row['phase'], row['model'], label, r['outcome'],
                     r.get('correct'), r.get('workflow_pass'), r.get('timing_valid'), row['path']])) + ' |')
    lines += ['', 'Context records retain actual occupancy and RAM. A budget decline is not an observed resource failure.',
              'A largest tested success is not a proven maximum. Check status.json for completion.', '']
    lines += throughput_lines(rows)
    (root / 'REPORT.md').write_text('\n'.join(lines))
    save(root / 'summary.json', {'protocol_version': MANIFEST['protocol_version'], 'rows': rows, 'coding_record_count':
         sum(row['phase'] == 'coding' for row in rows)})


def tokenizer_paths(root):
    # Reuse the previously frozen download, never silently select a new tokenizer revision.
    prior = Path.home() / 'experiments/gx10-coding-context-20261021-r3'
    metadata = json.loads((prior / 'tokenizers.json').read_text())
    paths = {'ornith': Path.home() / 'experiments/ornith-nvfp4-mtp-20261021/model/tokenizer.json',
             'flash': prior / 'flash-tokenizer.json'}
    for name, path in paths.items():
        if hashlib.sha256(path.read_bytes()).hexdigest() != metadata['hashes'][name]:
            raise Failure('runtime_error', 'Frozen tokenizer hash differs: ' + name)
    save(root / 'tokenizers.json', metadata)
    return paths


def check_validation(path):
    evidence = json.loads(path.read_text())
    if not evidence.get('passed') or evidence.get('harness_hashes') != harness_hashes():
        raise Failure('setup_declined', 'Current harness has not passed deterministic and live validation')


def run(args):
    check_validation(args.validation)
    root = args.output.resolve()
    root.mkdir(parents=True, exist_ok=False)
    (root / 'cells').mkdir()
    save(root / 'manifest.json', MANIFEST)
    save(root / 'harness-hashes.json', harness_hashes())
    rows = []
    if args.continue_from:
        old_manifest = json.loads((args.continue_from / 'manifest.json').read_text())
        if old_manifest != MANIFEST:
            raise Failure('setup_declined', 'Continuation requires the same protocol manifest')
        previous_hashes = args.continue_from / 'harness-hashes.json'
        if previous_hashes.exists():
            frozen = json.loads(previous_hashes.read_text())
        else:
            frozen = json.loads((args.continue_from / 'validation.json').read_text())['harness_hashes']
        if frozen != harness_hashes():
            raise Failure('setup_declined', 'Continuation requires the identical frozen harness')
        phase(root, 'recovery_cleanup')
        Backend(root, MANIFEST).cleanup()
        rows = retained_rows(args.continue_from, cleanup_verified=True)
        save(root / 'recovery.json', {'cleanup_verified': True, 'source': str(args.continue_from.resolve()),
             'evidence': str(root / 'lifecycle.jsonl'), 'retained': len(rows)})
        save(root / 'continuation.json', {'source': str(args.continue_from.resolve()), 'retained': len(rows)})
    def schedule(kind, model, task, context=None, seed=None, tokenizer=None):
        if context is None:
            context = MANIFEST['coding']['context']
        key = (kind, model['id'], task, context, seed)
        prior = next((row for row in rows if row_key(row) == key), None)
        if prior is not None:
            return prior['result']
        label = model['id'] + '-' + task + '-' + str(context) + '-' + str(seed)
        directory = root / 'cells' / (label + '-' + uuid.uuid4().hex[:8])
        phase(root, kind, cell=label)
        result = run_cell(model, task, directory, context, seed, tokenizer)
        rows.append({'phase': kind, 'model': model['id'], 'task': task, 'context': context,
                     'seed': seed, 'path': str(directory), 'result': result})
        save(root / 'results.json', rows)
        report(root, rows)
        return result
    for model in MANIFEST['models']:
        for task in MANIFEST['coding']['tasks']:
            schedule('coding', model, task)
    scan_contexts(root, MANIFEST['models'], schedule)
    save(root / 'status.json', {'stage': 'complete', 'result_count': len(rows)})
    report(root, rows)


def scan_contexts(root, models, schedule):
    """Shared resource-boundary search; retrieval quality remains a separate result."""
    paths = tokenizer_paths(root)
    for model in models:
        resource_fit = None
        upper = None
        queue = list(MANIFEST['context_scan'])
        while queue:
            context = queue.pop(0)
            outcomes = []
            for seed in MANIFEST['seeds']:
                tokenizer = paths['ornith']
                if model['id'] == 'flash-iq4':
                    tokenizer = paths['flash']
                result = schedule('context', model, 'retrieval', context, seed, tokenizer)
                outcomes.append(result)
                if result['outcome'] in ['resource_stopped', 'setup_declined', 'runtime_error', 'timed_out']:
                    break
            # A completed but incorrect retrieval still establishes that this resource allocation fit.
            if all(r['outcome'] in ['passed', 'task_failed'] for r in outcomes) and len(outcomes) == len(MANIFEST['seeds']):
                resource_fit = context
            elif any(r['outcome'] in ['resource_stopped', 'setup_declined'] for r in outcomes):
                upper = context
                queue = []
                if resource_fit is None and context == 32768:
                    queue = list(MANIFEST['fallback_contexts'])
            elif any(r['outcome'] in ['runtime_error', 'timed_out'] for r in outcomes):
                # An unknown infrastructure failure is not a resource boundary.
                queue = []
                upper = None
            else:
                # Retrieval/workflow failure is a semantic result, not a resource bracket.
                upper = None
            if upper is not None and resource_fit is not None and upper - resource_fit > MANIFEST['boundary_resolution_tokens']:
                resolution = MANIFEST['boundary_resolution_tokens']
                queue = [((upper + resource_fit) // 2 // resolution) * resolution]


def transition_path(models):
    """Visit every ordered pair once, including self transitions, in 17 loads."""
    remaining = {model['id']: list(reversed(models)) for model in models}
    stack = [models[0]]
    path = []
    while stack:
        choices = remaining[stack[-1]['id']]
        if choices:
            stack.append(choices.pop())
        else:
            path.append(stack.pop())
    return list(reversed(path))


def validate_live(args):
    """Unscored smoke plus all ordered transitions; failures never unlock scoring."""
    root = args.output.resolve()
    root.mkdir(parents=True, exist_ok=False)
    deterministic = json.loads(args.deterministic.read_text())
    if not deterministic.get('passed') or deterministic.get('harness_hashes') != harness_hashes():
        raise Failure('runtime_error', 'Deterministic validation evidence missing or stale')
    persistence = json.loads(args.persistence.read_text())
    if not persistence.get('passed'):
        raise Failure('runtime_error', 'SSH persistence evidence is missing')
    rows = []
    def attempt(model, task, label, smoke=False):
        result = run_cell(model, task, root / label, smoke=smoke)
        capabilities = False
        if smoke:
            capabilities = smoke_capabilities_verified(result)
        rows.append({'model': model['id'], 'label': label, 'result': result,
                     'smoke_capabilities_verified': capabilities})
        save(root / 'live-validation.json', rows)
        if result['outcome'] not in ['passed', 'setup_declined'] and not capabilities:
            raise Failure('runtime_error', 'Live validation failed at ' + label)
        return result
    for model in MANIFEST['models']:
        reused = getattr(args, 'reuse_flash_smoke', None)
        if reused is not None and model['id'] == MANIFEST['coding']['pilot_model']:
            reused = reused.resolve()
            cell = json.loads((reused / 'cell.json').read_text())
            result = json.loads((reused / 'result.json').read_text())
            if (json.loads((reused / 'harness-hashes.json').read_text()) != harness_hashes() or
                    cell['model'] != model or cell['context'] != MANIFEST['coding']['context'] or
                    not result.get('protocol_pass') or
                    json.loads((reused / 'checkpoint.json').read_text())['stage'] != 'complete'):
                raise Failure('setup_declined', 'Flash smoke reuse requires identical verified clean evidence')
            rows.append({'model': model['id'], 'label': 'smoke-' + model['id'],
                         'path': str(reused), 'result': result, 'reused': True})
            save(root / 'live-validation.json', rows)
        else:
            attempt(model, 'smoke', 'smoke-' + model['id'], True)
    previous = None
    for index, model in enumerate(transition_path(MANIFEST['models'])):
        label = 'transition-' + str(index).zfill(2) + '-initial-' + model['id']
        if previous is not None:
            label = 'transition-' + str(index).zfill(2) + '-' + previous['id'] + '-to-' + model['id']
        attempt(model, 'transition', label)
        previous = model
    deterministic = json.loads(args.deterministic.read_text())
    if not deterministic.get('passed') or deterministic.get('harness_hashes') != harness_hashes():
        raise Failure('runtime_error', 'Deterministic validation evidence missing or stale')
    persistence = json.loads(args.persistence.read_text())
    if not persistence.get('passed'):
        raise Failure('runtime_error', 'SSH persistence evidence is missing')
    save(root / 'validation.json', {'passed': True, 'harness_hashes': harness_hashes(),
         'deterministic': str(args.deterministic), 'live': str(root / 'live-validation.json'),
         'persistence': str(args.persistence), 'epoch': time.time()})


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest='command', required=True)
    cell = commands.add_parser('run-cell')
    cell.add_argument('--model', choices=[m['id'] for m in MANIFEST['models']], required=True)
    cell.add_argument('--task', choices=MANIFEST['coding']['tasks'], required=True)
    cell.add_argument('--output', type=Path, required=True)
    suite = commands.add_parser('run')
    suite.add_argument('--output', type=Path, required=True)
    suite.add_argument('--continue-from', type=Path)
    suite.add_argument('--validation', type=Path, required=True)
    live = commands.add_parser('validate-live')
    live.add_argument('--output', type=Path, required=True)
    live.add_argument('--deterministic', type=Path, required=True)
    live.add_argument('--persistence', type=Path, required=True)
    live.add_argument('--reuse-flash-smoke', type=Path)
    args = parser.parse_args()
    def interrupt(signum, frame):
        raise KeyboardInterrupt('Signal ' + str(signum))
    signal.signal(signal.SIGTERM, interrupt)
    signal.signal(signal.SIGINT, interrupt)
    lock = Path.home() / '.local/state/gx10-benchmark/experiment.lock'
    output_existed = args.output.exists()
    try:
        with experiment_lock(lock):
            if args.command == 'run-cell':
                run_cell(model_by_id(args.model), args.task, args.output.resolve())
            elif args.command == 'validate-live':
                validate_live(args)
            else:
                run(args)
    except BaseException as error:
        if not output_existed and args.output.exists() and not isinstance(error, FileExistsError):
            outcome = getattr(error, 'outcome', 'runtime_error')
            if isinstance(error, (KeyboardInterrupt, SystemExit)):
                outcome = 'interrupted'
            save(args.output / 'failure.json', {'outcome': outcome, 'error': repr(error)})
        raise


if __name__ == '__main__':
    main()
