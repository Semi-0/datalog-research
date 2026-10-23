import hashlib
import json
from pathlib import Path
import tempfile
import unittest
from unittest import mock
import structural_editing
import tooling
from lifecycle import Failure, save


class StructuralEditingTests(unittest.TestCase):
    def installation(self, base):
        manifest = base / 'manifest.json'
        profile = {'source_commit': 'pinned', 'tools': structural_editing.TOOLS}
        for field in ['binary', 'extension']:
            path = base / field
            path.write_text('fixture')
            profile[field] = str(path)
            profile[field + '_sha256'] = hashlib.sha256(path.read_bytes()).hexdigest()
        lock = base / 'package-lock.json'
        lock.write_text('{}')
        profile['npm_lock_sha256'] = hashlib.sha256(lock.read_bytes()).hexdigest()
        manifest.write_text(json.dumps(profile))
        return {'manifest': str(manifest), 'source_commit': 'pinned'}, profile

    def test_optional_profile_leaves_existing_tooling_unchanged(self):
        env = {'PATH': '/bin'}
        self.assertIsNone(structural_editing.configure(Path('/unused'), env, None))
        self.assertEqual({'PATH': '/bin'}, env)

    def test_pinned_artifacts_are_verified_and_recorded(self):
        with tempfile.TemporaryDirectory() as name:
            base = Path(name)
            config, expected = self.installation(base)
            env = {}
            profile = structural_editing.configure(base / 'agent', env, config)
            self.assertEqual(expected, profile)
            self.assertEqual(profile['binary'], env['CLJFORM_BIN'])
            self.assertEqual(profile, json.loads((base / 'agent/artifacts/structural-editing.json').read_text()))
            (base / 'binary').write_text('changed')
            with self.assertRaises(Failure):
                structural_editing.configure(base / 'retry', {}, config)

    def test_wrong_revision_or_dependency_lock_is_rejected(self):
        with tempfile.TemporaryDirectory() as name:
            base = Path(name)
            config, _ = self.installation(base)
            with self.assertRaises(Failure):
                structural_editing.configure(base / 'agent', {}, dict(config, source_commit='other'))
            (base / 'package-lock.json').write_text('changed')
            with self.assertRaises(Failure):
                structural_editing.configure(base / 'agent', {}, config)

    def test_missing_structural_tool_blocks_generation_admission(self):
        with tempfile.TemporaryDirectory() as name:
            root = Path(name)
            save(root / 'artifacts/tool-profile.json', {'role': 'controller', 'structural_tools': structural_editing.TOOLS})
            path = root / 'artifacts/tooling.jsonl'
            def audit(names):
                path.write_text(json.dumps({'kind': 'ready', 'tools': names}) + '\n' +
                                json.dumps({'kind': 'sampling', 'temperature': 1.0}) + '\n')
            audit(['web_search', 'fetch_content', 'clj_tree'])
            with self.assertRaises(Failure):
                tooling.require_ready(root)
            audit(['web_search', 'fetch_content'] + structural_editing.TOOLS)
            tooling.require_ready(root)
