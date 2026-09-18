package com.firstham.aethergui.vpngate;

import java.util.ArrayList;
import java.util.List;

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
        if (servers == null || servers.isEmpty() || limit <= 0) return new ArrayList<>();
        List<VpnGateServer> ranked = countryCode == null || countryCode.trim().isEmpty()
                ? VpnGateDirectory.ranked(servers)
                : VpnGateDirectory.inCountry(servers, countryCode);
        return ranked.size() <= limit ? ranked : new ArrayList<>(ranked.subList(0, limit));
    }

    public static List<VpnGateServer> candidates(List<VpnGateServer> servers, String countryCode) {
        return candidates(servers, countryCode, MAX_ATTEMPTS);
    }
}
