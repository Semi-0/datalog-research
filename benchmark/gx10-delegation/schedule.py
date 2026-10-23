#!/usr/bin/env python3
"""Wait for the original benchmark and sandbox admission; then run the separate pilot."""
import argparse
import json
from pathlib import Path
import subprocess
import sys
import time

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent / 'gx10-context'))
from lifecycle import save


def benchmark_state(unit, after_root):
    status = subprocess.run(['systemctl', '--user', 'show', unit, '-p', 'SubState', '--value'],
                            text=True, capture_output=True, timeout=30)
    if status.returncode != 0:
        raise RuntimeError('Cannot establish previous controller state')
    if status.stdout.strip() == 'failed':
        raise RuntimeError('Previous benchmark infrastructure failed (service failed); inspect before delegation')
    if status.stdout.strip() not in ['exited', 'dead']:
        return 'waiting_for_benchmark'
    if (after_root / 'failure.json').exists():
        raise RuntimeError('Previous benchmark infrastructure failed; inspect before delegation')
    p = after_root / 'status.json'
    if not p.exists():
        raise RuntimeError('Previous benchmark has no durable complete checkpoint')
    receipt = json.loads(p.read_text())
    validation_release = (receipt.get('validation_complete') is True and
                          receipt.get('cleanup_verified') is True and
                          receipt.get('stage') == 'finished' and receipt.get('exit') == 0)
    if receipt.get('stage') != 'complete' and not validation_release:
        raise RuntimeError('Previous benchmark has no durable complete checkpoint or verified validation release')
    return 'ready'


def ready_state(unit, after_root):
    state = benchmark_state(unit, after_root)
    if state != 'ready':
        return state
    probe = subprocess.run(['/usr/bin/bwrap', '--ro-bind', '/', '/', '--unshare-user', '--unshare-pid',
                            '--proc', '/proc', '/usr/bin/true'], capture_output=True, timeout=30)
    if probe.returncode != 0:
        return 'waiting_for_sandbox_administrator'
    return 'ready'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--after-unit', required=True)
    parser.add_argument('--after-root', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--worker-model', choices=['ornith-nvfp4', 'ornith-bf16'], default='ornith-bf16')
    parser.add_argument('--solo-first', action='store_true')
    args = parser.parse_args()
    root = args.output.resolve()
    root.mkdir(parents=True, exist_ok=False)
    try:
        if args.solo_first:
            while True:
                stage = benchmark_state(args.after_unit, args.after_root)
                save(root / 'status.json', {'stage': stage, 'next': args.worker_model + '-solo', 'epoch': time.time()})
                if stage == 'ready':
                    break
                time.sleep(15)
            save(root / 'status.json', {'stage': 'solo', 'model': args.worker_model, 'epoch': time.time()})
            with (root / 'solo.log').open('w') as log:
                solo = subprocess.run(['/usr/bin/python3', str(HERE / 'solo.py'), '--model', args.worker_model, '--output', str(root / 'solo')],
                                      stdout=log, stderr=subprocess.STDOUT)
            if solo.returncode != 0:
                raise RuntimeError('Enhanced solo infrastructure failed; inspect solo.log and solo/failure.json')
        while True:
            stage = ready_state(args.after_unit, args.after_root)
            save(root / 'status.json', {'stage': stage, 'epoch': time.time()})
            if stage == 'ready':
                break
            time.sleep(15)
        # The child performs its own real sandbox admission and takes the same host lock.
        with (root / 'pilot.log').open('w') as log:
            result = subprocess.run(['/usr/bin/python3', str(HERE / 'orchestrator.py'), '--worker-model', args.worker_model, '--output', str(root / 'pilot')],
                                    stdout=log, stderr=subprocess.STDOUT)
        save(root / 'status.json', {'stage': 'finished', 'exit': result.returncode, 'epoch': time.time()})
        if result.returncode != 0:
            raise RuntimeError('Delegation pilot failed; inspect pilot.log and pilot/failure.json')
    except BaseException as error:
        save(root / 'failure.json', {'error': repr(error), 'epoch': time.time()})
        raise


if __name__ == '__main__':
    main()
