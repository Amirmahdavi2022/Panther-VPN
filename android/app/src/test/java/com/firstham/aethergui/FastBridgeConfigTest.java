package com.firstham.aethergui;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Pins the fast bridge configuration to the exact shape that was proved on a real TUN device.
 *
 * <p>Every rule here stands for a failure that is silent on a phone: without {@code fd} the
 * engine creates its own device and no packet ever reaches it; with stack UDP off the in-tunnel
 * resolver stops answering; a fake range inside 198.18.0.0/15 collides with the tunnel's own
 * address; and a value of the wrong TOML type makes the whole document rejected.
 *
 * <p>Runs two ways, like the rest of the suite: CI runs the {@code @Test} methods, and
 * {@code java -cp <classes>:<junit> com.firstham.aethergui.FastBridgeConfigTest} runs the same
 * checks with a count at the end.
 */
public final class FastBridgeConfigTest {

    private static int checks = 0;
    private static int failures = 0;

    private static void check(boolean ok, String what) {
        checks++;
        if (!ok) { failures++; System.out.println("  FAIL: " + what); }
    }

    private static boolean has(String text, String line) {
        for (String l : text.split("\n")) if (l.equals(line)) return true;
        return false;
    }

    /** Value of a key inside a given table, or null. */
    private static String valueIn(String text, String table, String key) {
        String current = "";
        for (String l : text.split("\n")) {
            if (l.startsWith("[")) { current = l; continue; }
            if (current.equals(table) && l.startsWith(key + " = ")) return l.substring(key.length() + 3);
        }
        return null;
    }

    @Test
    public void ordinaryEnginesKeepUdpAndPlainDns() {
        runOrdinaryChecks();
        assertTrue("ordinary engine configuration", failures == 0);
    }

    @Test
    public void udpLessEngineGetsFakeDns() {
        runMappedChecks();
        assertTrue("mapped DNS configuration", failures == 0);
    }

    @Test
    public void inputsAreHandledSafely() {
        runInputChecks();
        assertTrue("input handling", failures == 0);
    }

    static void runOrdinaryChecks() {
        String c = FastBridgeConfig.render("127.0.0.1", 1819, 1500, false, 42);
        check(c.startsWith("preset = \"mobile\"\n"), "preset comes first, before anything it would reset");
        check("42".equals(valueIn(c, "[tun]", "fd")), "descriptor is named in the document itself");
        check("1500".equals(valueIn(c, "[tun]", "mtu")), "mtu passed through");
        check("false".equals(valueIn(c, "[tun]", "configure")), "Android configures the interface, not the engine");
        check("[\"198.18.0.1/30\", \"fc00::1/126\"]".equals(valueIn(c, "[tun]", "address")),
                "interface addresses match the ones the service builds");
        check("true".equals(valueIn(c, "[stack]", "udp")), "stack UDP on");
        check("\"socks5\"".equals(valueIn(c, "[handler]", "kind")), "socks5 handler");
        check("\"127.0.0.1:1819\"".equals(valueIn(c, "[handler.socks5]", "server")), "server address");
        check("true".equals(valueIn(c, "[handler.socks5]", "udp")), "UDP forwarded for engines that carry it");
        check("false".equals(valueIn(c, "[handler.socks5]", "pipeline")), "strict handshake");
        check("false".equals(valueIn(c, "[handler.socks5]", "optimistic_data")), "no data before the reply");
        check("0".equals(valueIn(c, "[handler.socks5]", "pool_size")), "no pooled connections to a restarting engine");
        check("\"epoll\"".equals(valueIn(c, "[io]", "backend")), "epoll backend");
        check("false".equals(valueIn(c, "[io]", "monitor_network")), "Android owns network changes");
        check("false".equals(valueIn(c, "[dns]", "fake_ip")), "no fake DNS for engines that carry UDP");
        check("false".equals(valueIn(c, "[dns]", "systemd_resolved")), "systemd_resolved is a boolean, not a string");
        check(valueIn(c, "[dns]", "fake_ranges") == null, "no fake range when fake DNS is off");
        check(!c.contains("\"off\"") || c.contains("elastic = \"off\""), "only enum-typed keys take \"off\"");
    }

    static void runMappedChecks() {
        String c = FastBridgeConfig.render("127.0.0.1", 40123, 1400, true, 7);
        check("true".equals(valueIn(c, "[stack]", "udp")),
                "stack UDP stays on even here, or the in-tunnel resolver goes silent");
        check("false".equals(valueIn(c, "[handler.socks5]", "udp")), "no UDP requests to the engine that refuses them");
        check("true".equals(valueIn(c, "[dns]", "fake_ip")), "fake DNS on");
        check(("[\"" + TunnelConfig.MAPPED_NETWORK + "/" + TunnelConfig.MAPPED_PREFIX + "\"]")
                .equals(valueIn(c, "[dns]", "fake_ranges")), "fake range is the one the service routes");
        check(!c.contains("198.18.0.0/15"), "library default range, which holds the tunnel address, is not used");
        check(("[\"" + TunnelConfig.MAPPED_DNS_ADDRESS + "\"]").equals(valueIn(c, "[dns]", "address")),
                "resolver address is a list and matches the DNS server the service advertises");
        check(String.valueOf(TunnelConfig.MAPPED_CACHE_SIZE).equals(valueIn(c, "[dns]", "cache_size")), "cache size");
        check("false".equals(valueIn(c, "[dns]", "hijack")), "only queries sent to the resolver are answered");
        check("\"127.0.0.1:40123\"".equals(valueIn(c, "[handler.socks5]", "server")), "engine-chosen port used");
    }

    static void runInputChecks() {
        check("[::1]:1819".equals(FastBridgeConfig.hostPort("::1", 1819)), "IPv6 host bracketed");
        check("[::1]:1819".equals(FastBridgeConfig.hostPort("[::1]", 1819)), "already bracketed host left alone");
        check("evil:1".equals(FastBridgeConfig.hostPort("ev\"il", 1)), "quotes cannot break out of the string");
        boolean threw = false;
        try { FastBridgeConfig.render("127.0.0.1", 1, 1500, false, -1); }
        catch (IllegalArgumentException expected) { threw = true; }
        check(threw, "a missing descriptor is refused rather than rendered");
        check(FastBridgeConfig.isFast("fast"), "fast selected");
        check(!FastBridgeConfig.isFast("classic") && !FastBridgeConfig.isFast(null) && !FastBridgeConfig.isFast(""),
                "anything else is the classic bridge");
        check(FastBridgeConfig.COUNTER_UPLOAD_BYTES == 1 && FastBridgeConfig.COUNTER_DOWNLOAD_BYTES == 3,
                "counter indexes: rx_bytes is upload, tx_bytes is download");
        check("configuration rejected".equals(FastBridgeConfig.describe(-16)), "config error described");
        check("error -42".equals(FastBridgeConfig.describe(-42)), "unknown code still readable");
    }

    public static void main(String[] args) {
        runOrdinaryChecks();
        runMappedChecks();
        runInputChecks();
        System.out.println("FastBridgeConfigTest: " + (checks - failures) + "/" + checks + " passed");
        if (failures > 0) System.exit(1);
    }
}
