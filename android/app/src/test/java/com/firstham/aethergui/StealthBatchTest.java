package com.firstham.aethergui;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Exercises how one fan-out round is composed and what its results are allowed to mean.
 *
 * <p>Built from real URIs rather than mocks, because the parser is what decides whether an
 * endpoint is dialable at all and a stub would quietly agree with whatever the policy assumed.
 *
 * <p>Runs two ways on purpose. CI runs the {@code @Test} methods, and
 * {@code java -cp <classes> com.firstham.aethergui.StealthBatchTest} runs the same checks with a
 * printed summary.
 */
public class StealthBatchTest {

    private static int checks;
    private static int failures;

    private static void check(boolean ok, String what) {
        checks++;
        if (!ok) {
            failures++;
            System.out.println("FAIL " + what);
        }
    }

    private static final String VLESS_A =
            "vless://11111111-1111-1111-1111-111111111111@10.9.0.1:443"
                    + "?type=ws&security=tls&host=aa.example&path=%2Faa#A";
    private static final String VLESS_B =
            "vless://22222222-2222-2222-2222-222222222222@10.9.0.2:443"
                    + "?type=ws&security=tls&host=bb.example&path=%2Fbb#B";
    private static final String TROJAN_C =
            "trojan://secretpass@10.9.0.3:8443?type=tcp&security=tls&sni=cc.example#C";
    private static final String HYSTERIA_D =
            "hysteria2://pw@10.9.0.4:443?sni=dd.example#D";

    private static List<ProxyConfig> pool(String... uris) {
        List<ProxyConfig> out = new ArrayList<>();
        for (String uri : uris) out.add(ProxyConfig.parse(uri));
        return out;
    }

    private static Set<Integer> indices(int... values) {
        Set<Integer> out = new HashSet<>();
        for (int value : values) out.add(value);
        return out;
    }

    static void runCompositionChecks() {
        int[] three = { StealthPlan.MODE_DIRECT, StealthPlan.MODE_SPOOF, StealthPlan.MODE_CHAINED };

        List<XrayConfig.Attempt> round =
                StealthBatch.plan(pool(VLESS_A, VLESS_B, TROJAN_C), three, StealthBatch.MAX_ATTEMPTS);
        check(round.size() == 9, "three endpoints on three routes is nine attempts");

        // The point of the whole change: the best endpoint gets every route straight away, rather
        // than being spent on the remembered one while the others wait for it to fail three times.
        check(round.get(0).endpoint.key().equals(round.get(1).endpoint.key())
                        && round.get(1).endpoint.key().equals(round.get(2).endpoint.key()),
                "the best-scoring endpoint is tried every way at once");
        check(round.get(0).mode != round.get(1).mode && round.get(1).mode != round.get(2).mode,
                "its three attempts are three different routes");

        // Xray cannot dial hysteria2 at all, so it must never occupy a port.
        List<XrayConfig.Attempt> filtered =
                StealthBatch.plan(pool(HYSTERIA_D, VLESS_A), three, StealthBatch.MAX_ATTEMPTS);
        check(filtered.size() == 3, "an endpoint the core cannot dial takes no port");
        for (XrayConfig.Attempt attempt : filtered) {
            check(XrayConfig.supports(attempt.endpoint), "every attempt is dialable");
        }

        List<XrayConfig.Attempt> oneRoute =
                StealthBatch.plan(pool(VLESS_A, VLESS_B), new int[] { StealthPlan.MODE_DIRECT },
                        StealthBatch.MAX_ATTEMPTS);
        check(oneRoute.size() == 2, "one usable route means one attempt per endpoint");

        check(StealthBatch.plan(pool(VLESS_A), new int[0], 48).isEmpty(),
                "no routes means no round");
        check(StealthBatch.plan(null, three, 48).isEmpty(), "no candidates means no round");
        check(StealthBatch.plan(pool(VLESS_A), three, 0).isEmpty(), "a zero cap means no round");
    }

    static void runCapChecks() {
        int[] three = { StealthPlan.MODE_DIRECT, StealthPlan.MODE_SPOOF, StealthPlan.MODE_CHAINED };
        List<ProxyConfig> many = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            many.add(ProxyConfig.parse("vless://11111111-1111-1111-1111-111111111111@10.9.1."
                    + i + ":443?type=ws&security=tls&host=x.example&path=%2Fx#N" + i));
        }

        List<XrayConfig.Attempt> capped = StealthBatch.plan(many, three, 10);
        check(capped.size() == 9, "the cap drops whole endpoints, never part of one");
        check(capped.size() % three.length == 0, "every endpoint in a round keeps all its routes");

        List<XrayConfig.Attempt> standard = StealthBatch.plan(many, three);
        check(standard.size() <= StealthBatch.MAX_ATTEMPTS, "a round fits the port range");
        check(standard.size() == StealthBatch.MAX_CANDIDATES * three.length,
                "sixteen endpoints on three routes is exactly the port budget");

        check(StealthBatch.portFor(0) == StealthBatch.BASE_PORT, "the first attempt takes the base port");
        check(StealthBatch.portFor(StealthBatch.MAX_ATTEMPTS - 1)
                == StealthBatch.BASE_PORT + StealthBatch.MAX_ATTEMPTS - 1, "the last attempt fits");
        boolean refused = false;
        try {
            StealthBatch.portFor(StealthBatch.MAX_ATTEMPTS);
        } catch (IllegalArgumentException expected) {
            refused = true;
        }
        check(refused, "a port outside the round's range is refused, not wrapped");

        // 🚨 A round must never bind the port the live tunnel listens on.
        for (int i = 0; i < StealthBatch.MAX_ATTEMPTS; i++) {
            check(StealthBatch.portFor(i) != XrayConfig.SOCKS_PORT,
                    "a round never binds the live tunnel's port");
        }
    }

    static void runEvidenceChecks() {
        int[] three = { StealthPlan.MODE_DIRECT, StealthPlan.MODE_SPOOF, StealthPlan.MODE_CHAINED };
        List<XrayConfig.Attempt> round = StealthBatch.plan(pool(VLESS_A, VLESS_B, TROJAN_C), three,
                StealthBatch.MAX_ATTEMPTS);
        String keyA = round.get(0).endpoint.key();
        String keyB = round.get(3).endpoint.key();
        String keyC = round.get(6).endpoint.key();

        // One route answering clears the endpoint, even though its other two failed. On a filtered
        // network a direct failure is the expected outcome for a healthy server.
        Set<String> dead = StealthBatch.failedEverywhere(round, indices(5));
        check(!dead.contains(keyB), "an endpoint that held on one route is not a failure");
        check(dead.contains(keyA) && dead.contains(keyC), "the others are recorded once each");
        check(dead.size() == 2, "a failure is recorded per endpoint, not per attempt");

        check(StealthBatch.failedEverywhere(round, indices()).size() == 3,
                "a round where nothing answered fails all three");
        check(StealthBatch.failedEverywhere(round, indices(0, 3, 6)).isEmpty(),
                "a round where everything answered fails nothing");
        check(StealthBatch.failedEverywhere(null, indices(1)).isEmpty(), "no round, no verdict");

        // The defect this exists for: the carrier tunnel died mid-round on a real device and
        // healthy endpoints were benched for it.
        check(StealthBatch.shouldRecordFailures(true, true), "an intact round is evidence");
        check(!StealthBatch.shouldRecordFailures(true, false),
                "a round whose hop died measured the hop, not the servers");
        check(!StealthBatch.shouldRecordFailures(false, true),
                "a round that began without its hop is not evidence either");
    }

    static void runWinnerChecks() {
        check(StealthBatch.best(new long[] { -1, 420, -1, 190, 900 }) == 3, "the fastest answer wins");
        check(StealthBatch.best(new long[] { -1, -1, -1 }) == -1, "nothing answered, no winner");
        check(StealthBatch.best(new long[0]) == -1, "an empty round has no winner");
        check(StealthBatch.best(null) == -1, "no measurements, no winner");
        check(StealthBatch.best(new long[] { 0, 5 }) == 0, "a zero-latency answer still counts");
        check(StealthBatch.best(new long[] { 300, 300 }) == 0, "a tie goes to the better-scored one");
    }

    @Test
    public void aRoundTriesEveryRouteAtOnce() {
        runCompositionChecks();
        assertTrue("round composition", failures == 0);
    }

    @Test
    public void aRoundFitsItsPortBudget() {
        runCapChecks();
        assertTrue("round caps and ports", failures == 0);
    }

    @Test
    public void failuresAreEvidenceOnlyWhenTheRoundWasIntact() {
        runEvidenceChecks();
        assertTrue("round evidence", failures == 0);
    }

    @Test
    public void theFastestWorkingRouteWins() {
        runWinnerChecks();
        assertTrue("round winner", failures == 0);
    }

    public static void main(String[] args) {
        runCompositionChecks();
        runCapChecks();
        runEvidenceChecks();
        runWinnerChecks();
        System.out.println("StealthBatch");
        System.out.println("  " + checks + " checks, " + failures + " failures");
        if (failures > 0) System.exit(1);
    }
}
