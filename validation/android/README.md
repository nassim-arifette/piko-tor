# Isolated Android network validation

These are test fixtures, not production components. They do not change Piko's
Gradle settings, companion code or X patches. Never run this harness against a
personal phone or an emulator containing accounts or another VPN. It explicitly
requires `PIKO_DISPOSABLE_EMULATOR=1`, an `emulator-NNNN` serial, `ro.kernel.qemu=1`,
a `userdebug`/`eng` image and successful `adb root`. All ADB commands address that
serial, and no command kills or restarts the shared ADB server.

Use a new **Android 15 / API 35 Google APIs x86_64** emulator on a Linux runner
with KVM. Google Play Store images do not support `adb root`. The companion must
contain its real x86_64 Onionmasq/Arti binary. These checks do not replace the
real-X patch application and runtime tests.

## Build and run

Prerequisites: JDK 17, Python 3.11+, Android platform 36 and build-tools 36.0.0,
platform-tools, and the already-built companion debug APK. The fixture build uses
`javac`, `d8`, `aapt`, `zipalign` and `apksigner`; no additional Gradle dependency
or module is needed.

```sh
bash validation/android/build-probe.sh
PIKO_DISPOSABLE_EMULATOR=1 python3 validation/android/run-network-checks.py \
  --serial emulator-5554 \
  --companion companion/build/outputs/apk/debug/companion-debug.apk \
  --output validation/android/artifacts
```

The probe APK is independently debug-signed with a generated fixture key. The
runner reads its actual installed signing digest and UID through instrumentation,
checks that its UID differs from the companion, and seeds the companion's existing
`tor_configuration` preferences using `run-as`. This deliberately tests network
behavior, not the production pairing/consent UX. No account credentials are used.

The root-only emulator helper configures always-on and lockdown through the named
`IVpnManager.setAlwaysOnVpnPackage` API and reads the state back. It does not guess
Binder transaction numbers or merely edit settings without applying them. The
method signatures were checked against
[Android 15's interface](https://github.com/aosp-mirror/platform_frameworks_base/blob/android15-release/core/java/android/net/IVpnManager.aidl).
The helper refuses to replace an existing different always-on provider. The runner
also refuses an existing TUN or always-on configuration before making changes.

## Evidence and decision rules

Before enabling the VPN, the runner disables Private DNS on this disposable
emulator and retries the direct DNS, public TCP and local TCP/UDP controls until
a 90-second deadline. Attempts and independent host receptions are retained;
the final baseline must still pass every required control. A failed baseline is
incomplete validation, never a successful blocking test. The report includes
the probe's default-network capabilities, DNS-server count, default-route counts,
INTERNET permission and sanitized exception causes/errno values to diagnose
emulator setup failures. Private DNS returns to `off` after the strict-DNS pause,
and the runner restores the emulator's original settings during cleanup.

1. **Direct positive controls.** The probe's own UID must reach the HTTPS Tor
   check and receive `IsTor=false`, resolve its name, open IPv4 TCP, and receive
   TCP/UDP echoes from the runner through emulator host alias `10.0.2.2`.
   Runner listeners bind only host loopback ports 18080/18081. The independent
   IPv4 UID counter must show physical output. An unavailable required control
   makes the run incomplete, never a successful blocking test.
2. **Real Tor path.** After VPN and lockdown configuration, the same probe must
   receive HTTPS `IsTor=true`, establish IPv4 TCP and emit no target-UID packets on
   physical interfaces. IPv6 availability is recorded separately. The JSON
   preserves no source/exit IP, response body or account information.
3. **Unsupported/local traffic.** Previously successful host TCP and UDP probes
   must fail under protection. The host must receive neither token, and physical
   target-UID counters must stay unchanged. This exercises a synthetic UDP
   datagram, not a real QUIC stack.
4. **Engine paused with the TUN retained.** Strict Private DNS with a reserved
   `.invalid` hostname invokes the companion's existing refusal policy. Its
   visible pause state and unchanged TUN name **and kernel ifindex** are checked;
   recreating an interface under the same `tun0` name is not accepted as retaining
   the original tunnel. The previously working
   probes must fail without host receipt or physical UID-counter increases.
   The external TCP controls use the **same numeric destination address** that
   succeeded directly and through healthy Tor, without a DNS lookup in the paused
   phase. This prevents failed strict-DNS resolution from masquerading as a stopped
   TCP/Tor engine. The runner keeps those test addresses only in memory.
   Restoring the original non-strict mode must recover a real Tor response.
   This is a policy-induced engine stop, not arbitrary native-fault injection.
5. **Process killed / tunnel absent.** `kill -9` targets the exact companion PID.
   The runner waits for TUN disappearance, polls interface presence while probing,
   and verifies lockdown remains configured. Direct UID or host egress fails.
   If the TUN reappears during measurement, the exact tunnel-absent experiment is
   marked inconclusive rather than treating a safe Tor restart as a regression.
   Manual restart must then recover the real Tor path. Automatic recovery is not
   claimed by that manual-recovery result.

The counting chains contain counter-only rules and a `RETURN`; they add no
`ACCEPT`, `DROP`, bypass or routing policy. They are scoped to the probe UID and
unique chain names, with cleanup in `finally`. Logs store only sanitized results,
counts, fixture hashes, UIDs and the emulator fingerprint. No packet capture,
general logcat or HTTP body is retained.

`network-report.json` is written even when an experiment fails. Exit codes:

- **0:** the bounded scenarios passed on this image/run;
- **1:** a demonstrated assertion failed, such as direct target traffic;
- **2:** prerequisites, positive controls or the intended experiment state were
  unavailable/inconclusive. A CI job must not report this as a pass.

The runner removes only its own counting chains, clears its test VPN configuration
and restores the previous Private DNS values at the end. Discard the emulator
afterward; fixture applications and their test data intentionally remain for
inspection until its destruction.

## Limits

The diagnostic UID is **not X**. This does not establish coverage of authenticated
X, WebView/native/isolated processes, notifications, media, boot, network switching,
physical OEM devices, battery restrictions or exhaustive UDP/IPv6 destinations.
DNS answers can be cached; a lookup that succeeds while blocked is recorded but
is not interpreted as direct egress. UID output rules may not attribute DNS sent
by `netd`, so these results alone cannot prove absence of all DNS leaks. A complete
claim requires independent DNS destination observation and real-X traffic tests.

The 500 ms TUN observer bounds the sampled interval; it does not prove that an
interface never existed between observations. Bootstrap and the Tor check depend
on external network availability. The harness is designed to expose missing
evidence rather than turn these limitations into successful assertions.
