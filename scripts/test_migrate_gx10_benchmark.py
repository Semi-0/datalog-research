"""Exercise migration safety against disposable source and destination trees."""
import hashlib
from pathlib import Path
import subprocess
import tempfile
import unittest

SCRIPT = Path(__file__).with_name('migrate_gx10_benchmark.clj').resolve()


class MigrationTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.source = self.root / 'kiroshi'
        self.destination = self.root / 'research'
        self.destination.mkdir()
        for relative in ['benchmark/gx10-context/fixture.py',
                         'benchmark/ornith-nvfp4-mtp/fixture.py',
                         'scripts/run-background.sh', 'scripts/run-background.md',
                         'scripts/gx10_protocol_v3.py']:
            path = self.source / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text('fixture\n')

    def run_script(self, *arguments):
        return subprocess.run(
            ['clojure', '-M', str(SCRIPT), '--source', str(self.source),
             '--destination', str(self.destination), *arguments],
            cwd=self.root, capture_output=True, text=True, timeout=60)

    def test_dry_run_apply_hashes_permissions_exclusions_and_repeat(self):
        harness = self.source / 'benchmark/gx10-context'
        (harness / '.gitignore').write_text('__pycache__/\n')
        (harness / 'runner.service').write_text('[Service]\n')
        (harness / '.env').write_text('PRIVATE=fixture\n')
        (harness / 'model.gguf').write_bytes(b'not a real model')
        (harness / 'results').mkdir()
        (harness / 'results/result.json').write_text('{}')
        launcher = self.source / 'scripts/run-background.sh'
        launcher.chmod(0o755)
        dry = self.run_script()
        self.assertEqual(0, dry.returncode, dry.stderr)
        self.assertEqual([], list(self.destination.iterdir()))
        applied = self.run_script('--apply')
        self.assertEqual(0, applied.returncode, applied.stderr)
        copied = self.destination / 'benchmark/gx10-context'
        for name in ['fixture.py', '.gitignore', 'runner.service']:
            self.assertEqual(hashlib.sha256((harness / name).read_bytes()).digest(),
                             hashlib.sha256((copied / name).read_bytes()).digest())
        for name in ['.env', 'model.gguf', 'results']:
            self.assertFalse((copied / name).exists())
        self.assertEqual(0o755, (self.destination / 'scripts/run-background.sh').stat().st_mode & 0o777)
        receipt = self.destination / 'benchmark/gx10-migration-manifest.edn'
        original_receipt = receipt.read_bytes()
        self.assertEqual(0, self.run_script('--apply').returncode)
        self.assertEqual(original_receipt, receipt.read_bytes())
        self.assertTrue(launcher.exists())

    def test_conflict_prevents_any_copy(self):
        conflict = self.destination / 'scripts/run-background.sh'
        conflict.parent.mkdir()
        conflict.write_text('local work')
        result = self.run_script('--apply')
        self.assertNotEqual(0, result.returncode)
        self.assertIn('Refusing to overwrite', result.stderr)
        self.assertFalse((self.destination / 'benchmark').exists())
        self.assertEqual('local work', conflict.read_text())

    def test_destination_symlink_is_rejected(self):
        outside = self.root / 'outside'
        outside.mkdir()
        (self.destination / 'benchmark').symlink_to(outside, target_is_directory=True)
        result = self.run_script('--apply')
        self.assertNotEqual(0, result.returncode)
        self.assertEqual([], list(outside.iterdir()))

    def test_source_symlink_is_rejected(self):
        (self.source / 'benchmark/gx10-context/link.py').symlink_to(SCRIPT)
        result = self.run_script('--apply')
        self.assertNotEqual(0, result.returncode)
        self.assertIn('Symbolic links', result.stderr)
        self.assertEqual([], list(self.destination.iterdir()))

    def test_overlapping_roots_and_missing_inputs_are_rejected(self):
        overlap = self.source / 'nested'
        overlap.mkdir()
        self.destination = overlap
        self.assertNotEqual(0, self.run_script('--apply').returncode)
        self.destination = self.root / 'research'
        (self.source / 'scripts/gx10_protocol_v3.py').unlink()
        result = self.run_script('--apply')
        self.assertNotEqual(0, result.returncode)
        self.assertIn('Required migration input', result.stderr)
        self.assertEqual([], list(self.destination.iterdir()))


if __name__ == '__main__':
    unittest.main()
