# Future work

## Investigate X's VPN/proxy profile indication

Requested on 2026-11-08. **Research only; no bypass is implemented or promised.**
This is separate from the current X-only Tor ON/OFF work.

The aim is to determine whether the VPN/proxy indication in **About this account**
can be reduced while keeping X's traffic on its selected Tor route. Hiding a label
only in the patched app would not remove information displayed to other people.

### What the primary sources establish

- X says the country/region in About this account is inferred from aggregated IP
  addresses. Its help page does not disclose the algorithm for a VPN/proxy warning,
  or establish that it depends on Android's local VPN signal. [X Help](https://help.x.com/en/managing-your-account/how-to-change-country-settings)
- Android exposes a VPN transport through `NetworkCapabilities.TRANSPORT_VPN` and
  `hasTransport()`. This makes local detection technically possible, but does not
  establish which checks this X version actually performs. [Android API](https://developer.android.com/reference/android/net/NetworkCapabilities)
- Tor exit addresses are public and destination services can identify Tor
  connections. Altering an Android API result does not hide the exit IP from X.
  [Tor Project](https://support.torproject.org/tor-browser/general/website-blocking-tor-exits/)

### Planned investigation

- [ ] Identify the exact profile warning, its client rendering and the server
  response field that supplies it; keep credentials and account data out of logs.
- [ ] Inspect the supported X APK for actual VPN-detection call sites and whether
  their results affect telemetry, requests, local UI, or nothing relevant to the
  public warning. Do not assume the Android transport flag is the source.
- [ ] Compare the unmodified and patched client under controlled network conditions
  and inspect the public result from an independent viewer. Account history and
  delayed updates must be considered before attributing a change to a patch.
- [ ] If a relevant local signal is demonstrated, prototype a narrowly scoped,
  optional X patch. Revalidate native requests, WebView, media and Tor egress; do
  not change routing or allow direct fallback to make a detection test pass.
- [ ] If the warning is determined by X's server using IP reputation or account
  history, document that a client patch cannot reliably remove it in the current
  architecture. Do not present a cosmetic change, a different Tor circuit, or a
  bridge as proof that Tor is undetectable to X.

Acceptance requires an independently observed effect on the intended indication,
with the same Tor routing guarantees and explicit limits. An absent local badge
alone is not acceptance evidence.
