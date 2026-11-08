"""Guard the evidence interpretation against false positives without requiring Android."""
import copy
import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location("network_checks", Path(__file__).with_name("run-network-checks.py"))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class BlockingEvidenceTest(unittest.TestCase):
    def setUp(self):
        self.entry = {
            "name": "negative_control",
            "results": [
                {"operation": "tcp4", "success": False},
                {"operation": "udp", "success": False, "host_received": 0},
                {"operation": "dns", "success": True},
            ],
            "physical_uid_packets": {"iptables": 0, "ip6tables": 0},
        }

    def test_cached_dns_is_not_misclassified_as_network_success(self):
        module.require_blocked(self.entry)

    def test_failed_client_cannot_hide_host_received_traffic(self):
        self.entry["results"][1]["host_received"] = 1
        with self.assertRaises(module.Regression):
            module.require_blocked(self.entry)

    def test_any_physical_family_egress_is_rejected(self):
        for family in ("iptables", "ip6tables"):
            with self.subTest(family=family):
                entry = copy.deepcopy(self.entry)
                entry["physical_uid_packets"][family] = 1
                with self.assertRaises(module.Regression):
                    module.require_blocked(entry)

    def test_successful_external_tcp_is_rejected_even_without_observed_packets(self):
        self.entry["results"][0]["success"] = True
        with self.assertRaises(module.Regression):
            module.require_blocked(self.entry)


if __name__ == "__main__":
    unittest.main()
