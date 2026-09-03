package com.firstham.aethergui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/** Run with: java -cp <classes> com.firstham.aethergui.EndpointTesterTest */
public final class EndpointTesterTest {

    private static int checks = 0;
    private static int failures = 0;
    private static final long NOW = 1_760_000_000_000L;

    private static void check(boolean ok, String what) {
        checks++;
        if (!ok) { failures++; System.out.println("  FAIL: " + what); }
    }

    private static List<ProxyConfig> candidates(int count) {
        List<ProxyConfig> out = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            out.add(ProxyConfig.parse("vless://u" + i + "@10.9.0." + i + ":443#N" + i));
        }
        return out;
    }

    /**
     * CI runs this. Without it these checks only ever ran by hand, because a class with a main
     * method and no test annotation is invisible to the unit test task - which is exactly how a
     * suite quietly stops protecting anything.
     */
    @Test public void everyCheckPasses() throws Exception {
        int before = failures;
        runAllChecks();
        assertEquals("endpoint tester checks failed", before, failures);
        assertTrue("No checks ran", checks > 0);
    }

    static void runAllChecks() throws Exception {
        recordsBothOutcomesInThePool();
        stopsOnceItHasEnough();
        runsThroughEverythingWhenNothingWorks();
        emptyAndNullInputsAreSafe();
        aThrowingProbeDoesNotSinkThePass();
        theResultIsImmediatelyUsable();
        udpProtocolsGetTheUdpProbe();
        realTcpProbeSeparatesOpenFromClosed();
        probesOverlap();

        System.out.println((failures == 0 ? "ALL PASS" : "FAILURES") + " — " + checks + " checks, " + failures + " failed");
    }

    private static void recordsBothOutcomesInThePool() {
        EndpointPool pool = new EndpointPool();
        List<ProxyConfig> list = candidates(10);
        // Odd hosts answer, even ones do not.
        EndpointTester.Probe half = (config, timeout) ->
                Integer.parseInt(config.host.substring(config.host.lastIndexOf('.') + 1)) % 2 == 1 ? 120 : -1;

        EndpointTester.Outcome outcome = EndpointTester.test(pool, list, half, 500, 8, 0, NOW);
        check(outcome.tested == 10, "every candidate was tested when nothing stops it");
        check(outcome.healthy == 5, "half of them answered");
        check(pool.size() == 10, "both the passes and the failures are kept in the pool");

        EndpointPool.Entry good = pool.get("vless|10.9.0.1|443|u1");
        check(good != null && good.successes == 1 && good.lastLatencyMillis == 120, "a pass is recorded with its latency");
        EndpointPool.Entry bad = pool.get("vless|10.9.0.2|443|u2");
        check(bad != null && bad.failures == 1 && bad.penaltyUntil > NOW, "a failure is recorded and benched");
        check(outcome.summary().contains("5 of 10"), "the summary reads correctly");
    }

    private static void stopsOnceItHasEnough() {
        EndpointPool pool = new EndpointPool();
        final AtomicInteger probes = new AtomicInteger();
        EndpointTester.Probe everythingWorks = (config, timeout) -> {
            probes.incrementAndGet();
            try { Thread.sleep(15); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            return 50;
        };
        EndpointTester.Outcome outcome = EndpointTester.test(pool, candidates(200), everythingWorks, 500, 4, 8, NOW);
        check(outcome.healthy >= 8, "it found what it needed");
        check(probes.get() < 200, "it did not probe the whole list once it had enough");
        check(outcome.stoppedEarly, "the early stop is reported");
    }

    private static void runsThroughEverythingWhenNothingWorks() {
        EndpointPool pool = new EndpointPool();
        EndpointTester.Probe dead = (config, timeout) -> -1;
        EndpointTester.Outcome outcome = EndpointTester.test(pool, candidates(20), dead, 200, 8, 5, NOW);
        check(outcome.tested == 20, "a dead network means the whole list gets tried");
        check(outcome.healthy == 0, "nothing was healthy");
        check(!outcome.stoppedEarly, "there was no early stop to report");
        check(pool.best(NOW) == null, "the pool honestly reports it has nothing to offer");
    }

    private static void emptyAndNullInputsAreSafe() {
        EndpointTester.Probe probe = (config, timeout) -> 10;
        check(EndpointTester.test(new EndpointPool(), new ArrayList<>(), probe, 100, 4, 2, NOW).tested == 0,
                "an empty candidate list is safe");
        check(EndpointTester.test(new EndpointPool(), null, probe, 100, 4, 2, NOW).healthy == 0,
                "a null candidate list is safe");
        check(EndpointTester.test(null, candidates(3), probe, 100, 4, 2, NOW).tested == 0,
                "a null pool is safe");
        check(EndpointTester.NETWORK_PROBE.probe(null, 100) == -1, "a null config probes as unreachable");
    }

    private static void aThrowingProbeDoesNotSinkThePass() {
        EndpointPool pool = new EndpointPool();
        EndpointTester.Probe flaky = (config, timeout) -> {
            if (config.host.endsWith(".3")) throw new RuntimeException("boom");
            return 30;
        };
        EndpointTester.Outcome outcome = EndpointTester.test(pool, candidates(6), flaky, 300, 3, 0, NOW);
        check(outcome.healthy == 5, "the five good candidates still landed");
        check(pool.size() == 6, "the exploding one is still in the pool for a later retry");
    }

    private static void theResultIsImmediatelyUsable() {
        EndpointPool pool = new EndpointPool();
        EndpointTester.Probe graded = (config, timeout) -> {
            int last = Integer.parseInt(config.host.substring(config.host.lastIndexOf('.') + 1));
            return last == 3 ? 40 : last % 2 == 1 ? 400 : -1;
        };
        EndpointTester.test(pool, candidates(9), graded, 500, 6, 0, NOW);
        EndpointPool.Entry best = pool.best(NOW);
        check(best != null, "there is something to connect to straight after the pass");
        check(best.config.host.equals("10.9.0.3"), "the fastest healthy one is chosen");
        EndpointPool.Entry next = pool.nextAfter(best.config.key(), NOW);
        check(next != null && !next.config.key().equals(best.config.key()),
                "a standby is ready before anything has even died");
    }

    private static void udpProtocolsGetTheUdpProbe() {
        // hysteria2 and tuic are QUIC; a TCP connect to their port fails on a healthy server, so
        // routing them to the wrong probe would quietly bench every one of them.
        ProxyConfig hy2 = ProxyConfig.parse("hysteria2://pw@203.0.113.1:8443#H");
        ProxyConfig tuic = ProxyConfig.parse("tuic://u:p@203.0.113.2:2053#T");
        ProxyConfig vless = ProxyConfig.parse("vless://u@203.0.113.3:443#V");
        check(hy2 != null && tuic != null && vless != null, "the three fixtures parse");

        // Closed ports on a documentation range: every probe should report unreachable, and the
        // point of the check is that none of them throws or hangs past its timeout.
        long started = System.currentTimeMillis();
        check(EndpointTester.NETWORK_PROBE.probe(hy2, 300) == -1, "an unreachable hysteria2 endpoint fails cleanly");
        check(EndpointTester.NETWORK_PROBE.probe(tuic, 300) == -1, "an unreachable tuic endpoint fails cleanly");
        check(System.currentTimeMillis() - started < 4000, "the probes honour their timeout");
    }

    /** The one that matters: the real TCP probe against a socket that is actually listening. */
    private static void realTcpProbeSeparatesOpenFromClosed() throws Exception {
        ServerSocket listener = new ServerSocket(0);
        int openPort = listener.getLocalPort();
        ProxyConfig open = ProxyConfig.parse("vless://u@127.0.0.1:" + openPort + "#open");
        long latency = EndpointTester.NETWORK_PROBE.probe(open, 1500);
        check(latency >= 0, "a listening port is reported as reachable");

        listener.close();
        ProxyConfig closed = ProxyConfig.parse("vless://u@127.0.0.1:" + openPort + "#closed");
        check(EndpointTester.NETWORK_PROBE.probe(closed, 800) == -1,
                "the same port is reported unreachable once nothing is listening");
    }

    private static void probesOverlap() {
        EndpointPool pool = new EndpointPool();
        final AtomicInteger concurrent = new AtomicInteger();
        final AtomicInteger peak = new AtomicInteger();
        EndpointTester.Probe slow = (config, timeout) -> {
            int now = concurrent.incrementAndGet();
            peak.accumulateAndGet(now, Math::max);
            try { Thread.sleep(80); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            concurrent.decrementAndGet();
            return 20;
        };
        long started = System.currentTimeMillis();
        EndpointTester.test(pool, candidates(16), slow, 1000, 8, 0, NOW);
        long elapsed = System.currentTimeMillis() - started;
        check(peak.get() > 1, "probes really run in parallel");
        check(elapsed < 16 * 80, "the pass beats a serial run");
    }

    /**
     * Standalone entry point, for running these checks without an Android toolchain around.
     * The exit code lives here and not in runAllChecks, because a System.exit inside a unit
     * test kills the test JVM and turns a clear failure report into an opaque crash.
     */
    public static void main(String[] args) throws Exception {
        runAllChecks();
        if (failures > 0) System.exit(1);
    }
}
