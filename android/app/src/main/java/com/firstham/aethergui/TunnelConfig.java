package com.firstham.aethergui;

/**
 * Builds the configuration handed to the HEV TUN-to-SOCKS bridge, and decides when the bridge
 * should answer DNS itself instead of forwarding it.
 *
 * <p>Why mapped DNS exists here at all. HEV forwards UDP by sending SOCKS5 command {@code 0x03}
 * (UDP ASSOCIATE) when {@code udp: 'udp'} is set, and its own non-standard command {@code 0x05}
 * for any other value - both read straight out of the pinned 2.16.0 sources, not from
 * documentation. The Global engine's SOCKS listener implements neither, so every UDP datagram is
 * refused and the log fills with "command was 0x03, not 0x01". DNS is UDP, so on that engine name
 * resolution never worked at all.
 *
 * <p>Mapped DNS sidesteps the whole problem rather than trying to negotiate a UDP mode that the
 * other end does not have. The bridge answers queries itself inside the tunnel, hands the app an
 * address out of a private range, and turns that address back into the original hostname when the
 * app opens a connection to it - so the name travels inside the ordinary TCP request that already
 * works. Two side benefits worth keeping in mind: nothing resolves outside the tunnel, and the
 * upstream sees hostnames rather than bare addresses.
 *
 * <p>Deliberately limited to the engine that is broken without it. The other engines forward UDP
 * correctly today, and quietly rerouting their DNS would risk working setups to fix one that is
 * already failing. See {@link #usesMappedDns(String)}.
 *
 * <p>Free of Android imports on purpose, so the configuration this produces can be generated and
 * checked on a desktop JVM instead of being read by eye.
 */
final class TunnelConfig {

    /**
     * Where the bridge listens for DNS. It sits inside the tunnel's own /30, so it is on-link for
     * the interface and reaches lwIP without needing a route of its own.
     */
    static final String MAPPED_DNS_ADDRESS = "198.18.0.2";

    static final int MAPPED_DNS_PORT = 53;

    /**
     * The range answers are drawn from. 100.64.0.0/10 is carrier-grade NAT space, so it will not
     * collide with a home network the way a 10/8 or 192.168/16 pick would.
     *
     * <p>This range MUST be routed into the tunnel when mapped DNS is on. It is excluded by
     * default, since it is a private range and the default routing mode leaves private ranges to
     * the local network - and an unrouted mapped address fails in the worst possible way, with
     * every name resolving fine and nothing connecting.
     */
    static final String MAPPED_NETWORK = "100.64.0.0";

    static final int MAPPED_PREFIX = 10;

    static final String MAPPED_NETMASK = "255.192.0.0";

    /** How many resolved names the bridge remembers. Each entry is one name and one address. */
    static final int MAPPED_CACHE_SIZE = 10000;

    private TunnelConfig() { }

    /**
     * Whether the bridge should answer DNS itself for a given engine.
     *
     * <p>Only Global, and only because its SOCKS listener refuses UDP outright. An engine that
     * forwards UDP correctly is left exactly as it is.
     */
    static boolean usesMappedDns(String engine) {
        return "global".equals(engine);
    }

    /**
     * The DNS server to advertise on the VPN interface: the bridge's own listener when it is
     * answering, and the caller's choice otherwise.
     */
    static String dnsServerFor(String engine, String fallback) {
        return usesMappedDns(engine) ? MAPPED_DNS_ADDRESS : fallback;
    }

    /**
     * Renders the bridge configuration.
     *
     * @param socksHost loopback address of whichever engine is carrying the connection
     * @param socksPort port that engine is listening on
     * @param mtu       tunnel MTU, matched to the one set on the interface
     * @param mappedDns whether the bridge answers DNS itself
     */
    static String render(String socksHost, int socksPort, int mtu, boolean mappedDns) {
        StringBuilder out = new StringBuilder();
        out.append("misc:\n");
        out.append("  task-stack-size: 32768\n");
        out.append("  connect-timeout: 15000\n");
        out.append("  log-level: warn\n");
        out.append("tunnel:\n");
        out.append("  mtu: ").append(mtu).append('\n');
        out.append("  ipv4: 198.18.0.1\n");
        out.append("  ipv6: 'fc00::1'\n");
        out.append("  icmp: 'reply'\n");
        out.append("socks5:\n");
        out.append("  address: '").append(escape(socksHost)).append("'\n");
        out.append("  port: ").append(socksPort).append('\n');
        out.append("  udp: 'udp'\n");
        if (mappedDns) {
            out.append("mapdns:\n");
            out.append("  address: ").append(MAPPED_DNS_ADDRESS).append('\n');
            out.append("  port: ").append(MAPPED_DNS_PORT).append('\n');
            out.append("  network: ").append(MAPPED_NETWORK).append('\n');
            out.append("  netmask: ").append(MAPPED_NETMASK).append('\n');
            out.append("  cache-size: ").append(MAPPED_CACHE_SIZE).append('\n');
        }
        return out.toString();
    }

    /** Single-quoted YAML escapes a quote by doubling it; nothing else needs touching. */
    static String escape(String value) {
        return value.replace("'", "''");
    }
}
