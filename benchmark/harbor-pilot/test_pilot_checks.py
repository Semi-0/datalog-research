import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
from urllib.error import HTTPError

from pilot_checks import backend_key, verify_connection, pi_execution, verifier_counts


class PilotChecksTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.trial = self.root / 'trial'
        (self.trial / 'agent').mkdir(parents=True)
        (self.trial / 'verifier').mkdir()
        (self.trial / 'result.json').write_text(json.dumps({'trial_name': 'trial',
            'verifier_result': {'rewards': {'reward': 1.0}}}))

    def transcript(self, reason='stop', tokens=17, error=None, settled=True):
        message = {'role': 'assistant', 'stopReason': reason, 'usage': {'output': tokens}}
        if error is not None:
            message['errorMessage'] = error
        rows = [{'type': 'message_end', 'message': message}, {'type': 'agent_end'}]
        if settled:
            rows.append({'type': 'agent_settled', 'aborted': False})
        (self.trial / 'agent/pi.txt').write_text(''.join(json.dumps(row) + '\n' for row in rows))

    def test_credential_comes_from_backend_command(self):
        (self.root / 'server-command.json').write_text(json.dumps([
            'server', '--served-model-name', 'qwen', '--api-key', 'actual-local-key']))
        self.assertEqual('actual-local-key', backend_key(self.root, 'qwen'))
        with self.assertRaises(RuntimeError):
            backend_key(self.root, 'wrong-model')
        (self.root / 'server-command.json').write_text(json.dumps(['server']))
        with self.assertRaises(RuntimeError):
            backend_key(self.root, 'qwen')

    def test_authenticated_and_rejected_credentials_are_both_checked(self):
        data = io.BytesIO(json.dumps({'data': [{'id': 'qwen'}]}).encode())
        rejected = HTTPError('http://127.0.0.1:8001/v1/models', 401, 'Unauthorized', {}, io.BytesIO())
        with patch('pilot_checks.urlopen', side_effect=[data, rejected]) as opened:
            receipt = verify_connection('http://127.0.0.1:8001/v1', 'qwen', 'actual-local-key')
        self.assertEqual('Bearer actual-local-key', opened.call_args_list[0].args[0].get_header('Authorization'))
        self.assertEqual(401, receipt['invalid_credential_status'])
        self.assertEqual(200, receipt['authenticated_models_status'])

    def test_wrong_served_model_and_remote_endpoint_are_rejected(self):
        with patch('pilot_checks.urlopen', return_value=io.BytesIO(b'{"data":[{"id":"wrong"}]}')):
            with self.assertRaises(RuntimeError):
                verify_connection('http://127.0.0.1:8001/v1', 'qwen', 'key')
        with patch('pilot_checks.urlopen') as opened:
            with self.assertRaises(RuntimeError):
                verify_connection('https://example.com/v1', 'qwen', 'key')
            opened.assert_not_called()

    def test_reward_and_settlement_do_not_hide_authentication_error(self):
        self.transcript('error', 0, '401 Unauthorized')
        execution = pi_execution(self.root)
        self.assertEqual('authentication_error', execution['outcome'])
        self.assertEqual(0, execution['generated_tokens'])
        self.assertTrue(execution['agent_settled'])

    def test_normal_generated_response_completes(self):
        self.transcript()
        execution = pi_execution(self.root)
        self.assertEqual('completed', execution['outcome'])
        self.assertEqual(17, execution['generated_tokens'])

    def test_incomplete_aborted_and_zero_token_responses_are_not_success(self):
        for reason, tokens, settled, expected in [
            ('length', 17, True, 'incomplete_response'), ('aborted', 17, True, 'interrupted'),
            ('stop', 17, False, 'incomplete_response'), ('stop', 0, True, 'incomplete_response')]:
            with self.subTest(reason=reason, tokens=tokens, settled=settled):
                self.transcript(reason, tokens, settled=settled)
                self.assertEqual(expected, pi_execution(self.root)['outcome'])

    def test_malformed_transcript_is_not_ignored(self):
        (self.trial / 'agent/pi.txt').write_text('{"type":')
        with self.assertRaises(json.JSONDecodeError):
            pi_execution(self.root)

    def test_four_verifier_assertions_are_required(self):
        log = self.trial / 'verifier/clojure.log'
        log.write_text('Ran 1 tests containing 4 assertions.\n0 failures, 0 errors.\n')
        self.assertEqual({'tests': 1, 'assertions': 4, 'failures': 0, 'errors': 0}, verifier_counts(self.root))
        log.write_text('Ran 0 tests containing 0 assertions.\n0 failures, 0 errors.\n')
        with self.assertRaises(RuntimeError):
            verifier_counts(self.root)


if __name__ == '__main__':
    unittest.main()
