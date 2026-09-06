package com.firstham.aethergui;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Checks the diagnostic filter against real lines taken from a device log.
 *
 * <p>The risk with any log filter is that it grows to swallow the thing you needed. The lines
 * below are copied from an actual run: the refusal that should be suppressed, and beside it the
 * port-forward rejection, the fetch failure and the location error that were in the same log and
 * all matter. Those must survive, or the next failure becomes invisible.
 */
public final class GlobalNoiseTest {

    private static int checks = 0;
    private static int failures = 0;

    private static void check(boolean ok, String what) {
        checks++;
        if (!ok) { failures++; System.out.println("  FAIL: " + what); }
    }

    @Test
    public void suppressesOnlyTheRepeatedRefusal() {
        runChecks();
        assertTrue("diagnostic filter", failures == 0);
    }

    private static void runChecks() {
        // Verbatim from a device log.
        String refusal = "SOCKS proxy accept error: socks5ReadCommand: "
                + "SOCKS message field command was 0x03, not 0x01";
        check(GlobalNoise.isRepeatedUdpRefusal(refusal), "the UDP refusal is recognised");
        check(GlobalNoise.isRepeatedUdpRefusal("{\"message\":\"" + refusal + "\"}"),
                "the refusal is recognised when wrapped in the engine's JSON");

        // Everything below was in the same log and is a real signal.
        check(!GlobalNoise.isRepeatedUdpRefusal(
                        "ssh: rejected: administratively prohibited (administratively prohibited)"),
                "port-forward rejections survive");
        check(!GlobalNoise.isRepeatedUdpRefusal("tunneled DSL fetch failed: missing public key"),
                "fetch failures survive");
        check(!GlobalNoise.isRepeatedUdpRefusal(
                        "Location provider speed.cloudflare.com failed: HTTP 403"),
                "location errors survive");
        check(!GlobalNoise.isRepeatedUdpRefusal("Global engine exit region: NL"),
                "ordinary progress lines survive");
        check(!GlobalNoise.isRepeatedUdpRefusal("close tunnel ssh error: use of closed network connection"),
                "tunnel errors survive");
        check(!GlobalNoise.isRepeatedUdpRefusal(""), "an empty line is not treated as noise");
        check(!GlobalNoise.isRepeatedUdpRefusal(null), "a null line does not throw");

        // A CONNECT refusal would mean the engine is rejecting ordinary traffic, which is a
        // genuine fault and must never be filtered just because it looks similar.
        check(!GlobalNoise.isRepeatedUdpRefusal(
                        "SOCKS message field command was 0x01, not 0x03"),
                "the inverse message is not suppressed");

        check(GlobalNoise.summary(0) == null, "nothing is reported when nothing was suppressed");
        check(GlobalNoise.summary(-1) == null, "a negative count reports nothing");
        String one = GlobalNoise.summary(1);
        check(one != null && one.contains("1 UDP request") && !one.contains("requests"),
                "one refusal reads in the singular");
        String many = GlobalNoise.summary(22);
        check(many != null && many.contains("22 UDP requests"), "many refusals report the count");
        check(many != null && many.contains("QUIC"), "the summary says why it is harmless");
    }

    public static void main(String[] args) {
        runChecks();
        System.out.println("GlobalNoise: " + checks + " checks, " + failures + " failures");
        if (failures > 0) System.exit(1);
    }
}
