import itertools
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

from lifecycle import Backend, Failure, GIB, experiment_lock, save, settle
from outcomes import classify, test_counts, tests_passed, smoke_capabilities_verified
import suite


class Clock:
    def __init__(self):
        self.value = 0
    def now(self):
        return self.value
    def sleep(self, value):
        self.value += value


def snapshot(available=100 * GIB, models=None, processes=None, endpoint=False):
    return {'models': models or [], 'old_processes': processes or [], 'endpoint_alive': endpoint,
            'memory': {'available': available}}


class TransitionTests(unittest.TestCase):
    def gate(self, samples, baseline=None, timeout=180):
        clock = Clock()
        events = []
        def observe():
            return samples[min(int(clock.value), len(samples) - 1)]
        value = settle(observe, lambda *event: events.append(event), baseline, timeout, clock.now, clock.sleep)
        return clock.value, events, value

    def test_all_16_ordered_transitions_include_same_configuration(self):
        models = suite.MANIFEST['models']
        self.assertEqual(4, len(models))
        for before, after in itertools.product(models, repeat=2):
            with self.subTest(before=before['id'], after=after['id']):
                samples = [snapshot(models=[before['source']])] * 4
                samples += [snapshot(processes=[{'pid': 123, 'start_ticks': 'old'}])] * 3
                samples += [snapshot(available=(90 + i) * GIB) for i in range(5)]
                samples += [snapshot()] * 12
                elapsed, events, value = self.gate(samples)
                self.assertGreaterEqual(elapsed, 22)
                self.assertEqual(100 * GIB, value)
                self.assertTrue(events)

    def test_ten_full_seconds_are_required(self):
        elapsed, _, _ = self.gate([snapshot()])
        self.assertEqual(10, elapsed)

    def test_acknowledgement_does_not_bypass_residency_or_lingering_process(self):
        for state in [snapshot(models=['still-loaded']), snapshot(processes=[{'pid': 12}]), snapshot(endpoint=True)]:
            with self.subTest(state=state), self.assertRaises(Failure) as error:
                self.gate([state], timeout=20)
            self.assertEqual('transition_failed', error.exception.outcome)

    def test_shortfall_requires_recovery_or_transition_failure(self):
        elapsed, events, _ = self.gate([snapshot(95 * GIB)] * 25 + [snapshot()], 100 * GIB)
        self.assertGreaterEqual(elapsed, 35)
        self.assertIn('baseline_shortfall', [kind for kind, _ in events])
        with self.assertRaises(Failure):
            self.gate([snapshot(95 * GIB)], 100 * GIB, 20)

    def test_api_failure_cannot_be_a_clean_observation(self):
        with self.assertRaises(OSError):
            settle(lambda: (_ for _ in ()).throw(OSError('inventory failed')), lambda *_: None)

    def test_host_lock_rejects_another_controller(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'host.lock'
            with experiment_lock(path):
                with self.assertRaises(Failure):
                    with experiment_lock(path):
                        self.fail('Second controller entered')

    def test_result_save_is_atomic_and_durable(self):
        with tempfile.TemporaryDirectory() as directory:
            p = Path(directory) / 'result.json'
            save(p, {'outcome': 'passed'})
            self.assertEqual('passed', json.loads(p.read_text())['outcome'])
            self.assertFalse(p.with_suffix('.json.tmp').exists())


class FakeBackend:
    events = []
    initial_error = None
    final_error = None
    def __init__(self, root, manifest):
        self.root = root
        self.unit = None
        self.attempt_unit = None
        self.state = {'attempt_results': {}}
        self.breached = type('Flag', (), {'is_set': lambda self: False})()
        self.calls = 0
    def event(self, *args):
        pass
    def persist(self):
        pass
    def cleanup(self):
        self.calls += 1
        self.events.append('cleanup')
        error = self.initial_error
        if self.calls > 1:
            error = self.final_error
        if error:
            raise error
        self.events.append('verified')
    def guard(self, name):
        from contextlib import nullcontext
        return nullcontext()
    def launch(self, *args):
        self.events.append('launch')
    def stop(self, *args):
        self.events.append('stop')


class CellTests(unittest.TestCase):
    def setUp(self):
        FakeBackend.events = []
        FakeBackend.initial_error = None
        FakeBackend.final_error = None

    def run_cell(self, directory, setup):
        with patch.object(suite, 'runtime_versions', return_value={}), patch.object(suite, 'Backend', FakeBackend), patch.object(suite, 'start', setup):
            return suite.run_cell(suite.MANIFEST['models'][0], 'transition', directory)

    def test_preflight_and_load_never_run_before_verified_transition(self):
        with tempfile.TemporaryDirectory() as directory:
            def setup(*args):
                self.assertEqual(['cleanup', 'verified'], FakeBackend.events)
                FakeBackend.events.append('load')
                return ('model', 'url', 'unit')
            self.run_cell(Path(directory) / 'attempt', setup)
            self.assertEqual(['cleanup', 'verified', 'load', 'cleanup', 'verified'], FakeBackend.events)
            FakeBackend.initial_error = Failure('transition_failed', 'lingering process')
            with patch.object(suite, 'runtime_versions', return_value={}), patch.object(suite, 'start') as start, patch.object(suite, 'Backend', FakeBackend), self.assertRaises(Failure):
                suite.run_cell(suite.MANIFEST['models'][0], 'transition', Path(directory) / 'blocked')
            start.assert_not_called()

    def test_cleanup_failure_retains_primary_result_and_stops_scheduler(self):
        FakeBackend.final_error = Failure('transition_failed', 'group still populated')
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory) / 'cell'
            with self.assertRaises(Failure):
                self.run_cell(root, lambda *_: ('model', 'url', 'unit'))
            self.assertEqual('passed', json.loads((root / 'result.json').read_text())['outcome'])
            self.assertEqual('cleanup_failed', json.loads((root / 'checkpoint.json').read_text())['stage'])
            self.assertTrue((root / 'cleanup-failure.json').exists())

    def test_startup_error_and_resource_breach_have_distinct_outcomes(self):
        for outcome in ['runtime_error', 'resource_stopped', 'setup_declined']:
            with tempfile.TemporaryDirectory() as directory:
                def fail(*_):
                    raise Failure(outcome, 'simulated failure')
                result = self.run_cell(Path(directory) / 'cell', fail)
                self.assertEqual(outcome, result['outcome'])
                self.assertEqual('verified', FakeBackend.events[-1])
                FakeBackend.events = []

    def test_interruption_in_setup_is_durable_and_retryable(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            def interrupted(*_):
                raise KeyboardInterrupt()
            with self.assertRaises(Failure):
                self.run_cell(root / 'cells/interrupted', interrupted)
            self.assertEqual('interrupted', json.loads((root / 'cells/interrupted/result.json').read_text())['outcome'])
            self.assertEqual([], suite.retained_rows(root))

    def test_resume_coding_and_context_recovers_result_after_index_interruption(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for task, seed in [('distinct', None), ('retrieval', 42)]:
                cell = root / 'cells' / task
                cell.mkdir(parents=True)
                save(cell / 'cell.json', {'task': task, 'seed': seed, 'model': {'id': 'ornith-q4'}, 'context': 32768})
                save(cell / 'checkpoint.json', {'stage': 'complete'})
                save(cell / 'result.json', {'outcome': 'passed'})
            self.assertEqual({'coding', 'context'}, {r['phase'] for r in suite.retained_rows(root)})
            save(root / 'cells/distinct/checkpoint.json', {'stage': 'cleanup_after'})
            self.assertEqual(['context'], [r['phase'] for r in suite.retained_rows(root)])


class OutcomeTests(unittest.TestCase):
    text = 'Ran 3 tests containing 14 assertions.\n0 failures, 0 errors.'
    def base(self):
        return dict(task='distinct', acceptance_exit=0, own_tests_exit=0, confined=True,
                    thinking_observed=True, search_succeeded=True, fetch_succeeded=True,
                    agent_final_response=True, agent_end_observed=True, pi_exit=0,
                    swap_in_pages_delta=0, swap_out_pages_delta=0)
    def test_counts_require_assertions_and_clean_summary(self):
        self.assertTrue(tests_passed(0, test_counts(self.text), 14))
        for text in ['Ran 0 tests containing 0 assertions.\n0 failures, 0 errors.', 'Syntax error',
                     'Ran 1 tests containing 1 assertions.\n1 failures, 0 errors.']:
            self.assertFalse(tests_passed(0, test_counts(text)))
    def test_outcome_dimensions(self):
        for changes, outcome, correct, workflow, timing in [
            ({}, 'passed', True, True, True),
            ({'confined': False}, 'task_failed', True, False, True),
            ({'stream_failures': [{'type': 'message_end', 'stop_reason': 'length', 'error': None}]}, 'runtime_error', True, False, False),
            ({'agent_end_observed': False}, 'runtime_error', True, False, False),
            ({'agent_final_response': False}, 'runtime_error', True, False, False),
            ({'assistant_errors': ['compaction failed']}, 'runtime_error', True, False, False),
            ({'nonterminating': True}, 'nonterminating', True, False, False),
            ({'resource_stopped': True}, 'resource_stopped', True, False, False),
            ({'interrupted': True}, 'interrupted', True, False, False),
            ({'swap_out_pages_delta': 1}, 'passed', True, True, False)]:
            result = classify(dict(self.base(), **changes), self.text, self.text)
            if correct:
                outcome = 'passed'
            self.assertEqual((outcome, correct, workflow, timing),
                             (result['outcome'], result['correct'], result['workflow_pass'], result['timing_valid']))
    def test_incorrect_independent_evaluation_cannot_pass(self):
        r = classify(self.base(), self.text, self.text.replace('0 failures', '1 failures'))
        self.assertFalse(r['correct'])
        self.assertEqual('task_failed', r['outcome'])

class InterruptionAndGuardTests(unittest.TestCase):
    def test_interrupted_coding_evaluation_and_context_attempts_are_not_retained(self):
        for task, seed, stage in [('distinct', None, 'coding'), ('order', None, 'evaluation'), ('retrieval', 42, 'context')]:
            with self.subTest(stage=stage), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                FakeBackend.initial_error = None
                FakeBackend.final_error = None
                with patch.object(suite, 'runtime_versions', return_value={}), patch.object(suite, 'Backend', FakeBackend), patch.object(suite, 'start', return_value=('m', 'url', 'unit')):
                    with patch.object(suite, 'wait_attempt', side_effect=KeyboardInterrupt()), self.assertRaises(Failure):
                        suite.run_cell(suite.MANIFEST['models'][0], task, root / 'cells/cell', seed=seed, tokenizer=Path('tokenizer'))
                result = json.loads((root / 'cells/cell/result.json').read_text())
                self.assertEqual('interrupted', result['outcome'])
                self.assertEqual([], suite.retained_rows(root))

    def test_reserve_breach_stops_only_registered_services(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            backend = Backend(root, suite.MANIFEST, root / 'state')
            backend.unit = 'owned-backend.service'
            backend.attempt_unit = 'owned-attempt.service'
            low = {'available': 5, 'reserve': 10}
            with patch('lifecycle.identities', return_value=[]), patch('lifecycle.memory', return_value=low), patch.object(backend, 'stop') as stopped:
                with self.assertRaises(Failure) as failure:
                    with backend.guard('test'):
                        self.assertTrue(backend.breached.wait(2))
                self.assertEqual('resource_stopped', failure.exception.outcome)
                self.assertEqual(['owned-attempt.service', 'owned-backend.service'], [call.args[0] for call in stopped.call_args_list])

@unittest.skipUnless(Path('/proc/self/cgroup').exists(), 'Linux systemd service ownership check')
class ServiceOwnershipTests(unittest.TestCase):
    def test_owned_cgroup_includes_separate_nested_groups_and_preserves_sentinel(self):
        import os
        import sys
        import time
        import uuid
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            backend = Backend(root, suite.MANIFEST, root / 'state')
            pidfile = root / 'child.pid'
            script = ('import subprocess,sys,time,signal; from pathlib import Path; '
                      'signal.signal(signal.SIGTERM,signal.SIG_IGN); '
                      'child=subprocess.Popen([sys.executable,"-c","import time,signal; signal.signal(signal.SIGTERM,signal.SIG_IGN); time.sleep(120)"],start_new_session=True); '
                      'Path(sys.argv[1]).write_text(str(child.pid)); time.sleep(120)')
            sentinel = subprocess.Popen([sys.executable, '-c', 'import time; time.sleep(120)'], start_new_session=True)
            unit = 'gx10-v3-test-' + uuid.uuid4().hex[:12] + '.service'
            try:
                backend.launch(unit, [sys.executable, '-c', script, str(pidfile)])
                deadline = time.monotonic() + 5
                while not pidfile.exists() and time.monotonic() < deadline:
                    time.sleep(.02)
                child = int(pidfile.read_text())
                self.assertIn(child, [p['pid'] for p in backend.group_processes(unit)])
                backend.stop(unit)
                backend.stop(unit)
                self.assertEqual([], backend.group_processes(unit))
                self.assertIsNone(sentinel.poll())
            finally:
                backend.system('stop', unit, check=False)
                sentinel.terminate()
                sentinel.wait(timeout=3)

class ReserveAbortClassificationTests(unittest.TestCase):
    def test_http_abort_after_reserve_breach_keeps_resource_outcome(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            backend = Backend(root, suite.MANIFEST, root / 'state')
            backend.unit = 'owned-backend.service'
            high = {'available': 100, 'reserve': 10}
            low = {'available': 1, 'reserve': 10}
            with patch('lifecycle.identities', return_value=[]), patch('lifecycle.memory', side_effect=[high, low]), patch.object(backend, 'stop'):
                with self.assertRaises(Failure) as failure:
                    with backend.guard('http'):
                        self.assertTrue(backend.breached.wait(2))
                        raise OSError('Connection closed by guard')
                self.assertEqual('resource_stopped', failure.exception.outcome)

    def test_child_without_result_after_resource_stop_is_not_runtime_error(self):
        backend = type('Stopped', (), {'breached': type('Flag', (), {'is_set': lambda self: True})()})()
        with self.assertRaises(Failure) as failure:
            suite.wait_attempt(backend, 'owned.service', Path('/missing/result.json'))
        self.assertEqual('resource_stopped', failure.exception.outcome)

class ScheduleTests(unittest.TestCase):
    def test_fresh_suite_has_twelve_independent_coding_cells_before_context(self):
        from types import SimpleNamespace
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory) / 'fresh'
            args = SimpleNamespace(output=root, validation=Path('validated'), continue_from=None)
            calls = []
            def cell(model, task, output, context=32768, seed=None, tokenizer=None):
                calls.append((model['id'], task, context, seed, output))
                self.assertFalse(output.exists())
                output.mkdir()
                return {'outcome': 'passed', 'correct': True, 'workflow_pass': True, 'timing_valid': True}
            with patch.object(suite, 'check_validation'), patch.object(suite, 'tokenizer_paths', return_value={'ornith': 'o', 'flash': 'f'}), patch.object(suite, 'run_cell', side_effect=cell):
                suite.run(args)
            self.assertEqual(12, len([call for call in calls if call[3] is None]))
            self.assertTrue(all(call[3] is None for call in calls[:12]))
            self.assertTrue(all(call[3] is not None for call in calls[12:]))
            self.assertEqual(12, len(set((call[0], call[1]) for call in calls[:12])))
            self.assertEqual(len(calls), len(set(call[4] for call in calls)))

    def test_stale_deterministic_evidence_prevents_live_loading(self):
        from types import SimpleNamespace
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            save(root / 'deterministic.json', {'passed': True, 'harness_hashes': {}})
            args = SimpleNamespace(output=root / 'live', deterministic=root / 'deterministic.json', persistence=root / 'persistence.json')
            with patch.object(suite, 'run_cell') as cell, self.assertRaises(Failure):
                suite.validate_live(args)
            cell.assert_not_called()


class SmokeCapabilityTests(unittest.TestCase):
    def test_output_budget_end_does_not_erase_verified_primitives_or_become_a_full_pass(self):
        result = OutcomeTests().base()
        result['tool_names'] = ['read', 'edit', 'bash', 'web_search', 'fetch_content']
        result['stream_failures'] = [{'type': 'message_end', 'stop_reason': 'length', 'error': None}]
        result['agent_final_response'] = False
        classify(result, OutcomeTests.text, OutcomeTests.text)
        self.assertEqual('runtime_error', result['execution_outcome'])
        self.assertEqual('output_token_exhaustion', result['failure_kind'])
        self.assertFalse(result['workflow_pass'])
        self.assertFalse(result['protocol_pass'])
        self.assertTrue(smoke_capabilities_verified(result))
        for changed in [{'resource_stopped': True}, {'correct': False}, {'agent_end_observed': False},
                        {'stream_failures': [{'type': 'message_end', 'stop_reason': 'error'}]},
                        {'assistant_errors': ['compaction failed']}]:
            self.assertFalse(smoke_capabilities_verified(dict(result, **changed)))


class ConflictTests(unittest.TestCase):
    def test_unowned_backend_is_never_unloaded_or_stopped(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            backend = Backend(root, suite.MANIFEST, root / 'state')
            process = {'pid': 55, 'command': '/usr/local/bin/ollama serve '}
            with patch('lifecycle.identities', return_value=[process]), patch('lifecycle.memory', return_value={}), patch.object(backend, 'inventory') as inventory, patch.object(backend, 'stop') as stop:
                with self.assertRaises(Failure):
                    backend.cleanup()
                inventory.assert_not_called()
                stop.assert_not_called()

    def test_existing_service_name_is_not_added_to_ownership(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            backend = Backend(root, suite.MANIFEST, root / 'state')
            with patch.object(backend, 'system', return_value='loaded'), patch('lifecycle.subprocess.run') as run:
                with self.assertRaises(Failure):
                    backend.launch('conflicting.service', ['unstarted'])
                run.assert_not_called()
            self.assertEqual([], backend.state['units'])


class DirectedTransitionPathTests(unittest.TestCase):
    def test_live_path_covers_all_sixteen_directed_pairs_exactly_once(self):
        from collections import Counter
        path = suite.transition_path(suite.MANIFEST['models'])
        ids = [model['id'] for model in path]
        self.assertEqual(17, len(ids))
        expected = Counter(itertools.product([m['id'] for m in suite.MANIFEST['models']], repeat=2))
        self.assertEqual(expected, Counter(zip(ids, ids[1:])))


class OllamaOffloadTests(unittest.TestCase):
    def test_resident_offload_is_independent_of_larger_gguf_artifact_size(self):
        from model_setup import verify_ollama_offload
        # Actual Q4 inventory: disk artifact 22616285127, resident allocation 22160447896.
        active = {'size': 22160447896, 'size_vram': 22160447896}
        evidence = verify_ollama_offload(active, 'load_tensors: offloaded 42/42 layers to GPU')
        self.assertTrue(evidence['all_layers_on_gpu'])
        self.assertTrue(evidence['all_resident_memory_on_gpu'])
        for inventory, journal in [
            ({'size': 100, 'size_vram': 90}, 'offloaded 42/42 layers to GPU'),
            ({'size': 100, 'size_vram': 100}, 'offloaded 41/42 layers to GPU'),
            ({'size': 0, 'size_vram': 0}, 'offloaded 42/42 layers to GPU'),
            ({'size': 100, 'size_vram': 100}, 'No placement evidence')]:
            with self.assertRaises(Failure):
                verify_ollama_offload(inventory, journal)


class RecoveryBoundaryTests(unittest.TestCase):
    def test_installed_runner_binary_forms_are_recorded_without_matching_shell_text(self):
        from lifecycle import ollama_runners
        commands = ['/opt/ollama/llama-server --model blob', '/opt/ollama/ollama runner --model blob',
                    '/opt/ollama/ollama_llama_server --model blob', '/bin/bash -c /opt/ollama/llama-server',
                    '/opt/ollama/ollama serve']
        processes = [{'pid': i, 'command': command} for i, command in enumerate(commands)]
        self.assertEqual([0, 1, 2], [p['pid'] for p in ollama_runners(processes)])

    def test_interrupted_runtime_metadata_is_an_explicit_durable_attempt(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory) / 'cell'
            with patch.object(suite, 'Backend', FakeBackend), patch.object(suite, 'runtime_versions', side_effect=KeyboardInterrupt()), self.assertRaises(Failure):
                suite.run_cell(suite.MANIFEST['models'][0], 'transition', root)
            self.assertEqual('interrupted', json.loads((root / 'result.json').read_text())['outcome'])
            self.assertTrue((root / 'cell.json').exists())

    def test_refused_existing_output_cannot_overwrite_earlier_failure(self):
        from contextlib import nullcontext
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            failure = root / 'failure.json'
            failure.write_text('previous exploratory failure')
            argv = ['suite.py', 'run-cell', '--model', suite.MANIFEST['models'][0]['id'], '--task', 'distinct', '--output', str(root)]
            with patch('sys.argv', argv), patch.object(suite, 'experiment_lock', return_value=nullcontext()), self.assertRaises(FileExistsError):
                suite.main()
            self.assertEqual('previous exploratory failure', failure.read_text())


class DurableContinuationTests(unittest.TestCase):
    def test_a_separately_guarded_previous_worker_is_waited_for_before_cleanup(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            backend = Backend(root, suite.MANIFEST, root / 'state')
            backend.state['units'] = ['gx10-v3-attempt-old.service']
            with patch.object(backend, 'system', side_effect=['running', 'running', 'exited']) as system, patch('lifecycle.time.sleep') as sleep:
                backend.wait_previous_attempts()
            self.assertEqual(2, sleep.call_count)
            self.assertTrue(all(call.args[0] == 'show' for call in system.call_args_list))

    def test_primary_and_worker_results_survive_an_interruption_during_cleanup_or_publication(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for task, seed, stage, result_path in [('distinct', None, 'cleanup_after', 'result.json'),
                ('retrieval', 42, 'context', 'attempt/result.json')]:
                cell = root / 'cells' / task
                cell.mkdir(parents=True)
                save(cell / 'cell.json', {'task': task, 'seed': seed, 'model': {'id': 'ornith-q4'}, 'context': 32768})
                save(cell / 'checkpoint.json', {'stage': stage})
                save(cell / result_path, {'outcome': 'passed'})
            self.assertEqual([], suite.retained_rows(root))
            recovered = suite.retained_rows(root, cleanup_verified=True)
            self.assertEqual({'coding', 'context'}, {row['phase'] for row in recovered})
            save(root / 'cells/distinct/result.json', {'outcome': 'interrupted'})
            self.assertEqual(['context'], [r['phase'] for r in suite.retained_rows(root, cleanup_verified=True)])


class ResourceBoundaryRefinementTests(unittest.TestCase):
    def test_retrieval_failure_does_not_stop_refining_a_known_memory_boundary(self):
        from types import SimpleNamespace
        with tempfile.TemporaryDirectory() as directory:
            args = SimpleNamespace(output=Path(directory) / 'fresh', validation=Path('validated'), continue_from=None)
            observed = []
            def cell(model, task, output, context=32768, seed=None, tokenizer=None):
                outcome = 'passed'
                if seed is not None:
                    observed.append((model['id'], context, seed))
                    if context == 65536:
                        outcome = 'setup_declined'
                    elif context == 49152:
                        outcome = 'task_failed'  # Full request completed safely; retrieval was incorrect.
                return {'outcome': outcome}
            with patch.object(suite, 'check_validation'), patch.object(suite, 'tokenizer_paths', return_value={'ornith': 'o', 'flash': 'f'}), patch.object(suite, 'run_cell', side_effect=cell):
                suite.run(args)
            for model in suite.MANIFEST['models']:
                sizes = [context for model_id, context, seed in observed if model_id == model['id']]
                self.assertEqual([32768] * 3 + [65536] + [49152] * 3 + [57344] * 3, sizes)


if __name__ == '__main__':
    unittest.main()
