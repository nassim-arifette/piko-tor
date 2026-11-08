package app.piko.tor;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.MotionEvent;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class MainActivity extends Activity {
    private static final int VPN_CONSENT = 101;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView status;
    private TextView target;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int padding = (int) (24 * getResources().getDisplayMetrics().density);
        content.setPadding(padding, padding, padding, padding);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(content);
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets;
        });
        setContentView(scroll);
        TextView title = new TextView(this);
        title.setText(R.string.app_name);
        title.setTextSize(24);
        content.addView(title);
        TextView warning = new TextView(this);
        warning.setText(R.string.experimental_warning);
        content.addView(warning);
        target = new TextView(this);
        target.setTextIsSelectable(true);
        content.addView(target);
        status = new TextView(this);
        content.addView(status);
        addButton(content, R.string.choose_target, this::chooseTarget);
        addButton(content, R.string.start_vpn, () -> showDialog(new AlertDialog.Builder(this)
                .setTitle(R.string.start_vpn).setMessage(R.string.configuration_warning)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> prepareVpn()).create()));
        addButton(content, R.string.system_settings, () -> startActivity(new Intent(Settings.ACTION_VPN_SETTINGS)));
        addButton(content, R.string.refresh_tor, () -> {
            if (TorVpnService.active()) send(TorVpnService.REFRESH);
            else showError(R.string.start_first);
        });
        addButton(content, R.string.stop_vpn, () -> showDialog(new AlertDialog.Builder(this)
                .setTitle(R.string.stop_vpn).setMessage(R.string.stop_warning)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    TargetConfig.preferences(this).edit().putBoolean("requested", false).apply();
                    if (TorVpnService.active()) send(TorVpnService.STOP);
                }).create()));
        if (state == null) {
            // Only Android's caller identity is used. Intent package-name extras are ignored.
            String caller = getCallingPackage();
            if (caller != null && !caller.equals(getPackageName())) confirmTarget(caller);
        }
    }

    private void addButton(LinearLayout content, int label, Runnable action) {
        Button button = new Button(this) {
            @Override public boolean onFilterTouchEventForSecurity(MotionEvent event) {
                return (event.getFlags() & MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED) == 0
                        && super.onFilterTouchEventForSecurity(event);
            }
        };
        button.setText(label);
        button.setFilterTouchesWhenObscured(true);
        button.setOnClickListener(view -> action.run());
        content.addView(button);
    }

    private void chooseTarget() {
        if (TorVpnService.active()) { showError(R.string.stop_before_pairing); return; }
        Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> installed = getPackageManager().queryIntentActivities(launcher, 0);
        installed.sort(Comparator.comparing(info -> info.activityInfo.packageName));
        List<String> packages = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        for (ResolveInfo info : installed) {
            String name = info.activityInfo.packageName;
            if (name.equals(getPackageName()) || packages.contains(name)) continue;
            packages.add(name);
            labels.add(info.loadLabel(getPackageManager()) + "\n" + name);
        }
        showDialog(new AlertDialog.Builder(this).setTitle(R.string.choose_target)
                .setItems(labels.toArray(new String[0]), (dialog, which) -> confirmTarget(packages.get(which)))
                .setNegativeButton(android.R.string.cancel, null).create());
    }

    private void confirmTarget(String packageName) {
        if (TorVpnService.active()) { showError(R.string.stop_before_pairing); return; }
        try {
            TargetConfig candidate = TargetConfig.inspect(this, packageName);
            showDialog(new AlertDialog.Builder(this).setTitle(R.string.confirm_target)
                    .setMessage(getString(R.string.target_identity, candidate.packageName, candidate.certificate))
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(R.string.pair, (dialog, which) -> {
                        if (TorVpnService.active() || !candidate.valid(this)) {
                            showError(R.string.target_invalid);
                            return;
                        }
                        candidate.save(this);
                        render();
                    }).create());
        } catch (PackageManager.NameNotFoundException | SecurityException e) {
            showError(R.string.target_invalid);
        }
    }

    private void prepareVpn() {
        if (!TargetConfig.load(this).valid(this)) { showError(R.string.target_invalid); return; }
        Intent consent = VpnService.prepare(this);
        if (consent != null && VpnConflict.hasVisibleVpn(this)) {
            showVpnConflict();
            return;
        }
        if (consent == null) send(TorVpnService.START);
        else startActivityForResult(consent, VPN_CONSENT);
    }

    private void showVpnConflict() {
        showDialog(new AlertDialog.Builder(this)
                .setTitle(R.string.vpn_conflict_title)
                .setMessage(R.string.vpn_conflict_message)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.system_settings,
                        (dialog, which) -> startActivity(new Intent(Settings.ACTION_VPN_SETTINGS)))
                .create());
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == VPN_CONSENT && result == RESULT_OK) {
            // Another provider may have been selected while the consent UI was open.
            // Do not ask again or take it over as a side effect of an old result.
            if (VpnService.prepare(this) != null) {
                showError(R.string.vpn_consent_changed);
                return;
            }
            if (!TargetConfig.load(this).valid(this)) { showError(R.string.target_invalid); return; }
            send(TorVpnService.START);
        }
    }

    private void send(String action) {
        try {
            startForegroundService(new Intent(this, TorVpnService.class).setAction(action));
            if (TorVpnService.START.equals(action) && Build.VERSION.SDK_INT >= 33
                    && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 102);
            }
        } catch (RuntimeException e) {
            showError(R.string.service_error);
        }
    }

    private void showError(int message) {
        showDialog(new AlertDialog.Builder(this).setMessage(message).setPositiveButton(android.R.string.ok, null).create());
    }

    @SuppressLint("ClickableViewAccessibility") // Only obscured events are consumed; normal clicks use Button.performClick.
    private void showDialog(AlertDialog dialog) {
        dialog.show();
        for (int which : new int[]{AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE}) {
            Button button = dialog.getButton(which);
            if (button == null) continue;
            button.setFilterTouchesWhenObscured(true);
            button.setOnTouchListener((view, event) ->
                    (event.getFlags() & MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED) != 0);
        }
    }

    private void render() {
        TargetConfig selected = TargetConfig.load(this);
        target.setText(selected.valid(this) ? getString(R.string.target_selected, selected.packageName)
                : getString(R.string.target_invalid));
        ProtectionState state = TorVpnService.state();
        if (!TorVpnService.active()) {
            state = TargetConfig.preferences(this).getBoolean("requested", false)
                    ? ProtectionState.VPN_MISSING : ProtectionState.OFF;
        }
        status.setText(describe(this, state));
    }

    private final Runnable refresh = new Runnable() {
        @Override public void run() { render(); handler.postDelayed(this, 1000); }
    };

    @Override protected void onResume() { super.onResume(); refresh.run(); }
    @Override protected void onPause() { handler.removeCallbacks(refresh); super.onPause(); }

    static String describe(Context context, ProtectionState state) {
        return context.getString(switch (state) {
            case OFF -> R.string.state_off;
            case TARGET_INVALID -> R.string.target_invalid;
            case VPN_MISSING -> R.string.state_vpn_missing;
            case LOCKDOWN_MISSING -> R.string.state_lockdown_missing;
            case PRIVATE_DNS_UNSUPPORTED -> R.string.state_private_dns;
            case NETWORK_UNAVAILABLE -> R.string.state_network_missing;
            case CONNECTING -> R.string.state_connecting;
            case TOR_UNAVAILABLE -> R.string.state_tor_unavailable;
            case TOR_READY -> R.string.state_tor_ready;
        });
    }
}
