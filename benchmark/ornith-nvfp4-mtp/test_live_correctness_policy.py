"""Opt-in real Pi regression: post-run workflow failure must not skip evaluation."""
import os
import unittest
from unittest import mock
import coding
from lifecycle import Failure
import test_live_solo


@unittest.skipUnless(os.environ.get('GX10_LIVE_SOLO') == '1', 'Requires installed GX10 Pi tools')
class LiveCorrectnessPolicyTests(unittest.TestCase):
    def test_workflow_failure_still_runs_independent_evaluator(self):
        captured = []
        classify = coding.classify

        def capture(result, own, acceptance):
            value = classify(result, own, acceptance)
            captured.append(dict(value))
            return value

        fixture = test_live_solo.LiveSoloTests('test_real_solo_passes_tools_own_tests_and_independent_evaluator')
        # The original fixture requires full workflow compliance. Its final
        # assertion may fail deliberately; inspect the actual runner result here.
        with mock.patch('tooling.verify', side_effect=Failure('task_failed', 'Deliberately omitted Lens diagnostics')), \
             mock.patch.object(coding, 'classify', side_effect=capture):
            try:
                fixture.exercise()
            except AssertionError:
                if not captured:
                    raise
            except FileNotFoundError as error:
                # The legacy fixture expects the success-only audit file that
                # our deliberately failing verification must not create.
                if not captured or not error.filename.endswith('/tooling-verification.json'):
                    raise
        self.assertEqual(1, len(captured))
        result = captured[0]
        self.assertEqual('passed', result['outcome'])
        self.assertTrue(result['correct'])
        self.assertEqual(14, result['acceptance_counts']['assertions'])
        self.assertFalse(result['workflow_pass'])
        self.assertFalse(result['protocol_pass'])
        self.assertIn('Deliberately omitted Lens', result['workflow_diagnostics'][0]['error'])
