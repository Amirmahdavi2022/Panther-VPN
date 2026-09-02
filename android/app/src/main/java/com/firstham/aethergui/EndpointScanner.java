package com.firstham.aethergui;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Picks a Cloudflare edge address for the Turbo core to dial.
 *
 * The core ships one anycast address. When a network blackholes that address the connection simply
 * never comes up, and there is nothing the user can do about it. The rule we settled on while
 * building the Worker deployer applies here too: whether an address answers is a property of the
 * USER'S NETWORK, not of the address. Measured from anywhere else every address looks fine. So the
 * search has to run on the device, at connect time, against the network actually in use.
 *
 * Deliberately free of Android imports so the whole thing can be compiled and exercised on a
 * desktop JVM — the same approach that made ExitLocation and GlobalRegions verifiable.
 */
final class EndpointScanner {

    /**
     * Address blocks Cloudflare's consumer WARP edge is published on, and the UDP ports it accepts.
     *
     * Treat this list as a starting set, not as truth: entries are cheap to include and the probe
     * is the only thing that decides. A block that stops answering costs one timed-out probe in
     * parallel with the rest; it cannot produce a wrong answer.
     */
    static final String[] PREFIXES = {
            "162.159.192", "162.159.193", "162.159.195",
            "188.114.96", "188.114.97", "188.114.98", "188.114.99",
    };

    static final int[] PORTS = {
            2408, 500, 1701, 4500, 854, 859, 864, 878, 880, 890, 891, 894,
            903, 908, 928, 934, 939, 942, 943, 945, 946, 955, 968, 987, 988,
            1002, 1010, 1014, 1018, 1070, 1074, 1180, 1387, 1843, 2371, 2506,
            3138, 3476, 3581, 3854, 4177, 4198, 4233, 5279, 5956, 7103, 7152,
            7156, 7281, 7559, 8319, 8742, 8854, 8886,
    };

    /** A candidate address and the round trip we measured for it, in milliseconds. */
    static final class Result {
        final String host;
        final int port;
        final long millis;

        Result(String host, int port, long millis) {
            this.host = host;
            this.port = port;
            this.millis = millis;
        }

        /** The form the core expects in AETHER_PEER. */
        String endpoint() { return host + ":" + port; }

        @Override public String toString() { return endpoint() + " (" + millis + "ms)"; }
    }

    /** How a single candidate gets tested. Swapped out in tests for something deterministic. */
    interface Prober {
        /** Round trip in millis, or -1 when the address did not answer in time. */
        long probe(String host, int port, int timeoutMillis);
    }

    private EndpointScanner() { }

    /**
     * Builds the candidate list: one random host per prefix per port, capped at {@code limit}.
     *
     * Random host octets rather than fixed ones, because a fixed set is exactly what a filter
     * learns to drop, and because within a /24 the edge answers on every address anyway. The
     * shuffle keeps one unlucky prefix from occupying the whole head of the list.
     */
    static List<String> candidates(int limit, Random random) {
        if (limit <= 0) return Collections.emptyList();
        LinkedHashSet<String> out = new LinkedHashSet<>();
        List<String> pool = new ArrayList<>();
        for (String prefix : PREFIXES) {
            for (int port : PORTS) {
                pool.add(prefix + "." + (1 + random.nextInt(254)) + ":" + port);
            }
        }
        Collections.shuffle(pool, random);
        for (String candidate : pool) {
            if (out.size() >= limit) break;
            out.add(candidate);
        }
        return new ArrayList<>(out);
    }

    /**
     * Probes every candidate in parallel and returns the ones that answered, fastest first.
     *
     * Parallel because a serial sweep over a list this long would take minutes on a bad network,
     * and the user is staring at a connect button the whole time.
     */
    static List<Result> scan(List<String> candidates, Prober prober, int timeoutMillis, int parallelism) {
        List<Result> alive = Collections.synchronizedList(new ArrayList<Result>());
        if (candidates.isEmpty()) return alive;
        ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, Math.min(parallelism, candidates.size())));
        try {
            List<Callable<Void>> jobs = new ArrayList<>();
            for (String candidate : candidates) {
                final int split = candidate.lastIndexOf(':');
                if (split <= 0) continue;
                final String host = candidate.substring(0, split);
                final int port;
                try {
                    port = Integer.parseInt(candidate.substring(split + 1));
                } catch (NumberFormatException ignored) {
                    continue;
                }
                jobs.add(() -> {
                    long millis = prober.probe(host, port, timeoutMillis);
                    if (millis >= 0) alive.add(new Result(host, port, millis));
                    return null;
                });
            }
            try {
                for (Future<Void> future : pool.invokeAll(jobs, timeoutMillis * 3L, TimeUnit.MILLISECONDS)) {
                    // invokeAll already waited; draining the futures just surfaces nothing we need.
                    future.isDone();
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        } finally {
            pool.shutdownNow();
        }
        List<Result> sorted = new ArrayList<>(alive);
        Collections.sort(sorted, new Comparator<Result>() {
            @Override public int compare(Result left, Result right) {
                return Long.compare(left.millis, right.millis);
            }
        });
        return sorted;
    }

    /**
     * The real probe: send a UDP datagram and see whether anything comes back.
     *
     * This does NOT prove the far side is a healthy WARP gateway — only that UDP to that
     * address and port is not being blackholed by the network in between, which is the failure
     * this whole class exists to route around. The core still does its own handshake afterwards
     * and rejects the address if it turns out to be unusable.
     */
    static final Prober UDP_PROBE = new Prober() {
        @Override public long probe(String host, int port, int timeoutMillis) {
            DatagramSocket socket = null;
            try {
                socket = new DatagramSocket();
                socket.setSoTimeout(timeoutMillis);
                byte[] payload = handshakeShapedPacket();
                socket.send(new DatagramPacket(payload, payload.length, new InetSocketAddress(host, port)));
                long started = System.nanoTime();
                byte[] buffer = new byte[256];
                socket.receive(new DatagramPacket(buffer, buffer.length));
                return Math.max(1L, (System.nanoTime() - started) / 1_000_000L);
            } catch (IOException timeoutOrRefused) {
                return -1;
            } finally {
                if (socket != null) socket.close();
            }
        }
    };

    /**
     * A datagram shaped like a WireGuard handshake initiation: type byte 1, three reserved zero
     * bytes, then the fixed-size body. The contents are not a valid handshake and no reply is
     * expected to be meaningful — the point is that a silent address and a reachable one are
     * distinguishable, and that the packet does not look like an obvious scanner probe.
     */
    static byte[] handshakeShapedPacket() {
        byte[] packet = new byte[148];
        packet[0] = 1;
        // Bytes 1..3 stay zero, as the real header's reserved field does. The body is filled with
        // per-packet random data so repeated probes are not byte-identical to each other.
        byte[] body = new byte[packet.length - 4];
        new Random().nextBytes(body);
        System.arraycopy(body, 0, packet, 4, body.length);
        return packet;
    }

    /**
     * Whole job in one call: sample candidates, probe them, hand back the best endpoint.
     * Returns null when nothing answered, which means "let the core use its own default" rather
     * than "fail the connection" — a scan that finds nothing must never be worse than not scanning.
     */
    static String bestEndpoint(int sampleSize, int timeoutMillis, int parallelism, Prober prober, Random random) {
        List<Result> results = scan(candidates(sampleSize, random), prober, timeoutMillis, parallelism);
        return results.isEmpty() ? null : results.get(0).endpoint();
    }
}
