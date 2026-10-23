import json
import os
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

HERE = Path(__file__).resolve().parent
sys.path[:0] = [str(HERE.parent / 'ornith-nvfp4-mtp'), str(HERE.parent / 'gx10-context')]
import coding
import test_pi_runtime as wire


class EffortTests(unittest.TestCase):
    def test_cell_model_policy_maps_pi_high_to_qwen_xhigh(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            model = json.loads((HERE / 'model.json').read_text())
            (root / 'manifest.json').write_text(json.dumps({'models': [model]}))
            coding.prepare(root / 'attempt', model['source'], 'http://127.0.0.1:1/v1', 131072)
            result = json.loads((root / 'attempt/agent/models.json').read_text())['providers']['local-benchmark']['models'][0]
            self.assertEqual(result['samplingParams']['reasoning_effort'], 'xhigh')
            self.assertEqual(result['thinkingLevelMap']['high'], 'xhigh')

    def test_other_models_keep_existing_effort(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            coding.prepare(root / 'attempt', 'ornith-nvfp4-mtp', 'http://127.0.0.1:1/v1', 131072)
            result = json.loads((root / 'attempt/agent/models.json').read_text())['providers']['local-benchmark']['models'][0]
            self.assertEqual(result['samplingParams']['reasoning_effort'], 'high')
            self.assertNotIn('thinkingLevelMap', result)

    @unittest.skipUnless(wire.PI.exists() and os.environ.get('GX10_LIVE_QWEN_EFFORT') == '1', 'Opt-in actual Pi payload check')
    def test_real_pi_submits_mapped_effort(self):
        actual_run = wire.subprocess.run
        def invoke(command, **kwargs):
            agent = Path(kwargs['env']['PI_CODING_AGENT_DIR'])
            path = agent / 'models.json'
            config = json.loads(path.read_text())
            config['providers']['mock']['models'][0]['thinkingLevelMap'] = {'high': 'xhigh'}
            path.write_text(json.dumps(config))
            return actual_run(command, **kwargs)
        with patch.object(wire.subprocess, 'run', side_effect=invoke):
            requests, events, audit = wire.PiWireIntegrationTests('runTest').invoke(
                lambda count, body: wire.PiWireIntegrationTests.completion())
        self.assertEqual(len(requests), 1)
        self.assertEqual(requests[0]['reasoning_effort'], 'xhigh')


if __name__ == '__main__':
    unittest.main()
