"""Run declared follow-on cohorts after a completed, cleaned predecessor."""
import argparse
import copy
import json
from pathlib import Path
import signal
import subprocess
import time

import run as reporting
from lifecycle import Failure, experiment_lock, save
from repetition_recovery import run_with_reload, validate_policy, can_continue_model_stop
from continuation import collect_retained, cell_path

TASKS = ['distinct', 'order', 'events']
BLOCKING = {'runtime_error', 'resource_stopped', 'transition_failed', 'setup_declined', 'interrupted'}


def load(path):
    return json.loads(path.read_text())


def require_complete(root, tasks):
    for task in tasks:
        cell = cell_path(root, task)
        if not (cell / 'result.json').is_file() or not (cell / 'checkpoint.json').is_file():
            raise Failure('transition_failed', 'Missing durable result/checkpoint: ' + str(cell))
        result = load(cell / 'result.json')
        if load(cell / 'checkpoint.json').get('stage') != 'complete':
            raise Failure('transition_failed', 'Cleanup checkpoint incomplete: ' + str(cell))
        outcome = result.get('execution_outcome', result['outcome'])
        if outcome in BLOCKING and not can_continue_model_stop(cell, result):
            raise Failure('transition_failed', 'Unresolved predecessor outcome: ' + outcome)


def wait_predecessor(root, predecessor, unit, unit_state):
    while True:
        state = unit_state(unit)
        status_path = predecessor / 'status.json'
        status = {}
        if status_path.is_file():
            status = load(status_path)
        save(root / 'status.json', {'stage': 'waiting_for_predecessor', 'unit': state,
                                   'predecessor': status, 'epoch': time.time()})
        if state['SubState'] not in ['running', 'start', 'start-pre', 'start-post', 'stop', 'stop-sigterm', 'stop-sigkill', 'stop-post']:
            if state.get('ExecMainStatus') != '0' or status.get('stage') != 'finished' or status.get('completed_cells') != 3:
                raise Failure('transition_failed', 'Predecessor did not finish all three cells cleanly')
            require_complete(predecessor, TASKS)
            return
        time.sleep(5)


def systemd_state(unit):
    output = subprocess.check_output(['systemctl', '--user', 'show', unit, '-p', 'SubState',
                                      '-p', 'ExecMainStatus', '-p', 'LoadState'], text=True)
    state = dict(line.split('=', 1) for line in output.splitlines() if '=' in line)
    if state.get('LoadState') != 'loaded':
        raise Failure('transition_failed', 'Predecessor unit missing')
    return state


def validate_plan(plan):
    if type(plan.get('prepare_artifacts', False)) is not bool:
        raise ValueError('prepare_artifacts must be boolean')
    validate_policy(plan['manifest'].get('repetition_recovery'))
    if plan['manifest']['coding']['tasks'] != TASKS:
        raise ValueError('Follow-on comparison requires the unchanged three-task order')
    if plan['manifest']['coding']['context'] != 131072 or plan['manifest']['coding']['tool_profile'] != 'enhanced':
        raise ValueError('Expected enhanced 128K harness')
    if plan['manifest']['sampling']['temperature'] != 1.0:
        raise ValueError('Expected temperature 1.0')
    continuation = plan['manifest'].get('continuation_from')
    if continuation is not None:
        if not isinstance(continuation, str) or not Path(continuation).is_absolute() or len(plan['cohorts']) != 1:
            raise ValueError('Continuation requires one cohort and an absolute previous cohort directory')
    labels = [cohort['label'] for cohort in plan['cohorts']]
    if not labels or len(labels) != len(set(labels)):
        raise ValueError('Cohort labels must be unique and nonempty')
    for label in labels:
        if not label or Path(label).name != label or label in ['.', '..']:
            raise ValueError('Cohort labels must be simple directory names')


def run_configured_cell(run_cell, model, task, output, manifest, smoke=False):
    policy = manifest.get('repetition_recovery')
    validate_policy(policy)
    if policy is not None:
        return run_with_reload(run_cell, model, task, output, smoke=smoke)
    if smoke:
        return run_cell(model, task, output, smoke=True)
    return run_cell(model, task, output)


def run_cohort(root, cohort, manifest, historical, current, run_cell):
    cohort_root = root / cohort['label']
    cohort_root.mkdir(exist_ok=False)
    model = cohort['model']
    reporting.suite.MANIFEST = copy.deepcopy(manifest)
    reporting.suite.MANIFEST['models'] = [model]
    save(cohort_root / 'manifest.json', reporting.suite.MANIFEST)
    retained = {}
    previous = manifest.get('continuation_from')
    if previous is not None:
        retained = collect_retained(Path(previous), reporting.suite.MANIFEST, systemd_state)
    save(cohort_root / 'retained-cells.json', {task: row['cell_root'] for task, row in retained.items()})
    if 'smoke' in retained:
        smoke = retained['smoke']['result']
    else:
        smoke = run_configured_cell(run_cell, model, 'smoke', cohort_root / 'smoke', manifest, smoke=True)
    if smoke.get('execution_outcome', smoke['outcome']) != 'passed':
        raise Failure('runtime_error', 'Follow-on smoke failed: ' + cohort['label'])
    for task in TASKS:
        save(root / 'status.json', {'stage': 'coding', 'cohort': cohort['label'], 'task': task, 'epoch': time.time()})
        if task in retained:
            result = retained[task]['result']
            result_path = retained[task]['result_path']
        else:
            result = run_configured_cell(run_cell, model, task, cohort_root / 'solo' / task, manifest)
            result_path = str(cohort_root / 'solo' / task / 'result.json')
        current.append({'cohort': cohort['label'], 'cell': {'model': model, 'task': task},
                        'result_path': result_path, 'result': result})
        reporting.report(root, historical, current)
        if task not in retained:
            require_complete(cohort_root, [task])
    save(cohort_root / 'status.json', {'stage': 'finished', 'completed_cells': 3, 'epoch': time.time()})


def prepare_model_artifacts(root, cohort):
    descriptor = root / 'downloads' / (cohort['label'] + '.json')
    save(descriptor, cohort['model'])
    save(root / 'status.json', {'stage': 'artifact_preparation', 'cohort': cohort['label'], 'epoch': time.time()})
    command = [str(Path.home() / '.local/opt/vllm-ornith-nvfp4/bin/python'),
               str(Path(__file__).resolve().parent / 'download.py'), '--model', str(descriptor)]
    try:
        subprocess.run(command, check=True, timeout=3600)
    except (subprocess.CalledProcessError, subprocess.TimeoutExpired) as error:
        raise Failure('setup_declined', 'Pinned artifact preparation failed; no model will load') from error


def execute(root, plan):
    validate_plan(plan)
    save(root / 'plan.json', plan)
    historical = []
    current = []
    try:
        predecessor = Path(plan['predecessor'])
        wait_predecessor(root, predecessor, plan['predecessor_unit'], systemd_state)
        comparison = load(Path(plan.get('predecessor_report', str(predecessor / 'comparison.json'))))
        historical = comparison['historical'] + comparison['current']
        with experiment_lock(Path.home() / '.local/state/gx10-benchmark/experiment.lock'):
            for cohort in plan['cohorts']:
                if plan.get('prepare_artifacts', False):
                    prepare_model_artifacts(root, cohort)
                save(root / 'status.json', {'stage': 'smoke', 'cohort': cohort['label'], 'epoch': time.time()})
                run_cohort(root, cohort, plan['manifest'], historical, current, reporting.suite.run_cell)
        save(root / 'status.json', {'stage': 'finished', 'completed_cells': len(current), 'epoch': time.time()})
    except BaseException as error:
        save(root / 'failure.json', {'outcome': getattr(error, 'outcome', 'interrupted'), 'error': repr(error)})
        status = {}
        if (root / 'status.json').is_file():
            status = load(root / 'status.json')
        status.update(stage='stopped', error=repr(error), epoch=time.time())
        save(root / 'status.json', status)
        raise
    finally:
        reporting.report(root, historical, current)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--plan', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    plan = load(args.plan)
    validate_plan(plan)
    root = args.output.resolve()
    root.mkdir(parents=True, exist_ok=False)
    def interrupt(signum, frame):
        raise KeyboardInterrupt('Signal ' + str(signum))
    signal.signal(signal.SIGINT, interrupt)
    signal.signal(signal.SIGTERM, interrupt)
    execute(root, plan)


if __name__ == '__main__':
    main()
