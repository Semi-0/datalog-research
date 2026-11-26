import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


class TimingTests(unittest.TestCase):
    def test_appends_start_and_finish_and_preserves_command_result(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "logs" / "timings.jsonl"
            for code in [0, 17]:
                result = subprocess.run(
                    [sys.executable, str(Path(__file__).with_name("ci_timing.py")),
                     "test", sys.executable, "-c", f"raise SystemExit({code})"],
                    env=dict(os.environ, LAIN_CI_TIMINGS=str(path)), capture_output=True)
                self.assertEqual(result.returncode, code)
            events = [json.loads(line) for line in path.read_text().splitlines()]
            self.assertEqual([r["event"] for r in events], ["start", "finish"] * 2)
            self.assertEqual([r["status"] for r in events[1::2]], ["passed", "failed"])
            self.assertEqual([r["exit_code"] for r in events[1::2]], [0, 17])
            self.assertTrue(all(r["elapsed_ms"] >= 0 for r in events[1::2]))
