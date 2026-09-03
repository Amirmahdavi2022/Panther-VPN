package com.firstham.aethergui;

import java.util.List;

/** Run with: java -cp <classes> com.firstham.aethergui.EndpointPoolTest */
public final class EndpointPoolTest {

    private static int checks = 0;
    private static int failures = 0;
    private static final long NOW = 1_760_000_000_000L;

    private static void check(boolean ok, String what) {
        checks++;
        if (!ok) { failures++; System.out.println("  FAIL: " + what); }
    }

    private static EndpointPool poolOf(String... uris) {
        EndpointPool pool = new EndpointPool();
        StringBuilder document = new StringBuilder();
        for (String uri : uris) document.append(uri).append('\n');
        pool.merge(ProxyConfig.parseDocument(document.toString()));
        return pool;
    }

    public static void main(String[] args) {
        mergeKeepsHistory();
        provenBeatsUnproven();
        oneLuckySuccessDoesNotOutrankAConsistentRecord();
        failureBenchesAndTheBenchExpires();
        repeatedFailuresBenchForLonger();
        nextAfterPicksAWarmAlternative();
        everythingBenchedIsHandledNotCrashed();
        latencyOnlyDecidesBetweenWorkingOnes();
        stalenessDecaysTheScore();
        pruneKeepsTheBestOnes();
        survivesASaveAndReload();
        corruptedSavedLinesCostOneEntryNotTheList();

        System.out.println((failures == 0 ? "ALL PASS" : "FAILURES") + " — " + checks + " checks, " + failures + " failed");
        if (failures > 0) System.exit(1);
    }

    private static void mergeKeepsHistory() {
        EndpointPool pool = poolOf("vless://a@1.1.1.1:443#A");
        pool.recordSuccess("vless|1.1.1.1|443|a", 120, NOW);
        // A later fetch re-publishes the same server; its record must not be wiped.
        pool.merge(ProxyConfig.parseDocument("vless://a@1.1.1.1:443#A renamed\nvless://b@2.2.2.2:443#B"));
        check(pool.size() == 2, "the new endpoint is added");
        check(pool.get("vless|1.1.1.1|443|a").successes == 1, "the existing endpoint keeps its history");
    }

    private static void provenBeatsUnproven() {
        EndpointPool pool = poolOf("vless://a@1.1.1.1:443#A", "vless://b@2.2.2.2:443#B");
        for (int i = 0; i < 5; i++) pool.recordSuccess("vless|1.1.1.1|443|a", 200, NOW);
        check(pool.best(NOW).config.host.equals("1.1.1.1"), "a proven endpoint wins over an untested one");

        EndpointPool bad = poolOf("vless://a@1.1.1.1:443#A", "vless://b@2.2.2.2:443#B");
        for (int i = 0; i < 5; i++) bad.recordFailure("vless|1.1.1.1|443|a", NOW - 3_600_000L);
        check(bad.best(NOW).config.host.equals("2.2.2.2"), "an untested endpoint beats a proven failure");
    }

    private static void oneLuckySuccessDoesNotOutrankAConsistentRecord() {
        EndpointPool pool = poolOf("vless://a@1.1.1.1:443#steady", "vless://b@2.2.2.2:443#lucky");
        for (int i = 0; i < 10; i++) pool.recordSuccess("vless|1.1.1.1|443|a", 300, NOW);
        pool.recordSuccess("vless|2.2.2.2|443|b", 40, NOW);
        check(pool.best(NOW).config.host.equals("1.1.1.1"),
                "ten successes at 300ms beat one success at 40ms");
    }

    private static void failureBenchesAndTheBenchExpires() {
        EndpointPool pool = poolOf("vless://a@1.1.1.1:443#A");
        pool.recordFailure("vless|1.1.1.1|443|a", NOW);
        check(pool.best(NOW) == null, "a just-failed endpoint is not offered");
        check(pool.get("vless|1.1.1.1|443|a") != null, "but it is benched, not deleted");
        check(pool.best(NOW + 24 * 3_600_000L) != null, "the bench expires so it can be retried later");
    }

    private static void repeatedFailuresBenchForLonger() {
        EndpointPool pool = poolOf("vless://a@1.1.1.1:443#A");
        pool.recordFailure("vless|1.1.1.1|443|a", NOW);
        long firstBench = pool.get("vless|1.1.1.1|443|a").penaltyUntil;
        for (int i = 0; i < 4; i++) pool.recordFailure("vless|1.1.1.1|443|a", NOW);
        long laterBench = pool.get("vless|1.1.1.1|443|a").penaltyUntil;
        check(laterBench > firstBench, "a repeat offender is benched for longer");
        check(laterBench - NOW <= 60 * 60_000L, "the bench is capped so nothing is exiled forever");
    }

    private static void nextAfterPicksAWarmAlternative() {
        EndpointPool pool = poolOf(
                "vless://a@1.1.1.1:443#A", "vless://b@2.2.2.2:443#B", "vless://c@3.3.3.3:443#C");
        for (int i = 0; i < 5; i++) pool.recordSuccess("vless|1.1.1.1|443|a", 100, NOW);
        for (int i = 0; i < 5; i++) pool.recordSuccess("vless|2.2.2.2|443|b", 150, NOW);

        EndpointPool.Entry live = pool.best(NOW);
        check(live.config.host.equals("1.1.1.1"), "the fastest proven endpoint is live");

        // The live one dies. The replacement must be decided without any network work.
        pool.recordFailure(live.config.key(), NOW);
        EndpointPool.Entry next = pool.nextAfter(live.config.key(), NOW);
        check(next != null, "there is a successor ready");
        check(next.config.host.equals("2.2.2.2"), "the successor is the next best proven one");
        check(!next.config.key().equals(live.config.key()), "the successor is never the dead one");
    }

    private static void everythingBenchedIsHandledNotCrashed() {
        EndpointPool pool = poolOf("vless://a@1.1.1.1:443#A", "vless://b@2.2.2.2:443#B");
        pool.recordFailure("vless|1.1.1.1|443|a", NOW);
        pool.recordFailure("vless|2.2.2.2|443|b", NOW);
        check(pool.best(NOW) == null, "an entirely benched pool reports nothing rather than a bad pick");
        check(pool.nextAfter("vless|1.1.1.1|443|a", NOW) == null, "and has no successor to offer");
        check(pool.ranked(NOW).size() == 2, "the endpoints are still there for when the bench expires");

        EndpointPool empty = new EndpointPool();
        check(empty.best(NOW) == null && empty.nextAfter("anything", NOW) == null,
                "an empty pool is safe to ask");
    }

    private static void latencyOnlyDecidesBetweenWorkingOnes() {
        EndpointPool pool = poolOf("vless://a@1.1.1.1:443#slow", "vless://b@2.2.2.2:443#fast");
        for (int i = 0; i < 5; i++) {
            pool.recordSuccess("vless|1.1.1.1|443|a", 900, NOW);
            pool.recordSuccess("vless|2.2.2.2|443|b", 90, NOW);
        }
        check(pool.best(NOW).config.host.equals("2.2.2.2"),
                "with equal reliability the faster one wins");
    }

    private static void stalenessDecaysTheScore() {
        EndpointPool pool = poolOf("vless://a@1.1.1.1:443#old", "vless://b@2.2.2.2:443#recent");
        for (int i = 0; i < 5; i++) pool.recordSuccess("vless|1.1.1.1|443|a", 100, NOW - 40L * 3_600_000L);
        for (int i = 0; i < 5; i++) pool.recordSuccess("vless|2.2.2.2|443|b", 100, NOW);
        check(pool.best(NOW).config.host.equals("2.2.2.2"), "recent evidence outranks stale evidence");
    }

    private static void pruneKeepsTheBestOnes() {
        StringBuilder document = new StringBuilder();
        for (int i = 1; i <= 60; i++) document.append("vless://u").append(i).append("@10.0.0.").append(i).append(":443#N").append(i).append('\n');
        EndpointPool pool = new EndpointPool();
        pool.merge(ProxyConfig.parseDocument(document.toString()));
        check(pool.size() == 60, "all sixty were taken in");

        pool.recordSuccess("vless|10.0.0.7|443|u7", 50, NOW);
        pool.recordSuccess("vless|10.0.0.7|443|u7", 50, NOW);
        pool.prune(NOW);
        check(pool.size() == EndpointPool.KEEP, "the pool is trimmed to the keep size");
        check(pool.get("vless|10.0.0.7|443|u7") != null, "the proven endpoint survived the trim");
    }

    private static void survivesASaveAndReload() {
        EndpointPool pool = poolOf(
                "vless://a@1.1.1.1:443?security=reality#A",
                "hysteria2://pw@2.2.2.2:8443#B");
        for (int i = 0; i < 3; i++) pool.recordSuccess("vless|1.1.1.1|443|a", 210, NOW);
        pool.recordFailure("hysteria2|2.2.2.2|8443|pw", NOW);

        EndpointPool reloaded = EndpointPool.deserialise(pool.serialise());
        check(reloaded.size() == 2, "both endpoints came back");
        EndpointPool.Entry restored = reloaded.get("vless|1.1.1.1|443|a");
        check(restored != null && restored.successes == 3, "the success count survived");
        check(restored.lastLatencyMillis == 210, "the latency survived");
        check(restored.config.isReality(), "the parsed config survived intact");

        // A bench describes the network we were on, not this one, so it must not come back.
        EndpointPool.Entry benched = reloaded.get("hysteria2|2.2.2.2|8443|pw");
        check(benched != null && benched.penaltyUntil == 0, "penalties do not survive a restart");
        check(reloaded.best(NOW) != null, "the reloaded pool is immediately usable offline");
    }

    private static void corruptedSavedLinesCostOneEntryNotTheList() {
        String saved = String.join("\n",
                "vless://a@1.1.1.1:443#A\t3\t0\t210\t" + NOW,
                "this line is not a uri at all\t1\t2\t3\t4",
                "vless://b@2.2.2.2:443#B\tnotanumber\tx\ty\tz",
                "",
                "hysteria2://pw@3.3.3.3:8443#C\t1\t0\t80\t" + NOW);
        EndpointPool pool = EndpointPool.deserialise(saved);
        check(pool.size() == 3, "the unparseable line is skipped, the rest survive");
        EndpointPool.Entry partial = pool.get("vless|2.2.2.2|443|b");
        check(partial != null && partial.successes == 0,
                "an entry with unreadable history is kept with its history dropped");
        List<EndpointPool.Entry> ranked = pool.ranked(NOW);
        check(ranked.size() == 3 && ranked.get(0) != null, "the reloaded pool still ranks");
    }
}
