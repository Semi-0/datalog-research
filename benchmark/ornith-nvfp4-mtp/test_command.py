import json
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest

import coding
from lifecycle import save


class CommandTests(unittest.TestCase):
    def test_exact_solo_command_uses_effective_tool_policy(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            tools = ['read', 'bash', 'clj_kondo', 'lens_diagnostics', 'lsp_navigation']
            save(root / 'agent/benchmark-policy.json', {'allowed_tools': tools})
            command = coding.agent_command(root, SimpleNamespace(model='fixture'), 'local-benchmark',
                                           'Prompt', [Path('/pinned/extension.mjs')], enhanced=True)
            allowed = command[command.index('--tools') + 1].split(',')
            self.assertTrue(set(tools).issubset(allowed))
            self.assertIn('workspace_info', allowed)
            self.assertIn('read_file', allowed)
            self.assertIn('--no-extensions', command)
            self.assertIn('--approve', command)
            self.assertNotIn('--no-approve', command)
            self.assertEqual(command[-1], 'Prompt')
            self.assertEqual(command[command.index('--extension') + 1], '/pinned/extension.mjs')


if __name__ == '__main__':
    unittest.main()
