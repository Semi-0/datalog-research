import hashlib
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import security


class SecurityTests(unittest.TestCase):
    def base(self):
        return json.dumps({'defaultAction': 'SCMP_ACT_ERRNO', 'defaultErrnoRet': 1,
            'syscalls': [{'names': ['read', 'write'], 'action': 'SCMP_ACT_ALLOW'},
                {'names': ['clone3'], 'action': 'SCMP_ACT_ERRNO', 'errnoRet': 38}]}).encode()

    def test_retains_original_policy_and_clone3_denial(self):
        raw = self.base()
        with patch.object(security, 'BASE_SHA256', hashlib.sha256(raw).hexdigest()):
            result = security.seccomp_profile(raw)
        original = json.loads(raw)
        self.assertEqual(result['defaultAction'], original['defaultAction'])
        self.assertEqual(result['syscalls'][:len(original['syscalls'])], original['syscalls'])
        self.assertEqual(result['syscalls'][-1]['names'], ['mount', 'umount2', 'pivot_root'])

    def test_only_user_and_mount_namespaces_are_added(self):
        raw = self.base()
        with patch.object(security, 'BASE_SHA256', hashlib.sha256(raw).hexdigest()):
            profile = security.seccomp_profile(raw)
        rules = profile['syscalls'][-3:-1]
        for rule in rules:
            arg = rule['args'][0]
            self.assertEqual(0x10020000 & arg['value'], 0)
            self.assertEqual(arg['valueTwo'], 0)
            # NETWORK, PID, IPC, UTS, CGROUP namespace requests must still be blocked.
            for bit in [0x40000000, 0x20000000, 0x08000000, 0x04000000, 0x02000000]:
                self.assertNotEqual(bit & arg['value'], 0)

    def test_refuses_unpinned_base(self):
        with self.assertRaisesRegex(ValueError, 'hash mismatch'):
            security.seccomp_profile(self.base())

    def test_container_options_do_not_disable_security_or_add_capabilities(self):
        raw = self.base()
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            base = root / 'base.json'
            base.write_bytes(raw)
            with patch.object(security, 'BASE_SHA256', hashlib.sha256(raw).hexdigest()):
                options = security.options(base, root)
            self.assertTrue((root / 'seccomp.json').is_file())
        self.assertIn('apparmor=' + security.PROFILE, options)
        self.assertIn('no-new-privileges=true', options)
        self.assertEqual(options[-2:], ['--cap-drop', 'ALL'])
        self.assertFalse(any('unconfined' in option for option in options))
        self.assertNotIn('--privileged', options)
        self.assertNotIn('--cap-add', options)

    def test_checks_enforcement_capabilities_and_no_new_privileges(self):
        security.verify_container(security.PROFILE + ' (enforce)\n', 'NoNewPrivs:\t1\nCapEff:\t0000000000000000\n')
        for label, status in [
                ('unconfined', 'NoNewPrivs: 1\nCapEff: 0'),
                (security.PROFILE + ' (complain)', 'NoNewPrivs: 1\nCapEff: 0'),
                (security.PROFILE + ' (enforce)', 'NoNewPrivs: 0\nCapEff: 0'),
                (security.PROFILE + ' (enforce)', 'NoNewPrivs: 1\nCapEff: 00002000'),
                (security.PROFILE + ' (enforce)', '')]:
            with self.assertRaises(ValueError):
                security.verify_container(label, status)


if __name__ == '__main__':
    unittest.main()
