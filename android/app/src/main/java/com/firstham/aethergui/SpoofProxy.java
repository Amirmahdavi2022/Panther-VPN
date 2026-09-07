package com.firstham.aethergui;

import android.content.Context;

import java.io.File;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;

/**
 * The local proxy the spoof dial mode dials through.
 *
 * <p>It listens on loopback and does one thing: tears up the opening packets of each connection it
 * forwards, so equipment that reads the first packet to decide whether to allow a connection has
 * nothing whole to read. It changes nothing else - same protocol, same certificate, same server -
 * so an endpoint that would have worked still works.
 *
 * <p>Why this is a separate process rather than a setting on the engine. The engine can already
 * fragment its own TLS record, and that is used on the direct route, but it can only do what its
 * own core implements. This one is a purpose-built tool with strategies the core has no equivalent
 * for: sending a decoy the inspector sees and the server never does, or reordering the pieces so
 * that reassembly in arrival order produces nonsense. Those are the ones worth having when plain
 * fragmentation is no longer enough, which on some networks it no longer is.
 *
 * <p>Shipped as {@code libbyedpi.so} in the installer's native library directory even though it is
 * a program, not a library. Since Android 10 an app may not execute a file it wrote into its own
 * data directory, and that directory is the one place a shipped binary can live with the execute
 * bit already set. The name is what the installer looks for; nothing else about it is a library.
 */
final class SpoofProxy {

    /**
     * Loopback port. Deliberately not one of the ports the engines use, so a stale process from a
     * previous run cannot be mistaken for this one.
     */
    static final int PORT = 18443;

    /**
     * How the opening packets are shaped.
     *
     * <p>One strategy rather than a menu, chosen because it is the one that both defeats the
     * common case and cannot be defeated by reassembly. Splitting alone falls to equipment that
     * reassembles before matching, which is now ordinary. Sending the pieces out of order means
     * that reassembling in arrival order produces something that does not parse, while the server's
     * own stack - which reorders by sequence number, as TCP requires - sees the correct hello.
     *
     * <p>🚨 Out-of-band strategies are deliberately absent. Measured while building the standalone
     * prober: both of them reset the connection to a destination that was never filtered at all.
     * A strategy that breaks ordinary traffic is worse than no strategy, because every endpoint it
     * touches looks dead and gets written into the pool as a failure.
     */
    private static final String[] SHAPING = { "--disorder", "1" };

    private final File binary;
    private Process process;

    SpoofProxy(Context context) {
        this.binary = new File(context.getApplicationInfo().nativeLibraryDir, "libbyedpi.so");
    }

    /**
     * Whether this mode can be offered at all.
     *
     * <p>Checked before the mode is put in the dial order rather than discovered by a failed dial.
     * A dial that fails because a binary is missing would be recorded as evidence about the
     * endpoint, and the pool would slowly fill with healthy servers marked dead.
     */
    boolean available() {
        return binary.isFile() && binary.canExecute();
    }

    /** The address to dial through, in the form the engine's config builder expects. */
    static String address() {
        return "127.0.0.1:" + PORT;
    }

    /**
     * Starts it and waits until it is actually accepting connections.
     *
     * @return true once the port answers
     */
    boolean start() {
        stop();
        if (!available()) return false;
        List<String> command = new ArrayList<>();
        command.add(binary.getAbsolutePath());
        command.add("-i");
        command.add("127.0.0.1");
        command.add("-p");
        command.add(String.valueOf(PORT));
        for (String argument : SHAPING) command.add(argument);
        try {
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.redirectErrorStream(true);
            process = builder.start();
        } catch (Throwable error) {
            process = null;
            return false;
        }
        // Waiting for the port rather than sleeping a fixed interval: a fixed sleep is either
        // longer than it needs to be on every connect, or too short on a loaded phone, and the
        // second failure looks exactly like an endpoint that did not answer.
        return waitForPort(2500);
    }

    private boolean waitForPort(int waitMs) {
        long deadline = System.currentTimeMillis() + waitMs;
        while (System.currentTimeMillis() < deadline) {
            Process running = process;
            if (running == null) return false;
            Socket probe = new Socket();
            try {
                probe.connect(new InetSocketAddress("127.0.0.1", PORT), 200);
                return true;
            } catch (Throwable ignored) {
                // not listening yet
            } finally {
                try { probe.close(); } catch (Throwable ignored) { }
            }
            try { Thread.sleep(50); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        stop();
        return false;
    }

    /** Stops it. Safe to call when it was never started. */
    void stop() {
        Process running = process;
        process = null;
        if (running == null) return;
        running.destroy();
        try {
            running.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
