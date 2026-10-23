"""Model selection must not silently reuse the Ornith artifact or MTP."""
import hashlib
import json
from pathlib import Path
import sys
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent / 'gx10-context'))
import model_setup
from lifecycle import Failure, GIB


class LaunchReached(Exception):
    pass


class SetupTests(unittest.TestCase):
    def exercise(self, mutation=None):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            weights = root / 'weights'; weights.mkdir()
            (weights / 'weight').write_bytes(b'qwen-fixture')
            artifact = root / 'artifacts.json'
            artifact.write_text(json.dumps({'sha': 'pinned', 'siblings': [
                {'rfilename': 'weight', 'lfs': {'sha256': hashlib.sha256(b'qwen-fixture').hexdigest()}}]}))
            command = json.loads((HERE / 'server-command.json').read_text())
            command[command.index('--model') + 1] = str(weights)
            path = root / 'command.json'
            model = dict(json.loads((HERE / 'model.json').read_text()), revision='pinned',
                         model_directory=str(weights), artifact_manifest=str(artifact), server_command=str(path))
            if mutation:
                mutation(model, command)
            path.write_text(json.dumps(command))
            manifest = json.loads((HERE.parent / 'gx10-context/config.json').read_text())
            manifest['sampling']['temperature'] = 1.0
            captured = []
            def launch(unit, args, env):
                captured.append(args)
                raise LaunchReached()
            backend = SimpleNamespace(root=root, manifest=manifest, event=lambda *args: None, launch=launch)
            with patch.object(model_setup, 'memory', return_value={'available': 110*GIB, 'reserve': 25*GIB}), patch.object(model_setup, 'identities', return_value=[]):
                try:
                    model_setup.start(backend, model, 131072, 'fixture')
                except LaunchReached:
                    return captured[0], json.loads((root / 'budget.json').read_text())
            self.fail('Expected controlled launch boundary')

    def test_new_artifact_and_context_use_shared_guarded_backend(self):
        command, budget = self.exercise()
        self.assertEqual(command[command.index('--served-model-name') + 1], 'qwen38-27b-nvfp4')
        self.assertEqual(command[command.index('--max-model-len') + 1], '131072')
        self.assertNotIn('--speculative-config', command)
        self.assertEqual(command[command.index('--tool-call-parser') + 1], 'qwen3_coder')
        self.assertEqual(budget['cache_estimate_bytes'], 16*GIB)
        self.assertTrue(budget['allowed'])

    def test_revision_mismatch_never_launches(self):
        with self.assertRaises(Failure):
            self.exercise(lambda model, command: model.update(revision='wrong'))

    def test_served_identity_mismatch_never_launches(self):
        def change(model, command):
            command[command.index('--served-model-name') + 1] = 'ornith-nvfp4-mtp'
        with self.assertRaises(Failure):
            self.exercise(change)

    def test_stale_template_effort_never_launches(self):
        def change(model, command):
            command[command.index('--default-chat-template-kwargs') + 1] = '{"reasoning_effort":"high"}'
        with self.assertRaises(Failure):
            self.exercise(change)

    def test_speculation_is_rejected_for_baseline(self):
        def change(model, command):
            command.extend(['--speculative-config', '{"method":"mtp"}'])
        with self.assertRaises(Failure):
            self.exercise(change)


if __name__ == '__main__':
    unittest.main()
