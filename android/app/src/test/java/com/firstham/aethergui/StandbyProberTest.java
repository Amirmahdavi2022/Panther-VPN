package com.firstham.aethergui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Checks for the background pass that keeps a proved successor ready. */
public class StandbyProberTest {

    private static final long NOW = 1_700_000_000_000L;
    private static final String NL = "\uD83C\uDDF3\uD83C\uDDF1";
    private static final String JP = "\uD83C\uDDEF\uD83C\uDDF5";

    private static ProxyConfig node(String host, String flag) {
        return ProxyConfig.parse("vless://11111111-2222-3333-4444-555555555555@" + host
                + ":443?security=tls#" + flag + " node");
    }

    /** hysteria2 is in the pools and cannot be dialled by this core, so it is never probed. */
    private static ProxyConfig quic(String host) {
        return ProxyConfig.parse("hysteria2://secret@" + host + ":443#" + NL + " quic");
    }

    private static EndpointPool poolOf(ProxyConfig... configs) {
        EndpointPool pool = new EndpointPool();
        pool.merge(Arrays.asList(configs));
        return pool;
    }

    @Test public void waitsForTheTunnelToSettleBeforeTheFirstPass() {
        assertFalse(StandbyProber.due(NOW, 0, 0, NOW + 1_000));
        assertFalse(StandbyProber.due(NOW, 0, 0, NOW + StandbyProber.SETTLE_MS - 1));
        assertTrue(StandbyProber.due(NOW, 0, 0, NOW + StandbyProber.SETTLE_MS));
    }

    @Test public void waitsOutTheIntervalBetweenPasses() {
        long settled = NOW + StandbyProber.SETTLE_MS;
        assertFalse(StandbyProber.due(NOW, settled, 0, settled + 1_000));
        assertTrue(StandbyProber.due(NOW, settled, 0, settled + StandbyProber.INTERVAL_MS));
    }

    /** Nothing to gain means nothing is spent: no probing while enough successors are warm. */
    @Test public void skipsThePassWhenEnoughSuccessorsAreAlreadyWarm() {
        long settled = NOW + StandbyProber.SETTLE_MS + StandbyProber.INTERVAL_MS;
        assertFalse(StandbyProber.due(NOW, 0, StandbyProber.ENOUGH_WARM, settled));
        assertTrue(StandbyProber.due(NOW, 0, StandbyProber.ENOUGH_WARM - 1, settled));
    }

    @Test public void countsOnlyRecentEvidenceAsWarm() {
        ProxyConfig live = node("live.example.com", NL);
        ProxyConfig fresh = node("fresh.example.com", NL);
        ProxyConfig stale = node("stale.example.com", NL);
        ProxyConfig never = node("never.example.com", NL);
        EndpointPool pool = poolOf(live, fresh, stale, never);
        pool.recordSuccess(live.key(), 50, NOW);
        pool.recordSuccess(fresh.key(), 80, NOW - 60_000);
        pool.recordSuccess(stale.key(), 80, NOW - StandbyProber.WARM_FOR_MS - 60_000);

        // The live endpoint is not a successor, however warm it is.
        assertEquals(1, StandbyProber.warmCount(pool, StealthRegions.AUTOMATIC, live.key(), NOW));
        assertEquals(0, StandbyProber.warmCount(null, StealthRegions.AUTOMATIC, null, NOW));
    }

    /** The live endpoint must never be probed: a pass cannot bench the connection in use. */
    @Test public void neverProbesTheLiveEndpoint() {
        ProxyConfig live = node("live.example.com", NL);
        ProxyConfig other = node("other.example.com", NL);
        EndpointPool pool = poolOf(live, other);
        List<ProxyConfig> candidates =
                StandbyProber.candidates(pool, StealthRegions.AUTOMATIC, live.key(), NOW,
                        StandbyProber.BATCH);
        assertEquals(1, candidates.size());
        assertEquals(other.key(), candidates.get(0).key());
    }

    @Test public void skipsBenchedAndAlreadyWarmEndpoints() {
        ProxyConfig benched = node("benched.example.com", NL);
        ProxyConfig warm = node("warm.example.com", NL);
        ProxyConfig cold = node("cold.example.com", NL);
        EndpointPool pool = poolOf(benched, warm, cold);
        pool.recordFailure(benched.key(), NOW);
        pool.recordSuccess(warm.key(), 40, NOW - 30_000);

        List<ProxyConfig> candidates =
                StandbyProber.candidates(pool, StealthRegions.AUTOMATIC, null, NOW,
                        StandbyProber.BATCH);
        assertEquals(1, candidates.size());
        assertEquals(cold.key(), candidates.get(0).key());
    }

    /** Probing something the core could never dial would bench it for no reason. */
    @Test public void skipsProtocolsThisCoreCannotDial() {
        EndpointPool pool = poolOf(quic("quic.example.com"), node("tcp.example.com", NL));
        List<ProxyConfig> candidates =
                StandbyProber.candidates(pool, StealthRegions.AUTOMATIC, null, NOW,
                        StandbyProber.BATCH);
        assertEquals(1, candidates.size());
        assertEquals("vless", candidates.get(0).protocol);
    }

    /** This runs on someone's battery, so the batch is a hard cap. */
    @Test public void neverProbesMoreThanTheBatch() {
        List<ProxyConfig> many = new ArrayList<>();
        for (int i = 0; i < 40; i++) many.add(node("n" + i + ".example.com", NL));
        EndpointPool pool = new EndpointPool();
        pool.merge(many);
        assertEquals(3, StandbyProber.candidates(pool, StealthRegions.AUTOMATIC, null, NOW, 3).size());
        assertEquals(0, StandbyProber.candidates(pool, StealthRegions.AUTOMATIC, null, NOW, 0).size());
        assertEquals(0, StandbyProber.candidates(null, StealthRegions.AUTOMATIC, null, NOW, 5).size());
    }

    /** A chosen country is warmed first, so the successor is in the country the user asked for. */
    @Test public void warmsTheChosenCountryFirst() {
        EndpointPool pool = poolOf(
                node("nl1.example.com", NL), node("nl2.example.com", NL), node("jp1.example.com", JP));
        List<ProxyConfig> candidates = StandbyProber.candidates(pool, "JP", null, NOW, 1);
        assertEquals(1, candidates.size());
        assertEquals("JP", StealthRegions.countryOf(candidates.get(0)));
    }


    /**
     * The pools publish the same server several times with different credentials. Without a host
     * cap a six-slot batch regularly spent three slots on one address, so half the pass learned
     * the same fact twice - measured on the live United States list before this was added.
     */
    @Test public void spendsAtMostOneSlotPerHost() {
        EndpointPool pool = poolOf(
                ProxyConfig.parse("vless://aaaaaaaa-1111-1111-1111-111111111111@dup.example.com:8443#" + NL),
                ProxyConfig.parse("vless://bbbbbbbb-2222-2222-2222-222222222222@dup.example.com:8443#" + NL),
                ProxyConfig.parse("vless://cccccccc-3333-3333-3333-333333333333@dup.example.com:9443#" + NL),
                node("other.example.com", NL));
        List<ProxyConfig> candidates =
                StandbyProber.candidates(pool, StealthRegions.AUTOMATIC, null, NOW,
                        StandbyProber.BATCH);
        assertEquals(2, candidates.size());
        assertFalse(candidates.get(0).host.equalsIgnoreCase(candidates.get(1).host));
    }

    public static void main(String[] args) {
        int failures = 0;
        StandbyProberTest suite = new StandbyProberTest();
        for (java.lang.reflect.Method method : StandbyProberTest.class.getDeclaredMethods()) {
            if (method.getAnnotation(Test.class) == null) continue;
            try {
                method.invoke(suite);
                System.out.println("ok   " + method.getName());
            } catch (Exception failed) {
                failures++;
                System.out.println("FAIL " + method.getName() + ": " + failed.getCause());
            }
        }
        System.out.println(failures == 0 ? "all passed" : failures + " failed");
    }
}
