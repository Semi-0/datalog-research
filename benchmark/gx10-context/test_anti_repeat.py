import hashlib
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import anti_repeat
from lifecycle import Failure


class AntiRepeatPreparationTests(unittest.TestCase):
    def fixture(self, root):
        source = root / 'package'; source.mkdir()
        (source / 'index.ts').write_text('pinned fixture')
        manifest = {'name': 'pi-anti-repeat', 'version': '0.2.1', 'source': str(source),
                    'files': {'index.ts': hashlib.sha256(b'pinned fixture').hexdigest()}}
        path = root / 'manifest.json'; path.write_text(json.dumps(manifest))
        policy = {'manifest': str(path), 'manifest_sha256': hashlib.sha256(path.read_bytes()).hexdigest(), 'version': '0.2.1'}
        attempt = root / 'attempt'; (attempt / 'agent/extensions').mkdir(parents=True)
        (attempt / 'artifacts').mkdir()
        (attempt / 'agent/benchmark-policy.json').write_text('{}')
        return attempt, source, policy

    def test_verified_package_is_copied_and_required(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory); attempt, source, policy = self.fixture(root)
            entry = anti_repeat.prepare(attempt, policy)
            self.assertTrue(entry.is_file())
            self.assertEqual((attempt / 'agent/extensions/pi-anti-repeat/index.ts').read_text(), 'pinned fixture')
            self.assertTrue(json.loads((attempt / 'agent/benchmark-policy.json').read_text())['anti_repeat_required'])
            with self.assertRaises(Failure):
                anti_repeat.require_ready(attempt)
            # Tool-profile construction can replace policy fields; the prepared-package
            # receipt still makes guard readiness mandatory.
            (attempt / 'agent/benchmark-policy.json').write_text('{}')
            with self.assertRaises(Failure):
                anti_repeat.require_ready(attempt)
            (attempt / 'artifacts/anti-repeat-ready.json').write_text(json.dumps({
                'version': '0.2.1', 'watching': True, 'vigilant_coordination': True}))
            anti_repeat.require_ready(attempt)

    def test_changed_package_is_rejected_before_loading(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory); attempt, source, policy = self.fixture(root)
            (source / 'index.ts').write_text('tampered')
            with self.assertRaises(RuntimeError):
                anti_repeat.prepare(attempt, policy)
            self.assertFalse((attempt / 'agent/extensions/anti-repeat.ts').exists())

    def test_changed_manifest_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory); attempt, source, policy = self.fixture(root)
            Path(policy['manifest']).write_text('{}')
            with self.assertRaises(RuntimeError):
                anti_repeat.prepare(attempt, policy)

    def test_old_profile_remains_optional(self):
        with tempfile.TemporaryDirectory() as directory:
            anti_repeat.require_ready(Path(directory))


if __name__ == '__main__':
    unittest.main()
