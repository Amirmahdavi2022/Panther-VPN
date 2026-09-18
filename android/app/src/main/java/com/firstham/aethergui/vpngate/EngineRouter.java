package com.firstham.aethergui.vpngate;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Decides which engine handles a connection, and makes sure only one ever holds the tunnel.
 *
 * Android allows a single active VpnService. The Aether core and the OpenVPN engine each bring
 * their own, so switching between Relay and any of the other three is a handover, not a parallel
 * start - whichever engine is not wanted gets stopped first, unconditionally, because a stale
 * tunnel from the other engine is exactly the kind of failure that looks like "the app is broken".
 *
 * The connect itself belongs to {@link RelayEngine}; this class only answers which engine is armed
 * and where its exit should be.
 */
public final class EngineRouter {
    public static final String KEY_LOCATION = "exitLocation";
    public static final String KEY_LOCATION_NAME = "exitLocationName";
    public static final String AUTOMATIC = "auto";

    /** The preference the engine selector writes, and the value that means the relay engine. */
    public static final String KEY_ENGINE = "engine";
    public static final String RELAY = "relay";

    private EngineRouter() {
    }

    /** The stored relay exit country, or null when Automatic. */
    public static String location(SharedPreferences preferences) {
        String value = preferences.getString(KEY_LOCATION, AUTOMATIC);
        return AUTOMATIC.equals(value) || value == null || value.isEmpty() ? null : value;
    }

    public static void setLocation(SharedPreferences preferences, String countryCode,
                                   String countryName) {
        preferences.edit()
                .putString(KEY_LOCATION, countryCode == null ? AUTOMATIC : countryCode)
                .putString(KEY_LOCATION_NAME, countryName == null ? "" : countryName)
                .apply();
    }

    /**
     * The display name of the stored country, or null when Automatic.
     *
     * The name is kept alongside the code because the home card should never have to fall back to
     * showing a bare "US" - and looking the name up again would mean parsing the whole directory
     * on the main thread just to draw one line of text.
     */
    public static String locationName(SharedPreferences preferences) {
        String code = location(preferences);
        if (code == null) return null;
        String name = preferences.getString(KEY_LOCATION_NAME, "");
        return name == null || name.isEmpty() ? code : name;
    }

    /** True when the armed engine is the relay engine, whatever its exit country is set to. */
    public static boolean usesRelay(SharedPreferences preferences) {
        return RELAY.equals(preferences.getString(KEY_ENGINE, "turbo"));
    }

    /** Stops whichever engine might be holding the tunnel. Safe to call at any time. */
    public static void stopAll(Context context) {
        RelayEngine.get(context).stop();
    }
}
