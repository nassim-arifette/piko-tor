#!/usr/bin/env python3
"""Bounded network experiments on a new, explicitly selected disposable emulator only."""
from __future__ import annotations

import argparse
import hashlib
import ipaddress
import json
import os
from pathlib import Path
import re
import shlex
import socketserver
import subprocess
import threading
import time
import uuid
import xml.etree.ElementTree as ET

PROBE = "app.piko.tor.probe"
COMPANION = "app.piko.tor"
SETUP = "/data/local/tmp/piko-vpn-setup.zip"


class Incomplete(RuntimeError):
    pass


class Regression(RuntimeError):
    pass


class Device:
    def __init__(self, adb: str, serial: str):
        self.command = [adb, "-s", serial]
        self.control_addresses: dict[str, str] = {}

    def run(self, *arguments: str, data: str | None = None, timeout: int = 60) -> str:
        try:
            result = subprocess.run(self.command + list(arguments), input=data, text=True,
                                    capture_output=True, timeout=timeout)
        except subprocess.TimeoutExpired as error:
            raise Incomplete(f"ADB operation timed out: {arguments[0]}") from error
        if result.returncode:
            # Shell errors contain only fixture paths/commands; do not collect general logcat.
            raise Incomplete(f"ADB operation failed: {arguments[0]}: {result.stderr.strip()[:600]}")
        return result.stdout.strip()

    def shell(self, *arguments: str, **kwargs) -> str:
        return self.run("shell", shlex.join(arguments), **kwargs)

    def tun(self) -> list[str]:
        return sorted(name for name in self.shell("ls", "/sys/class/net").split()
                      if re.fullmatch(r"tun\d+", name))

    def tun_identity(self) -> dict[str, int]:
        result = {}
        for name in self.tun():
            if not re.fullmatch(r"tun\d+", name):
                raise Incomplete("Unexpected TUN interface name")
            result[name] = int(self.shell("cat", "/sys/class/net/" + name + "/ifindex"))
        return result

    def probe(self, operation: str, token: str | None = None) -> dict:
        args = ["am", "instrument", "-w", "-e", "operation", operation]
        if token:
            args += ["-e", "token", token]
        if operation in self.control_addresses:
            args += ["-e", "address", self.control_addresses[operation]]
        args += [PROBE + "/.ProbeInstrumentation"]
        output = self.shell(*args, timeout=45)
        match = re.search(r"^INSTRUMENTATION_RESULT: result=(\{.*\})$", output, re.MULTILINE)
        if not match or "INSTRUMENTATION_CODE: -1" not in output:
            raise Incomplete(f"Probe instrumentation did not complete: {operation}")
        result = json.loads(match.group(1))
        address = result.pop("control_address", None)
        if address is not None:
            if operation not in {"tcp4", "tcp6"} or not ipaddress.ip_address(address).is_global:
                raise Incomplete("TCP control did not resolve to a public literal address")
            previous = self.control_addresses.setdefault(operation, address)
            if previous != address:
                raise Incomplete("TCP control address changed between experiment phases")
            result["literal_control"] = True
        if result.get("uid") != getattr(self, "probe_uid", result.get("uid")):
            raise Regression("Probe executed under the wrong Android UID")
        return result

    def vpn(self, operation: str) -> bool:
        result = self.shell("env", "CLASSPATH=" + SETUP, "app_process", "/",
                            "app.piko.tor.validation.VpnSetup", operation)
        if result not in {"PIKO_LAB_VPN_ENABLED", "PIKO_LAB_VPN_DISABLED"}:
            raise Incomplete("Named Android VPN API helper did not complete")
        return result == "PIKO_LAB_VPN_ENABLED"


class Echo:
    def __init__(self):
        self.events: dict[str, int] = {}
        self.lock = threading.Lock()
        self.servers = []
        owner = self

        def respond(data: bytes) -> bytes:
            value = data.decode("ascii", errors="ignore")
            if not re.fullmatch(r"piko-[a-f0-9]{32}", value):
                return b""
            with owner.lock:
                owner.events[value] = owner.events.get(value, 0) + 1
            return data

        class TCP(socketserver.BaseRequestHandler):
            def handle(self):
                self.request.settimeout(3)
                data = b""
                try:
                    while len(data) < 37:
                        part = self.request.recv(37 - len(data))
                        if not part:
                            break
                        data += part
                    self.request.sendall(respond(data))
                except OSError:
                    pass

        class UDP(socketserver.BaseRequestHandler):
            def handle(self):
                data, sock = self.request
                response = respond(data)
                if response:
                    sock.sendto(response, self.client_address)

        for cls, port, handler in [(socketserver.ThreadingTCPServer, 18080, TCP),
                                   (socketserver.ThreadingUDPServer, 18081, UDP)]:
            server = cls(("127.0.0.1", port), handler)
            server.daemon_threads = True
            self.servers.append(server)
            threading.Thread(target=server.serve_forever, daemon=True).start()

    def received(self, token: str) -> int:
        with self.lock:
            return self.events.get(token, 0)

    def close(self):
        for server in self.servers:
            server.shutdown()
            server.server_close()


class Counters:
    """Target-UID counting rules; no ACCEPT/DROP verdict or routing modification."""
    def __init__(self, device: Device, uid: int):
        self.device, self.uid = device, str(uid)
        self.chain = "PIKO_" + uuid.uuid4().hex[:8]
        self.tables: list[str] = []
        self.physical = sorted(name for name in device.shell("ls", "/sys/class/net").split()
                               if name != "lo" and not name.startswith("tun"))
        if not self.physical or any(not re.fullmatch(r"[a-zA-Z0-9_.-]+", x) for x in self.physical):
            raise Incomplete("Cannot identify physical emulator interfaces")
        try:
            for command in ("iptables", "ip6tables"):
                device.shell(command, "-w", "-N", self.chain)
                self.tables.append(command)
                for interface in self.physical:
                    # A rule without -j only increments its counters and proceeds.
                    device.shell(command, "-w", "-A", self.chain, "-o", interface)
                device.shell(command, "-w", "-A", self.chain, "-j", "RETURN")
                device.shell(command, "-w", "-I", "OUTPUT", "1", "-m", "owner",
                             "--uid-owner", self.uid, "-j", self.chain)
        except Exception:
            self.close()
            raise

    def read(self) -> dict[str, int]:
        counts = {}
        for command in self.tables:
            output = self.device.shell(command + "-save", "-c")
            total = 0
            found = 0
            for line in output.splitlines():
                match = re.match(r"\[(\d+):\d+\] -A " + self.chain + r" -o (\S+)\s*$", line)
                if match:
                    found += 1
                    total += int(match.group(1))
            if found != len(self.physical):
                raise Incomplete("Could not read all independent physical UID counters")
            counts[command] = total
        return counts

    def close(self):
        for command in self.tables:
            for args in [("-D", "OUTPUT", "-m", "owner", "--uid-owner", self.uid, "-j", self.chain),
                         ("-F", self.chain), ("-X", self.chain)]:
                try:
                    self.device.shell(command, "-w", *args)
                except Incomplete:
                    pass
        self.tables = []


def wait_for(predicate, seconds: int, description: str):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        value = predicate()
        if value:
            return value
        time.sleep(1)
    raise Incomplete("Timed out waiting for " + description)


def strict_dns_state(device: Device) -> bool:
    device.shell("am", "start", "-n", COMPANION + "/.MainActivity")
    device.shell("uiautomator", "dump", "/data/local/tmp/piko-validation-ui.xml")
    content = device.shell("cat", "/data/local/tmp/piko-validation-ui.xml")
    try:
        return any("Tor paused; the tunnel is retained" in item.get("text", "")
                   for item in ET.fromstring(content).iter("node"))
    except ET.ParseError:
        return False


def sha256(path: Path) -> str:
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def require_blocked(entry: dict) -> None:
    """Only call after proving the negative experiment's intended TUN/state interval."""
    name = entry["name"]
    if any(result.get("success") for result in entry["results"] if result["operation"] != "dns"):
        raise Regression(name + ": a previously working network probe succeeded")
    if any(result.get("host_received", 0) for result in entry["results"]):
        raise Regression(name + ": controlled host received direct probe traffic")
    if any(entry["physical_uid_packets"].values()):
        raise Regression(name + ": target UID emitted physical-interface packets")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--companion", required=True, type=Path)
    parser.add_argument("--fixtures", type=Path, default=Path(__file__).parent / "build")
    parser.add_argument("--output", type=Path, default=Path(__file__).parent / "artifacts")
    parser.add_argument("--bootstrap-seconds", type=int, default=600)
    args = parser.parse_args()
    if os.environ.get("PIKO_DISPOSABLE_EMULATOR") != "1" or not re.fullmatch(r"emulator-\d+", args.serial):
        parser.error("Set PIKO_DISPOSABLE_EMULATOR=1 and explicitly select an emulator-NNNN serial")
    args.output.mkdir(parents=True, exist_ok=True)
    report = {"schema": 1, "status": "incomplete", "scenarios": [], "limitations": [
        "Independent diagnostic UID, not authenticated X or its auxiliary processes.",
        "No global absence-of-leaks claim; output counters may not attribute netd-owned DNS.",
        "UDP is a synthetic host echo, not a real QUIC implementation.",
        "DNS can be cached: a successful blocked-phase lookup is not direct-network evidence.",
        "No physical-device, reboot, battery or OEM matrix validation."]}
    device = Device(args.adb, args.serial)
    counters = echo = None
    configured = False
    original_dns = {}
    exit_code = 2
    try:
        if device.shell("getprop", "ro.kernel.qemu") != "1":
            raise Incomplete("Refusing non-emulator device")
        if device.shell("getprop", "ro.build.type") not in {"userdebug", "eng"}:
            raise Incomplete("A rootable userdebug/eng test image is required")
        existing_always_on = device.shell("settings", "get", "secure", "always_on_vpn_app")
        initial_tuns = device.tun()
        report["preflight"] = {
            "always_on_vpn_app": existing_always_on,
            "interfaces": device.shell("ls", "/sys/class/net").split(),
            "vpn_tun_interfaces": initial_tuns,
        }
        if existing_always_on not in {"", "null"} or initial_tuns:
            raise Incomplete("Use a fresh emulator: an existing VPN configuration was found")
        device.run("root")
        device.run("wait-for-device")
        if device.shell("id", "-u") != "0":
            raise Incomplete("adb root unavailable")
        report["android"] = {"sdk": device.shell("getprop", "ro.build.version.sdk"),
                             "fingerprint": device.shell("getprop", "ro.build.fingerprint")}
        for key in ("private_dns_mode", "private_dns_specifier"):
            original_dns[key] = device.shell("settings", "get", "global", key)
        device.shell("settings", "put", "global", "private_dns_mode", "off")
        for apk in (args.companion, args.fixtures / "probe.apk"):
            device.run("install", "-r", str(apk.resolve()), timeout=120)
        device.run("push", str((args.fixtures / "vpn-setup.zip").resolve()), SETUP)
        identity = device.probe("identity")
        if not identity.get("success") or not re.fullmatch(r"[a-f0-9]{64}", identity.get("certificate", "")):
            raise Incomplete("Probe signing identity unavailable")
        device.probe_uid = identity["uid"]
        companion_uid = int(device.shell("run-as", COMPANION, "id", "-u"))
        if device.probe_uid == companion_uid or device.probe_uid < 10000:
            raise Regression("Probe and companion must have distinct application UIDs")
        report["identities"] = {"probe": identity, "companion_uid": companion_uid,
                                "companion_apk_sha256": sha256(args.companion),
                                "probe_apk_sha256": sha256(args.fixtures / "probe.apk")}
        report["network_before_readiness"] = device.probe("network")
        report["probe_run_as_identity"] = device.shell("run-as", PROBE, "id")
        echo = Echo()
        counters = Counters(device, device.probe_uid)

        def measure(name: str, operations: list[str], blocked: bool = False) -> dict:
            before = counters.read()
            results = []
            for operation in operations:
                token = "piko-" + uuid.uuid4().hex if operation in {"udp", "local_tcp"} else None
                result = device.probe(operation, token)
                if token:
                    result["host_received"] = echo.received(token)
                results.append(result)
            after = counters.read()
            entry = {"name": name, "results": results,
                     "physical_uid_packets": {key: after[key] - before[key] for key in before},
                     "tun": device.tun()}
            report["scenarios"].append(entry)
            if blocked:
                require_blocked(entry)
            return entry

        readiness_attempts = 0
        def direct_ready():
            nonlocal readiness_attempts
            readiness_attempts += 1
            entry = measure("direct_readiness_" + str(readiness_attempts), ["dns", "tcp4", "local_tcp", "udp"])
            entry["network"] = device.probe("network")
            return all(item.get("success") and item.get("host_received", 1) == 1
                       for item in entry["results"])
        wait_for(direct_ready, 90, "direct DNS, TCP and UDP positive controls before VPN setup")
        baseline = measure("direct_positive_controls", ["tor", "dns", "tcp4", "tcp6", "local_tcp", "udp"])
        controls = {item["operation"]: item for item in baseline["results"]}
        for operation in ("tor", "dns", "tcp4", "local_tcp", "udp"):
            if not controls[operation].get("success"):
                raise Incomplete("Required direct positive control failed: " + operation)
        if "tcp4" not in device.control_addresses:
            raise Incomplete("No fixed public IPv4 TCP control address")
        if controls["tor"].get("is_tor") is not False:
            raise Incomplete("Direct baseline already uses Tor; this is not an isolated direct control")
        if not baseline["physical_uid_packets"]["iptables"]:
            raise Incomplete("Physical target-UID counter has no working positive control")
        for operation in ("local_tcp", "udp"):
            if controls[operation].get("host_received") != 1:
                raise Incomplete("Controlled host failed to observe positive " + operation)
        report["ipv6_direct_positive_control"] = controls["tcp6"]["success"]

        device.shell("am", "force-stop", COMPANION)
        configuration = ("<?xml version='1.0' encoding='utf-8' standalone='yes' ?><map>"
                         f"<string name='target'>{PROBE}</string>"
                         f"<string name='certificate'>{identity['certificate']}</string>"
                         f"<int name='uid' value='{identity['uid']}' />"
                         "<boolean name='requested' value='true' /></map>")
        device.shell("run-as", COMPANION, "mkdir", "-p", "shared_prefs")
        device.shell("run-as", COMPANION, "sh", "-c", "cat > shared_prefs/tor_configuration.xml", data=configuration)
        configured = True
        if not device.vpn("enable"):
            raise Incomplete("Platform always-on + lockdown setup failed")
        wait_for(device.tun, 30, "VPN TUN")

        def healthy(label: str):
            attempts = []
            before = counters.read()
            def check():
                result = device.probe("tor")
                attempts.append(result)
                return result.get("success") and result.get("is_tor") is True
            try:
                wait_for(check, args.bootstrap_seconds, "real HTTPS Tor exit")
            finally:
                after = counters.read()
                delta = {key: after[key] - before[key] for key in before}
                report["scenarios"].append({"name": label, "tor_attempts": attempts,
                                            "physical_uid_packets": delta, "tun": device.tun()})
            if any(delta.values()):
                raise Regression(label + ": probe UID emitted physical-interface packets")
            if not device.vpn("status"):
                raise Regression("Tor path is reachable without required recorded lockdown state")

        healthy("healthy_tor_before_failures")
        through_tor = measure("tor_positive_controls", ["dns", "tcp4", "tcp6"])
        if any(through_tor["physical_uid_packets"].values()):
            raise Regression("Healthy Tor probes emitted target-UID physical-interface traffic")
        healthy_ops = [item["operation"] for item in through_tor["results"] if item.get("success")]
        if "tcp4" not in healthy_ops:
            raise Incomplete("TCP4 positive control does not work through Tor")
        measure("unsupported_local_and_udp_blocked", ["local_tcp", "udp"], blocked=True)
        original_tun = device.tun_identity()
        device.shell("settings", "put", "global", "private_dns_specifier", "piko-validation.invalid")
        device.shell("settings", "put", "global", "private_dns_mode", "hostname")
        wait_for(lambda: strict_dns_state(device), 60, "companion's strict DNS pause state")
        if device.tun_identity() != original_tun:
            raise Regression("Strict-DNS pause destroyed or replaced the retained TUN")
        negative_ops = ["tor", "tcp4", "local_tcp", "udp"]
        if "tcp6" in healthy_ops:
            negative_ops.insert(2, "tcp6")
        paused = measure("engine_paused_same_tun", negative_ops + ["dns"], blocked=True)
        paused["retained_tun_ifindices"] = original_tun
        if device.tun_identity() != original_tun or not device.vpn("status"):
            raise Regression("Paused-engine measurement did not retain its TUN and lockdown")
        device.shell("settings", "put", "global", "private_dns_mode", "off")
        device.shell("settings", "delete", "global", "private_dns_specifier")
        healthy("recovery_after_engine_pause")

        pid = device.shell("pidof", COMPANION)
        if not re.fullmatch(r"\d+", pid):
            raise Incomplete("Cannot identify a single companion process to crash")
        device.shell("kill", "-9", pid)
        wait_for(lambda: not device.tun(), 10, "TUN disappearance after process kill")
        stop = threading.Event()
        observed_tuns: list[list[str]] = []
        monitor_errors: list[str] = []
        def monitor():
            while not stop.is_set():
                try:
                    observed_tuns.append(device.tun())
                except Incomplete:
                    monitor_errors.append("adb_monitor_failed")
                stop.wait(0.5)
        watcher = threading.Thread(target=monitor, daemon=True)
        watcher.start()
        try:
            crash = measure("process_kill_lockdown", negative_ops)
        finally:
            stop.set()
            watcher.join(timeout=65)
        crash["tun_observation_samples"] = len(observed_tuns)
        crash["tun_reappeared"] = any(observed_tuns)
        # An automatic safe restart can restore Tor; that invalidates the tunnel-absent
        # interval, but is not itself a fail-closed regression. Direct egress still fails.
        if any(crash["physical_uid_packets"].values()) or any(
                item.get("host_received", 0) or (
                    item.get("operation") == "tor" and item.get("success")
                    and item.get("is_tor") is False)
                for item in crash["results"]):
            raise Regression("Direct probe traffic escaped after process crash")
        if monitor_errors or any(observed_tuns):
            raise Incomplete("TUN reappeared or monitoring failed: exact tunnel-absent experiment is inconclusive")
        require_blocked(crash)
        if not device.vpn("status"):
            raise Regression("Android lockdown disappeared after process crash")

        # Manual recovery is distinct from automatic recovery; do not claim the latter.
        device.shell("am", "start-foreground-service", "-n", COMPANION + "/.TorVpnService",
                     "-a", "app.piko.tor.START")
        healthy("manual_recovery_after_process_kill")
        report["status"] = "passed_bounded_scenarios"
        exit_code = 0
    except Regression as error:
        report.update(status="failed", reason=str(error))
        exit_code = 1
    except (Incomplete, OSError, ValueError, KeyError) as error:
        report.update(status="incomplete", reason=str(error))
        exit_code = 2
    finally:
        if counters:
            counters.close()
        if echo:
            echo.close()
        if configured:
            try:
                device.vpn("disable")
                device.shell("am", "force-stop", COMPANION)
            except Incomplete:
                report["cleanup_vpn"] = "incomplete; discard this test emulator"
        for key, value in original_dns.items():
            try:
                if value in {"", "null"}:
                    device.shell("settings", "delete", "global", key)
                else:
                    device.shell("settings", "put", "global", key, value)
            except Incomplete:
                report["cleanup_dns"] = "incomplete; discard this test emulator"
        (args.output / "network-report.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
        print(json.dumps({"status": report["status"], "report": str(args.output / "network-report.json")}))
    return exit_code


if __name__ == "__main__":
    raise SystemExit(main())
