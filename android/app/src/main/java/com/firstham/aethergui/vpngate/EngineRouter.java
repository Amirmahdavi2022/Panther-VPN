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

    public static void setLocation(SharedPreferences preferences, String countryCode) {
        preferences.edit()
                .putString(KEY_LOCATION, countryCode == null ? AUTOMATIC : countryCode)
                .apply();
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
            List<VpnGateServer> candidates = VpnGateDirectory.inCountry(servers, countryCode);

            if (candidates.isEmpty()) {
                // An empty cache on first run is the usual cause, so force one refresh before
                // telling the user the country is unavailable.
                servers = repository.load(true);
                candidates = VpnGateDirectory.inCountry(servers, countryCode);
            }

            if (candidates.isEmpty()) {
                MAIN.post(() -> callback.failed("No relays are available in that country right now."));
                return;
            }

            String countryName = candidates.get(0).countryName;
            MAIN.post(() -> callback.connecting(countryName));

            List<VpnGateServer> attempt = candidates;
            MAIN.post(() -> {
                // The engine must be started from the main thread; it shows the system VPN dialog.
                VpnGateServer started = VpnGateConnector.connectBest(context, attempt);
                if (started == null) {
                    callback.failed("Could not start any relay in " + countryName + ".");
                } else {
                    callback.connected(started);
                }
            });
        });
    }

    /** Stops whichever engine might be holding the tunnel. Safe to call at any time. */
    public static void stopAll(Context context) {
        VpnGateConnector.stop(context);
    }
}
