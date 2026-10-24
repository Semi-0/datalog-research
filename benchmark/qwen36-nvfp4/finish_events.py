"""Run only the missing Events cell with the existing pinned Python harness.

Prior Distinct/Ordering records are references, never rewritten or reclassified.
This standalone authorization does not claim the old queue passed its error gate.
"""
import argparse
import hashlib
import json
from pathlib import Path
import signal
import subprocess
import sys
import time


def retained_record(path):
    raw = path.read_bytes()
    result = json.loads(raw)
    return {'path': str(path), 'sha256': hashlib.sha256(raw).hexdigest(),
            'correct': result.get('correct'), 'outcome': result.get('outcome'),
            'execution_outcome': result.get('execution_outcome')}


def execute(harness, previous, output):
    sys.path[:0] = [str(harness / 'benchmark/qwen38-nvfp4'),
                   str(harness / 'benchmark/gx10-context')]
    import suite
    from lifecycle import experiment_lock, save
    from repetition_recovery import run_with_reload

    plan = json.loads((previous / 'plan.json').read_text())
    cohort = plan['cohorts'][0]
    model = cohort['model']
    if model['id'] != 'qwen36-35b-a3b-nvfp4':
        raise ValueError('Expected the pinned Qwen3.6 cohort')
    suite.MANIFEST = plan['manifest']
    suite.MANIFEST['models'] = [model]
    retained = {task: retained_record(previous / cohort['label'] / 'solo' / task / 'result.json')
                for task in ['distinct', 'order']}
    output.mkdir(parents=True, exist_ok=False)
    save(output / 'continuation.json', {'policy': 'user_authorized_standalone_missing_cell',
         'previous_queue': str(previous), 'harness': str(harness), 'retained': retained,
         'historical_runtime_errors_preserved': True, 'task': 'events', 'epoch': time.time()})
    try:
        with experiment_lock(Path.home() / '.local/state/gx10-benchmark/experiment.lock'):
            state = subprocess.check_output(['systemctl', '--user', 'show',
                     'gx10-qwen36-priority-1.service', '-p', 'MainPID', '--value'], text=True).strip()
            if state != '0':
                raise RuntimeError('Previous controller is still running or cannot be verified')
            save(output / 'status.json', {'stage': 'events', 'epoch': time.time()})
            result = run_with_reload(suite.run_cell, model, 'events', output / 'events')
            save(output / 'summary.json', {'retained': retained, 'events': result,
                 'all_three_implementations_correct': all(r['correct'] is True for r in retained.values())
                                                     and result.get('correct') is True,
                 'old_ordering_runtime_error_resolved': False})
        save(output / 'status.json', {'stage': 'finished', 'outcome': result['outcome'],
                                     'execution_outcome': result.get('execution_outcome'),
                                     'correct': result.get('correct'), 'epoch': time.time()})
    except BaseException as error:
        save(output / 'status.json', {'stage': 'stopped', 'error': repr(error),
             'outcome': getattr(error, 'outcome', 'runtime_error'), 'epoch': time.time()})
        raise


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--harness', type=Path, required=True)
    parser.add_argument('--previous', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    def interrupt(signum, frame):
        raise KeyboardInterrupt('Signal ' + str(signum))
    signal.signal(signal.SIGTERM, interrupt)
    signal.signal(signal.SIGINT, interrupt)
    execute(args.harness.resolve(), args.previous.resolve(), args.output.resolve())


if __name__ == '__main__':
    main()
