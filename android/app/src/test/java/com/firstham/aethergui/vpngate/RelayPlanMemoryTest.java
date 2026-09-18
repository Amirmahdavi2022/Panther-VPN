package com.firstham.aethergui.vpngate;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The memory is allowed to reorder the candidate list and nothing else. Letting it add to the list
 * would be a way for a remembered relay to pull a connection into a country the user did not ask
 * for, which is the one failure this engine must never have.
 */
public final class RelayPlanMemoryTest {

    private static String config(int length) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < length; i++) builder.append('A');
        return builder.toString();
    }

    private static List<VpnGateServer> feed() {
        String ok = config(200);
        StringBuilder csv = new StringBuilder("*vpn_servers\n");
        csv.append("#HostName,IP,Score,Ping,Speed,CountryLong,CountryShort,NumVpnSessions,Uptime,")
                .append("TotalUsers,TotalTraffic,LogType,Operator,Message,OpenVPN_ConfigData_Base64\n");
        csv.append("jpBest,1.1.1.1,900000,10,50000000,Japan,JP,1,100,1,1,2w,op,,").append(ok).append("\n");
        csv.append("jpMid,2.2.2.2,500000,20,10000000,Japan,JP,1,100,1,1,2w,op,,").append(ok).append("\n");
        csv.append("jpWorst,3.3.3.3,100,90,100000,Japan,JP,1,100,1,1,2w,op,,").append(ok).append("\n");
        csv.append("usBest,4.4.4.4,990000,5,90000000,United States,US,1,100,1,1,2w,op,,").append(ok).append("\n");
        csv.append("*\n");
        return VpnGateDirectory.parse(csv.toString());
    }

    @Test public void aRelayThatWorkedHereLeadsTheListAheadOfABetterScoredOne() {
        List<VpnGateServer> plan = RelayPlan.candidates(feed(), "JP", 5,
                Collections.singletonList("jpWorst@3.3.3.3"));
        assertEquals("jpWorst", plan.get(0).hostName);
        // The rest keep their own order behind it.
        assertEquals("jpBest", plan.get(1).hostName);
        assertEquals("jpMid", plan.get(2).hostName);
    }

    @Test public void aRememberedRelayInAnotherCountryIsNotPulledIn() {
        List<VpnGateServer> plan = RelayPlan.candidates(feed(), "JP", 5,
                Collections.singletonList("usBest@4.4.4.4"));
        assertEquals(3, plan.size());
        for (VpnGateServer server : plan) assertEquals("JP", server.countryCode);
        assertEquals("jpBest", plan.get(0).hostName);
    }

    @Test public void aRememberedRelayThatHasLeftTheDirectoryIsSimplyIgnored() {
        List<VpnGateServer> plan = RelayPlan.candidates(feed(), "JP", 5,
                Arrays.asList("gone@9.9.9.9", "jpMid@2.2.2.2"));
        assertEquals("jpMid", plan.get(0).hostName);
        assertEquals(3, plan.size());
    }

    @Test public void noHistoryLeavesTheOrderExactlyAsItWas() {
        List<VpnGateServer> withNull = RelayPlan.candidates(feed(), "JP", 5, null);
        List<VpnGateServer> withNone = RelayPlan.candidates(feed(), "JP", 5, Collections.emptyList());
        List<VpnGateServer> plain = RelayPlan.candidates(feed(), "JP", 5);
        for (int i = 0; i < plain.size(); i++) {
            assertEquals(plain.get(i).hostName, withNull.get(i).hostName);
            assertEquals(plain.get(i).hostName, withNone.get(i).hostName);
        }
    }

    @Test public void theCapStillHoldsWithHistoryInPlay() {
        List<VpnGateServer> plan = RelayPlan.candidates(feed(), null, 2,
                Collections.singletonList("jpWorst@3.3.3.3"));
        assertEquals(2, plan.size());
        assertEquals("jpWorst", plan.get(0).hostName);
    }

    @Test public void automaticAlsoLeadsWithWhatWorkedHere() {
        List<VpnGateServer> plan = RelayPlan.candidates(feed(), null, 5,
                Collections.singletonList("jpMid@2.2.2.2"));
        assertEquals("jpMid", plan.get(0).hostName);
        assertTrue(plan.size() == 4);
    }
}
