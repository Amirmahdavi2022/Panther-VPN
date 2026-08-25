package com.firstham.aethergui.vpngate;

import android.app.Dialog;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Country picker for relay mode.
 *
 * The directory is fetched off the main thread and the list is built from whatever comes back, so
 * the countries offered are the ones that actually have servers right now rather than a hardcoded
 * list that slowly goes stale.
 */
public final class LocationPicker {

    public interface OnPicked {
        /** countryCode is null for Automatic. */
        void picked(String countryCode, String countryName);
    }

    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private LocationPicker() {
    }

    public static void show(Context context, String currentCode, OnPicked callback) {
        Dialog dialog = new Dialog(context, com.google.android.material.R.style.Theme_Material3_DayNight_Dialog);

        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(context, 20);
        root.setPadding(pad, pad, pad, pad);
        root.setBackgroundColor(0xFF0A0A0C);

        TextView title = new TextView(context);
        title.setText(context.getString(com.firstham.aethergui.R.string.picker_title));
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(18f);
        root.addView(title);

        ProgressBar spinner = new ProgressBar(context);
        spinner.setPadding(0, dp(context, 24), 0, dp(context, 24));
        root.addView(spinner);

        LinearLayout list = new LinearLayout(context);
        list.setOrientation(LinearLayout.VERTICAL);

        android.widget.ScrollView scroller = new android.widget.ScrollView(context);
        scroller.addView(list);
        scroller.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 420)));
        root.addView(scroller);

        dialog.setContentView(root);
        dialog.show();

        // Automatic is always available, so it can be offered before the directory arrives.
        list.addView(row(context, context.getString(com.firstham.aethergui.R.string.picker_automatic),
                context.getString(com.firstham.aethergui.R.string.picker_automatic_detail),
                currentCode == null, v -> {
                    callback.picked(null, null);
                    dialog.dismiss();
                }));

        VpnGateRepository repository = new VpnGateRepository(context.getFilesDir());
        WORKER.execute(() -> {
            List<VpnGateServer> servers = repository.load(false);
            Map<String, Integer> countries = VpnGateDirectory.countries(servers);
            List<String> codes = new ArrayList<>(countries.keySet());

            MAIN.post(() -> {
                spinner.setVisibility(View.GONE);
                if (codes.isEmpty()) {
                    TextView empty = new TextView(context);
                    empty.setText(context.getString(com.firstham.aethergui.R.string.picker_unavailable));
                    empty.setTextColor(0xFF9A9AA6);
                    empty.setPadding(0, dp(context, 12), 0, 0);
                    list.addView(empty);
                    return;
                }
                for (String code : codes) {
                    String name = VpnGateDirectory.countryName(servers, code);
                    int count = countries.get(code);
                    VpnGateServer best = VpnGateDirectory.inCountry(servers, code).get(0);
                    String detail = String.format(Locale.US, "%d server%s · %.0f Mbps · %d ms",
                            count, count == 1 ? "" : "s", best.speedMbps(), best.pingMs);
                    list.addView(row(context, flag(code) + "  " + name, detail,
                            code.equals(currentCode), v -> {
                                callback.picked(code, name);
                                dialog.dismiss();
                            }));
                }
            });
        });
    }

    private static View row(Context context, String label, String detail, boolean selected,
                            View.OnClickListener click) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(context, 14);
        row.setPadding(pad, pad, pad, pad);
        row.setBackgroundColor(selected ? 0xFF16161C : 0x00000000);
        row.setOnClickListener(click);

        TextView top = new TextView(context);
        top.setText(label);
        top.setTextColor(selected ? 0xFF4DA3FF : 0xFFFFFFFF);
        top.setTextSize(15f);
        row.addView(top);

        TextView bottom = new TextView(context);
        bottom.setText(detail);
        bottom.setTextColor(0xFF9A9AA6);
        bottom.setTextSize(12f);
        row.addView(bottom);

        return row;
    }

    /** Regional-indicator flag for an ISO country code. */
    static String flag(String countryCode) {
        if (countryCode == null || countryCode.length() != 2) return "";
        String upper = countryCode.toUpperCase(Locale.US);
        int first = Character.codePointAt(upper, 0) - 'A' + 0x1F1E6;
        int second = Character.codePointAt(upper, 1) - 'A' + 0x1F1E6;
        return new String(Character.toChars(first)) + new String(Character.toChars(second));
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
