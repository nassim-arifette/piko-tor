# Piko Tor — experimental X-only Tor

**The requested default has changed:** Tor ON sends X through Tor while other apps
keep their ordinary network; Tor OFF restores X's ordinary direct network.
The companion `0.2.0-experimental` release candidate (version code 2) is unpublished.
**Publication and final validation are PENDING.** No result for an earlier build
certifies this final release candidate.

The source includes upstream Piko 3.10.0, with bundle version
`3.10.0-tor.1`. The published `3.9.0-tor.2` bundle and companion
`0.1.0-experimental` still use the older strict behavior. `patches-bundle.json`
selects that existing release, not this new default. The current X patch requires
companion version code 2 or newer and opens this installation guide when the app
is missing or outdated; it does not offer the old APK as a compatible update.
The [dated report](../docs/validation/2026-11-08-piko-tor.md) separates the pending
revision from earlier strict-mode evidence.

## Intended default behavior and limits

| State | X | Other apps in the profile |
| --- | --- | --- |
| ON, Tor ready | Selected X traffic uses the companion's Tor tunnel within its supported scope. | Keep their ordinary network. |
| ON, engine unavailable but TUN retained | Traffic is blocked; no internal direct fallback. | Keep their ordinary network. |
| Explicit OFF | Tunnel closes and X resumes its ordinary direct network. | Keep their ordinary network. |
| Unexpected TUN loss, service death or consent revocation | X may reconnect directly without optional Android lockdown. | Remain subject to their ordinary network configuration. |

These are requirements for the new default, not a claim that its pending tests
have passed. An open TUN or Arti ready event alone does not prove end-to-end Tor
routing. Do not interpret the mode as a crash-proof kill switch or a guarantee
against all DNS, IPv6, native-process or other leaks.

Android's **Always-on VPN** and **Block connections without VPN** are optional
advanced configuration. They are not enabled automatically or required by the
normal setup. Lockdown can block other apps in the profile and keep X blocked
after OFF; it therefore changes the normal toggle behavior. A separate profile
may isolate an intentionally strict setup, but is not required for this feature.

## Architecture and boundaries

- X + the optional **Tor network companion** Morphe patch exposes a settings entry
  and visible handoff to `app.piko.tor.MainActivity`. The patch is disabled by
  default. No VPN service or Tor native library is injected into X.
- Piko Tor (`app.piko.tor`, Android 10/API 29+) has its own APK/UID, `VpnService`
  and Onionmasq/Arti JNI runtime. Shared signing with X is not required.
- The selected X package is scoped with `addAllowedApplication`; other apps are
  excluded from the tunnel. Pairing checks the installed package, certificate and
  distinct UID. Reinstalling or changing identity invalidates the association.
- X reports companion installation/version, not a trusted protection claim.
  Sensitive actions require the visible companion UI; no remote control API may
  disable the VPN or Android policy without user action.
- The unpublished development source has a best-effort visible-VPN guard before
  requesting consent and rechecks consent/target identity after the dialog.
  Android can hide a per-app VPN that excludes the companion. The published
  **3.9.0-tor.2 companion lacks these safeguards**.

The TUN carries IPv4/IPv6 and Onionmasq's synthetic DNS addresses
`169.254.42.53` and `fe80::53`. Supported TCP/DNS traffic is forwarded through
Arti. Unsupported UDP/QUIC is rejected while the tunnel is active; it is not sent
directly to make media work. X auxiliary or isolated processes need separate
coverage tests.

Only the current Tor engine may protect its outbound sockets against a VPN loop.
If the engine pauses, keep the TUN instead of creating a direct bypass. An explicit
OFF action is different: it closes the tunnel so X can use the ordinary network.
Without Android lockdown, the same direct exposure can occur after unexpected
tunnel loss. Strict Private DNS may suspend the engine; do not silently change the
user's DNS policy to restore connectivity.

## Development setup on a test device

The steps below target the **unpublished 0.2 release candidate**. The old published download
does not provide this default.

1. Build the development companion and patch bundle using the instructions below;
   install with signing identities you trust. The companion and patched X are
   separate APKs. Preserve existing app data/signatures when updating.
2. Open X's Tor settings or the companion launcher, and confirm X's actual installed
   package, certificate and distinct UID.
3. Inspect Android VPN settings. Only one VPN can be active per profile; do not
   replace another provider unintentionally. The visible-VPN guard is best effort.
4. For the normal ON/OFF behavior, leave **Always-on VPN** and **Block connections
   without VPN** off. If upgrading from the old strict setup, turn those options
   off manually in Android. Retained lockdown can keep X and other apps blocked
   even after the companion stops.
5. Start Tor in the companion and grant Android VPN consent. Wait for readiness and
   verify actual Tor egress; a bootstrap event alone is not proof. Check that an
   unselected app still has its ordinary connection.
6. Use the companion's explicit OFF action to restore direct X connectivity. Verify
   both X and another app remain usable. If system lockdown remains enabled, the
   companion cannot silently remove that policy.

A logged-in X account remains identifiable. Tor changes the network path, not the
account identity; the ISP normally sees Tor connectivity.

### Android VPN settings on the tested HyperOS firmware

On the Android 14 Xiaomi used for the earlier strict tests, the normal HyperOS VPN
entry requested a new screen lock. The existing Android VPN list could instead be
opened through its exported Settings activity:

```sh
adb -s DEVICE_SERIAL shell am start -n com.android.settings/.SubSettings \
  --es :settings:show_fragment com.android.settings.vpn2.VpnSettings
```

Select Piko Tor's arrow to inspect its actual Always-on/lockdown switches. For the
new normal mode, both should be off. This navigation was verified on that firmware;
it is not a universal OEM interface. It did not create/change a PIN, install a
helper APK, use root, or grant privileged settings permission.

### Published legacy release

[Add the legacy source to Morphe](https://morphe.software/add-source?github=nassim-arifette/piko-tor&name=Piko%20Tor%20experimental)
or import `patches-3.9.0-tor.2.mpp` from the
[published release](https://github.com/nassim-arifette/piko-tor/releases/tag/v3.9.0-tor.2).
Its manifest is on `main`; leave Morphe's **Use pre-release** toggle off.
Use one Piko source for X and enable **Tor network companion** in expert mode.

That legacy companion requires Android Always-on VPN and lockdown to start its
engine. It can block the rest of the profile and leave X blocked after stopping;
it is not the new ON/OFF experience. X patched with that legacy bundle offers its
legacy APK; the new patch opens installation instructions and refuses to open a
version-code-1 companion. No new release or prepatched X APK is implied by
the source changes. Published debug signing/checksums do not independently
authenticate a publisher.

### Signing and migration for the release candidate

The downloaded public `0.1.0-experimental` APK was verified against its release
SHA-256 and has certificate fingerprint
`7cb77ebdbbd49e40f8f447100b99958788274e8d5c12cf0375649ffe446d9456`.
The persistent companion key used for the existing local phone installation has
fingerprint `b1fa54b0d1876c71b26586ced20d78aab26716b2dcd812f7c0f207177c996866`.
The release candidate is intended to use that persistent key. Final signing and
publication must still be verified.

These certificates differ, and no signing-key rotation lineage is configured.
A new APK signed with the persistent key can preserve update continuity for the
matching local installation, but cannot normally update the old public debug-signed
companion in place. This affects `app.piko.tor`, not the separately signed X app.

For an old public installation that Android rejects as an incompatible update,
stop the legacy companion and turn off its old Always-on/lockdown settings before
replacing only the companion. Removing it loses its saved pairing/settings; pair X
and grant VPN consent again afterward. **Do not uninstall X or clear X's data for
this companion migration.** An installation already using the persistent certificate
should use an in-place update instead. Keep the new normal mode's system options off.

## Build (Linux x86_64)

Prerequisites: JDK 17, Rustup, Python 3, git, curl, unzip, Android SDK licenses, and the
repository's usual GitHub Packages read credentials. Never put tokens in source.
`GITHUB_TOKEN`/`GITHUB_ACTOR` are read by the existing Gradle configuration.

```sh
# Installs pinned Android SDK/NDK, Rust and cargo-ndk. Review Android license prompts.
scripts/setup-tor-android.sh
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/sdk}"
export PATH="$HOME/.cargo/bin:$PATH"

# If cleaning, clean BEFORE building native libraries (they live under build/).
./gradlew clean
scripts/build-tor-native.sh
# Run these sequentially to bound Gradle memory, as in the passing CI job.
./gradlew --no-daemon --max-workers=2 '-Dorg.gradle.jvmargs=-Xmx2g' \
  :companion:verifyTorNative :companion:assembleDebug :companion:assembleRelease :companion:testDebugUnitTest :companion:lintDebug :companion:lintRelease
./gradlew --no-daemon --max-workers=2 '-Dorg.gradle.jvmargs=-Xmx2g' buildAndroid
```

Outputs: `patches/build/libs/patches-3.10.0-tor.1.mpp`,
`companion/build/outputs/apk/debug/companion-debug.apk` and
`companion/build/outputs/apk/release/companion-release-unsigned.apk`.
The debug APK is for tests. The release APK must be signed separately with the
persistent companion key; CI does not supply that private key. Preserve the same
certificate for in-place companion updates, then verify the final signature and
16 KiB alignment. X keeps its own Morphe signing identity.
For a smaller development APK, use matching native/Gradle ABI selections, e.g.
`scripts/build-tor-native.sh arm64-v8a` and `-PtorAbis=arm64-v8a`.

Native inputs: Onionmasq revision `086d05145f5aa711ac300d04edc19c26360bac29`,
its locked Cargo dependency graph (Arti 0.45.0), Rust 1.97.1, cargo-ndk 4.1.2,
NDK 27.3.13750724, Android native platform 29. `native/privacy.patch` disables
upstream tracing (including verbose builds). No PCAP/bridge/TURN controls are exposed.
Native compilation receives an allowlisted environment, not ambient credentials.
The build copies upstream's `dylib` output, strips symbols, checks every selected
ABI's ELF machine, 16 KiB LOAD alignment, non-executable stack, Android dependencies,
and JNI exports, then records SHA-256 hashes. Gradle refuses missing/wrong-revision
or changed binaries and missing/unresolved notices. Pinning is not a reproducibility
measurement or a substitute for dependency/security review.

To apply only the optional patch to the supported X bundle with Morphe Desktop
2.1.1 and Java 21+ (the versions used for the 2026-11-08 patching check):

```sh
java -jar morphe-desktop-2.1.1-all.jar patch --exclusive \
  -p patches/build/libs/patches-3.10.0-tor.1.mpp -e 'Tor network companion' \
  -o x-piko-tor.apk X-12.19.1-release.0.apkm
```

Use the bundle generated by YOUR build if the project version changes. This command
selects Tor alone. A separate 2026-11-08 check also applied 71 existing patch
selections plus Tor successfully; patching success does not establish their Android
runtime behavior.

## Validation evidence and remaining gates

### Release candidate — publication and validation pending

The 0.2 release candidate changes the routing policy. Its final build, unit tests,
emulator checks and real-device ON/OFF checks are **PENDING**. Required evidence
includes X using Tor while another app remains direct, explicit OFF restoring
direct X traffic, and engine suspension retaining the TUN without a direct fallback.
Unexpected tunnel loss must be measured with system lockdown off and reported
honestly; the earlier strict-mode block cannot be assumed in that configuration.

The intended experimental release contains `patches-3.10.0-tor.1.mpp`, the signed
`piko-tor-0.2.0-experimental.apk` and `SHA256SUMS`, with sources in tag
`v3.10.0-tor.1`. Publication is pending; the existing manifest and download remain
on the legacy release until the new assets are available. A checksum records file
integrity, not an independent publisher identity.

### Prior strict build and patching checks (2026-11-08)

The following November results describe the earlier strict build merged at
`b78c21f4`, before the new default. They do not validate the 0.2 behavior change.

The [full workflow](https://github.com/nassim-arifette/piko-tor/actions/runs/42499335203)
passed for revision `e3d21b5a`, including the companion, fixture and network jobs.
The companion job packaged and verified all four native ABIs. It passed 17 JUnit
and Robolectric tests (including 512 policy combinations), Android lint,
16 KiB APK alignment and APK signature verification. The native libraries were
restored from the exact-input cache and checked by `verifyTorNative`; this run did
not rebuild Rust. The patch bundle also built successfully.

Real Morphe Desktop 2.1.1 patching on Windows passed against the original X
12.19.1-release.0 APKM: Tor alone in 192.8 seconds, and 71 existing selections plus
Tor (72 patches) in 191.2 seconds, with no failed patches. Static output checks
confirmed the companion visibility query and no Tor native library inside X.
The combined output retained package `com.twitter.android` and used the selected
version code `2147483647`.

On an Android 14 Xiaomi, Morphe Manager 2.5.0 also rebuilt all 72 patches using
the existing signing key. X and the locally signed companion installed successfully
with distinct UIDs. X's Tor settings detected and opened the companion, and app
association succeeded. The subsequent physical-device network checks are described
below and in the dated report.

### Prior strict-mode emulator checks (2026-11-08)

The independent diagnostic app on Android 15/API 35 obtained `IsTor=true`, successful
DNS and IPv4/IPv6 TCP connections, with zero measured physical-interface packet
counts for its UID. Local TCP and synthetic UDP controls that worked directly were
blocked through the VPN, and their host listeners received no connections/packets.

Strict Private DNS paused the engine while retaining `tun0`, interface index `18`.
The probes then failed, including timeouts for previously working TCP addresses,
with zero measured physical UID counts. Setting Private DNS back to **Off**
recovered `IsTor=true`.
After `kill -9`, the TUN disappeared and connection probes were denied with zero
measured physical UID counts. The TUN did not reappear during four samples covering
about two seconds; this short window does not establish automatic-restart behavior.
Manual recovery restored `IsTor=true`.

Direct IPv6 was unavailable in the baseline, so the test cannot establish absence
of direct IPv6 leakage on an IPv6-capable underlying network. UID counters may miss
DNS sent by Android's `netd`; synthetic UDP is not real QUIC. These are measured
diagnostic-app scenarios, not exhaustive coverage of X or its auxiliary processes.
The [validation report](../docs/validation/2026-11-08-piko-tor.md) records the exact
APK hash and evidence. The emulator results are separate from the real-X checks
below.

### Prior strict-mode physical-device X checks (2026-11-08)

On the Android 14 Xiaomi, the real installed X app (`com.twitter.android`, UID
`10428`) opened its own `BrowserActivity` and `android.webkit.WebView`. A fresh
Tor-check URL returned `IsTor=true` while Android reported `app.piko.tor` as the
always-on VPN with lockdown enabled. Native profile screens loaded, an image was
visible and two observations showed video playback progressing. A fresh search
through X's normal UI returned four result rows and no error nodes. Initial
`twitter://search` deep links failed; the successful normal-UI search does not
support attributing that deep-link failure to Tor.

Stopping the companion through its UI retained Android always-on/lockdown and
removed the TUN. A new URL in X's WebView produced `ERR_NETWORK_ACCESS_DENIED`;
a fresh native search returned no rows and two error nodes. This was a manual-stop
test on the phone, separate from the emulator's process-kill test.

These observations exercise the real X app. They do not provide packet-level
attribution for all native traffic, DNS, IPv6, QUIC or auxiliary processes, nor do
they establish full login, messaging, notification or media coverage. Screenshots,
Tor exit addresses and account metadata remain local.

### Historical build checks (2026-10-19)

Historical results from the development VM (2026-10-19), before the upstream
3.10.0 synchronization. These are not results from the current development version:

- `buildAndroid`; companion Java compilation, debug APK assembly and Android lint.
- JUnit state-policy checks, including all 512 combinations of nine prerequisite
  signals. These do **not** exercise Android lifecycle or actual native callbacks.
- Real native compilation for arm64-v8a, armeabi-v7a, x86, x86_64; ELF/JNI/dependency
  verification and packaging of all four libraries in the companion APK.
- `zipalign -c -P 16 4` and `apksigner verify` on the companion; signature verification
  on the patched X APK. Debug signing is not publisher authentication.
- Real Morphe patch application to X 12.19.1-release.0 / 312191000 (input APKM SHA-256
  `e288abb4b3bdf94c3d0397137b42cbf27a8f90049276166aee0383ad694dbd2c`). Static output
  inspection: distinct package names, companion visibility query, protected service
  only in the companion, no Onionmasq library injected into X.

### Historical Android runtime checks (2026-10-19)

Runtime code revision tested on that date: `4602d80a`. Companion debug APK SHA-256:
`4ae13fd75fcbeb6e9c9a1b730680eaabb78866777ebb2dbc54d053bfab7825bf`.
Android 15/API 35 Google APIs x86_64 emulator, fingerprint
`google/sdk_gphone64_x86_64/emu64xa:15/AE3A.240806.043/12960925:userdebug/dev-keys`.
This is **not** a physical-device or Android/OEM compatibility matrix.

The companion ran as UID 10209/x86_64, real patched X as UID 10210/arm64-v8a,
and an independently signed diagnostic app as UID 10211. These are observed lab
UIDs, not hard-coded values. X's original x86/x86_64 splits lack `libyoga.so` and
crash before login on this emulator; installing the unchanged patched APK with
`adb install -r --abi arm64-v8a` used its existing ARM libraries through Android's
native bridge. No replacement Yoga library or X binary workaround was added.

| Scenario | Observed result and scope |
| --- | --- |
| Real X integration | Package/certificate/UID pairing, VPN consent, Android always-on/lockdown, native bootstrap, actual X username form and Piko settings handoff worked. No account credentials were used. |
| STOP regression | OFF state, saved `requested=false`, no TUN/duplicate descriptors/native worker, target picker usable. Repeated stop/start and reassociation to the diagnostic UID worked without disabling lockdown. |
| Diagnostic positive controls | HTTPS Tor-check returned `IsTor=true`; ordinary DNS and IPv4/IPv6 TCP succeeded. Controlled DNS and synthetic UDP443 probes appeared on TUN, not physical interfaces, with zero physical target-UID counter increases in the measured windows. This is diagnostic-app evidence, not every X code path or real QUIC. |
| Engine paused with TUN retained | Strict Private DNS kept the same TUN/service descriptor while previously working diagnostic controls failed. Restoring Automatic restored the successful controls. This does not simulate every native engine failure. |
| Process kill / TUN loss | After `kill -9`, diagnostic connections were denied with no direct UID-counter increase. **No automatic process/TUN restart within 120.7 seconds.** Android logged a `DeadObjectException` during VPN interface removal/unbinding; the exact sole cause is not proven. |
| Manual recovery | Visible Prepare/start restored a new TUN, worker and successful diagnostic controls with always-on and lockdown still enabled. |
| Consent removal | Android's Forget VPN removed TUN and allowed direct DNS/TCP4/UDP443; Tor-check returned `IsTor=false`, as warned. Direct IPv6 was unavailable on the underlying emulator network. System protection must not be claimed after revocation. |
| Final real X interval | X was reassociated and both Android settings restored. UID-attributed counters increased by 225 IPv4 / 3 IPv6 TUN packets, with zero physical UID delta. Capture showed bidirectional TUN DNS/IPv4; this is not exhaustive IPv6/X coverage. |
| Other applications | Diagnostic app received permission/DNS failures again while X alone was selected. Per-app lockdown can block other applications rather than allow them direct access. |

Independent observations used scoped packet counts plus Android UID, interface,
descriptor and system-policy checks. The diagnostic application's healthy controls
were established before its negative tests; earlier `su`-UID shell probes timed out
even when healthy and **are not evidence of fail-closed behavior**. No proxy/exit-IP
result alone was treated as an absence-of-leaks proof.

**Unresolved:** an earlier physical DNS request/reply was not conclusively attributed.
New controlled-query captures do not retrospectively explain it. No complete DNS
leak-prevention claim is made. Automatic post-kill recovery remains an availability
limitation even though the tested negative window did not show direct target traffic.

Earlier code `24b99366` also showed post-reboot startup and TUN retention through
airplane mode; those observations are not final-revision early-boot/network-transition
validation. Not yet demonstrated on the final revision: reboot/early-boot traffic,
airplane mode/Wi-Fi/mobile transitions, authenticated X/media/DMs/notifications,
comprehensive WebView/native/auxiliary/isolated-process coverage, real QUIC or exhaustive
UDP destinations, replacement identity invalidation, provider change/uninstall,
background/battery restrictions, battery/memory/latency, physical devices and
non-regression of other patches. Runtime evidence and recording are linked from
[the original PR](https://github.com/nassim-arifette/piko-tor/pull/1) and its Devin session.

Before describing a tested scenario as protected, run it with independent packet
capture (including DNS and IPv6), record the target's real UID and Android lockdown
settings, test cold start and each negative transition, and show both the successful
Tor path AND the absence of direct target traffic. A Tor exit IP alone is insufficient.
Do not log credentials, browsing destinations, packet payloads, or account tokens.

## Licenses / corresponding source

Piko is GPLv3 with its root `NOTICE` preserved. Onionmasq/Arti offer MIT/Apache-2.0;
individual transitive components retain their own licenses. Generated APK assets
contain Piko notices, a Cargo inventory with original texts and supplement provenance,
and Rust standard-library notices. See `native/licenses/README.md`, in particular
the separate IPFire GeoIP database attribution/license. These are attribution and
source records, not an automated legal opinion. A distributor must provide the
corresponding source/build instructions and preserve all applicable obligations;
do not distribute only a binary or present a debug prototype as production-ready.
