"""Meaningful-progress policy, separate from agent execution and wall-clock deadlines."""
import hashlib
import re
import threading


class ProgressSupervisor:
    def __init__(self, policy, started):
        self._policy = policy
        self._last_progress = started
        self._phase = 0
        self._evidence = set()
        self._best_tests = None
        self._lock = threading.Lock()

    def observe_tool(self, name, text, failed, now):
        reason = None
        with self._lock:
            counts = re.findall(r'Ran (\d+) tests containing (\d+) assertions.*?(\d+) failures, (\d+) errors', text, re.S)
            if counts:
                tests, assertions, failures, errors = map(int, counts[-1])
                score = (errors + failures, -assertions)
                if tests > 0 and assertions > 0 and (self._best_tests is None or score < self._best_tests):
                    self._best_tests = score
                    reason = 'new_or_improved_assertion_result'
            elif name in ['read', 'read_file', 'get_symbol_source', 'explore', 'web_search', 'fetch_content'] and not failed:
                digest = hashlib.sha256(text.encode()).hexdigest()
                if text and digest not in self._evidence and len(self._evidence) < self._policy['initial_evidence_limit']:
                    self._evidence.add(digest)
                    reason = 'bounded_new_reference_evidence'
            if reason is not None:
                self._last_progress = now
                self._phase = 0
            return reason

    def check(self, now, requests, generated_tokens):
        with self._lock:
            idle = now - self._last_progress
            if requests >= self._policy['maximum_requests'] or generated_tokens >= self._policy['maximum_generated_tokens']:
                return {'action': 'stop', 'outcome': 'budget_exhausted', 'idle_s': idle,
                        'requests': requests, 'generated_tokens': generated_tokens}
            if idle >= self._policy['stop_no_progress_seconds']:
                return {'action': 'stop', 'outcome': 'stalled', 'idle_s': idle,
                        'reason': 'No qualifying evidence or improved assertion result; elapsed time does not prove nontermination'}
            if idle >= self._policy['steer_no_progress_seconds'] and self._phase < 2:
                self._phase = 2
                return {'action': 'steer', 'idle_s': idle}
            if idle >= self._policy['warn_no_progress_seconds'] and self._phase < 1:
                self._phase = 1
                return {'action': 'warn', 'idle_s': idle}
            return None
