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

    private final Context host;
    private final EndpointPool pool;
    private final int socksPort;
    private final Listener listener;

    private final AtomicReference<ProxyConfig> current = new AtomicReference<>();
    private final AtomicReference<Process> process = new AtomicReference<>();
    private final AtomicBoolean connected = new AtomicBoolean();
    private final AtomicBoolean stopped = new AtomicBoolean();
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
        listener.onState("starting", host.getString(R.string.status_connecting));
        return dialNextCandidate();
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
     * <p>Read from the ranking the pool already holds, so choosing costs no network work at all.
     * That is what makes a swap a decision taken earlier rather than a search started at the worst
     * possible moment.
     */
    private ProxyConfig nextCandidate() {
        long now = System.currentTimeMillis();
        for (EndpointPool.Entry entry : pool.ranked(now)) {
            ProxyConfig candidate = entry.config;
            if (entry.score(now) < 0) continue;                       // benched
            if (!XrayConfig.supports(candidate)) continue;
            if (attempted.contains(candidate.key())) continue;
            return candidate;
        }
        return null;
    }

    private boolean dial(ProxyConfig candidate) {
        stopProcess();
        long began = System.currentTimeMillis();
        try {
            File config = writeConfig(candidate);
            startProcess(config);
        } catch (Throwable error) {
            listener.onLog("Stealth could not start on " + candidate + ": " + error.getMessage());
            pool.recordFailure(candidate.key(), System.currentTimeMillis());
            stopProcess();
            return false;
        }

        if (!waitForListener()) {
            listener.onLog("Stealth started on " + candidate + " but never opened its listener");
            pool.recordFailure(candidate.key(), System.currentTimeMillis());
            stopProcess();
            return false;
        }

        // The listener being open proves nothing about the server behind it - see the class note.
        long latency = SocksProbe.latencyMillis(XrayConfig.SOCKS_LISTEN, socksPort,
                CANDIDATE_TIMEOUT_MS);
        if (latency < 0) {
            listener.onLog("Stealth started on " + candidate + " but nothing came back through it");
            pool.recordFailure(candidate.key(), System.currentTimeMillis());
            stopProcess();
            return false;
        }

        pool.recordSuccess(candidate.key(), latency, System.currentTimeMillis());
        current.set(candidate);
        connected.set(true);
        listener.onLog("Stealth is up on " + candidate + " in "
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
        process.set(started);
        drainOutput(started);
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

    private File writeConfig(ProxyConfig candidate) throws Exception {
        File config = new File(directory(), "stealth.json");
        byte[] body = XrayConfig.build(candidate, socksPort, "warning")
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
