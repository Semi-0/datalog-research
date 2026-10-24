"""Unscored Harbor oracle + Pi pilot, after the current Events service exits.

Uses the pinned GX10 backend ownership/transition/RAM guard. Docker containers
are separately labelled and removed because the Docker daemon owns their groups.
"""
import argparse
import json
from contextlib import ExitStack
from pathlib import Path
import signal
import subprocess
import sys
import time
import uuid

from pilot_checks import backend_key, verify_connection, verifier_counts, pi_execution

HARBOR = '/home/lain/.local/opt/harbor-eval/bin/harbor'
OWNER = 'harbor-pilot-20261023'


def api_url(base_url):
    normalized = base_url.rstrip('/')
    if normalized.endswith('/v1'):
        return normalized
    return normalized + '/v1'


def owned_containers():
    return subprocess.check_output(['docker', 'ps', '-aq', '--filter', 'label=gx10.benchmark.owner=' + OWNER],
                                   text=True, timeout=20).split()


def remove_containers():
    ids = owned_containers()
    if ids:
        subprocess.run(['docker', 'rm', '-f', *ids], check=True, timeout=60)
    if owned_containers():
        raise RuntimeError('Harbor pilot containers remain after cleanup')


def trial_reward(job):
    trials = []
    for path in job.glob('*/result.json'):
        result = json.loads(path.read_text())
        if 'trial_name' in result:
            trials.append(result)
    if len(trials) != 1:
        raise RuntimeError('Expected exactly one durable Harbor trial')
    result = trials[0]
    if result.get('exception_info') is not None:
        raise RuntimeError('Harbor trial error: ' + json.dumps(result['exception_info']))
    rewards = (result.get('verifier_result') or {}).get('rewards') or {}
    return rewards.get('reward') == 1.0


def wait_events(root, save):
    while True:
        state = subprocess.check_output(['systemctl', '--user', 'show',
                 'gx10-qwen36-events-finish-1.service', '-p', 'SubState', '--value'], text=True).strip()
        if state in ['dead', 'failed', 'exited']:
            return
        if state not in ['running', 'start', 'start-pre', 'start-post', 'stop', 'stop-sigterm', 'stop-sigkill']:
            raise RuntimeError('Unknown Events service state: ' + state)
        save(root / 'status.json', {'stage': 'waiting_for_events', 'epoch': time.time()})
        time.sleep(5)


def execute(harness, previous, task, root):
    sys.path[:0] = [str(harness / 'benchmark/gx10-context'),
                   str(harness / 'benchmark/qwen38-nvfp4')]
    import suite
    from lifecycle import Backend, experiment_lock, save
    from repetition_recovery import cleanup_verified
    root.mkdir(parents=True, exist_ok=False)
    backend = None
    try:
        wait_events(root, save)
        events = Path('/home/lain/experiments/gx10-qwen36-events-finish-1/events')
        result = json.loads((events / 'result.json').read_text())
        selected = Path(result['recovery']['selected_result']).parent
        selected.relative_to(events)
        if not cleanup_verified(selected):
            raise RuntimeError('Events cleanup is uncertain; Harbor model loading blocked')
        save(root / 'events-predecessor.json', {'result_path': str(events / 'result.json'),
             'outcome': result['outcome'], 'cleanup_verified': True})
        subprocess.run(['docker', 'info'], check=True, stdout=subprocess.DEVNULL, timeout=30)
        if owned_containers():
            raise RuntimeError('Earlier pilot containers exist; investigate before reuse')
        plan = json.loads((previous / 'plan.json').read_text())
        suite.MANIFEST = plan['manifest']
        model = plan['cohorts'][0]['model']
        suite.MANIFEST['models'] = [model]
        with experiment_lock(Path.home() / '.local/state/gx10-benchmark/experiment.lock'), ExitStack() as owned:
            owned.callback(remove_containers)
            common = ['/usr/bin/env', 'HARBOR_TELEMETRY=0', HARBOR, 'run', '-p', str(task), '-n', '1', '-k', '1',
                      '-o', str(root / 'jobs')]
            save(root / 'status.json', {'stage': 'oracle', 'epoch': time.time()})
            with (root / 'oracle.log').open('w') as log:
                subprocess.run(common + ['-a', 'oracle', '--job-name', 'oracle', '-r', '0'],
                               check=True, stdout=log, stderr=subprocess.STDOUT, timeout=2100)
            if not trial_reward(root / 'jobs/oracle'):
                raise RuntimeError('Reference solution failed; Pi pilot not admitted')
            oracle_counts = verifier_counts(root / 'jobs/oracle')
            if oracle_counts['failures'] or oracle_counts['errors']:
                raise RuntimeError('Reference assertions failed; Pi pilot not admitted')
            remove_containers()
            (root / 'backend').mkdir()
            backend = Backend(root / 'backend', suite.MANIFEST)
            owned.callback(backend.cleanup)
            backend.cleanup()
            save(root / 'status.json', {'stage': 'loading_model', 'epoch': time.time()})
            with backend.guard('setup'):
                name, base_url, server_unit = suite.start(backend, model, 131072, uuid.uuid4().hex[:12])
            key = backend_key(root / 'backend', name)
            connection = verify_connection(api_url(base_url), name, key)
            save(root / 'authentication.json', connection)
            unit = 'gx10-harbor-pi-' + uuid.uuid4().hex[:12] + '.service'
            receipt = root / 'pi-execution.json'
            backend.attempt_unit = unit
            backend.state['attempt_results'][unit] = {'result_path': str(receipt), 'cell': str(root)}
            backend.persist()
            command = common + ['-a', 'pi', '-m', 'openai/' + name,
                '--job-name', 'pi', '-r', '1', '--retry-include', 'ApiConnectionClosedError',
                '--ak', 'version=1.3.0', '--ak', 'thinking=high', '--ak', 'max_turns=24',
                '--ak', 'model_api=openai-completions',
                '--ae', 'OPENAI_API_KEY=' + key,
                '--ae', 'OPENAI_BASE_URL=' + api_url(base_url)]
            # The pinned backend's API key is a public local-only dummy credential.
            save(root / 'command.json', command)
            save(root / 'status.json', {'stage': 'pi', 'unit': unit, 'epoch': time.time()})
            correct = None
            try:
                with backend.guard('harbor-pi-and-verifier'):
                    backend.launch(unit, command)
                    while True:
                        state = subprocess.check_output(['systemctl', '--user', 'show', unit,
                                 '-p', 'SubState', '--value'], text=True).strip()
                        if state in ['dead', 'failed', 'exited']:
                            break
                        time.sleep(.2)
                correct = trial_reward(root / 'jobs/pi')
                counts = verifier_counts(root / 'jobs/pi')
                correct = correct and counts['failures'] == 0 and counts['errors'] == 0
                execution = pi_execution(root / 'jobs/pi')
                execution['harbor_exit'] = int(subprocess.check_output(
                    ['systemctl', '--user', 'show', unit, '-p', 'ExecMainStatus', '--value'], text=True).strip())
                if execution['harbor_exit'] != 0:
                    execution['outcome'] = 'runtime_error'
                passed = correct and execution['outcome'] == 'completed'
                outcome = execution['outcome']
                if outcome == 'completed':
                    outcome = 'task_failed'
                    if correct:
                        outcome = 'passed'
                save(receipt, {'outcome': outcome, 'correct': correct, 'execution': execution,
                               'verifier': counts})
            except BaseException as error:
                save(receipt, {'outcome': 'runtime_error', 'error': repr(error), 'correct': correct})
                raise
            finally:
                backend.stop(unit)
                remove_containers()
                backend.cleanup()
            save(root / 'result.json', {'unscored': True, 'oracle_passed': True,
                 'pi_passed': passed, 'correct': correct, 'execution': execution,
                 'verifier': counts, 'assertions': counts['assertions'], 'model': model,
                 'cleanup_verified': True})
            if not passed:
                raise RuntimeError('Harbor Pi pilot did not pass; see durable result.json and Pi transcript')
        save(root / 'status.json', {'stage': 'finished', 'epoch': time.time()})
    except BaseException as error:
        save(root / 'status.json', {'stage': 'stopped', 'error': repr(error), 'epoch': time.time()})
        raise


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ['harness', 'previous', 'task', 'output']:
        parser.add_argument('--' + name, type=Path, required=True)
    args = parser.parse_args()
    def interrupt(signum, frame):
        raise KeyboardInterrupt('Signal ' + str(signum))
    signal.signal(signal.SIGINT, interrupt)
    signal.signal(signal.SIGTERM, interrupt)
    execute(args.harness.resolve(), args.previous.resolve(), args.task.resolve(), args.output.resolve())


if __name__ == '__main__':
    main()
