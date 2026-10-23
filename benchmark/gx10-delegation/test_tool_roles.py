"""Contracts for Flash orchestration and bounded BF16 implementation."""
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import orchestrator
import runtime
import tooling
from lifecycle import Failure, save


class RoleTests(unittest.TestCase):
    def test_flash_integrates_only_after_bf16_is_verified_unloaded(self):
        steps = orchestrator.plan('ornith-bf16')['steps']
        # Inspect the public executable schedule rather than starting models.
        serialized = json.dumps(steps)
        self.assertIn('ornith-bf16', serialized)
        self.assertNotIn('ornith-nvfp4', serialized)
        operations = [step['operation'] for step in steps]
        self.assertLess(operations.index('patch/collect'), operations.index('pi/integrate'))
        integration = operations.index('pi/integrate')
        self.assertEqual(operations[integration - 2:integration], ['model/verify-unloaded', 'model/enter'])
        self.assertLess(integration, operations.index('pi/review'))

    def test_agent_roles_select_private_implementation_tools_only_for_workers(self):
        for lane, role in [('planner', 'controller'), ('reviewer', 'controller'),
                           ('integrator', 'controller'), ('normalize', 'implementor')]:
            with self.subTest(lane=lane), tempfile.TemporaryDirectory() as tmp:
                root = Path(tmp) / 'agent'
                def prepare(destination, *_args):
                    (destination / 'agent').mkdir(parents=True)
                    (destination / 'artifacts').mkdir()
                    save(destination / 'agent/settings.json', {})
                def configure(destination, _checkout, env, _protocol):
                    self.assertEqual(env['GX10_TOOL_ROLE'], role)
                    policy = {'allowed_tools': ['read', 'write', 'edit', 'bash']}
                    if role == 'implementor':
                        policy['allowed_tools'] += ['clj_kondo', 'lens_diagnostics', 'lsp_navigation']
                        policy['gortex_tools'] = ['workspace_info', 'read_file']
                    save(destination / 'agent/benchmark-policy.json', policy)
                    if role == 'implementor':
                        save(destination / 'artifacts/gortex-profile.json', {'socket': '/tmp/private.sock'})
                    return []
                with patch.object(runtime, 'prepare', side_effect=prepare), \
                     patch.object(runtime, 'prepare_vigilant', return_value=Path('/vigilant')), \
                     patch.object(runtime, 'node_environment', return_value={}), \
                     patch.object(runtime, 'configure_tooling', side_effect=configure):
                    job = runtime.agent_job(root, Path(tmp), lane, 'Assigned task', 'model', 'url', {'coding': {}})
                self.assertEqual(job['env']['GX10_TOOL_ROLE'], role)
                self.assertIn('--no-extensions', job['invocation']['args'])
                self.assertEqual('--approve' in job['invocation']['args'], role == 'implementor')
                self.assertEqual('clj_kondo' in job['tools'], role == 'implementor')
                self.assertEqual('workspace_info' in job['tools'], role == 'implementor')
                self.assertEqual(job['gortexSocket'] is not None, role == 'implementor')
                self.assertEqual('lens_diagnostics' in job['task'], role == 'implementor')

    def test_solo_environment_declares_observer_root(self):
        with tempfile.TemporaryDirectory() as tmp:
            base = Path(tmp)
            root = base / 'attempt'
            checkout = base / 'checkout'
            (checkout / '.git/info').mkdir(parents=True)
            tools = base / 'tools'
            web = base / 'web'
            kondo = base / 'clj-kondo'
            graph = base / 'gortex-entry.ts'
            for file in [tools / 'node_modules/pi-lens/dist/index.js',
                         tools / 'node_modules/pi-linter/index.ts', tools / 'lsp/clojure-lsp',
                         tools / 'package-lock.json', web / 'dist/index.js', kondo, graph]:
                file.parent.mkdir(parents=True, exist_ok=True)
                file.write_text('fixture')
            save(root / 'agent/models.json', {'providers': {'local-benchmark': {'models': [{'samplingParams': {}}]}}})
            env = {'PATH': '/usr/bin:/bin'}
            protocol = {'sampling': {'temperature': 1.0}, 'coding': {'allowed_tools': ['read', 'write', 'edit', 'bash']}}
            with patch.object(tooling, 'TOOLS', tools), patch.object(tooling, 'WEB', web), \
                 patch.object(tooling, 'KONDO', kondo), patch.object(tooling, 'prepare_gortex', return_value=graph):
                tooling.configure(root, checkout, env, protocol)
            self.assertEqual(env['GX10_AGENT_ROOT'], str(root))
            self.assertEqual(env['GX10_TOOL_ROLE'], 'implementor')
            policy = json.loads((root / 'agent/benchmark-policy.json').read_text())
            self.assertIn('workspace_info', policy['gortex_tools'])
            self.assertIn('clj_kondo', policy['allowed_tools'])
            self.assertIn('lens_diagnostics', policy['allowed_tools'])
            # Reconfiguration after a graph entry already exists must retain graph policy.
            existing = root / 'agent/extensions/gortex-entry.ts'
            existing.parent.mkdir(parents=True, exist_ok=True)
            existing.write_text('fixture')
            with patch.object(tooling, 'TOOLS', tools), patch.object(tooling, 'WEB', web), \
                 patch.object(tooling, 'KONDO', kondo), patch.object(tooling, 'prepare_gortex') as prepare:
                tooling.configure(root, checkout, env, protocol)
                prepare.assert_not_called()
            self.assertEqual(json.loads((root / 'agent/benchmark-policy.json').read_text()), policy)

    def test_registered_tools_without_successful_calls_are_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            save(root / 'artifacts/tool-profile.json', {'role': 'controller'})
            (root / 'artifacts/tooling.jsonl').write_text(json.dumps({'kind': 'ready', 'temperature': 1.0,
                'tools': ['web_search', 'fetch_content']}) + '\n')
            (root / 'artifacts/activity.jsonl').write_text('')
            with self.assertRaises(Failure):
                tooling.verify(root)


if __name__ == '__main__':
    unittest.main()
