package app.piko.tor;

import org.junit.Test;
import static org.junit.Assert.*;

public class ProtectionStateTest {
    @Test public void engineNeverStartsWithoutEveryPrerequisite() {
        for (int bits = 0; bits < 128; bits++) {
            boolean requested = (bits & 1) != 0, target = (bits & 2) != 0, tun = (bits & 4) != 0;
            boolean privateDns = (bits & 8) != 0, failed = (bits & 16) != 0, ready = (bits & 32) != 0;
            boolean network = (bits & 64) != 0;
            ProtectionState state = ProtectionState.evaluate(requested, target, tun,
                    privateDns, failed, ready, network);
            assertEquals(requested && target && tun && !privateDns && !failed && network,
                    state.permitsEngine());
            if (!requested) assertEquals(ProtectionState.OFF, state);
            if (state == ProtectionState.TOR_READY) assertTrue(ready && state.permitsEngine());
        }
    }

    @Test public void staleBootstrapDoesNotHideRevocation() {
        assertEquals(ProtectionState.VPN_MISSING,
                ProtectionState.evaluate(true, true, false, false, false, true, true));
        assertEquals(ProtectionState.TARGET_INVALID,
                ProtectionState.evaluate(true, false, true, false, false, true, true));
    }

    @Test public void engineFailureDoesNotBecomeReady() {
        assertEquals(ProtectionState.TOR_UNAVAILABLE,
                ProtectionState.evaluate(true, true, true, false, true, true, true));
    }

    @Test public void perAppTunnelConnectsAndBecomesReadyWithoutSystemLockdownPrerequisites() {
        assertEquals(ProtectionState.CONNECTING,
                ProtectionState.evaluate(true, true, true, false, false, false, true));
        assertEquals(ProtectionState.TOR_READY,
                ProtectionState.evaluate(true, true, true, false, false, true, true));
    }

    @Test public void strictPrivateDnsStillPausesReadyTor() {
        assertEquals(ProtectionState.PRIVATE_DNS_UNSUPPORTED,
                ProtectionState.evaluate(true, true, true, true, false, true, true));
    }
}
