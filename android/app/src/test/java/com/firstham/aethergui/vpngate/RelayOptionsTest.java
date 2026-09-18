package com.firstham.aethergui.vpngate;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Relay builds its tunnel from a profile rather than from the VpnService builder the other three
 * share, so these rules are written twice in the app. If the two ever disagree, one setting quietly
 * means two different things depending on which card is lit - which is the bug worth guarding.
 */
public final class RelayOptionsTest {

    private static final String[] DNS = {"1.1.1.1", "1.0.0.1"};
    private static final String SELF = "io.github.amirmahdavi2023.panther";

    private static RelayOptions of(int routing, String apps) {
        return RelayOptions.of(routing, apps, SELF, "1500", false, true, DNS);
    }

    @Test public void withoutASplitEverythingButPantherGoesThroughTheTunnel() {
        RelayOptions options = of(RelayOptions.ROUTING_DEFAULT, "");
        assertTrue(options.appsAreExcluded);
        assertEquals(1, options.apps.size());
        assertTrue(options.apps.contains(SELF));

        // "Full" routing differs in which addresses are routed, not in which apps are.
        assertTrue(of(RelayOptions.ROUTING_FULL, "").apps.contains(SELF));
    }

    @Test public void includeCarriesOnlyTheChosenAppsAndNeverAddsPanther() {
        RelayOptions options = of(RelayOptions.ROUTING_INCLUDE, "com.a, com.b\ncom.c");
        assertFalse(options.appsAreExcluded);
        assertEquals(3, options.apps.size());
        assertTrue(options.apps.contains("com.a"));
        assertTrue(options.apps.contains("com.c"));
        assertFalse(options.apps.contains(SELF));
    }

    @Test public void excludeKeepsTheChosenAppsAndPantherOutOfTheTunnel() {
        RelayOptions options = of(RelayOptions.ROUTING_EXCLUDE, "com.a,com.b");
        assertTrue(options.appsAreExcluded);
        assertEquals(3, options.apps.size());
        assertTrue(options.apps.contains("com.a"));
        assertTrue(options.apps.contains(SELF));
    }

    @Test public void anIncludeListThatIsEmptyIsNotTakenLiterally() {
        // Applied as written it would mean "tunnel nothing", which reads as a broken VPN rather
        // than as the setting it came from.
        for (String empty : new String[]{"", "   ", ",,\n", SELF}) {
            RelayOptions options = of(RelayOptions.ROUTING_INCLUDE, empty);
            assertTrue(empty, options.appsAreExcluded);
            assertTrue(empty, options.apps.contains(SELF));
        }
    }

    @Test public void packageNamesKeepTheirCase() {
        assertTrue(of(RelayOptions.ROUTING_INCLUDE, "com.Example.App").apps.contains("com.Example.App"));
    }

    @Test public void theMtuIsClampedTheSameWayTheOtherEnginesClampIt() {
        assertEquals(1500, RelayOptions.clampMtu("1500"));
        assertEquals(1400, RelayOptions.clampMtu(" 1400 "));
        assertEquals(1280, RelayOptions.clampMtu("900"));
        assertEquals(9000, RelayOptions.clampMtu("99999"));
        assertEquals(1500, RelayOptions.clampMtu("not a number"));
        assertEquals(1500, RelayOptions.clampMtu(""));
    }

    @Test public void dnsFollowsTheLeakProtectionSwitch() {
        assertEquals(2, RelayOptions.of(0, "", SELF, "1500", false, true, DNS).dns.length);
        assertEquals(0, RelayOptions.of(0, "", SELF, "1500", false, false, DNS).dns.length);
    }

    @Test public void theKillSwitchIsCarriedThrough() {
        assertTrue(RelayOptions.of(0, "", SELF, "1500", true, true, DNS).killSwitch);
        assertFalse(RelayOptions.of(0, "", SELF, "1500", false, true, DNS).killSwitch);
    }

    @Test(expected = UnsupportedOperationException.class)
    public void theAppListCannotBeEditedAfterTheFact() {
        of(RelayOptions.ROUTING_EXCLUDE, "com.a").apps.add("com.b");
    }
}
