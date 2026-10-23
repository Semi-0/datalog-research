import copy
import importlib.util
import json
from pathlib import Path
import tempfile
import types
import unittest
from unittest.mock import patch

import follow_on
from lifecycle import Failure, save
import model_setup
from model_profile import thinking_enabled, prepared_thinking
from outcomes import classify, smoke_capabilities_verified

spec = importlib.util.spec_from_file_location('profile_coding', Path(__file__).resolve().parent.parent / 'ornith-nvfp4-mtp/coding.py')
coding = importlib.util.module_from_spec(spec)
spec.loader.exec_module(coding)


class ModelProfileTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        registration = patch.dict('outcomes.EXPECTED_ASSERTIONS', {'smoke': 1})
        registration.start()
        self.addCleanup(registration.stop)

    def completed(self):
        return {'task': 'smoke', 'acceptance_exit': 0, 'own_tests_exit': 0, 'confined': True,
                'thinking_observed': False, 'thinking_required': False, 'search_succeeded': True,
                'fetch_succeeded': True, 'agent_final_response': True, 'agent_end_observed': True,
                'pi_exit': 0, 'swap_in_pages_delta': 0, 'swap_out_pages_delta': 0,
                'tool_names': ['read', 'write', 'edit', 'bash', 'web_search', 'fetch_content']}

    def test_non_thinking_classification_does_not_invent_thinking_evidence(self):
        text = 'Ran 1 tests containing 1 assertions.\n0 failures, 0 errors.'
        result = classify(self.completed(), text, text)
        self.assertTrue(result['correct'])
        self.assertTrue(result['workflow_pass'])
        self.assertFalse(result['thinking_observed'])
        self.assertEqual(result['thinking_requirement'], 'not_applicable')

    def test_legacy_thinking_requirement_is_preserved(self):
        result = self.completed()
        result.pop('thinking_required')
        text = 'Ran 1 tests containing 1 assertions.\n0 failures, 0 errors.'
        classify(result, text, text)
        self.assertFalse(result['workflow_pass'])
        self.assertEqual(result['thinking_requirement'], 'required')

    def test_non_thinking_still_requires_real_independent_assertions(self):
        text = 'Ran 0 tests containing 0 assertions.\n0 failures, 0 errors.'
        result = classify(self.completed(), text, text)
        self.assertFalse(result['correct'])
        self.assertFalse(result['workflow_pass'])

    def test_smoke_capabilities_accept_declared_non_thinking(self):
        text = 'Ran 1 tests containing 1 assertions.\n0 failures, 0 errors.'
        result = classify(self.completed(), text, text)
        self.assertTrue(smoke_capabilities_verified(result))

    def test_non_thinking_prepare_and_exact_command_disable_reasoning(self):
        manifest = {'models': [{'source': 'coder', 'supports_thinking': False}],
                    'sampling': {'thinking': True, 'temperature': 1.0, 'top_p': 0.95}}
        save(self.root / 'manifest.json', manifest)
        root = self.root / 'attempt'
        provider = coding.prepare(root, 'coder', 'http://127.0.0.1:1', 131072)
        config = json.loads((root / 'agent/models.json').read_text())['providers'][provider]['models'][0]
        self.assertFalse(config['reasoning'])
        self.assertFalse(config['compat']['supportsReasoningEffort'])
        self.assertNotIn('reasoning_effort', config['samplingParams'])
        self.assertEqual(config['samplingParams']['temperature'], 1.0)
        self.assertFalse(prepared_thinking(root))
        command = coding.agent_command(root, types.SimpleNamespace(model='coder'), provider, 'test', [])
        self.assertEqual(command[command.index('--thinking') + 1], 'off')

    def test_missing_capability_retains_high_thinking(self):
        root = self.root / 'attempt'
        provider = coding.prepare(root, 'legacy', 'http://127.0.0.1:1', 131072)
        config = json.loads((root / 'agent/models.json').read_text())['providers'][provider]['models'][0]
        self.assertTrue(config['reasoning'])
        self.assertEqual(config['samplingParams']['reasoning_effort'], 'high')
        self.assertTrue(prepared_thinking(root))

    def test_invalid_capability_values_are_rejected(self):
        for model, sampling in [({'supports_thinking': 'false'}, {}), ({'supports_thinking': 0}, {}), ({}, {'thinking': 'true'})]:
            with self.assertRaises(Failure):
                thinking_enabled(model, sampling)
        self.assertFalse(thinking_enabled({'supports_thinking': False}, {'thinking': True}))
        self.assertTrue(thinking_enabled({}, {}))

    def exercise_preload(self, thinking=False, unexpected_reasoning=False, wrong_template=False):
        model = {'id': 'candidate', 'backend': 'vllm', 'source': 'candidate', 'revision': 'fixed',
                 'weight_bytes': 1, 'supports_thinking': thinking, 'mtp': False,
                 'model_directory': str(self.root / 'model'), 'artifact_manifest': str(self.root / 'artifacts.json'),
                 'server_command': str(self.root / 'server.json')}
        save(Path(model['artifact_manifest']), {'sha': 'fixed', 'siblings': []})
        template = {'enable_thinking': thinking}
        command = ['python', '--model', model['model_directory'], '--served-model-name', 'candidate',
                   '--max-model-len', '131072', '--kv-cache-memory-bytes', '1', '--max-num-seqs', '1',
                   '--override-generation-config', '{}']
        if thinking:
            template['reasoning_effort'] = 'high'
            command += ['--reasoning-parser', 'qwen3']
        if wrong_template:
            template['enable_thinking'] = True
        command += ['--default-chat-template-kwargs', json.dumps(template)]
        save(Path(model['server_command']), command)
        out = self.root / 'setup'
        out.mkdir()
        calls = []
        backend = types.SimpleNamespace(root=out, manifest={'sampling': {'thinking': True, 'temperature': 1.0, 'repeat_penalty': 1}, 'backend_runtime': {}},
            event=lambda *args: None, launch=lambda *args: calls.append(args),
            wait_ready=lambda url: {'data': [{'id': 'candidate', 'max_model_len': 131072}]})
        message = {'content': 'READY'}
        if thinking or unexpected_reasoning:
            message['reasoning_content'] = 'checked'
        payloads = []
        def http(url, body, **kwargs):
            payloads.append(body)
            return {'choices': [{'message': message, 'finish_reason': 'stop'}]}
        with (patch.object(model_setup, 'memory', return_value={'available': 100 * 2**30, 'reserve': 20 * 2**30}),
              patch.object(model_setup, 'identities', return_value=[]), patch.object(model_setup, 'http', side_effect=http),
              patch.object(model_setup.subprocess, 'check_output', return_value='GPU execution')):
            model_setup.start(backend, model, 131072, 'test-only')
        return out, payloads, calls

    def test_non_thinking_preload_has_no_reasoning_effort(self):
        out, payloads, calls = self.exercise_preload()
        self.assertNotIn('reasoning_effort', payloads[0])
        self.assertFalse(json.loads((out / 'configuration-verification.json').read_text())['thinking'])
        self.assertEqual(len(calls), 1)

    def test_legacy_preload_still_requires_reasoning(self):
        out, payloads, calls = self.exercise_preload(thinking=True)
        self.assertEqual(payloads[0]['reasoning_effort'], 'high')
        self.assertTrue(json.loads((out / 'configuration-verification.json').read_text())['thinking'])

    def test_non_thinking_configuration_mismatch_is_rejected(self):
        with self.assertRaises(Failure):
            self.exercise_preload(wrong_template=True)

    def test_non_thinking_unexpected_reasoning_is_rejected(self):
        with self.assertRaises(Failure):
            self.exercise_preload(unexpected_reasoning=True)

    def test_runtime_overrides_are_restricted_to_verified_backend_flags(self):
        env = model_setup.vllm_environment(self.root, {'runtime_environment': {'VLLM_NVFP4_GEMM_BACKEND': 'marlin'}})
        self.assertEqual(env['VLLM_NVFP4_GEMM_BACKEND'], 'marlin')
        self.assertEqual(env['VLLM_MARLIN_USE_ATOMIC_ADD'], '1')
        with self.assertRaises(Failure):
            model_setup.vllm_environment(self.root, {'runtime_environment': {'PATH': '/untrusted'}})


if __name__ == '__main__':
    unittest.main()
