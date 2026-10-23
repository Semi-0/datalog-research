#!/usr/bin/env python3
"""Three enhanced BF16 cells using the existing independent coding lifecycle."""
import argparse
from pathlib import Path
import signal
import sys

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent / 'gx10-context'))
import suite
from lifecycle import Failure, experiment_lock, save
from os import environ
from continuation import completed_cell


def execute(output, model_id='ornith-bf16'):
    if model_id not in ['ornith-bf16', 'ornith-nvfp4']:
        raise Failure('setup_declined', 'Unsupported enhanced solo model')
    output.mkdir(parents=True, exist_ok=False)
    # This dedicated controller owns its process-local manifest. The frozen file
    # and active benchmark are never rewritten.
    suite.MANIFEST['sampling']['temperature'] = 1.0
    suite.MANIFEST['coding']['context'] = 131072
    suite.MANIFEST['coding']['tool_profile'] = 'enhanced'
    suite.MANIFEST['backend_runtime']['ollama_parallel_requests'] = 1
    suite.MANIFEST['backend_runtime']['ollama_workspace_allowance_bytes'] = 6 * 1024 ** 3
    rows = []
    save(output / 'manifest.json', suite.MANIFEST)
    with experiment_lock(Path.home() / '.local/state/gx10-benchmark/experiment.lock'):
        for task in suite.MANIFEST['coding']['tasks']:
            result = completed_cell(environ.get('GX10_SOLO_CONTINUE_FROM'), task, model_id)
            if result is None:
                result = suite.run_cell(suite.model_by_id(model_id), task, output / task)
            else:
                retained = output / task
                retained.mkdir()
                save(retained / 'result.json', result)
                save(retained / 'checkpoint.json', {'stage': 'complete', 'continued_from': result['continued_from']})
            rows.append({'task': task, 'result': result})
            save(output / 'results.json', rows)
            if result['outcome'] in ['resource_stopped', 'runtime_error'] or result.get('startup_failure'):
                raise Failure(result['outcome'], 'Resource or infrastructure failure; stop and investigate')
    save(output / 'status.json', {'stage': 'complete', 'cells': len(rows)})
    return rows


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--model', choices=['ornith-bf16', 'ornith-nvfp4'], default='ornith-bf16')
    args = parser.parse_args()
    def interrupt(signum, _frame):
        raise KeyboardInterrupt('Signal ' + str(signum))
    signal.signal(signal.SIGTERM, interrupt)
    signal.signal(signal.SIGINT, interrupt)
    try:
        execute(args.output.resolve(), args.model)
    except BaseException as error:
        if args.output.exists() and not isinstance(error, FileExistsError):
            save(args.output / 'failure.json', {'outcome': getattr(error, 'outcome', 'interrupted'), 'error': repr(error)})
        raise


if __name__ == '__main__':
    main()
