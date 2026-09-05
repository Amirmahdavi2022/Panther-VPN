package com.firstham.aethergui;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The Stealth engine: the third core, alongside Turbo and Global.
 *
 * <p>Where the other two each own a single network, this one owns a <em>list</em>. It takes the
 * pool of endpoints that {@link EndpointPool} has already scored from this device's own history,
 * dials the best one, proves it carries traffic, and moves down the list when it does not. To the
 * rest of the app it looks like the other two: a SOCKS5 proxy on loopback that the existing TUN
 * bridge points at.
 *
 * <p>Four decisions are worth knowing before changing anything here.
 *
 * <p><b>The core runs as a separate process, not as a library.</b> The obvious route was the
 * published Android library, and it cannot be used: it and the Global engine's library are both
 * gomobile builds carrying the same {@code go.Seq} classes and the same {@code libgojni.so}, so an
 * APK can only ever load one of them. Running the core as an executable, the way the Turbo core
 * already does, gives the two engines nothing to collide over.
 *
 * <p><b>The port never moves.</b> {@link XrayConfig#SOCKS_PORT} is fixed, and every endpoint this
 * engine dials publishes on it. That is what makes a swap cheap: the TUN interface is built once
 * and never rebuilt, so apps on the phone do not see the network disappear and come back. The
 * proxy is unavailable for the moment between one core stopping and the next starting, which costs
 * in-flight connections, but the interface itself stays up.
 *
 * <p><b>Started is not connected.</b> 🚨 The core answers the SOCKS handshake itself and dials the
 * real server lazily, so a black-holed endpoint still completes a handshake in about a
 * millisecond. Every candidate is therefore proved with {@link SocksProbe#carriesTraffic}, which
 * requires a real reply to a real request, before this class calls itself connected.
 *
 * <p><b>The core cannot dial everything the pool holds.</b> {@link XrayConfig#supports} is the
 * authority, and candidates are filtered through it, so a hysteria2 entry never costs an attempt.
 */
public final class StealthCore {

    /** Mirrors the shape the other engines report in, so the service handles all three alike. */
    public interface Listener {
        void onState(String state, String message);
        void onLog(String line);
        /** The endpoint now carrying traffic, so the UI shows something real rather than a guess. */
        void onEndpoint(ProxyConfig endpoint);
    }

    /** The executable, staged into the native library directory like the Turbo core. */
    static final String EXECUTABLE = "libxray.so";

    /** How long one candidate gets to start and prove itself before we move on. */
    static final int CANDIDATE_TIMEOUT_MS = 9_000;

    /** How long to wait for the core to open its listener before probing it. */
    static final int LISTENER_TIMEOUT_MS = 4_000;

    /** How many endpoints to try before giving up on this run. */
    static final int MAX_ATTEMPTS = 6;

    /**
     * How long the dial loop may keep trying before it gives up and lets the carrier hold the
     * connection instead.
     *
     * <p>Checked between candidates rather than inside one, so an endpoint that is nearly through
     * is never cut off half way. The number is a judgement about attention, not about networks: a
     * user who has been staring at a connecting screen for this long is better served by a working
     * Turbo tunnel and an honest line about it than by another endpoint that probably will not
     * answer either.
     */
    static final long DIAL_BUDGET_MS = 55_000L;

    /**
     * The budget for a second pass over a freshly refreshed pool.
     *
     * <p>Shorter than the first on purpose. By the time this runs the user has already waited out
     * a carrier, a full dial pass and a refresh; handing the loop another full budget would make
     * the worst case longer than it was before any of this was bounded at all.
     */
    static final long RETRY_BUDGET_MS = 30_000L;

    private final Context host;
    private final EndpointPool pool;
    private final int socksPort;
    private final Listener listener;
    /** A {@code host:port} SOCKS proxy to dial endpoints through, or null to only dial direct. */
    private final String carrier;

    /** Which way worked last time on this device, tried first so the cost of learning is paid once. */
    private final boolean preferChained;

    /** Whether this run has established which way reaches an endpoint from this network. */
    private final AtomicBoolean modeProven = new AtomicBoolean();

    /** The way that worked, once {@link #modeProven} is set. */
    private final AtomicBoolean chained = new AtomicBoolean();

    private final AtomicReference<ProxyConfig> current = new AtomicReference<>();
    private final AtomicReference<Process> process = new AtomicReference<>();
    private final AtomicBoolean connected = new AtomicBoolean();
    private final AtomicBoolean stopped = new AtomicBoolean();
    private final List<String> attempted = new ArrayList<>();

    /**
     * The exit country the user asked for, or empty for Automatic.
     *
     * <p>Mutable because it can change while the tunnel is up: the picker writes a preference and
     * the monitor hands it here without the connection being torn down.
     */
    private final AtomicReference<String> country = new AtomicReference<>(StealthRegions.AUTOMATIC);

    public StealthCore(Context host, EndpointPool pool, Listener listener) {
        this(host, pool, XrayConfig.SOCKS_PORT, null, false, listener);
    }

    public StealthCore(Context host, EndpointPool pool, int socksPort, String carrier,
                       boolean preferChained, Listener listener) {
        this.host = host;
        this.pool = pool;
        this.socksPort = socksPort;
        this.carrier = XrayConfig.carrierHop(carrier) == null ? null : carrier.trim();
        this.preferChained = preferChained;
        this.listener = listener;
    }

    /** The loopback port the engine publishes on. Constant for the life of the connection. */
    public int socksPort() { return socksPort; }

    /** The exit country being preferred right now, or empty for Automatic. */
    public String country() { return country.get(); }

    /** Sets the preferred exit country for the next candidate chosen. Does not re-dial by itself. */
    public void prefer(String code) { country.set(StealthRegions.normalise(code)); }

    /** The endpoint currently carrying traffic, or null before one has been proved. */
    public ProxyConfig current() { return current.get(); }

    /** Whether the live endpoint is being dialled through the carrier rather than directly. */
    public boolean isChained() { return modeProven.get() && chained.get(); }

    public boolean isConnected() { return connected.get() && !stopped.get(); }

    /**
     * Brings the engine up on the best endpoint that actually works.
     *
     * @return true once a candidate has started and been proved to carry traffic.
     */
    public boolean start() { return start(DIAL_BUDGET_MS); }

    /** The same, with an explicit time budget for the dial loop. */
    public boolean start(long budgetMs) {
        stopped.set(false);
        attempted.clear();
        listener.onState("starting", host.getString(R.string.status_connecting));
        return dialNextCandidate(budgetMs);
    }

    /**
     * Moves to a different endpoint without touching the port.
     *
     * <p>The current endpoint is benched in the pool first, so this run does not come back to it
     * and the next run starts better informed.
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

    /**
     * Moves the tunnel to a different exit country while it is up, without the user seeing a drop.
     *
     * <p>The hard part is that the SOCKS port is fixed and only one core can hold it, so the new
     * endpoint cannot simply be started alongside the old one and swapped in. Instead it is proved
     * first on a staging port, next to the live tunnel and without touching it: a second core is
     * started, made to carry a real request, and killed again. Only an endpoint that has actually
     * answered is worth interrupting a working connection for.
     *
     * <p>What the user experiences is therefore either nothing at all — the chosen country had
     * nothing that answers, and the tunnel they are on is left exactly as it was — or a stall of a
     * second or two while the proved endpoint takes the port. The TUN interface is never rebuilt
     * and the VPN never reports itself down, so no app sees a disconnect, and the kill switch has
     * nothing to trip on.
     *
     * @return true when the tunnel is now exiting through the requested country.
     */
    public boolean retarget(String code) {
        String wanted = StealthRegions.normalise(code);
        String previous = country.getAndSet(wanted);
        if (wanted.equals(previous)) return false;
        outsideWantedCountry = false;
        if (stopped.get() || !connected.get()) return false;

        ProxyConfig live = current.get();
        if (live != null && StealthRegions.matches(live, wanted)) return true;
        if (StealthRegions.isAutomatic(wanted)) {
            // Back to Automatic: the endpoint we are on is still the best-scoring one there is,
            // so there is nothing worth interrupting a working tunnel for.
            listener.onLog("Stealth is back on automatic; keeping the endpoint already carrying traffic");
            return true;
        }

        ProxyConfig target = bestIn(wanted);
        if (target == null) {
            listener.onLog("Stealth has no endpoint in " + StealthRegions.name(wanted)
                    + " to move to; staying where it is");
            return false;
        }

        listener.onLog("Stealth is proving " + target + " in " + StealthRegions.name(wanted)
                + " before moving to it");
        if (!provesOffline(target)) {
            pool.recordFailure(target.key(), System.currentTimeMillis());
            listener.onLog("Stealth could not reach " + target + "; the tunnel stays where it is");
            return false;
        }

        attempted.clear();
        attempted.add(target.key());
        if (dial(target)) {
            listener.onLog("Stealth moved to " + StealthRegions.name(wanted));
            return true;
        }
        // The proved endpoint would not take the live port. The old one is gone by now, so the
        // ordinary dial loop takes over rather than leaving the tunnel with nothing behind it.
        listener.onLog("Stealth lost the port moving to " + StealthRegions.name(wanted)
                + "; reconnecting on whatever answers");
        return dialNextCandidate();
    }

    /** The best-scoring endpoint in a country that is not already the live one. */
    private ProxyConfig bestIn(String wanted) {
        long now = System.currentTimeMillis();
        ProxyConfig live = current.get();
        for (EndpointPool.Entry entry : pool.rankedFor(wanted, now)) {
            ProxyConfig candidate = entry.config;
            if (!StealthRegions.matches(candidate, wanted)) break;   // past the wanted country
            if (entry.score(now) < 0) continue;
            if (!XrayConfig.supports(candidate)) continue;
            if (live != null && live.key().equals(candidate.key())) continue;
            return candidate;
        }
        return null;
    }

    /**
     * Starts a second core on a staging port, makes it carry a real request and kills it again.
     *
     * <p>Runs beside the live tunnel and never touches {@link #process}, so a failure here costs
     * the user nothing. The route is the one this network has already been shown to allow.
     */
    private boolean provesOffline(ProxyConfig candidate) {
        int stagingPort = socksPort + 1;
        String hop = isChained() ? carrier : null;
        Process staged = null;
        try {
            staged = launch(writeConfig(candidate, hop, stagingPort, "stealth-staging.json"));
            long deadline = System.currentTimeMillis() + LISTENER_TIMEOUT_MS;
            boolean listening = false;
            while (System.currentTimeMillis() < deadline && !stopped.get()) {
                if (SocksProbe.reaches(XrayConfig.SOCKS_LISTEN, stagingPort,
                        SocksProbe.PROBE_HOST, SocksProbe.PROBE_PORT, 800)) {
                    listening = true;
                    break;
                }
                Thread.sleep(150);
            }
            if (!listening) return false;
            return SocksProbe.latencyMillis(XrayConfig.SOCKS_LISTEN, stagingPort,
                    CANDIDATE_TIMEOUT_MS) >= 0;
        } catch (Throwable error) {
            listener.onLog("Stealth could not stage " + candidate + ": " + error);
            return false;
        } finally {
            if (staged != null) {
                staged.destroy();
                try {
                    for (int i = 0; i < 20 && staged.isAlive(); i++) Thread.sleep(50);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
                if (staged.isAlive()) staged.destroyForcibly();
            }
        }
    }

    /** Whether the tunnel is still carrying traffic right now. */
    public boolean verify() {
        return SocksProbe.carriesTraffic(XrayConfig.SOCKS_LISTEN, socksPort, CANDIDATE_TIMEOUT_MS);
    }

    public void stop() {
        stopped.set(true);
        connected.set(false);
        current.set(null);
        stopProcess();
    }

    // --- the dial loop --------------------------------------------------------------------------

    private boolean dialNextCandidate() { return dialNextCandidate(DIAL_BUDGET_MS); }

    private boolean dialNextCandidate(long budgetMs) {
        // 🔑 An attempt count on its own is not a time budget. Each attempt can cost a listener
        // wait plus a real-traffic probe, and on a network that black-holes everything it can cost
        // that twice - once direct, once through the carrier. Six of those in a row is minutes of
        // the user watching a spinner. The clock decides when to stop, and the attempt count only
        // caps how many are tried inside it.
        long deadline = System.currentTimeMillis() + budgetMs;
        for (int attempt = 0; attempt < MAX_ATTEMPTS && !stopped.get(); attempt++) {
            if (attempt > 0 && System.currentTimeMillis() > deadline) {
                listener.onLog("Stealth stopped dialling after " + attempt
                        + " endpoints; none of them held within the time budget");
                break;
            }
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
     * <p>Read from the ranking the pool already holds, so choosing costs no network work at all.
     * That is what makes a swap a decision taken earlier rather than a search started at the worst
     * possible moment.
     */
    private ProxyConfig nextCandidate() {
        long now = System.currentTimeMillis();
        String wanted = country.get();
        for (EndpointPool.Entry entry : pool.rankedFor(wanted, now)) {
            ProxyConfig candidate = entry.config;
            if (entry.score(now) < 0) continue;                       // benched
            if (!XrayConfig.supports(candidate)) continue;
            if (attempted.contains(candidate.key())) continue;
            if (!StealthRegions.isAutomatic(wanted)
                    && !StealthRegions.matches(candidate, wanted)
                    && !outsideWantedCountry) {
                // Said once per run, not once per candidate: the user chose a country and is
                // about to be given a different one, and the log is where that is explained.
                outsideWantedCountry = true;
                listener.onLog("Stealth has nothing left in " + StealthRegions.name(wanted)
                        + " that answers; staying connected on another country instead");
            }
            return candidate;
        }
        return null;
    }

    /** Set once a run has had to leave the chosen country, so the log says it only once. */
    private volatile boolean outsideWantedCountry;

    /**
     * Tries one endpoint every way this network allows, and only calls it dead when all of them
     * have failed.
     *
     * <p>🚨 A direct failure is not evidence about the endpoint. On a filtered network it is the
     * expected outcome for a healthy server, so writing it into the pool would bench the good
     * endpoints one connect at a time until nothing worth dialling was left. The failure is
     * recorded once, after the last mode, and describes the endpoint rather than the network.
     */
    private boolean dial(ProxyConfig candidate) {
        long began = System.currentTimeMillis();
        boolean[] modes = StealthPlan.dialModes(carrier != null, preferChained,
                modeProven.get(), chained.get());
        for (boolean viaCarrier : modes) {
            if (stopped.get()) return false;
            if (dialOnce(candidate, viaCarrier, began)) return true;
        }
        pool.recordFailure(candidate.key(), System.currentTimeMillis());
        return false;
    }

    /** One endpoint, one way of reaching it. Leaves nothing running when it returns false. */
    private boolean dialOnce(ProxyConfig candidate, boolean viaCarrier, long began) {
        String route = viaCarrier ? " through the carrier" : " directly";
        stopProcess();
        try {
            File config = writeConfig(candidate, viaCarrier ? carrier : null);
            startProcess(config);
        } catch (Throwable error) {
            listener.onLog("Stealth could not start on " + candidate + route + ": "
                    + error.getMessage());
            stopProcess();
            return false;
        }

        if (!waitForListener()) {
            listener.onLog("Stealth started on " + candidate + route
                    + " but never opened its listener");
            stopProcess();
            return false;
        }

        // The listener being open proves nothing about the server behind it - see the class note.
        long latency = SocksProbe.latencyMillis(XrayConfig.SOCKS_LISTEN, socksPort,
                CANDIDATE_TIMEOUT_MS);
        if (latency < 0) {
            listener.onLog("Stealth started on " + candidate + route
                    + " but nothing came back through it");
            stopProcess();
            return false;
        }

        pool.recordSuccess(candidate.key(), latency, System.currentTimeMillis());
        current.set(candidate);
        connected.set(true);
        if (!modeProven.get()) {
            modeProven.set(true);
            chained.set(viaCarrier);
            listener.onLog(viaCarrier
                    ? "Stealth reaches its endpoints through the carrier on this network"
                    : "Stealth reaches its endpoints directly on this network");
        }
        listener.onLog("Stealth is up on " + candidate + route + " in "
                + (System.currentTimeMillis() - began) + "ms, proved in " + latency + "ms");
        listener.onEndpoint(candidate);
        listener.onState("connected", host.getString(R.string.service_connected));
        return true;
    }

    /** Waits for something to accept on the port, so the probe is not raced against startup. */
    private boolean waitForListener() {
        long deadline = System.currentTimeMillis() + LISTENER_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline && !stopped.get()) {
            if (!isProcessAlive()) return false;
            if (SocksProbe.reaches(XrayConfig.SOCKS_LISTEN, socksPort,
                    SocksProbe.PROBE_HOST, SocksProbe.PROBE_PORT, 800)) {
                return true;
            }
            try { Thread.sleep(150); } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    // --- the process ----------------------------------------------------------------------------

    private void startProcess(File config) throws Exception {
        process.set(launch(config));
    }

    private Process launch(File config) throws Exception {
        File executable = new File(host.getApplicationInfo().nativeLibraryDir, EXECUTABLE);
        if (!executable.isFile()) {
            throw new IllegalStateException("The Stealth engine is missing for this device architecture");
        }
        ProcessBuilder builder = new ProcessBuilder(
                executable.getAbsolutePath(), "run", "-c", config.getAbsolutePath());
        builder.directory(directory());
        builder.redirectErrorStream(true);
        Map<String, String> environment = builder.environment();
        // The core looks for the geo databases through these. Nothing we write references a
        // geoip: or geosite: rule, so they are never opened, but pointing them somewhere writable
        // keeps the core from complaining about a read-only path.
        environment.put("XRAY_LOCATION_ASSET", directory().getAbsolutePath());
        environment.put("XRAY_LOCATION_CONFIG", directory().getAbsolutePath());
        environment.put("TMPDIR", host.getCacheDir().getAbsolutePath());

        Process started = builder.start();
        drainOutput(started);
        return started;
    }

    /**
     * Reads the core's output on a background thread.
     *
     * <p>Not for the logging: a process whose output nobody reads fills its pipe buffer and then
     * blocks forever, which would look exactly like a hung engine.
     */
    private void drainOutput(final Process started) {
        Thread reader = new Thread(() -> {
            try (BufferedReader lines = new BufferedReader(
                    new InputStreamReader(started.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = lines.readLine()) != null) {
                    if (!line.isEmpty()) listener.onLog("Stealth core: " + line);
                }
            } catch (Exception ignored) {
                // The stream closes when the process ends; that is not worth reporting.
            }
        });
        reader.setDaemon(true);
        reader.start();
    }

    private boolean isProcessAlive() {
        Process running = process.get();
        if (running == null) return false;
        try { running.exitValue(); return false; }
        catch (IllegalThreadStateException stillRunning) { return true; }
    }

    private void stopProcess() {
        Process running = process.getAndSet(null);
        if (running == null) return;
        try {
            running.destroy();
            // Give it a moment to release the port, or the next candidate cannot bind it.
            for (int i = 0; i < 20; i++) {
                try { running.exitValue(); return; }
                catch (IllegalThreadStateException stillRunning) { Thread.sleep(50); }
            }
            running.destroyForcibly();
        } catch (Exception error) {
            listener.onLog("Stealth did not stop cleanly: " + error);
        }
    }

    private File writeConfig(ProxyConfig candidate, String hop) throws Exception {
        return writeConfig(candidate, hop, socksPort, "stealth.json");
    }

    private File writeConfig(ProxyConfig candidate, String hop, int port, String name)
            throws Exception {
        File config = new File(directory(), name);
        byte[] body = XrayConfig.build(candidate, port, "warning", hop)
                .getBytes(StandardCharsets.UTF_8);
        try (FileOutputStream out = new FileOutputStream(config)) {
            out.write(body);
        }
        return config;
    }

    /** Where the engine keeps its config. Kept apart from the other cores' files. */
    private File directory() {
        File directory = new File(host.getFilesDir(), "stealth-core");
        if (!directory.isDirectory() && !directory.mkdirs()) {
            listener.onLog("Could not create the Stealth engine directory");
        }
        return directory;
    }
}
