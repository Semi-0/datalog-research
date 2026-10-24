import json
from pathlib import Path
import shutil
import tempfile
import tomllib
import unittest
from unittest.mock import patch

import build
import verifier

SOURCE = Path(__file__).resolve().parent.parent / 'gx10-context'
IMAGE = 'eclipse-temurin@sha256:' + 'a' * 64


class MigrationTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.prefix = self.root / 'runtime'
        paths = ['bin/clojure', 'lib/clojure/deps.edn', 'lib/clojure/example-deps.edn',
                 'lib/clojure/tools.edn', 'lib/clojure/libexec/exec.jar',
                 'lib/clojure/libexec/clojure-tools-' + build.CLI_VERSION + '.jar']
        for name in paths:
            build.write(self.prefix / name, 'fixture')
        build.write(self.prefix / 'bin/clojure', 'install_dir=' + str(self.prefix / 'lib/clojure') + '\n')

    def package(self):
        output = self.root / 'tasks'
        return output, build.build(SOURCE, output, self.prefix, IMAGE)

    def test_original_contracts_evaluators_and_counts_are_preserved(self):
        output, manifest = self.package()
        self.assertEqual(['distinct', 'order', 'events'], [row['task']['id'] for row in manifest['tasks']])
        for row in manifest['tasks']:
            task = row['task']
            directory = output / task['id']
            self.assertEqual(build.EXPECTED[task['id']], row['expected_assertions'])
            self.assertEqual((SOURCE / 'fixtures' / task['acceptance']).read_bytes(),
                             (directory / 'tests' / task['acceptance']).read_bytes())
            self.assertEqual(row['evaluator_sha256'], build.sha(directory / 'tests' / task['acceptance']))
            config = tomllib.loads((directory / 'task.toml').read_text())
            self.assertNotIn('timeout_sec', config['agent'])
            self.assertEqual('separate', config['verifier']['environment_mode'])
            self.assertEqual('/app', config['artifacts'][0]['source'])
            self.assertFalse(any(path.name == task['acceptance'] for path in (directory / 'environment').rglob('*')))
            self.assertNotIn('environment/solution', manifest['package_hashes'])
            frozen = json.loads((directory / 'tests/scaffold.json').read_text())
            self.assertNotIn(task['path'], frozen)
            self.assertNotIn(task['test_path'], frozen)
            self.assertIn('deps.edn', frozen)
            self.assertIn('benchmark/harbor_test_runner.clj', frozen)
            launcher = (directory / 'environment/clojure/bin/clojure').read_text()
            self.assertIn('/opt/clojure/lib/clojure', launcher)
            self.assertNotIn(str(self.prefix), launcher)

    def test_packages_are_locked_and_existing_output_is_never_overwritten(self):
        output, manifest = self.package()
        for name, expected in manifest['package_hashes'].items():
            self.assertEqual(expected, build.sha(output / name))
        with self.assertRaises(FileExistsError):
            build.build(SOURCE, output, self.prefix, IMAGE)
        for name, expected in manifest['package_hashes'].items():
            self.assertEqual(expected, build.sha(output / name))

    def source_copy(self):
        copied = self.root / 'source'
        copied.mkdir()
        for name in ['tasks.json', 'config.json', 'evaluator-manifest.json']:
            shutil.copyfile(SOURCE / name, copied / name)
        for name in ['fixtures', 'prompts', 'validation-fixtures']:
            shutil.copytree(SOURCE / name, copied / name)
        return copied

    def test_evaluator_drift_declines_before_writing_output(self):
        source = self.source_copy()
        evaluator = source / 'fixtures/distinct-acceptance.clj'
        evaluator.write_text(evaluator.read_text() + '\n; drift\n')
        output = self.root / 'declined'
        with self.assertRaisesRegex(ValueError, 'hash drift'):
            build.build(source, output, self.prefix, IMAGE)
        self.assertFalse(output.exists())

    def test_unpinned_runtime_and_path_traversal_are_rejected(self):
        with self.assertRaises(ValueError):
            build.build(SOURCE, self.root / 'declined', self.prefix, 'eclipse-temurin:latest')
        source = self.source_copy()
        tasks = json.loads((source / 'tasks.json').read_text())
        tasks[0]['path'] = '../outside.clj'
        (source / 'tasks.json').write_text(json.dumps(tasks))
        with self.assertRaisesRegex(ValueError, 'relative'):
            build.build(source, self.root / 'declined', self.prefix, IMAGE)

    def test_scope_allows_owned_files_and_detects_scaffold_changes(self):
        project = self.root / 'project'
        build.write(project / 'deps.edn', 'frozen')
        build.write(project / 'owned.clj', 'first')
        frozen = {'deps.edn': build.sha(project / 'deps.edn')}
        build.write(project / 'owned.clj', 'changed')
        self.assertEqual([], verifier.scope_violations(project, frozen, ['owned.clj']))
        build.write(project / 'deps.edn', 'tampered')
        build.write(project / 'unassigned.clj', 'extra')
        (project / 'link.clj').symlink_to(project / 'owned.clj')
        violations = verifier.scope_violations(project, frozen, ['owned.clj'])
        self.assertIn('Changed or missing scaffold: deps.edn', violations)
        self.assertIn('Unassigned file: unassigned.clj', violations)
        self.assertIn('Symlink: link.clj', violations)

    def test_counts_require_one_unambiguous_summary(self):
        text = 'Ran 3 tests containing 42 assertions.\n0 failures, 0 errors.\n'
        self.assertEqual(42, verifier.assertion_counts(text)['assertions'])
        self.assertIsNone(verifier.assertion_counts('exit=0'))
        self.assertIsNone(verifier.assertion_counts(text + text))

    def test_zero_assertions_and_timeouts_cannot_pass(self):
        output, manifest = self.package()
        task = output / 'distinct'
        logs = self.root / 'logs'
        logs.mkdir()
        for code, counts in [(0, {'tests': 0, 'assertions': 0, 'failures': 0, 'errors': 0}),
                             (124, None)]:
            with self.subTest(code=code):
                result = {'exit': code, 'timed_out': code == 124, 'wall_s': 0, 'counts': counts}
                with patch('verifier.finite_run', return_value=result):
                    checked = verifier.verify(task / 'tests', task / 'environment/project', logs)
                self.assertFalse(checked['verification_valid'])
                self.assertEqual(0, checked['reward'])


if __name__ == '__main__':
    unittest.main()
