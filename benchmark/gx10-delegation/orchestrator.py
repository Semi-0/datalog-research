#!/usr/bin/env python3
"""Python scheduling for the existing guarded GX10 delegation harness."""
import argparse
import json
from pathlib import Path
import time

import run as pilot
from lifecycle import Failure, experiment_lock, save


# A single loaded backend serves all workers. Context and sampling come from
# the existing harness configuration and verified Host.enter operation.
STEPS = (
    ('planning-load', 'model/enter', 'flash'),
    ('planning', 'pi/plan', 'event-batch'),
    ('planning-cleanup', 'model/exit', 'flash'),
    ('planning-unloaded', 'model/verify-unloaded', 'flash'),
    ('workers-load', 'model/enter', 'ornith-nvfp4'),
    ('two-worker-smoke', 'pi/smoke', 2),
    ('four-workers', 'pi/parallel', [4, ['normalize', 'project', 'order', 'batch']]),
    ('handoffs', 'patch/collect', 'workers'),
    ('workers-cleanup', 'model/exit', 'ornith-nvfp4'),
    ('workers-unloaded', 'model/verify-unloaded', 'ornith-nvfp4'),
    ('integration-load', 'model/enter', 'flash'),
    ('integration', 'pi/integrate', 'handoffs'),
    ('evaluation', 'evaluate', 'integration'),
    ('review', 'pi/review', 'evaluation'),
    ('review-cleanup', 'model/exit', 'flash'),
    ('complete', 'workflow/complete', None),
)


def step_request(identity, operation, arguments, worker_model):
    if arguments == 'ornith-nvfp4':
        arguments = worker_model
    return {'path': [identity], 'operation': operation, 'arguments': arguments}


def plan(worker_model='ornith-nvfp4'):
    if worker_model not in ['ornith-nvfp4', 'ornith-bf16']:
        raise ValueError('Unsupported worker model')
    return {'controller': 'python', 'context_tokens_per_agent': 131072,
            'worker_count': 4, 'worker_model': worker_model, 'smoke_workers': 2, 'nested_delegation': False,
            'coding_wall_limit_seconds': None, 'command_timeout_seconds': 120,
            'steps': [step_request(identity, operation, arguments, worker_model)
                      for identity, operation, arguments in STEPS]}


def perform(host, request):
    identity = request['path'][0]
    save(host.output / 'status.json', {'state': 'running', 'step': identity, 'epoch': time.time()})
    started = time.monotonic()
    try:
        detail = host.dispatch(request)
    except BaseException as error:
        outcome = getattr(error, 'outcome', 'runtime_error')
        if isinstance(error, (KeyboardInterrupt, SystemExit)):
            outcome = 'interrupted'
        save(host.output / 'steps' / (identity + '.json'),
             {'request': request, 'outcome': outcome, 'error': repr(error),
              'wall_s': time.monotonic() - started})
        raise
    save(host.output / 'steps' / (identity + '.json'),
         {'request': request, 'outcome': 'passed', 'detail': detail,
          'wall_s': time.monotonic() - started})
    return detail


def execute(output, host_factory=None, worker_model='ornith-nvfp4'):
    # Lazy import avoids a circular import through run.py's compatibility CLI.
    if host_factory is None:
        from lain_host import Host
        host_factory = Host
    output = Path(output).resolve()
    output.mkdir(parents=True, exist_ok=False)
    save(output / 'orchestrator-plan.json', plan(worker_model))
    host = None
    try:
        with experiment_lock(Path.home() / '.local/state/gx10-benchmark/experiment.lock'):
            try:
                host = host_factory(output)
                host.worker_model = worker_model
                admission = None
                for request in plan(worker_model)['steps']:
                    if request['operation'] in ['pi/smoke', 'pi/parallel']:
                        if admission != [True, True, True, True] or not all(type(v) is bool for v in admission):
                            raise Failure('setup_declined', 'Worker admission lacks verified cleanup, memory, reserve or sandbox')
                    detail = perform(host, request)
                    if request['operation'] == 'model/enter':
                        admission = detail.get('admission')
            finally:
                if host is not None and host.backend is not None:
                    try:
                        perform(host, {'path': ['emergency-cleanup'], 'operation': 'model/exit', 'arguments': host.model})
                    except BaseException as error:
                        save(output / 'cleanup-failure.json', {'outcome': 'transition_failed', 'error': repr(error)})
                        raise
        save(output / 'status.json', {'state': 'complete', 'epoch': time.time()})
    except BaseException as error:
        outcome = getattr(error, 'outcome', 'runtime_error')
        if isinstance(error, (KeyboardInterrupt, SystemExit)):
            outcome = 'interrupted'
        failure = {'outcome': outcome, 'error': repr(error)}
        save(output / 'failure.json', failure)
        result = dict(failure)
        final = getattr(host, 'final', None)
        if final is not None:
            # Later review/cleanup failures cannot erase measured correctness.
            result = dict(final)
            result.update(execution_outcome=outcome, completion_failure=failure,
                          workflow_pass=False, protocol_pass=False,
                          timing_valid=False, elapsed_censored=True)
        save(output / 'result.json', result)
        save(output / 'status.json', {'state': 'stopped', **failure, 'epoch': time.time()})
        raise


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    group = parser.add_mutually_exclusive_group(required=True)
    group.add_argument('--plan', action='store_true', help='Print schedule without starting models')
    group.add_argument('--output', type=Path, help='Run on GX10 using a fresh output directory')
    parser.add_argument('--worker-model', choices=['ornith-nvfp4', 'ornith-bf16'], default='ornith-nvfp4')
    args = parser.parse_args()
    if args.plan:
        print(json.dumps(plan(args.worker_model), indent=2))
    else:
        # run.main supplies the standard SIGTERM/SIGINT handling for live runs.
        import signal
        def interrupt(signum, frame):
            raise KeyboardInterrupt('Signal ' + str(signum))
        signal.signal(signal.SIGTERM, interrupt)
        signal.signal(signal.SIGINT, interrupt)
        execute(args.output, worker_model=args.worker_model)


if __name__ == '__main__':
    main()
