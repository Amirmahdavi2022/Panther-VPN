package com.firstham.aethergui.vpngate;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * What the phone has learned has to survive being written to a preference string and read back,
 * and it has to stop being believed eventually - a relay that worked once last spring leading
 * every connect forever is worse than no memory at all.
 */
public final class RelayMemoryTest {

    private static final long NOW = 1_700_000_000_000L;
    private static final long DAY = 24L * 60 * 60 * 1000;

    private static List<String> order(String stored, long now) {
        return new ArrayList<>(RelayMemory.parse(stored, now).keySet());
    }

    @Test public void aSuccessIsRememberedAndComesBackFirst() {
        String stored = RelayMemory.remember("", "vpn1@1.1.1.1", NOW);
        assertEquals(1, RelayMemory.parse(stored, NOW).size());

        stored = RelayMemory.remember(stored, "vpn2@2.2.2.2", NOW + 1000);
        assertEquals("vpn2@2.2.2.2", order(stored, NOW + 1000).get(0));
        assertEquals("vpn1@1.1.1.1", order(stored, NOW + 1000).get(1));
    }

    @Test public void connectingAgainMovesARelayBackToTheFrontWithoutDuplicatingIt() {
        String stored = RelayMemory.remember("", "a@1", NOW);
        stored = RelayMemory.remember(stored, "b@2", NOW + 1000);
        stored = RelayMemory.remember(stored, "a@1", NOW + 2000);

        List<String> keys = order(stored, NOW + 2000);
        assertEquals(2, keys.size());
        assertEquals("a@1", keys.get(0));
    }

    @Test public void aRelayStopsBeingBelievedOnceItIsOldEnough() {
        String stored = "old@1=" + (NOW - RelayMemory.TTL_MS - 1) + "\nfresh@2=" + NOW;
        List<String> keys = order(stored, NOW);
        assertEquals(1, keys.size());
        assertEquals("fresh@2", keys.get(0));
    }

    @Test public void theListCannotGrowWithoutBound() {
        String stored = "";
        for (int i = 0; i < RelayMemory.MAX_ENTRIES + 15; i++) {
            stored = RelayMemory.remember(stored, "relay" + i + "@" + i, NOW + i);
        }
        Map<String, Long> parsed = RelayMemory.parse(stored, NOW + 1000);
        assertEquals(RelayMemory.MAX_ENTRIES, parsed.size());
        // The newest survive, the oldest are the ones dropped.
        assertTrue(parsed.containsKey("relay" + (RelayMemory.MAX_ENTRIES + 14) + "@"
                + (RelayMemory.MAX_ENTRIES + 14)));
        assertFalse(parsed.containsKey("relay0@0"));
    }

    @Test public void aTimestampFromAheadOfTheClockStillAgesOut() {
        // A device whose clock was wrong when the entry was written, or is wrong now.
        String stored = "future@1=" + (NOW + 10 * DAY);
        // Read with an earlier clock it counts as current rather than as negative age.
        assertEquals(1, RelayMemory.parse(stored, NOW).size());
        // It is still the ordinary lifetime from the stamp itself, not forever.
        assertEquals(1, RelayMemory.parse(stored, NOW + 10 * DAY + RelayMemory.TTL_MS - DAY).size());
        assertTrue(RelayMemory.parse(stored, NOW + 10 * DAY + RelayMemory.TTL_MS + 1).isEmpty());
    }

    @Test public void rubbishInTheStoreIsIgnoredRatherThanThrown() {
        assertTrue(RelayMemory.parse(null, NOW).isEmpty());
        assertTrue(RelayMemory.parse("", NOW).isEmpty());
        assertTrue(RelayMemory.parse("no separator\n=1\nkey=notanumber\n", NOW).isEmpty());
        assertEquals("", RelayMemory.remember("", "   ", NOW));
        assertEquals("", RelayMemory.remember("", null, NOW));
    }
}
