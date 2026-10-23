import json
import os
from pathlib import Path
import sys
import subprocess
import tempfile
import time
import unittest
from unittest.mock import patch

import coding
import resource_guard


class IsolationAndResourceTests(unittest.TestCase):
    @unittest.skipUnless(Path('/proc/self/status').exists(), 'Linux process ownership check')
    def test_execution_deadline_stops_owned_agent_and_preserves_unrelated_process(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            agent = subprocess.Popen([sys.executable, '-c', 'import time; time.sleep(60)'], start_new_session=True)
            unrelated = subprocess.Popen([sys.executable, '-c', 'import time; time.sleep(60)'], start_new_session=True)
            try:
                self.assertFalse(coding.enforce_deadline(agent, 1199, 1200, root, 'agent test'))
                self.assertIsNone(agent.poll())
                self.assertTrue(coding.enforce_deadline(agent, 1200, 1200, root, 'agent test'))
                agent.wait(timeout=3)
                self.assertIsNone(unrelated.poll())
                receipt = json.loads((root / 'deadline.json').read_text())
                self.assertEqual('timed_out', receipt['outcome'])
                self.assertEqual(1200, receipt['limit_s'])
            finally:
                if agent.poll() is None:
                    coding.stop_process_tree(agent)
                unrelated.terminate()
                unrelated.wait(timeout=3)

    def test_model_context_matches_selected_live_context(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory) / 'attempt'
            coding.prepare(root, 'model', 'http://localhost/v1', context=98304)
            provider = json.loads((root / 'agent/models.json').read_text())['providers']['local-benchmark']
            self.assertEqual(98304, provider['models'][0]['contextWindow'])
            self.assertEqual(12288, provider['models'][0]['maxTokens'])
            self.assertTrue(provider['models'][0]['compat']['supportsFinishReason'])
            settings = json.loads((root / 'agent/settings.json').read_text())
            self.assertEqual(1800000, settings['httpIdleTimeoutMs'])

    @unittest.skipUnless(Path('/proc/self/status').exists(), 'Linux process ownership check')
    def test_stop_includes_separate_tool_group_and_preserves_unrelated_process(self):
        with tempfile.TemporaryDirectory() as directory:
            pidfile = Path(directory) / 'child.pid'
            script = ('import subprocess,sys,time; '
                      'from pathlib import Path; '
                      'child=subprocess.Popen([sys.executable,"-c","import time; time.sleep(60)"],start_new_session=True); '
                      'Path(sys.argv[1]).write_text(str(child.pid)); time.sleep(60)')
            unrelated = subprocess.Popen([sys.executable, '-c', 'import time; time.sleep(60)'], start_new_session=True)
            parent = subprocess.Popen([sys.executable, '-c', script, str(pidfile)], start_new_session=True)
            try:
                deadline = time.monotonic() + 5
                while not pidfile.exists() and time.monotonic() < deadline:
                    time.sleep(0.01)
                child = int(pidfile.read_text())
                coding.stop_process_tree(parent)
                parent.wait(timeout=3)
                status = Path('/proc') / str(child) / 'status'
                if status.exists():
                    self.assertIn('State:\tZ', status.read_text())
                self.assertIsNone(unrelated.poll())
            finally:
                if parent.poll() is None:
                    coding.stop_process_tree(parent)
                unrelated.terminate()
                unrelated.wait(timeout=3)

    def test_existing_history_and_credentials_are_not_inherited(self):
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory)
            home = base / "home"
            history = home / ".pi/agent/sessions/old.jsonl"
            history.parent.mkdir(parents=True)
            history.write_text("private previous conversation\n")
            with patch.dict(os.environ, {"HOME": str(home), "OPENAI_API_KEY": "unrelated-secret",
                                        "PI_CODING_AGENT_DIR": str(history.parent)}):
                for name in ["attempt-1", "attempt-2"]:
                    root = base / name
                    coding.prepare(root, "local-model", "http://127.0.0.1:8001/v1")
                    env = coding.environment(root)
                    self.assertNotIn("OPENAI_API_KEY", env)
                    self.assertEqual(str(root / "agent"), env["PI_CODING_AGENT_DIR"])
                    self.assertEqual([], list((root / "sessions").iterdir()))
                    self.assertEqual([], list((root / "web-cache").iterdir()))
                    self.assertFalse(any(p.name == "old.jsonl" for p in root.rglob("*")))
                    settings = json.loads((root / "agent/settings.json").read_text())
                    self.assertEqual(16384, settings["compaction"]["reserveTokens"])
                self.assertEqual("private previous conversation\n", history.read_text())

    def test_smoke_contracts_do_not_redirect_runtime_configuration_and_setup_errors_are_durable(self):
        from types import SimpleNamespace
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory) / 'attempt'
            args = SimpleNamespace(output=root, model='fixture', base_url='http://localhost/v1', context=131072, task='smoke')
            canonical = coding.HERE.parent / 'gx10-context/config.json'
            expected = json.loads(canonical.read_text())['coding']['supervision']
            smoke_contracts = canonical.parent / 'smoke-contract'
            self.assertFalse((smoke_contracts / 'config.json').exists())
            with patch.object(coding, 'CONTRACTS', smoke_contracts), \
                 patch.object(coding, 'metrics_endpoint', return_value=('http://localhost/metrics', 'ollama')), \
                 patch.object(coding, 'InferenceProxy', side_effect=RuntimeError('fixture unavailable metrics')) as proxy:
                with self.assertRaisesRegex(RuntimeError, 'fixture unavailable metrics'):
                    coding.run(args)
            self.assertEqual(expected, proxy.call_args.kwargs['budget'])
            result = json.loads((root / 'result.json').read_text())
            self.assertEqual('attempt_preparation', result['failure_kind'])
            self.assertIn('fixture unavailable metrics', result['error'])

    def test_attempt_directory_cannot_be_reused(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory) / "attempt"
            coding.prepare(root, "model", "http://localhost/v1")
            with self.assertRaises(FileExistsError):
                coding.prepare(root, "model", "http://localhost/v1")

    def test_resource_guard_stops_only_its_child_on_low_memory(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            command = root / "command.json"
            command.write_text(json.dumps([sys.executable, "-c", "import time; time.sleep(60)"]))
            high = {"available": coding.RESERVE + 1024}
            low = {"available": coding.RESERVE - 1}
            with patch.object(resource_guard, "memory", side_effect=[high, high, low]):
                exit_code = resource_guard.supervise(command, root / "output")
            self.assertEqual(91, exit_code)
            result = json.loads((root / "output/server-exit.json").read_text())
            self.assertTrue(result["resource_stopped"])
            self.assertIsNotNone(result["exit"])


if __name__ == "__main__":
    unittest.main()
