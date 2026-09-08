package com.firstham.aethergui;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Extra environment variables handed to the Turbo core, on top of the ones the user picks in
 * settings.
 *
 * <p>These are core options that already exist in the pinned build and were never being set, so
 * the core fell back to its own defaults. Both were read out of the core's own source at the
 * pinned version rather than from documentation, because the defaults are not written down
 * anywhere the user could see them.
 *
 * <h3>Why the stale timeout is raised</h3>
 *
 * <p>The core runs a health probe on a WireGuard tunnel every three seconds and declares the
 * tunnel dead once no valid packet has come back for {@code AETHER_WG_STALE_SECS}, which defaults
 * to ten. Ten seconds is roughly three missed probes. On a mobile network that is an ordinary
 * event: a handover between cells, a moment of congestion, a screen-off radio doze. A device log
 * from this app showed exactly that - "no valid data from peer in 10.79s" - on a tunnel that was
 * carrying traffic before and after, and the tunnel was torn down for it.
 *
 * <p>The cost of the tear-down is not only the reconnect. When the Prowl engine is dialling an
 * endpoint through this tunnel as its carrier, the carrier dying under the dial looks exactly like
 * the endpoint refusing traffic, so a healthy server gets benched in the pool for something it did
 * not do. Thirty seconds is ten missed probes: still short enough that a genuinely dead tunnel is
 * noticed and restarted quickly, long enough that a normal mobile hiccup is ridden out.
 *
 * <p>Only set for the protocols that own a WireGuard tunnel. MASQUE has its own liveness settings
 * and ignores this one, so setting it there would be noise in the environment.
 *
 * <h3>Why the handshake is fragmented on the HTTP/2 transport</h3>
 *
 * <p>MASQUE over HTTP/2 is the one Turbo mode that makes an ordinary TLS connection to port 443
 * over TCP. That is also the one mode where a middlebox can read the server name straight out of
 * the client hello and drop the connection on it. The core can split that first record into small
 * randomly sized pieces with small random gaps, which defeats equipment that matches on a single
 * packet, and it costs a few milliseconds once per connection.
 *
 * <p>Deliberately left at the core's own size and delay defaults rather than pinned here. They are
 * the values the core author chose, they are randomised per connection, and a fixed pattern of our
 * own would be one more thing to fingerprint.
 *
 * <p>Not applied to the HTTP/3 transport or to WireGuard: both are UDP and have no TLS record to
 * split, so the setting would do nothing.
 *
 * <p>Free of Android imports on purpose, so what the core is actually launched with can be checked
 * on a desktop JVM instead of being read by eye.
 */
final class CoreTuning {

    /** How long a WireGuard tunnel may go silent before the core gives up on it, in seconds. */
    static final String WIREGUARD_STALE_SECONDS = "30";

    /**
     * Builds the extra environment for a run.
     *
     * @param protocol  the core protocol: {@code masque}, {@code wg} or {@code gool}
     * @param transport {@code h2} or {@code h3}; only meaningful for {@code masque}
     * @return the variables to add, in a stable order, never null
     */
    static Map<String, String> environment(String protocol, String transport) {
        Map<String, String> env = new LinkedHashMap<>();
        String core = normalise(protocol);
        String hop = normalise(transport);

        if (usesWireguardTunnel(core)) {
            env.put("AETHER_WG_STALE_SECS", WIREGUARD_STALE_SECONDS);
        }
        if (fragmentsHandshake(core, hop)) {
            env.put("AETHER_MASQUE_H2_FRAGMENT", "1");
        }
        return env;
    }

    /**
     * Whether this protocol carries its traffic over a WireGuard tunnel.
     *
     * <p>{@code gool} is warp-in-warp: two WireGuard tunnels, one inside the other. Both are
     * governed by the same stale timeout, so it counts.
     */
    static boolean usesWireguardTunnel(String protocol) {
        String core = normalise(protocol);
        return "wg".equals(core) || "gool".equals(core);
    }

    /** Whether the handshake for this combination is a TCP TLS handshake worth splitting. */
    static boolean fragmentsHandshake(String protocol, String transport) {
        return "masque".equals(normalise(protocol)) && "h2".equals(normalise(transport));
    }

    private static String normalise(String value) {
        return value == null ? "" : value.trim().toLowerCase(java.util.Locale.ROOT);
    }

    private CoreTuning() { }
}
