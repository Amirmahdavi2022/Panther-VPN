package com.firstham.aethergui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Exercises the decisions the Stealth engine makes before it dials.
 *
 * <p>These are the rules that decide whether the engine comes up at all on a network where nothing
 * can be fetched, which is the one case that matters most and the one hardest to reproduce by
 * hand, so they are checked here against real pools built from real URIs rather than mocks.
 *
 * <p>Runs two ways on purpose. CI runs the {@code @Test} methods, and
 * {@code java -cp <classes> com.firstham.aethergui.StealthPlanTest} runs the same checks with a
 * count at the end, which is how they get exercised while there is no Android toolchain around.
 */
public final class StealthPlanTest {

    private static int checks = 0;
    private static int failures = 0;

    private static void check(boolean ok, String what) {
        checks++;
        if (!ok) { failures++; System.out.println("  FAIL: " + what); }
    }

    private static final long NOW = 1_700_000_000_000L;

    // --- fixtures ----------------------------------------------------------------------------

    private static ProxyConfig parse(String uri) {
        ProxyConfig config = ProxyConfig.parse(uri);
        if (config == null) throw new IllegalStateException("The fixture did not parse: " + uri);
        return config;
    }

    /** A dialable endpoint, numbered so a pool can hold as many distinct ones as a test needs. */
    private static ProxyConfig vless(int index) {
        return parse("vless://11111111-2222-3333-4444-55555555555" + (index % 10)
                + "@node" + index + ".example.net:443"
                + "?encryption=none&security=reality&sni=www.microsoft.com&fp=chrome"
                + "&pbk=abcdefgHIJKLmnop0123456789&sid=7f&type=tcp#Node" + index);
    }

    /** Parses fine, and the core has no client for it — so it must never count as ready. */
    private static ProxyConfig hysteria(int index) {
        return parse("hysteria2://p4ssw0rd@fast" + index + ".example.org:8443?sni=example.org#Hy" + index);
    }

    private static EndpointPool poolOf(ProxyConfig... configs) {
        EndpointPool pool = new EndpointPool();
        pool.merge(new ArrayList<>(Arrays.asList(configs)));
        return pool;
    }

    // --- what counts as ready ------------------------------------------------------------------

    @Test public void countsOnlyEndpointsTheCoreCanActuallyDial() {
        EndpointPool pool = poolOf(vless(1), hysteria(1), hysteria(2), vless(2));
        check(pool.size() == 4, "the pool holds everything it was given");
        check(StealthPlan.ready(pool, NOW) == 2, "only the two dialable entries count as ready");
    }

    @Test public void doesNotCountBenchedEndpoints() {
        EndpointPool pool = poolOf(vless(1), vless(2), vless(3));
        pool.recordFailure(vless(2).key(), NOW);
        check(StealthPlan.ready(pool, NOW) == 2, "a benched endpoint is not ready");
        // The penalty is a few minutes; well past it the endpoint is available again.
        check(StealthPlan.ready(pool, NOW + 3_600_000L) == 3, "the bench is temporary, not a delete");
    }

    @Test public void anEmptyOrMissingPoolIsReadyForNothing() {
        check(StealthPlan.ready(new EndpointPool(), NOW) == 0, "an empty pool is not ready");
        check(StealthPlan.ready(null, NOW) == 0, "a missing pool does not throw");
    }

    // --- the refresh decision ------------------------------------------------------------------

    @Test public void refreshesWhenThePoolHasNothingDialable() {
        StealthPlan.Decision decision = StealthPlan.decide(poolOf(hysteria(1)), NOW, true, NOW);
        check(decision.refresh, "a pool of unsupported entries is refreshed");
        check(!decision.canDialNow(), "and there is nothing to dial in the meantime");
        check(!decision.isStuck(), "but it is not stuck, because there is a tunnel to fetch through");
    }

    @Test public void refreshesWhenThereAreFewSavedEndpoints() {
        EndpointPool thin = poolOf(vless(1), vless(2));
        StealthPlan.Decision decision = StealthPlan.decide(thin, NOW, true, NOW);
        check(decision.refresh, "a thin pool is topped up");
        check(decision.canDialNow(), "while still being dialable if the fetch fails");
    }

    @Test public void dialsStraightAwayFromAFullFreshPool() {
        EndpointPool full = poolOf(vless(1), vless(2), vless(3), vless(4), vless(5), vless(6));
        StealthPlan.Decision decision = StealthPlan.decide(full, NOW, true, NOW);
        check(!decision.refresh, "a full fresh pool is dialled without a fetch");
        check(decision.ready == 6, "and reports what it has");
    }

    @Test public void refreshesAFullButStalePool() {
        EndpointPool full = poolOf(vless(1), vless(2), vless(3), vless(4), vless(5), vless(6));
        long saved = NOW - StealthPlan.POOL_MAX_AGE_MS - 1;
        check(StealthPlan.decide(full, saved, true, NOW).refresh, "a stale pool is topped up");
        check(!StealthPlan.decide(full, NOW - 60_000L, true, NOW).refresh, "a recent one is not");
    }

    @Test public void neverAsksForARefreshItCannotPerform() {
        // The whole point of the engine: no tunnel to fetch through is the normal case on the
        // networks it exists for, and it must still dial what it has.
        EndpointPool saved = poolOf(vless(1), vless(2));
        StealthPlan.Decision decision = StealthPlan.decide(saved, 0L, false, NOW);
        check(!decision.refresh, "no fetch is attempted without a tunnel");
        check(decision.canDialNow(), "yesterday's pool still carries the run");
        check(!decision.isStuck(), "so this is not a failure");
    }

    @Test public void reportsBeingStuckOnlyWhenThereIsNothingAndNoWayToGetAnything() {
        StealthPlan.Decision nothing = StealthPlan.decide(new EndpointPool(), 0L, false, NOW);
        check(nothing.isStuck(), "an empty pool with no tunnel is stuck");
        StealthPlan.Decision fetchable = StealthPlan.decide(new EndpointPool(), 0L, true, NOW);
        check(!fetchable.isStuck(), "an empty pool with a tunnel is not");
    }

    @Test public void everyDecisionExplainsItself() {
        StealthPlan.Decision[] all = {
                StealthPlan.decide(new EndpointPool(), 0L, false, NOW),
                StealthPlan.decide(new EndpointPool(), 0L, true, NOW),
                StealthPlan.decide(poolOf(vless(1)), NOW, true, NOW),
                StealthPlan.decide(poolOf(vless(1)), NOW, false, NOW),
        };
        for (StealthPlan.Decision decision : all) {
            check(decision.reason != null && !decision.reason.isEmpty(), "the decision carries a reason");
        }
    }

    // --- staleness ------------------------------------------------------------------------------

    @Test public void treatsAnUnwrittenOrImpossibleTimestampAsStale() {
        check(StealthPlan.isStale(0L, NOW), "a pool that was never written is stale");
        check(StealthPlan.isStale(NOW + 86_400_000L, NOW), "a timestamp from the future is stale");
        check(!StealthPlan.isStale(NOW - 1_000L, NOW), "a pool written a second ago is not");
    }

    // --- candidate selection --------------------------------------------------------------------

    @Test public void spendsTheProbeBudgetOnlyOnDialableEndpoints() {
        List<ProxyConfig> fetched = new ArrayList<>();
        for (int i = 0; i < 30; i++) fetched.add(hysteria(i));
        for (int i = 0; i < 4; i++) fetched.add(vless(i));

        List<ProxyConfig> candidates = StealthPlan.candidates(fetched, 10);
        check(candidates.size() == 4, "the unsupported entries never reach the tester");
        for (ProxyConfig candidate : candidates) {
            check(XrayConfig.supports(candidate), "every candidate is one the core can dial");
        }
    }

    @Test public void honoursTheBudget() {
        List<ProxyConfig> fetched = new ArrayList<>();
        for (int i = 0; i < 50; i++) fetched.add(vless(i));
        check(StealthPlan.candidates(fetched, 12).size() == 12, "the budget caps the shortlist");
        check(StealthPlan.candidates(fetched).size() == StealthPlan.TEST_BUDGET,
                "the default budget is the one the service uses");
    }

    @Test public void survivesAnEmptyFetch() {
        check(StealthPlan.candidates(new ArrayList<ProxyConfig>(), 10).isEmpty(), "nothing in, nothing out");
        check(StealthPlan.candidates(null, 10).isEmpty(), "a failed fetch does not throw");
    }

    // --- the monitor --------------------------------------------------------------------------

    @Test public void verifiesOnFirstLookAndThenOnTheInterval() {
        check(StealthPlan.shouldVerify(0L, NOW), "the first check happens immediately");
        check(!StealthPlan.shouldVerify(NOW - 1_000L, NOW), "not again a second later");
        check(StealthPlan.shouldVerify(NOW - StealthPlan.VERIFY_INTERVAL_MS, NOW), "again on the interval");
        check(StealthPlan.shouldVerify(NOW + 60_000L, NOW), "a clock that jumped back does not stall it");
    }

    @Test public void aTunnelThatLastedResetsTheSwapCount() {
        check(StealthPlan.swapsAfter(5, StealthPlan.HELD_LONG_ENOUGH_MS) == 1,
                "a tunnel that held starts the count over");
        check(StealthPlan.swapsAfter(5, 3_000L) == 6, "one that died instantly adds to it");
    }

    @Test public void givesUpOnlyAfterRealPersistence() {
        check(!StealthPlan.exhausted(StealthPlan.MAX_SWAPS), "the last allowed swap still runs");
        check(StealthPlan.exhausted(StealthPlan.MAX_SWAPS + 1), "one past the limit gives up");
    }

    // --- runner ---------------------------------------------------------------------------------

    /**
     * The one check CI actually fails on.
     *
     * <p>The methods above record failures in a counter rather than throwing, which keeps a
     * standalone run reporting every problem instead of stopping at the first. That counter has to
     * be asserted somewhere or a red run would report green, and this is where.
     */
    @Test public void everyCheckPasses() {
        int before = failures;
        runAllChecks();
        assertEquals("Stealth plan checks failed", before, failures);
        assertTrue("No checks ran", checks > 0);
    }

    void runAllChecks() {
        System.out.println("StealthPlan");
        countsOnlyEndpointsTheCoreCanActuallyDial();
        doesNotCountBenchedEndpoints();
        anEmptyOrMissingPoolIsReadyForNothing();
        refreshesWhenThePoolHasNothingDialable();
        refreshesWhenThereAreFewSavedEndpoints();
        dialsStraightAwayFromAFullFreshPool();
        refreshesAFullButStalePool();
        neverAsksForARefreshItCannotPerform();
        reportsBeingStuckOnlyWhenThereIsNothingAndNoWayToGetAnything();
        everyDecisionExplainsItself();
        treatsAnUnwrittenOrImpossibleTimestampAsStale();
        spendsTheProbeBudgetOnlyOnDialableEndpoints();
        honoursTheBudget();
        survivesAnEmptyFetch();
        verifiesOnFirstLookAndThenOnTheInterval();
        aTunnelThatLastedResetsTheSwapCount();
        givesUpOnlyAfterRealPersistence();
        System.out.println("  " + checks + " checks, " + failures + " failures");
    }

    /**
     * Standalone entry point, for running these checks without an Android toolchain around.
     * The exit code lives here and not in runAllChecks, because a System.exit inside a unit
     * test kills the test JVM and turns a clear failure report into an opaque crash.
     */
    public static void main(String[] args) {
        new StealthPlanTest().runAllChecks();
        if (failures > 0) System.exit(1);
    }
}
