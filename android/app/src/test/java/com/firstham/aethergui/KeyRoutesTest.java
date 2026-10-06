package com.firstham.aethergui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Map;

/**
 * The hidden key routes. The variable names and values here were each run against the real
 * Aether v2.3.0 core in register mode, which accepted all four shapes (an IPv6 address in brackets
 * with a port, ECH with a udp resolver, an upstream proxy) and went on to dial.
 */
public class KeyRoutesTest {

    @Rule public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void ipv6IsTriedFirstAndEveryRouteHasAnAddress() {
        assertEquals(KeyRoutes.IPV6_EDGE, KeyRoutes.ATTEMPTS[0].address);
        assertEquals(KeyRoutes.IPV6_EDGE, KeyRoutes.ATTEMPTS[1].address);
        for (KeyRoutes.Attempt attempt : KeyRoutes.ATTEMPTS) {
            assertTrue(attempt.address.endsWith(":443"));
            assertTrue(attempt.ech != attempt.shaped); // exactly one trick per attempt
        }
    }

    @Test
    public void registerSetFollowsTheProtocol() {
        assertEquals("gool", KeyRoutes.registerSet("gool"));
        assertEquals("gool", KeyRoutes.registerSet(null));
        assertEquals("gool", KeyRoutes.registerSet("smart"));
        assertEquals("wg", KeyRoutes.registerSet("WG"));
        assertEquals("masque", KeyRoutes.registerSet("masque"));
        assertEquals(Arrays.asList("aether.toml", "aether-secondary.toml"), KeyRoutes.filesFor("gool"));
        assertEquals(Arrays.asList("aether.toml"), KeyRoutes.filesFor("wg"));
        assertEquals(Arrays.asList("aether-masque.toml"), KeyRoutes.filesFor("masque"));
    }

    @Test
    public void echAttemptCarriesTheKeyLookupAndNoProxy() {
        KeyRoutes.Attempt ech = KeyRoutes.ATTEMPTS[1];
        Map<String, String> env = KeyRoutes.environment(ech, "gool", "/data/aether.toml", "127.0.0.1:18443", "/tmp");
        assertEquals("gool", env.get("AETHER_REGISTER"));
        assertEquals("/data/aether.toml", env.get("AETHER_CONFIG"));
        assertEquals("auto", env.get("AETHER_ECH"));
        assertEquals("udp://1.1.1.1", env.get("AETHER_ECH_DNS"));
        assertEquals(KeyRoutes.IPV6_EDGE, env.get("AETHER_ENROLL_ADDRESS"));
        assertEquals(KeyRoutes.REGISTRAR_SOCKS, env.get("AETHER_SOCKS"));
        assertNull(env.get("AETHER_UPSTREAM"));
    }

    @Test
    public void shapedAttemptGoesThroughTheShapingProxyWithoutEch() {
        KeyRoutes.Attempt shaped = KeyRoutes.ATTEMPTS[0];
        Map<String, String> env = KeyRoutes.environment(shaped, "wg", "/data/aether.toml", "127.0.0.1:18443", null);
        assertEquals("socks5://127.0.0.1:18443", env.get("AETHER_UPSTREAM"));
        assertNull(env.get("AETHER_ECH"));
        assertNull(env.get("TMPDIR"));
    }

    @Test
    public void savedNeedsEveryFileOfTheSet() throws Exception {
        File dir = folder.newFolder();
        assertFalse(KeyRoutes.saved(dir, "gool"));
        write(dir, "aether.toml", identity());
        assertTrue(KeyRoutes.saved(dir, "wg"));
        assertFalse(KeyRoutes.saved(dir, "gool"));
        write(dir, "aether-secondary.toml", "garbage");
        assertFalse(KeyRoutes.saved(dir, "gool"));
        write(dir, "aether-secondary.toml", identity());
        assertTrue(KeyRoutes.saved(dir, "gool"));
        assertFalse(KeyRoutes.saved(dir, "masque"));
    }

    private static String identity() {
        return "device_id = \"d\"\naccess_token = \"t\"\nipv4 = \"\"\nipv6 = \"\"\n"
                + "wg_private_key = \"AAAA\"\nwg_peer_public_key = \"AAAA\"\n";
    }

    private static void write(File dir, String name, String content) throws Exception {
        Files.write(new File(dir, name).toPath(), content.getBytes(StandardCharsets.UTF_8));
    }
}
