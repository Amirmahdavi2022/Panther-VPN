package com.firstham.aethergui.vpngate;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Chooses which relays a connect will walk through, and in what order.
 *
 * Kept apart from the engine on purpose: this is the one part of the relay path that is worth
 * being sure about, and it is plain Java with no Android in it, so it can be tested for real
 * rather than reasoned about from a screenshot.
 *
 * The rule is the same in both modes - best first, capped. The cap exists because a connect that
 * quietly works through forty dead volunteer machines is indistinguishable from a frozen app; a
 * handful of tries either finds something or the honest answer is that nothing here is reachable
 * right now.
 *
 * <p>Relays this phone has actually connected through lead the list. The feed's own score is a
 * global average and says nothing about whether a machine answers from the network you are on
 * right now, which is the only question a connect is really asking. Anything the device has proved
 * for itself beats anything the feed asserts - the same reasoning Prowl's pool already runs on.
 */
public final class RelayPlan {

    /** How many relays one connect is allowed to try before it reports failure. */
    public static final int MAX_ATTEMPTS = 5;

    private RelayPlan() {
    }

    /**
     * The ordered candidate list for a connect.
     *
     * @param servers     the whole directory
     * @param countryCode the chosen exit country, or null for Automatic
     * @param limit       how many candidates to keep
     * @return best-first candidates, never null, empty when the country has nothing behind it
     */
    public static List<VpnGateServer> candidates(List<VpnGateServer> servers, String countryCode,
                                                 int limit) {
        return candidates(servers, countryCode, limit, null);
    }

    public static List<VpnGateServer> candidates(List<VpnGateServer> servers, String countryCode) {
        return candidates(servers, countryCode, MAX_ATTEMPTS, null);
    }

    /**
     * The ordered candidate list, with relays this phone has connected through before first.
     *
     * <p>A remembered relay still has to be in the directory and still has to be in the chosen
     * country - the memory reorders the list, it never adds to it, so it can never send a
     * connection somewhere the user did not ask for.
     *
     * @param knownGood keys ({@link VpnGateServer#key()}) of relays that have worked here, best
     *                  remembered first; null or empty simply means no history yet
     */
    public static List<VpnGateServer> candidates(List<VpnGateServer> servers, String countryCode,
                                                 int limit, Collection<String> knownGood) {
        if (servers == null || servers.isEmpty() || limit <= 0) return new ArrayList<>();
        List<VpnGateServer> ranked = countryCode == null || countryCode.trim().isEmpty()
                ? VpnGateDirectory.ranked(servers)
                : VpnGateDirectory.inCountry(servers, countryCode);

        if (knownGood != null && !knownGood.isEmpty()) {
            Set<String> wanted = new LinkedHashSet<>(knownGood);
            List<VpnGateServer> proven = new ArrayList<>();
            List<VpnGateServer> rest = new ArrayList<>();
            for (VpnGateServer server : ranked) {
                if (wanted.contains(server.key())) proven.add(server);
                else rest.add(server);
            }
            proven.addAll(rest);
            ranked = proven;
        }

        return ranked.size() <= limit ? ranked : new ArrayList<>(ranked.subList(0, limit));
    }
}
