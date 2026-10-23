#!/usr/bin/env python3
"""Durable gated sequence: context scan, Flash pilot, live gates, fresh matrix."""
import argparse
import hashlib
import json
from pathlib import Path
import signal
import subprocess
import sys
import time
import uuid

from lifecycle import Failure, save, experiment_lock
from suite import HERE, MANIFEST, harness_hashes
from inference_metrics import measurements_complete


def read_json(path):
    return json.loads(path.read_text())


def stage_evidence(name, output):
    filenames = {'deterministic': 'deterministic.json', 'flash-pilot': 'pilot.json',
                 'live-validation': 'validation.json', 'matrix': 'summary.json'}
    path = output / filenames[name]
    value = read_json(path)
    if name in ['deterministic', 'live-validation']:
        if not value.get('passed') or value.get('harness_hashes') != harness_hashes():
            raise Failure('setup_declined', name + ' proof is missing, failed, or stale')
    elif name == 'flash-pilot':
        rows = value.get('rows', [])
        tasks = MANIFEST['coding']['tasks']
        if (not value.get('complete') or len(rows) != len(tasks) or
                {row['task'] for row in rows} != set(tasks) or
                any(row['model'] != MANIFEST['coding']['pilot_model'] for row in rows)):
            raise Failure('runtime_error', 'Flash must finish all three tasks with inspectable coding outcomes')
        for row in rows:
            result = row['result']
            unresolved = [item for item in result.get('stream_failures', []) if not item.get('recovered')]
            if (result.get('outcome') not in ['passed', 'task_failed'] or
                    not result.get('agent_end_observed') or not result.get('agent_final_response') or
                    result.get('pi_exit') != 0 or unresolved or result.get('unresolved_assistant_errors') or
                    not (result.get('acceptance_counts', {}).get('complete') or
                         (result.get('acceptance_completed') and result.get('acceptance_exit', 0) > 0)) or
                    not measurements_complete(result)):
                raise Failure('runtime_error', 'Flash runtime, termination, or evaluation gate unresolved: ' + row['task'])
    elif name == 'matrix':
        if read_json(output / 'status.json').get('stage') != 'complete':
            raise Failure('interrupted', 'Matrix did not finish')
        coding = [row for row in value['rows'] if row['phase'] == 'coding']
        expected = {(model['id'], task) for model in MANIFEST['models'] for task in MANIFEST['coding']['tasks']}
        actual = {(row['model'], row['task']) for row in coding}
        if actual != expected or len(coding) != len(expected):
            raise Failure('runtime_error', 'Matrix must contain exactly one explicit outcome per coding cell')
        if any(row['context'] != MANIFEST['coding']['context'] for row in coding):
            raise Failure('runtime_error', 'Scored coding contexts differ from the authoritative manifest')
    return {'path': str(path), 'sha256': hashlib.sha256(path.read_bytes()).hexdigest()}


def wait_for_context(source, service, root):
    while True:
        status = source / 'status.json'
        if status.exists() and read_json(status).get('stage') == 'context_complete':
            return
        if (source / 'failure.json').exists():
            raise Failure('runtime_error', 'Context scan ended with a failure: ' + str(source))
        state = subprocess.run(['systemctl', '--user', 'show', service, '-p', 'MainPID', '--value'],
                               text=True, capture_output=True, check=True, timeout=15).stdout.strip()
        if state == '0':
            raise Failure('interrupted', 'Context service stopped without a durable completed scan')
        save(root / 'status.json', {'stage': 'waiting_context', 'source': str(source), 'epoch': time.time()})
        time.sleep(5)


def run_stage(root, name, arguments, rows, resume=False):
    previous = [row for row in rows if row['name'] == name]
    if previous and previous[-1]['state'] == 'complete':
        prior = previous[-1]
        proof = stage_evidence(name, Path(prior['output']))
        if proof != prior['evidence']:
            raise Failure('setup_declined', 'Completed stage evidence changed: ' + name)
        return Path(prior['output'])
    output = root / (name + '-' + uuid.uuid4().hex[:8])
    command = [sys.executable, *arguments, '--output', str(output)]
    if resume and name == 'matrix' and previous and Path(previous[-1]['output']).exists():
        if '--continue-from' in command:
            index = command.index('--continue-from')
            del command[index:index + 2]
        command += ['--continue-from', previous[-1]['output']]
    row = {'name': name, 'state': 'running', 'output': str(output), 'command': command,
           'started_epoch': time.time()}
    rows.append(row)
    save(root / 'queue.json', rows)
    save(root / 'status.json', {'stage': name, 'output': str(output), 'epoch': time.time()})
    with (root / (output.name + '.stdout.txt')).open('w') as stdout, \
         (root / (output.name + '.stderr.txt')).open('w') as stderr:
        completed = subprocess.run(command, stdout=stdout, stderr=stderr)
    row['exit'] = completed.returncode
    row['finished_epoch'] = time.time()
    if completed.returncode != 0:
        row['state'] = 'failed'
        save(root / 'queue.json', rows)
        raise Failure('runtime_error', 'Stage failed; no later stage will start: ' + name)
    try:
        row['evidence'] = stage_evidence(name, output)
    except BaseException:
        row['state'] = 'failed'
        save(root / 'queue.json', rows)
        raise
    row['state'] = 'complete'
    save(root / 'queue.json', rows)
    return output


def run(args):
    root = args.output.resolve()
    hashes = harness_hashes()
    rows = []
    if args.resume:
        if read_json(root / 'harness-hashes.json') != hashes:
            raise Failure('setup_declined', 'Queue resume requires its original frozen harness')
        rows = read_json(root / 'queue.json')
        if read_json(root / 'intent.json') != {'context_run': str(args.context_run.resolve()),
                                             'persistence': str(args.persistence.resolve())}:
            raise Failure('setup_declined', 'Queue continuation inputs differ')
    else:
        root.mkdir(parents=True, exist_ok=False)
        save(root / 'harness-hashes.json', hashes)
        save(root / 'manifest.json', MANIFEST)
        save(root / 'queue.json', rows)
        save(root / 'intent.json', {'context_run': str(args.context_run.resolve()),
                                   'persistence': str(args.persistence.resolve())})
    wait_for_context(args.context_run, args.context_service, root)
    deterministic = run_stage(root, 'deterministic', [str(HERE / 'validate.py')], rows, args.resume)
    pilot = run_stage(root, 'flash-pilot', [str(HERE / 'pilot.py'), '--model', MANIFEST['coding']['pilot_model'],
              '--deterministic', str(deterministic / 'deterministic.json'),
              '--coding-only'], rows, args.resume)
    live = run_stage(root, 'live-validation', [str(HERE / 'suite.py'), 'validate-live',
              '--deterministic', str(deterministic / 'deterministic.json'),
              '--persistence', str(args.persistence.resolve()),
              '--reuse-flash-smoke', str(pilot / 'smoke')], rows, args.resume)
    matrix = run_stage(root, 'matrix', [str(HERE / 'suite.py'), 'run',
              '--validation', str(live / 'validation.json'), '--continue-from', str(pilot)], rows, args.resume)
    save(root / 'status.json', {'stage': 'complete', 'matrix': str(matrix),
         'coding_cells': len(MANIFEST['models']) * len(MANIFEST['coding']['tasks']), 'epoch': time.time()})


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--context-run', type=Path, required=True)
    parser.add_argument('--context-service', default='gx10-datalog-flash-context-1.service')
    parser.add_argument('--persistence', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--resume', action='store_true')
    args = parser.parse_args()
    def interrupt(signum, frame):
        raise KeyboardInterrupt('Signal ' + str(signum))
    signal.signal(signal.SIGTERM, interrupt)
    signal.signal(signal.SIGINT, interrupt)
    try:
        with experiment_lock(Path.home() / '.local/state/gx10-benchmark/queue.lock'):
            run(args)
    except BaseException as error:
        outcome = getattr(error, 'outcome', 'runtime_error')
        if isinstance(error, (KeyboardInterrupt, SystemExit)):
            outcome = 'interrupted'
        if args.output.exists():
            save(args.output / 'failure.json', {'outcome': outcome,
                 'error': repr(error), 'epoch': time.time()})
            save(args.output / 'status.json', {'stage': 'failed', 'outcome': outcome,
                 'error': repr(error), 'epoch': time.time()})
        raise
