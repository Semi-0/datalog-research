#!/usr/bin/env python3
"""Run all coding tasks for one model; never unlock the full matrix implicitly."""
import argparse
import hashlib
import json
from pathlib import Path
import signal
import time

from lifecycle import Backend, Failure, experiment_lock, save
from inference_metrics import throughput_lines
from suite import MANIFEST, harness_hashes, model_by_id, run_cell, scan_contexts


def report(root, rows, complete=False):
    passed = complete and len(rows) == len(MANIFEST['coding']['tasks']) and all(
        row['result'].get('protocol_pass') for row in rows)
    lines = ['# Single-model Pi pilot', '',
             'Protocol ' + str(MANIFEST['protocol_version']) + '. No overall wall-clock deadline. Meaningful-progress supervision and finite token/request budgets apply; shell and evaluator commands have 120-second limits.',
             'Setup/loading and verified cleanup are measured separately. A timeout does not prove nontermination.', '',
             '| Model | Task | Outcome | Implementation | Workflow | Agent seconds |',
             '| --- | --- | --- | --- | --- | ---: |']
    for row in rows:
        result = row['result']
        correctness = 'unmeasured'
        if result.get('acceptance_counts', {}).get('complete'):
            correctness = str(result.get('correct'))
        lines.append('| ' + ' | '.join(str(value) for value in [row['model'], row['task'],
            result['outcome'], correctness, result.get('workflow_pass'), result.get('coding_wall_s')]) + ' |')
    context_path = root / 'context-results.json'
    if context_path.exists():
        lines += ['', '## Context scan', '',
                  '| Context | Seed | Outcome | Actual input tokens | Min available GiB | Retrieval |',
                  '| ---: | ---: | --- | ---: | ---: | --- |']
        for row in json.loads(context_path.read_text()):
            value = row['result']
            ram = value.get('minimum_available_bytes')
            if ram is not None:
                ram = round(ram / 1024**3, 2)
            lines.append('| ' + ' | '.join(str(item) for item in [row['context'], row['seed'], value['outcome'],
                value.get('observed_prompt_tokens'), ram, value.get('retrieval_correct')]) + ' |')
    selected = root / 'coding-context.json'
    if not selected.exists():
        selected = root / 'selected-context.json'
    if selected.exists():
        lines += ['', 'Selected coding context: **' + str(json.loads(selected.read_text())['context']) + ' tokens**.']
    lines += ['', 'All three full passes: **' + str(passed) + '**.',
              'The queue admits the other configurations only after all three Flash cells have complete agent endings and independent evaluation; genuine task failures remain data. Earlier runs remain separate.', '']
    lines += throughput_lines(rows)
    (root / 'REPORT.md').write_text('\n'.join(lines))
    save(root / 'pilot.json', {'complete': complete, 'all_tasks_passed': passed, 'rows': rows})
    return passed


def successful_contexts(rows):
    successful = []
    for context in sorted({row['context'] for row in rows}):
        group = [row for row in rows if row['context'] == context]
        if {row['seed'] for row in group} != set(MANIFEST['seeds']):
            continue
        if all(row['result'].get('protocol_pass') and row['result'].get('timing_valid') for row in group):
            successful.append(context)
    return successful


def select_context(rows):
    successful = successful_contexts(rows)
    if not successful:
        raise Failure('setup_declined', 'No context passed all seeds with calibrated retrieval and clean memory/timing')
    return max(successful)


def smoke_context(root, model, contexts, selected, allow_fallback=False):
    candidates = [selected]
    if allow_fallback:
        candidates = sorted(successful_contexts(contexts), reverse=True)
    attempts = []
    for context in candidates:
        directory = root / 'smoke'
        if attempts:
            directory = root / ('smoke-' + str(context))
        save(root / 'status.json', {'stage': 'smoke', 'model': model['id'], 'context': context})
        result = run_cell(model, 'smoke', directory, context=context, smoke=True)
        attempts.append({'context': context, 'path': str(directory), 'result': result})
        save(root / 'smoke-contexts.json', attempts)
        save(root / 'smoke.json', result)
        if result.get('protocol_pass'):
            save(root / 'coding-context.json', {'context': context, 'retrieval_context': selected,
                 'policy': 'Largest tested all-seed context also passing the full Pi/plugin smoke',
                 'smoke': str(directory)})
            return context
        if result['outcome'] not in ['resource_stopped', 'setup_declined']:
            raise Failure('runtime_error', 'Pi smoke must fully pass before the three-task pilot')
    raise Failure('setup_declined', 'No validated context could run the full Pi/plugin smoke within reserve')


def recover_contexts(source, root):
    """Caller has verified clean recovery; retain completed probe evidence only."""
    old = json.loads((source / 'manifest.json').read_text())
    for key in ['models', 'sampling', 'memory', 'context_scan', 'fallback_contexts', 'context',
                'seeds', 'boundary_resolution_tokens', 'native_context_ceiling', 'runtime_phase']:
        if old[key] != MANIFEST[key]:
            raise Failure('setup_declined', 'Context continuation policy differs: ' + key)
    rows = []
    hashes = harness_hashes()
    references = {}
    index = source / 'context-results.json'
    if index.exists():
        for row in json.loads(index.read_text()):
            directory = Path(row['path'])
            if not directory.is_absolute():
                directory = source / directory
            directory = directory.resolve()
            references.setdefault(directory, []).append(row)
    directories = set(references)
    directories.update(record.parent.resolve() for record in source.glob('context/*/cell.json'))
    identities = set()
    for directory in sorted(directories):
        cell = json.loads((directory / 'cell.json').read_text())
        identity = (cell['model']['id'], cell['context'], cell['seed'])
        for row in references.get(directory, []):
            if (row['model'], row['context'], row['seed']) != identity:
                raise Failure('setup_declined', 'Context continuation reference differs from cell: ' + str(directory))
            if row.get('recovered_result_sha256'):
                recorded = Path(row['recovered_result_path'])
                if hashlib.sha256(recorded.read_bytes()).hexdigest() != row['recovered_result_sha256']:
                    raise Failure('setup_declined', 'Recovered context evidence changed: ' + str(recorded))
        previous = json.loads((directory / 'harness-hashes.json').read_text())
        for name in ['context_probe.py', 'model_setup.py', 'lifecycle.py']:
            key = 'benchmark/gx10-context/' + name
            if previous[key] != hashes[key]:
                raise Failure('setup_declined', 'Context implementation differs: ' + key)
        path = directory / 'result.json'
        if not path.exists():
            continue
        result = json.loads(path.read_text())
        if result['outcome'] == 'interrupted':
            path = directory / 'attempt/result.json'
            if not path.exists():
                continue
            result = json.loads(path.read_text())
        if result['outcome'] in ['interrupted', 'transition_failed']:
            continue
        if identity in identities:
            raise Failure('setup_declined', 'Duplicate completed context attempts require adjudication: ' + str(identity))
        identities.add(identity)
        rows.append({'phase': 'context', 'model': cell['model']['id'], 'task': 'retrieval',
            'context': cell['context'], 'seed': cell['seed'], 'path': str(directory), 'result': result,
            'recovered_result_path': str(path), 'recovered_result_sha256': hashlib.sha256(path.read_bytes()).hexdigest()})
    save(root / 'context-recovery.json', {'source': str(source), 'cleanup_verified': True, 'rows': rows})
    return rows


def coding_tasks(root, model, context):
    rows = []
    for task in MANIFEST['coding']['tasks']:
        save(root / 'status.json', {'stage': 'coding', 'model': model['id'], 'task': task, 'epoch': time.time()})
        result = run_cell(model, task, root / 'cells' / task, context=context)
        rows.append({'phase': 'coding', 'model': model['id'], 'task': task, 'context': context,
                     'path': str(root / 'cells' / task), 'result': result})
        save(root / 'results.json', rows)
        report(root, rows)
    return rows


def run(args):
    root = args.output.resolve()
    root.mkdir(parents=True, exist_ok=False)
    proof = json.loads(args.deterministic.read_text())
    if not proof.get('passed') or proof['harness_hashes'] != harness_hashes():
        raise Failure('setup_declined', 'Deterministic validation is missing or stale')
    save(root / 'manifest.json', MANIFEST)
    save(root / 'purpose.json', {'purpose': 'Flash-first measured coding cells; protocol-6 cells are retained in the same final matrix'})
    save(root / 'validation.json', {'deterministic': str(args.deterministic), 'harness_hashes': harness_hashes()})
    rows = []
    model = model_by_id(args.model)
    contexts = []
    if getattr(args, 'coding_only', False):
        context = MANIFEST['coding']['context']
        with experiment_lock(Path.home() / '.local/state/gx10-benchmark/experiment.lock'):
            smoke = run_cell(model, 'smoke', root / 'smoke', context=context, smoke=True)
            if not smoke.get('protocol_pass'):
                raise Failure(smoke['outcome'], 'Requested 128K full-agent smoke did not pass; no fallback or coding admission')
            save(root / 'coding-context.json', {'context': context, 'policy': 'Explicit manifest context; no downgrade',
                 'smoke': str(root / 'smoke')})
            rows = coding_tasks(root, model, context)
        passed = report(root, rows, complete=True)
        save(root / 'status.json', {'stage': 'complete', 'all_tasks_passed': passed, 'epoch': time.time()})
        return
    with experiment_lock(Path.home() / '.local/state/gx10-benchmark/experiment.lock'):
        if args.continue_context_from is not None:
            Backend(root, MANIFEST).cleanup()
            contexts = recover_contexts(args.continue_context_from.resolve(), root)
            save(root / 'context-results.json', contexts)
        def schedule(kind, model, task, context, seed, tokenizer):
            prior = next((row for row in contexts if row['model'] == model['id'] and
                          row['context'] == context and row['seed'] == seed), None)
            if prior is not None:
                return prior['result']
            save(root / 'status.json', {'stage': 'context', 'model': args.model, 'context': context, 'seed': seed})
            cell = root / 'context' / (str(context) + '-' + str(seed))
            result = run_cell(model, task, cell, context, seed, tokenizer)
            contexts.append({'phase': kind, 'model': args.model, 'task': task, 'context': context,
                             'seed': seed, 'path': str(cell), 'result': result})
            save(root / 'context-results.json', contexts)
            report(root, rows)
            return result
        scan_contexts(root, [model], schedule)
        selected = select_context(contexts)
        save(root / 'selected-context.json', {'context': selected,
             'policy': 'Largest all-seed retrieval success with calibrated occupancy, clean timing and preserved RAM reserve',
             'resolution_tokens': MANIFEST['boundary_resolution_tokens'], 'native_ceiling': MANIFEST['native_context_ceiling']})
        if getattr(args, 'context_only', False):
            report(root, rows)
            save(root / 'status.json', {'stage': 'context_complete', 'model': args.model,
                 'context': selected, 'coding_started': False, 'epoch': time.time()})
            return
        selected = smoke_context(root, model, contexts, selected, getattr(args, 'smoke_context_fallback', False))
        rows = coding_tasks(root, model, selected)
    passed = report(root, rows, complete=True)
    save(root / 'status.json', {'stage': 'complete', 'all_tasks_passed': passed, 'epoch': time.time()})


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--model', default=MANIFEST['coding']['pilot_model'], choices=[m['id'] for m in MANIFEST['models']])
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--deterministic', type=Path, required=True)
    parser.add_argument('--continue-context-from', type=Path)
    parser.add_argument('--smoke-context-fallback', action='store_true',
                        help='On a resource-only smoke failure, try lower contexts that passed every seed')
    parser.add_argument('--coding-only', action='store_true',
                        help='Run the explicit manifest context smoke and three coding cells, without a new context scan')
    parser.add_argument('--context-only', action='store_true',
                        help='Finish context validation and stop before Pi smoke or coding')
    args = parser.parse_args()
    existed = args.output.exists()
    def interrupt(signum, frame):
        raise KeyboardInterrupt('Signal ' + str(signum))
    signal.signal(signal.SIGTERM, interrupt)
    signal.signal(signal.SIGINT, interrupt)
    try:
        run(args)
    except BaseException as error:
        if not existed and args.output.exists():
            save(args.output / 'failure.json', {'outcome': getattr(error, 'outcome', 'runtime_error'), 'error': repr(error)})
        raise
