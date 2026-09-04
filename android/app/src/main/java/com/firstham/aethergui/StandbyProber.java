package com.firstham.aethergui;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Keeps a proved successor ready while the tunnel is still healthy.
 *
 * <p>Until now the pool was only ever tested at connect time. That means the moment an endpoint
 * dies is the moment the search for its replacement <em>starts</em>: the pool has a ranking, but a
 * ranking built from evidence that may be hours old, so the first candidate it hands over is a
 * guess and the user waits through however many guesses are wrong. On a filtered network that is
 * exactly when guessing is most likely to fail.
 *
 * <p>This class turns that around. While the tunnel is up and the user is doing nothing in
 * particular, a few of the endpoints next in line are probed quietly in the background. When the
 * live one dies, the successor is not the best-scoring guess — it is an endpoint that answered a
 * minute ago. The swap becomes a decision that was already made.
 *
 * <p>Three rules keep it from doing harm, which matters more than the speed it buys:
 *
 * <ul>
 *   <li><b>It probes the way the engine dials.</b> If this network only reaches endpoints through
 *       the carrier, a direct probe fails on every healthy server and would bench the entire pool
 *       one pass at a time — the pool would end up emptiest on the networks where it is needed
 *       most. The caller supplies a probe matching the route the engine has already proved.
 *   <li><b>It does nothing when there is nothing to gain.</b> A pass is skipped entirely while
 *       enough successors already have recent evidence behind them.
 *   <li><b>It never touches the live endpoint.</b> The connection in use is not a candidate, is
 *       never probed, and cannot be benched by a pass.
 * </ul>
 *
 * <p>Android-free, so every decision here is checkable on a desktop JVM.
 */
final class StandbyProber {

    /** How long after connecting the first pass waits, so it never competes with startup. */
    static final long SETTLE_MS = 45_000L;

    /** How often a pass may run. */
    static final long INTERVAL_MS = 4L * 60L * 1000L;

    /** How many endpoints one pass probes. Small on purpose: this runs on someone's battery. */
    static final int BATCH = 6;

    /** How recent a success has to be to count as a warm successor. */
    static final long WARM_FOR_MS = 6L * 60L * 1000L;

    /** How many warm successors are enough to skip a pass. */
    static final int ENOUGH_WARM = 3;

    static final int TIMEOUT_MS = 2_500;
    static final int PARALLELISM = 6;

    private StandbyProber() { }

    /**
     * Whether a pass should run now.
     *
     * @param connectedAt when the live tunnel came up
     * @param lastPassAt  when a pass last ran, or 0 if none has
     * @param warm        how many successors already have recent evidence
     */
    static boolean due(long connectedAt, long lastPassAt, int warm, long now) {
        if (now - connectedAt < SETTLE_MS) return false;
        if (warm >= ENOUGH_WARM) return false;
        if (lastPassAt <= 0) return true;
        return now - lastPassAt >= INTERVAL_MS;
    }

    /**
     * How many endpoints other than the live one have answered recently enough to be trusted as a
     * successor without probing them again.
     */
    static int warmCount(EndpointPool pool, String country, String liveKey, long now) {
        if (pool == null) return 0;
        int warm = 0;
        for (EndpointPool.Entry entry : pool.rankedFor(country, now)) {
            if (isLive(entry, liveKey)) continue;
            if (entry.score(now) < 0) continue;
            if (entry.lastSuccessAt > 0 && now - entry.lastSuccessAt <= WARM_FOR_MS) warm++;
        }
        return warm;
    }

    /**
     * The endpoints worth probing this pass: the ones nearest the front of the queue that are not
     * live, not benched, and not already warm.
     *
     * <p>Deliberately the ones next in line rather than the ones we know least about. The point is
     * not to survey the pool, it is to have the specific endpoint that would be dialled next
     * already proved.
     */
    static List<ProxyConfig> candidates(EndpointPool pool, String country, String liveKey,
                                        long now, int batch) {
        List<ProxyConfig> out = new ArrayList<>();
        if (pool == null || batch <= 0) return out;
        // One per host. The pools publish the same server several times over with different
        // credentials, and without this a batch of six regularly spends three of its slots on one
        // address - which, if that address is dead, is half the pass learning the same thing.
        Set<String> hosts = new HashSet<>();
        for (EndpointPool.Entry entry : pool.rankedFor(country, now)) {
            if (out.size() >= batch) break;
            if (isLive(entry, liveKey)) continue;
            if (entry.score(now) < 0) continue;                      // benched
            if (!XrayConfig.supports(entry.config)) continue;
            if (entry.lastSuccessAt > 0 && now - entry.lastSuccessAt <= WARM_FOR_MS) continue;
            if (!hosts.add(entry.config.host.toLowerCase(Locale.US))) continue;
            out.add(entry.config);
        }
        return Collections.unmodifiableList(out);
    }

    private static boolean isLive(EndpointPool.Entry entry, String liveKey) {
        return liveKey != null && liveKey.equals(entry.config.key());
    }
}
