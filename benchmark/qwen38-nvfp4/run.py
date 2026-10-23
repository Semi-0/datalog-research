"""Fresh three-task Qwen trial using the existing guarded Pi lifecycle."""
import argparse
import json
from pathlib import Path
import signal
import subprocess
import sys
import time

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent / 'gx10-context'))
import suite
from lifecycle import Failure, experiment_lock, save

BASELINES = [
    Path('/home/lain/experiments/gx10-datalog-benchmark-queue-5/flash-pilot-0f48d36b/cells'),
    Path('/home/lain/experiments/gx10-datalog-benchmark-queue-5/matrix-a534746b/cells'),
    Path('/home/lain/experiments/gx10-delegation-python-queue-7/solo'),
    Path('/home/lain/experiments/gx10-structural-reliability-1/solo'),
]


def collect_baselines():
    records = []
    for base in BASELINES:
        for path in sorted(base.glob('*/result.json')):
            cell = path.parent / 'cell.json'
            if not cell.exists():
                continue
            identity = json.loads(cell.read_text())
            if identity.get('task') not in suite.MANIFEST['coding']['tasks']:
                continue
            records.append({'cohort': str(base), 'result_path': str(path),
                            'cell': identity, 'result': json.loads(path.read_text()),
                            'comparison': 'historical; harness and serving differences; no controlled speed ranking'})
    return records


def rates(result):
    requests = result.get('inference', {}).get('records', [])
    from inference_metrics import completed_rates
    measured = completed_rates(requests)
    bases = sorted({row.get('prefill_basis', 'unknown') for row in requests
                    if row.get('complete') and not row.get('error') and not row.get('metrics_error')})
    measured['prefill_basis'] = 'unknown'
    if bases:
        measured['prefill_basis'] = ', '.join(bases)
    return measured


def report(root, historical, current):
    save(root / 'comparison.json', {'report_version': 2, 'historical': historical, 'current': current,
         'limitations': ['One attempt per cell; no speed medians or causal model ranking.',
                        'Historical harness/tooling and MTP settings differ.',
                        'Only completed error-free requests contribute throughput; vLLM prefill includes cache hits.',
                        'Correctness uses unchanged independent assertions; original outcomes retained.']})
    lines = ['# Qwen 3.8 27B NVFP4 trial', '',
             'Historical comparisons are descriptive: tooling, harness and serving settings differ.',
             'One attempt per cell. No speed medians. Missing measurements remain unknown.',
             'Throughput excludes incomplete/error requests. vLLM prefill includes cached tokens; compare the basis column.', '',
             '| Cohort/model | Task | Outcome | Correct | Workflow | Timing valid | Decode tok/s | Prefill/refill tok/s | Prefill basis |',
             '|---|---|---|---|---|---|---|---|---|']
    for entry in historical + current:
        result = entry['result']; identity = entry['cell']; model = identity.get('model', {})
        if isinstance(model, dict):
            model = model.get('id', model.get('source', '?'))
        measured = rates(result)
        def display(value):
            if value is None:
                return 'unknown'
            if isinstance(value, float):
                return str(round(value, 2))
            return str(value)
        values = [entry['cohort'] + '/' + str(model), identity['task'], result.get('outcome'),
                  result.get('correct'), result.get('workflow_pass'), result.get('timing_valid'),
                  measured['generation_tokens_per_second'], measured['prefill_tokens_per_second'], measured['prefill_basis']]
        lines.append('| ' + ' | '.join(display(v) for v in values) + ' |')
    (root / 'REPORT.md').write_text('\n'.join(lines) + '\n')


def wait_download(root, model):
    started = time.monotonic()
    while True:
        state = subprocess.check_output(['systemctl', '--user', 'show', 'gx10-qwen38-download-1.service',
                                         '-p', 'SubState', '--value'], text=True).strip()
        if state in ['dead', 'exited', 'failed']:
            code = subprocess.check_output(['systemctl', '--user', 'show', 'gx10-qwen38-download-1.service',
                                            '-p', 'ExecMainStatus', '--value'], text=True).strip()
            if state == 'failed' or code != '0' or not Path(model['artifact_manifest']).is_file():
                raise Failure('setup_declined', 'Checkpoint preparation failed; no model will load')
            return
        if time.monotonic() - started > 3600:
            raise Failure('setup_declined', 'Checkpoint preparation exceeded infrastructure wait budget')
        save(root / 'status.json', {'stage': 'waiting_for_verified_download', 'epoch': time.time()})
        time.sleep(5)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    root = args.output.resolve()
    root.mkdir(parents=True, exist_ok=False)
    def interrupt(signum, frame):
        raise KeyboardInterrupt('Signal ' + str(signum))
    signal.signal(signal.SIGINT, interrupt)
    signal.signal(signal.SIGTERM, interrupt)
    model = json.loads((HERE / 'model.json').read_text())
    suite.MANIFEST['models'] = [model]
    suite.MANIFEST['sampling']['temperature'] = 1.0
    suite.MANIFEST['coding']['tool_profile'] = 'enhanced'
    suite.MANIFEST['coding']['context'] = 131072
    save(root / 'manifest.json', suite.MANIFEST)
    historical = collect_baselines()
    current = []
    report(root, historical, current)
    try:
        wait_download(root, model)
        with experiment_lock(Path.home() / '.local/state/gx10-benchmark/experiment.lock'):
            save(root / 'status.json', {'stage': 'smoke', 'epoch': time.time()})
            smoke = suite.run_cell(model, 'smoke', root / 'smoke', smoke=True)
            if smoke.get('execution_outcome', smoke['outcome']) != 'passed':
                raise Failure('runtime_error', 'Live smoke failed; coding cells not admitted')
            for task in suite.MANIFEST['coding']['tasks']:
                save(root / 'status.json', {'stage': 'coding', 'task': task, 'epoch': time.time()})
                result = suite.run_cell(model, task, root / 'solo' / task)
                current.append({'cohort': 'qwen-current-structural', 'cell': {'model': model, 'task': task},
                                'result_path': str(root / 'solo' / task / 'result.json'), 'result': result})
                report(root, historical, current)
                if result.get('execution_outcome', result['outcome']) in ['runtime_error', 'resource_stopped', 'transition_failed', 'setup_declined']:
                    raise Failure('runtime_error', 'Infrastructure or resource gate unresolved; scheduling stopped')
        save(root / 'status.json', {'stage': 'finished', 'completed_cells': len(current), 'epoch': time.time()})
    except BaseException as error:
        report(root, historical, current)
        save(root / 'failure.json', {'outcome': getattr(error, 'outcome', 'interrupted'), 'error': repr(error)})
        save(root / 'status.json', {'stage': 'stopped', 'error': repr(error), 'epoch': time.time()})
        raise


if __name__ == '__main__':
    main()
