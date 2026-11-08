"""Guard the evidence interpretation against false positives without requiring Android."""
import copy
import importlib.util
from pathlib import Path
import unittest
import xml.etree.ElementTree as ET

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


class DeviceInterfacesTest(unittest.TestCase):
    def test_ip_tunnel_placeholder_is_not_an_android_vpn_tun(self):
        device = module.Device("adb", "emulator-5554")
        device.shell = lambda *args: "eth0 ip6tnl0 lo tunl0 wlan0"
        self.assertEqual([], device.tun())

    def test_real_vpn_tun_remains_detected_alongside_placeholders(self):
        device = module.Device("adb", "emulator-5554")
        device.shell = lambda *args: "lo tunl0 tun0 tun12 wlan0"
        self.assertEqual(["tun0", "tun12"], device.tun())


class RoutingModeEvidenceTest(unittest.TestCase):
    def test_default_mode_rejects_either_system_restriction(self):
        module.require_mode({"always_on_package": None, "lockdown": False}, strict=False)
        for state in ({"always_on_package": module.COMPANION, "lockdown": False},
                      {"always_on_package": None, "lockdown": True},
                      {"always_on_package": module.COMPANION, "lockdown": True}):
            with self.subTest(state=state), self.assertRaises(module.Regression):
                module.require_mode(state, strict=False)

    def test_optional_strict_requires_both_settings(self):
        module.require_mode({"always_on_package": module.COMPANION, "lockdown": True}, strict=True)
        with self.assertRaises(module.Regression):
            module.require_mode({"always_on_package": module.COMPANION, "lockdown": False}, strict=True)

    def direct_entry(self):
        return {"name": "direct", "physical_uid_packets": {"iptables": 8, "ip6tables": 0},
                "results": [{"operation": "tor", "success": True, "is_tor": False},
                            {"operation": "tcp4", "success": True},
                            {"operation": "local_tcp", "success": True, "host_received": 1},
                            {"operation": "udp", "success": True, "host_received": 1}]}

    def test_control_using_tor_is_not_a_direct_success(self):
        entry = self.direct_entry()
        module.require_direct(entry)
        entry["results"][0]["is_tor"] = True
        with self.assertRaises(module.Regression):
            module.require_direct(entry)

    def test_off_requires_independent_direct_positive_evidence(self):
        for change in ("missing_host", "missing_counter", "failed_https"):
            entry = self.direct_entry()
            if change == "missing_host":
                entry["results"][3]["host_received"] = 0
            elif change == "missing_counter":
                entry["physical_uid_packets"]["iptables"] = 0
            else:
                entry["results"][0]["success"] = False
            with self.subTest(change=change), self.assertRaises(module.Incomplete):
                module.require_direct(entry)


class SeparateFixtureTest(unittest.TestCase):
    def test_control_manifest_keeps_real_classes_but_uses_its_own_uid_package(self):
        android = "{http://schemas.android.com/apk/res/android}"
        for filename, package in (("AndroidManifest.xml", module.PROBE),
                                  ("ControlAndroidManifest.xml", module.CONTROL)):
            manifest = ET.parse(Path(__file__).with_name(filename)).getroot()
            with self.subTest(package=package):
                self.assertEqual(package, manifest.get("package"))
                self.assertIsNone(manifest.get(android + "sharedUserId"))
                runner = manifest.find("instrumentation")
                self.assertEqual(package, runner.get(android + "targetPackage"))
                self.assertEqual(module.PROBE + ".ProbeInstrumentation", runner.get(android + "name"))
                self.assertEqual(module.PROBE + ".ProbeActivity",
                                 manifest.find("application/activity").get(android + "name"))

    def test_control_instrumentation_cannot_silently_run_as_target_uid(self):
        device = module.Device("adb", "emulator-5554")
        device.probe_uids = {module.PROBE: 10210, module.CONTROL: 10211}
        calls = []
        def shell(*args, **kwargs):
            calls.append(args)
            return ('INSTRUMENTATION_RESULT: result={"operation":"tor","uid":10210,"success":true}'
                    '\nINSTRUMENTATION_CODE: -1')
        device.shell = shell
        with self.assertRaises(module.Regression):
            device.probe("tor", package=module.CONTROL)
        self.assertEqual(module.CONTROL + "/" + module.PROBE + ".ProbeInstrumentation", calls[0][-1])


if __name__ == "__main__":
    unittest.main()
