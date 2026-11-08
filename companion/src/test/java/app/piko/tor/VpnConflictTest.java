package app.piko.tor;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowConnectivityManager;
import org.robolectric.shadows.ShadowNetwork;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class VpnConflictTest {
    private Application context;
    private ShadowConnectivityManager connectivity;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        connectivity = shadowOf(context.getSystemService(ConnectivityManager.class));
        connectivity.clearAllNetworks();
    }

    @Test public void visibleVpnAlongsideWifiIsDetected() {
        addNetwork(1, new NetworkCapabilities().addTransportType(NetworkCapabilities.TRANSPORT_WIFI));
        addNetwork(2, new NetworkCapabilities().addTransportType(NetworkCapabilities.TRANSPORT_VPN));
        assertTrue(VpnConflict.hasVisibleVpn(context));
    }

    @Test public void wifiAloneDoesNotReportConflict() {
        addNetwork(1, new NetworkCapabilities().addTransportType(NetworkCapabilities.TRANSPORT_WIFI));
        assertFalse(VpnConflict.hasVisibleVpn(context));
    }

    @Test public void disappearedNetworkCapabilitiesDoNotCrash() {
        addNetwork(1, null);
        assertFalse(VpnConflict.hasVisibleVpn(context));
    }

    private void addNetwork(int id, NetworkCapabilities capabilities) {
        Network network = ShadowNetwork.newInstance(id);
        connectivity.addNetwork(network, null);
        connectivity.setNetworkCapabilities(network, capabilities);
    }
}
