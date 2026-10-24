import importlib.util
import unittest
from pathlib import Path

spec = importlib.util.spec_from_file_location('runtime_bwrap', Path(__file__).with_name('runtime-bwrap.py'))
runtime = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runtime)


class RuntimeMountTests(unittest.TestCase):
    def test_adds_only_fixed_read_only_runtime_paths_before_the_command(self):
        original = ['--tmpfs', '/', '--bind', '/app', '/app', '--', 'node', 'worker.mjs']
        result = runtime.arguments(original, exists=lambda _path: True)
        command = result.index('--')
        self.assertEqual(result[:5], original[:5])
        self.assertEqual(result[command:], ['--', 'node', 'worker.mjs'])
        added = result[5:command]
        self.assertNotIn('--bind', added)
        self.assertEqual(added[::3], ['--ro-bind'] * len(runtime.RUNTIME_READS))
        self.assertNotIn('/home', added)
        self.assertNotIn('/etc', added)

    def test_version_and_unavailable_paths_are_preserved(self):
        self.assertEqual(runtime.arguments(['--version']), ['--version'])
        original = ['--tmpfs', '/', '--', 'node']
        self.assertEqual(runtime.arguments(original, exists=lambda _path: False), original)


if __name__ == '__main__':
    unittest.main()
