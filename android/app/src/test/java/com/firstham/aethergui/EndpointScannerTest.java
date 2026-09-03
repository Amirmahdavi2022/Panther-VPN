package com.firstham.aethergui;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/** Run with: java -cp <classes> com.firstham.aethergui.EndpointScannerTest */
public final class EndpointScannerTest {

    private static int checks = 0;
    private static int failures = 0;

    private static void check(boolean condition, String what) {
        checks++;
        if (!condition) {
            failures++;
            System.out.println("  FAIL: " + what);
        }
    }

    public static void main(String[] args) throws Exception {
        candidatesAreWellFormed();
        candidatesRespectTheLimit();
        candidatesAreUnique();
        scanKeepsOnlyLiveOnesAndSortsByLatency();
        scanSurvivesAnAllDeadNetwork();
        scanIgnoresMalformedCandidates();
        packetLooksLikeAHandshakeHeader();
        packetsDifferBetweenCalls();
        realUdpProbeSeparatesLiveFromSilent();
        probesRunInParallel();

        System.out.println((failures == 0 ? "ALL PASS" : "FAILURES") + " — " + checks + " checks, " + failures + " failed");
        if (failures > 0) System.exit(1);
    }

    private static void candidatesAreWellFormed() {
        List<String> list = EndpointScanner.candidates(40, new Random(7));
        for (String candidate : list) {
            int split = candidate.lastIndexOf(':');
            check(split > 0, "candidate has a port separator: " + candidate);
            String host = candidate.substring(0, split);
            int port = Integer.parseInt(candidate.substring(split + 1));
            String[] octets = host.split("\\.");
            check(octets.length == 4, "host has four octets: " + host);
            int last = Integer.parseInt(octets[3]);
            check(last >= 1 && last <= 254, "last octet is a usable host address: " + host);
            boolean knownPrefix = false;
            for (String prefix : EndpointScanner.PREFIXES) {
                if (host.startsWith(prefix + ".")) knownPrefix = true;
            }
            check(knownPrefix, "host sits in a known prefix: " + host);
            boolean knownPort = false;
            for (int candidatePort : EndpointScanner.PORTS) {
                if (candidatePort == port) knownPort = true;
            }
            check(knownPort, "port comes from the published list: " + port);
        }
    }

    private static void candidatesRespectTheLimit() {
        check(EndpointScanner.candidates(0, new Random(1)).isEmpty(), "a zero limit yields nothing");
        check(EndpointScanner.candidates(-5, new Random(1)).isEmpty(), "a negative limit yields nothing");
        check(EndpointScanner.candidates(12, new Random(1)).size() == 12, "the limit is honoured exactly");
        int pool = EndpointScanner.PREFIXES.length * EndpointScanner.PORTS.length;
        check(EndpointScanner.candidates(pool + 500, new Random(1)).size() <= pool,
                "asking for more than exists does not loop forever");
    }

    private static void candidatesAreUnique() {
        List<String> list = EndpointScanner.candidates(60, new Random(3));
        Set<String> unique = new HashSet<>(list);
        check(unique.size() == list.size(), "no candidate is probed twice");

        // Different seeds must not produce the same sample, or a filter learns one fixed set.
        List<String> other = EndpointScanner.candidates(60, new Random(99));
        check(!list.equals(other), "the sample varies between runs");
    }

    private static void scanKeepsOnlyLiveOnesAndSortsByLatency() {
        List<String> candidates = new ArrayList<>();
        candidates.add("10.0.0.1:2408");
        candidates.add("10.0.0.2:500");
        candidates.add("10.0.0.3:4500");
        candidates.add("10.0.0.4:2408");

        EndpointScanner.Prober fake = (host, port, timeout) -> {
            if (host.equals("10.0.0.1")) return 90;
            if (host.equals("10.0.0.3")) return 12;
            if (host.equals("10.0.0.4")) return 45;
            return -1;
        };

        List<EndpointScanner.Result> results = EndpointScanner.scan(candidates, fake, 200, 4);
        check(results.size() == 3, "silent addresses are dropped");
        check(results.get(0).endpoint().equals("10.0.0.3:4500"), "fastest is first");
        check(results.get(1).endpoint().equals("10.0.0.4:2408"), "second fastest is second");
        check(results.get(2).endpoint().equals("10.0.0.1:2408"), "slowest live one is last");

        String best = EndpointScanner.bestEndpoint(0, 200, 4, fake, new Random(1));
        check(best == null, "an empty candidate list yields no endpoint rather than a crash");
    }

    private static void scanSurvivesAnAllDeadNetwork() {
        List<String> candidates = new ArrayList<>();
        candidates.add("10.0.0.1:2408");
        candidates.add("10.0.0.2:500");
        EndpointScanner.Prober dead = (host, port, timeout) -> -1;
        List<EndpointScanner.Result> results = EndpointScanner.scan(candidates, dead, 100, 4);
        check(results.isEmpty(), "nothing answered means an empty list");

        // This is the important one: finding nothing must fall back to the core's own default
        // rather than failing the connection outright.
        String best = EndpointScanner.bestEndpoint(10, 100, 4, dead, new Random(5));
        check(best == null, "a dead sweep returns null so the caller can fall back");
    }

    private static void scanIgnoresMalformedCandidates() {
        List<String> candidates = new ArrayList<>();
        candidates.add("garbage-without-a-port");
        candidates.add("10.0.0.9:notanumber");
        candidates.add(":2408");
        candidates.add("10.0.0.5:2408");
        EndpointScanner.Prober fake = (host, port, timeout) -> host.equals("10.0.0.5") ? 20 : -1;
        List<EndpointScanner.Result> results = EndpointScanner.scan(candidates, fake, 100, 4);
        check(results.size() == 1, "malformed entries are skipped, not fatal");
        check(results.get(0).endpoint().equals("10.0.0.5:2408"), "the valid entry still wins");
    }

    private static void packetLooksLikeAHandshakeHeader() {
        byte[] packet = EndpointScanner.handshakeShapedPacket();
        check(packet.length == 148, "packet is the handshake initiation size");
        check(packet[0] == 1, "type byte marks a handshake initiation");
        check(packet[1] == 0 && packet[2] == 0 && packet[3] == 0, "the reserved bytes are zero");
    }

    private static void packetsDifferBetweenCalls() {
        byte[] first = EndpointScanner.handshakeShapedPacket();
        byte[] second = EndpointScanner.handshakeShapedPacket();
        boolean differs = false;
        for (int i = 4; i < first.length; i++) if (first[i] != second[i]) differs = true;
        check(differs, "two probes are not byte-identical");
    }

    /** The one that matters: the real UDP prober against a socket that actually answers. */
    private static void realUdpProbeSeparatesLiveFromSilent() throws Exception {
        DatagramSocket responder = new DatagramSocket(0);
        int livePort = responder.getLocalPort();
        Thread echo = new Thread(() -> {
            try {
                byte[] buffer = new byte[512];
                DatagramPacket in = new DatagramPacket(buffer, buffer.length);
                responder.receive(in);
                byte[] reply = new byte[92];
                reply[0] = 2;
                responder.send(new DatagramPacket(reply, reply.length, in.getSocketAddress()));
            } catch (Exception ignored) {
                // socket closed at the end of the test
            }
        });
        echo.setDaemon(true);
        echo.start();

        long live = EndpointScanner.UDP_PROBE.probe("127.0.0.1", livePort, 1500);
        check(live >= 0, "an address that answers is reported as reachable");

        // A port with nothing bound: loopback answers ICMP port-unreachable, which surfaces as an
        // IOException, so this also covers the refused case rather than only the timeout case.
        DatagramSocket scratch = new DatagramSocket(0);
        int deadPort = scratch.getLocalPort();
        scratch.close();
        long dead = EndpointScanner.UDP_PROBE.probe("127.0.0.1", deadPort, 400);
        check(dead < 0, "an address with nothing listening is reported as unreachable");

        responder.close();
    }

    private static void probesRunInParallel() {
        List<String> candidates = new ArrayList<>();
        for (int i = 1; i <= 24; i++) candidates.add("10.1.0." + i + ":2408");
        final AtomicInteger concurrent = new AtomicInteger();
        final AtomicInteger peak = new AtomicInteger();
        EndpointScanner.Prober slow = (host, port, timeout) -> {
            int now = concurrent.incrementAndGet();
            peak.accumulateAndGet(now, Math::max);
            try { Thread.sleep(120); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            concurrent.decrementAndGet();
            return 10;
        };
        long started = System.currentTimeMillis();
        List<EndpointScanner.Result> results = EndpointScanner.scan(candidates, slow, 2000, 12);
        long elapsed = System.currentTimeMillis() - started;
        check(results.size() == 24, "every candidate was probed");
        check(peak.get() > 1, "probes really overlap rather than running one at a time");
        check(elapsed < 24 * 120, "the sweep is faster than a serial pass would be");
    }
}
