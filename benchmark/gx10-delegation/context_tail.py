#!/usr/bin/env python3
"""Resume only NVFP4 context probes after the complete solo/delegation queue."""
import argparse
import hashlib
import json
from pathlib import Path
import signal
import subprocess
import sys
import time
import uuid

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent / 'gx10-context'))
import suite
from lifecycle import Backend, Failure, experiment_lock, save


def predecessor_ready(unit, root):
    result = subprocess.run(['systemctl', '--user', 'show', unit, '-p', 'SubState', '--value'],
                            text=True, capture_output=True, timeout=30)
    if result.returncode != 0 or result.stdout.strip() == 'failed':
        raise Failure('setup_declined', 'Preceding queue failed; inspect before context continuation')
    if result.stdout.strip() not in ['dead', 'exited']:
        return False
    status = root / 'status.json'
    if not status.is_file() or (root / 'failure.json').exists():
        raise Failure('setup_declined', 'Preceding queue has no valid completion receipt')
    receipt = json.loads(status.read_text())
    if receipt.get('stage') != 'finished' or receipt.get('exit') != 0:
        raise Failure('setup_declined', 'Preceding solo/delegation queue is incomplete')
    if not (root / 'pilot/result.json').is_file():
        raise Failure('setup_declined', 'Missing durable orchestration result')
    return True


def scan(output, source):
    rows = []
    with experiment_lock(Path.home() / '.local/state/gx10-benchmark/experiment.lock'):
        Backend(output, suite.MANIFEST).cleanup()
        for row in suite.retained_rows(source, cleanup_verified=True):
            if row['phase'] == 'context' and row['model'] == 'ornith-nvfp4':
                if row['result']['outcome'] not in ['interrupted', 'transition_failed']:
                    retained = dict(row)
                    record = Path(row['path']) / 'result.json'
                    retained['source_result_sha256'] = hashlib.sha256(record.read_bytes()).hexdigest()
                    rows.append(retained)
        save(output / 'context-results.json', rows)
        save(output / 'manifest.json', suite.MANIFEST)
        def schedule(kind, model, task, context, seed, tokenizer):
            prior = next((row for row in rows if row['context'] == context and row['seed'] == seed), None)
            if prior is not None:
                return prior['result']
            cell = output / 'cells' / (str(context) + '-' + str(seed) + '-' + uuid.uuid4().hex[:8])
            save(output / 'status.json', {'stage': 'context', 'model': model['id'], 'context': context, 'seed': seed})
            result = suite.run_cell(model, task, cell, context, seed, tokenizer)
            rows.append({'phase': kind, 'model': model['id'], 'task': task, 'context': context,
                         'seed': seed, 'path': str(cell), 'result': result})
            save(output / 'context-results.json', rows)
            return result
        suite.scan_contexts(output, [suite.model_by_id('ornith-nvfp4')], schedule)
    save(output / 'status.json', {'stage': 'complete', 'model': 'ornith-nvfp4', 'coding_started': False,
                                 'context_records': len(rows), 'epoch': time.time()})


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--after-unit', required=True)
    parser.add_argument('--after-root', type=Path, required=True)
    parser.add_argument('--continue-from', dest='source', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=False)
    def interrupt(signum, _frame):
        raise KeyboardInterrupt('Signal ' + str(signum))
    signal.signal(signal.SIGTERM, interrupt)
    signal.signal(signal.SIGINT, interrupt)
    try:
        while not predecessor_ready(args.after_unit, args.after_root):
            save(output / 'status.json', {'stage': 'waiting_for_solo_and_orchestration', 'epoch': time.time()})
            time.sleep(15)
        scan(output, args.source.resolve())
    except BaseException as error:
        save(output / 'failure.json', {'outcome': getattr(error, 'outcome', 'interrupted'), 'error': repr(error)})
        raise


if __name__ == '__main__':
    main()
