"""Real Pi with both pinned guards and scripted streaming inference."""
import os
import unittest
import test_pi_runtime as wire


def response(repeating):
    def chunk(delta, reason=None):
        return {'id': 'mock', 'object': 'chat.completion.chunk', 'created': 1, 'model': 'mock',
                'choices': [{'index': 0, 'delta': delta, 'finish_reason': reason}]}
    events = [chunk({'role': 'assistant'})]
    if repeating:
        events += [chunk({'reasoning_content': ', Db'}) for _ in range(1000)]
    else:
        events += [chunk({'reasoning_content': 'I verified the finite fixture and will report its result.'})]
    events += [chunk({'content': 'Complete'}), chunk({}, 'stop')]
    return events


@unittest.skipUnless(wire.PI.exists() and os.environ.get('GX10_LIVE_ANTI_REPEAT') == '1', 'Opt-in real Pi anti-repeat checks')
class AntiRepeatWireTests(unittest.TestCase):
    def invoke(self, behavior):
        fixture = wire.PiWireIntegrationTests('runTest')
        return fixture.invoke(behavior, vigilant=True)

    def test_first_stream_loop_is_aborted_then_corrected(self):
        requests, events, audit = self.invoke(lambda count, body: response(count == 1))
        self.assertEqual(len(requests), 2)
        self.assertEqual(sum(row.get('kind') == 'anti_repeat' and row.get('type') == 'corrected' for row in audit), 1)
        self.assertFalse(any(row.get('kind') == 'anti_repeat' and row.get('type') == 'stopped' for row in audit))
        self.assertTrue(any(row.get('kind') == 'anti_repeat' and row.get('type') == 'vigilant_hook_suppressed' for row in audit))
        endings = [row['message'] for row in events if row.get('type') == 'message_end' and row.get('message', {}).get('role') == 'assistant']
        self.assertEqual(endings[-1]['stopReason'], 'stop')

    def test_second_loop_stops_without_vigilant_resurrection(self):
        requests, events, audit = self.invoke(lambda count, body: response(True))
        self.assertEqual(len(requests), 2)
        self.assertEqual(sum(row.get('kind') == 'anti_repeat' and row.get('type') == 'corrected' for row in audit), 1)
        self.assertEqual(sum(row.get('kind') == 'anti_repeat' and row.get('type') == 'stopped' for row in audit), 1)

    def test_normal_thinking_is_unchanged(self):
        requests, events, audit = self.invoke(lambda count, body: response(False))
        self.assertEqual(len(requests), 1)
        self.assertFalse(any(row.get('kind') == 'anti_repeat' and row.get('type') == 'detected' for row in audit))


if __name__ == '__main__':
    unittest.main()
