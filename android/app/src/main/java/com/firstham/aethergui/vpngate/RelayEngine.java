package com.firstham.aethergui.vpngate;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.SharedPreferences.Editor;
import android.os.Handler;
import android.os.Looper;

import com.firstham.aethergui.R;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Drives the relay engine: picks the servers, walks them until one carries traffic, and is the
 * single place the screen reads relay state from.
 *
 * <p>Two things made the relay path unusable before, and both are handled here.
 *
 * <p>First, nothing published its state. The OpenVPN engine reports on its own status bus and the
 * home screen only ever listened to the Aether service's broadcasts, so a relay could start, fail
 * or never launch and the orb kept saying CONNECT. {@link RelayStatus} translates that bus; this
 * class owns the subscription and hands the result to whichever screen is currently attached.
 *
 * <p>Second, one attempt was the whole plan. Relays are volunteer machines that are advertised in
 * a feed hours before they die, so the top entry refusing the handshake is the ordinary case, not
 * the exceptional one. A connect here is a sequence: try the best candidate, and on a refusal, an
 * auth failure or a silence longer than {@link #ATTEMPT_TIMEOUT_MS}, stop it and take the next.
 * Only when the whole list is spent does the user see a failure.
 *
 * <p>It is a process-wide singleton holding the application context rather than an activity-scoped
 * helper, because the sequence has to survive the screen going away - a user who backgrounds the
 * app two seconds into a connect should come back to a tunnel, not to an abandoned attempt.
 */
public final class RelayEngine implements RelayStatus.Listener {

    /** How long one relay gets to bring the tunnel up before the next one is tried. */
    private static final long ATTEMPT_TIMEOUT_MS = 35_000L;

    /**
     * Where the relay currently carrying the connection is remembered.
     *
     * <p>Only so the screen can still name the exit after the app's process has been restarted
     * under a live tunnel. The engine's own status bus replays that the tunnel is up, but not
     * which relay it is, and the relay engine opens no proxy for the location card to ask
     * through - so without this the home screen read "location unavailable" over a working
     * connection until the user reconnected.
     */
    private static final String STATE_PREFS = "relay_state";

    /** Where {@link RelayMemory} keeps the relays that have worked on this phone. */
    private static final String KEY_KNOWN_GOOD = "knownGood";

    /**
     * The gap between tearing down a dead attempt and starting the next.
     *
     * <p>Stopping the engine emits its own "disconnected", which arrives after the call returns.
     * Without a settling window that event lands on the following attempt and knocks it over
     * immediately, so the whole list burns down in under a second.
     */
    private static final long HANDOVER_MS = 900L;

    /** What the screen needs from the relay engine. All calls arrive on the main thread. */
    public interface Observer {
        /** state is one of: connected, starting, reconnecting, error, disconnected. */
        void relayState(String state, String message);

        void relayTraffic(long tx, long rx);

        /** The relay that is actually carrying the connection. */
        void relayServer(VpnGateServer server);
    }

    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();
    private static RelayEngine instance;

    private final Context app;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final RelayStatus status;

    private Observer observer;
    /**
     * The settings this connect runs with, read once when it starts.
     *
     * <p>Read once on purpose: a sequence that walked five relays while the user was editing the
     * settings screen would otherwise apply a different rule to each attempt, and the one that
     * happened to connect would decide what the connection did.
     */
    private RelayOptions options;
    private List<VpnGateServer> candidates = new ArrayList<>();
    private int index;
    private VpnGateServer current;

    /**
     * Counts connects, so work left over from an abandoned one cannot land on its replacement.
     *
     * <p>Two things outlive a connect by design: the directory load, which is on a worker thread,
     * and the pause between tearing a dead relay down and starting the next. Both come back to the
     * main thread later and both used to check only whether <em>a</em> connect was running. Tap
     * disconnect and connect again inside that window - or change the country and reconnect - and
     * the old one would finish into the new one: candidates chosen for the country you just left,
     * or an attempt stepped forward through a list that had been replaced under it.
     */
    private int connectId;

    /** True from the moment a connect is asked for until it succeeds, fails or is stopped. */
    private boolean running;
    private boolean connected;
    /** True while an attempt is being torn down and the next one has not started yet. */
    private boolean settling;

    /**
     * True once a connect has reported failure and until the next one starts.
     *
     * <p>Failing means stopping the engine, and stopping it makes it report a disconnect a moment
     * later. Passing that through replaced the sentence explaining what went wrong with a bare
     * "disconnected", so the one screen that could tell the user why nothing worked showed nothing
     * at all.
     */
    private boolean failed;

    private String lastState = "disconnected";
    private String lastMessage;

    private final Runnable attemptTimeout = new Runnable() {
        @Override public void run() {
            if (!running || connected) return;
            advance();
        }
    };

    private RelayEngine(Context context) {
        this.app = context.getApplicationContext();
        this.current = remembered();
        this.status = new RelayStatus(this.app, this);
        // Registered for the life of the process. Registration replays the engine's last known
        // state, which is how a relay that is already up survives the activity being recreated.
        this.status.register();
    }

    public static synchronized RelayEngine get(Context context) {
        if (instance == null) instance = new RelayEngine(context);
        return instance;
    }

    /** Attaches the screen and replays the current state to it straight away. */
    public void observe(Observer newObserver) {
        this.observer = newObserver;
        if (newObserver == null) return;
        if (current != null && (connected || RelayStatus.live())) newObserver.relayServer(current);
        newObserver.relayState(lastState, lastMessage);
    }

    public void stopObserving(Observer leaving) {
        if (this.observer == leaving) this.observer = null;
    }

    /** True when a relay tunnel is up or coming up. */
    public boolean live() {
        return running || connected || RelayStatus.live();
    }

    /**
     * Starts a connect for the exit country stored in {@code preferences}, or the best relay
     * anywhere when that is Automatic.
     *
     * <p>The directory load runs off the main thread: it parses several thousand rows and may
     * fetch the feed first, and doing that on the main thread was the connect appearing to hang
     * before it had even chosen a server.
     */
    public void start(SharedPreferences preferences) {
        final int id = ++connectId;
        String countryCode = EngineRouter.location(preferences);
        options = RelayOptions.of(
                preferences.getInt("routing", RelayOptions.ROUTING_DEFAULT),
                preferences.getString("splitApps", ""),
                app.getPackageName(),
                preferences.getString("mtu", "1500"),
                preferences.getBoolean("killSwitch", false),
                preferences.getBoolean("dnsLeak", true),
                VpnGateConnector.DNS);
        cancelSequence();
        running = true;
        connected = false;
        settling = false;
        failed = false;
        index = 0;
        current = null;
        // The relay remembered from the last connection is not this one's. Dropping it here means
        // a process that dies mid-sequence comes back naming nothing rather than naming the exit
        // of a tunnel that has already been replaced.
        forget();
        candidates = new ArrayList<>();
        publish("starting", app.getString(R.string.relay_finding));

        // Read on the main thread while the preferences are certainly current, used off it.
        final java.util.Collection<String> proven = knownGood();

        WORKER.execute(() -> {
            VpnGateRepository repository = new VpnGateRepository(app.getFilesDir());
            List<VpnGateServer> found = RelayPlan.candidates(
                    repository.load(false), countryCode, RelayPlan.MAX_ATTEMPTS, proven);
            if (found.isEmpty()) {
                // An empty or stale cache on a first run is the usual cause, so pay for one
                // forced refresh before telling the user there is nothing there.
                found = RelayPlan.candidates(
                        repository.load(true), countryCode, RelayPlan.MAX_ATTEMPTS, proven);
            }
            final List<VpnGateServer> chosen = found;
            main.post(() -> {
                if (!running || connectId != id) return;
                candidates = chosen;
                index = 0;
                if (candidates.isEmpty()) {
                    fail(countryCode == null
                            ? app.getString(R.string.relay_none_available)
                            : app.getString(R.string.relay_no_country));
                    return;
                }
                attemptNext();
            });
        });
    }

    /** Tears the relay down and abandons any sequence in flight. */
    public void stop() {
        connectId++;
        cancelSequence();
        running = false;
        connected = false;
        failed = false;
        current = null;
        forget();
        VpnGateConnector.stop(app);
        publish("disconnected", null);
    }

    private void cancelSequence() {
        main.removeCallbacks(attemptTimeout);
        settling = false;
    }

    /** Starts the next candidate, or reports failure when the list is spent. */
    private void attemptNext() {
        main.removeCallbacks(attemptTimeout);
        settling = false;
        if (!running) return;

        while (index < candidates.size()) {
            VpnGateServer server = candidates.get(index++);
            publish("starting", app.getString(R.string.relay_trying,
                    server.countryName, index, candidates.size()));
            if (VpnGateConnector.connect(app, server, options)) {
                current = server;
                main.postDelayed(attemptTimeout, ATTEMPT_TIMEOUT_MS);
                return;
            }
            // A profile that will not parse is not a failure worth showing: the user asked for a
            // country, not for one particular volunteer's machine. Take the next one.
        }
        fail(app.getString(R.string.relay_exhausted));
    }

    /** Stops the current attempt and moves to the next after the engine has settled. */
    private void advance() {
        if (!running || settling) return;
        settling = true;
        main.removeCallbacks(attemptTimeout);
        current = null;
        VpnGateConnector.stop(app);
        final int id = connectId;
        main.postDelayed(() -> {
            if (connectId != id) return;
            attemptNext();
        }, HANDOVER_MS);
    }

    private void fail(String reason) {
        cancelSequence();
        running = false;
        connected = false;
        current = null;
        forget();
        VpnGateConnector.stop(app);
        failed = true;
        publish("error", reason);
    }

    @Override public void relayState(String state, String message) {
        // Events queued before a dead attempt was torn down would otherwise knock over the one
        // that replaced it.
        if (settling) return;

        if ("connected".equals(state)) {
            main.removeCallbacks(attemptTimeout);
            connected = true;
            running = true;
            failed = false;
            // A process restarted under a live tunnel learns from the engine that the tunnel is
            // up, but not which relay it is. That part only this app knows, so it is read back
            // rather than left blank.
            if (current == null) {
                current = remembered();
            } else {
                remember(current);
                // It carried a connection on this network, which is worth more than anything the
                // feed says about it. Next connect starts here.
                rememberGood(current);
            }
            String name = current == null ? null : current.countryName;
            // The exit is named before the state flips, or the location line would show
            // "unavailable" for a frame on every successful connect.
            if (observer != null && current != null) observer.relayServer(current);
            publish("connected", name == null ? message
                    : app.getString(R.string.relay_connected, name));
            return;
        }

        if (!running) {
            // The disconnect our own failure caused, arriving after the fact. Letting it through
            // would overwrite the reason the connect failed with a state the user can already see.
            if (failed) return;
            // Otherwise this is the engine reporting on its own - a user disconnect, or a tunnel
            // that dropped after the sequence finished. Pass it through.
            connected = false;
            publish(state, message);
            return;
        }

        if ("error".equals(state) || "disconnected".equals(state)) {
            // Mid-sequence this is a dead relay, not a dead connect. The user sees the next
            // attempt rather than a failure that is about to be retried anyway.
            if (connected) {
                // The tunnel we had went away. Rebuilding it silently would be the wrong answer:
                // the exit has changed under the user either way, so say so.
                connected = false;
                running = false;
                publish(state, message);
                return;
            }
            advance();
            return;
        }

        publish(state, message);
    }

    /** The relays this phone has connected through, best remembered first. */
    private java.util.Collection<String> knownGood() {
        try {
            SharedPreferences store = app.getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE);
            return RelayMemory.parse(store.getString(KEY_KNOWN_GOOD, ""),
                    System.currentTimeMillis()).keySet();
        } catch (Throwable ignored) {
            return java.util.Collections.emptyList();
        }
    }

    /** Files a relay that actually carried traffic. Survives {@link #forget()} by design. */
    private void rememberGood(VpnGateServer server) {
        try {
            SharedPreferences store = app.getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE);
            store.edit().putString(KEY_KNOWN_GOOD, RelayMemory.remember(
                    store.getString(KEY_KNOWN_GOOD, ""), server.key(),
                    System.currentTimeMillis())).apply();
        } catch (Throwable ignored) {
            // Learning nothing is a worse connect next time, never a failed one now.
        }
    }

    /** Stores just enough of a relay to name it on screen after a restart. */
    private void remember(VpnGateServer server) {
        try {
            Editor editor = app.getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE).edit();
            editor.putString("host", server.hostName)
                    .putString("ip", server.ip)
                    .putString("countryCode", server.countryCode)
                    .putString("countryName", server.countryName)
                    .apply();
        } catch (Throwable ignored) {
            // Losing the label is never worth losing the connection over.
        }
    }

    /** The relay remembered from before, or null when there is nothing usable stored. */
    private VpnGateServer remembered() {
        try {
            SharedPreferences store = app.getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE);
            String host = store.getString("host", "");
            String code = store.getString("countryCode", "");
            if (host == null || host.isEmpty() || code == null || code.length() != 2) return null;
            return new VpnGateServer(host, store.getString("ip", ""), 0L, 0, 0L,
                    store.getString("countryName", code), code, 0, 0L, "");
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * Drops the "which relay am I on" label.
     *
     * <p>Deliberately not a wipe: {@link #KEY_KNOWN_GOOD} is what the device has learned about
     * this network and has to outlive every disconnect, or the next connect starts from nothing
     * every time.
     */
    private void forget() {
        try {
            app.getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE).edit()
                    .remove("host").remove("ip").remove("countryCode").remove("countryName")
                    .apply();
        } catch (Throwable ignored) { }
    }

    @Override public void relayTraffic(long tx, long rx) {
        if (observer != null) observer.relayTraffic(tx, rx);
    }

    private void publish(String state, String message) {
        boolean changed = !state.equals(lastState);
        lastState = state;
        lastMessage = message;
        if (observer != null) observer.relayState(state, message);
        // The tile reads this engine directly, so it has to be told to look again. Without this it
        // kept whatever it showed when the shade was last opened - which on a relay connect meant
        // an "off" tile sitting above a live tunnel.
        if (changed) {
            try { com.firstham.aethergui.AethonTileService.requestUpdate(app); }
            catch (Throwable ignored) {
                // A tile that will not refresh is never a reason to fail a connection.
            }
        }
    }
}
