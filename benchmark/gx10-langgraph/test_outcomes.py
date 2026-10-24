import unittest
from outcomes import Execution, assessment, normalize, retryable


def normal_events():
    return [{'type': 'provider_request'},
            {'type': 'message_end', 'message': {'role': 'assistant', 'stopReason': 'stop'}},
            {'type': 'agent_end'}, {'type': 'agent_settled', 'aborted': False}]


class OutcomeTests(unittest.TestCase):
    def test_normal_execution(self):
        result = normalize(normal_events(), 0)
        self.assertEqual('completed', result.outcome)
        self.assertEqual(1, result.requests)

    def test_authentication_error_is_not_hidden_by_exit_zero_or_settlement(self):
        events = normal_events()
        events.insert(1, {'type': 'message_end', 'message': {
            'role': 'assistant', 'stopReason': 'error', 'errorMessage': '401 Unauthorized'}})
        result = normalize(events, 0)
        self.assertEqual('authentication_error', result.outcome)
        self.assertFalse(retryable(result, 1))
        self.assertEqual('authentication_error', assessment(result, 1, 14, 0, True, True)['execution'])

    def test_length_and_truncated_response_are_incomplete(self):
        events = normal_events()
        events[1]['message']['stopReason'] = 'length'
        self.assertEqual('incomplete_response', normalize(events, 0).outcome)
        self.assertEqual('incomplete_response', normalize(events[:1], 0).outcome)

    def test_interruption_is_not_success(self):
        events = normal_events()
        events[-1]['aborted'] = True
        self.assertEqual('interrupted', normalize(events, 0).outcome)
        self.assertEqual('runtime_error', normalize(normal_events(), -15).outcome)

    def test_zero_assertions_and_failed_verifier_are_invalid(self):
        result = Execution('completed', 'ok', 1)
        self.assertEqual('invalid_verification', assessment(result, 1, 0, 0, True, True)['correctness'])
        self.assertEqual('invalid_verification', assessment(result, 1, 14, 1, True, True)['correctness'])

    def test_wrong_implementation_stays_wrong(self):
        result = assessment(Execution('completed', 'ok', 1), 0, 14, 0, True, True)
        self.assertEqual('failed', result['correctness'])
        self.assertTrue(result['workflow_valid'])
        self.assertTrue(result['timing_valid'])

    def test_retry_budget_is_two_after_initial_attempt(self):
        result = Execution('transient_provider_error', '503', 1)
        self.assertTrue(retryable(result, 1))
        self.assertTrue(retryable(result, 2))
        self.assertFalse(retryable(result, 3))
        self.assertFalse(retryable(Execution('provider_error', 'unknown', 1), 1))


if __name__ == '__main__':
    unittest.main()
