# Permanent project instructions — Piko Tor / Arti (Android / X)

> **Scope:** this document describes the context, vision, constraints and working method to keep across all agent sessions (including Devin) working on this repository.
>
> **Important:** this is a **development goal**, not a feature that is already implemented or whose security has been demonstrated. Never present a capability as working or leak-free without actual validation.
>
> **Priority:** any task related to Tor, Arti, networking or X patches must stay consistent with this document. For other tasks in the repository, do not needlessly impose the Tor scope.

## 1. Context, architecture choice and absolute priority

- **Fork repository:** https://github.com/nassim-arifette/piko-tor
- **Existing project:** Piko is a set of Morphe patches for the Twitter/X and Instagram Android apps. Morphe applies the patches to the APK/APKM; Piko itself is not the patching engine.
- **Initial scope:** X only, not Instagram.
- **Target feature:** an optional Piko patch (working name: `TorNetworkPatch`) giving access to Tor controls in X's settings.
- **Privacy goal:** hide the user's public IP from X's servers by routing the relevant connections through the Tor network, via Arti (a Tor client written in Rust).
- **NON-NEGOTIABLE priority:** when a protection mode is enabled, **it is better for X to have no Internet at all than to connect directly without Tor**. No silent fallback to the direct network, including during startup, a Tor error or a VPN failure.

### Chosen design: two APKs, one project

The **preferred approach** is to maintain two separate Android apps, developed in this same repository, rather than a single APK hosting both X *and* the VPN provider:

1. **X + Piko APK (Morphe)**: keeps the existing X patches and exposes the settings, state and controls of the Tor feature. The X app **does not host the VPN service**.
2. **"Piko Tor" companion APK**: an app maintained by this project, standalone, without Orbot or any third-party VPN/proxy provider, hosting `VpnService`, Arti and the network forwarding engine, with an app identifier/Android UID distinct from X.

Having two APKs **is not enough** to guarantee blocking: protection against the tunnel disappearing must rely on Android's **"Always-on VPN" + "Block connections without VPN"** mode, a system setting the user must enable and verify. This setting may affect other apps on the phone/profile.

**Why this separation:** if X and `VpnService` share the VPN provider's UID in a single APK, they do not get the same independent system lockdown barrier; the VPN provider must be able to reach the underlying network to reach Tor. The two-UID design must be validated by tests on the target Android versions.

The old "Tor + VPN in the same X APK" idea remains a research topic, **not the reference architecture for strict mode**. Only adopt it if an equivalent barrier against leaks during a VPN crash/removal is actually demonstrated.

**Limit of the promise:** the goal "no direct connection while protection is active and correctly configured" must be demonstrated on real devices; never promise zero risk on all devices, especially if system lockdown is disabled, VPN permission is revoked, the companion APK is uninstalled or the system is compromised.

## 2. Target features

### 2.1. Piko Tor: companion app and Tor/Arti engine

- Study creating a **standalone Android module/app** in the same repository, separate from the extension code injected into X.
- Assess the feasibility of embedding Arti in Rust in this app, the required Android ABIs and the JNI/FFI interface with Kotlin/Java.
- Assess **Onionmasq** or an auditable equivalent to connect an Android TUN tunnel to Arti; **do not assume** its compatibility or maintenance without checking.
- Start, maintain, restart and cleanly stop components, handling circuits, errors, battery, Android restrictions and reboots.
- Use **secure inter-app communication** between X/Piko and Piko Tor (permissions, explicit intents/IPC, authentication suited to the signatures Morphe actually uses). Never allow an arbitrary external command to disable protection.
- Do not require root, Orbot, a commercial VPN or a proprietary proxy server.
- Check license compatibility and publishing requirements for the Rust/Android components.

### 2.2. Strict fail-closed and IP leak protection

**Desired security invariants:**

1. **Tor working + VPN active + Android lockdown active** → X can reach the network **only via Tor** within the validated network scope.
2. **Tor unavailable but VPN tunnel present** → X's connections are **blocked**; never a direct exit or IPv4, IPv6, DNS, UDP or QUIC bypass.
3. **VPN service stopped, killed, crashed or tunnel destroyed** → the VPN lockdown **managed by Android** must block X's connections until the VPN is restored.
4. **At startup / after reboot / during reconnection** → do not consider protection active nor allow X to transmit until the required conditions are met. Audit requests sent before the hooks are initialized.
5. **VPN permission revoked, always-on VPN disabled or app uninstalled** → do not claim the system barrier still holds; detect and report loss of protection when possible. Evaluate an additional guard in X, without calling it a "guarantee" if it does not cover every network stack.
6. **The user explicitly disables protection** → clearly show the risk of a direct connection; never disable lockdown or switch to direct implicitly.

**Android requirements:**

- Study `VpnService`, the "Always-on VPN" / "Block connections without VPN" system options and how they differ across Android versions/OEMs. **A consumer app must not assume it can enable this lockdown automatically**; provide a user setup guide.
- Use and test `VpnService.Builder.addAllowedApplication()` to precisely target X's installed package when appropriate. **Check in lockdown mode** what happens to other apps: per-app VPN plus Android lockdown can cut their Internet access. Evaluate a separate Android profile if the user wants to isolate X from the rest of the phone.
- Check that X and Piko Tor actually have **different UIDs**; do not use a shared UID nor assume an immutable package name (clones/renaming).
- Protect the outgoing sockets Arti needs against a VPN loop, while making sure **no X socket** gets a direct bypass of the VPN system.
- Forward TCP flows via Tor, handle DNS without leaks and **block or explicitly handle** incompatible protocols (notably UDP/QUIC, IPv6); never a direct fallback.
- Check permissions, exported components, VPN state, network configuration, reconnection and interruption handling.

**Fundamental distinction:** the Tor engine failing closed while the TUN exists does **not** protect against the TUN disappearing. The strict goal requires an independent lockdown provided by Android and actually enabled.

**A local SOCKS proxy is not a full VPN.** Any claimed network coverage must be verified with negative tests (traffic that *must not* go out).

### 2.3. Piko settings in X

Add a section such as **Privacy → Tor Network**, following Piko's existing preferences.

- Show the presence/version of the "Piko Tor" companion app and offer manual installation/setup if it is missing.
- Guide the user through granting VPN consent and enabling **Always-on VPN** then **Block connections without VPN** in Android; do not present these system actions as automatically performed.
- Show an honest state: **not installed**, **VPN/lockdown not configured**, **Tor connecting**, **protected**, **Tor unavailable — traffic blocked**, **protection lost / error**.
- Allow starting/restarting Tor if inter-app control is validated, without creating a direct-connection window.
- Only show **"protected"** with sufficiently reliable signals about Tor state, the VPN and the lockdown configuration; otherwise state that the status is unverified.
- For any voluntary disable, explain the risk to the public IP and require an explicit action. **Never automatically disable protection to "fix" the connection.**
- Do not expose X tokens, sensitive addresses, keys or other secrets in logs.

### 2.4. Compatibility and performance

- Preserve X's normal behavior and the other Piko patches as much as possible.
- Respect Android lifecycles, background restrictions, services and permissions.
- Limit the impact on battery, memory and startup.
- Ensure compatibility with Morphe patching and APK/APKM packaging.
- Specifically test: account login, images, videos, direct messages, notifications and Tor-incompatible protocols.
- Clearly describe degraded or impossible-to-preserve features.
- Account for the fact that Android normally allows **only one active VPN per profile/user** and that system lockdown may cut the network for other apps; communicate this explicitly.
- Plan for installing **two APKs** and system setup by the user, even though the sources stay in a single repository.

## 3. Study the actual repository before changing code

Do not invent the project's architecture or existing paths. Inspect the current code, versions and conventions, in particular, **if these paths exist**:

- `patches/src/main/kotlin/app/crimera/patches/twitter/`
- `patches/src/main/kotlin/app/crimera/patches/twitter/misc/extension/ExtensionPatch.kt`
- `extensions/twitter/src/main/java/app/morphe/extension/twitter/`
- `extensions/twitter/src/main/java/app/morphe/extension/twitter/settings/Settings.java`
- `extensions/twitter/src/main/java/app/morphe/extension/twitter/settings/ScreenBuilder.java`
- Gradle files, Android manifests and Morphe injection and packaging mechanisms.

Determine:

- Where to declare the optional `TorNetworkPatch`, how to add settings to X and integrate controls/states coming from Piko Tor.
- How to create a **new companion APK** without breaking the build or existing Piko patches.
- How to sign and install both APKs, recognize X's real package, secure communications and avoid depending on shared signatures that are not guaranteed.
- How to package Arti and any Rust libraries, per ABI and Android version.
- Which system settings must be configured **manually** before a "protected" state is valid.

## 4. Working method: four stages

### Stage 1 — Feasibility study (before implementation)

Compare at least:

- **A — A single X/Piko APK with Arti + SOCKS hooks**: risk of unintercepted traffic, coverage limits.
- **B — A single X/Piko APK with Arti + `VpnService`**: limits due to the VPN provider's UID and independent lockdown.
- **C — Preferred approach: two separate APKs** (X + Piko, and Piko Tor with `VpnService` + Arti) **with mandatory Android system lockdown** for strict protection mode.

Examine Onionmasq and equivalent components, Android profile isolation, per-app VPN behavior in lockdown mode, X's network processes and Morphe constraints. Produce a risk table and **flag every undemonstrated point**. Do not reintroduce a weaker architecture without explaining why it would preserve the same guarantees.

### Stage 2 — Design

Define precisely:

- Both APKs, their roles, signatures/UIDs, manifests, services, permissions and IPC protocol.
- The state machine for Tor connections, the VPN tunnel and the protection indicator; behavior at each transition, crash and permission revocation.
- Safe startup (no unplanned direct network access window), DNS, IPv4/IPv6, TCP, UDP/QUIC, Arti sockets and blocking of incompatible traffic.
- Rust/Android build, ABI packaging, battery/memory impact and installation/system setup flows.
- A reproducible validation procedure and objective criteria for saying "X traffic blocked" / "X traffic via Tor".

### Stage 3 — Incremental implementation

- Start with a first verifiable milestone, without announcing full protection too early.
- Develop the **optional** Piko patch and the **separate** Android companion modularly.
- Do not rewrite Piko, replace Morphe or modify Instagram for the first version.
- Prefer **fail-closed by default** as soon as protection is requested. Refuse a direct fallback even in case of connection difficulties or media incompatibility.
- Do not confuse showing a button, connecting a proxy, starting Arti and effective protection.

### Stage 4 — Validation, including negative scenarios

Distinguish **tests actually performed**, **not executed** and **tests requiring an Android device**. Test at least:

1. Piko/Morphe build and Piko Tor build; installing both APKs on the supported Android ABIs.
2. Package identities and UIDs actually distinct, IPC and rejection of unauthorized requests.
3. Real Arti connection and X traffic exiting via Tor (independent network observations, without confusing an open port with a safe tunnel).
4. DNS, IPv4, IPv6, native connections, UDP/QUIC, WebView and X auxiliary process leaks.
5. **Deliberately stopping Tor with the TUN kept up**: no direct connection from X.
6. **Crash/kill of `VpnService` or TUN disappearance**: with always-on VPN and lockdown active, no direct connection from X.
7. Device reboot, startup before Tor, Wi-Fi ↔ mobile switching, airplane mode, background and battery restrictions.
8. VPN consent revoked, lockdown disabled, VPN provider changed, companion uninstalled: document precisely **what is no longer guaranteed** and prevent any false "protected" indication.
9. **Per-app VPN + lockdown**: check other apps' traffic, warn about the impact and consider an Android profile if needed.
10. X authentication, media, direct messages, notifications and non-regression of other patches.

Never invent a test, a measurement, a secure state or a global absence of leaks.

## 5. Security and technical honesty constraints

1. **Strict fail-closed comes first.** X must not automatically regain Internet access without Tor if Arti or the VPN stops working. The real limits of this requirement depend on the configuration and verified Android behavior.
2. **Two APKs as reference.** Separating X / Piko Tor and having Android VPN lockdown enabled are prerequisites of the intended protection model. "Two APKs" without active lockdown **is not enough**.
3. **No false guarantees.** Never claim to guarantee 100% absence of IP leaks on all devices or if the user removes/disables system protections.
4. **No silent bypass.** Never bypass Tor to make X, notifications, media, UDP or DNS work. Block rather than switch to direct.
5. **Separate account identity from IP.** Even with Tor, a logged-in X account and the user's actions may let X recognize that account. The ISP normally sees the connection to the Tor network.
6. **Independent infrastructure.** No mandatory Orbot, third-party VPN, proprietary proxy server or root requirement. The Piko Tor companion APK is a component **of the project**, not an external provider.
7. **Control security.** Do not allow an intent, exported component or other app to disable lockdown / protection without explicit authorization.
8. **Licenses and compatibility.** Respect GPLv3 and component licenses; document incompatibilities and Android or Morphe constraints.
9. **Honest installation documentation.** State VPN consent, system lockdown and its potential impact on other apps' connectivity. Never describe a setting that has not been applied as active.

## 6. Target outcome and expected agent behavior

**Final target outcome:** Piko adds to X a Tor interface and a secure integration with a **Piko Tor** companion app providing `VpnService` and Arti, with **strict fail-closed** backed by Android's "Always-on VPN + Block connections without VPN" lockdown, then demonstrated by tests.

In future agent sessions:

- Keep **priority on no direct connection** over X's availability.
- Treat **separating X and the VPN engine into two APKs** as the reference architecture, without assuming it alone secures traffic.
- Read the actual code and repository state before making changes; preserve existing patches and keep the scope to X only.
- Do not present these requirements as development already done.
- Document decisions, dependencies, versions, evidence and tests in the repository.
- Start with feasibility, then design, implement and validate incrementally, flagging impossible features or residual risks unambiguously.

### Technical references for the feasibility phase

- Android — VPN, always-on VPN and blocking connections outside the VPN: https://developer.android.com/develop/connectivity/vpn
- Android — VpnService, lockdown state and VPN engine socket bypass: https://developer.android.com/reference/android/net/VpnService
- Android — Per-app VPN: https://developer.android.com/reference/android/net/VpnService.Builder
- Tor Project — Arti: https://gitlab.torproject.org/tpo/core/arti
- Tor Project — Onionmasq (to be examined, not assumed compatible): https://gitlab.torproject.org/tpo/core/onionmasq
