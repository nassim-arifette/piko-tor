/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */
package app.morphe.extension.twitter.settings;

import static app.morphe.extension.shared.StringRef.str;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.preference.Preference;
import android.preference.PreferenceScreen;

import app.morphe.extension.crimera.PikoUtils;

public final class TorCompanion {
    private static final String PACKAGE = "app.piko.tor";
    private static final long MIN_COMPANION_VERSION_CODE = 2;
    private static final String INSTALLATION_URL = "https://github.com/nassim-arifette/piko-tor/blob/main/companion/README.md";

    private TorCompanion() {}

    public static String actionTitle(Context context) {
        if (Build.VERSION.SDK_INT < 29) return str("piko_tor_open");
        PackageInfo installed = installedCompanion(context);
        return str(installed == null ? "piko_tor_download"
                : isCompatible(installed) ? "piko_tor_open" : "piko_tor_update");
    }

    @SuppressWarnings("deprecation")
    public static void refresh(Context context, PreferenceScreen screen) {
        if (screen == null) return;
        Preference preference = screen.findPreference(Settings.TOR_COMPANION);
        if (preference == null) return;
        preference.setTitle(actionTitle(context));
        preference.setSummary(summary(context));
    }

    private static PackageInfo installedCompanion(Context context) {
        try {
            return context.getPackageManager().getPackageInfo(PACKAGE, 0);
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    private static boolean isCompatible(PackageInfo installed) {
        return installed.getLongVersionCode() >= MIN_COMPANION_VERSION_CODE;
    }

    public static String summary(Context context) {
        if (Build.VERSION.SDK_INT < 29) return str("piko_tor_unsupported");
        PackageInfo installed = installedCompanion(context);
        if (installed == null) return str("piko_tor_missing");
        if (!isCompatible(installed)) return str("piko_tor_outdated", installed.versionName);
        return str("piko_tor_installed", installed.versionName) + "\n" + str("piko_tor_unverified");
    }

    public static void open(Context context) {
        if (Build.VERSION.SDK_INT < 29) {
            PikoUtils.toast(str("piko_tor_unsupported"));
            return;
        }
        while (context instanceof ContextWrapper && !(context instanceof Activity)) {
            Context base = ((ContextWrapper) context).getBaseContext();
            if (base == context) break;
            context = base;
        }
        if (!(context instanceof Activity)) {
            PikoUtils.toast(str("piko_tor_open_failed"));
            return;
        }
        Activity activity = (Activity) context;
        PackageInfo installed = installedCompanion(activity);
        if (installed == null || !isCompatible(installed)) {
            new AlertDialog.Builder(activity)
                    .setTitle(actionTitle(activity))
                    .setMessage(str("piko_tor_download_warning"))
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(str("piko_tor_install_instructions"), (dialog, which) -> {
                        try {
                            activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(INSTALLATION_URL))
                                    .addCategory(Intent.CATEGORY_BROWSABLE));
                        } catch (RuntimeException e) {
                            PikoUtils.toast(str("piko_tor_download_failed"));
                        }
                    })
                    .show();
            return;
        }
        Intent intent = new Intent().setComponent(new ComponentName(PACKAGE, PACKAGE + ".MainActivity"));
        try {
            // Android supplies getCallingPackage() to the companion; no trusted state is returned to X.
            activity.startActivityForResult(intent, 0x5049);
        } catch (RuntimeException e) {
            PikoUtils.toast(str("piko_tor_open_failed"));
        }
    }
}
