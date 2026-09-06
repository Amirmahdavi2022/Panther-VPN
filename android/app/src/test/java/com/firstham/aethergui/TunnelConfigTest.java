package com.firstham.aethergui;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Checks the bridge configuration and the mapped-DNS decision.
 *
 * <p>Worth stating what these guard, because the failures they prevent are all silent ones. Mapped
 * DNS on an engine that forwards UDP fine is a change to a working data path for no reason; mapped
 * DNS missing from the engine that cannot forward UDP leaves name resolution broken; and a
 * configuration that names the wrong range leaves every lookup succeeding while every connection
 * fails. None of those announce themselves in a log.
 *
 * <p>Runs two ways, like the rest of the suite: CI runs the {@code @Test} methods, and
 * {@code java -cp <classes> com.firstham.aethergui.TunnelConfigTest} runs the same checks with a
 * count at the end.
 */
public final class TunnelConfigTest {

    private static int checks = 0;
    private static int failures = 0;

    private static void check(boolean ok, String what) {
        checks++;
        if (!ok) { failures++; System.out.println("  FAIL: " + what); }
    }

    @Test
    public void mappedDnsIsLimitedToTheEngineThatNeedsIt() {
        runMappedDnsChecks();
        assertTrue("mapped DNS decisions", failures == 0);
    }

    @Test
    public void configurationNamesTheRightAddresses() {
        runRenderChecks();
        assertTrue("rendered configuration", failures == 0);
    }

    private static void runMappedDnsChecks() {
        check(TunnelConfig.usesMappedDns("global"), "global answers DNS in the tunnel");
        check(!TunnelConfig.usesMappedDns("turbo"), "turbo keeps the ordinary DNS path");
        check(!TunnelConfig.usesMappedDns("stealth"), "stealth keeps the ordinary DNS path");
        check(!TunnelConfig.usesMappedDns(""), "a degraded run keeps the ordinary DNS path");
        check(!TunnelConfig.usesMappedDns(null), "an absent engine keeps the ordinary DNS path");
        check(!TunnelConfig.usesMappedDns("Global"), "the engine key is matched exactly");

        check(TunnelConfig.MAPPED_DNS_ADDRESS.equals(TunnelConfig.dnsServerFor("global", "1.1.1.1")),
                "global advertises the bridge as its resolver");
        check("1.1.1.1".equals(TunnelConfig.dnsServerFor("turbo", "1.1.1.1")),
                "turbo advertises the caller's resolver");
    }

    private static void runRenderChecks() {
        String global = TunnelConfig.render("127.0.0.1", 1080, 1500, true);
        check(global.contains("mapdns:\n"), "global config carries a mapdns section");
        check(global.contains("  address: 198.18.0.2\n"), "mapdns listens inside the tunnel's own subnet");
        check(global.contains("  port: 53\n"), "mapdns listens on the DNS port");
        check(global.contains("  network: 100.64.0.0\n"), "answers come from carrier-grade NAT space");
        check(global.contains("  netmask: 255.192.0.0\n"), "the mask matches the /10 that gets routed");
        check(global.contains("  cache-size: 10000\n"), "the resolver keeps a cache");

        String turbo = TunnelConfig.render("127.0.0.1", 1819, 1500, false);
        check(!turbo.contains("mapdns"), "no mapdns section when the engine forwards UDP");
        check(turbo.contains("socks5:\n"), "the bridge still points at the engine");
        check(turbo.contains("  port: 1819\n"), "the engine's own port is used");
        check(turbo.contains("  address: '127.0.0.1'\n"), "the engine's own address is used");
        check(turbo.contains("  udp: 'udp'\n"), "UDP forwarding is unchanged for the other engines");
        check(turbo.contains("  mtu: 1500\n"), "the MTU is carried through");

        // The netmask written into the bridge and the prefix routed on the interface describe the
        // same range. If these ever drift, names resolve and nothing connects.
        check(TunnelConfig.MAPPED_PREFIX == 10, "the routed prefix is /10");
        check(TunnelConfig.MAPPED_NETMASK.equals(maskFromPrefix(TunnelConfig.MAPPED_PREFIX)),
                "the netmask and the routed prefix agree");

        // The resolver has to be reachable without a route of its own, which means sharing the /30
        // the interface already owns.
        check(sameSubnet("198.18.0.1", TunnelConfig.MAPPED_DNS_ADDRESS, 30),
                "the resolver is on-link for the tunnel interface");

        String odd = TunnelConfig.render("127.0.0.1", 1080, 1280, true);
        check(odd.contains("  mtu: 1280\n"), "a different MTU is carried through");
        check(TunnelConfig.escape("it's").equals("it''s"), "a quote is escaped for YAML");
    }

    private static String maskFromPrefix(int prefix) {
        long mask = prefix == 0 ? 0L : (0xFFFFFFFFL << (32 - prefix)) & 0xFFFFFFFFL;
        return ((mask >> 24) & 0xFF) + "." + ((mask >> 16) & 0xFF) + "."
                + ((mask >> 8) & 0xFF) + "." + (mask & 0xFF);
    }

    private static boolean sameSubnet(String left, String right, int prefix) {
        long mask = (0xFFFFFFFFL << (32 - prefix)) & 0xFFFFFFFFL;
        return (toLong(left) & mask) == (toLong(right) & mask);
    }

    private static long toLong(String address) {
        String[] parts = address.split("\\.");
        long value = 0;
        for (String part : parts) value = (value << 8) | Integer.parseInt(part);
        return value;
    }

    public static void main(String[] args) {
        runMappedDnsChecks();
        runRenderChecks();
        System.out.println("TunnelConfig: " + checks + " checks, " + failures + " failures");
        if (failures > 0) System.exit(1);
    }
}
