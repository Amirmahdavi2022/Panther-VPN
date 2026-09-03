package com.firstham.aethergui;

import android.content.Context;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import libv2ray.CoreCallbackHandler;
import libv2ray.CoreController;
import libv2ray.Libv2ray;

/**
 * The Stealth engine: the third core, alongside Turbo and Global.
 *
 * <p>Where the other two each own a single network, this one owns a <em>list</em>. It takes the
 * pool of endpoints that {@link EndpointPool} has already scored from this device's own history,
 * dials the best one, proves it carries traffic, and moves down the list when it does not. To the
 * rest of the app it still looks exactly like the other two engines: it publishes a SOCKS5 proxy
 * on loopback and the existing TUN bridge is pointed at it.
 *
 * <p>Three decisions are worth knowing before changing anything here.
 *
 * <p><b>The port never moves.</b> {@link XrayConfig#SOCKS_PORT} is fixed, and every endpoint this
 * engine dials publishes on it. That is the whole reason a swap is cheap: the TUN interface is
 * built once and never rebuilt, so apps on the phone do not see the network disappear and come
 * back. The proxy is unavailable for the fraction of a second between one core stopping and the
 * next starting, which costs in-flight connections, but the interface itself stays up.
 *
 * <p><b>Starting is not connecting.</b> The core returns from its start call as soon as it has
 * loaded a config and begun listening. A server that died an hour ago starts just as cleanly as
 * one that works. So every candidate is proved through {@link SocksProbe} before this class calls
 * itself connected, and a candidate that cannot be proved is reported to the pool as a failure and
 * skipped.
 *
 * <p><b>The core cannot dial everything the pool holds.</b> {@link XrayConfig#supports} is the
 * authority on that, and the candidate list is filtered through it, so a hysteria2 entry never
 * costs a connection attempt.
 */
public final class StealthCore {

    /** Mirrors the shape the other engines report in, so the service handles all three alike. */
    public interface Listener {
        void onState(String state, String message);
        void onLog(String line);
        /** The endpoint now carrying traffic, so the UI can show something real rather than a guess. */
        void onEndpoint(ProxyConfig endpoint);
    }

    /** How long one candidate gets to start and prove itself before we move on. */
    static final int CANDIDATE_TIMEOUT_MS = 9_000;

    /** How many endpoints to try before giving up on this run. */
    static final int MAX_ATTEMPTS = 6;

    private final Context host;
    private final EndpointPool pool;
    private final int socksPort;
    private final Listener listener;

    private final AtomicReference<ProxyConfig> current = new AtomicReference<>();
    private final AtomicBoolean connected = new AtomicBoolean();
    private final AtomicBoolean stopped = new AtomicBoolean();
    private final AtomicReference<CoreController> controller = new AtomicReference<>();
    private final List<String> attempted = new ArrayList<>();

    public StealthCore(Context host, EndpointPool pool, Listener listener) {
        this(host, pool, XrayConfig.SOCKS_PORT, listener);
    }

    public StealthCore(Context host, EndpointPool pool, int socksPort, Listener listener) {
        this.host = host;
        this.pool = pool;
        this.socksPort = socksPort;
        this.listener = listener;
    }

    /** The loopback port the engine publishes on. Constant for the life of the connection. */
    public int socksPort() { return socksPort; }

    /** The endpoint currently carrying traffic, or null before one has been proved. */
    public ProxyConfig current() { return current.get(); }

    public boolean isConnected() { return connected.get() && !stopped.get(); }

    /**
     * Brings the engine up on the best endpoint that actually works.
     *
     * @return true once a candidate has started and been proved to carry traffic.
     */
    public boolean start() {
        stopped.set(false);
        attempted.clear();
        prepareEnvironment();
        listener.onState("starting", host.getString(R.string.status_connecting));
        return dialNextCandidate();
    }

    /**
     * Moves to a different endpoint without touching the port.
     *
     * <p>Called when the connection stops carrying traffic. The current endpoint is benched in the
     * pool first, so this run does not come back to it and so the next run starts better informed.
     *
     * @return true if another endpoint is now carrying traffic.
     */
    public boolean swap(String reason) {
        if (stopped.get()) return false;
        ProxyConfig failed = current.get();
        if (failed != null) {
            pool.recordFailure(failed.key(), System.currentTimeMillis());
            listener.onLog("Stealth leaving " + failed + ": " + reason);
        }
        connected.set(false);
        listener.onState("reconnecting", host.getString(R.string.status_connecting));
        return dialNextCandidate();
    }

    /** Whether the tunnel is still carrying traffic right now. */
    public boolean verify() {
        return SocksProbe.isUsable(XrayConfig.SOCKS_LISTEN, socksPort, CANDIDATE_TIMEOUT_MS);
    }

    public void stop() {
        stopped.set(true);
        connected.set(false);
        current.set(null);
        stopCore();
    }

    // --- the dial loop --------------------------------------------------------------------------

    private boolean dialNextCandidate() {
        for (int attempt = 0; attempt < MAX_ATTEMPTS && !stopped.get(); attempt++) {
            ProxyConfig candidate = nextCandidate();
            if (candidate == null) {
                listener.onLog("Stealth has no untried endpoint left in the pool");
                break;
            }
            attempted.add(candidate.key());
            if (dial(candidate)) return true;
        }
        if (!stopped.get()) {
            listener.onState("error", host.getString(R.string.service_engine_stopped));
        }
        return false;
    }

    /**
     * The best-scoring endpoint this engine can dial and has not already tried on this run.
     *
     * <p>Reads from the ranking the pool already holds, so choosing costs no network work at all.
     * That is what makes a swap a decision taken earlier rather than a search started at the worst
     * possible moment.
     */
    private ProxyConfig nextCandidate() {
        for (EndpointPool.Entry entry : pool.ranked(System.currentTimeMillis())) {
            ProxyConfig candidate = entry.config;
            if (entry.score(System.currentTimeMillis()) < 0) continue;   // benched
            if (!XrayConfig.supports(candidate)) continue;
            if (attempted.contains(candidate.key())) continue;
            return candidate;
        }
        return null;
    }

    private boolean dial(ProxyConfig candidate) {
        stopCore();
        try {
            CoreController created = Libv2ray.newCoreController(new Callbacks());
            controller.set(created);
            // 0 means the core must not take a TUN of its own. Ours is already established and
            // bridged into this port; handing the core a descriptor would give us two owners of
            // the same interface.
            created.startLoop(XrayConfig.build(candidate, socksPort, "warning"), 0);
        } catch (Throwable error) {
            listener.onLog("Stealth could not start on " + candidate + ": " + error.getMessage());
            pool.recordFailure(candidate.key(), System.currentTimeMillis());
            stopCore();
            return false;
        }

        // Started is not connected. Prove it before believing it.
        long began = System.currentTimeMillis();
        long latency = SocksProbe.latencyMillis(XrayConfig.SOCKS_LISTEN, socksPort,
                SocksProbe.PROBE_HOST, SocksProbe.PROBE_PORT, CANDIDATE_TIMEOUT_MS);
        if (latency < 0) {
            listener.onLog("Stealth started on " + candidate + " but nothing came back through it");
            pool.recordFailure(candidate.key(), System.currentTimeMillis());
            stopCore();
            return false;
        }

        pool.recordSuccess(candidate.key(), latency, System.currentTimeMillis());
        current.set(candidate);
        connected.set(true);
        listener.onLog("Stealth is up on " + candidate + " in " + (System.currentTimeMillis() - began)
                + "ms, proved in " + latency + "ms");
        listener.onEndpoint(candidate);
        listener.onState("connected", host.getString(R.string.service_connected));
        return true;
    }

    private void stopCore() {
        CoreController running = controller.getAndSet(null);
        if (running == null) return;
        try { running.stopLoop(); }
        catch (Throwable error) { listener.onLog("Stealth did not stop cleanly: " + error); }
    }

    /**
     * Points the core at a writable directory of its own.
     *
     * <p>The config this engine writes never references the geo databases, so nothing here needs
     * them and the build strips them out. This directory only exists because the core insists on
     * having somewhere to look.
     */
    private void prepareEnvironment() {
        File directory = new File(host.getFilesDir(), "stealth-core");
        if (!directory.isDirectory() && !directory.mkdirs()) {
            listener.onLog("Could not create the Stealth engine directory");
        }
        try {
            Libv2ray.initCoreEnv(directory.getAbsolutePath(), "");
        } catch (Throwable error) {
            listener.onLog("Stealth environment setup failed: " + error);
        }
    }

    /** The core talks back through this. It is chatty, so only real events are surfaced. */
    private final class Callbacks implements CoreCallbackHandler {
        @Override public long startup() { return 0L; }

        @Override public long shutdown() { return 0L; }

        @Override public long onEmitStatus(long code, String message) {
            if (message != null && !message.isEmpty()) listener.onLog("Stealth core: " + message);
            return 0L;
        }
    }
}
