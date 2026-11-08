# Piko Tor — experimental milestone

**Not validated for sensitive use. There is no “protected” indicator.** Building an APK,
an open TUN, or Arti's bootstrap-ready event does not prove that X cannot leak traffic.

The current source includes upstream Piko 3.10.0 and uses the unpublished development
version `3.10.0-tor.1-dev.1`. The published patch bundle remains `3.9.0-tor.2`, based
on Piko 3.9.0, with companion APK `0.1.0-experimental`. `patches-bundle.json` still
selects that existing release for Morphe; it does not distribute the current source.
The dated validation evidence below describes earlier builds, not validation of
the 3.10.0 synchronization.

## Architecture and boundaries

- X + the optional **Tor network companion** Morphe patch: a settings section and an
  explicit, visible handoff to `app.piko.tor.MainActivity`. Disabled by default. No
  VPN service, Tor native library, control broadcast, or trusted remote status in X.
- Piko Tor (`app.piko.tor`, Android 10/API 29+): its own APK/UID, `VpnService`, and
  Onionmasq/Arti JNI runtime. No shared signing certificate is required.
- The user confirms X's actual installed package, signing-certificate SHA-256, and
  distinct UID in the companion. Re-signing/reinstalling with a new UID invalidates
  that association. Shared-UID targets are rejected. The picker supports renamed
  packages; it cannot certify that the selected app really is X.
- `startActivityForResult` lets Android identify the caller. Caller-supplied extras
  are never trusted. Sensitive actions require the visible companion UI; its
  service requires the system `BIND_VPN_SERVICE` permission. No IPC API can stop the
  VPN or disable lockdown. X reports only installation/version, never protection.

The companion routes the selected application's UID through an IPv4/IPv6 TUN
using `addAllowedApplication`, with Onionmasq's synthetic DNS addresses
`169.254.42.53` and `fe80::53`. Onionmasq forwards supported TCP/DNS traffic via Arti.
UDP/QUIC relaying and bypass UID APIs are not exposed; the pinned upstream defaults
to UDP rejection, not a direct fallback. Media and auxiliary/isolated processes
may therefore fail; coverage must be observed on Android, not inferred from hooks.

Only the Tor engine requests `protect(socket)`; it is refused unless this engine
generation is current and Android always-on plus lockdown are detected. A retained
TUN descriptor is intended to blackhole traffic while the engine is stopped.
Native execution owns a separate duplicate descriptor. Worker shutdown is
asynchronous; another worker cannot begin until the previous one exits.
STOP and revocation explicitly release resources instead of waiting for Android to
destroy a still-bound service. Target changes remain blocked until the native
worker exits; a subsequent start reloads the selected identity.

## Setup on a test device

[Add this fork to Morphe](https://morphe.software/add-source?github=nassim-arifette/piko-tor&name=Piko%20Tor%20experimental)
or import `patches-3.9.0-tor.2.mpp` from the
[published experimental release](https://github.com/nassim-arifette/piko-tor/releases/tag/v3.9.0-tor.2).
Leave the source's **Use pre-release** toggle off: Morphe switches that toggle to
`dev`, but this fork publishes its experimental manifest on `main`. Use this
source instead of upstream Piko for X to avoid duplicate patches. In expert mode,
enable **Tor network companion** and provide your original X 12.19.1-release.0 APKM.

When the companion is missing, X offers **Download Piko Tor**. An explicit
confirmation opens the companion APK from `v3.9.0-tor.2` in an external handler; Android
still requires installation consent. Installed companions open the existing
pairing/setup screen. No background download, installer permission, automatic
installation or weakening of lockdown is added. If lockdown blocks the browser,
transfer the APK from another device instead of disabling lockdown.

The published release uses debug signing for the companion and has no detached publisher
signature for the bundle. SHA-256 checksums detect mismatched files, not an
independent publisher identity. The release tag provides the Piko source; pinned
native source revisions and build instructions are below. No prepatched X APK is
distributed. The upstream automatic stable-release job does not run in this fork.

1. Use a spare device or dedicated Android profile. Install the patched X APK and
   companion from a source you trust; confirm their signing identities separately.
2. Do not use X while configuring protection. In Piko settings, open **Privacy —
   Tor network**, or open the companion from the launcher. Confirm the target app.
3. Prepare the VPN and grant Android's VPN consent. A TUN alone is not a kill switch.
4. In Android VPN settings, enable **Always-on VPN** AND **Block connections without
   VPN** for **Piko Tor**. The engine refuses to start without both. These settings
   cannot be silently enabled by the app. Check they remain enabled after upgrades.
5. The companion distinguishes missing VPN, missing lockdown, unsupported strict
   Private DNS, absent network, connection in progress, engine failure, and Tor's
   reported readiness. None is a verified end-to-end protection claim.
6. “Retry Tor / refresh circuits” retries a failed engine or requests new circuits;
   it does not promise an immediate new exit IP or reset X's account identity.
7. Automatic recovery after killing the VPN process failed on the tested emulator.
   Reopen Piko Tor and use **Prepare VPN / start** to recover manually; do not disable
   Android lockdown to regain connectivity. Check both system settings again.

**Important limits:** one VPN per Android profile. Per-app VPN plus lockdown can
block the OTHER applications in that profile rather than letting them bypass.
Strict Private DNS is intentionally unsupported in this milestone: engine paused,
TUN retained. Do not silently turn off Android's DNS policy as a workaround.
Stopping Piko Tor does not turn off Android lockdown. Revoking consent, disabling
lockdown, changing provider, or uninstalling the companion can expose direct
connectivity; the companion cannot override the user's system administrator choices.
Before first setup, during consent, and without system lockdown, no protection is
claimed. An authenticated X account remains identifiable; the ISP normally sees Tor.

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
./gradlew buildAndroid :companion:assembleDebug :companion:testDebugUnitTest :companion:lintDebug
```

Outputs: `patches/build/libs/*.mpp` and
`companion/build/outputs/apk/debug/companion-debug.apk`. Debug keys are test-only,
not a stable production signing identity. No production release is configured.
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

To apply only the new optional patch to the supported X bundle (Morphe Desktop
1.18.1 requires Java 21+):

```sh
java -jar morphe-desktop-1.18.1-all.jar patch --exclusive \
  -p patches/build/libs/patches-3.10.0-tor.1-dev.1.mpp -e 'Tor network companion' \
  -o x-piko-tor.apk X-12.19.1-release.0.apkm
```

Use the bundle generated by YOUR build if the project version changes. Other
patches are intentionally excluded from this smoke check, not declared compatible.

## Validation evidence and remaining gates

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

### Android runtime checks (2026-10-19)

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
