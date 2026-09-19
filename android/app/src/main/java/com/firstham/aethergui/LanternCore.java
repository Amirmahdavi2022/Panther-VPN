package com.firstham.aethergui;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * The Beacon engine: a third-party circumvention network, run as its own process.
 *
 * <p>It is the only engine here that needs nothing else to be up first. Turbo dials a fixed
 * provider, Global cannot bootstrap from a filtered network without Turbo carrying it, and Prowl
 * needs a pool of endpoints that are alive today. This one reaches its own infrastructure through
 * domain fronting, so a phone that can reach any large CDN can reach it, and it finds its own way
 * out from there.
 *
 * <p>🚨 It ships as a standalone executable rather than the library its authors publish, and that
 * is not a preference. Their Android library and the Global engine are both gomobile builds: each
 * one carries jni/&lt;abi&gt;/libgojni.so and a copy of go/Seq, so an APK can hold only one of
 * them. Measured on the two real files - 31MB against 40MB for the same path - and it is the same
 * wall that ended the attempt to ship the Prowl core as a library. An executable shares no runtime
 * with anything, so there is nothing to collide over.
 *
 * <p>Shipped as {@code liblantern.so} for the same reason the other cores are: since Android 10 an
 * app may not execute a file it wrote into its own data directory, and the installer's native
 * library directory is the one place a shipped binary arrives with the execute bit already set.
 */
final class LanternCore {

    /**
     * The loopback port the engine serves SOCKS5 on.
     *
     * <p>Its own number, clear of Turbo on 1819 and Prowl on 1820, so a process left behind by a
     * previous run cannot be mistaken for this one.
     */
    static final int PORT = 1821;

    /** How long a start is given once the engine has its configuration cached on disk. */
    static final int START_TIMEOUT_MS = 120_000;

    /** How often a start that is still running says where it has got to. */
    static final long PROGRESS_INTERVAL_MS = 30_000L;

    /**
     * How long a start is given when the engine has never fetched its configuration here.
     *
     * <p>A first run has to pull its bootstrap through domain fronting before it has a single
     * route to offer, and on a filtered network that is a different order of task from reusing
     * what is already on disk. The shorter budget above is right for the warm case and was
     * spending the cold one's entire allowance on the fetch.
     */
    static final int COLD_START_TIMEOUT_MS = 210_000;

    interface Listener {
        void onLog(String line);
    }

    private final File binary;
    private final File configDir;
    private final Listener listener;

    private volatile Process process;
    private volatile boolean cancelled;
    private volatile int forwarded;

    LanternCore(Context context, Listener listener) {
        this.binary = new File(context.getApplicationInfo().nativeLibraryDir, "liblantern.so");
        // Its own directory under the app's files, because the engine caches the bootstrap it
        // fetched there. Keeping it across runs is what makes the second connect quicker than
        // the first, and what lets it come up at all on a network where the fetch is blocked.
        this.configDir = new File(context.getFilesDir(), "beacon");
        this.listener = listener;
    }

    /**
     * Whether this engine can be offered at all.
     *
     * <p>Checked before it is armed rather than discovered by a failed connect, so a build that
     * shipped without the binary says so instead of looking like a network problem.
     */
    boolean available() {
        return binary.isFile() && binary.canExecute();
    }

    static String address() {
        return "127.0.0.1:" + PORT;
    }

    int port() {
        return PORT;
    }

    boolean isAlive() {
        Process running = process;
        return running != null && running.isAlive();
    }

    /**
     * Starts the engine and does not report success until it has actually carried a request.
     *
     * @return true once traffic really passes through it
     */
    boolean start(int timeoutMs) {
        stop();
        cancelled = false;
        forwarded = 0;
        if (!available()) {
            log("The Beacon core is missing from this build");
            return false;
        }
        if (!configDir.isDirectory() && !configDir.mkdirs()) {
            log("Beacon could not create its working directory");
            return false;
        }
        boolean cold = configBytes() == 0;
        int budget = cold ? Math.max(timeoutMs, COLD_START_TIMEOUT_MS) : timeoutMs;
        log(cold
                ? "Beacon has no cached configuration; fetching it can take a few minutes"
                : "Beacon config: " + describeConfig());
        List<String> command = new ArrayList<>();
        command.add(binary.getAbsolutePath());
        command.add("-socks");
        command.add(address());
        // A port of its own choosing for the HTTP listener: the engine insists on having one and
        // nothing here uses it, so it gets an ephemeral port rather than a number worth guarding.
        command.add("-http");
        command.add("127.0.0.1:0");
        command.add("-configdir");
        command.add(configDir.getAbsolutePath());
        try {
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.redirectErrorStream(true);
            process = builder.start();
        } catch (Throwable error) {
            process = null;
            log("Beacon could not start: " + error);
            return false;
        }
        drain(process);
        return waitUntilUsable(budget);
    }

    /** Total bytes the engine has cached. Zero means it has never completed a fetch here. */
    private long configBytes() {
        File[] files = configDir.listFiles();
        if (files == null) return 0L;
        long total = 0L;
        for (File file : files) if (file.isFile()) total += file.length();
        return total;
    }

    /**
     * The cache, in one line.
     *
     * <p>Worth logging rather than inferring, because it splits the two failures that look
     * identical from outside: nothing here means the engine never reached its own bootstrap, and
     * a populated directory with no working route means it did and the routes are dead. Guessing
     * between those two cost a whole test cycle once already.
     */
    private String describeConfig() {
        File[] files = configDir.listFiles();
        if (files == null || files.length == 0) return "empty";
        int count = 0;
        long total = 0L;
        for (File file : files) {
            if (!file.isFile()) continue;
            count++;
            total += file.length();
        }
        return count == 0 ? "empty" : count + " files, " + total + " bytes";
    }

    /**
     * 🚨 The port opening is NOT readiness, and treating it as such is the mistake that made this
     * engine look dead the first time it was measured. The listener binds within a second, long
     * before the engine has fetched its configuration and has anywhere to send traffic; asked too
     * early it answers the handshake locally and then resets every connection. So the port is only
     * the first gate, and a request that comes back is the second.
     */
    private boolean waitUntilUsable(int timeoutMs) {
        long started = System.currentTimeMillis();
        long deadline = started + Math.max(1, timeoutMs);
        long nextReport = started + PROGRESS_INTERVAL_MS;
        boolean listening = false;
        while (!cancelled && System.currentTimeMillis() < deadline) {
            // A silent wait and a frozen app are the same picture. Saying what the engine has
            // fetched so far turns a two-minute blank into something a person can read, and
            // leaves the answer in the log for whoever asks afterwards.
            if (System.currentTimeMillis() >= nextReport) {
                nextReport += PROGRESS_INTERVAL_MS;
                log("Beacon still starting after " + ((System.currentTimeMillis() - started) / 1000)
                        + "s; config " + describeConfig());
            }
            Process running = process;
            if (running == null) return false;
            if (!running.isAlive()) {
                log("Beacon stopped before it was ready");
                return false;
            }
            if (!listening) {
                if (SocksProbe.opens("127.0.0.1", PORT, 400)) {
                    listening = true;
                    log("Beacon is listening on " + address() + "; waiting for it to find a route");
                }
            } else if (SocksProbe.carriesTraffic("127.0.0.1", PORT, 6_000)) {
                return true;
            }
            if (!sleep(listening ? 1_500L : 400L)) return false;
        }
        if (!cancelled) {
            log(listening
                    ? "Beacon was listening but never carried a request; config " + describeConfig()
                    : "Beacon never opened its SOCKS5 listener; config " + describeConfig());
        }
        stop();
        return false;
    }

    /** Tells a start in flight to give up without waiting for its own timeout. */
    void cancel() {
        cancelled = true;
    }

    void stop() {
        cancelled = true;
        Process running = process;
        process = null;
        if (running == null) return;
        running.destroy();
        try {
            if (!running.waitFor(2, TimeUnit.SECONDS)) running.destroyForcibly();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Reads the engine's output so its pipe cannot fill and stall it, and keeps almost none of it.
     *
     * <p>It logs every dial it makes at debug level - hundreds of lines a minute - and the app's
     * own log is a small buffer meant to be read by a person. Only failures are kept, and only a
     * handful of those, which is enough to tell a broken engine from a blocked network.
     */
    private void drain(final Process running) {
        Thread reader = new Thread(() -> {
            try (BufferedReader lines = new BufferedReader(
                    new InputStreamReader(running.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = lines.readLine()) != null) {
                    String upper = line.toUpperCase(Locale.US);
                    // The core announces the address it is serving on, and says so without the
                    // word this filter was built around - so the one line worth keeping was the
                    // one being dropped.
                    if (upper.contains("SOCKS5 LISTENING ON")) {
                        log("Beacon: " + line.trim());
                        continue;
                    }
                    if (upper.contains("ERROR") || upper.contains("FATAL")) {
                        if (forwarded < 8) {
                            forwarded++;
                            log("Beacon: " + line.trim());
                        }
                    }
                }
            } catch (Throwable ignored) {
                // The pipe closing when the process ends is the ordinary way out of this loop.
            }
        }, "beacon-output");
        reader.setDaemon(true);
        reader.start();
    }

    private boolean sleep(long millis) {
        try {
            Thread.sleep(millis);
            return true;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private void log(String line) {
        Listener sink = listener;
        if (sink != null) sink.onLog(line);
    }
}
