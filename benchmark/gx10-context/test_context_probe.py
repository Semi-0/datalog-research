import json
import io
from types import SimpleNamespace
import unittest
from unittest.mock import patch

from context_probe import answer_mapping, fixture
from model_setup import budget_projection
from lifecycle import http
RESERVE = 26 * 1024**3


class Words:
    def encode(self, text, add_special_tokens=False):
        return SimpleNamespace(ids=text.split())


class ContextProbeTests(unittest.TestCase):
    def test_retrieval_fixture_is_bounded_and_values_only_appear_in_records(self):
        prompt, expected, tokens = fixture(Words(), 4096, 42)
        again = fixture(Words(), 4096, 42)
        self.assertEqual((prompt, expected, tokens), again)
        self.assertEqual(16, len(expected))
        self.assertLessEqual(tokens, 4096 - 128)
        records, query = prompt.split("END RECORDS\nREQUESTED KEYS\n")
        for key, value in expected.items():
            self.assertIn(key + " = " + value, records)
            self.assertIn(key, query)
            self.assertNotIn(value, query)
        first_key = next(iter(expected))
        last_key = list(expected)[-1]
        self.assertIn(first_key, records.split("RECORDS\n")[1].splitlines()[0])
        self.assertIn(last_key, records.splitlines()[-1])
        self.assertNotEqual(prompt, fixture(Words(), 4096, 43)[0])

    def test_json_response_contract(self):
        self.assertEqual({"k": "v"}, answer_mapping('```json\n{"k":"v"}\n```'))
        with self.assertRaises(ValueError):
            answer_mapping('["v"]')
        with self.assertRaises(json.JSONDecodeError):
            answer_mapping('The answer is {"k":"v"}')

    def test_larger_context_can_be_declined_before_allocating(self):
        info = {"general.architecture": "test", "test.block_count": 48,
                "test.full_attention_interval": 4, "test.attention.head_count_kv": 2,
                "test.attention.key_length": 256, "test.attention.value_length": 256}
        available = RESERVE + 10 * 1024**3 + 4 * 1024**3 + 2 * 1024**3
        small = budget_projection(info, 10 * 1024**3, 32768, available, RESERVE)
        large = budget_projection(info, 10 * 1024**3, 262144, available, RESERVE)
        self.assertTrue(small["allowed"])
        self.assertFalse(large["allowed"])

    def test_local_endpoint_key_is_not_sent_to_model_registry(self):
        with patch("lifecycle.urllib.request.urlopen", side_effect=lambda *a, **kw: io.BytesIO(b'{"ok":true}')) as opened:
            http("https://huggingface.co/api/models/test")
            request = opened.call_args.args[0]
            self.assertIsNone(request.get_header("Authorization"))
            http("http://127.0.0.1:8001/v1/models")
            request = opened.call_args.args[0]
            self.assertEqual("Bearer local-benchmark", request.get_header("Authorization"))


if __name__ == "__main__":
    unittest.main()
