package com.firstham.aethergui;

/**
 * Decides how long the service keeps trying to revive a core that died under a live tunnel.
 *
 * <p>The problem this replaces. Recovery used to be bounded by a count alone - five attempts, each
 * allowed the same 120 second wait as a first connection, on top of a backoff that doubled to 20
 * seconds. Nothing capped the total, so a network that was simply down could hold the app on
 * "reconnecting" for roughly eleven minutes. That is the worst possible failure to hand someone:
 * the interface stays attached the whole time, so their traffic goes nowhere while the app reports
 * that it is working on it. Users read it as a frozen app, and they are close enough to right.
 *
 * <p>Two things changed. Recovery is now bounded by a clock as well as a count, and a retry gets a
 * much shorter window than a first connection does - a first connection may have to scan for an
 * edge and negotiate from cold, while a retry is re-running a configuration that worked minutes
 * ago. If it cannot come back inside that window, something has changed that waiting will not fix,
 * and failing quickly lets the kill switch and the user's own judgement take over.
 *
 * <p>Free of Android imports, so the arithmetic can be checked on a desktop JVM.
 */
final class ReconnectPolicy {

    /** Attempts allowed before giving up, unchanged from the behaviour this replaces. */
    static final int MAX_ATTEMPTS = 5;

    /**
     * Total wall clock allowed for the whole recovery sequence. Chosen so the worst case is a
     * minute and a half of honest "reconnecting" rather than eleven minutes of apparent hang.
     */
    static final long BUDGET_MS = 90_000L;

    /**
     * How long one retry may wait for the core to open its listener. Far shorter than a first
     * connection's allowance, for the reason given above.
     */
    static final long SOCKS_TIMEOUT_MS = 30_000L;

    /** Backoff ceiling. */
    static final long MAX_BACKOFF_MS = 8_000L;

    private static final long BASE_BACKOFF_MS = 1_500L;

    private ReconnectPolicy() { }

    /**
     * Backoff before a given attempt, doubling from {@link #BASE_BACKOFF_MS} and capped.
     *
     * @param attempt 1 for the first retry
     */
    static long backoffMs(int attempt) {
        if (attempt <= 1) return BASE_BACKOFF_MS;
        int shift = Math.min(attempt - 1, 20);
        return Math.min(MAX_BACKOFF_MS, BASE_BACKOFF_MS << shift);
    }

    /**
     * Whether recovery should stop. True once the attempt count is spent or the clock has run out.
     *
     * @param attempt   number of the attempt about to be made, 1 for the first
     * @param elapsedMs milliseconds since recovery began
     */
    static boolean exhausted(int attempt, long elapsedMs) {
        return attempt > MAX_ATTEMPTS || elapsedMs >= BUDGET_MS;
    }

    /**
     * How long the next attempt may take, so a retry cannot overrun the budget and leave the user
     * staring at a dead tunnel past the point where the app promised to give up.
     */
    static long remainingTimeoutMs(long elapsedMs) {
        long left = BUDGET_MS - elapsedMs;
        if (left <= 0) return 0;
        return Math.min(SOCKS_TIMEOUT_MS, left);
    }

    /**
     * Whether a core that ran this long counts as having genuinely recovered, which resets the
     * attempt count. A core that survives a minute came back; one that dies immediately did not.
     */
    static boolean recovered(long upMs) {
        return upMs >= 60_000L;
    }
}
