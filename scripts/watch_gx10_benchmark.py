#!/usr/bin/env python3
"""Watch the owned GX10 queue; publish scoped evidence only after verified completion."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shlex
import subprocess
import tarfile
import time

HOST = 'lain@gx10-ac8b.local'
SOCKET = '/tmp/codex-gx10-ac8b-20261020.sock'
EXCLUDED = {'results', '__pycache__', '.git', 'checkout', 'agent', 'gortex', 'tmp', 'web-cache', 'sessions'}
ARTIFACTS = {'result.json', 'cell.json', 'budget.json', 'configuration-verification.json',
             'prompt-cache-verification.json', 'harness-hashes.json', 'checkpoint.json',
             'phase-durations.json', 'selected-context.json', 'coding-context.json',
             'context-results.json', 'pilot.json', 'results.json', 'summary.json',
             'status.json', 'queue.json', 'intent.json', 'manifest.json', 'validation.json',
             'deterministic.json', 'REPORT.md', 'REPORT.txt', 'live-validation.json',
             'acceptance.txt', 'own_tests.txt', 'changes.patch', 'failure.json', 'cleanup-failure.json',
             'backend-runtime-verification.json', 'inference-summary.json', 'inference-requests.jsonl',
             'progress.jsonl', 'supervision-stop.json', 'supervision-warn.json', 'supervision-steer.json',
             'smoke-contexts.json', 'backend-execution.log'}


def write_json(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix('.tmp')
    with temporary.open('w') as stream:
        json.dump(value, stream, indent=2)
        stream.flush()
        os.fsync(stream.fileno())
    temporary.replace(path)


def command(arguments, cwd=None, timeout=90):
    return subprocess.check_output(arguments, cwd=cwd, text=True, stderr=subprocess.STDOUT,
                                   env=dict(os.environ, GIT_TERMINAL_PROMPT='0'), timeout=timeout).strip()


def remote(code):
    return command(['ssh', '-o', 'BatchMode=yes', '-o', 'ConnectTimeout=15', '-S', SOCKET,
                    HOST, 'python3 -c ' + shlex.quote(code)])


def source_paths(repo):
    paths = []
    for folder in ['benchmark/gx10-context', 'benchmark/ornith-nvfp4-mtp']:
        paths.extend(path for path in (repo / folder).rglob('*') if path.is_file() and
                     not EXCLUDED.intersection(path.relative_to(repo).parts))
    paths.extend(repo / name for name in ['benchmark/gx10-migration-manifest.edn',
        'doc/gx10-benchmark-migration-plan.md', 'scripts/migrate_gx10_benchmark.clj',
        'scripts/test_migrate_gx10_benchmark.py', 'scripts/run-background.sh',
        'scripts/run-background.md', 'scripts/gx10_protocol_v3.py',
        'scripts/watch_gx10_benchmark.py', 'scripts/test_watch_gx10_benchmark.py'])
    return sorted(set(path for path in paths if path.is_file()))


def fingerprints(repo):
    return {str(path.relative_to(repo)): hashlib.sha256(path.read_bytes()).hexdigest()
            for path in source_paths(repo)}


def verified_matrix(root, expected):
    if json.loads((root / 'status.json').read_text()).get('stage') != 'complete':
        raise RuntimeError('Queue has not completed')
    stages = json.loads((root / 'queue.json').read_text())
    if [row['name'] for row in stages if row['state'] == 'complete'] != [
            'deterministic', 'flash-pilot', 'live-validation', 'matrix']:
        raise RuntimeError('Queue gates are incomplete or contain ambiguous retries')
    for row in stages:
        if row['state'] != 'complete':
            raise RuntimeError('Unresolved stage: ' + row['name'])
        evidence = root / Path(row['evidence']['path']).relative_to(Path(row['output']).parent)
        if hashlib.sha256(evidence.read_bytes()).hexdigest() != row['evidence']['sha256']:
            raise RuntimeError('Completed evidence hash changed: ' + row['name'])
    matrix = root / Path(stages[-1]['output']).name
    rows = json.loads((matrix / 'summary.json').read_text())['rows']
    coding = [row for row in rows if row['phase'] == 'coding']
    identities = {(row['model'], row['task']) for row in coding}
    if identities != expected or len(coding) != len(expected):
        raise RuntimeError('Expected exactly one explicit outcome for every coding cell')
    for row in rows:
        if row['result']['outcome'] not in ['passed', 'task_failed', 'setup_declined',
                                          'resource_stopped', 'nonterminating', 'timed_out', 'stalled', 'budget_exhausted']:
            raise RuntimeError('Unresolved outcome requires review: ' + str((row['model'], row['task'])))
    if list(root.rglob('cleanup-failure.json')):
        raise RuntimeError('Cleanup failure requires review')
    return matrix


def collect(root, destination):
    archive = root + '-publication.tar.gz'
    code = '\n'.join([
        'from pathlib import Path', 'import os,tarfile,hashlib,json',
        'root=Path(' + repr(root) + ')', 'archive=Path(' + repr(archive) + ')',
        'allowed=' + repr(ARTIFACTS), 'excluded=' + repr(EXCLUDED),
        'with tarfile.open(archive,"w:gz") as bundle:',
        ' for base,dirs,files in os.walk(root):',
        '  dirs[:]=[name for name in dirs if name not in excluded]',
        '  for name in files:',
        '   path=Path(base)/name',
        '   if name in allowed and not path.is_symlink():',
        '    bundle.add(path,arcname=str(path.relative_to(root)))',
        'print(hashlib.sha256(archive.read_bytes()).hexdigest())'])
    digest = remote(code)
    destination.mkdir(parents=True, exist_ok=False)
    local = destination / 'publication.tar.gz'
    command(['scp', '-o', 'BatchMode=yes', '-o', 'ConnectTimeout=15', '-o', 'ControlPath=' + SOCKET,
             HOST + ':' + archive, str(local)], timeout=300)
    if hashlib.sha256(local.read_bytes()).hexdigest() != digest:
        raise RuntimeError('Downloaded evidence archive hash mismatch')
    with tarfile.open(local) as bundle:
        bundle.extractall(destination, filter='data')
    local.unlink()
    write_json(destination / 'download.json', {'source': root, 'archive': archive, 'sha256': digest})


def publish(repo, destination, original, expected_head):
    if fingerprints(repo) != original or command(['git', 'rev-parse', 'HEAD'], cwd=repo) != expected_head:
        raise RuntimeError('Local source or HEAD changed; refusing unattended publication')
    if command(['git', 'diff', '--cached', '--name-only'], cwd=repo):
        raise RuntimeError('Existing staged changes require review')
    paths = list(original) + [str(path.relative_to(repo)) for path in destination.rglob('*') if path.is_file()]
    command(['git', 'add', '--', *paths], cwd=repo)
    staged = command(['git', 'diff', '--cached', '--name-only'], cwd=repo).splitlines()
    if set(staged) != set(paths):
        raise RuntimeError('Staged paths differ from the explicit benchmark publication scope')
    command(['git', 'diff', '--cached', '--check'], cwd=repo)
    command(['git', 'commit', '-m', 'Migrate GX10 benchmark and record verified protocol 5 runs'], cwd=repo)
    branch = command(['git', 'branch', '--show-current'], cwd=repo)
    if not branch:
        raise RuntimeError('Detached HEAD; refusing push')
    command(['git', 'push', 'origin', branch], cwd=repo, timeout=300)
    return command(['git', 'rev-parse', 'HEAD'], cwd=repo)


def run(args):
    repo = args.repo.resolve()
    if not args.run_root.startswith('/home/lain/experiments/gx10-') or any(c in args.run_root for c in ['\n', '\0']):
        raise ValueError('Expected an owned GX10 experiment path')
    manifest = json.loads((repo / 'benchmark/gx10-context/config.json').read_text())
    expected = {(model['id'], task) for model in manifest['models'] for task in manifest['coding']['tasks']}
    original = fingerprints(repo)
    head = command(['git', 'rev-parse', 'HEAD'], cwd=repo)
    if command(['git', 'diff', '--cached', '--name-only'], cwd=repo):
        raise RuntimeError('Refuse unattended publication with pre-existing staged changes')
    initial = {'state': 'monitoring', 'run_root': args.run_root,
               'source_hashes': original, 'head': head, 'publish_authorized': args.publish}
    write_json(args.receipt.with_suffix('.source.json'), initial)
    write_json(args.receipt, initial)
    while True:
        try:
            code = ('from pathlib import Path; import json; p=Path(' + repr(args.run_root) + '); '
                    'print(json.dumps({n:json.loads((p/n).read_text()) if (p/n).exists() else None '
                    'for n in ["status.json","failure.json"]}))')
            snapshot = json.loads(remote(code))
        except (subprocess.SubprocessError, OSError, ValueError) as error:
            write_json(args.receipt, {'state': 'connection_unavailable', 'error': repr(error), 'epoch': time.time()})
            time.sleep(45)
            continue
        print(json.dumps({'epoch': time.time(), **snapshot}), flush=True)
        if snapshot['failure.json'] is not None:
            destination = repo / 'benchmark/gx10-context/results' / Path(args.run_root).name
            collect(args.run_root, destination)
            write_json(args.receipt, {'state': 'blocked', 'run_root': args.run_root,
                                     'evidence': str(destination), **snapshot})
            return
        if (snapshot['status.json'] or {}).get('stage') == 'complete':
            destination = repo / 'benchmark/gx10-context/results' / Path(args.run_root).name
            collect(args.run_root, destination)
            verified_matrix(destination, expected)
            commit = None
            if args.publish:
                commit = publish(repo, destination, original, head)
            write_json(args.receipt, {'state': 'complete', 'evidence': str(destination), 'commit': commit,
                                     'pushed': bool(commit), 'epoch': time.time()})
            return
        write_json(args.receipt, {'state': 'monitoring', 'run_root': args.run_root, **snapshot, 'epoch': time.time()})
        time.sleep(45)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--repo', type=Path, default=Path.cwd())
    parser.add_argument('--run-root', required=True)
    parser.add_argument('--receipt', type=Path, required=True)
    parser.add_argument('--publish', action='store_true')
    args = parser.parse_args()
    if args.receipt.exists() or args.receipt.with_suffix('.source.json').exists():
        parser.error('Choose a new receipt path; existing monitor evidence is preserved')
    try:
        run(args)
    except BaseException as error:
        write_json(args.receipt, {'state': 'failed', 'error': repr(error), 'epoch': time.time()})
        raise
