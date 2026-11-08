package app.piko.tor;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.app.Service;
import android.content.Intent;
import android.net.VpnService;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.system.OsConstants;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.LooperMode;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.shadows.ShadowVpnService;
import org.robolectric.util.ReflectionHelpers;
import org.torproject.onionmasq.OnionMasqJni;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Java service lifecycle tests; these do not establish a VPN or validate network blocking. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, shadows = TorVpnServiceTest.ConfiguredVpnService.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class TorVpnServiceTest {
    private ServiceController<TorVpnService> controller;
    private TorVpnService service;

    @Before public void createService() {
        ShadowVpnService.setPrepareResult(null);
        controller = Robolectric.buildService(TorVpnService.class).create();
        service = controller.get();
        TargetConfig.preferences(service).edit().clear().putBoolean("requested", true).commit();
        ReflectionHelpers.setField(service, "requested", true);
        ReflectionHelpers.setField(service, "target", new TargetConfig("", "", -1));
        ReflectionHelpers.setField(service, "engineGeneration", 7);
        ReflectionHelpers.setField(service, "permitSockets", true);
        ReflectionHelpers.setField(service, "ready", true);
    }

    @After public void destroyService() {
        controller.destroy();
        shadowOf(Looper.getMainLooper()).idle();
        assertFalse(TorEngine.hasWorker());
    }

    @Test public void binderRevocationDeniesCallbacksBeforeMainThreadClosesResources() throws Exception {
        TargetConfig target = ReflectionHelpers.getField(service, "target");
        ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
        try {
            ReflectionHelpers.setField(service, "tun", pipe[0]);
            OnionMasqJni.Listener callback = listener();
            revokeFromBinderThread();

            assertTrue(field("revoked"));
            assertFalse(field("permitSockets"));
            assertFalse(field("ready"));
            assertSame(target, ReflectionHelpers.getField(service, "target"));
            assertSame(pipe[0], ReflectionHelpers.getField(service, "tun"));
            assertTrue(pipe[0].getFd() >= 0);
            // Even an eligibility result computed by an in-flight tick cannot override revocation.
            ReflectionHelpers.setField(service, "permitSockets", true);
            assertFalse(callback.protect(123));
            assertEquals(-1, callback.owner(OsConstants.IPPROTO_TCP,
                    new byte[]{10, 0, 0, 1}, 1234, new byte[]{1, 1, 1, 1}, 443));

            shadowOf(Looper.getMainLooper()).idle();
            assertNull(ReflectionHelpers.getField(service, "target"));
            assertNull(ReflectionHelpers.getField(service, "tun"));
            assertThrows(IllegalStateException.class, pipe[0]::getFd);
            assertEquals(ProtectionState.VPN_MISSING, TorVpnService.state());
            assertTrue(shadowOf(service).isStoppedBySelf());
        } finally {
            pipe[0].close();
            pipe[1].close();
        }
    }

    @Test public void queuedBootstrapCannotRestoreReadinessAfterRevocation() throws Exception {
        listener().event("{\"type\":\"Bootstrap\",\"is_ready_for_traffic\":true}");
        AtomicBoolean readyBeforeTeardown = new AtomicBoolean(true);
        new Handler(Looper.getMainLooper()).post(() -> readyBeforeTeardown.set(field("ready")));
        revokeFromBinderThread();
        ReflectionHelpers.setField(service, "permitSockets", true);

        shadowOf(Looper.getMainLooper()).idle();

        assertFalse(readyBeforeTeardown.get());
        assertFalse(field("ready"));
    }

    @Test public void lateTickAfterRevocationDoesNotDereferenceClearedTargetOrRestart() throws Exception {
        Runnable tick = ReflectionHelpers.getField(service, "tick");
        revokeFromBinderThread();
        new Handler(Looper.getMainLooper()).post(tick);

        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5));

        assertNull(ReflectionHelpers.getField(service, "target"));
        assertFalse(field("started"));
        assertFalse(TorEngine.hasWorker());
        assertEquals(ProtectionState.VPN_MISSING, TorVpnService.state());
    }

    @Test public void newlyGrantedConsentCannotRestartRevokedBoundInstance() {
        service.onRevoke();
        ShadowVpnService.setPrepareResult(null);

        int result = service.onStartCommand(new Intent(TorVpnService.START), 0, 2);

        assertEquals(Service.START_NOT_STICKY, result);
        assertTrue(field("revoked"));
        assertFalse(field("permitSockets"));
        assertNull(ReflectionHelpers.getField(service, "target"));
        assertTrue(TargetConfig.preferences(service).getBoolean("requested", false));
        assertEquals(ProtectionState.VPN_MISSING, TorVpnService.state());
    }

    @Test public void newInstanceCanStartAfterOldRevocationAndDestroy() throws Exception {
        revokeFromBinderThread();
        // Destroy before the queued revocation cleanup, then create the next instance.
        controller.destroy();
        controller = Robolectric.buildService(TorVpnService.class).create();
        service = controller.get();

        int result = service.onStartCommand(new Intent(TorVpnService.START), 0, 1);
        shadowOf(Looper.getMainLooper()).idle();

        assertEquals(Service.START_STICKY, result);
        assertFalse(field("revoked"));
        assertTrue(TorVpnService.active());
        assertFalse(shadowOf(service).isStoppedBySelf());
        // No target is paired: recovery still honors prerequisites and never starts native Tor.
        assertEquals(ProtectionState.TARGET_INVALID, TorVpnService.state());
        assertFalse(TorEngine.hasWorker());
    }

    @Test public void explicitStopAfterRevocationClearsRequest() throws Exception {
        revokeFromBinderThread();

        assertEquals(Service.START_NOT_STICKY,
                service.onStartCommand(new Intent(TorVpnService.STOP), 0, 2));
        shadowOf(Looper.getMainLooper()).idle();

        assertFalse(TargetConfig.preferences(service).getBoolean("requested", true));
        assertEquals(ProtectionState.OFF, TorVpnService.state());
    }

    private boolean field(String name) { return ReflectionHelpers.getField(service, name); }

    private OnionMasqJni.Listener listener() {
        return ReflectionHelpers.callInstanceMethod(service, "listener",
                ReflectionHelpers.ClassParameter.from(int.class, 7));
    }

    private void revokeFromBinderThread() throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread binder = new Thread(() -> {
            try { service.onRevoke(); } catch (Throwable error) { failure.set(error); }
        }, "test-vpn-revocation");
        binder.start();
        binder.join(5000);
        assertFalse("Revocation must not wait for the main thread", binder.isAlive());
        if (failure.get() != null) throw new AssertionError(failure.get());
    }

    @Implements(VpnService.class)
    public static class ConfiguredVpnService extends ShadowVpnService {
        @Implementation protected boolean isAlwaysOn() { return true; }
        @Implementation protected boolean isLockdownEnabled() { return true; }
    }
}
