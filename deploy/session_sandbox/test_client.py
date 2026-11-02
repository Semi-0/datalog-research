import copy
import tempfile
import unittest
from pathlib import Path
from unittest.mock import Mock
from client import Client, Viewer


class ViewerTests(unittest.TestCase):
    def setUp(self):
        self.state = {'sessionId': 'owner', 'identity': {'id': 'owner', 'run': 'saved'},
                      'generation': 7, 'views': []}
        self.sent = []
        self.client = Mock()
        self.client.pending.return_value = {}
        self.client.command.side_effect = self.send
        self.viewer = Viewer(self.client, 'our-view')

    def send(self, command):
        self.sent.append(command)
        if command['op'] == 'reopen-session':
            self.state['views'].append(command['pane'])
        return {'ok': True, 'result': copy.deepcopy(self.state), 'receiptStatus': 'committed'}

    def test_refresh_uses_one_lease_without_replaying_document(self):
        command = {'op': 'restore', 'workspace': {'panes': [{'id': 'one', 'source': 'DO NOT RUN'}]}}
        for _ in range(2):
            result = self.viewer.command(command)
            self.assertEqual(result['result']['generations'], {'one': 7})
        self.assertEqual(sum(c['op'] == 'reopen-session' for c in self.sent), 1)
        self.assertTrue(all('workspace' not in c and 'source' not in c for c in self.sent))
        self.assertEqual(self.state['identity']['run'], 'saved')

    def test_program_mutations_never_reach_transport(self):
        for op in ['run', 'extend', 'sandbox-create', 'sandbox-stop', 'sandbox-rerun',
                   'sandbox-upload', 'sandbox-install', 'sandbox-input', 'layout']:
            with self.assertRaises(ValueError):
                self.viewer.command({'op': op})
        self.assertEqual(self.sent, [])

    def test_read_cannot_smuggle_program(self):
        with self.assertRaises(ValueError):
            self.viewer.command({'op': 'inspect', 'source': 'DO NOT RUN'})
        self.assertEqual(self.sent, [])

    def test_browser_cannot_detach_another_pane(self):
        self.viewer.command({'op': 'detach-session', 'pane': 'owner-pane'})
        self.assertEqual(self.sent[-1]['pane'], 'our-view')

    def test_uncertain_lease_is_not_replayed(self):
        self.client.pending.return_value = {'uuid': {'op': 'reopen-session'}}
        with self.assertRaises(RuntimeError):
            self.viewer.command({'op': 'restore'})
        self.assertEqual([c['op'] for c in self.sent], ['sandbox-status'])

    def test_owner_change_is_visible_as_error(self):
        original = self.send
        def changing(command):
            if command['op'] == 'reopen-session':
                self.state['generation'] += 1
            return original(command)
        self.client.command.side_effect = changing
        with self.assertRaises(RuntimeError):
            self.viewer.command({'op': 'restore'})


class ClientEndpointTests(unittest.TestCase):
    def test_local_endpoint_requires_explicit_choice(self):
        import json
        with tempfile.TemporaryDirectory() as root:
            path = Path(root) / 'scope.json'
            path.write_text(json.dumps({'baseUrl': 'http://127.0.0.1:48870',
                                        'sandboxId': 'a'*32, 'token': 'test'}))
            path.chmod(0o600)
            with self.assertRaises(ValueError):
                Client(path)
            self.assertTrue(Client(path, local=True).url.endswith('/api'))
