"""Continuation must retain referenced evidence across more than one handoff."""
import argparse
import hashlib
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import pilot
from lifecycle import Failure, save


class ContextContinuationTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name).resolve()
        self.first = self.root / 'first'
        self.second = self.root / 'second'
        self.third = self.root / 'third'
        for directory in [self.first, self.second, self.third]:
            directory.mkdir()
            save(directory / 'manifest.json', pilot.MANIFEST)

    def cell(self, run, context, seed, interrupted=False):
        directory = run / 'context' / f'{context}-{seed}'
        directory.mkdir(parents=True)
        save(directory / 'cell.json', {'model': {'id': 'flash-iq4'}, 'context': context, 'seed': seed})
        save(directory / 'harness-hashes.json', pilot.harness_hashes())
        result = {'outcome': 'passed', 'protocol_pass': True, 'timing_valid': True}
        if interrupted:
            save(directory / 'result.json', {'outcome': 'interrupted'})
            save(directory / 'attempt/result.json', result)
        else:
            save(directory / 'result.json', result)
        return directory

    def test_inherited_and_local_attempts_survive_second_handoff(self):
        inherited = self.cell(self.first, 32768, 42, interrupted=True)
        save(self.second / 'context-results.json', pilot.recover_contexts(self.first, self.second))
        local = self.cell(self.second, 65536, 43)
        rows = pilot.recover_contexts(self.second, self.third)
        self.assertEqual({str(inherited), str(local)}, {row['path'] for row in rows})
        self.assertEqual({32768, 65536}, {row['context'] for row in rows})
        for row in rows:
            raw = Path(row['recovered_result_path']).read_bytes()
            self.assertEqual(hashlib.sha256(raw).hexdigest(), row['recovered_result_sha256'])
        self.assertEqual('interrupted', json.loads((inherited / 'result.json').read_text())['outcome'])

    def test_embedded_results_are_not_trusted_and_reference_identity_is_checked(self):
        directory = self.cell(self.first, 32768, 42)
        save(self.second / 'context-results.json', [{'path': str(directory), 'model': 'flash-iq4',
             'context': 32768, 'seed': 42, 'result': {'outcome': 'task_failed'}}])
        self.assertEqual('passed', pilot.recover_contexts(self.second, self.third)[0]['result']['outcome'])
        rows = json.loads((self.second / 'context-results.json').read_text())
        rows[0]['seed'] = 43
        save(self.second / 'context-results.json', rows)
        with self.assertRaisesRegex(Failure, 'reference differs'):
            pilot.recover_contexts(self.second, self.third)

    def test_inherited_hash_drift_and_duplicate_completed_identity_are_rejected(self):
        directory = self.cell(self.first, 32768, 42)
        rows = pilot.recover_contexts(self.first, self.second)
        save(self.second / 'context-results.json', rows)
        save(directory / 'result.json', {'outcome': 'task_failed'})
        with self.assertRaisesRegex(Failure, 'evidence changed'):
            pilot.recover_contexts(self.second, self.third)
        save(directory / 'result.json', {'outcome': 'passed', 'protocol_pass': True, 'timing_valid': True})
        self.cell(self.second, 32768, 42)
        with self.assertRaisesRegex(Failure, 'Duplicate completed'):
            pilot.recover_contexts(self.second, self.third)

    def test_context_only_stops_before_smoke_or_coding(self):
        output = self.root / 'context-only'
        proof = self.root / 'proof.json'
        save(proof, {'passed': True, 'harness_hashes': pilot.harness_hashes()})
        args = argparse.Namespace(output=output, deterministic=proof, model='flash-iq4',
                                  continue_context_from=None, context_only=True)
        with patch.object(pilot, 'experiment_lock'), patch.object(pilot, 'scan_contexts'), \
             patch.object(pilot, 'select_context', return_value=65536), patch.object(pilot, 'run_cell') as run_cell:
            pilot.run(args)
        run_cell.assert_not_called()
        self.assertEqual('context_complete', json.loads((output / 'status.json').read_text())['stage'])


if __name__ == '__main__':
    unittest.main()
