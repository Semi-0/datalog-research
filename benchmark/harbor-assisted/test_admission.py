import importlib.util
import json
import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('assisted_admission', Path(__file__).with_name('admission.py'))
admission = importlib.util.module_from_spec(spec)
spec.loader.exec_module(admission)


class AdmissionTests(unittest.TestCase):
    def run_probe(self, directory, sandbox, *, leftover=False, interrupt=False, version='0.19.1'):
        task = directory / 'task'
        (task / 'environment').mkdir(parents=True)
        (task / 'environment/Dockerfile').write_text('FROM fixture\n')
        node = directory / 'node'
        (node / 'bin').mkdir(parents=True)
        (node / 'bin/node').write_text('fixture')
        plugin = directory / 'plugin'
        plugin.mkdir()
        (plugin / 'package.json').write_text(json.dumps({'name': 'pi-better-subagents', 'version': version}))
        for name in ['sandbox.ts', 'shared-sandbox-core.ts']:
            (plugin / name).write_text('fixture')

        def execute(argv, timeout=30):
            result = {'argv': argv, 'exit_code': 0, 'stdout': '', 'stderr': ''}
            if argv[:2] == ['docker', 'exec']:
                if interrupt:
                    raise KeyboardInterrupt()
                result.update(sandbox)
            if argv[:3] == ['docker', 'ps', '-aq'] and leftover:
                result['stdout'] = 'still-running\n'
            return result

        output = directory / 'output'
        with patch.object(admission, 'command', side_effect=execute) as calls:
            result = admission.probe(task, node, plugin, output)
        self.assertEqual(result, json.loads((output / 'result.json').read_text()))
        return result, [call.args[0] for call in calls.call_args_list]

    def test_sandbox_failure_declines_without_inference_and_cleans(self):
        with tempfile.TemporaryDirectory() as temporary:
            result, calls = self.run_probe(Path(temporary), {
                'exit_code': 1, 'stdout': json.dumps({'sandbox_passed': False, 'stderr': 'namespace denied'})})
        self.assertEqual(result['outcome'], 'setup_declined')
        self.assertTrue(result['cleanup_verified'])
        self.assertFalse(result['cohort_started'])
        self.assertEqual(result['parent_model_requests'], 0)
        self.assertTrue(any(argv[:3] == ['docker', 'rm', '-f'] for argv in calls))

    def test_exit_zero_without_successful_write_confinement_is_not_accepted(self):
        with tempfile.TemporaryDirectory() as temporary:
            result, _ = self.run_probe(Path(temporary), {'stdout': '{"sandbox_passed": false}'})
        self.assertEqual(result['outcome'], 'setup_declined')

    def test_passed_sandbox_does_not_admit_cohort_or_claim_tool_verification(self):
        with tempfile.TemporaryDirectory() as temporary:
            result, _ = self.run_probe(Path(temporary), {'stdout': '{"sandbox_passed": true}'})
        self.assertEqual(result['outcome'], 'sandbox_passed')
        self.assertFalse(result['cohort_started'])
        self.assertNotIn('tools', result['checks'])

    def test_cleanup_uncertainty_overrides_success(self):
        with tempfile.TemporaryDirectory() as temporary:
            result, _ = self.run_probe(Path(temporary), {'stdout': '{"sandbox_passed": true}'}, leftover=True)
        self.assertEqual(result['outcome'], 'cleanup_uncertain')
        self.assertFalse(result['cleanup_verified'])

    def test_interruption_is_durable_and_cleanup_runs(self):
        with tempfile.TemporaryDirectory() as temporary:
            result, _ = self.run_probe(Path(temporary), {}, interrupt=True)
        self.assertEqual(result['outcome'], 'interrupted')
        self.assertTrue(result['cleanup_verified'])

    def test_wrong_plugin_is_rejected_before_build(self):
        with tempfile.TemporaryDirectory() as temporary:
            result, calls = self.run_probe(Path(temporary), {}, version='wrong')
        self.assertEqual(result['outcome'], 'setup_declined')
        self.assertFalse(any(argv[:2] == ['docker', 'build'] for argv in calls))

    def test_command_timeout_is_inspectable(self):
        with patch.object(admission.subprocess, 'run', side_effect=subprocess.TimeoutExpired(['docker'], 30)):
            result = admission.command(['docker', 'info'])
        self.assertIsNone(result['exit_code'])
        self.assertIn('TimeoutExpired', result['stderr'])

    def test_custom_policy_must_be_enforcing_before_sandbox_execution(self):
        from functools import partial
        import security

        def policy(base, root):
            (root / 'seccomp.json').write_text('{}')
            return ['--security-opt', 'apparmor=' + security.PROFILE]

        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            base = directory / 'base.json'
            base.write_text('{}')
            original = admission.probe
            with patch.object(admission, 'probe', side_effect=partial(original, seccomp_base=base)), \
                    patch.object(security, 'options', side_effect=policy):
                result, calls = self.run_probe(directory, {'stdout': 'unconfined\n'})
        self.assertEqual(result['outcome'], 'setup_declined')
        self.assertIn('enforcing AppArmor', result['error'])
        self.assertTrue(result['cleanup_verified'])
        self.assertFalse(any(argv[:3] == ['docker', 'exec', '-e'] for argv in calls))


if __name__ == '__main__':
    unittest.main()
