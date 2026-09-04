package com.firstham.aethergui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Checks for the per-network memory of which dial route works. */
public class NetworkMemoryTest {

    @Test public void wifiIsOneNetwork() {
        assertEquals("wifi", NetworkMemory.key(false, null));
        assertEquals("wifi", NetworkMemory.key(false, "anything"));
    }

    @Test public void mobileNetworksAreToldApartByOperator() {
        assertEquals("cell:mci", NetworkMemory.key(true, "MCI"));
        assertEquals("cell:mci", NetworkMemory.key(true, "  mci  "));
        assertFalse(NetworkMemory.key(true, "MCI").equals(NetworkMemory.key(true, "Irancell")));
    }

    /** The key goes into a tab-separated file, so anything that could break it is dropped. */
    @Test public void keysCannotBreakTheFileFormat() {
        String key = NetworkMemory.key(true, "we\tird\nname");
        assertFalse(key.contains("\t"));
        assertFalse(key.contains("\n"));
        assertEquals(NetworkMemory.UNKNOWN, NetworkMemory.key(true, ""));
        assertEquals(NetworkMemory.UNKNOWN, NetworkMemory.key(true, "!!!"));
        assertEquals(NetworkMemory.UNKNOWN, NetworkMemory.key(true, null));
    }

    @Test public void anUnseenNetworkHasNoAnswer() {
        NetworkMemory memory = new NetworkMemory();
        assertNull(memory.chainedOn("wifi"));
        assertNull(memory.chainedOn(null));
    }

    /** An unseen network falls back to what the app already believed, not to a guess. */
    @Test public void fallsBackToTheCallersOwnBelief() {
        NetworkMemory memory = new NetworkMemory();
        assertTrue(memory.preferChainedOn("wifi", true));
        assertFalse(memory.preferChainedOn("wifi", false));
        memory.remember("wifi", false);
        assertFalse(memory.preferChainedOn("wifi", true));
    }

    /** The whole point: two networks hold two different answers at the same time. */
    @Test public void twoNetworksKeepTwoAnswers() {
        NetworkMemory memory = new NetworkMemory();
        memory.remember("wifi", false);
        memory.remember("cell:mci", true);
        assertFalse(memory.preferChainedOn("wifi", true));
        assertTrue(memory.preferChainedOn("cell:mci", false));
    }

    @Test public void rememberingAgainReplacesTheAnswer() {
        NetworkMemory memory = new NetworkMemory();
        memory.remember("wifi", true);
        memory.remember("wifi", false);
        assertEquals(1, memory.size());
        assertFalse(memory.preferChainedOn("wifi", true));
    }

    @Test public void ignoresAnEmptyKey() {
        NetworkMemory memory = new NetworkMemory();
        memory.remember(null, true);
        memory.remember("", true);
        assertEquals(0, memory.size());
    }

    /** Oldest out first, so a phone that sees many networks does not grow a file forever. */
    @Test public void dropsTheOldestNetworkPastTheCap() {
        NetworkMemory memory = new NetworkMemory();
        for (int i = 0; i < NetworkMemory.KEEP + 3; i++) memory.remember("cell:n" + i, i % 2 == 0);
        assertEquals(NetworkMemory.KEEP, memory.size());
        assertNull(memory.chainedOn("cell:n0"));
        assertEquals(Boolean.valueOf(true),
                memory.chainedOn("cell:n" + (NetworkMemory.KEEP + 2)));
    }

    /** Touching a network again makes it the newest, so a network in daily use is never dropped. */
    @Test public void reusingANetworkKeepsItAlive() {
        NetworkMemory memory = new NetworkMemory();
        memory.remember("wifi", false);
        for (int i = 0; i < NetworkMemory.KEEP - 1; i++) memory.remember("cell:n" + i, true);
        memory.remember("wifi", false);
        memory.remember("cell:late", true);
        assertEquals(Boolean.valueOf(false), memory.chainedOn("wifi"));
    }

    @Test public void roundTripsThroughStorage() {
        NetworkMemory memory = new NetworkMemory();
        memory.remember("wifi", false);
        memory.remember("cell:irancell", true);
        NetworkMemory read = NetworkMemory.deserialise(memory.serialise());
        assertEquals(2, read.size());
        assertEquals(Boolean.valueOf(false), read.chainedOn("wifi"));
        assertEquals(Boolean.valueOf(true), read.chainedOn("cell:irancell"));
        assertEquals(memory.networks(), read.networks());
    }

    /** A corrupt file costs one wrong first attempt at worst, never an exception. */
    @Test public void readingSurvivesRubbish() {
        assertEquals(0, NetworkMemory.deserialise(null).size());
        assertEquals(0, NetworkMemory.deserialise("").size());
        assertEquals(0, NetworkMemory.deserialise("no tab here\n").size());
        assertEquals(0, NetworkMemory.deserialise("wifi\tmaybe\n").size());
        assertEquals(0, NetworkMemory.deserialise("\t1\n").size());
        assertEquals(1, NetworkMemory.deserialise("junk\nwifi\t1\n\n").size());
    }

    public static void main(String[] args) {
        int failures = 0;
        NetworkMemoryTest suite = new NetworkMemoryTest();
        for (java.lang.reflect.Method method : NetworkMemoryTest.class.getDeclaredMethods()) {
            if (method.getAnnotation(Test.class) == null) continue;
            try {
                method.invoke(suite);
                System.out.println("ok   " + method.getName());
            } catch (Exception failed) {
                failures++;
                System.out.println("FAIL " + method.getName() + ": " + failed.getCause());
            }
        }
        System.out.println(failures == 0 ? "all passed" : failures + " failed");
    }
}
