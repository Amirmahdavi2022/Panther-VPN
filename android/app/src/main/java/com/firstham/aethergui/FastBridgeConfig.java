package com.firstham.aethergui;

/**
 * Builds the TOML handed to the fast TUN bridge (Zeptun, see NOTICE.md), the optional
 * replacement for the classic bridge rendered by {@link TunnelConfig}.
 *
 * <p>Kept free of Android imports, like {@link TunnelConfig}, so the exact text the phone passes
 * to the native library can be generated on a desktop JVM and fed to the same library there.
 * That is how every key below was checked: the output of {@link #render} was loaded by the
 * library's own TOML parser, attached to a real TUN device, and driven with TCP, UDP and DNS.
 *
 * <p>Why each non-default choice is here:
 * <ul>
 *   <li>{@code preset = "mobile"} first, because a preset resets everything after it is read.
 *       It gives one queue, no offloads and a 24 MB memory budget.</li>
 *   <li>{@code io.backend = "epoll"}. The library already picks epoll for a descriptor handed
 *       in from outside; saying so keeps io_uring, which apps may not be allowed to use, out of
 *       the picture even if that default changes upstream.</li>
 *   <li>{@code io.monitor_network = false}. Android owns network changes for a VPN app, and an
 *       app cannot rely on reading the routing table the way the watcher does.</li>
 *   <li>{@code pipeline}, {@code optimistic_data} off and {@code pool_size = 0}. Every engine
 *       Panther runs listens on loopback, where a round trip costs microseconds, so these save
 *       nothing - and a pooled connection to an engine that just restarted is a dead one. A
 *       strict one-message-at-a-time handshake is what every one of the three engines is known
 *       to accept.</li>
 *   <li>Fake-IP DNS for the engine that cannot forward UDP, drawn from the same range the classic
 *       bridge uses, and with an explicit resolver address: the library's defaults would place
 *       both inside 198.18.0.0/15, which is where the tunnel's own address lives.</li>
 * </ul>
 */
final class FastBridgeConfig {

    /** Preference value selecting this bridge. Anything else means the classic one. */
    static final String BRIDGE_FAST = "fast";

    static final String BRIDGE_CLASSIC = "classic";

    /** Index of the device-read byte counter in the native stats snapshot. That is upload. */
    static final int COUNTER_UPLOAD_BYTES = 1;

    /** Index of the device-write byte counter in the native stats snapshot. That is download. */
    static final int COUNTER_DOWNLOAD_BYTES = 3;

    private FastBridgeConfig() { }

    static boolean isFast(String bridge) {
        return BRIDGE_FAST.equals(bridge);
    }

    /**
     * Renders the configuration.
     *
     * @param socksHost loopback address of whichever engine is carrying the connection
     * @param socksPort port that engine is listening on
     * @param mtu       tunnel MTU, matched to the one set on the interface
     * @param mappedDns whether the bridge answers DNS itself; see {@link TunnelConfig#usesMappedDns}
     * @param tunFd     descriptor of the interface Android created for the service
     */
    static String render(String socksHost, int socksPort, int mtu, boolean mappedDns, int tunFd) {
        if (tunFd < 0) throw new IllegalArgumentException("no tunnel descriptor");
        StringBuilder out = new StringBuilder();
        out.append("preset = \"mobile\"\n");
        out.append("log_level = \"warn\"\n");
        out.append('\n');
        out.append("[tun]\n");
        // 🚨 The descriptor MUST be named here. The library's bridge also passes it through a
        // separate setter after parsing, but in 1.0.0 that setter records the number without
        // switching the device to descriptor mode, so the engine quietly creates a device of its
        // own and no packet from the phone ever reaches it. Measured on a real TUN interface,
        // with the same calls the bridge makes: setter alone, zero traffic; named here, traffic.
        out.append("fd = ").append(tunFd).append('\n');
        out.append("mtu = ").append(mtu).append('\n');
        out.append("configure = false\n");
        // The interface itself is set up by Android; these only have to match what the service
        // gave the builder, because the library derives its own resolver address from them.
        out.append("address = [\"198.18.0.1/30\", \"fc00::1/126\"]\n");
        out.append('\n');
        out.append("[stack]\n");
        out.append("tcp_connect_timeout_ms = 15000\n");
        // Left on even for the engine that cannot carry UDP: the in-tunnel resolver is itself
        // reached over UDP, and turning this off silences it too (measured - every query timed
        // out). The proxy-side switch below is the one that keeps datagrams away from that engine.
        out.append("udp = true\n");
        out.append("icmp = \"local\"\n");
        out.append('\n');
        out.append("[handler]\n");
        out.append("kind = \"socks5\"\n");
        out.append('\n');
        out.append("[handler.socks5]\n");
        out.append("server = \"").append(hostPort(socksHost, socksPort)).append("\"\n");
        // An engine that cannot carry UDP is never asked to; other datagrams are dropped here
        // instead of producing one refused request each.
        out.append("udp = ").append(!mappedDns).append('\n');
        out.append("pipeline = false\n");
        out.append("optimistic_data = false\n");
        out.append("pool_size = 0\n");
        out.append('\n');
        out.append("[io]\n");
        out.append("backend = \"epoll\"\n");
        out.append("elastic = \"off\"\n");
        out.append("monitor_network = false\n");
        out.append('\n');
        out.append("[dns]\n");
        if (mappedDns) {
            out.append("fake_ip = true\n");
            out.append("fake_ranges = [\"").append(TunnelConfig.MAPPED_NETWORK).append('/')
                    .append(TunnelConfig.MAPPED_PREFIX).append("\"]\n");
            out.append("address = [\"").append(TunnelConfig.MAPPED_DNS_ADDRESS).append("\"]\n");
            out.append("cache_size = ").append(TunnelConfig.MAPPED_CACHE_SIZE).append('\n');
        } else {
            out.append("fake_ip = false\n");
        }
        out.append("hijack = false\n");
        out.append("systemd_resolved = false\n");
        return out.toString();
    }

    /** IPv6 literals need brackets in host:port form. The value never contains a quote. */
    static String hostPort(String host, int port) {
        String clean = host.replace("\"", "").replace("\\", "");
        if (clean.indexOf(':') >= 0 && !clean.startsWith("[")) clean = "[" + clean + "]";
        return clean + ":" + port;
    }

    /** Readable text for the library's own error codes, for the connection log. */
    static String describe(int code) {
        switch (code) {
            case 0: return "ok";
            case -1: return "invalid argument";
            case -2: return "out of memory";
            case -3: return "permission denied";
            case -4: return "not supported on this device";
            case -5: return "device error";
            case -6: return "I/O error";
            case -7: return "already running";
            case -16: return "configuration rejected";
            default: return "error " + code;
        }
    }
}
