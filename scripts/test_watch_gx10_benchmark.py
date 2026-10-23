"""Completion, scope and publication safety for the overnight watcher."""
import hashlib
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import watch_gx10_benchmark as watch


class WatcherTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)

    def proof(self, outcome='task_failed'):
        queue = self.root / 'queue'
        stages = []
        for name, filename in [('deterministic', 'deterministic.json'), ('flash-pilot', 'pilot.json'),
                               ('live-validation', 'validation.json'), ('matrix', 'summary.json')]:
            directory = queue / name
            value = {'passed': True}
            if name == 'matrix':
                value = {'rows': [{'phase': 'coding', 'model': 'flash', 'task': 'distinct',
                                   'result': {'outcome': outcome}}]}
            path = directory / filename
            watch.write_json(path, value)
            stages.append({'name': name, 'state': 'complete', 'output': '/remote/queue/' + name,
                           'evidence': {'path': '/remote/queue/' + name + '/' + filename,
                                        'sha256': hashlib.sha256(path.read_bytes()).hexdigest()}})
        watch.write_json(queue / 'status.json', {'stage': 'complete'})
        watch.write_json(queue / 'queue.json', stages)
        return queue

    def test_genuine_task_failure_is_data_but_runtime_failure_blocks_publication(self):
        queue = self.proof()
        self.assertEqual(queue / 'matrix', watch.verified_matrix(queue, {('flash', 'distinct')}))
        for outcome in ['runtime_error', 'interrupted', 'transition_failed', 'unknown']:
            queue = self.proof(outcome)
            with self.assertRaisesRegex(RuntimeError, 'Unresolved outcome'):
                watch.verified_matrix(queue, {('flash', 'distinct')})

    def test_incomplete_cells_and_changed_proofs_block_publication(self):
        queue = self.proof()
        with self.assertRaisesRegex(RuntimeError, 'every coding cell'):
            watch.verified_matrix(queue, {('flash', 'distinct'), ('other', 'distinct')})
        (queue / 'deterministic/deterministic.json').write_text('{}')
        with self.assertRaisesRegex(RuntimeError, 'evidence hash changed'):
            watch.verified_matrix(queue, {('flash', 'distinct')})

    def test_publication_preserves_unrelated_files_and_pushes_only_scoped_changes(self):
        repo = self.root / 'repo'
        repo.mkdir()
        remote = self.root / 'remote.git'
        watch.command(['git', 'init', '--bare', str(remote)])
        watch.command(['git', 'init'], cwd=repo)
        watch.command(['git', 'config', 'user.email', 'test@example.invalid'], cwd=repo)
        watch.command(['git', 'config', 'user.name', 'Watcher Test'], cwd=repo)
        watch.command(['git', 'commit', '--allow-empty', '-m', 'baseline'], cwd=repo)
        watch.command(['git', 'remote', 'add', 'origin', str(remote)], cwd=repo)
        source = repo / 'benchmark/gx10-context/config.json'
        watch.write_json(source, {'protocol_version': 5})
        unrelated = repo / 'unrelated.txt'
        unrelated.write_text('preserve me')
        destination = repo / 'benchmark/gx10-context/results/completed'
        watch.write_json(destination / 'summary.json', {'complete': True})
        original = watch.fingerprints(repo)
        head = watch.command(['git', 'rev-parse', 'HEAD'], cwd=repo)
        commit = watch.publish(repo, destination, original, head)
        self.assertEqual(commit, watch.command(['git', 'rev-parse', 'HEAD'], cwd=repo))
        self.assertEqual('preserve me', unrelated.read_text())
        self.assertEqual('?? unrelated.txt', watch.command(['git', 'status', '--short'], cwd=repo))
        self.assertIn(commit, watch.command(['git', '--git-dir', str(remote), 'show-ref']))
        source.write_text('changed during monitoring')
        with self.assertRaisesRegex(RuntimeError, 'source or HEAD changed'):
            watch.publish(repo, destination, original, head)

    def test_failed_queue_is_collected_without_publication(self):
        import argparse
        repo = self.root / 'repo'
        watch.write_json(repo / 'benchmark/gx10-context/config.json',
                         {'models': [{'id': 'flash'}], 'coding': {'tasks': ['distinct']}})
        args = argparse.Namespace(repo=repo, receipt=self.root / 'watch.json',
            run_root='/home/lain/experiments/gx10-test-queue', publish=True)
        snapshot = json.dumps({'status.json': {'stage': 'failed'},
                               'failure.json': {'outcome': 'runtime_error'}})
        with patch.object(watch, 'command', side_effect=['head', '']), \
             patch.object(watch, 'remote', return_value=snapshot), \
             patch.object(watch, 'collect') as collect, patch.object(watch, 'publish') as publish:
            watch.run(args)
        collect.assert_called_once()
        publish.assert_not_called()
        self.assertEqual('blocked', json.loads(args.receipt.read_text())['state'])
        self.assertTrue(args.receipt.with_suffix('.source.json').exists())

    def test_existing_staged_changes_are_never_committed(self):
        with patch.object(watch, 'fingerprints', return_value={}), \
             patch.object(watch, 'command', side_effect=['head', 'unrelated.txt']) as command:
            with self.assertRaisesRegex(RuntimeError, 'Existing staged changes'):
                watch.publish(self.root, self.root, {}, 'head')
        self.assertEqual(2, command.call_count)


if __name__ == '__main__':
    unittest.main()
