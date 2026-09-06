package com.firstham.aethergui;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Checks that recovery cannot run long enough to look like a hang.
 *
 * <p>The headline check is {@link #worstCaseIsMinutesNotTens}, which walks the whole retry
 * sequence the way the service does and adds up the wall clock. The behaviour this replaced could
 * reach roughly eleven minutes, and nothing in the code said so - it was the product of a count, a
 * backoff and a timeout that were each reasonable on their own. A number that only appears when
 * three constants are multiplied together is exactly the kind that needs a test holding it down.
 */
public final class ReconnectPolicyTest {

    private static int checks = 0;
    private static int failures = 0;

    private static void check(boolean ok, String what) {
        checks++;
        if (!ok) { failures++; System.out.println("  FAIL: " + what); }
    }

    @Test
    public void worstCaseIsMinutesNotTens() {
        runBudgetChecks();
        assertTrue("recovery budget", failures == 0);
    }

    @Test
    public void backoffAndResetBehave() {
        runBackoffChecks();
        assertTrue("backoff and reset", failures == 0);
    }

    private static void runBudgetChecks() {
        // Walk the sequence exactly as monitorAether does: back off, then allow the attempt
        // whatever is left of the budget.
        long elapsed = 0;
        int attempt = 0;
        int made = 0;
        while (true) {
            attempt++;
            if (ReconnectPolicy.exhausted(attempt, elapsed)) break;
            elapsed += ReconnectPolicy.backoffMs(attempt);
            long timeout = ReconnectPolicy.remainingTimeoutMs(elapsed);
            if (timeout <= 0) break;
            elapsed += timeout;
            made++;
            if (made > 50) { check(false, "the retry loop terminates"); break; }
        }
        check(made > 0, "at least one recovery attempt is made");
        check(made <= ReconnectPolicy.MAX_ATTEMPTS, "no more attempts than the cap allows");
        check(elapsed <= 150_000L, "worst case stays inside two and a half minutes, was about 11");
        check(elapsed >= 30_000L, "recovery still gets a fair chance before giving up");

        // A single attempt can never be handed more time than the budget has left.
        check(ReconnectPolicy.remainingTimeoutMs(ReconnectPolicy.BUDGET_MS - 5_000L) == 5_000L,
                "the last attempt is trimmed to what is left");
        check(ReconnectPolicy.remainingTimeoutMs(ReconnectPolicy.BUDGET_MS) == 0,
                "no attempt is started once the budget is spent");
        check(ReconnectPolicy.remainingTimeoutMs(ReconnectPolicy.BUDGET_MS + 60_000L) == 0,
                "an overrun does not produce a negative timeout");
        check(ReconnectPolicy.remainingTimeoutMs(0) == ReconnectPolicy.SOCKS_TIMEOUT_MS,
                "a fresh attempt gets the full retry window");
        check(ReconnectPolicy.SOCKS_TIMEOUT_MS < 120_000L,
                "a retry gets less time than a first connection");
    }

    private static void runBackoffChecks() {
        check(ReconnectPolicy.exhausted(ReconnectPolicy.MAX_ATTEMPTS + 1, 0),
                "the attempt cap still ends recovery");
        check(ReconnectPolicy.exhausted(1, ReconnectPolicy.BUDGET_MS),
                "the clock ends recovery even on the first attempt");
        check(!ReconnectPolicy.exhausted(1, 0), "recovery starts");
        check(!ReconnectPolicy.exhausted(ReconnectPolicy.MAX_ATTEMPTS, ReconnectPolicy.BUDGET_MS - 1),
                "the last attempt inside the budget is allowed");

        long previous = 0;
        for (int attempt = 1; attempt <= 8; attempt++) {
            long backoff = ReconnectPolicy.backoffMs(attempt);
            check(backoff > 0, "backoff " + attempt + " is positive");
            check(backoff >= previous, "backoff does not shrink at attempt " + attempt);
            check(backoff <= ReconnectPolicy.MAX_BACKOFF_MS, "backoff " + attempt + " is capped");
            previous = backoff;
        }
        check(ReconnectPolicy.backoffMs(0) == ReconnectPolicy.backoffMs(1),
                "a nonsense attempt number does not produce a nonsense wait");
        check(ReconnectPolicy.backoffMs(64) == ReconnectPolicy.MAX_BACKOFF_MS,
                "a large attempt number cannot overflow the shift");

        check(ReconnectPolicy.recovered(60_000L), "a core that ran a minute counts as recovered");
        check(ReconnectPolicy.recovered(600_000L), "a long-lived core counts as recovered");
        check(!ReconnectPolicy.recovered(59_999L), "a core that died quickly does not reset the count");
        check(!ReconnectPolicy.recovered(0), "an immediate death does not reset the count");
    }

    public static void main(String[] args) {
        runBudgetChecks();
        runBackoffChecks();
        System.out.println("ReconnectPolicy: " + checks + " checks, " + failures + " failures");
        if (failures > 0) System.exit(1);
    }
}
