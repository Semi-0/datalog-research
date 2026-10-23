#!/usr/bin/env python3
"""Fresh single-task reliability trial, using the shared guarded cell lifecycle."""
import argparse
from pathlib import Path
import signal
import sys
import time
sys.path.insert(0, str(Path(__file__).resolve().parent.parent / 'gx10-context'))
import suite
from lifecycle import experiment_lock, save


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--task', choices=suite.MANIFEST['coding']['tasks'], default='order')
    parser.add_argument('--model', choices=['ornith-nvfp4'], default='ornith-nvfp4')
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    root = args.output.resolve()
    if root.exists():
        parser.error('Output must be a new directory')
    root.mkdir(parents=True)
    suite.MANIFEST['sampling']['temperature'] = 1.0
    suite.MANIFEST['coding']['context'] = 131072
    suite.MANIFEST['coding']['tool_profile'] = 'enhanced'
    if suite.MANIFEST['coding'].get('structural_editing') is None:
        parser.error('This trial requires the pinned structural-editing profile')
    def interrupt(signum, _frame):
        raise KeyboardInterrupt('Signal ' + str(signum))
    signal.signal(signal.SIGINT, interrupt)
    signal.signal(signal.SIGTERM, interrupt)
    save(root / 'manifest.json', suite.MANIFEST)
    save(root / 'trial.json', {'purpose': 'agent_reliability', 'changed_harness': True,
         'comparison_eligible': False, 'fresh_start': True, 'task': args.task,
         'previous_attempt': '/home/lain/experiments/gx10-delegation-python-queue-7/solo/order',
         'pass_policy': 'independent-correctness-v1', 'epoch': time.time()})
    try:
        with experiment_lock(Path.home() / '.local/state/gx10-benchmark/experiment.lock'):
            result = suite.run_cell(suite.model_by_id(args.model), args.task, root / 'solo' / args.task)
        save(root / 'result.json', result)
        save(root / 'status.json', {'stage': 'finished', 'outcome': result['outcome'], 'epoch': time.time()})
    except BaseException as error:
        save(root / 'failure.json', {'outcome': getattr(error, 'outcome', 'interrupted'), 'error': repr(error)})
        raise


if __name__ == '__main__':
    main()
