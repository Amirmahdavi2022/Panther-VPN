package com.firstham.aethergui.vpngate;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * VPN Gate profiles are generated for desktop clients. These tests pin down which desktop-only
 * directives get stripped, because leaving any of them in produces an engine failure that is very
 * hard to read back from a user's log.
 */
public final class VpnGateProfileTest {

    private static final String PADDING = "\n<ca>\n"
            + "XXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX"
            + "XXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX"
            + "\n</ca>";

    private static VpnGateServer server(String config) {
        String encoded = Base64.getEncoder().encodeToString(config.getBytes(StandardCharsets.UTF_8));
        return VpnGateDirectory.parse(
                "vpnA,1.1.1.1,900000,12,50000000,Japan,JP,10,7200000,1,1,2w,op,," + encoded + "\n").get(0);
    }

    private static final String DESKTOP_PROFILE = String.join("\n",
            "dev tun", "proto tcp", "remote 1.2.3.4 443", "dev-node MyTAP",
            "block-outside-dns", "redirect-gateway def1", "explicit-exit-notify 3",
            "cipher AES-128-CBC", "auth SHA1", "# a comment", ";another",
            "route-method exe", "management 127.0.0.1 5555") + PADDING;

    @Test public void keepsWhatIsNeededToDial() {
        String profile = VpnGateProfile.build(server(DESKTOP_PROFILE), new String[]{"1.1.1.1", "1.0.0.1"});
        assertNotNull(profile);
        assertTrue(profile.contains("remote 1.2.3.4 443"));
        assertTrue(profile.contains("<ca>"));
        assertTrue(profile.contains("persist-tun"));
        assertTrue(profile.contains("nobind"));
        assertTrue(profile.contains("connect-retry-max 3"));
        assertTrue(profile.contains("dhcp-option DNS 1.1.1.1"));
        assertTrue(profile.contains("dhcp-option DNS 1.0.0.1"));
    }

    @Test public void stripsDirectivesAndroidCannotHonour() {
        String profile = VpnGateProfile.build(server(DESKTOP_PROFILE), null);
        assertFalse(profile.contains("dev-node"));
        assertFalse(profile.contains("block-outside-dns"));
        assertFalse(profile.contains("route-method"));
        assertFalse(profile.contains("management"));
        assertFalse(profile.contains("explicit-exit-notify"));
        assertFalse(profile.contains("# a comment"));
        assertFalse(profile.contains(";another"));
        assertFalse(profile.contains("dhcp-option DNS"));
    }

    @Test public void redirectGatewayIsOursAndAppearsOnce() {
        String profile = VpnGateProfile.build(server(DESKTOP_PROFILE), null);
        assertEquals(1, profile.split("redirect-gateway", -1).length - 1);
        assertTrue(profile.contains("redirect-gateway def1 bypass-dhcp"));
    }

    @Test public void readsTransportAndPort() {
        String tcp = VpnGateProfile.build(server(DESKTOP_PROFILE), null);
        assertTrue(VpnGateProfile.isTcp(tcp));
        assertEquals(443, VpnGateProfile.port(tcp));

        String udp = VpnGateProfile.build(server("proto udp\nremote 9.9.9.9 1194" + PADDING), null);
        assertFalse(VpnGateProfile.isTcp(udp));
        assertEquals(1194, VpnGateProfile.port(udp));
    }

    @Test public void rejectsProfilesThatCannotWork() {
        // No remote means nothing to dial; better to fail here than inside the engine.
        assertNull(VpnGateProfile.build(server("proto tcp\ncipher AES-128-CBC" + PADDING), null));
        assertNull(VpnGateProfile.build(null, null));
        assertFalse(VpnGateProfile.isTcp(null));
        assertEquals(-1, VpnGateProfile.port(null));
    }
}
