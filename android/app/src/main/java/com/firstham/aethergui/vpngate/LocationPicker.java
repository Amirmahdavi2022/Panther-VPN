package com.firstham.aethergui.vpngate;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import com.firstham.aethergui.R;
import com.google.android.material.bottomsheet.BottomSheetDialog;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Exit-location picker.
 *
 * Two things this sheet has to get right. First, every list row is now assembled on the worker
 * thread: the old version called back into the directory once per country from the main thread,
 * which meant sorting several thousand relays ninety times over while the UI was blocked - the
 * reason the menu felt sluggish and arrived in an arbitrary order.
 *
 * Second, the ordering is deliberate. A pure quality sort reads as random to anyone scanning for a
 * country, and a pure alphabetical sort buries the ones actually worth picking. So the sheet leads
 * with a short "fastest" group and then lists everything A-Z, with a search box once the list is
 * long enough to need one.
 */
public final class LocationPicker {

    public interface OnPicked {
        /** countryCode and countryName are both null for Automatic. */
        void picked(String countryCode, String countryName);
    }

    /** How many countries get pinned to the top as the recommended picks. */
    private static final int FASTEST_COUNT = 3;

    /** Below this many countries a search box is clutter rather than help. */
    private static final int SEARCH_THRESHOLD = 12;

    private static final int BACKGROUND = 0xFF07070A;
    private static final int SURFACE = 0xFF121216;
    private static final int SURFACE_SELECTED = 0xFF1E1E26;
    private static final int TEXT = 0xFFFFFFFF;
    private static final int TEXT_MUTED = 0xFF8E8E9A;
    private static final int ACCENT = 0xFF4DA3FF;

    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();

    private LocationPicker() {
    }

    /** One country as the sheet needs it: everything already computed, nothing left to look up. */
    private static final class Entry {
        final String code;
        final String name;
        final String detail;
        final long score;

        Entry(String code, String name, String detail, long score) {
            this.code = code;
            this.name = name;
            this.detail = detail;
            this.score = score;
        }
    }

    public static void show(Context context, String currentCode, OnPicked callback) {
        BottomSheetDialog dialog = new BottomSheetDialog(context);

        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(topRounded(BACKGROUND, dp(context, 22)));
        root.setPadding(dp(context, 20), dp(context, 12), dp(context, 20), dp(context, 8));

        root.addView(grabber(context));

        TextView title = new TextView(context);
        title.setText(R.string.picker_title);
        title.setTextColor(TEXT);
        title.setTextSize(19f);
        title.setTypeface(title.getTypeface(), Typeface.BOLD);
        title.setPadding(0, dp(context, 10), 0, 0);
        root.addView(title);

        TextView subtitle = new TextView(context);
        subtitle.setText(R.string.picker_subtitle);
        subtitle.setTextColor(TEXT_MUTED);
        subtitle.setTextSize(12.5f);
        subtitle.setPadding(0, dp(context, 4), 0, dp(context, 14));
        root.addView(subtitle);

        // Automatic never depends on the directory, so it is placed and usable straight away.
        root.addView(row(context, "\u26A1", context.getString(R.string.picker_automatic),
                context.getString(R.string.picker_automatic_detail), currentCode == null, v -> {
                    callback.picked(null, null);
                    dialog.dismiss();
                }));

        EditText search = new EditText(context);
        search.setHint(R.string.picker_search);
        search.setSingleLine(true);
        search.setTextColor(TEXT);
        search.setHintTextColor(TEXT_MUTED);
        search.setTextSize(14f);
        search.setBackground(rounded(SURFACE, dp(context, 14)));
        search.setPadding(dp(context, 14), dp(context, 12), dp(context, 14), dp(context, 12));
        search.setVisibility(View.GONE);
        LinearLayout.LayoutParams searchParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        searchParams.topMargin = dp(context, 14);
        root.addView(search, searchParams);

        ProgressBar spinner = new ProgressBar(context);
        LinearLayout.LayoutParams spinnerParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        spinnerParams.gravity = Gravity.CENTER_HORIZONTAL;
        spinnerParams.topMargin = dp(context, 28);
        spinnerParams.bottomMargin = dp(context, 28);
        root.addView(spinner, spinnerParams);

        LinearLayout list = new LinearLayout(context);
        list.setOrientation(LinearLayout.VERTICAL);

        ScrollView scroller = new ScrollView(context);
        scroller.setVerticalScrollBarEnabled(false);
        scroller.addView(list);
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 400));
        scrollParams.topMargin = dp(context, 6);
        root.addView(scroller, scrollParams);

        dialog.setContentView(root);
        dialog.show();

        WORKER.execute(() -> {
            // Everything expensive happens here, once: parse, count, rank, format.
            VpnGateRepository repository = new VpnGateRepository(context.getFilesDir());
            List<VpnGateServer> servers = repository.load(false);
            if (servers.isEmpty()) servers = repository.load(true);

            List<Entry> entries = build(servers);

            root.post(() -> {
                spinner.setVisibility(View.GONE);
                if (entries.isEmpty()) {
                    list.addView(note(context, context.getString(R.string.picker_unavailable)));
                    return;
                }
                if (entries.size() >= SEARCH_THRESHOLD) {
                    search.setVisibility(View.VISIBLE);
                    search.addTextChangedListener(new TextWatcher() {
                        @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {
                        }

                        @Override public void onTextChanged(CharSequence s, int a, int b, int c) {
                        }

                        @Override public void afterTextChanged(Editable s) {
                            render(context, list, entries, currentCode, s.toString(), callback, dialog);
                        }
                    });
                }
                render(context, list, entries, currentCode, "", callback, dialog);
            });
        });
    }

    /** Collapses the relay list into one formatted entry per country, in a single pass. */
    private static List<Entry> build(List<VpnGateServer> servers) {
        List<Entry> entries = new ArrayList<>();
        if (servers == null || servers.isEmpty()) return entries;

        Map<String, Integer> counts = VpnGateDirectory.countries(servers);
        java.util.Map<String, VpnGateServer> best = new java.util.HashMap<>();
        java.util.Comparator<VpnGateServer> quality = VpnGateDirectory.byQuality();
        for (VpnGateServer server : servers) {
            VpnGateServer leader = best.get(server.countryCode);
            if (leader == null || quality.compare(server, leader) < 0) {
                best.put(server.countryCode, server);
            }
        }

        for (Map.Entry<String, Integer> country : counts.entrySet()) {
            VpnGateServer leader = best.get(country.getKey());
            if (leader == null) continue;
            int count = country.getValue();
            String detail = String.format(Locale.US, "%d server%s · %.0f Mbps · %d ms",
                    count, count == 1 ? "" : "s", leader.speedMbps(), leader.pingMs);
            entries.add(new Entry(country.getKey(), leader.countryName, detail, leader.score));
        }
        return entries;
    }

    private static void render(Context context, LinearLayout list, List<Entry> entries,
                               String currentCode, String query, OnPicked callback,
                               BottomSheetDialog dialog) {
        list.removeAllViews();
        String needle = query == null ? "" : query.trim().toLowerCase(Locale.US);

        List<Entry> matching = new ArrayList<>();
        for (Entry entry : entries) {
            if (needle.isEmpty()
                    || entry.name.toLowerCase(Locale.US).contains(needle)
                    || entry.code.toLowerCase(Locale.US).contains(needle)) {
                matching.add(entry);
            }
        }

        if (matching.isEmpty()) {
            list.addView(note(context, context.getString(R.string.picker_no_match)));
            return;
        }

        // The fastest group is only meaningful for the unfiltered list; a search wants a plain
        // alphabetical answer, not a re-ranked one.
        List<Entry> fastest = new ArrayList<>();
        if (needle.isEmpty() && matching.size() > FASTEST_COUNT + 2) {
            List<Entry> ranked = new ArrayList<>(matching);
            Collections.sort(ranked, (a, b) -> Long.compare(b.score, a.score));
            fastest.addAll(ranked.subList(0, FASTEST_COUNT));
        }

        if (!fastest.isEmpty()) {
            list.addView(header(context, context.getString(R.string.picker_section_fastest)));
            for (Entry entry : fastest) addRow(context, list, entry, currentCode, callback, dialog);
        }

        List<Entry> alphabetical = new ArrayList<>(matching);
        Collections.sort(alphabetical, (a, b) -> a.name.compareToIgnoreCase(b.name));

        list.addView(header(context, context.getString(R.string.picker_section_all,
                alphabetical.size())));
        for (Entry entry : alphabetical) addRow(context, list, entry, currentCode, callback, dialog);
    }

    private static void addRow(Context context, LinearLayout list, Entry entry, String currentCode,
                               OnPicked callback, BottomSheetDialog dialog) {
        list.addView(row(context, flag(entry.code), entry.name, entry.detail,
                entry.code.equals(currentCode), v -> {
                    callback.picked(entry.code, entry.name);
                    dialog.dismiss();
                }));
    }

    /**
     * One tappable row: leading glyph, name over detail, and a tick when it is the current choice.
     * The tick matters - the old sheet marked the selection with a near-black background that was
     * invisible against a near-black sheet.
     */
    private static View row(Context context, String glyph, String label, String detail,
                            boolean selected, View.OnClickListener click) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(context, 14), dp(context, 12), dp(context, 14), dp(context, 12));
        row.setBackground(rounded(selected ? SURFACE_SELECTED : SURFACE, dp(context, 16)));
        row.setClickable(true);
        row.setOnClickListener(click);

        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowParams.topMargin = dp(context, 6);
        row.setLayoutParams(rowParams);

        TextView lead = new TextView(context);
        lead.setText(glyph);
        lead.setTextSize(20f);
        lead.setTextColor(TEXT);
        lead.setPadding(0, 0, dp(context, 12), 0);
        row.addView(lead);

        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView top = new TextView(context);
        top.setText(label);
        top.setTextColor(selected ? ACCENT : TEXT);
        top.setTextSize(15f);
        top.setTypeface(top.getTypeface(), selected ? Typeface.BOLD : Typeface.NORMAL);
        column.addView(top);

        TextView bottom = new TextView(context);
        bottom.setText(detail);
        bottom.setTextColor(TEXT_MUTED);
        bottom.setTextSize(12f);
        bottom.setPadding(0, dp(context, 2), 0, 0);
        column.addView(bottom);

        row.addView(column);

        TextView tick = new TextView(context);
        tick.setText(selected ? "\u2713" : "");
        tick.setTextColor(ACCENT);
        tick.setTextSize(17f);
        tick.setPadding(dp(context, 10), 0, 0, 0);
        row.addView(tick);

        return row;
    }

    private static View header(Context context, String text) {
        TextView view = new TextView(context);
        view.setText(text.toUpperCase(Locale.US));
        view.setTextColor(TEXT_MUTED);
        view.setTextSize(11f);
        view.setLetterSpacing(0.12f);
        view.setTypeface(view.getTypeface(), Typeface.BOLD);
        view.setPadding(dp(context, 4), dp(context, 16), 0, dp(context, 4));
        return view;
    }

    private static View note(Context context, String text) {
        TextView view = new TextView(context);
        view.setText(text);
        view.setTextColor(TEXT_MUTED);
        view.setTextSize(13f);
        view.setPadding(dp(context, 4), dp(context, 18), dp(context, 4), dp(context, 18));
        return view;
    }

    private static View grabber(Context context) {
        View view = new View(context);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                dp(context, 38), dp(context, 4));
        params.gravity = Gravity.CENTER_HORIZONTAL;
        view.setLayoutParams(params);
        view.setBackground(rounded(0xFF2A2A33, dp(context, 2)));
        return view;
    }

    private static GradientDrawable rounded(int color, float radius) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(radius);
        return shape;
    }

    private static GradientDrawable topRounded(int color, float radius) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadii(new float[]{radius, radius, radius, radius, 0, 0, 0, 0});
        return shape;
    }

    /** Regional-indicator flag for an ISO country code. */
    public static String flag(String countryCode) {
        if (countryCode == null || countryCode.length() != 2) return "\uD83C\uDF10";
        String upper = countryCode.toUpperCase(Locale.US);
        char first = upper.charAt(0);
        char second = upper.charAt(1);
        if (first < 'A' || first > 'Z' || second < 'A' || second > 'Z') return "\uD83C\uDF10";
        return new String(Character.toChars(first - 'A' + 0x1F1E6))
                + new String(Character.toChars(second - 'A' + 0x1F1E6));
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
