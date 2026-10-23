import hashlib
import importlib.util
import io
import json
from pathlib import Path
import sys
import tempfile
import types
import unittest
from unittest.mock import patch

import follow_on
from lifecycle import Failure


class ArtifactPreparationTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        spec = importlib.util.spec_from_file_location('download_under_test', Path(__file__).with_name('download.py'))
        self.download = importlib.util.module_from_spec(spec)
        with patch.dict(sys.modules, {'huggingface_hub': types.SimpleNamespace(snapshot_download=None)}):
            spec.loader.exec_module(self.download)
        self.files = {'config.json': b'{}', 'model.safetensors': b'weights'}
        self.model = {'repository': 'test/model', 'revision': 'a' * 40,
                      'model_directory': str(self.root / 'model'), 'weight_bytes': 7,
                      'artifact_manifest': str(self.root / 'artifacts.json')}
        self.metadata = {'sha': self.model['revision'], 'siblings': [
            {'rfilename': 'config.json', 'size': 2, 'blobId': hashlib.sha1(b'blob 2\0{}').hexdigest()},
            {'rfilename': 'model.safetensors', 'size': 7, 'lfs': {'sha256': hashlib.sha256(b'weights').hexdigest()}}]}

    def prepare(self, tamper=False):
        def snapshot(**kwargs):
            target = Path(kwargs['local_dir'])
            target.mkdir(parents=True, exist_ok=True)
            for name, content in self.files.items():
                if tamper and name == 'config.json':
                    content = b'[]'
                (target / name).write_bytes(content)
        with patch.object(self.download, 'snapshot_download', side_effect=snapshot), patch.object(self.download.urllib.request, 'urlopen', return_value=io.BytesIO(json.dumps(self.metadata).encode())):
            self.download.download_model(self.model)

    def test_descriptor_drives_pinned_download_and_all_files_are_verified(self):
        self.prepare()
        result = json.loads((self.root / 'artifacts.json').read_text())
        self.assertEqual(result['sha'], self.model['revision'])
        self.assertEqual(len(result['siblings']), 2)
        self.assertTrue(all('sha256' in row['lfs'] for row in result['siblings']))

    def test_small_blob_tampering_prevents_prepared_manifest(self):
        with self.assertRaises(RuntimeError):
            self.prepare(tamper=True)
        self.assertFalse((self.root / 'artifacts.json').exists())

    def test_wrong_weight_budget_prevents_prepared_manifest(self):
        self.model['weight_bytes'] = 8
        with self.assertRaises(RuntimeError):
            self.prepare()
        self.assertFalse((self.root / 'artifacts.json').exists())

    def test_revision_mismatch_prevents_downloading(self):
        self.metadata['sha'] = 'b' * 40
        with self.assertRaises(RuntimeError):
            self.prepare()
        self.assertFalse((self.root / 'model').exists())

    def test_preparation_writes_descriptor_before_launch(self):
        cohort = {'label': 'candidate', 'model': self.model}
        with patch.object(follow_on.subprocess, 'run') as launch:
            follow_on.prepare_model_artifacts(self.root, cohort)
        command = launch.call_args.args[0]
        self.assertEqual(command[-2], '--model')
        self.assertEqual(json.loads(Path(command[-1]).read_text()), self.model)
        self.assertEqual(launch.call_args.kwargs['timeout'], 3600)

    def test_preparation_failure_is_setup_declined(self):
        error = follow_on.subprocess.CalledProcessError(1, ['download'])
        with patch.object(follow_on.subprocess, 'run', side_effect=error):
            with self.assertRaises(Failure) as raised:
                follow_on.prepare_model_artifacts(self.root, {'label': 'candidate', 'model': self.model})
        self.assertEqual(raised.exception.outcome, 'setup_declined')

    def test_artifact_preparation_is_after_predecessor_and_before_cohort(self):
        events = []
        root = self.root / 'run'
        root.mkdir()
        plan = {'predecessor': str(self.root / 'previous'), 'predecessor_unit': 'previous.service',
                'manifest': {}, 'cohorts': [{'label': 'model', 'model': {}}], 'prepare_artifacts': True}
        class Lock:
            def __enter__(self):
                events.append('lock')
            def __exit__(self, *args):
                events.append('unlock')
        with (patch.object(follow_on, 'validate_plan'), patch.object(follow_on, 'wait_predecessor', side_effect=lambda *args: events.append('wait')),
              patch.object(follow_on, 'load', return_value={'historical': [], 'current': []}), patch.object(follow_on, 'experiment_lock', return_value=Lock()),
              patch.object(follow_on, 'prepare_model_artifacts', side_effect=lambda *args: events.append('download')),
              patch.object(follow_on, 'run_cohort', side_effect=lambda *args: events.append('run')), patch.object(follow_on.reporting, 'report')):
            follow_on.execute(root, plan)
        self.assertEqual(events, ['wait', 'lock', 'download', 'run', 'unlock'])

    def test_failed_predecessor_does_not_download_or_load(self):
        root = self.root / 'run'
        root.mkdir()
        plan = {'predecessor': str(self.root / 'previous'), 'predecessor_unit': 'previous.service'}
        with (patch.object(follow_on, 'validate_plan'), patch.object(follow_on, 'wait_predecessor', side_effect=Failure('transition_failed', 'uncertain cleanup')),
              patch.object(follow_on, 'prepare_model_artifacts') as prepare, patch.object(follow_on, 'run_cohort') as run,
              patch.object(follow_on.reporting, 'report')):
            with self.assertRaises(Failure):
                follow_on.execute(root, plan)
        prepare.assert_not_called()
        run.assert_not_called()


if __name__ == '__main__':
    unittest.main()
