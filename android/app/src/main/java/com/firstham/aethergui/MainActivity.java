package com.firstham.aethergui;

import android.animation.ValueAnimator;
import android.content.BroadcastReceiver;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.app.StatusBarManager;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MenuItem;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Toast;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.activity.OnBackPressedCallback;
import androidx.core.content.ContextCompat;
import androidx.core.view.GravityCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

import com.firstham.aethergui.databinding.ActivityMainBinding;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import com.firstham.aethergui.vpngate.EngineRouter;
import com.firstham.aethergui.vpngate.LocationPicker;

public final class MainActivity extends AppCompatActivity {
    private static final int VPN_REQUEST = 41;
    private static final int NOTIFICATION_REQUEST = 42;
    private static final int APPS_REQUEST = 43;
    private static final String INTERNAL_PERMISSION = "io.github.amirmahdavi2023.panther.permission.INTERNAL";
    private ActivityMainBinding binding;
    private SharedPreferences preferences;
    private String state = "disconnected";
    private String page = "connect";
    private boolean receiverRegistered;
    private boolean syncingNav;
    private String endpoint = "";
    private String locationDetail = "";
    /** True while the chosen exit location routes through the OpenVPN relay engine. */
    private boolean relayMode;
    private final Handler updateHandler = new Handler(Looper.getMainLooper());
    private final Runnable updateProgressPoll = new Runnable() {
        @Override public void run() {
            if (binding == null) return;
            renderUpdateState();
            if ("downloading".equals(getSharedPreferences(UpdateConfig.PREFS, MODE_PRIVATE).getString("status", ""))) updateHandler.postDelayed(this, 1000);
        }
    };

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            // In relay mode the OpenVPN engine owns the tunnel and the Aether service is idle.
            // Letting its "disconnected" broadcast through here would wipe the relay's own state
            // off the screen a moment after the relay came up.
            if (relayMode) return;
            if (AetherVpnService.ACTION_STATUS.equals(intent.getAction())) {
                endpoint = intent.getStringExtra("endpoint");
                locationDetail = intent.getStringExtra("locationDetail");
                renderState(intent.getStringExtra("state"), intent.getStringExtra("message"));
            }
            else if (AetherVpnService.ACTION_STATS.equals(intent.getAction())) renderStats(intent);
            else if (UpdateConfig.ACTION_STATE.equals(intent.getAction())) renderUpdateState();
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        preferences = getSharedPreferences("aether", MODE_PRIVATE);
        AppCompatDelegate.setDefaultNightMode(themeMode(preferences.getInt("theme", 2)));
        super.onCreate(savedInstanceState);
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        binding.getRoot().setAlpha(0f);
        binding.getRoot().setTranslationY(18f);
        binding.getRoot().post(() -> binding.getRoot().animate().alpha(1f).translationY(0f).setDuration(420).setInterpolator(new android.view.animation.DecelerateInterpolator()).start());
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        int toolbarHeight = binding.toolbar.getLayoutParams().height;
        ViewCompat.setOnApplyWindowInsetsListener(binding.root, (view, insets) -> {
            androidx.core.graphics.Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            binding.mainContent.setPadding(bars.left, 0, bars.right, 0);
            binding.toolbar.setPadding(binding.toolbar.getPaddingLeft(), bars.top, binding.toolbar.getPaddingRight(), binding.toolbar.getPaddingBottom());
            android.view.ViewGroup.LayoutParams params = binding.toolbar.getLayoutParams();
            params.height = toolbarHeight + bars.top;
            binding.toolbar.setLayoutParams(params);
            binding.pageContainer.setPadding(0, 0, 0, 0);
            binding.bottomNav.setPadding(0, 0, 0, bars.bottom);
            return insets;
        });
        setupDropdowns();
        restoreSettings();
        setupNavigation();
        binding.toolbar.setTitle("");
        binding.toolbarTitle.setText(R.string.app_name);
        setupActions();
        requestNotificationPermission();
        AppUpdateManager.initialize(this);
        binding.statusVersion.setText(getString(R.string.version_format, BuildConfig.VERSION_NAME));
        binding.currentVersionValue.setText(BuildConfig.VERSION_NAME);
        binding.autoDownloadSwitch.setChecked(getSharedPreferences(UpdateConfig.PREFS, MODE_PRIVATE).getBoolean(UpdateConfig.KEY_AUTO_DOWNLOAD, false));
        renderUpdateState();
        // The VPN Gate relay countries are public, heavily abused endpoints; in practice they
        // refuse the handshake far more often than they accept it, and a country list that mostly
        // fails is worse than no country list. Automatic (the Aether core) is the only exit now.
        // Anyone whose preferences still point at a country gets moved back here, once, silently.
        EngineRouter.setLocation(preferences, null, null);
        relayMode = false;
        binding.exitLocationCard.setVisibility(View.GONE);
        binding.exitLocationCard.setClickable(false);
        renderState("disconnected", getString(R.string.status_ready_message));
        if (getIntent().getBooleanExtra(AethonTileService.EXTRA_CONNECT_FROM_TILE, false)) {
            getIntent().removeExtra(AethonTileService.EXTRA_CONNECT_FROM_TILE);
            binding.root.post(this::connect);
        }
    }

    private void setupDropdowns() {
        setAdapter(binding.protocolInput, R.array.protocol_labels);
        setAdapter(binding.scanInput, R.array.scan_labels);
        setAdapter(binding.transportInput, R.array.transport_labels);
        setAdapter(binding.ipInput, R.array.ip_labels);
        setAdapter(binding.obfuscationInput, R.array.obfuscation_labels);
        setAdapter(binding.logInput, R.array.log_labels);
        setAdapter(binding.themeInput, R.array.theme_labels);
        binding.protocolInput.setOnItemClickListener((p, v, position, id) -> { binding.protocolInput.setTag(position); updateModeUi(); saveSettings(); });
        binding.scanInput.setOnItemClickListener((p, v, position, id) -> { binding.scanInput.setTag(position); saveSettings(); });
        binding.transportInput.setOnItemClickListener((p, v, position, id) -> { binding.transportInput.setTag(position); saveSettings(); });
        binding.ipInput.setOnItemClickListener((p, v, position, id) -> { binding.ipInput.setTag(position); saveSettings(); });
        binding.obfuscationInput.setOnItemClickListener((p, v, position, id) -> { binding.obfuscationInput.setTag(position); saveSettings(); });
        binding.logInput.setOnItemClickListener((p, v, position, id) -> { binding.logInput.setTag(position); saveSettings(); });
        binding.themeInput.setOnItemClickListener((p, v, position, id) -> { binding.themeInput.setTag(position); saveSettings(); applyTheme(position); });
    }

    private void setAdapter(MaterialAutoCompleteTextView view, int arrayId) { view.setAdapter(new ArrayAdapter<>(this, R.layout.item_dropdown, getResources().getStringArray(arrayId))); }

    private void setupNavigation() {
        binding.toolbar.setNavigationOnClickListener(v -> binding.root.openDrawer(GravityCompat.START));
        binding.toolbar.setOnMenuItemClickListener(item -> {
            if (item.getItemId() != R.id.action_telegram) return false;
            openTelegram();
            return true;
        });
        binding.navigationView.setNavigationItemSelectedListener(item -> { selectPage(item); binding.root.closeDrawer(GravityCompat.START); return true; });
        binding.navigationView.setCheckedItem(R.id.nav_connect);
        binding.bottomNav.setOnItemSelectedListener(item -> { if (!syncingNav) selectPage(item); return true; });
        binding.bottomNav.setSelectedItemId(R.id.nav_connect);
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() {
                if (binding.root.isDrawerOpen(GravityCompat.START)) {
                    binding.root.closeDrawer(GravityCompat.START);
                } else if (!"connect".equals(page)) {
                    showPage("connect");
                }
            }
        });
    }

    private void selectPage(MenuItem item) {
        int id = item.getItemId();
        showPage(id == R.id.nav_configurations ? "configurations" : id == R.id.nav_settings ? "settings" : id == R.id.nav_about ? "about" : "connect");
    }

    private void showPage(String destination) {
        page = destination;
        binding.homePage.setVisibility("connect".equals(page) ? View.VISIBLE : View.GONE);
        binding.configurationsPage.setVisibility("configurations".equals(page) ? View.VISIBLE : View.GONE);
        binding.settingsPage.setVisibility("settings".equals(page) ? View.VISIBLE : View.GONE);
        binding.aboutPage.setVisibility("about".equals(page) ? View.VISIBLE : View.GONE);
        int checked = "configurations".equals(page) ? R.id.nav_configurations : "settings".equals(page) ? R.id.nav_settings : "about".equals(page) ? R.id.nav_about : R.id.nav_connect;
        binding.navigationView.setCheckedItem(checked);
        if (checked != R.id.nav_about) { syncingNav = true; binding.bottomNav.setSelectedItemId(checked); syncingNav = false; }
        int title = "configurations".equals(page) ? R.string.configurations_title : "settings".equals(page) ? R.string.settings_title : "about".equals(page) ? R.string.about : R.string.app_name;
        binding.toolbar.setTitle("");
        binding.toolbarTitle.setText(title);
        View visible = "configurations".equals(page) ? binding.configurationsPage : "settings".equals(page) ? binding.settingsPage : "about".equals(page) ? binding.aboutPage : binding.homePage;
        if (ValueAnimator.areAnimatorsEnabled()) {
            visible.setAlpha(0f);
            visible.setTranslationY(12f);
            visible.animate().alpha(1f).translationY(0f).setDuration(220).start();
        }
    }

    private void setupActions() {
        binding.connectButton.setOnClickListener(v -> { v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY); if (shouldDisconnect()) disconnect(); else connect(); });
        binding.modeGroup.addOnButtonCheckedListener((group, checkedId, checked) -> { if (!checked) return; preferences.edit().putString("mode", checkedId == R.id.proxy_mode_button ? "manual" : checkedId == R.id.smart_mode_button ? "smart" : "vpn").apply(); updateModeUi(); });
        binding.splitSwitch.setOnCheckedChangeListener((button, checked) -> { binding.splitContainer.setVisibility(checked ? View.VISIBLE : View.GONE); saveSettings(); });
        binding.routingGroup.setOnCheckedChangeListener((group, checkedId) -> { saveSettings(); updateSelectedCount(); });
        binding.locationCard.setOnClickListener(v -> { v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY); refreshLocation(); });
        binding.chooseAppsButton.setOnClickListener(v -> openAppSelection());
        binding.advancedToggle.setOnClickListener(v -> { boolean show = binding.advancedContainer.getVisibility() != View.VISIBLE; binding.advancedContainer.setVisibility(show ? View.VISIBLE : View.GONE); binding.advancedToggle.setText(show ? R.string.hide_advanced : R.string.show_advanced); });
        binding.resetButton.setOnClickListener(v -> resetDefaults());
        binding.checkUpdatesButton.setOnClickListener(v -> checkForUpdates());
        binding.downloadUpdateButton.setOnClickListener(v -> { String status = getSharedPreferences(UpdateConfig.PREFS, MODE_PRIVATE).getString("status", ""); if ("ready_install".equals(status)) sendBroadcast(new Intent(this, AppUpdateReceiver.class).setAction(UpdateConfig.ACTION_INSTALL)); else Toast.makeText(this, AppUpdateManager.startDownload(this, false) ? R.string.update_download_started : R.string.update_download_failed, Toast.LENGTH_SHORT).show(); });
        binding.autoDownloadSwitch.setOnCheckedChangeListener((button, checked) -> { getSharedPreferences(UpdateConfig.PREFS, MODE_PRIVATE).edit().putBoolean(UpdateConfig.KEY_AUTO_DOWNLOAD, checked).apply(); AppUpdateManager.setAutomaticChecks(this, checked); if (checked) checkForUpdates(); });
        binding.notificationSettingsButton.setOnClickListener(v -> openNotificationSettings());
        binding.addTileButton.setOnClickListener(v -> requestQuickSettingsTile());
        binding.telegramCard.setOnClickListener(v -> openTelegram());
        binding.aboutTelegramCard.setOnClickListener(v -> openTelegram());
    }

    private void restoreSettings() {
        String mode = preferences.getString("mode", "vpn");
        binding.modeGroup.check("manual".equals(mode) ? R.id.proxy_mode_button : "smart".equals(mode) ? R.id.smart_mode_button : R.id.vpn_mode_button);
        setSelection(binding.protocolInput, "protocol", ConnectionDefaults.PROTOCOL_INDEX, R.array.protocol_labels);
        setSelection(binding.scanInput, "scan", ConnectionDefaults.SCAN_INDEX, R.array.scan_labels);
        setSelection(binding.transportInput, "transport", 0, R.array.transport_labels);
        setSelection(binding.ipInput, "ip", 0, R.array.ip_labels);
        setSelection(binding.obfuscationInput, "obfuscation", 0, R.array.obfuscation_labels);
        setSelection(binding.logInput, "log", 0, R.array.log_labels);
        setSelection(binding.themeInput, "theme", 2, R.array.theme_labels);
        binding.socksInput.setText(preferences.getString("socks", getString(R.string.default_socks_address)));
        renderExitLocation();
        binding.peerInput.setText(preferences.getString("peer", "")); binding.mtuInput.setText(preferences.getString("mtu", getString(R.string.default_mtu)));
        binding.dnsSwitch.setChecked(preferences.getBoolean("dnsLeak", true)); binding.killswitchSwitch.setChecked(preferences.getBoolean("killSwitch", false)); binding.reconnectSwitch.setChecked(preferences.getBoolean("quickReconnect", true));
        boolean split = preferences.getInt("routing", 0) >= 2; binding.splitSwitch.setChecked(split); binding.splitContainer.setVisibility(split ? View.VISIBLE : View.GONE); binding.routingGroup.check(preferences.getInt("routing", 2) == 3 ? R.id.exclude_apps_radio : R.id.include_apps_radio); updateModeUi(); updateSelectedCount();
    }

    private void setSelection(MaterialAutoCompleteTextView view, String key, int fallback, int arrayId) { setSelection(view, preferences.getInt(key, fallback), arrayId); }
    private void setSelection(MaterialAutoCompleteTextView view, int index, int arrayId) { String[] values = getResources().getStringArray(arrayId); index = Math.max(0, Math.min(values.length - 1, index)); view.setText(values[index], false); view.setTag(index); }

    private void updateModeUi() { String mode = preferences.getString("mode", "vpn"); binding.modeSummary.setText("smart".equals(mode) ? R.string.smart_mode_summary : R.string.status_ready_message); binding.protocolLayout.setVisibility("smart".equals(mode) ? View.GONE : View.VISIBLE); binding.transportLayout.setVisibility("smart".equals(mode) || selectedIndex(binding.protocolInput) != 0 ? View.GONE : View.VISIBLE); }

    private void connect() {
        if (!validSocks(text(binding.socksInput))) { binding.socksInput.setError(getString(R.string.invalid_socks)); return; }
        if (binding.splitSwitch.isChecked() && selectedPackages().isEmpty() && binding.routingGroup.getCheckedRadioButtonId() == R.id.include_apps_radio) { Toast.makeText(this, R.string.split_include_empty, Toast.LENGTH_LONG).show(); return; }
        saveSettings();
        if (!"manual".equals(preferences.getString("mode", "vpn"))) { Intent permission = VpnService.prepare(this); if (permission != null) { startActivityForResult(permission, VPN_REQUEST); return; } }
        startSelectedEngine();
    }

    private void disconnect() {
        // Only one VpnService can hold the tunnel, but either engine may be the one holding it,
        // so stop both rather than guessing which.
        VpnConnectionController.disconnect(this);
        EngineRouter.stopAll(this);
    }

    /** Routes the connect to whichever engine the chosen exit location needs. */
    private void startSelectedEngine() {
        relayMode = EngineRouter.usesRelay(preferences);
        if (!relayMode) {
            EngineRouter.stopAll(this);
            VpnConnectionController.connect(this, preferences);
            return;
        }
        // Relay mode runs OpenVPN, so the Aether core must not be holding the tunnel.
        VpnConnectionController.disconnect(this);
        // The directory lookup happens off-thread and can take a second or two on a cold cache.
        // Move the orb now so the press is visibly acknowledged instead of appearing to do nothing.
        renderState("starting", getString(R.string.status_connecting));
        EngineRouter.connectRelay(this, preferences, new EngineRouter.RelayCallback() {
            @Override public void connecting(String countryName) {
                binding.locationValue.setText(getString(R.string.relay_connecting, countryName));
            }

            @Override public void connected(com.firstham.aethergui.vpngate.VpnGateServer server) {
                binding.locationValue.setText(getString(R.string.relay_connected, server.countryName));
            }

            @Override public void failed(String reason) {
                Toast.makeText(MainActivity.this, reason, Toast.LENGTH_LONG).show();
                binding.locationValue.setText(R.string.connection_location_unavailable);
                renderState("error", reason);
            }
        });
    }

    private void renderExitLocation() {
        String code = EngineRouter.location(preferences);
        if (code == null) {
            binding.exitLocationValue.setText(R.string.picker_automatic);
            return;
        }
        binding.exitLocationValue.setText(LocationPicker.flag(code) + "  " + EngineRouter.locationName(preferences));
    }

    private void openAppSelection() {
        String key = binding.routingGroup.getCheckedRadioButtonId() == R.id.exclude_apps_radio ? "splitExcludeApps" : "splitIncludeApps";
        startActivityForResult(new Intent(this, AppSelectionActivity.class).putExtra(AppSelectionActivity.EXTRA_PACKAGES, preferences.getString(key, "")), APPS_REQUEST);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) { super.onActivityResult(requestCode, resultCode, data); if (requestCode == VPN_REQUEST) { if (resultCode == RESULT_OK) startSelectedEngine(); else Toast.makeText(this, R.string.vpn_permission_denied, Toast.LENGTH_LONG).show(); } else if (requestCode == APPS_REQUEST) { if (data != null && data.getBooleanExtra(AppSelectionActivity.EXTRA_RETURN_HOME, false)) showPage("connect"); else if (resultCode == RESULT_OK && data != null) { String key = binding.routingGroup.getCheckedRadioButtonId() == R.id.exclude_apps_radio ? "splitExcludeApps" : "splitIncludeApps"; preferences.edit().putString(key, data.getStringExtra(AppSelectionActivity.EXTRA_PACKAGES)).apply(); updateSelectedCount(); saveSettings(); } } }

    private void renderState(String newState, String message) {
        state = newState == null ? "disconnected" : newState;
        boolean connected = "connected".equals(state);
        boolean transitioning = "starting".equals(state) || "smart-testing".equals(state) || "scanning".equals(state) || "securing".equals(state) || "reconnecting".equals(state) || "disconnecting".equals(state);
        binding.connectButton.setEnabled(!"disconnecting".equals(state));
        String orbLabel = connected ? getString(R.string.disconnect) : transitioning ? ("disconnecting".equals(state) ? getString(R.string.disconnecting) : getString(R.string.connecting)) : getString(R.string.connect);
        binding.connectButton.setConnectionState(state, orbLabel);
        binding.connectButton.setContentDescription(orbLabel);
        binding.connectionStatus.setText(connected ? R.string.status_connected : transitioning ? ("disconnecting".equals(state) ? R.string.status_disconnecting : R.string.status_connecting) : ("error".equals(state) || "blocked".equals(state) ? R.string.status_error : R.string.status_disconnected));
        binding.statusDot.setBackgroundResource(connected ? R.drawable.status_dot_connected : transitioning ? R.drawable.status_dot_connecting : R.drawable.status_dot);
        binding.progress.setVisibility(View.GONE);
        if (connected) {
            binding.connectionMessage.setVisibility(View.GONE);
            binding.connectionInfo.setVisibility(View.VISIBLE);
            renderLocation();
        }
        else if (transitioning) { binding.connectionMessage.setVisibility(View.GONE); binding.connectionInfo.setVisibility(View.VISIBLE); }
        else { boolean showError = "error".equals(state) || "blocked".equals(state); binding.connectionMessage.setText(message == null ? getString(R.string.status_error) : message); binding.connectionMessage.setVisibility(showError ? View.VISIBLE : View.GONE); binding.connectionInfo.setVisibility(View.VISIBLE); }
        preferences.edit().putString("state", state).putString("message", message == null ? "" : message).apply();
        if (!connected) resetStats();
    }

    private boolean shouldDisconnect() { return "connected".equals(state) || "starting".equals(state) || "smart-testing".equals(state) || "scanning".equals(state) || "securing".equals(state) || "reconnecting".equals(state) || "disconnecting".equals(state); }

    private void renderStats(Intent intent) {
        long tx = Math.max(0, intent.getLongExtra("tx", 0));
        long rx = Math.max(0, intent.getLongExtra("rx", 0));
        animateMetric(binding.uploadValue, formatTraffic(tx));
        animateMetric(binding.downloadValue, formatTraffic(rx));
        long ping = intent.getLongExtra("ping", -1);
        binding.pingValue.setText(getString(R.string.ping_value, ping >= 0 ? getString(R.string.ping_millis, Long.toString(ping)) : getString(R.string.metric_unavailable)));
    }

    private void animateMetric(TextView view, String value) {
        if (value.equals(view.getTag())) return;
        view.setTag(value);
        view.animate().cancel();
        view.setAlpha(0.45f);
        view.setScaleX(.96f);
        view.setScaleY(.96f);
        view.setText(value);
        view.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(220).start();
    }

    private String formatTraffic(long bytes) {
        // getString() formats with the device locale, which renders Persian or Arabic digits on
        // phones set to those languages. The app is English-only, so pin the digits to match.
        if (bytes < 1024L * 1024L) {
            return String.format(java.util.Locale.US, getString(R.string.traffic_kilobytes), bytes / 1024.0);
        }
        if (bytes < 1024L * 1024L * 1024L) {
            return String.format(java.util.Locale.US, getString(R.string.traffic_megabytes), bytes / (1024.0 * 1024.0));
        }
        return String.format(java.util.Locale.US, getString(R.string.traffic_gigabytes), bytes / (1024.0 * 1024.0 * 1024.0));
    }

    private void resetStats() {
        binding.uploadValue.setText(R.string.metric_unavailable);
        binding.downloadValue.setText(R.string.metric_unavailable);
        binding.pingValue.setText(getString(R.string.ping_value, getString(R.string.metric_unavailable)));
        binding.locationValue.setText(R.string.connection_location_unavailable);
        binding.locationDetail.setVisibility(View.GONE);
        binding.locationRefresh.setVisibility(View.GONE);
    }

    /** Fills the location card from the last status broadcast. */
    private void renderLocation() {
        boolean known = endpoint != null && !endpoint.isEmpty();
        binding.locationValue.setText(known ? endpoint : getString(R.string.connection_location_unavailable));
        boolean hasDetail = locationDetail != null && !locationDetail.isEmpty();
        binding.locationDetail.setText(hasDetail ? locationDetail : "");
        binding.locationDetail.setVisibility(hasDetail ? View.VISIBLE : View.GONE);
        // The refresh control only means anything while a tunnel is up to re-ask through.
        binding.locationRefresh.setVisibility("connected".equals(state) ? View.VISIBLE : View.GONE);
    }

    private void refreshLocation() {
        if (!"connected".equals(state)) return;
        binding.locationValue.setText(R.string.location_refreshing);
        binding.locationDetail.setVisibility(View.GONE);
        binding.locationRefresh.animate().rotationBy(360f).setDuration(600).start();
        startService(new Intent(this, AetherVpnService.class).setAction(AetherVpnService.ACTION_REFRESH_LOCATION));
    }

    private void saveSettings() {
        int routing = binding.splitSwitch.isChecked() ? (binding.routingGroup.getCheckedRadioButtonId() == R.id.exclude_apps_radio ? 3 : 2) : 0;
        String include = preferences.getString("splitIncludeApps", ""); String exclude = preferences.getString("splitExcludeApps", "");
        preferences.edit().putInt("protocol", selectedIndex(binding.protocolInput)).putInt("scan", selectedIndex(binding.scanInput)).putInt("transport", selectedIndex(binding.transportInput)).putInt("ip", selectedIndex(binding.ipInput)).putInt("obfuscation", selectedIndex(binding.obfuscationInput)).putInt("log", selectedIndex(binding.logInput)).putInt("theme", selectedIndex(binding.themeInput)).putInt("routing", routing).putString("splitApps", routing == 3 ? exclude : include).putString("socks", text(binding.socksInput)).putString("peer", text(binding.peerInput)).putString("mtu", text(binding.mtuInput)).putBoolean("dnsLeak", binding.dnsSwitch.isChecked()).putBoolean("killSwitch", binding.killswitchSwitch.isChecked()).putBoolean("quickReconnect", binding.reconnectSwitch.isChecked()).apply();
    }

    private Set<String> selectedPackages() { Set<String> result = new LinkedHashSet<>(); String key = binding.routingGroup.getCheckedRadioButtonId() == R.id.exclude_apps_radio ? "splitExcludeApps" : "splitIncludeApps"; AppSelectionActivity.parsePackages(preferences.getString(key, ""), result); return result; }
    private void updateSelectedCount() { if (binding == null) return; binding.selectedAppsCount.setText(getResources().getQuantityString(R.plurals.app_picker_selected_count, selectedPackages().size(), selectedPackages().size())); }
    private void resetDefaults() { preferences.edit().clear().putInt("theme", 2).apply(); restoreSettings(); saveSettings(); applyTheme(2); }

    private void checkForUpdates() { SharedPreferences updates = getSharedPreferences(UpdateConfig.PREFS, MODE_PRIVATE); updates.edit().putString("status", "checking").apply(); renderUpdateState(); binding.checkUpdatesButton.setEnabled(false); AppUpdateManager.checkNow(this, new AppUpdateManager.Listener() { @Override public void onComplete() { binding.checkUpdatesButton.setEnabled(true); renderUpdateState(); } @Override public void onError(Throwable error) { binding.checkUpdatesButton.setEnabled(true); renderUpdateState(); String detail = error == null ? "" : error.getMessage(); Toast.makeText(MainActivity.this, detail == null || detail.isEmpty() ? getString(R.string.update_failed) : getString(R.string.update_failed) + ": " + detail, Toast.LENGTH_LONG).show(); } }); }
    private void renderUpdateState() { if (binding == null) return; SharedPreferences updates = getSharedPreferences(UpdateConfig.PREFS, MODE_PRIVATE); String latest = updates.getString(UpdateConfig.KEY_LATEST_VERSION, ""); String status = updates.getString("status", ""); binding.latestVersionValue.setText(latest.isEmpty() ? getString(R.string.not_checked) : latest); int id = "up_to_date".equals(status) ? R.string.update_up_to_date : "available".equals(status) ? R.string.update_available : "downloading".equals(status) ? R.string.update_downloading : "ready_install".equals(status) ? R.string.update_ready_install : "checking".equals(status) ? R.string.update_checking : "download_failed".equals(status) ? R.string.update_download_failed : "verification_failed".equals(status) ? R.string.update_verification_failed : "failed".equals(status) ? R.string.update_failed : R.string.not_checked; binding.updateStatusValue.setText(id); String notes = updates.getString(UpdateConfig.KEY_RELEASE_NOTES, ""); binding.releaseNotesValue.setText(notes); binding.releaseNotesValue.setVisibility(notes.isEmpty() ? View.GONE : View.VISIBLE); boolean downloading = "downloading".equals(status); int progress = downloading ? AppUpdateManager.downloadProgress(this) : -1; binding.updateProgress.setVisibility(downloading ? View.VISIBLE : View.GONE); binding.updateProgress.setIndeterminate(downloading && progress <= 0); if (progress > 0) binding.updateProgress.setProgress(progress); boolean action = "available".equals(status) || "download_failed".equals(status) || "verification_failed".equals(status) || "ready_install".equals(status); binding.downloadUpdateButton.setVisibility(action ? View.VISIBLE : View.GONE); binding.downloadUpdateButton.setText("ready_install".equals(status) ? R.string.install_update : R.string.download_update); }

    private void applyTheme(int choice) { preferences.edit().putInt("theme", choice).apply(); AppCompatDelegate.setDefaultNightMode(themeMode(choice)); }
    private static int themeMode(int choice) { return choice == 1 ? AppCompatDelegate.MODE_NIGHT_NO : choice == 2 ? AppCompatDelegate.MODE_NIGHT_YES : AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM; }
    private void requestNotificationPermission() { if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, NOTIFICATION_REQUEST); }
    private void openNotificationSettings() {
        try { startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName())); }
        catch (Exception ignored) { startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()))); }
    }
    private void requestQuickSettingsTile() {
        if (Build.VERSION.SDK_INT >= 33) {
            StatusBarManager manager = getSystemService(StatusBarManager.class);
            manager.requestAddTileService(new ComponentName(this, AethonTileService.class), getString(R.string.tile_name), Icon.createWithResource(this, R.drawable.ic_aethon_mono), getMainExecutor(), result -> Toast.makeText(this, R.string.tile_add_requested, Toast.LENGTH_SHORT).show());
            return;
        }
        try { startActivity(new Intent("android.settings.QUICK_SETTINGS_SETTINGS")); }
        catch (Exception ignored) { Toast.makeText(this, R.string.tile_add_manual, Toast.LENGTH_LONG).show(); }
    }
    private void openTelegram() {
        Intent direct = new Intent(Intent.ACTION_VIEW, Uri.parse("tg://resolve?domain=parsv2r"));
        for (String packageName : new String[]{"org.telegram.messenger", "org.telegram.messenger.web"}) {
            try {
                direct.setPackage(packageName);
                startActivity(direct);
                return;
            } catch (ActivityNotFoundException ignored) { }
        }
        try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/parsv2r"))); }
        catch (ActivityNotFoundException ignored) { Toast.makeText(this, R.string.telegram_fallback, Toast.LENGTH_SHORT).show(); }
    }
    private int selectedIndex(MaterialAutoCompleteTextView view) { Object tag = view.getTag(); return tag instanceof Integer ? (Integer) tag : 0; }
    private String text(com.google.android.material.textfield.TextInputEditText view) { return view.getText() == null ? "" : view.getText().toString().trim(); }
    private boolean validSocks(String value) { int split = value.lastIndexOf(':'); if (split <= 0) return false; try { int port = Integer.parseInt(value.substring(split + 1)); return port > 0 && port <= 65535; } catch (Exception ignored) { return false; } }

    @Override protected void onStart() { super.onStart(); if (!receiverRegistered) { IntentFilter filter = new IntentFilter(); filter.addAction(AetherVpnService.ACTION_STATUS); filter.addAction(AetherVpnService.ACTION_STATS); filter.addAction(UpdateConfig.ACTION_STATE); ContextCompat.registerReceiver(this, receiver, filter, INTERNAL_PERMISSION, null, ContextCompat.RECEIVER_NOT_EXPORTED); receiverRegistered = true; } if (!relayMode) startService(new Intent(this, AetherVpnService.class).setAction(AetherVpnService.ACTION_QUERY)); updateHandler.removeCallbacks(updateProgressPoll); updateHandler.post(updateProgressPoll); }
    @Override protected void onStop() { updateHandler.removeCallbacks(updateProgressPoll); if (receiverRegistered) { unregisterReceiver(receiver); receiverRegistered = false; } super.onStop(); }
}
