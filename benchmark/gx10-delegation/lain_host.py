#!/usr/bin/env python3
"""Named GX10 effects for the Lain workflow; no scheduling policy lives here."""
import contextlib
import copy
import hashlib
import json
from pathlib import Path
import signal
import subprocess
import sys
import time
import uuid

import run as pilot
from lifecycle import Backend, Failure, experiment_lock, save
from contracts import LANES, collect_patch, owned_paths, tests_passed, validate_plan
from runtime import agent_job, run_agents


class Host:
    def __init__(self, output):
        self.output = output
        pilot.sandbox_check(output / 'sandbox-admission')
        self.protocol = json.loads((pilot.HERE.parent / 'gx10-context/config.json').read_text())
        self.protocol['sampling']['temperature'] = 1.0
        self.protocol['backend_runtime']['ollama_workspace_allowance_bytes'] = 6 * 1024 ** 3
        self.models = {m['id']: m for m in self.protocol['models']}
        self.worker_model = 'ornith-nvfp4'
        save(output / 'protocol.json', self.protocol)
        save(output / 'host-source-hashes.json', {p.name: hashlib.sha256(p.read_bytes()).hexdigest()
             for p in pilot.HERE.iterdir() if p.is_file()})
        self.base = output / 'base'
        save(output / 'fixture.json', {'commit': pilot.bootstrap(self.base),
             'evaluator_sha256': pilot.file_hash(pilot.HERE / 'acceptance.clj')})
        self.backend = None
        self.model = None
        self.name = None
        self.url = None
        self.jobs = []
        self.seen = set()
        self.final = None
        self.clean = False
        self.active_guard = None
        self.completed = False

    def enter(self, model):
        if self.backend is not None:
            raise Failure('transition_failed', 'A model scope is already active')
        identities = {'flash': 'flash-iq4', 'ornith-nvfp4': 'ornith-nvfp4', 'ornith-bf16': 'ornith-bf16'}
        if model not in identities:
            raise Failure('setup_declined', 'Unknown model scope')
        root = self.output / ('model-' + str(len(self.seen)))
        root.mkdir()
        phase_protocol = copy.deepcopy(self.protocol)
        if model == 'flash':
            phase_protocol['backend_runtime']['ollama_workspace_allowance_bytes'] = int(3.5 * 1024 ** 3)
        if model == 'ornith-bf16':
            phase_protocol['backend_runtime']['ollama_parallel_requests'] = 4
            phase_protocol['backend_runtime']['ollama_workspace_allowance_bytes'] = 12 * 1024 ** 3
        self.backend = Backend(root, phase_protocol)
        self.model = model
        self.clean = False
        self.backend.cleanup()
        with self.backend.guard('setup'):
            spec = self.models[identities[model]]
            if model == 'ornith-nvfp4':
                self.name, self.url = pilot.parallel_start(self.backend, spec, self.protocol, 4)
            else:
                self.name, self.url, _ = pilot.start(self.backend, spec, 131072, uuid.uuid4().hex[:12])
        self.active_guard = self.backend.guard('model-scope')
        self.active_guard.__enter__()
        return {'configuration': str(root / 'configuration-verification.json'),
                'admission': [True, True, True, True]}

    def exit(self, model):
        if self.backend is None or self.model != model:
            raise Failure('transition_failed', 'Model scope does not match cleanup')
        backend = self.backend
        if backend.attempt_unit:
            backend.stop(backend.attempt_unit)
        try:
            if self.active_guard is not None:
                self.active_guard.__exit__(None, None, None)
        finally:
            self.active_guard = None
            backend.cleanup()
        self.backend = None
        self.model = None
        self.clean = True
        return {'verified_unloaded': model}

    def require_model(self, model):
        if self.backend is None or self.model != model:
            raise Failure('setup_declined', 'Operation has no matching verified model scope')

    def agents(self, jobs, label):
        return run_agents(self.backend, jobs, self.backend.root / label, self.protocol, self.url)

    def planning(self):
        self.require_model('flash')
        checkout = self.output / 'planner-checkout'
        pilot.clone(self.base, checkout)
        job = agent_job(self.output / 'planner', checkout, 'planner', pilot.plan_prompt(), self.name, self.url, self.protocol)
        result = self.agents([job], 'planner-agents')
        changed = pilot.git(checkout, 'status', '--porcelain', '--untracked-files=all').splitlines()
        if changed != ['?? plan.json'] or (checkout / 'plan.json').is_symlink():
            raise Failure('task_failed', 'Planner changed files beyond plan.json')
        plan = json.loads((checkout / 'plan.json').read_text())
        validate_plan(plan)
        save(self.output / 'plan.json', plan)
        return result

    def smoke(self, count):
        self.require_model(self.worker_model)
        if count != 2:
            raise Failure('setup_declined', 'Smoke requires exactly two workers')
        jobs = []
        for index in range(count):
            checkout = self.output / ('smoke-checkout-' + str(index))
            pilot.clone(self.base, checkout)
            prompt = 'Create smoke.clj defining twice, and smoke_test.clj with clojure.test assertions for positive, negative and zero inputs. Run both files and real assertions. Only those two files may change. Finish normally.'
            jobs.append(agent_job(self.output / ('smoke-agent-' + str(index)), checkout,
                'smoke-' + str(index), prompt, self.name, self.url, self.protocol))
        result = self.agents(jobs, 'smoke-agents')
        for job in jobs:
            code, text = pilot.finite_check(self.backend, Path(job['cwd']), ['clojure', '-M', '-e',
                '(load-file "smoke.clj") (load-file "smoke_test.clj") (clojure.test/run-tests)'],
                Path(job['root']) / 'artifacts/verification.txt')
            if not tests_passed(code, text):
                raise Failure('task_failed', 'Smoke assertions failed')
        return result

    def workers(self, arguments):
        self.require_model(self.worker_model)
        if arguments != [4, ['normalize', 'project', 'order', 'batch']]:
            raise Failure('setup_declined', 'Worker bound or ownership differs')
        assignments = validate_plan(json.loads((self.output / 'plan.json').read_text()))
        for lane, spec in LANES.items():
            checkout = self.output / ('worker-checkout-' + lane)
            pilot.clone(self.base, checkout)
            prompt = spec['contract'] + '\nPlanner assignment:\n' + assignments[lane] + '\nOwned paths: ' + ', '.join(sorted(owned_paths(lane))) + '. Add and run clojure.test assertions using clojure -M -e with require and run-tests. No :test CLI alias exists.'
            self.jobs.append(agent_job(self.output / ('worker-' + lane), checkout, lane,
                prompt, self.name, self.url, self.protocol))
        return self.agents(self.jobs, 'worker-agents')

    def collect(self):
        self.require_model(self.worker_model)
        if len(self.jobs) != 4:
            raise Failure('task_failed', 'Missing worker handoffs')
        handoffs = []
        worker_tests = []
        for job in self.jobs:
            lane = job['lane']
            checkout = Path(job['cwd'])
            handoffs.append(collect_patch(checkout, lane, self.output / (lane + '.patch')))
            if lane != 'batch':
                namespace = LANES[lane]['namespace'] + '-test'
                expr = "(require '" + namespace + ") (clojure.test/run-tests '" + namespace + ')'
                code, text = pilot.finite_check(self.backend, checkout, ['clojure', '-M', '-e', expr],
                    Path(job['root']) / 'artifacts/verification.txt')
                worker_tests.append({'lane': lane, 'passed': tests_passed(code, text),
                                     'exit': code, 'output': str(Path(job['root']) / 'artifacts/verification.txt')})
        # Flash may repair worker tests during integration; retain their failures.
        save(self.output / 'worker-test-results.json', worker_tests)
        save(self.output / 'handoffs.json', handoffs)
        return {'handoffs': handoffs}

    def integrate(self):
        self.require_model('flash')
        checkout = self.output / 'integration-checkout'
        pilot.clone(self.base, checkout)
        for lane in LANES:
            subprocess.run(['git', 'apply', '--check', str(self.output / (lane + '.patch'))], cwd=checkout, check=True)
            subprocess.run(['git', 'apply', str(self.output / (lane + '.patch'))], cwd=checkout, check=True)
        prompt = 'Read TASK.md. Your saved plan:\n' + (self.output / 'plan.json').read_text() + '\nValidated worker patches are assembled here. Integrate and repair only delegation/*.clj and test/delegation/*_test.clj. Run all four test namespaces with real assertions. Complete normally. Reports:\n' + '\n'.join((self.output / ('worker-' + lane) / 'terminal.json').read_text() for lane in LANES)
        job = agent_job(self.output / 'integrator', checkout, 'integrator', prompt, self.name, self.url, self.protocol)
        result = self.agents([job], 'integration-agents')
        allowed = set().union(*(owned_paths(lane) for lane in LANES))
        for line in pilot.git(checkout, 'status', '--porcelain', '--untracked-files=all').splitlines():
            name = line[3:]
            if name not in allowed or (checkout / name).is_symlink():
                raise Failure('task_failed', 'Integrator changed an unowned path')
        (self.output / 'integrated.patch').write_text(pilot.git(checkout, 'diff', '--binary'))
        return result

    def evaluate(self):
        self.require_model('flash')
        checkout = self.output / 'integration-checkout'
        names = [spec['namespace'] + '-test' for spec in LANES.values()]
        expr = '(require ' + ' '.join("'" + ns for ns in names) + ') (clojure.test/run-tests ' + ' '.join("'" + ns for ns in names) + ')'
        code, text = pilot.finite_check(self.backend, checkout, ['clojure', '-M', '-e', expr], self.output / 'own-tests.txt')
        own = tests_passed(code, text)
        code, text = pilot.finite_check(self.backend, checkout, ['clojure', '-M', str(pilot.HERE / 'acceptance.clj')], self.output / 'acceptance.txt')
        correct = tests_passed(code, text)
        from contracts import assertion_counts
        import hashlib
        diagnostics = [str(path.relative_to(self.output))
                       for path in self.output.rglob('workflow-diagnostics.json')]
        workers_path = self.output / 'worker-test-results.json'
        if workers_path.exists():
            workers = json.loads(workers_path.read_text())
            diagnostics.extend('Worker assertions failed: ' + row['lane'] for row in workers if not row['passed'])
        if not own:
            diagnostics.append('Integrated own tests failed; see own-tests.txt')
        outcome = 'task_failed'
        if correct:
            outcome = 'passed'
        workflow = own and not diagnostics
        self.final = {'correct': correct, 'workflow_pass': workflow,
                      'protocol_pass': correct and workflow, 'outcome': outcome,
                      'pass_policy': 'independent-correctness-v1',
                      'workflow_diagnostics': diagnostics,
                      'acceptance_counts': assertion_counts(text),
                      'evaluator_sha256': hashlib.sha256((pilot.HERE / 'acceptance.clj').read_bytes()).hexdigest()}
        save(self.output / 'evaluation.json', self.final)
        if not correct:
            raise Failure('task_failed', 'Independent assertions failed')
        return self.final

    def review(self):
        self.require_model('flash')
        checkout = self.output / 'review-checkout'
        pilot.clone(self.base, checkout)
        prompt = ('Review these independent evaluation results and worker handoffs. '
                  'Do not implement or repair code. Write only report.json with your assessment.\n'
                  + (self.output / 'evaluation.json').read_text() + '\n'
                  + (self.output / 'handoffs.json').read_text())
        job = agent_job(self.output / 'reviewer', checkout, 'reviewer', prompt,
                        self.name, self.url, self.protocol)
        result = self.agents([job], 'review-agents')
        for line in pilot.git(checkout, 'status', '--porcelain', '--untracked-files=all').splitlines():
            if line[3:] != 'report.json':
                raise Failure('task_failed', 'Reviewer changed an implementation path')
        report = checkout / 'report.json'
        if not report.is_file() or report.is_symlink():
            self.final['workflow_pass'] = False
            self.final['protocol_pass'] = False
            self.final['workflow_diagnostics'].append('Reviewer did not produce a regular report.json')
            save(self.output / 'review-diagnostics.json', self.final['workflow_diagnostics'])
            return result
        try:
            review = json.loads(report.read_text())
        except json.JSONDecodeError as error:
            self.final['workflow_pass'] = False
            self.final['protocol_pass'] = False
            self.final['workflow_diagnostics'].append('Invalid reviewer JSON: ' + str(error))
            save(self.output / 'review-diagnostics.json', self.final['workflow_diagnostics'])
            return result
        save(self.output / 'review.json', review)
        return result

    def dispatch(self, request):
        key = json.dumps(request['path'], separators=(',', ':'))
        if key in self.seen:
            raise Failure('interrupted', 'Effects are never replayed in an existing attempt')
        self.seen.add(key)
        op, args = request['operation'], request['arguments']
        handlers = {'pi/plan': self.planning, 'patch/collect': self.collect,
                    'pi/integrate': self.integrate, 'evaluate': self.evaluate, 'pi/review': self.review}
        if op == 'workflow/provenance':
            if self.backend is not None:
                raise Failure('setup_declined', 'Provenance must precede model admission')
            save(self.output / 'lain-workflow.json', args)
            return {'saved': 'lain-workflow.json'}
        if op == 'workflow/complete':
            if self.backend is not None or not self.clean or self.final is None:
                raise Failure('transition_failed', 'Workflow has no complete evaluation and cleanup')
            samples = [json.loads(line) for path in self.output.rglob('*-memory.jsonl')
                       for line in path.read_text().splitlines()]
            if not samples:
                raise Failure('runtime_error', 'Missing RAM and swap evidence')
            result = dict(self.final)
            result['minimum_available_bytes'] = min(s['available'] for s in samples)
            result['swap_in_pages_delta'] = max(s['swap_in'] for s in samples) - min(s['swap_in'] for s in samples)
            result['swap_out_pages_delta'] = max(s['swap_out'] for s in samples) - min(s['swap_out'] for s in samples)
            wire = [json.loads(line) for path in self.output.rglob('inference-requests.jsonl')
                    for line in path.read_text().splitlines()]
            result['output_limit_responses'] = sum(r.get('finish_reason') == 'length' for r in wire)
            result['timing_valid'] = (result['swap_in_pages_delta'] == 0 and
                result['swap_out_pages_delta'] == 0 and result['output_limit_responses'] == 0)
            save(self.output / 'result.json', result)
            self.completed = True
            return result
        if op == 'model/enter':
            return self.enter(args)
        if op == 'model/exit':
            return self.exit(args)
        if op == 'model/verify-unloaded':
            if self.backend is not None or not self.clean:
                raise Failure('transition_failed', 'No verified cleanup receipt')
            return {'verified_unloaded': args}
        if op == 'pi/smoke':
            return self.smoke(args)
        if op == 'pi/parallel':
            return self.workers(args)
        if op not in handlers:
            raise Failure('setup_declined', 'Unsupported host operation')
        return handlers[op]()


def serve(output):
    host = None
    wire = sys.stdout
    def interrupt(signum, frame):
        raise KeyboardInterrupt('Signal ' + str(signum))
    signal.signal(signal.SIGTERM, interrupt)
    signal.signal(signal.SIGINT, interrupt)
    with experiment_lock(Path.home() / '.local/state/gx10-benchmark/experiment.lock'):
        try:
            with contextlib.redirect_stdout(sys.stderr):
                host = Host(output)
            for line in sys.stdin:
                request = json.loads(line)
                started = time.monotonic()
                save(output / 'pending-effect.json', request)
                try:
                    with contextlib.redirect_stdout(sys.stderr):
                        detail = host.dispatch(request)
                    receipt = {'outcome': 'passed', 'detail': detail}
                except BaseException as error:
                    receipt = {'outcome': getattr(error, 'outcome', 'runtime_error'), 'error': repr(error)}
                    if isinstance(error, (KeyboardInterrupt, SystemExit)):
                        receipt['outcome'] = 'interrupted'
                receipt.update(request=request, wall_s=time.monotonic() - started)
                if receipt['outcome'] != 'passed':
                    save(output / 'failure.json', receipt)
                with (output / 'receipts.jsonl').open('a') as stream:
                    stream.write(json.dumps(receipt) + '\n')
                    stream.flush()
                    import os
                    os.fsync(stream.fileno())
                print(json.dumps(receipt, ensure_ascii=False), file=wire, flush=True)
        finally:
            try:
                if host is not None and host.backend is not None:
                    with contextlib.redirect_stdout(sys.stderr):
                        host.exit(host.model)
            except BaseException as error:
                save(output / 'cleanup-failure.json', {'outcome': 'transition_failed', 'error': repr(error)})
                raise
            finally:
                if host is not None and not host.completed:
                    failure = output / 'failure.json'
                    result = {'outcome': 'interrupted', 'reason': 'No successful full workflow receipt'}
                    if failure.exists():
                        result = json.loads(failure.read_text())
                    save(output / 'result.json', result)


def main():
    output = Path(sys.argv[1]).resolve()
    output.mkdir(parents=True, exist_ok=False)
    try:
        serve(output)
    except BaseException as error:
        outcome = getattr(error, 'outcome', 'runtime_error')
        if isinstance(error, (KeyboardInterrupt, SystemExit)):
            outcome = 'interrupted'
        failure = {'outcome': outcome, 'error': repr(error)}
        save(output / 'host-failure.json', failure)
        if not (output / 'result.json').exists():
            save(output / 'result.json', failure)
        raise


if __name__ == '__main__':
    main()
