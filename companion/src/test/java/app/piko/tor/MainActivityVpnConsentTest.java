package app.piko.tor;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.shadows.ShadowConnectivityManager;
import org.robolectric.shadows.ShadowNetwork;
import org.robolectric.shadows.ShadowNetworkCapabilities;
import org.robolectric.shadows.ShadowVpnService;
import org.robolectric.util.ReflectionHelpers;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
@LooperMode(LooperMode.Mode.PAUSED)
public class MainActivityVpnConsentTest {
    private ActivityController<MainActivity> controller;
    private MainActivity activity;

    @Before public void createActivityWithPairedTarget() throws Exception {
        controller = Robolectric.buildActivity(MainActivity.class).create();
        activity = controller.get();
        PackageInfo target = new PackageInfo();
        target.packageName = "app.piko.test.target";
        target.applicationInfo = new ApplicationInfo();
        target.applicationInfo.packageName = target.packageName;
        target.applicationInfo.uid = activity.getApplicationInfo().uid + 1;
        target.signingInfo = Shadow.newInstanceOf(SigningInfo.class);
        shadowOf(target.signingInfo).setSignatures(new Signature[]{new Signature(new byte[]{1, 2, 3})});
        shadowOf(activity.getPackageManager()).installPackage(target);
        TargetConfig.preferences(activity).edit().clear().commit();
        TargetConfig.inspect(activity, target.packageName).save(activity);
        assertTrue(TargetConfig.load(activity).valid(activity));
        shadowOf(activity.getSystemService(ConnectivityManager.class)).clearAllNetworks();
    }

    @After public void destroyActivity() { controller.destroy(); }

    @Test public void cancelledConsentDoesNotStartService() {
        ShadowVpnService.setPrepareResult(null);

        activity.onActivityResult(101, Activity.RESULT_CANCELED, null);

        assertNull(shadowOf(activity).getNextStartedService());
        assertFalse(TargetConfig.preferences(activity).getBoolean("requested", false));
    }

    @Test public void staleSuccessfulConsentDoesNotTakeOverAnotherProvider() {
        ShadowVpnService.setPrepareResult(new Intent("test.new.vpn.consent.required"));

        activity.onActivityResult(101, Activity.RESULT_OK, null);

        assertNull(shadowOf(activity).getNextStartedService());
        assertNull(shadowOf(activity).getNextStartedActivityForResult());
        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        assertNotNull(dialog);
        assertEquals(activity.getString(R.string.vpn_consent_changed), shadowOf(dialog).getMessage());
    }

    @Test public void freshConsentWithValidTargetStartsCompanion() {
        ShadowVpnService.setPrepareResult(null);

        activity.onActivityResult(101, Activity.RESULT_OK, null);

        Intent started = shadowOf(activity).getNextStartedService();
        assertNotNull(started);
        assertEquals(TorVpnService.START, started.getAction());
        assertEquals(TorVpnService.class.getName(), started.getComponent().getClassName());
    }

    @Test public void targetChangedWhileConsentOpenPreventsStart() {
        ShadowVpnService.setPrepareResult(null);
        TargetConfig.preferences(activity).edit().putString("certificate", "changed").commit();

        activity.onActivityResult(101, Activity.RESULT_OK, null);

        assertNull(shadowOf(activity).getNextStartedService());
    }

    @Test public void visibleOtherVpnPreventsConsentPromptAndServiceStart() {
        ShadowVpnService.setPrepareResult(new Intent("test.vpn.consent.required"));
        ShadowConnectivityManager connectivity = shadowOf(activity.getSystemService(ConnectivityManager.class));
        Network network = ShadowNetwork.newInstance(42);
        connectivity.addNetwork(network, null);
        NetworkCapabilities capabilities = ShadowNetworkCapabilities.newInstance();
        shadowOf(capabilities).addTransportType(NetworkCapabilities.TRANSPORT_VPN);
        connectivity.setNetworkCapabilities(network, capabilities);

        ReflectionHelpers.callInstanceMethod(activity, "prepareVpn");

        assertNull(shadowOf(activity).getNextStartedActivityForResult());
        assertNull(shadowOf(activity).getNextStartedService());
        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        assertNotNull(dialog);
        assertEquals(activity.getString(R.string.vpn_conflict_message), shadowOf(dialog).getMessage());
    }
}
