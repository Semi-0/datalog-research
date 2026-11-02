"""Pure network-policy checks; live namespace probes are separate acceptance."""
import importlib.util
from pathlib import Path
import unittest

SPEC = importlib.util.spec_from_file_location("sandbox_network", Path(__file__).with_name("network.py"))
network = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(network)


class NetworkTests(unittest.TestCase):
    def declaration(self):
        return {"meshPeers": ["100.103.106.251", "100.122.233.90"],
                "hostAddresses": ["127.0.0.1", "100.83.165.122", "192.168.178.201"],
                "dnsUpstream": "213.46.228.196"}

    def test_host_denial_precedes_mesh_allowance(self):
        rules = network.firewall_rules(self.declaration())
        self.assertLess(rules.index("ip daddr @host4"), rules.index("ip daddr @mesh4"))
        self.assertLess(rules.index("ip daddr @mesh4"), rules.index("ip daddr @blocked4"))
        self.assertIn("100.64.0.0/10", rules)
        self.assertIn("192.168.0.0/16", rules)
        self.assertIn("169.254.0.0/16", rules)
        self.assertIn("meta nfproto ipv4 accept", rules)
        self.assertEqual(rules.count("policy drop;"), 2)

    def test_only_explicit_peer_addresses_escape_private_network_denial(self):
        for invalid in ("192.168.1.1", "127.0.0.1", "100.83.165.122", "100.90.1.1; flush ruleset"):
            declaration = self.declaration()
            declaration["meshPeers"] = [invalid]
            with self.subTest(address=invalid), self.assertRaises(ValueError):
                network.firewall_rules(declaration)

    def test_host_loopback_dns_cannot_expose_host_services(self):
        for invalid in ("127.0.0.53", "100.83.165.122", "0.0.0.0"):
            declaration = self.declaration()
            declaration["dnsUpstream"] = invalid
            with self.subTest(address=invalid), self.assertRaises(ValueError):
                network.firewall_rules(declaration)

    def test_empty_mesh_catalog_does_not_create_a_private_range_allowance(self):
        declaration = self.declaration()
        declaration["meshPeers"] = []
        rules = network.firewall_rules(declaration)
        self.assertIn("set mesh4", rules)
        self.assertNotIn("elements = { 100.64.0.0/10", rules)

    def test_pasta_disables_all_port_forwarding_and_gateway_mapping(self):
        command = network.pasta_command(123, self.declaration())
        for flag in ("-t", "-u", "-T", "-U"):
            self.assertEqual(command[command.index(flag) + 1], "none")
        self.assertIn("--no-map-gw", command)
        self.assertIn("-4", command)
        self.assertEqual(command[command.index("--netns") + 1], "/proc/123/ns/net")
        self.assertEqual(command[command.index("--userns") + 1], "/proc/123/ns/user")


if __name__ == "__main__":
    unittest.main()
