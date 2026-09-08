package com.firstham.aethergui;

import static org.junit.Assert.assertTrue;

import java.util.Map;

import org.junit.Test;

/**
 * Checks the extra environment the Turbo core is launched with.
 *
 * <p>These guard failures that would never show up in a log. A stale timeout set on MASQUE is
 * ignored by the core, so a mistake there looks like it worked. The fragment flag on a UDP
 * transport is equally silent. And if the stale value were ever written in a form the core cannot
 * parse, the core falls back to its own ten seconds without complaining - which is the exact
 * behaviour this change exists to stop.
 *
 * <p>Runs two ways, like the rest of the suite: CI runs the {@code @Test} methods, and
 * {@code java -cp <classes> com.firstham.aethergui.CoreTuningTest} runs the same checks with a
 * count at the end.
 */
public final class CoreTuningTest {

    private static int checks = 0;
    private static int failures = 0;

    private static void check(boolean ok, String what) {
        checks++;
        if (!ok) { failures++; System.out.println("  FAIL: " + what); }
    }

    @Test
    public void staleTimeoutReachesTheProtocolsThatUseIt() {
        runStaleChecks();
        assertTrue("stale timeout placement", failures == 0);
    }

    @Test
    public void handshakeIsFragmentedOnlyOnTheTcpTransport() {
        runFragmentChecks();
        assertTrue("fragment placement", failures == 0);
    }

    @Test
    public void unknownAndMissingValuesAreSafe() {
        runToleranceChecks();
        assertTrue("tolerance", failures == 0);
    }

    private static void runStaleChecks() {
        check(CoreTuning.usesWireguardTunnel("wg"), "wg owns a wireguard tunnel");
        check(CoreTuning.usesWireguardTunnel("gool"), "gool is two wireguard tunnels");
        check(!CoreTuning.usesWireguardTunnel("masque"), "masque has no wireguard tunnel");

        check("30".equals(CoreTuning.environment("gool", "h3").get("AETHER_WG_STALE_SECS")),
                "gool gets the raised stale timeout");
        check("30".equals(CoreTuning.environment("wg", "h3").get("AETHER_WG_STALE_SECS")),
                "wg gets the raised stale timeout");
        check(!CoreTuning.environment("masque", "h3").containsKey("AETHER_WG_STALE_SECS"),
                "masque is not given a wireguard setting");

        // The core parses this with u64::parse and silently keeps its own default on failure,
        // so a value that is not plain digits would disable the fix without any sign of it.
        check(CoreTuning.WIREGUARD_STALE_SECONDS.matches("[0-9]+"),
                "the stale value is something the core can parse");
        check(Integer.parseInt(CoreTuning.WIREGUARD_STALE_SECONDS) > 10,
                "the stale value is longer than the core's own default of ten");
        check(Integer.parseInt(CoreTuning.WIREGUARD_STALE_SECONDS) <= 60,
                "a dead tunnel is still noticed within a minute");
    }

    private static void runFragmentChecks() {
        check(CoreTuning.fragmentsHandshake("masque", "h2"), "masque over h2 is a TCP handshake");
        check(!CoreTuning.fragmentsHandshake("masque", "h3"), "masque over h3 is QUIC, nothing to split");
        check(!CoreTuning.fragmentsHandshake("gool", "h2"), "gool never uses the h2 transport");
        check(!CoreTuning.fragmentsHandshake("wg", "h2"), "wg never uses the h2 transport");

        check("1".equals(CoreTuning.environment("masque", "h2").get("AETHER_MASQUE_H2_FRAGMENT")),
                "masque over h2 asks the core to split the client hello");
        check(!CoreTuning.environment("masque", "h3").containsKey("AETHER_MASQUE_H2_FRAGMENT"),
                "h3 is left alone");
        check(!CoreTuning.environment("gool", "h2").containsKey("AETHER_MASQUE_H2_FRAGMENT"),
                "wireguard protocols are left alone");

        // The core reads this with a truthy test over "1", "true", "yes", "on".
        check("1".equals(CoreTuning.environment("masque", "h2").get("AETHER_MASQUE_H2_FRAGMENT")),
                "the flag is a value the core counts as on");

        // Size and delay are deliberately not set, so the core keeps its own randomised ranges.
        Map<String, String> masque = CoreTuning.environment("masque", "h2");
        check(!masque.containsKey("AETHER_MASQUE_H2_FRAGMENT_SIZE"), "size is left to the core");
        check(!masque.containsKey("AETHER_MASQUE_H2_FRAGMENT_DELAY"), "delay is left to the core");
    }

    private static void runToleranceChecks() {
        check(CoreTuning.environment(null, null).isEmpty(), "no protocol means no extra environment");
        check(CoreTuning.environment("", "").isEmpty(), "empty values add nothing");
        check(CoreTuning.environment("something-else", "h2").isEmpty(), "an unknown protocol adds nothing");
        check(CoreTuning.environment("GOOL", "H3").containsKey("AETHER_WG_STALE_SECS"),
                "case does not change the decision");
        check(CoreTuning.environment(" masque ", " h2 ").containsKey("AETHER_MASQUE_H2_FRAGMENT"),
                "surrounding space does not change the decision");
        check(CoreTuning.environment("gool", null).containsKey("AETHER_WG_STALE_SECS"),
                "a missing transport still leaves the stale timeout in place");
    }

    public static void main(String[] args) {
        runStaleChecks();
        runFragmentChecks();
        runToleranceChecks();
        System.out.println(checks + " checks, " + failures + " failures");
        if (failures > 0) { throw new IllegalStateException(failures + " failures"); }
    }
}
