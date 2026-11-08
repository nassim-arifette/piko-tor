package app.piko.tor;

/** Reports prerequisites, never a claim that leak testing has passed. */
public enum ProtectionState {
    OFF, TARGET_INVALID, VPN_MISSING, PRIVATE_DNS_UNSUPPORTED, NETWORK_UNAVAILABLE,
    CONNECTING, TOR_UNAVAILABLE, TOR_READY;

    public static ProtectionState evaluate(boolean requested, boolean targetValid, boolean tun,
            boolean privateDns, boolean failed, boolean ready, boolean network) {
        if (!requested) return OFF;
        if (!targetValid) return TARGET_INVALID;
        if (!tun) return VPN_MISSING;
        if (privateDns) return PRIVATE_DNS_UNSUPPORTED;
        if (!network) return NETWORK_UNAVAILABLE;
        if (failed) return TOR_UNAVAILABLE;
        return ready ? TOR_READY : CONNECTING;
    }

    public boolean permitsEngine() {
        return this == CONNECTING || this == TOR_READY;
    }
}
