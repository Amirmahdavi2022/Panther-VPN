package com.firstham.aethergui.vpngate;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

/**
 * Turns a directory entry's inline base64 blob into an OpenVPN profile the engine will accept.
 *
 * VPN Gate's profiles are generated for desktop clients, so they carry directives Android either
 * cannot honour or that actively break a VpnService-based tunnel. Rather than hand the engine a
 * profile that fails in a hard-to-read way, the known-bad directives are stripped and the Android
 * essentials are appended.
 */
public final class VpnGateProfile {

    /**
     * Directives dropped before handing the profile to the engine.
     *
     * - dev-node / route-method / win-sys are Windows TAP concepts with no Android equivalent.
     * - block-outside-dns is a Windows firewall feature; Android handles this via the kill switch.
     * - redirect-gateway is re-added deliberately below so its flags are ours, not the volunteer's.
     * - explicit-exit-notify over TCP makes some servers drop the session immediately.
     */
    private static final String[] DROPPED_PREFIXES = {
            "dev-node", "route-method", "win-sys", "block-outside-dns",
            "redirect-gateway", "explicit-exit-notify", "register-dns",
            "dhcp-option DNS", "management", "ifconfig-nowarn",
    };

    private VpnGateProfile() {
    }

    /**
     * Decodes and adapts the profile for a server.
     *
     * @param dnsServers DNS to push into the tunnel; pass an empty array to leave DNS untouched.
     * @return a ready OpenVPN profile, or null when the blob cannot be decoded into one.
     */
    public static String build(VpnGateServer server, String[] dnsServers) {
        if (server == null) return null;
        String decoded = decode(server.openVpnConfigBase64);
        if (decoded == null) return null;

        List<String> kept = new ArrayList<>();
        boolean sawRemote = false;

        for (String raw : decoded.split("\r?\n")) {
            String line = raw.trim();
            if (line.isEmpty() || line.charAt(0) == '#' || line.charAt(0) == ';') continue;
            if (isDropped(line)) continue;
            if (line.toLowerCase(Locale.US).startsWith("remote ")) sawRemote = true;
            kept.add(line);
        }

        // Without a remote there is nothing to dial and the engine would fail obscurely.
        if (!sawRemote) return null;

        kept.add("");
        kept.add("# Added by Panther for Android");
        kept.add("client");
        kept.add("nobind");
        kept.add("persist-key");
        kept.add("persist-tun");
        // Send every route through the tunnel, and keep working when the server pushes no gateway.
        kept.add("redirect-gateway def1 bypass-dhcp");
        for (String dns : dnsServers == null ? new String[0] : dnsServers) {
            if (dns != null && !dns.trim().isEmpty()) {
                kept.add("dhcp-option DNS " + dns.trim());
            }
        }
        // Volunteer relays churn; give up on a dead one quickly instead of hanging the UI.
        kept.add("connect-retry 2 8");
        kept.add("connect-retry-max 3");
        kept.add("server-poll-timeout 12");
        kept.add("resolv-retry 30");

        return String.join("\n", kept) + "\n";
    }

    /** True when the profile speaks TCP, which matters for picking a fallback transport. */
    public static boolean isTcp(String profile) {
        if (profile == null) return false;
        for (String raw : profile.split("\r?\n")) {
            String line = raw.trim().toLowerCase(Locale.US);
            if (line.startsWith("proto ")) return line.contains("tcp");
        }
        return false;
    }

    /** The port the profile dials, or -1 when it cannot be determined. */
    public static int port(String profile) {
        if (profile == null) return -1;
        for (String raw : profile.split("\r?\n")) {
            String line = raw.trim();
            if (!line.toLowerCase(Locale.US).startsWith("remote ")) continue;
            String[] parts = line.split("\\s+");
            if (parts.length >= 3) {
                try {
                    return Integer.parseInt(parts[2]);
                } catch (NumberFormatException ignored) {
                    return -1;
                }
            }
        }
        return -1;
    }

    private static boolean isDropped(String line) {
        String lower = line.toLowerCase(Locale.US);
        for (String prefix : DROPPED_PREFIXES) {
            if (lower.startsWith(prefix.toLowerCase(Locale.US))) return true;
        }
        return false;
    }

    private static String decode(String base64) {
        if (base64 == null || base64.isEmpty()) return null;
        try {
            byte[] bytes = Base64.getDecoder().decode(base64.trim());
            String text = new String(bytes, StandardCharsets.UTF_8);
            return text.contains("remote ") ? text : null;
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }
}
