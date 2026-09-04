package com.firstham.aethergui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Run with: java -cp <classes> com.firstham.aethergui.ConfigSourcesTest */
public final class ConfigSourcesTest {

    private static int checks = 0;
    private static int failures = 0;

    private static void check(boolean ok, String what) {
        checks++;
        if (!ok) { failures++; System.out.println("  FAIL: " + what); }
    }

    private static final String[][] THREE = {
            {"host.a", "/a.txt", "A"},
            {"host.b", "/b.txt", "B"},
            {"host.c", "/c.txt", "C"},
    };

    /**
     * CI runs this. Without it these checks only ever ran by hand, because a class with a main
     * method and no test annotation is invisible to the unit test task - which is exactly how a
     * suite quietly stops protecting anything.
     */
    @Test public void everyCheckPasses() {
        int before = failures;
        runAllChecks();
        assertEquals("config source checks failed", before, failures);
        assertTrue("No checks ran", checks > 0);
    }

    static void runAllChecks() {
        mergesAcrossSources();
        aDeadSourceCostsOnlyItsOwnEntries();
        everySourceDownStillReturnsSomethingUsable();
        aThrowingFetcherIsNotFatal();
        anEmptyOrJunkDocumentCountsAsAFailure();
        duplicatesAcrossSourcesCollapse();
        theLimitIsRespected();
        base64SourcesWork();
        shortlistSpreadsAcrossProtocols();
        shortlistHandlesLopsidedAndTinyPools();
        theShippedSourceListIsWellFormed();

        System.out.println((failures == 0 ? "ALL PASS" : "FAILURES") + " — " + checks + " checks, " + failures + " failed");
    }

    private static ConfigSources.Fetcher fetcherOf(Map<String, String> bodies) {
        return (host, path) -> bodies.get(host + path);
    }

    private static void mergesAcrossSources() {
        Map<String, String> bodies = new HashMap<>();
        bodies.put("host.a/a.txt", "vless://a@1.1.1.1:443#A");
        bodies.put("host.b/b.txt", "hysteria2://p@2.2.2.2:8443#B");
        bodies.put("host.c/c.txt", "trojan://p@3.3.3.3:443#C");

        ConfigSources.Refresh refresh = ConfigSources.refresh(fetcherOf(bodies), THREE, 400);
        check(refresh.configs.size() == 3, "all three sources contributed");
        check(refresh.succeeded.size() == 3 && refresh.failed.isEmpty(), "all three counted as successes");
        check(refresh.configs.get(0).host.equals("1.1.1.1"), "source order is preserved");
    }

    private static void aDeadSourceCostsOnlyItsOwnEntries() {
        Map<String, String> bodies = new HashMap<>();
        bodies.put("host.a/a.txt", "vless://a@1.1.1.1:443#A");
        // host.b is missing entirely, as a renamed or deleted repo would be
        bodies.put("host.c/c.txt", "trojan://p@3.3.3.3:443#C");

        ConfigSources.Refresh refresh = ConfigSources.refresh(fetcherOf(bodies), THREE, 400);
        check(refresh.configs.size() == 2, "the surviving sources still contribute");
        check(refresh.failed.size() == 1 && refresh.failed.get(0).startsWith("B"), "the dead one is named");
        check(refresh.summary().contains("failed"), "the summary says what went wrong");
    }

    private static void everySourceDownStillReturnsSomethingUsable() {
        ConfigSources.Refresh refresh = ConfigSources.refresh(fetcherOf(new HashMap<>()), THREE, 400);
        check(refresh.isEmpty(), "nothing came back");
        check(refresh.failed.size() == 3, "all three are reported as failed");
        check(refresh.configs != null, "the caller gets an empty list, never null");
        // This is the case that matters: the caller must be able to fall back to the saved pool.
        check(refresh.summary().length() > 0, "there is still something to log");
    }

    private static void aThrowingFetcherIsNotFatal() {
        ConfigSources.Fetcher explodes = (host, path) -> {
            if (host.equals("host.b")) throw new IllegalStateException("connection reset");
            return "vless://x@9.9.9.9:443#X";
        };
        ConfigSources.Refresh refresh = ConfigSources.refresh(explodes, THREE, 400);
        check(refresh.succeeded.size() == 2, "the two working sources still land");
        check(refresh.failed.size() == 1, "the throwing source is recorded as a failure");
        check(refresh.configs.size() == 1, "the duplicate body across two sources collapses to one");
    }

    private static void anEmptyOrJunkDocumentCountsAsAFailure() {
        Map<String, String> bodies = new HashMap<>();
        bodies.put("host.a/a.txt", "");
        bodies.put("host.b/b.txt", "<html><body>404 not found</body></html>");
        bodies.put("host.c/c.txt", "vless://a@1.1.1.1:443#C");
        ConfigSources.Refresh refresh = ConfigSources.refresh(fetcherOf(bodies), THREE, 400);
        check(refresh.configs.size() == 1, "only the real document contributed");
        check(refresh.failed.size() == 2, "an empty body and an html error page both count as failures");
        check(refresh.failed.get(1).contains("unusable"), "a reachable but useless source is labelled");
    }

    private static void duplicatesAcrossSourcesCollapse() {
        Map<String, String> bodies = new HashMap<>();
        // The same servers really do appear in several pools at once.
        bodies.put("host.a/a.txt", "vless://a@1.1.1.1:443#FromA\nvless://b@2.2.2.2:443#AlsoA");
        bodies.put("host.b/b.txt", "vless://a@1.1.1.1:443#FromB\nvless://c@3.3.3.3:443#OnlyB");
        bodies.put("host.c/c.txt", "vless://a@1.1.1.1:443#FromC");

        ConfigSources.Refresh refresh = ConfigSources.refresh(fetcherOf(bodies), THREE, 400);
        check(refresh.configs.size() == 3, "the shared server is counted once");
        check(refresh.configs.get(0).label.equals("FromA"), "the first source's copy is the one kept");
    }

    private static void theLimitIsRespected() {
        StringBuilder many = new StringBuilder();
        for (int i = 1; i <= 300; i++) many.append("vless://u").append(i).append("@10.0.0.").append(i % 250 + 1).append(":").append(1000 + i).append("#N\n");
        Map<String, String> bodies = new HashMap<>();
        bodies.put("host.a/a.txt", many.toString());
        bodies.put("host.b/b.txt", many.toString());
        ConfigSources.Refresh refresh = ConfigSources.refresh(fetcherOf(bodies), THREE, 50);
        check(refresh.configs.size() == 50, "the candidate limit is enforced");
    }

    private static void base64SourcesWork() {
        String plain = "vless://a@1.1.1.1:443#A\nhysteria2://p@2.2.2.2:8443#B";
        Map<String, String> bodies = new HashMap<>();
        bodies.put("host.a/a.txt", Base64.getEncoder().encodeToString(plain.getBytes(StandardCharsets.UTF_8)));
        ConfigSources.Refresh refresh = ConfigSources.refresh(fetcherOf(bodies), THREE, 400);
        check(refresh.configs.size() == 2, "a base64 subscription source is unwrapped");
    }

    private static void shortlistSpreadsAcrossProtocols() {
        List<ProxyConfig> configs = new ArrayList<>();
        for (int i = 1; i <= 40; i++) configs.add(ProxyConfig.parse("vless://u" + i + "@10.0.1." + i + ":443?security=reality#R"));
        for (int i = 1; i <= 40; i++) configs.add(ProxyConfig.parse("hysteria2://p" + i + "@10.0.2." + i + ":8443#H"));
        for (int i = 1; i <= 10; i++) configs.add(ProxyConfig.parse("tuic://u" + i + ":p@10.0.3." + i + ":2053#T"));
        for (int i = 1; i <= 40; i++) configs.add(ProxyConfig.parse("trojan://p" + i + "@10.0.4." + i + ":443#O"));

        List<ProxyConfig> shortlist = ConfigSources.shortlist(configs, 20);
        check(shortlist.size() == 20, "the budget is filled");

        int reality = 0, hysteria = 0, tuic = 0, other = 0;
        for (ProxyConfig config : shortlist) {
            if (config.isReality()) reality++;
            else if ("hysteria2".equals(config.protocol)) hysteria++;
            else if ("tuic".equals(config.protocol)) tuic++;
            else other++;
        }
        check(reality > 0 && hysteria > 0 && tuic > 0 && other > 0, "every protocol family is represented");
        check(reality <= 8 && hysteria <= 8 && other <= 8, "no single protocol eats the budget");
    }

    private static void shortlistHandlesLopsidedAndTinyPools() {
        List<ProxyConfig> onlyVless = new ArrayList<>();
        for (int i = 1; i <= 5; i++) onlyVless.add(ProxyConfig.parse("vless://u" + i + "@10.0.5." + i + ":443#V"));
        List<ProxyConfig> shortlist = ConfigSources.shortlist(onlyVless, 20);
        check(shortlist.size() == 5, "a single-protocol pool still yields everything it has");

        check(ConfigSources.shortlist(new ArrayList<>(), 20).isEmpty(), "an empty pool yields nothing");
        check(ConfigSources.shortlist(null, 20).isEmpty(), "a null pool is safe");
        check(ConfigSources.shortlist(onlyVless, 0).isEmpty(), "a zero budget yields nothing");
        check(ConfigSources.shortlist(onlyVless, 2).size() == 2, "a small budget is respected");
    }

    /** The shipped list is data, so a typo in it would be a silent runtime failure. */
    private static void theShippedSourceListIsWellFormed() {
        check(ConfigSources.SOURCES.length >= 3, "several sources are configured, not just one");
        for (String[] source : ConfigSources.SOURCES) {
            check(source.length == 3, "each source has host, path and label");
            check(!source[0].isEmpty() && !source[0].contains("/"), "the host is a bare hostname: " + source[0]);
            check(source[1].startsWith("/"), "the path is absolute: " + source[1]);
            check(!source[2].isEmpty(), "the source has a label for logging");
        }
    }

    /**
     * Standalone entry point, for running these checks without an Android toolchain around.
     * The exit code lives here and not in runAllChecks, because a System.exit inside a unit
     * test kills the test JVM and turns a clear failure report into an opaque crash.
     */

    /** Picking a country reads that country's own published list, not the general pools. */
    @Test public void countryRefreshReadsThatCountrysList() {
        final java.util.List<String> asked = new java.util.ArrayList<>();
        ConfigSources.Fetcher fetcher = new ConfigSources.Fetcher() {
            @Override public String fetch(String host, String path) {
                asked.add(host + path);
                return "vless://11111111-2222-3333-4444-555555555555@jp1.example.com:443"
                        + "?security=tls#\uD83C\uDDEF\uD83C\uDDF5 Japan-1";
            }
        };
        ConfigSources.Refresh refresh = ConfigSources.refreshCountry(fetcher, "jp", 50);
        assertEquals(1, asked.size());
        assertTrue(asked.get(0), asked.get(0).endsWith("/countries/jp.txt"));
        assertEquals(1, refresh.configs.size());
        assertEquals("JP", StealthRegions.countryOf(refresh.configs.get(0)));
        assertEquals(1, refresh.succeeded.size());
    }

    /** A country we do not offer must cost nothing at all - no fetch, no 404, no exception. */
    @Test public void countryRefreshRefusesACountryWeDoNotOffer() {
        ConfigSources.Fetcher exploding = new ConfigSources.Fetcher() {
            @Override public String fetch(String host, String path) {
                throw new AssertionError("should not have fetched " + host + path);
            }
        };
        assertTrue(ConfigSources.refreshCountry(exploding, "ZZ", 50).isEmpty());
        assertTrue(ConfigSources.refreshCountry(exploding, "", 50).isEmpty());
        assertTrue(ConfigSources.refreshCountry(exploding, null, 50).isEmpty());
    }

    /** A dead country list leaves the caller with an empty refresh, never an exception. */
    @Test public void countryRefreshSurvivesADeadList() {
        ConfigSources.Fetcher dead = new ConfigSources.Fetcher() {
            @Override public String fetch(String host, String path) throws Exception {
                throw new java.io.IOException("unreachable");
            }
        };
        ConfigSources.Refresh refresh = ConfigSources.refreshCountry(dead, "NL", 50);
        assertTrue(refresh.isEmpty());
        assertEquals(1, refresh.failed.size());
    }

    public static void main(String[] args) {
        runAllChecks();
        if (failures > 0) System.exit(1);
    }
}
