import json
import tempfile
import threading
import time
import unittest
from pathlib import Path

from inference_proxy import MODEL, RequestBook


class RequestPolicyTests(unittest.TestCase):
    def test_budgets_and_sampling_are_independent_per_worker(self):
        with tempfile.TemporaryDirectory() as temporary:
            book = RequestBook(['distinct', 'order', 'events'], Path(temporary) / 'requests.jsonl')
            request = {'model': MODEL, 'temperature': 0, 'stream': True, 'max_tokens': 90000}
            self.assertEqual(book.admit('distinct', request), 1)
            self.assertEqual(request['temperature'], 1.0)
            self.assertEqual(request['max_tokens'], 32768)
            self.assertEqual(request['stream_options'], {'include_usage': True})
            book.workers['distinct']['requests'] = 128
            with self.assertRaisesRegex(ValueError, 'budget_exhausted'):
                book.admit('distinct', {'model': MODEL})
            self.assertEqual(book.admit('order', {'model': MODEL}), 1)
            book.finish('events', None)
            with self.assertRaisesRegex(ValueError, 'budget_exhausted'):
                book.admit('events', {'model': MODEL})

    def test_token_budget_clamps_last_request_without_resetting(self):
        with tempfile.TemporaryDirectory() as temporary:
            book = RequestBook(['distinct'], Path(temporary) / 'requests.jsonl')
            book.finish('distinct', 199995)
            request = {'model': MODEL, 'max_tokens': 1000}
            book.admit('distinct', request)
            self.assertEqual(request['max_tokens'], 5)
            book.finish('distinct', 5)
            with self.assertRaises(ValueError):
                book.admit('distinct', {'model': MODEL})

    def test_barrier_holds_until_all_three_workers_are_ready(self):
        with tempfile.TemporaryDirectory() as temporary:
            book = RequestBook(['distinct', 'order', 'events'], Path(temporary) / 'requests.jsonl')
            released = []
            def arrive(name):
                book.barrier(name, timeout=2)
                released.append(name)
            threads = [threading.Thread(target=arrive, args=(name,)) for name in ['distinct', 'order']]
            for thread in threads:
                thread.start()
            time.sleep(.03)
            self.assertEqual(released, [])
            arrive('events')
            for thread in threads:
                thread.join(timeout=2)
                self.assertFalse(thread.is_alive())
            self.assertCountEqual(released, ['distinct', 'order', 'events'])
            rows = [json.loads(line) for line in book.log.read_text().splitlines()]
            self.assertEqual(sum(row['kind'] == 'barrier_release' for row in rows), 1)

    def test_partial_launch_never_releases_the_barrier(self):
        with tempfile.TemporaryDirectory() as temporary:
            book = RequestBook(['distinct', 'order', 'events'], Path(temporary) / 'requests.jsonl')
            with self.assertRaisesRegex(TimeoutError, 'barrier_timeout'):
                book.barrier('distinct', timeout=.01)
            self.assertIsNone(book.released_ns)
            with self.assertRaisesRegex(TimeoutError, 'barrier_failed'):
                book.barrier('order', timeout=.01)
            self.assertIsNone(book.released_ns)

    def test_wrong_model_is_rejected_before_admission(self):
        with tempfile.TemporaryDirectory() as temporary:
            book = RequestBook(['distinct'], Path(temporary) / 'requests.jsonl')
            with self.assertRaisesRegex(ValueError, 'unexpected_model'):
                book.admit('distinct', {'model': 'flash'})
            self.assertEqual(book.workers['distinct']['requests'], 0)


if __name__ == '__main__':
    unittest.main()
