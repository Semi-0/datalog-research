import json
from pathlib import Path
import tempfile
import unittest
from unittest import mock

import gx10_editor as deployment
import gx10_lan as lan


# Synthetic fixtures only; deployment-specific addresses stay outside Git.
class LanGatewayTests(unittest.TestCase):
    def test_only_explicit_private_lan_networks_are_accepted(self):
        self.assertEqual(("10.0.0.10", "10.0.0.0/24"),
                         lan.validate_access("10.0.0.10", "10.0.0.0/24", "lain", 8443))
        for address, subnet in [("0.0.0.0", "0.0.0.0/0"), ("8.8.8.8", "8.8.8.0/24"),
                                ("127.0.0.1", "127.0.0.0/24"), ("10.0.0.10", "192.168.1.0/24"),
                                ("10.0.0.10", "10.0.0.0/8")]:
            with self.assertRaises(ValueError):
                lan.validate_access(address, subnet, "lain", 8443)
        with self.assertRaises(ValueError):
            lan.validate_access("10.0.0.10", "10.0.0.0/24", "invalid\nuser", 8443)

    def test_private_credentials_are_stable_and_refuse_permissive_files(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "credential"
            value = lan.credential(path)
            self.assertEqual(value, lan.credential(path))
            self.assertEqual(0o600, path.stat().st_mode & 0o777)
            with self.assertRaises(FileExistsError):
                lan.private_file(path, "replacement")
            path.chmod(0o644)
            with self.assertRaises(ValueError):
                lan.credential(path)

    def test_existing_symlink_credential_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            target = root / "target"
            lan.private_file(target, "a" * 40)
            link = root / "link"
            link.symlink_to(target)
            with self.assertRaises(ValueError):
                lan.credential(link)

    def test_config_rejects_injected_gateway_or_password_hash(self):
        arguments = ["10.0.0.10", "10.0.0.0/24", 8443, "lain", "$2a$14$" + "a" * 53, "b" * 40]
        self.assertIn("tls internal", lan.caddy_configuration(*arguments))
        for position in (4, 5):
            changed = arguments.copy()
            changed[position] += "\nrespond 200"
            with self.assertRaises(ValueError):
                lan.caddy_configuration(*changed)

    def test_production_probe_credentials_never_reach_candidate_or_external_urls(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            gateway = root / "gateway"
            lan.private_file(gateway, "a" * 40)
            deployment.save(root / "lan.json", {"origin": "https://10.0.0.10:8443",
                                                "gateway_credential_file": str(gateway)})
            with mock.patch.object(deployment, "ROOT", root):
                for url in ["http://127.0.0.1:12345", "https://external.example", "http://localhost:4782",
                            "http://127.0.0.1:4782.evil.example"]:
                    self.assertEqual({}, deployment.probe_headers(url))
                self.assertEqual({"Origin": "https://10.0.0.10:8443", "X-Lain-Gateway": "a" * 40},
                                 deployment.probe_headers("http://127.0.0.1:4782"))


if __name__ == "__main__":
    unittest.main()
