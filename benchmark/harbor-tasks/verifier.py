"""Harbor verifier: unchanged acceptance code in a fresh verifier container."""
import hashlib
import json
import os
from pathlib import Path
import re
import signal
import subprocess
import time

IGNORED = {'.git', '.cpcache'}


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def assertion_counts(text):
    matches = re.findall(r'Ran (\d+) tests containing (\d+) assertions\.\s*(\d+) failures, (\d+) errors\.', text)
    if len(matches) != 1:
        return None
    return dict(zip(['tests', 'assertions', 'failures', 'errors'], map(int, matches[0])))


def scope_violations(project, frozen, allowed):
    violations = []
    for name, expected in frozen.items():
        path = project / name
        if not path.is_file() or path.is_symlink() or digest(path) != expected:
            violations.append('Changed or missing scaffold: ' + name)
    for path in project.rglob('*'):
        relative = path.relative_to(project)
        if relative.parts[0] in IGNORED:
            continue
        name = relative.as_posix()
        if path.is_symlink():
            violations.append('Symlink: ' + name)
        elif path.is_file() and name not in frozen and name not in allowed:
            violations.append('Unassigned file: ' + name)
    return sorted(set(violations))


def finite_run(command, log, cwd):
    started = time.monotonic()
    timed_out = False
    with log.open('w') as output:
        process = subprocess.Popen(command, cwd=cwd, stdout=output, stderr=subprocess.STDOUT,
                                   start_new_session=True)
        try:
            code = process.wait(timeout=120)
        except subprocess.TimeoutExpired:
            timed_out = True
            os.killpg(process.pid, signal.SIGKILL)
            process.wait(timeout=10)
            code = 124
    return {'exit': code, 'timed_out': timed_out, 'wall_s': time.monotonic() - started,
            'counts': assertion_counts(log.read_text())}


def verify(tests, project, logs):
    metadata = json.loads((tests / 'metadata.json').read_text())
    frozen = json.loads((tests / 'scaffold.json').read_text())
    evaluator = tests / metadata['task']['acceptance']
    if digest(evaluator) != metadata['evaluator_sha256']:
        raise RuntimeError('Frozen independent evaluator hash mismatch')
    allowed = [metadata['task']['path'], metadata['task']['test_path']]
    before = scope_violations(project, frozen, allowed)
    if any(path.is_symlink() for path in project.rglob('*') if path.relative_to(project).parts[0] not in IGNORED):
        raise RuntimeError('Symlink candidate is unsupported; independent verification declined')
    java = ['runuser', '-u', 'grader', '--', 'java', '-Xmx512m', '-cp',
            '/opt/clojure/runtime/*:/app:/app/test', 'clojure.main']
    acceptance = finite_run(java + [str(evaluator)], logs / 'acceptance.log', project)
    counts = acceptance['counts']
    valid = (acceptance['exit'] in (0, 1) and counts is not None and
             counts['tests'] > 0 and counts['assertions'] > 0)
    correct = (valid and acceptance['exit'] == 0 and counts['assertions'] == metadata['expected_assertions']
               and counts['failures'] == 0 and counts['errors'] == 0)
    own = None
    if not before:
        own = finite_run(java + ['-m', 'benchmark.harbor-test-runner', metadata['task']['test_namespace']],
                         logs / 'own-tests.log', project)
    after = scope_violations(project, frozen, allowed)
    violations = sorted(set(before + after))
    own_passed = (own is not None and own['exit'] == 0 and own['counts'] is not None and
                  own['counts']['assertions'] > 0 and own['counts']['failures'] == 0 and own['counts']['errors'] == 0)
    return {'task': metadata['task']['id'], 'verification_valid': valid, 'correct': correct,
            'reward': int(correct), 'acceptance': acceptance, 'own_tests': own,
            'scope_valid': not violations, 'scope_violations': violations,
            'workflow_pass': bool(own_passed and not violations),
            'expected_assertions': metadata['expected_assertions'],
            'evaluator_sha256': metadata['evaluator_sha256'],
            'execution_scope': 'fresh Harbor verifier container; no evaluator feedback to agent',
            'timing_valid': None, 'timing_note': 'Migration validation does not establish model timing validity'}


def main():
    logs = Path('/logs/verifier')
    logs.mkdir(parents=True, exist_ok=True)
    # Harbor's shared log mount may be writable by all users initially.
    # The grader inherits stdout handles but cannot alter the score files.
    logs.chmod(0o700)
    (logs / 'reward.txt').write_text('0\n')
    try:
        result = verify(Path('/tests'), Path('/app'), logs)
    except Exception as error:
        result = {'verification_valid': False, 'correct': None, 'reward': 0, 'error': repr(error)}
    (logs / 'migration-result.json').write_text(json.dumps(result, indent=2) + '\n')
    (logs / 'reward.txt').write_text(str(result['reward']) + '\n')
    if not result['verification_valid']:
        return 2
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
