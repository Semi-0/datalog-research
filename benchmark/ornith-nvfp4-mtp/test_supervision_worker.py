"""A stopped real worker still invokes independent evaluation in a fresh checkout."""
import argparse
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

import coding
from supervision import ProgressSupervisor


class WorkerSupervisionTests(unittest.TestCase):
    @unittest.skipUnless(Path('/proc/self/status').exists(), 'Linux process ownership check')
    def test_agent_stall_preserves_evaluation_and_explicit_censored_outcome(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / 'source'
            source.mkdir()
            def git(*arguments):
                return subprocess.check_output(['git', '-C', str(source), *arguments], text=True,
                    stderr=subprocess.DEVNULL).strip()
            git('init')
            git('config', 'user.name', 'Benchmark Test')
            git('config', 'user.email', 'test@example.invalid')
            git('commit', '--allow-empty', '-m', 'fixture')
            pin = git('rev-parse', 'HEAD')
            binary = root / 'bin'
            binary.mkdir()
            pi = binary / 'pi'
            pi.write_text('#!' + sys.executable + '\nimport json,sys,time\n'
                          'if "--version" in sys.argv: print("fixture-pi"); sys.exit(0)\n'
                          'print(json.dumps({"type":"session","id":"fixture"}),flush=True)\n'
                          'time.sleep(30)\n')
            pi.chmod(0o755)
            for name in ['clojure', 'java', 'node']:
                executable = binary / name
                executable.write_text('#!/bin/sh\nprintf "Ran 1 tests containing 14 assertions.\\n0 failures, 0 errors.\\n"\n')
                executable.chmod(0o755)
            extension = root / 'extension.ts'
            extension.write_text('export default function() {}')
            output = root / 'attempt'
            args = argparse.Namespace(output=output, model='fixture-model', base_url='http://localhost/v1',
                context=131072, source=source, task='distinct', web_extension=extension, web_guard=extension,
                server_unit='unused-fixture.service')
            environment = coding.environment(output)
            environment['PATH'] = str(binary) + ':/usr/bin:/bin'
            policy = {'warn_no_progress_seconds': .05, 'steer_no_progress_seconds': .1,
                      'stop_no_progress_seconds': .15, 'initial_evidence_limit': 2,
                      'maximum_requests': 128, 'maximum_generated_tokens': 200000}
            memory = {'available': 100 * 2**30, 'reserve': 16 * 2**30, 'swap_in': 0, 'swap_out': 0}
            class Proxy:
                def __init__(self, *args, **kwargs):
                    self.base_url = 'http://localhost/v1'
                def totals(self):
                    return 0, 0
                def summary(self):
                    return {'requests': 0, 'records': []}
                def close(self):
                    pass
            with patch.object(coding, 'PIN', pin), patch.object(coding, 'environment', return_value=environment), \
                 patch.object(coding, 'prepare_vigilant', return_value=extension), \
                 patch.object(coding, 'prepare_gortex', return_value=extension), \
                 patch.object(coding, 'verify_gortex'), patch.object(coding, 'memory', return_value=memory), \
                 patch.object(coding, 'metrics_endpoint', return_value=('http://localhost/metrics', 'ollama')), \
                 patch.object(coding, 'InferenceProxy', Proxy), \
                 patch.object(coding, 'ProgressSupervisor', side_effect=lambda unused, started: ProgressSupervisor(policy, started)):
                coding.run(args)
            result = json.loads((output / 'result.json').read_text())
            self.assertEqual('stalled', result['execution_outcome'])
            self.assertEqual('passed', result['outcome'])
            self.assertLess(result['coding_wall_s'], 3)
            self.assertNotEqual(0, result['pi_exit'])
            self.assertTrue(result['acceptance_completed'])
            self.assertFalse(result['agent_end_observed'])
            self.assertFalse(result['workflow_pass'])
            self.assertFalse(result['timing_valid'])
            self.assertTrue(result['elapsed_censored'])
            self.assertEqual(0, result['acceptance_exit'])
            self.assertEqual(14, result['acceptance_counts']['assertions'])
            self.assertTrue(result['correct'])
            self.assertTrue((output / 'artifacts/changes.patch').exists())
            self.assertTrue((output / 'artifacts/supervision-stop.json').exists())


if __name__ == '__main__':
    unittest.main()
