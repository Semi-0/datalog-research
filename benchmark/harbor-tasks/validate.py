#!/usr/bin/env python3
"""Model-free Harbor oracle/nop and scaffold-tamper validation."""
import argparse
import fcntl
import json
import os
from pathlib import Path
import shutil
import subprocess
import time

from build import EXPECTED, OWNER, save, sha

HARBOR = Path.home() / '.local/opt/harbor-eval/bin/harbor'


def containers():
    return subprocess.check_output(['docker', 'ps', '-aq', '--filter', 'label=gx10.benchmark.owner=' + OWNER],
                                   text=True, timeout=20).split()


def cleanup():
    owned = containers()
    if owned:
        subprocess.run(['docker', 'rm', '-f', *owned], check=True, timeout=60)
    if containers():
        raise RuntimeError('Migration container cleanup uncertain; further trials blocked')


def verify_packages(dataset, manifest):
    for name, expected in manifest['package_hashes'].items():
        path = dataset / name
        if path.is_symlink() or not path.is_file() or sha(path) != expected:
            raise RuntimeError('Generated task package drift: ' + name)


def trial(root, task, agent, label, expected_reward, expected_scope=True):
    save(root / 'status.json', {'stage': label, 'models_loaded': False, 'epoch': time.time()})
    command = [str(HARBOR), 'run', '-p', str(task), '-a', agent, '-n', '1', '-k', '1', '-r', '0',
               '-o', str(root / 'jobs'), '--job-name', label]
    environment = dict(os.environ, HARBOR_TELEMETRY='0', DO_NOT_TRACK='1')
    try:
        with (root / (label + '.log')).open('w') as output:
            subprocess.run(command, env=environment, stdout=output, stderr=subprocess.STDOUT,
                           check=True, timeout=2400)
        candidates = []
        for path in (root / 'jobs' / label).glob('*/result.json'):
            result = json.loads(path.read_text())
            if 'trial_name' in result:
                candidates.append((path, result))
        if len(candidates) != 1:
            raise RuntimeError('Expected one durable Harbor trial for ' + label)
        path, result = candidates[0]
        if result.get('exception_info') is not None:
            raise RuntimeError('Harbor execution error: ' + json.dumps(result['exception_info']))
        report = json.loads((path.parent / 'verifier/migration-result.json').read_text())
        reward = (result.get('verifier_result') or {}).get('rewards', {}).get('reward')
        if reward != expected_reward or report['reward'] != expected_reward or not report['verification_valid']:
            raise RuntimeError('Unexpected independent verification result for ' + label + ': ' + json.dumps(report))
        if report['scope_valid'] != expected_scope:
            raise RuntimeError('Unexpected scaffold/ownership result for ' + label)
        if expected_reward == 1 and expected_scope and not report['workflow_pass']:
            raise RuntimeError('Reference solution did not pass its own finite assertions')
        if expected_reward == 1 and report['acceptance']['counts']['assertions'] != EXPECTED[report['task']]:
            raise RuntimeError('Original assertion total was not preserved')
        return {'label': label, 'agent': agent, 'harbor_trial': str(path), 'reward': reward, 'verification': report}
    finally:
        cleanup()


def oracle_fixture(dataset, output, name, command):
    task = output / name
    shutil.copytree(dataset / 'distinct', task)
    with (task / 'solution/solve.sh').open('a') as stream:
        stream.write(command)
    return task


def validate(dataset, output):
    manifest = json.loads((dataset / 'migration.json').read_text())
    verify_packages(dataset, manifest)
    output.mkdir(parents=True, exist_ok=False)
    lock_path = Path.home() / '.local/state/gx10-benchmark/experiment.lock'
    lock_path.parent.mkdir(parents=True, exist_ok=True)
    rows = []
    try:
        with lock_path.open('a+') as lock:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            if containers():
                raise RuntimeError('Earlier migration containers exist; reconcile before admission')
            for identity in EXPECTED:
                for agent, reward in [('oracle', 1), ('nop', 0)]:
                    rows.append(trial(output, dataset / identity, agent, agent + '-' + identity, reward))
                    save(output / 'trials.json', rows)
            scope_task = oracle_fixture(dataset, output, 'scope-fixture',
                "printf '\\n; forbidden scaffold edit\\n' >> /app/deps.edn\n")
            rows.append(trial(output, scope_task, 'oracle', 'oracle-scope-violation', 1, expected_scope=False))
            save(output / 'trials.json', rows)
            score_probe = '''cat >> /app/benchmark/stable_distinct.clj <<'CLOJURE'
(let [blocked? (try (spit "/logs/verifier/reward.txt" "forged") false
                    (catch java.io.IOException _ true))]
  (when-not blocked? (throw (ex-info "Candidate can overwrite verifier score" {}))))
CLOJURE
'''
            score_task = oracle_fixture(dataset, output, 'score-fixture', score_probe)
            rows.append(trial(output, score_task, 'oracle', 'oracle-score-protection', 1))
            save(output / 'trials.json', rows)
            verify_packages(dataset, manifest)
            if containers():
                raise RuntimeError('Owned containers remain at completion')
        result = {'passed': True, 'scope': 'Harbor task migration validation; no model assessment',
                  'models_loaded': False, 'model_requests': 0, 'trials': rows,
                  'oracle_assertions': sum(row['verification']['acceptance']['counts']['assertions']
                      for row in rows if row['label'] in ['oracle-' + name for name in EXPECTED]),
                  'task_package_hashes_unchanged': True, 'cleanup_verified': True,
                  'harbor_version': subprocess.check_output([str(HARBOR), '--version'], text=True, timeout=10).strip()}
        save(output / 'result.json', result)
        save(output / 'status.json', {'stage': 'finished', 'passed': True, 'epoch': time.time()})
        return result
    except BaseException as error:
        save(output / 'status.json', {'stage': 'stopped', 'error': repr(error), 'epoch': time.time()})
        raise


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--tasks', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    result = validate(args.tasks.resolve(), args.output.resolve())
    print(json.dumps({'passed': result['passed'], 'trials': len(result['trials']),
                      'oracle_assertions': result['oracle_assertions']}))
