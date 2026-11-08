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
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.preference.Preference;
import android.preference.PreferenceScreen;

import app.morphe.extension.crimera.PikoUtils;

public final class TorCompanion {
    private static final String PACKAGE = "app.piko.tor";
    private static final String DOWNLOAD_URL = "https://github.com/nassim-arifette/piko-tor/releases/download/v3.9.0-tor.2/piko-tor-0.1.0-experimental.apk";

    private TorCompanion() {}

    public static String actionTitle(Context context) {
        return str(Build.VERSION.SDK_INT >= 29 && !isInstalled(context)
                ? "piko_tor_download" : "piko_tor_open");
    }

    @SuppressWarnings("deprecation")
    public static void refresh(Context context, PreferenceScreen screen) {
        if (screen == null) return;
        Preference preference = screen.findPreference(Settings.TOR_COMPANION);
        if (preference == null) return;
        preference.setTitle(actionTitle(context));
        preference.setSummary(summary(context));
    }

    private static boolean isInstalled(Context context) {
        try {
            context.getPackageManager().getPackageInfo(PACKAGE, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    public static String summary(Context context) {
        if (Build.VERSION.SDK_INT < 29) return str("piko_tor_unsupported");
        try {
            String version = context.getPackageManager().getPackageInfo(PACKAGE, 0).versionName;
            return str("piko_tor_installed", version) + "\n" + str("piko_tor_unverified");
        } catch (PackageManager.NameNotFoundException e) {
            return str("piko_tor_missing");
        }
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
        if (!isInstalled(activity)) {
            new AlertDialog.Builder(activity)
                    .setTitle(str("piko_tor_download"))
                    .setMessage(str("piko_tor_download_warning"))
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(str("piko_tor_download"), (dialog, which) -> {
                        try {
                            activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(DOWNLOAD_URL))
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
