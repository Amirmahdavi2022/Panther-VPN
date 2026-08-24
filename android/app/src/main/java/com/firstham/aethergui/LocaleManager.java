package com.firstham.aethergui;

import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;

import java.util.Locale;

/**
 * Applies the language the user picked in Settings, independently of the device locale.
 *
 * Every Context that renders user-visible text (activities, the VPN service, the tile service)
 * routes its base context through {@link #wrap(Context)} in attachBaseContext, so the chosen
 * language is resolved at resource-lookup time on every API level. This deliberately does not
 * use AppCompatDelegate.setApplicationLocales, which is a no-op on this project's setup.
 */
public final class LocaleManager {
    public static final String PREFS = "aether";
    public static final String KEY = "language";
    public static final String ENGLISH = "en";
    public static final String PERSIAN = "fa";

    private LocaleManager() {
    }

    /** The language in use: the stored choice, or the device language on first run. */
    public static String stored(Context context) {
        String value = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null);
        if (value == null) return deviceLanguage();
        return normalize(value);
    }

    public static void store(Context context, String language) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY, normalize(language))
                .apply();
    }

    public static Context wrap(Context base) {
        return base == null ? null : wrap(base, stored(base));
    }

    public static Context wrap(Context base, String language) {
        Locale locale = new Locale(normalize(language));
        Locale.setDefault(locale);
        Configuration configuration = new Configuration(base.getResources().getConfiguration());
        configuration.setLocale(locale);
        configuration.setLayoutDirection(locale);
        return base.createConfigurationContext(configuration);
    }

    /** Dropdown index: 0 = English, 1 = Persian. */
    public static int index(String language) {
        return PERSIAN.equals(normalize(language)) ? 1 : 0;
    }

    public static String fromIndex(int index) {
        return index == 1 ? PERSIAN : ENGLISH;
    }

    private static String normalize(String language) {
        return PERSIAN.equals(language) ? PERSIAN : ENGLISH;
    }

    private static String deviceLanguage() {
        Locale locale = Resources.getSystem().getConfiguration().getLocales().get(0);
        return locale != null && PERSIAN.equals(locale.getLanguage()) ? PERSIAN : ENGLISH;
    }
}
