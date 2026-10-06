package com.firstham.aethergui;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/** The Turbo identity backup: round trip, and refusing anything that is not one. */
public class IdentityBundleTest {

    /** Shaped like the file the core writes (config.rs PersistedIdentity, serialised by toml). */
    private static byte[] identity(String device) {
        return ("device_id = \"" + device + "\"\n"
                + "access_token = \"token-" + device + "\"\n"
                + "ipv4 = \"172.16.0.2\"\nipv6 = \"2606:4700:110::1\"\n"
                + "wg_private_key = \"" + Base64.getEncoder().encodeToString(new byte[32]) + "\"\n"
                + "wg_peer_public_key = \"" + Base64.getEncoder().encodeToString(new byte[32]) + "\"\n")
                .getBytes(StandardCharsets.UTF_8);
    }

    @Test
    public void roundTripKeepsEveryByte() {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("aether.toml", identity("outer"));
        files.put("aether-secondary.toml", identity("inner"));
        Map<String, byte[]> back = IdentityBundle.decode(IdentityBundle.encode(files));
        assertEquals(2, back.size());
        assertArrayEquals(files.get("aether.toml"), back.get("aether.toml"));
        assertArrayEquals(files.get("aether-secondary.toml"), back.get("aether-secondary.toml"));
    }

    @Test
    public void nothingToBackUpGivesNull() {
        assertNull(IdentityBundle.encode(null));
        assertNull(IdentityBundle.encode(new LinkedHashMap<>()));
        Map<String, byte[]> junk = new LinkedHashMap<>();
        junk.put("aether.toml", "not an identity".getBytes(StandardCharsets.UTF_8));
        assertNull(IdentityBundle.encode(junk));
    }

    @Test
    public void filesThatAreNotIdentitiesAreLeftOut() {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("aether.toml", identity("outer"));
        files.put("settings.xml", identity("sneaky"));
        Map<String, byte[]> back = IdentityBundle.decode(IdentityBundle.encode(files));
        assertEquals(1, back.size());
        assertTrue(back.containsKey("aether.toml"));
    }

    @Test
    public void survivesWindowsLineEndingsAndAByteOrderMark() {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("aether-masque.toml", identity("masque"));
        String text = String.valueOf((char) 0xFEFF) + IdentityBundle.encode(files).replace("\n", "\r\n") + "\r\n\r\n";
        assertArrayEquals(files.get("aether-masque.toml"), IdentityBundle.decode(text).get("aether-masque.toml"));
    }

    @Test
    public void refusesWhatIsNotABackup() {
        refused(null);
        refused("");
        refused("hello");
        refused("panther-identity 2\naether.toml AAAA\n");
        refused(IdentityBundle.HEADER + "\n"); // no files
    }

    @Test
    public void refusesAPathOutsideTheIdentityFiles() {
        String b64 = Base64.getEncoder().encodeToString(identity("x"));
        refused(IdentityBundle.HEADER + "\n../shared_prefs/settings.xml " + b64 + "\n");
        refused(IdentityBundle.HEADER + "\n/data/evil " + b64 + "\n");
        refused(IdentityBundle.HEADER + "\naether.toml\n");
    }

    @Test
    public void refusesDamagedContent() {
        refused(IdentityBundle.HEADER + "\naether.toml !!!not-base64!!!\n");
        refused(IdentityBundle.HEADER + "\naether.toml "
                + Base64.getEncoder().encodeToString("plain text".getBytes(StandardCharsets.UTF_8)) + "\n");
    }

    @Test
    public void namesAreExactlyTheCoresFiles() {
        assertTrue(IdentityBundle.isIdentityName("aether.toml"));
        assertTrue(IdentityBundle.isIdentityName("aether-secondary.toml"));
        assertTrue(IdentityBundle.isIdentityName("aether-masque.toml"));
        assertFalse(IdentityBundle.isIdentityName("aether.toml.restore"));
        assertFalse(IdentityBundle.isIdentityName("../aether.toml"));
    }

    private static void refused(String text) {
        try {
            IdentityBundle.decode(text);
            fail("accepted: " + text);
        } catch (IllegalArgumentException expected) {
            assertFalse(expected.getMessage().isEmpty());
        }
    }
}
