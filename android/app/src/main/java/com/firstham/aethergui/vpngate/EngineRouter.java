package com.firstham.aethergui.vpngate;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Decides which engine handles a connection, and makes sure only one ever holds the tunnel.
 *
 * Android allows a single active VpnService. The Aether core and the OpenVPN engine each bring
 * their own, so switching between Automatic and a relay country is a handover, not a parallel
 * start - whichever engine is not wanted gets stopped first, unconditionally, because a stale
 * tunnel from the other engine is exactly the kind of failure that looks like "the app is broken".
 */
public final class EngineRouter {
    public static final String KEY_LOCATION = "exitLocation";
    public static final String KEY_LOCATION_NAME = "exitLocationName";
    public static final String AUTOMATIC = "auto";

    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    public interface RelayCallback {
        void connecting(String countryName);

        void connected(VpnGateServer server);

        void failed(String reason);
    }

    private EngineRouter() {
    }

    /** The stored exit country, or null when Automatic. */
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

    public static boolean usesRelay(SharedPreferences preferences) {
        return location(preferences) != null;
    }

    /**
     * Connects through a relay in the stored country.
     *
     * The directory load and the profile work both happen off the main thread; only the callbacks
     * come back on it. A country with no live relay reports failure rather than silently doing
     * nothing, so the UI can say so.
     */
    public static void connectRelay(Context context, SharedPreferences preferences,
                                    RelayCallback callback) {
        String countryCode = location(preferences);
        if (countryCode == null) {
            MAIN.post(() -> callback.failed("No relay country is selected."));
            return;
        }

        WORKER.execute(() -> {
            VpnGateRepository repository = new VpnGateRepository(context.getFilesDir());
            List<VpnGateServer> servers = repository.load(false);
            List<VpnGateServer> found = VpnGateDirectory.inCountry(servers, countryCode);

            if (found.isEmpty()) {
                // An empty cache on first run is the usual cause, so force one refresh before
                // telling the user the country is unavailable.
                found = VpnGateDirectory.inCountry(repository.load(true), countryCode);
            }
            final List<VpnGateServer> candidates = found;

            if (candidates.isEmpty()) {
                MAIN.post(() -> callback.failed(context.getString(
                        com.firstham.aethergui.R.string.relay_no_country)));
                return;
            }

            String countryName = candidates.get(0).countryName;
            MAIN.post(() -> callback.connecting(countryName));

            // Parsing a relay profile means reading ~15KB of PEM and then writing the profile
            // store to disk. VPN consent was already granted by the caller, so nothing here needs
            // the main thread - and doing it there was stalling the UI at exactly the moment the
            // user expects the orb to move.
            VpnGateServer started = VpnGateConnector.connectBest(context, candidates);
            if (started == null) {
                MAIN.post(() -> callback.failed(context.getString(
                        com.firstham.aethergui.R.string.relay_start_failed, countryName)));
            } else {
                MAIN.post(() -> callback.connected(started));
            }
        });
    }

    /** Stops whichever engine might be holding the tunnel. Safe to call at any time. */
    public static void stopAll(Context context) {
        VpnGateConnector.stop(context);
    }
}
