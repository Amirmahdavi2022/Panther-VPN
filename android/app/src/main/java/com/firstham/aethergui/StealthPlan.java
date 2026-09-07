package com.firstham.aethergui;

import java.util.ArrayList;
import java.util.List;

/**
 * The decisions the Stealth engine makes before and around dialling — kept apart from the service
 * that acts on them.
 *
 * <p>The service is where Android is, and Android is where nothing can be tested. So everything
 * here that could be got wrong lives in plain Java: whether the saved pool is worth dialling
 * straight away, whether it is worth going out to the network for a fresh one, which candidates
 * are worth spending a probe on, and when a run has swapped often enough to be called a failure.
 * {@link AetherVpnService} reads the answers and does the Android part.
 *
 * <p>Two rules underpin the whole thing:
 *
 * <p><b>A saved pool is the fallback, not the second choice.</b> The networks this engine exists
 * for are the ones where the config sources are blocked, so the run that most needs fresh
 * endpoints is exactly the run that cannot fetch any. Yesterday's scored list is then the only way
 * back online, and the engine must be willing to dial it with no fetch at all.
 *
 * <p><b>Fetching is a luxury, dialling is not.</b> A refresh is only ever attempted when there is
 * a tunnel to fetch through. Without one, this class never asks for a refresh it knows will fail —
 * it either dials what is saved or says plainly that there is nothing to dial.
 */
final class StealthPlan {

    /** Where the scored pool is kept between runs, under the app's files directory. */
    static final String POOL_FILE = "stealth-pool.txt";

    /** How many dialable endpoints make a saved pool good enough to skip the refresh. */
    static final int ENOUGH_SAVED = 5;

    /**
     * How old a pool may be before it is refreshed even though it still has entries.
     *
     * <p>Public pool endpoints turn over fast — servers are rotated, blocked and abandoned within
     * a day — so an old list is worth topping up whenever there is a tunnel to do it through.
     */
    static final long POOL_MAX_AGE_MS = 6L * 60L * 60L * 1000L;

    /** How many candidates one refresh is willing to probe. */
    static final int TEST_BUDGET = 48;

    /** How many probes run at once. */
    static final int TEST_PARALLELISM = 24;

    /** How long a single probe gets. */
    static final int TEST_TIMEOUT_MS = 2_500;

    /** Stop probing once this many candidates have answered; we need enough, not the best. */
    static final int TEST_ENOUGH = 16;

    /** How often the monitor looks at the engine. */
    static final long MONITOR_TICK_MS = 2_000L;

    /**
     * How often the live tunnel is made to carry a real request.
     *
     * <p>The core answers its own SOCKS handshake, so a dead upstream looks perfectly healthy from
     * the outside. Only pushing a request through it tells the truth, and that costs a round trip,
     * which is why it happens on a timer rather than every tick.
     */
    static final long VERIFY_INTERVAL_MS = 30_000L;

    /** How many endpoints one run may burn through before the run is called a failure. */
    static final int MAX_SWAPS = 8;

    /** A tunnel that lasted this long counts as having worked, whatever happened to it after. */
    static final long HELD_LONG_ENOUGH_MS = 120_000L;

    /** What the engine should do before it dials. */
    static final class Decision {
        /** How many saved endpoints this engine could dial right now. */
        final int ready;
        /** Whether to go out to the sources for more before dialling. */
        final boolean refresh;
        /** Why, in a line fit for the log. */
        final String reason;

        Decision(int ready, boolean refresh, String reason) {
            this.ready = ready;
            this.refresh = refresh;
            this.reason = reason;
        }

        /** True when there is at least one endpoint to try without touching the network. */
        boolean canDialNow() { return ready > 0; }

        /** True when there is nothing to dial and no way to go and get anything. */
        boolean isStuck() { return ready == 0 && !refresh; }
    }

    private StealthPlan() { }

    /**
     * Decides what to do with the pool we have.
     *
     * @param pool     the pool as loaded from disk
     * @param savedAt  when it was written, or 0 if it has never been written
     * @param canFetch whether there is a working tunnel to fetch through
     */
    static Decision decide(EndpointPool pool, long savedAt, boolean canFetch, long now) {
        int ready = ready(pool, now);
        if (!canFetch) {
            // No fetch path. Either the saved list carries this run or nothing does; asking for a
            // refresh here would only spend time failing.
            return new Decision(ready, false, ready == 0
                    ? "no saved endpoints, and no tunnel to fetch more through"
                    : ready + " saved endpoints and no tunnel to fetch through — dialling as is");
        }
        if (ready == 0) {
            return new Decision(0, true, "the saved pool has nothing this engine can dial");
        }
        if (ready < ENOUGH_SAVED) {
            return new Decision(ready, true, "only " + ready + " saved endpoints, topping up first");
        }
        if (isStale(savedAt, now)) {
            return new Decision(ready, true, ready + " saved endpoints but the pool is stale");
        }
        return new Decision(ready, false, ready + " saved endpoints, fresh enough to dial");
    }

    /**
     * How many endpoints in the pool this engine could actually dial.
     *
     * <p>Both filters matter. A benched endpoint has just failed and would fail again, and an
     * unsupported one — a hysteria2 or tuic entry — can never work at all no matter how well it
     * scores, so counting either would make an empty pool look healthy.
     */
    static int ready(EndpointPool pool, long now) {
        if (pool == null) return 0;
        int count = 0;
        for (EndpointPool.Entry entry : pool.ranked(now)) {
            if (entry.score(now) < 0) continue;
            if (!XrayConfig.supports(entry.config)) continue;
            count++;
        }
        return count;
    }

    /**
     * Whether a pool written at {@code savedAt} is old enough to be worth topping up.
     *
     * <p>A timestamp from the future is treated as unknown rather than as very fresh: phone clocks
     * do move backwards, and the cost of being wrong is one unnecessary refresh in one direction
     * and a pool that never refreshes again in the other.
     */
    static boolean isStale(long savedAt, long now) {
        if (savedAt <= 0 || savedAt > now) return true;
        return now - savedAt > POOL_MAX_AGE_MS;
    }

    /** The candidates worth probing out of a fresh fetch. */
    static List<ProxyConfig> candidates(List<ProxyConfig> fetched) {
        return candidates(fetched, TEST_BUDGET);
    }

    /**
     * Filters to what the core can dial, then spreads the budget across protocols.
     *
     * <p>Order matters: filtering first means the budget is spent entirely on endpoints that could
     * work. Shortlisting an unfiltered list would hand a large share of it to hysteria2 entries the
     * core has no client for.
     */
    static List<ProxyConfig> candidates(List<ProxyConfig> fetched, int budget) {
        return ConfigSources.shortlist(XrayConfig.supported(fetched), budget);
    }

    /** Whether the live tunnel is due to be made to carry a real request. */
    /**
     * How many checks in a row have to come back empty before a live tunnel is given up on.
     *
     * <p>🚨 One was costing working connections. A tunnel that had been carrying real traffic for
     * a minute - browsing, messaging, DNS, all of it visibly flowing - was thrown away because a
     * single small request did not come back, and the endpoint it moved to was worse. A probe
     * failing tells you about that one request: the endpoint may be busy, the check host may be
     * rate-limiting a shared address, a packet may simply have been lost. Three in a row, spaced
     * out, is a tunnel that has actually stopped.
     */
    static final int VERIFY_FAILURES_BEFORE_SWAP = 3;

    /**
     * How long to wait before asking again after a check comes back empty.
     *
     * <p>Much shorter than the healthy interval: something might be wrong, so this is not the
     * moment to wait another half minute, but it is also not the moment to hammer an endpoint that
     * may just be busy.
     */
    static final long VERIFY_RECHECK_MS = 5_000L;

    static boolean shouldVerify(long lastVerifiedAt, long now) {
        if (lastVerifiedAt <= 0 || lastVerifiedAt > now) return true;
        return now - lastVerifiedAt >= VERIFY_INTERVAL_MS;
    }

    /**
     * The swap count to carry forward after a tunnel that held for {@code heldMillis}.
     *
     * <p>Resetting after a tunnel that actually lasted is what separates "this network is hostile
     * and we are burning the pool" from "we have been connected for an hour and endpoints
     * occasionally die". Only the first should ever exhaust a run.
     */
    static int swapsAfter(int swaps, long heldMillis) {
        return heldMillis >= HELD_LONG_ENOUGH_MS ? 1 : swaps + 1;
    }

    /** True once a run has swapped so often that it is not going to settle. */
    static boolean exhausted(int swaps) { return swaps > MAX_SWAPS; }

    /** Dial the endpoint directly, from this network. */
    static final boolean DIRECT = false;

    /** Dial the endpoint through the carrier tunnel, so the connection starts somewhere else. */
    static final boolean CHAINED = true;

    // Three ways to reach an endpoint, not two. The boolean pair above is kept because the route
    // this network allows is persisted as one, and rewriting stored state to add a mode would
    // discard what every existing install has already learned.

    /** Straight out from this network. One hop, nothing between. */
    static final int MODE_DIRECT = 0;

    /** Out through the carrier tunnel. Two hops, and needs the carrier to be up. */
    static final int MODE_CHAINED = 1;

    /**
     * Straight out from this network, but with the opening packets shaped by a local proxy first.
     *
     * <p>It sits between the other two on purpose. Like the direct route it leaves from here and
     * needs nothing else running remotely, so it keeps the property the carrier route costs us:
     * the engine reaching its own endpoints without depending on another engine. What it adds is a
     * local hop that tears up the first packets, which is what a filter reading the handshake
     * cannot follow.
     *
     * <p>It is not a better direct route, and it is not a cheaper carrier. It is the only mode
     * that can be both independent and get through a network where plain direct dialling dies, and
     * that combination is the whole reason it exists.
     */
    static final int MODE_SPOOF = 2;

    /** Name for a mode, for logs and for the route remembered on disk. */
    static String modeName(int mode) {
        switch (mode) {
            case MODE_CHAINED: return "carrier";
            case MODE_SPOOF: return "spoof";
            default: return "direct";
        }
    }

    /**
     * The ways to reach one endpoint, in the order worth trying them.
     *
     * <p>🔑 There are two different reasons an endpoint fails, and only one of them is the
     * endpoint's fault. It can be dead — nothing answers it from anywhere. Or it can be alive and
     * simply unreachable <em>from this network</em>, which is the ordinary case on a filtered
     * connection and says nothing about the server at all. Trying both ways is what tells those
     * two apart, and it is why a failure is only written into the pool once every way has failed:
     * benching a healthy endpoint because the local network blocks it would slowly empty the pool
     * of exactly the servers worth keeping.
     *
     * <p>Direct comes first when nothing is known, because it is one hop rather than two and every
     * byte is faster for it. Once a run has learned which way works, that way is remembered and
     * tried first next time, so the cost of finding out is paid once rather than on every connect.
     *
     * <p>⚠️ Superseded, and kept only for its tests. This is the two-route form from before the
     * spoof route existed; the engine calls {@link #modes(boolean, boolean, int)} instead, which
     * names a route rather than answering "chained or not". Do not wire anything new to this — a
     * boolean cannot say "spoof", which is exactly how the spoof route came to be recorded as
     * "direct" and thrown away on the next connect.
     *
     * @param carrierAvailable whether a carrier tunnel is up to dial through
     * @param preferChained    what worked last time on this device
     */
    static boolean[] dialModes(boolean carrierAvailable, boolean preferChained) {
        if (!carrierAvailable) return new boolean[] { DIRECT };
        return preferChained
                ? new boolean[] { CHAINED, DIRECT }
                : new boolean[] { DIRECT, CHAINED };
    }

    /**
     * The modes left to try once one of them has been proved to work on this run.
     *
     * <p>Proving costs a full timeout per endpoint. Paying it once per run is the point of
     * knowing; paying it per candidate would make a swap slower than the drop it is hiding.
     */
    static boolean[] dialModes(boolean carrierAvailable, boolean preferChained, boolean proven,
                               boolean provenMode) {
        if (proven && (carrierAvailable || provenMode == DIRECT)) {
            return new boolean[] { provenMode };
        }
        return dialModes(carrierAvailable, preferChained);
    }

    /**
     * How many endpoints are tried on the remembered route alone before the other one is tried too.
     *
     * <p>The route this network allows is remembered across runs, but nothing in a run knows it is
     * still true until an endpoint answers - and until then every candidate was being dialled
     * twice, at a full timeout each. That is the difference between four endpoints inside the time
     * budget and eight, and the second mode almost never wins: if the carrier route worked on this
     * network an hour ago, the endpoint that just failed on it failed because it is dead, not
     * because the route changed.
     *
     * <p>Three, not one, because the remembered route can genuinely go stale - a different network
     * with the same name, a carrier that is no longer up - and after three dead endpoints in a row
     * that is worth considering. It is not worth considering after the first.
     */
    static final int PREFERRED_ONLY_CANDIDATES = 3;

    /**
     * The modes to try for a candidate, given how many have already failed on the preferred one.
     *
     * @param failedOnPreferred endpoints tried on the remembered route this run that did not hold
     */
    static boolean[] dialModes(boolean carrierAvailable, boolean preferChained, boolean proven,
                               boolean provenMode, int failedOnPreferred) {
        if (proven && (carrierAvailable || provenMode == DIRECT)) {
            return new boolean[] { provenMode };
        }
        if (carrierAvailable && failedOnPreferred < PREFERRED_ONLY_CANDIDATES) {
            return new boolean[] { preferChained };
        }
        return dialModes(carrierAvailable, preferChained);
    }

    /**
     * The ways to reach one endpoint, in the order worth trying them, across all three modes.
     *
     * <p>Cost order when nothing is known: direct, then spoof, then carrier. Direct is one hop and
     * no extra process. Spoof is one hop plus a local process, so it is cheap and — unlike the
     * carrier — does not need another engine to be up. The carrier is last because it is two hops
     * and depends on something else already working.
     *
     * <p>The remembered route still goes first. What this network allowed an hour ago is the best
     * guess available, and paying a full timeout to rediscover it on every connect is the cost
     * this ordering exists to avoid.
     *
     * <p>A mode whose machinery is not available is left out entirely rather than tried and
     * failed. A failure has to mean something about the endpoint or the network; a failure that
     * only means "the binary is missing" would be recorded as evidence and would slowly poison
     * the pool.
     *
     * @param carrierAvailable whether a carrier tunnel is up to dial through
     * @param spoofAvailable   whether the local shaping proxy can be started
     * @param preferred        the mode this device remembers working on this network
     */
    static int[] modes(boolean carrierAvailable, boolean spoofAvailable, int preferred) {
        List<Integer> order = new ArrayList<>();
        if (usable(preferred, carrierAvailable, spoofAvailable)) order.add(preferred);
        for (int mode : new int[] { MODE_DIRECT, MODE_SPOOF, MODE_CHAINED }) {
            if (!order.contains(mode) && usable(mode, carrierAvailable, spoofAvailable)) {
                order.add(mode);
            }
        }
        // Direct is always possible - there is nothing to be unavailable - so this cannot be empty
        // in practice. Returning it explicitly rather than an empty array keeps a caller that
        // somehow gets here from silently dialling nothing.
        if (order.isEmpty()) return new int[] { MODE_DIRECT };
        int[] out = new int[order.size()];
        for (int i = 0; i < out.length; i++) out[i] = order.get(i);
        return out;
    }

    /**
     * The modes left once one has been proved to work this run.
     *
     * <p>Same reasoning as the two-mode version: proving costs a full timeout per endpoint, and
     * paying it once per run is the point of knowing.
     */
    static int[] modes(boolean carrierAvailable, boolean spoofAvailable, int preferred,
                       boolean proven, int provenMode) {
        if (proven && usable(provenMode, carrierAvailable, spoofAvailable)) {
            return new int[] { provenMode };
        }
        return modes(carrierAvailable, spoofAvailable, preferred);
    }

    /**
     * The modes to try for a candidate, given how many have already failed on the preferred one.
     *
     * <p>Holding to the remembered route for the first few candidates matters more with three
     * modes than with two: trying every mode on every candidate would triple the cost of a dead
     * endpoint, and a dead endpoint is the common case in a public pool.
     */
    static int[] modes(boolean carrierAvailable, boolean spoofAvailable, int preferred,
                       boolean proven, int provenMode, int failedOnPreferred) {
        if (proven && usable(provenMode, carrierAvailable, spoofAvailable)) {
            return new int[] { provenMode };
        }
        if (failedOnPreferred < PREFERRED_ONLY_CANDIDATES
                && usable(preferred, carrierAvailable, spoofAvailable)) {
            return new int[] { preferred };
        }
        return modes(carrierAvailable, spoofAvailable, preferred);
    }

    /** Whether a mode's machinery is present. Direct needs nothing, so it always is. */
    static boolean usable(int mode, boolean carrierAvailable, boolean spoofAvailable) {
        if (mode == MODE_CHAINED) return carrierAvailable;
        if (mode == MODE_SPOOF) return spoofAvailable;
        return mode == MODE_DIRECT;
    }
}
