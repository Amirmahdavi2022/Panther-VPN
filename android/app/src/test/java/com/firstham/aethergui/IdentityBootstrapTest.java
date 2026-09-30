package com.firstham.aethergui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Cold-start identity registration: which log lines mean what, which engine is asked first, and
 * when a registrar run counts as done.
 *
 * <p>The log lines are copied from the pinned core's source (Aether v2.1.0) with only the
 * variable parts filled in, so a wording change upstream shows up here rather than on a phone.
 */
public class IdentityBootstrapTest {

    // Existing identity, normal start.
    private static final String[] WARM_START = {
            "Aether v2.1.0",
            "[+] loaded existing warp identity from /data/files/aether.toml",
            "[+] loaded existing warp identity from /data/files/aether.secondary.toml",
            "[+] outer device=abc ipv4=172.16.0.2 | inner device=def ipv4=172.16.0.3",
    };

    // Fresh install on an open network: registration answers at once.
    private static final String[] COLD_OPEN = {
            "Aether v2.1.0",
            "[+] no warp identity found; provisioning dedicated wireguard account",
            "[+] provisioned and saved new warp identity to /data/files/aether.toml",
            "[+] no warp identity found; provisioning dedicated wireguard account",
            "[+] provisioned and saved new warp identity to /data/files/aether.secondary.toml",
            "[+] outer device=abc ipv4=172.16.0.2 | inner device=def ipv4=172.16.0.3",
    };

    // Fresh install where the registration API is filtered.
    private static final String[] COLD_BLOCKED = {
            "Aether v2.1.0",
            "[+] no warp identity found; provisioning dedicated wireguard account",
            "[!] registration retry 1/4 in 0.6s: registration: error sending request",
            "[!] registration retry 2/4 in 1.2s: registration: error sending request",
            "[!] registration failed over the direct route: registration: timed out",
            "[*] registration retrying over a camouflaged route: random cloudflare edge address, "
                    + "no dns lookup, split client hello, alternate tls fingerprints",
    };

    @Test
    public void warmStartNeverAsksForAnIdentity() {
        for (String line : WARM_START) {
            assertFalse(line, IdentityBootstrap.needsIdentity(lower(line)));
            assertFalse(line, IdentityBootstrap.struggling(lower(line)));
        }
        assertTrue(IdentityBootstrap.identityReady(lower(WARM_START[3])));
    }

    @Test
    public void coldStartOnAnOpenNetworkIsNeverTreatedAsStruggling() {
        boolean needs = false;
        boolean struggling = false;
        boolean ready = false;
        for (String line : COLD_OPEN) {
            needs |= IdentityBootstrap.needsIdentity(lower(line));
            struggling |= IdentityBootstrap.struggling(lower(line));
            ready |= IdentityBootstrap.identityReady(lower(line));
        }
        assertTrue(needs);
        // The service only hands over early when BOTH are true, so no needless bootstrap.
        assertFalse(struggling);
        assertTrue(ready);
    }

    @Test
    public void blockedRegistrationIsDetectedEvenWithoutTheProvisioningLine() {
        // At log level warn the info-level provisioning line is hidden; the retry lines are not.
        boolean needs = false;
        boolean struggling = false;
        for (int i = 2; i < COLD_BLOCKED.length; i++) {
            needs |= IdentityBootstrap.needsIdentity(lower(COLD_BLOCKED[i]));
            struggling |= IdentityBootstrap.struggling(lower(COLD_BLOCKED[i]));
        }
        assertTrue(needs);
        assertTrue(struggling);
        assertTrue(IdentityBootstrap.needsIdentity(lower(COLD_BLOCKED[1])));
    }

    @Test
    public void masqueLinesAndKeyEnrollmentCountToo() {
        assertTrue(IdentityBootstrap.needsIdentity(lower(
                "[+] no masque identity found; provisioning dedicated masque account")));
        assertTrue(IdentityBootstrap.needsIdentity(lower(
                "[!] key enrollment retry 1/4 in 0.9s: key enrollment: timed out")));
        assertTrue(IdentityBootstrap.struggling(lower(
                "[!] key enrollment failed over the direct route: key enrollment: timed out")));
        assertTrue(IdentityBootstrap.identityReady(lower(
                "[+] identity ready: device=abc ipv4=172.16.0.2 ipv6=fd01::2")));
    }

    @Test
    public void zeroTrustDeviceRefreshIsNotRegistration() {
        String[] lines = {
                "[!] device refresh retry 1/4 in 0.9s: device refresh: timed out",
                "[!] device refresh failed over the direct route: timed out",
                "[*] device refresh retrying over a camouflaged route: random cloudflare edge address",
        };
        for (String line : lines) {
            assertFalse(line, IdentityBootstrap.needsIdentity(lower(line)));
            assertFalse(line, IdentityBootstrap.struggling(lower(line)));
        }
    }

    @Test
    public void nullLinesAreHarmless() {
        assertFalse(IdentityBootstrap.needsIdentity(null));
        assertFalse(IdentityBootstrap.struggling(null));
        assertFalse(IdentityBootstrap.identityReady(null));
    }

    @Test
    public void beaconIsAskedFirst() {
        assertEquals(Arrays.asList(IdentityBootstrap.Route.BEACON, IdentityBootstrap.Route.GLOBAL),
                Arrays.asList(IdentityBootstrap.ORDER));
    }

    @Test
    public void beaconSuccessNeverStartsGlobal() {
        Recorder recorder = new Recorder(true, true);
        assertEquals(IdentityBootstrap.Route.BEACON, IdentityBootstrap.run(recorder, () -> false));
        assertEquals(Arrays.asList(IdentityBootstrap.Route.BEACON), recorder.tried);
    }

    @Test
    public void globalIsTheSecondTry() {
        Recorder recorder = new Recorder(false, true);
        assertEquals(IdentityBootstrap.Route.GLOBAL, IdentityBootstrap.run(recorder, () -> false));
        assertEquals(Arrays.asList(IdentityBootstrap.Route.BEACON, IdentityBootstrap.Route.GLOBAL),
                recorder.tried);
    }

    @Test
    public void bothFailingReportsNothing() {
        Recorder recorder = new Recorder(false, false);
        assertNull(IdentityBootstrap.run(recorder, () -> false));
        assertEquals(2, recorder.tried.size());
    }

    @Test
    public void aCrashingCourierCountsAsAFailedRouteNotAnAbort() {
        List<IdentityBootstrap.Route> tried = new ArrayList<>();
        IdentityBootstrap.Route route = IdentityBootstrap.run(via -> {
            tried.add(via);
            if (via == IdentityBootstrap.Route.BEACON) throw new IllegalStateException("boom");
            return true;
        }, () -> false);
        assertEquals(IdentityBootstrap.Route.GLOBAL, route);
        assertEquals(2, tried.size());
    }

    @Test
    public void disconnectDuringBeaconStopsEverything() {
        boolean[] cancelled = {false};
        List<IdentityBootstrap.Route> tried = new ArrayList<>();
        IdentityBootstrap.Route route = IdentityBootstrap.run(via -> {
            tried.add(via);
            cancelled[0] = true; // the user pressed disconnect mid-registration
            return false;
        }, () -> cancelled[0]);
        assertNull(route);
        assertEquals(Arrays.asList(IdentityBootstrap.Route.BEACON), tried);
    }

    @Test
    public void aReplacedConnectCannotClaimASuccess() {
        // The old connect's courier "succeeded" only after a newer connect replaced it.
        boolean[] replaced = {false};
        IdentityBootstrap.Route route = IdentityBootstrap.run(via -> {
            replaced[0] = true;
            return true;
        }, () -> replaced[0]);
        assertNull(route);
    }

    @Test
    public void alreadyCancelledStartsNothing() {
        Recorder recorder = new Recorder(true, true);
        assertNull(IdentityBootstrap.run(recorder, () -> true));
        assertTrue(recorder.tried.isEmpty());
    }

    @Test
    public void interruptedCourierStopsAndKeepsTheInterrupt() {
        IdentityBootstrap.Route route = IdentityBootstrap.run(via -> {
            throw new InterruptedException();
        }, () -> false);
        assertNull(route);
        assertTrue(Thread.interrupted()); // also clears it for the next test
    }

    @Test
    public void registrarReadiness() {
        // Its own log line is enough, even if it exited right after saving.
        assertTrue(IdentityBootstrap.registrarReady(true, true, false, false));
        assertTrue(IdentityBootstrap.registrarReady(false, true, false, false));
        // Its own listener, and it is still running after answering.
        assertTrue(IdentityBootstrap.registrarReady(true, false, true, true));
        // Still provisioning.
        assertFalse(IdentityBootstrap.registrarReady(true, false, false, false));
        // Port collision: something answered, but our registrar died (could not bind).
        assertFalse(IdentityBootstrap.registrarReady(true, false, true, false));
        assertFalse(IdentityBootstrap.registrarReady(false, false, true, false));
        // Dead with nothing logged.
        assertFalse(IdentityBootstrap.registrarReady(false, false, false, false));
    }

    private static String lower(String line) {
        return line.toLowerCase(Locale.US);
    }

    private static final class Recorder implements IdentityBootstrap.Courier {
        final List<IdentityBootstrap.Route> tried = new ArrayList<>();
        private final boolean beacon;
        private final boolean global;

        Recorder(boolean beacon, boolean global) {
            this.beacon = beacon;
            this.global = global;
        }

        @Override public boolean register(IdentityBootstrap.Route route) {
            tried.add(route);
            return route == IdentityBootstrap.Route.BEACON ? beacon : global;
        }
    }
}
