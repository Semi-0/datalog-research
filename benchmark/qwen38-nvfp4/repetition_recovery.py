"""One fresh full-lifecycle retry after an explicit terminal repetition stop."""
import copy
import json
from pathlib import Path
import time

from lifecycle import Failure, save


def read_json(path):
    return json.loads(path.read_text())


def read_events(path):
    if not path.is_file():
        return []
    try:
        return [json.loads(line) for line in path.read_text().splitlines() if line.strip()]
    except ValueError:
        return []


def validate_policy(policy):
    if policy is None:
        return
    if not isinstance(policy, dict) or set(policy) != {'maximum_model_reloads'}:
        raise ValueError('Unsupported repetition recovery policy')
    count = policy['maximum_model_reloads']
    if type(count) is not int or count != 1:
        raise ValueError('Repetition recovery permits exactly one model reload per cell')


def cleanup_verified(root):
    checkpoint = root / 'checkpoint.json'
    if (root / 'cleanup-failure.json').exists() or not checkpoint.is_file():
        return False
    if read_json(checkpoint).get('stage') != 'complete':
        return False
    events = read_events(root / 'lifecycle.jsonl')
    if not events or events[-1].get('operation') != 'transition_verified':
        return False
    samples = [row.get('details', {}) for row in events if row.get('operation') == 'settlement_sample']
    if not samples:
        return False
    final = samples[-1]
    memory = final.get('memory', {})
    return (final.get('models') == [] and final.get('old_processes') == []
            and final.get('endpoint_alive') is False
            and isinstance(memory.get('available'), int) and isinstance(memory.get('reserve'), int)
            and memory['available'] >= memory['reserve'])


def reload_decision(root, result):
    execution = result.get('execution_outcome', result.get('outcome'))
    if execution != 'runtime_error' or result.get('error') or result.get('assistant_errors'):
        return {'eligible': False, 'reason': 'Not an isolated repetition-related stream failure'}
    if (result.get('resource_stopped') or result.get('timed_out') or result.get('evaluation_timeouts')
            or result.get('swap_in_pages_delta', 0) != 0 or result.get('swap_out_pages_delta', 0) != 0):
        return {'eligible': False, 'reason': 'Resource, timeout or swap failure must be investigated'}
    failures = result.get('stream_failures', [])
    if (not any(row.get('stop_reason') == 'aborted' for row in failures)
            or any(row.get('stop_reason') not in ['aborted', 'length'] for row in failures)):
        return {'eligible': False, 'reason': 'No repetition abort, or an unrelated stream error is present'}
    events = read_events(root / 'attempt/artifacts/anti-repeat.jsonl')
    corrected = any(row.get('kind') == 'anti_repeat' and row.get('type') == 'corrected' for row in events)
    stopped = any(row.get('kind') == 'anti_repeat' and row.get('type') == 'stopped'
                  and row.get('detection', {}).get('kind') == 'reasoning_repeat' for row in events)
    if not corrected or not stopped:
        return {'eligible': False, 'reason': 'No explicit terminal anti-repeat stop after correction'}
    return {'eligible': True, 'reason': 'Terminal reasoning repetition after corrective continuation'}


def selected_attempt(root, result):
    selected = result.get('recovery', {}).get('selected_result')
    if selected is None:
        return root
    path = Path(selected).resolve().parent
    if not path.is_relative_to(root.resolve()):
        raise Failure('transition_failed', 'Selected attempt escapes its logical cell')
    return path


def can_continue_model_stop(root, result):
    if result.get('execution_outcome') != 'runtime_error':
        return False
    recovery = result.get('recovery', {})
    if result.get('correct') is not True and recovery.get('exhausted') is not True:
        return False
    attempt = selected_attempt(root, result)
    if not (attempt / 'result.json').is_file() or not cleanup_verified(attempt):
        return False
    return reload_decision(attempt, read_json(attempt / 'result.json'))['eligible']


def save_recovery(root, records, started, state):
    save(root / 'recovery.json', {'version': 1, 'maximum_model_reloads': 1,
         'reloads_used': max(0, len(records) - 1), 'attempts': records,
         'wall_s': time.monotonic() - started, 'state': state})


def finish(root, selected, records, started):
    result = copy.deepcopy(selected)
    durations = {}
    inference_records = []
    for entry in records:
        raw = read_json(root / entry['directory'] / 'result.json')
        for key, value in raw.get('durations', {}).items():
            durations[key] = durations.get(key, 0) + value
        for row in raw.get('inference', {}).get('records', []):
            inference_records.append(dict(row, recovery_attempt=entry['directory']))
    durations['total_s'] = time.monotonic() - started
    result['durations'] = durations
    result['coding_wall_s'] = sum(row.get('coding_wall_s', 0) for row in records)
    result['inference'] = {'records': inference_records, 'scope': 'all recovery attempts'}
    result['recovery'] = {'version': 1, 'reloads_used': len(records) - 1,
                          'selected_result': records[-1]['result_path'], 'attempts': records,
                          'exhausted': len(records) == 2 and records[-1]['reload_decision']['eligible'],
                          'completion_wall_s': None,
                          'measurement_scope': {'durations': 'all attempts including reload overhead',
                                                'coding_wall_s': 'all attempts', 'inference': 'all attempts',
                                                'other_result_fields': 'selected attempt'}}
    if result.get('execution_outcome', result['outcome']) == 'passed' and result.get('correct') is True:
        result['recovery']['completion_wall_s'] = durations['total_s']
    if len(records) > 1:
        result['timing_valid'] = False
        result['recovery']['timing_note'] = 'Reload latency included; excluded from uninterrupted speed comparisons'
    save(root / 'result.json', result)
    for name in ['manifest.json', 'cell.json', 'harness-hashes.json']:
        source = root / records[-1]['directory'] / name
        if source.is_file():
            save(root / name, read_json(source))
    save_recovery(root, records, started, 'complete')
    save(root / 'checkpoint.json', {'stage': 'complete', 'epoch': time.time()})
    return result


def run_with_reload(run_cell, model, task, root, smoke=False):
    root.mkdir(parents=True, exist_ok=False)
    started = time.monotonic()
    records = []
    try:
        for index in range(2):
            directory = 'attempt-' + str(index + 1)
            cell = root / directory
            save(root / 'checkpoint.json', {'stage': 'running', 'active_attempt': str(cell), 'epoch': time.time()})
            kwargs = {}
            if smoke:
                kwargs['smoke'] = True
            result = run_cell(model, task, cell, **kwargs)
            if not cleanup_verified(cell):
                raise Failure('transition_failed', 'Reload recovery requires verified cleanup: ' + str(cell))
            decision = reload_decision(cell, result)
            records.append({'directory': directory, 'result_path': str(cell / 'result.json'),
                            'outcome': result['outcome'], 'execution_outcome': result.get('execution_outcome'),
                            'correct': result.get('correct'), 'coding_wall_s': result.get('coding_wall_s', 0),
                            'reload_decision': decision, 'cleanup_verified': True})
            save_recovery(root, records, started, 'result_durable')
            if index == 0 and decision['eligible']:
                save(root / 'checkpoint.json', {'stage': 'reload_pending', 'epoch': time.time()})
                save_recovery(root, records, started, 'reload_pending')
            else:
                return finish(root, result, records, started)
        raise RuntimeError('Recovery attempt bound violated')
    except BaseException as error:
        outcome = getattr(error, 'outcome', 'runtime_error')
        if isinstance(error, (KeyboardInterrupt, SystemExit)):
            outcome = 'interrupted'
        if not records or records[-1]['directory'] != directory:
            records.append({'directory': directory, 'result_path': str(cell / 'result.json'),
                            'outcome': outcome, 'error': repr(error), 'cleanup_verified': False})
        save_recovery(root, records, started, 'stopped')
        save(root / 'result.json', {'outcome': outcome, 'correct': None, 'workflow_pass': False,
             'timing_valid': False, 'elapsed_censored': True, 'error': repr(error)})
        save(root / 'checkpoint.json', {'stage': 'failed', 'active_attempt': str(cell), 'epoch': time.time()})
        raise
