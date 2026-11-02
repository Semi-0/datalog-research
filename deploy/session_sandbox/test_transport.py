"""Focused transport checks; these do not certify live Linux containment."""
import importlib.util
import os
from pathlib import Path
import subprocess
import sys
import time
import unittest

SPEC = importlib.util.spec_from_file_location("sandbox_manager", Path(__file__).with_name("manager.py"))
manager = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(manager)

ECHO = r'''
import sys
while True:
    line = sys.stdin.buffer.readline()
    if not line:
        break
    headers = {}
    while True:
        line = sys.stdin.buffer.readline()
        if line == b"\r\n":
            break
        name, value = line.decode().split(":", 1)
        headers[name.lower()] = value.strip()
    body = sys.stdin.buffer.read(int(headers["content-length"]))
    assert headers["authorization"] == "Bearer own-key"
    sys.stdout.buffer.write(b"HTTP/1.1 200 OK\r\nContent-Length: " + str(len(body)).encode() + b"\r\nConnection: close\r\n\r\n" + body)
    sys.stdout.buffer.flush()
'''


class TransportTests(unittest.TestCase):
    def child(self, code):
        process = subprocess.Popen([sys.executable, "-I", "-u", "-c", code],
                                   stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                   stderr=subprocess.PIPE, bufsize=0)
        os.set_blocking(process.stdin.fileno(), False)
        self.addCleanup(self.close_child, process)
        return process

    @staticmethod
    def close_child(process):
        if process.poll() is None:
            process.kill()
        process.wait(timeout=3)
        for pipe in (process.stdin, process.stdout, process.stderr):
            pipe.close()

    def test_sequential_http_frames_and_large_body(self):
        process = self.child(ECHO)
        for body in (b'{"op":"sandbox-status"}', b"x" * (1024 * 1024), b"last"):
            status, result = manager.pipe_exchange(process, "own-key", body)
            self.assertEqual(status, 200)
            self.assertEqual(result, body)
        self.assertIsNone(process.poll())

    def test_read_deadline_does_not_wait_for_a_broken_child(self):
        read, write = os.pipe()
        self.addCleanup(os.close, read)
        self.addCleanup(os.close, write)
        reader = manager.DeadlinePipe(read, time.monotonic() - 1)
        with self.assertRaises(TimeoutError):
            reader.readinto(bytearray(16))

    def test_response_requires_bounded_content_length(self):
        process = self.child('import sys; sys.stdin.buffer.read(1); sys.stdout.buffer.write(b"HTTP/1.1 200 OK\\r\\nConnection: close\\r\\n\\r\\n"); sys.stdout.buffer.flush()')
        with self.assertRaisesRegex(ValueError, "framing"):
            manager.pipe_exchange(process, "own-key", b"{}")

    def test_response_rejects_oversized_length_before_reading_body(self):
        process = self.child('import sys; sys.stdin.buffer.read(1); sys.stdout.buffer.write(b"HTTP/1.1 200 OK\\r\\nContent-Length: 999999999\\r\\n\\r\\n"); sys.stdout.buffer.flush()')
        with self.assertRaisesRegex(ValueError, "too large"):
            manager.pipe_exchange(process, "own-key", b"{}")

    def test_truncated_body_is_not_success(self):
        process = self.child('import sys; sys.stdin.buffer.read(1); sys.stdout.buffer.write(b"HTTP/1.1 200 OK\\r\\nContent-Length: 10\\r\\n\\r\\nshort"); sys.stdout.buffer.flush()')
        with self.assertRaisesRegex(ValueError, "Truncated"):
            manager.pipe_exchange(process, "own-key", b"{}")


if __name__ == "__main__":
    unittest.main()
