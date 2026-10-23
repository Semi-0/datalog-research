#!/usr/bin/env python3
"""Wait for verified live validation, launch the fresh matrix, and publish compact evidence."""
import argparse
import json
from pathlib import Path
import shutil
import subprocess
import time

REPO = Path(__file__).resolve().parents[1]
PROTOCOL = REPO / 'benchmark/gx10-context'
RESULTS = PROTOCOL / 'results'


def read(path, default=None):
    if path.exists():
        return json.loads(path.read_text())
    return default


def save(path, value):
    temporary = path.with_suffix(path.suffix + '.tmp')
    temporary.write_text(json.dumps(value, indent=2) + '\n')
    temporary.replace(path)


def shown(value, digits=2):
    if value is None:
        return '—'
    if isinstance(value, float):
        return str(round(value, digits))
    return str(value)


def report(root, validation):
    rows = read(root / 'results.json', [])
    status = read(root / 'status.json', {})
    failure = read(root / 'failure.json')
    coding = [row for row in rows if row['phase'] == 'coding']
    contexts = [row for row in rows if row['phase'] == 'context']
    complete = status.get('stage') == 'complete' and len(coding) == 12
    state = 'in progress'
    if complete:
        state = 'complete'
    if failure:
        state = 'stopped with an inspectable failure'
    lines = ['# GX10 protocol v3 report', '', 'Status: **' + state + '**. Completed coding records: ' + str(len(coding)) + '/12.',
        'One fresh attempt per coding cell. Coding has no wall-clock deadline. No speed medians are calculated.',
        'Correctness, workflow and timing are independent. Unmeasured implementation results are excluded from correctness denominators.',
        'Setup declines are excluded from correctness denominators. Interrupted and nonterminating execution times are censored.', '',
        'The previous r1/r2/r3 runs remain exploratory, archived unchanged on GX10. Older transition-dependent skips are inconclusive.',
        'Archive: `~/experiments/gx10-protocol-v3-preservation/exploratory-runs.tar`; SHA-256 `7e1b0c3e4b54f2b61be4c03779177855af08b20fd1c874beeacee34f5a221412`.', '',
        '## Validation', '',
        'The validated harness and deterministic/live evidence are bound by the hashes in `' + str(validation / 'validation.json') + '`.',
        'Deterministic checks cover evaluator fixtures (14/17/42 assertions), all 16 directed pairs, delayed unloading, runner identities,',
        'RAM settlement, process ownership, reserve stops, incomplete endings, nontermination, interruption and continuation.',
        'Live smoke preserves output-token exhaustion as a workflow failure even when required tool primitives and assertions succeeded.',
        'The SSH persistence receipt verifies a service completing after its launch session returned, with Linger=yes;',
        'the authenticated ControlMaster transport remained open. No forced all-login logout is claimed.', '',
        '## Coding', '',
        '| Model | Task | Outcome | Implementation | Workflow | Timing valid | Agent s | Censored | Min available GiB | Own assertions | Evaluator assertions |',
        '| --- | --- | --- | --- | --- | --- | ---: | --- | ---: | ---: | ---: |']
    for row in coding:
        result = row['result']
        acceptance = result.get('acceptance_counts', {})
        implementation = 'unmeasured'
        if acceptance.get('complete'):
            implementation = shown(result.get('correct'))
        available = result.get('minimum_available_bytes')
        if available is not None:
            available /= 1024**3
        values = [row['model'], row['task'], result['outcome'], implementation,
                  result.get('workflow_pass'), result.get('timing_valid'), result.get('coding_wall_s'),
                  result.get('elapsed_censored'), available, result.get('own_assertions'), acceptance.get('assertions')]
        lines.append('| ' + ' | '.join(shown(value) for value in values) + ' |')
    lines += ['', '## Phase durations', '',
              '| Model | Task | Transition s | Setup s | Load s | Preload s | Agent s | Evaluation s | Cleanup s |',
              '| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |']
    for row in coding:
        result = row['result']
        durations = result.get('durations', {})
        values = [row['model'], row['task'], durations.get('transition_before_s'), durations.get('setup_s'),
                  durations.get('load_s'), durations.get('preload_s'), result.get('coding_wall_s'),
                  result.get('verification_wall_s'), durations.get('cleanup_after_s')]
        lines.append('| ' + ' | '.join(shown(value) for value in values) + ' |')
    lines += ['', '## Actual context occupancy and RAM', '',
              '| Model | Context | Seed | Outcome | Input tokens | Target input tokens | Min available GiB | Retrieval | Timing valid |',
              '| --- | ---: | ---: | --- | ---: | ---: | ---: | --- | --- |']
    for row in contexts:
        result = row['result']
        available = result.get('minimum_available_bytes')
        if available is not None:
            available /= 1024**3
        values = [row['model'], row['context'], row['seed'], result['outcome'], result.get('observed_prompt_tokens'),
                  result.get('target_prompt_tokens'), available, result.get('retrieval_correct'), result.get('timing_valid')]
        lines.append('| ' + ' | '.join(shown(value) for value in values) + ' |')
    lines += ['', '## Context evidence bounds', '',
              '| Model | Largest tested all-seed success | Conservative budget declines | Observed reserve stops |',
              '| --- | ---: | --- | --- |']
    for model in read(PROTOCOL / 'config.json')['models']:
        matches = [row for row in contexts if row['model'] == model['id']]
        sizes = sorted({row['context'] for row in matches})
        successful = []
        for size in sizes:
            selected = [row for row in matches if row['context'] == size]
            if len(selected) == 3 and all(row['result']['outcome'] == 'passed' for row in selected):
                successful.append(size)
        largest = None
        if successful:
            largest = max(successful)
        declines = sorted({row['context'] for row in matches if row['result']['outcome'] == 'setup_declined'})
        stopped = sorted({row['context'] for row in matches if row['result']['outcome'] == 'resource_stopped'})
        lines.append('| ' + ' | '.join([model['id'], shown(largest), shown(declines), shown(stopped)]) + ' |')
    lines += ['', 'A conservative decline is not an observed OOM or a proven maximum. A semantic or infrastructure failure is not a resource boundary.',
              'A tested success reports observed input occupancy, not a claim that every advertised token was filled.',
              'No LSP or clj-kondo was added; all configurations use the same compiler/test baseline.', '',
              '## Commands and raw references', '',
              'Protocol and standalone commands: [EXPERIMENT.md](EXPERIMENT.md). Authoritative configuration: [config.json](config.json).',
              'Validated live root on GX10: `' + str(validation) + '`.',
              'Fresh coding/context root on GX10: `' + str(root) + '`.',
              'Raw code, patches, events, lifecycle samples, test outputs and model metadata stay in per-cell directories on GX10.',
              'Compact results and validation receipts are under `results/`. Raw reasoning, fetched pages and process snapshots are excluded.', '']
    if failure:
        lines += ['Failure receipt: `' + str(root / 'failure.json') + '`; ' + shown(failure), '']
    (PROTOCOL / 'PROTOCOL-V3-REPORT.md').write_text('\n'.join(lines))
    save(RESULTS / (root.name + '-summary.json'), {'status': state, 'coding_count': len(coding), 'context_count': len(contexts),
         'raw_root': str(root), 'validation_root': str(validation), 'rows': rows, 'failure': failure})


def launch(unit, command):
    subprocess.run(['bash', str(REPO / 'scripts/run-background.sh'), unit, '--', *command], cwd=REPO, check=True)


def main(args):
    validation = args.validation_root.resolve()
    scored = args.scored_root.resolve()
    RESULTS.mkdir(exist_ok=True)
    if scored.exists():
        raise RuntimeError('Refuse to replace an existing scored root')
    while not (validation / 'validation.json').exists():
        if (validation / 'failure.json').exists():
            raise RuntimeError('Live validation failed; scored matrix was not launched: ' + str(validation))
        time.sleep(5)
    proof = read(validation / 'validation.json')
    if not proof.get('passed'):
        raise RuntimeError('Live validation did not pass')
    for name in ['validation.json', 'live-validation.json']:
        shutil.copy2(validation / name, RESULTS / (validation.name + '-' + name))
    deterministic = Path(proof['deterministic'])
    shutil.copy2(deterministic, RESULTS / (validation.name + '-deterministic.json'))
    for name in ['gx10-context.txt', 'ornith-nvfp4-mtp.txt']:
        shutil.copy2(deterministic.parent / name, RESULTS / (validation.name + '-' + name))
    shutil.copy2(Path(proof['persistence']), RESULTS / (validation.name + '-persistence.json'))
    command = ['/usr/bin/python3', str(PROTOCOL / 'suite.py'), 'run', '--output', str(scored),
               '--validation', str(validation / 'validation.json')]
    if args.continue_from is not None:
        command += ['--continue-from', str(args.continue_from.resolve())]
    launch(args.unit_prefix, command)
    launch(args.unit_prefix + '-recorder', ['/usr/bin/python3', str(PROTOCOL / 'record_live.py'), '--run-root', str(scored)])
    while True:
        report(scored, validation)
        status = read(scored / 'status.json', {})
        if status.get('stage') == 'complete':
            return
        if (scored / 'failure.json').exists():
            raise RuntimeError('Scored matrix stopped; inspect ' + str(scored / 'failure.json'))
        time.sleep(5)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--validation-root', required=True, type=Path)
    parser.add_argument('--scored-root', required=True, type=Path)
    parser.add_argument('--unit-prefix', required=True)
    parser.add_argument('--continue-from', type=Path)
    main(parser.parse_args())
