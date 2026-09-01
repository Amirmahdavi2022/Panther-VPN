package com.firstham.aethergui;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.widget.NestedScrollView;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Picks the exit country for the Global engine.
 *
 * <p>The one thing this sheet does that a plain country list does not: it remembers what happened
 * last time. Every country the user has actually connected through carries the result of the
 * reachability probes from that connection, so the list stops being a guess after the first few
 * tries and starts being a record of what worked on this phone, on this network.
 *
 * <p>Nothing here is a claim about which countries are good. The app has no business asserting
 * that, and any built-in table of it would be stale within weeks. It only reports back what it
 * measured.
 */
public final class RegionPicker {

    public interface OnPicked {
        /** {@code code} is empty for Automatic. */
        void picked(String code);
    }

    /** Where the per-country probe verdicts are kept. */
    private static final String KEY_VERDICT_PREFIX = "regionVerdict_";

    /** Below this many rows a search box is clutter rather than help. */
    private static final int SEARCH_THRESHOLD = 12;

    private static final int BACKGROUND = 0xFF07070A;
    private static final int SURFACE = 0xFF121216;
    private static final int SURFACE_SELECTED = 0xFF1E1E26;
    private static final int TEXT = 0xFFFFFFFF;
    private static final int TEXT_MUTED = 0xFF8E8E9A;
    private static final int ACCENT = 0xFF4DA3FF;
    private static final int GOOD = 0xFF4ADE80;
    private static final int BAD = 0xFFF87171;

    private RegionPicker() { }

    /** Files the probe outcome against the country the tunnel actually came out in. */
    public static void remember(SharedPreferences preferences, String countryCode, String verdict) {
        String code = GlobalRegions.normalise(countryCode);
        if (code.isEmpty() || verdict == null || ServiceProbe.UNKNOWN.equals(verdict)) return;
        preferences.edit().putString(KEY_VERDICT_PREFIX + code, verdict).apply();
    }

    static String verdictOf(SharedPreferences preferences, String code) {
        return preferences.getString(KEY_VERDICT_PREFIX + GlobalRegions.normalise(code),
                ServiceProbe.UNKNOWN);
    }

    public static void show(Context context, SharedPreferences preferences, String currentCode,
                            OnPicked callback) {
        List<String> codes = GlobalRegions.merge(
                GlobalRegions.decode(preferences.getString("availableRegions", "")));

        BottomSheetDialog sheet = new BottomSheetDialog(context);
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(rounded(BACKGROUND, dp(context, 20)));
        root.setPadding(dp(context, 18), dp(context, 10), dp(context, 18), dp(context, 20));

        root.addView(grabber(context));
        root.addView(title(context, context.getString(R.string.region_picker_title)));
        root.addView(note(context, context.getString(R.string.region_picker_note)));

        // Taking focus on the root first stops the search box claiming it the moment the sheet is
        // laid out.
        root.setFocusableInTouchMode(true);

        LinearLayout list = new LinearLayout(context);
        list.setOrientation(LinearLayout.VERTICAL);

        if (codes.size() >= SEARCH_THRESHOLD) {
            EditText search = new EditText(context);
            search.setHint(R.string.region_picker_search);
            search.setSingleLine(true);
            search.setTextColor(TEXT);
            search.setHintTextColor(TEXT_MUTED);
            search.setTextSize(14f);
            search.setBackground(rounded(SURFACE, dp(context, 12)));
            search.setPadding(dp(context, 14), dp(context, 12), dp(context, 14), dp(context, 12));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            params.topMargin = dp(context, 10);
            search.setLayoutParams(params);
            search.setFocusable(true);
            search.setFocusableInTouchMode(true);
            search.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
                @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
                @Override public void afterTextChanged(Editable s) {
                    render(context, preferences, list, codes, currentCode, s.toString(), callback, sheet);
                }
            });
            root.addView(search);
        }

        // A plain ScrollView does not scroll inside a bottom sheet. The sheet's behaviour only
        // hands a drag to a child that implements NestedScrollingChild, so with a ScrollView every
        // swipe is taken as an attempt to drag the sheet itself and the list never moves.
        NestedScrollView scroller = new NestedScrollView(context);
        scroller.addView(list);
        scroller.setFillViewport(true);
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 420));
        scrollParams.topMargin = dp(context, 8);
        scroller.setLayoutParams(scrollParams);
        root.addView(scroller);

        render(context, preferences, list, codes, currentCode, "", callback, sheet);
        sheet.setContentView(root);

        // Open at full height. Left to itself the sheet opens collapsed, which on a list this long
        // means the first rows are already off the bottom before anyone has touched it.
        // Asked of the dialog rather than dug out of root.getParent(): the parent is only the
        // sheet's own container once the dialog has laid itself out, and reaching for it early is
        // how this turns into a null dereference on some devices and not others.
        BottomSheetBehavior<?> behaviour = sheet.getBehavior();
        behaviour.setState(BottomSheetBehavior.STATE_EXPANDED);
        behaviour.setSkipCollapsed(true);
        sheet.show();
    }

    private static void render(Context context, SharedPreferences preferences, LinearLayout list,
                               List<String> codes, String currentCode, String query,
                               OnPicked callback, BottomSheetDialog sheet) {
        list.removeAllViews();
        String needle = query == null ? "" : query.trim().toLowerCase(Locale.US);
        String current = GlobalRegions.normalise(currentCode);

        if (needle.isEmpty()) {
            list.addView(row(context, "\uD83C\uDF10", context.getString(R.string.picker_automatic),
                    context.getString(R.string.region_automatic_detail), current.isEmpty(), 0,
                    view -> { callback.picked(GlobalRegions.AUTOMATIC); sheet.dismiss(); }));
        }

        for (String code : codes) {
            String name = GlobalRegions.name(code);
            if (!needle.isEmpty()
                    && !name.toLowerCase(Locale.US).contains(needle)
                    && !code.toLowerCase(Locale.US).contains(needle)) {
                continue;
            }
            String verdict = verdictOf(preferences, code);
            String detail;
            int detailColour = 0;
            if (ServiceProbe.OPEN.equals(verdict)) {
                detail = context.getString(R.string.region_verdict_open);
                detailColour = GOOD;
            } else if (ServiceProbe.BLOCKED.equals(verdict)) {
                detail = context.getString(R.string.region_verdict_blocked);
                detailColour = BAD;
            } else if (ServiceProbe.UNREACHABLE.equals(verdict)) {
                detail = context.getString(R.string.region_verdict_unreachable);
                detailColour = TEXT_MUTED;
            } else {
                detail = context.getString(R.string.region_verdict_untried);
            }
            final String picked = code;
            list.addView(row(context, ExitLocation.flag(code), name, detail,
                    code.equals(current), detailColour,
                    view -> { callback.picked(picked); sheet.dismiss(); }));
        }

        if (list.getChildCount() == 0) {
            list.addView(note(context, context.getString(R.string.region_picker_no_match)));
        }
    }

    private static View row(Context context, String glyph, String label, String detail,
                            boolean selected, int detailColour, View.OnClickListener onClick) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(rounded(selected ? SURFACE_SELECTED : SURFACE, dp(context, 12)));
        row.setPadding(dp(context, 14), dp(context, 12), dp(context, 14), dp(context, 12));
        row.setClickable(true);
        row.setOnClickListener(onClick);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(context, 6);
        row.setLayoutParams(params);

        TextView flag = new TextView(context);
        flag.setText(glyph);
        flag.setTextSize(20f);
        row.addView(flag);

        LinearLayout texts = new LinearLayout(context);
        texts.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        textParams.leftMargin = dp(context, 12);
        texts.setLayoutParams(textParams);

        TextView name = new TextView(context);
        name.setText(label);
        name.setTextColor(selected ? ACCENT : TEXT);
        name.setTextSize(15f);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        texts.addView(name);

        if (detail != null && !detail.isEmpty()) {
            TextView sub = new TextView(context);
            sub.setText(detail);
            sub.setTextColor(detailColour == 0 ? TEXT_MUTED : detailColour);
            sub.setTextSize(12f);
            texts.addView(sub);
        }
        row.addView(texts);

        if (selected) {
            TextView tick = new TextView(context);
            tick.setText("\u2713");
            tick.setTextColor(ACCENT);
            tick.setTextSize(17f);
            row.addView(tick);
        }
        return row;
    }

    private static View title(Context context, String text) {
        TextView view = new TextView(context);
        view.setText(text);
        view.setTextColor(TEXT);
        view.setTextSize(17f);
        view.setTypeface(Typeface.DEFAULT_BOLD);
        view.setPadding(0, dp(context, 6), 0, dp(context, 2));
        return view;
    }

    private static View note(Context context, String text) {
        TextView view = new TextView(context);
        view.setText(text);
        view.setTextColor(TEXT_MUTED);
        view.setTextSize(12f);
        view.setPadding(0, dp(context, 2), 0, dp(context, 4));
        return view;
    }

    private static View grabber(Context context) {
        View view = new View(context);
        view.setBackground(rounded(SURFACE_SELECTED, dp(context, 3)));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(context, 36), dp(context, 4));
        params.gravity = Gravity.CENTER_HORIZONTAL;
        params.bottomMargin = dp(context, 8);
        view.setLayoutParams(params);
        return view;
    }

    private static GradientDrawable rounded(int colour, float radius) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(colour);
        shape.setCornerRadius(radius);
        return shape;
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    /** Convenience for callers that only have a result map. */
    public static void remember(SharedPreferences preferences, String countryCode,
                                Map<String, String> results) {
        remember(preferences, countryCode, ServiceProbe.verdict(results));
    }
}
