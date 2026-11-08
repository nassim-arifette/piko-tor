# Permanent project instructions — Piko Tor / Arti (Android / X)

> **Scope:** preserve these product decisions across agent sessions. They describe requirements and validation duties, not capabilities already proved.
>
> **Current user choice (2026-11-08):** Tor ON affects X only; other applications keep their ordinary network. Tor OFF restores X's ordinary direct network. This explicit choice replaces the earlier mandatory system-lockdown default.

## 1. Product behavior and priorities

- Fork repository: https://github.com/nassim-arifette/piko-tor
- Piko supplies Morphe patches for Twitter/X and Instagram. Tor work targets X only; preserve the existing patches and Instagram behavior.
- Keep two separate APKs: X patched by Morphe exposes Tor settings; the project-owned Piko Tor companion (app.piko.tor) owns VpnService and the Onionmasq/Arti engine under a distinct UID.
- **Default ON:** route the selected X application's supported traffic through Tor. Other applications in the Android profile must retain their ordinary network.
- **Default OFF:** an explicit user stop closes the companion tunnel and restores X's ordinary direct connectivity. This is intended behavior, not a failed protection test.
- **While ON with the TUN retained:** engine failure, unsupported protocols and media problems must not cause an internal direct fallback. Retain the tunnel and block affected X traffic when Tor cannot carry it.
- **Unexpected tunnel loss:** without optional Android lockdown, a service crash, VPN revocation or tunnel destruction can let X reconnect directly. State this limit clearly; do not claim a crash-proof kill switch or global absence of leaks.
- Do not silently stop the TUN to restore connectivity while the user still requests Tor ON.

The separate-UID design keeps Tor's protected outbound sockets separate from X. It does not alone impose an independent network block after the TUN disappears. Do not replace the companion with a VPN hosted inside X without a separate design review and evidence.

## 2. Android system policy is optional advanced configuration

Android's Always-on VPN and Block connections without VPN are **not required by the default product**. Do not automatically enable them, require them to start Tor, or reintroduce them as the default to satisfy older strict-mode instructions.

System lockdown is an optional advanced choice for users who prefer blocking after tunnel loss. It can also block other applications in the profile and keep X blocked after an explicit OFF action. Explain this incompatibility with the normal ON/OFF experience before recommending it. A separate Android profile may isolate that strict use case, but is not required by the default design.

- A consumer app cannot assume authority to change these system settings. Use Android's visible settings UI; do not obtain privileged settings permissions, root, or change a screen-lock credential for this feature.
- When migrating from an older strict build, explain that users who want normal ON/OFF behavior must turn off both old system options themselves. Never label OFF as direct connectivity when retained system lockdown still blocks it.
- Android generally permits one active VPN per profile. Do not silently replace another provider.
- Detect another visible VPN before requesting consent, recheck consent and target identity after the consent dialog, and explain that Android can hide a per-app VPN that excludes the companion UID.
- Per-app routing must use the actual selected installed package and confirm its UID and signing identity; do not assume a fixed package name or immutable identity.
- Re-signing/reinstalling, revocation, provider replacement and app removal require honest state updates. Do not treat an old consent result or bootstrap callback as current authority.

## 3. Implementation boundaries

- Keep the companion standalone, without requiring Orbot, a commercial VPN, a proprietary proxy, root, or shared signing with X.
- Use VpnService.Builder.addAllowedApplication() to scope the tunnel to the selected X app. Test that an unselected app retains direct connectivity while Tor is ON.
- Protect only the Tor engine's own outgoing sockets against VPN loops. No X socket may receive a direct bypass through this API.
- Forward supported TCP/DNS traffic through Tor and reject unsupported UDP/QUIC or other traffic while the tunnel is active. Do not solve compatibility failures with a direct fallback.
- Separate engine readiness, tunnel existence, Android policy and observed end-to-end network results. An open TUN or ready event is not proof of privacy.
- Preserve the existing guarded handoff from X settings to the companion. Sensitive actions require visible user interaction; do not add an unauthenticated remote stop or policy-control API.
- Keep resource cleanup, stale callback rejection, permission rechecks and lifecycle handling explicit. Deliberate stop and unexpected failure must have distinct semantics.
- Keep credentials, browsing destinations, packet payloads, account tokens and sensitive addresses out of logs and published validation artifacts.
- Preserve GPLv3/root NOTICE and native dependency license/source obligations.

Study the actual source and build system before editing. Relevant areas include companion/, patches/src/main/kotlin/app/crimera/patches/twitter/, extensions/twitter/src/main/java/app/morphe/extension/twitter/settings/, the Android manifests, Gradle files and native build/verification scripts. Do not invent paths, compatibility or device state.

## 4. User experience and documentation

X's settings show whether the companion is installed and offer its visible setup/control screen. Do not report a trusted protection state in X from an unverified external signal.

Explain the intended states in plain language:

- OFF: X uses its ordinary network, subject to any separately enabled Android lockdown.
- Connecting: the tunnel may exist while Tor is not ready; do not claim successful Tor egress.
- ON: the selected X traffic is routed through Tor within the measured scope; other apps retain ordinary connectivity.
- Engine unavailable with TUN retained: X traffic is blocked while the engine recovers.
- Tunnel/permission lost: direct X traffic may resume without optional system lockdown.

Only display a claim such as "protected" if its exact meaning is supported by reliable current signals and device evidence. Do not promise anonymity, complete DNS/IPv6/native-process coverage, or zero leak risk. Even through Tor, X can recognize the logged-in account and its actions; the ISP normally sees Tor connectivity.

Published artifacts and unpublished source must be distinguished. The existing 3.9.0-tor.2 release uses the older strict behavior; do not imply it contains the new ON/OFF default or subsequent safeguards. Keep download links pointing to real artifacts until a new release exists.

## 5. Working method and validation

Implement changes incrementally, preserving existing Piko functionality. Record decisions, revisions, build inputs and measured results. Do not restart completed feasibility work without a concrete reason, and never substitute a document update for implementing the authorized behavior.

Distinguish tests actually executed, tests pending and tests requiring a real Android device. Cover at least:

1. Patch bundle and companion build, unit tests, lint, APK alignment/signature, native ABI packaging and notices.
2. Real Morphe patch application; separate package identities/UIDs; target pairing, stale consent and rejection of unauthorized control.
3. **Default ON:** real Arti egress from X, plus an unselected application's successful direct connectivity. Establish working direct positive controls first.
4. **Default OFF:** explicit stop removes the tunnel and restores direct X connectivity; other applications remain usable. Test repeated ON/OFF/recovery.
5. **Engine paused with TUN retained:** previously working X/probe controls fail without a direct fallback. Distinguish this from the OFF action.
6. **Unexpected service death, tunnel loss or revocation:** observe and report actual behavior without system lockdown. Do not expect or claim an independent block that has not been configured.
7. Optional strict mode separately: test Android lockdown and its impact on X and other apps, including after OFF or tunnel loss. Label these results as optional-mode evidence.
8. DNS, IPv4/IPv6, native connections, WebView, UDP/QUIC and auxiliary/isolated processes. A failed baseline cannot prove blocking, and UID counters may miss netd-owned DNS.
9. Reboot/early boot, Wi-Fi/mobile transitions, airplane mode, background and battery restrictions; provider changes and uninstall.
10. Real X search, profiles, images, video, login, direct messages and notifications, plus other Piko patches. A successful patch build or one WebView Tor exit is not exhaustive runtime coverage.

Keep prior strict-mode evidence with its original revision and settings. Do not present it as validation of the new default. A short post-kill observation is not an automatic-restart measurement or a claim about all devices.

## 6. Expected outcome

Deliver an optional X-only Tor feature whose **ON** mode sends X through Tor while other apps keep working normally, and whose **OFF** action restores direct X connectivity. Preserve no-fallback behavior while the TUN remains active, document the exposure after unexpected tunnel loss, and offer Android lockdown only as an explicitly chosen advanced configuration.

Follow the latest explicit user preference when it changes scope. Do not silently restore the superseded mandatory-lockdown model. Do not invent tests, device configuration, successful installation, a secure state or a release.

### Technical references

- Android VPN and per-app behavior: https://developer.android.com/develop/connectivity/vpn
- VpnService and socket protection: https://developer.android.com/reference/android/net/VpnService
- VpnService.Builder: https://developer.android.com/reference/android/net/VpnService.Builder
- Tor Project Arti: https://gitlab.torproject.org/tpo/core/arti
- Tor Project Onionmasq: https://gitlab.torproject.org/tpo/core/onionmasq
