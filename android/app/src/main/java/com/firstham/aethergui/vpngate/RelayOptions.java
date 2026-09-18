package com.firstham.aethergui.vpngate;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * The settings a relay connection has to carry, worked out from what the user chose.
 *
 * <p>This exists because the relay engine builds its tunnel somewhere else entirely. The other
 * three engines all end up in {@code AetherVpnService.establishVpn}, which reads the settings
 * screen and applies split tunnelling, the MTU, the DNS choice and the kill switch to the
 * interface it builds. Relay's interface is built by the OpenVPN engine from a profile, so none of
 * that reached it: every relay connection routed every app, at the default MTU, with DNS forced
 * and the kill switch effectively always on - whatever the user had actually chosen.
 *
 * <p>The translation is the part worth testing, so it lives here as plain values with no Android
 * in it, and {@link VpnGateConnector} does nothing but copy them onto the profile.
 */
public final class RelayOptions {

    /** Stored values of the "routing" preference. They are indexes into the engine's own list. */
    public static final int ROUTING_DEFAULT = 0;
    public static final int ROUTING_FULL = 1;
    public static final int ROUTING_INCLUDE = 2;
    public static final int ROUTING_EXCLUDE = 3;

    private static final int MTU_MIN = 1280;
    private static final int MTU_MAX = 9000;
    private static final int MTU_DEFAULT = 1500;

    /**
     * True when {@link #apps} is a list to keep <em>out</em> of the tunnel, false when it is the
     * only list allowed <em>in</em>. Matches the OpenVPN profile field of the same meaning.
     */
    public final boolean appsAreExcluded;

    /** The packages the choice applies to. Never null; empty means "no per-app rule". */
    public final Set<String> apps;

    /** Tunnel MTU, already clamped to what the engine will accept. */
    public final int mtu;

    /** True when the tunnel should stay up and blackhole traffic rather than leak it. */
    public final boolean killSwitch;

    /** The resolvers to push, or an empty array to leave the server's own DNS in place. */
    public final String[] dns;

    RelayOptions(boolean appsAreExcluded, Set<String> apps, int mtu, boolean killSwitch,
                 String[] dns) {
        this.appsAreExcluded = appsAreExcluded;
        this.apps = Collections.unmodifiableSet(apps);
        this.mtu = mtu;
        this.killSwitch = killSwitch;
        this.dns = dns;
    }

    /**
     * Builds the options for one connection.
     *
     * <p>The per-app rules mirror {@code AetherVpnService.applySplitApps} exactly, because a
     * setting that means one thing on Turbo and another on Relay is worse than one that is
     * ignored: at least an ignored setting is visibly ignored.
     *
     * <ul>
     *   <li>No split: everything goes through the tunnel except Panther itself, so the update
     *       check and the relay list are not carried by the tunnel they are meant to fix.</li>
     *   <li>Include: only the chosen apps, and Panther is not silently added to them.</li>
     *   <li>Exclude: the chosen apps stay out, and so does Panther.</li>
     * </ul>
     *
     * @param routing      the stored routing preference
     * @param packages     the stored package list, comma or newline separated
     * @param ownPackage   Panther's own package name
     * @param mtu          the stored MTU, as typed
     * @param killSwitch   the stored kill switch choice
     * @param leakProtect  the stored DNS choice
     * @param dnsServers   the resolvers to use when DNS is being overridden
     */
    public static RelayOptions of(int routing, String packages, String ownPackage, String mtu,
                                  boolean killSwitch, boolean leakProtect, String[] dnsServers) {
        boolean include = routing == ROUTING_INCLUDE;
        boolean exclude = routing == ROUTING_EXCLUDE;

        Set<String> chosen = new LinkedHashSet<>();
        if (include || exclude) {
            for (String candidate : split(packages)) {
                // Panther is handled by the rules below, never by the user's list: adding it to an
                // include list would tunnel the app's own traffic, and it is already excluded in
                // every other mode.
                if (candidate.equals(ownPackage)) continue;
                chosen.add(candidate);
            }
        }

        // An include list that ended up empty is not a rule, it is a mistake - and applied
        // literally it would mean "tunnel nothing", which looks exactly like a broken VPN. Fall
        // back to the ordinary shape instead.
        if (include && chosen.isEmpty()) include = false;

        if (!include) {
            // Everything except Panther, plus whatever the user excluded.
            if (ownPackage != null && !ownPackage.isEmpty()) chosen.add(ownPackage);
            return new RelayOptions(true, chosen, clampMtu(mtu), killSwitch,
                    leakProtect ? dnsServers : new String[0]);
        }
        return new RelayOptions(false, chosen, clampMtu(mtu), killSwitch,
                leakProtect ? dnsServers : new String[0]);
    }

    /** Same bounds the other engines clamp to, so one MTU cannot mean two things. */
    public static int clampMtu(String value) {
        try {
            return Math.max(MTU_MIN, Math.min(MTU_MAX, Integer.parseInt(value.trim())));
        } catch (Exception ignored) {
            return MTU_DEFAULT;
        }
    }

    private static Set<String> split(String value) {
        Set<String> out = new LinkedHashSet<>();
        if (value == null) return out;
        for (String part : value.split("[\\r\\n,]+")) {
            // Package names are case sensitive on Android, so they are kept exactly as stored.
            String name = part.trim();
            if (!name.isEmpty()) out.add(name);
        }
        return out;
    }
}
