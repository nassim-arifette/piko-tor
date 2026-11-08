package app.piko.tor.validation;

import android.os.IBinder;
import android.os.Process;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;

/** Lab-only app_process helper. Calls the named platform API, never a guessed Binder transaction. */
public final class VpnSetup {
    public static void main(String[] arguments) throws Exception {
        Class<?> properties = Class.forName("android.os.SystemProperties");
        String emulator = (String) properties.getMethod("get", String.class).invoke(null, "ro.kernel.qemu");
        if (!"1".equals(emulator) || Process.myUid() != 0) {
            throw new SecurityException("This helper requires a disposable rooted Android emulator");
        }
        if (arguments.length != 1 || !("enable".equals(arguments[0]) || "disable".equals(arguments[0])
                || "status".equals(arguments[0]))) {
            throw new IllegalArgumentException("Expected enable, disable or status");
        }
        IBinder binder = (IBinder) Class.forName("android.os.ServiceManager")
                .getMethod("getService", String.class).invoke(null, "vpn_management");
        if (binder == null) throw new IllegalStateException("vpn_management unavailable");
        Class<?> api = Class.forName("android.net.IVpnManager");
        Object manager = Class.forName("android.net.IVpnManager$Stub")
                .getMethod("asInterface", IBinder.class).invoke(null, binder);
        boolean enabled = "enable".equals(arguments[0]);
        String selected = (String) api.getMethod("getAlwaysOnVpnPackage", int.class).invoke(manager, 0);
        if (selected != null && !"app.piko.tor".equals(selected)) {
            throw new SecurityException("Refusing to replace another VPN provider");
        }
        if (!"status".equals(arguments[0])) {
            Method setAlwaysOn = api.getMethod("setAlwaysOnVpnPackage", int.class, String.class, boolean.class, List.class);
            Object result = setAlwaysOn.invoke(manager, 0, enabled ? "app.piko.tor" : null, enabled, Collections.emptyList());
            if (!Boolean.TRUE.equals(result)) throw new IllegalStateException("Platform rejected always-on VPN configuration");
            selected = (String) api.getMethod("getAlwaysOnVpnPackage", int.class).invoke(manager, 0);
        }
        boolean lockdown = (Boolean) api.getMethod("isVpnLockdownEnabled", int.class).invoke(manager, 0);
        if (enabled && (!"app.piko.tor".equals(selected) || !lockdown)) {
            throw new IllegalStateException("Platform did not retain requested lockdown");
        }
        System.out.println("app.piko.tor".equals(selected) && lockdown
                ? "PIKO_LAB_VPN_ENABLED" : "PIKO_LAB_VPN_DISABLED");
    }
}
