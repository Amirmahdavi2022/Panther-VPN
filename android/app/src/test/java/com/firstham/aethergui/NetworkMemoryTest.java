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
        assertNull(memory.routeOn("wifi"));
        assertNull(memory.routeOn(null));
    }

    /** An unseen network falls back to what the app already believed, not to a guess. */
    @Test public void fallsBackToTheCallersOwnBelief() {
        NetworkMemory memory = new NetworkMemory();
        assertEquals(StealthPlan.MODE_CHAINED,
                memory.preferredRouteOn("wifi", StealthPlan.MODE_CHAINED));
        assertEquals(StealthPlan.MODE_DIRECT,
                memory.preferredRouteOn("wifi", StealthPlan.MODE_DIRECT));
        memory.remember("wifi", StealthPlan.MODE_DIRECT);
        assertEquals(StealthPlan.MODE_DIRECT,
                memory.preferredRouteOn("wifi", StealthPlan.MODE_CHAINED));
    }

    /**
     * The defect this file exists to stop coming back: a spoof win used to be recorded as "not
     * chained", which downstream could only read as plain direct. The route has to survive the
     * round trip as itself.
     */
    @Test public void theSpoofRouteIsRememberedAsItself() {
        NetworkMemory memory = new NetworkMemory();
        memory.remember("cell:mci", StealthPlan.MODE_SPOOF);
        assertEquals(StealthPlan.MODE_SPOOF,
                memory.preferredRouteOn("cell:mci", StealthPlan.MODE_CHAINED));
        NetworkMemory read = NetworkMemory.deserialise(memory.serialise());
        assertEquals(StealthPlan.MODE_SPOOF,
                read.preferredRouteOn("cell:mci", StealthPlan.MODE_CHAINED));
    }

    /** All three routes are distinct values, or two of them would collapse into one on disk. */
    @Test public void everyRouteHasItsOwnValue() {
        assertTrue(NetworkMemory.known(StealthPlan.MODE_DIRECT));
        assertTrue(NetworkMemory.known(StealthPlan.MODE_CHAINED));
        assertTrue(NetworkMemory.known(StealthPlan.MODE_SPOOF));
        assertFalse(NetworkMemory.known(-1));
        assertFalse(NetworkMemory.known(7));
        assertFalse(StealthPlan.MODE_DIRECT == StealthPlan.MODE_CHAINED);
        assertFalse(StealthPlan.MODE_DIRECT == StealthPlan.MODE_SPOOF);
        assertFalse(StealthPlan.MODE_CHAINED == StealthPlan.MODE_SPOOF);
    }

    /**
     * A file written before the spoof route existed held 0 for direct and 1 for chained. Those are
     * still those routes' own values, so an upgrade must not reinterpret them.
     */
    @Test public void readsAFileWrittenBeforeTheSpoofRouteExisted() {
        NetworkMemory read = NetworkMemory.deserialise("wifi\t0\ncell:mci\t1\n");
        assertEquals(2, read.size());
        assertEquals(Integer.valueOf(StealthPlan.MODE_DIRECT), read.routeOn("wifi"));
        assertEquals(Integer.valueOf(StealthPlan.MODE_CHAINED), read.routeOn("cell:mci"));
    }

    /** A route this build cannot dial is dropped rather than stored and later attempted. */
    @Test public void ignoresARouteItCannotDial() {
        NetworkMemory memory = new NetworkMemory();
        memory.remember("wifi", 42);
        assertEquals(0, memory.size());
        assertEquals(0, NetworkMemory.deserialise("wifi\t42\n").size());
    }

    /** The whole point: two networks hold two different answers at the same time. */
    @Test public void twoNetworksKeepTwoAnswers() {
        NetworkMemory memory = new NetworkMemory();
        memory.remember("wifi", StealthPlan.MODE_SPOOF);
        memory.remember("cell:mci", StealthPlan.MODE_CHAINED);
        assertEquals(StealthPlan.MODE_SPOOF,
                memory.preferredRouteOn("wifi", StealthPlan.MODE_DIRECT));
        assertEquals(StealthPlan.MODE_CHAINED,
                memory.preferredRouteOn("cell:mci", StealthPlan.MODE_DIRECT));
    }

    @Test public void rememberingAgainReplacesTheAnswer() {
        NetworkMemory memory = new NetworkMemory();
        memory.remember("wifi", StealthPlan.MODE_CHAINED);
        memory.remember("wifi", StealthPlan.MODE_SPOOF);
        assertEquals(1, memory.size());
        assertEquals(StealthPlan.MODE_SPOOF,
                memory.preferredRouteOn("wifi", StealthPlan.MODE_CHAINED));
    }

    /** The reset button's whole job: after it, every network is discovered from cold again. */
    @Test public void forgettingClearsEveryNetwork() {
        NetworkMemory memory = new NetworkMemory();
        memory.remember("wifi", StealthPlan.MODE_CHAINED);
        memory.remember("cell:mci", StealthPlan.MODE_CHAINED);
        memory.forget();
        assertEquals(0, memory.size());
        assertNull(memory.routeOn("wifi"));
        assertEquals(StealthPlan.MODE_DIRECT,
                memory.preferredRouteOn("wifi", StealthPlan.MODE_DIRECT));
    }

    @Test public void ignoresAnEmptyKey() {
        NetworkMemory memory = new NetworkMemory();
        memory.remember(null, StealthPlan.MODE_CHAINED);
        memory.remember("", StealthPlan.MODE_CHAINED);
        assertEquals(0, memory.size());
    }

    /** Oldest out first, so a phone that sees many networks does not grow a file forever. */
    @Test public void dropsTheOldestNetworkPastTheCap() {
        NetworkMemory memory = new NetworkMemory();
        for (int i = 0; i < NetworkMemory.KEEP + 3; i++) {
            memory.remember("cell:n" + i, i % 2 == 0 ? StealthPlan.MODE_CHAINED : StealthPlan.MODE_SPOOF);
        }
        assertEquals(NetworkMemory.KEEP, memory.size());
        assertNull(memory.routeOn("cell:n0"));
        assertEquals(Integer.valueOf(StealthPlan.MODE_CHAINED),
                memory.routeOn("cell:n" + (NetworkMemory.KEEP + 2)));
    }

    /** Touching a network again makes it the newest, so a network in daily use is never dropped. */
    @Test public void reusingANetworkKeepsItAlive() {
        NetworkMemory memory = new NetworkMemory();
        memory.remember("wifi", StealthPlan.MODE_DIRECT);
        for (int i = 0; i < NetworkMemory.KEEP - 1; i++) {
            memory.remember("cell:n" + i, StealthPlan.MODE_CHAINED);
        }
        memory.remember("wifi", StealthPlan.MODE_DIRECT);
        memory.remember("cell:late", StealthPlan.MODE_CHAINED);
        assertEquals(Integer.valueOf(StealthPlan.MODE_DIRECT), memory.routeOn("wifi"));
    }

    @Test public void roundTripsThroughStorage() {
        NetworkMemory memory = new NetworkMemory();
        memory.remember("wifi", StealthPlan.MODE_DIRECT);
        memory.remember("cell:irancell", StealthPlan.MODE_CHAINED);
        memory.remember("cell:mci", StealthPlan.MODE_SPOOF);
        NetworkMemory read = NetworkMemory.deserialise(memory.serialise());
        assertEquals(3, read.size());
        assertEquals(Integer.valueOf(StealthPlan.MODE_DIRECT), read.routeOn("wifi"));
        assertEquals(Integer.valueOf(StealthPlan.MODE_CHAINED), read.routeOn("cell:irancell"));
        assertEquals(Integer.valueOf(StealthPlan.MODE_SPOOF), read.routeOn("cell:mci"));
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
