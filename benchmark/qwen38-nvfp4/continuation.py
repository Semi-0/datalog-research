"""Validate immutable completed-cell references before scheduling missing cells."""
import copy
from pathlib import Path

from lifecycle import Failure
from repetition_recovery import read_json, cleanup_verified, selected_attempt, can_continue_model_stop


def comparable_manifest(manifest):
    value = copy.deepcopy(manifest)
    value.pop('continuation_from', None)
    return value


def retained_cell(cell, task, expected):
    if not cell.exists():
        return None
    for name in ['result.json', 'checkpoint.json', 'manifest.json', 'cell.json']:
        if not (cell / name).is_file():
            raise Failure('transition_failed', 'Incomplete prior cell cannot be silently retried: ' + str(cell))
    if read_json(cell / 'checkpoint.json').get('stage') != 'complete':
        raise Failure('transition_failed', 'Prior checkpoint is incomplete: ' + str(cell))
    if comparable_manifest(read_json(cell / 'manifest.json')) != comparable_manifest(expected):
        raise Failure('setup_declined', 'Prior cell configuration differs: ' + str(cell))
    if read_json(cell / 'cell.json').get('task') != task:
        raise Failure('setup_declined', 'Prior task identity differs')
    result = read_json(cell / 'result.json')
    if not cleanup_verified(selected_attempt(cell, result)):
        raise Failure('transition_failed', 'Prior cell cleanup is uncertain: ' + str(cell))
    execution = result.get('execution_outcome', result['outcome'])
    if task == 'smoke':
        if execution != 'passed' or result.get('correct') is not True:
            raise Failure('runtime_error', 'Continuation requires a normally passed smoke')
    elif execution not in ['passed', 'task_failed', 'stalled', 'nonterminating', 'budget_exhausted']:
        if not can_continue_model_stop(cell, result):
            raise Failure('transition_failed', 'Prior cell has an unresolved failure: ' + str(cell))
    return {'cell_root': str(cell), 'result_path': str(cell / 'result.json'), 'result': result}


def cell_path(previous, task):
    cell = previous / 'smoke'
    if task != 'smoke':
        cell = previous / 'solo' / task
    index = previous / 'retained-cells.json'
    if index.is_file():
        references = read_json(index)
        if task in references:
            cell = Path(references[task])
            if not cell.is_absolute():
                raise Failure('transition_failed', 'Retained references must be absolute')
    return cell


def collect_retained(previous, expected, unit_state):
    if not previous.is_absolute() or not previous.is_dir():
        raise ValueError('Continuation source must be an existing absolute cohort directory')
    state = unit_state(previous.parent.name + '.service')
    if state.get('SubState') not in ['dead', 'exited', 'failed']:
        raise Failure('transition_failed', 'Previous controller must finish before continuation')
    if comparable_manifest(read_json(previous / 'manifest.json')) != comparable_manifest(expected):
        raise Failure('setup_declined', 'Continuation configuration differs from previous cohort')
    retained = {}
    for task in ['smoke'] + expected['coding']['tasks']:
        entry = retained_cell(cell_path(previous, task), task, expected)
        if entry is not None:
            retained[task] = entry
    if 'smoke' not in retained:
        raise Failure('setup_declined', 'Continuation has no verified completed smoke')
    return retained
