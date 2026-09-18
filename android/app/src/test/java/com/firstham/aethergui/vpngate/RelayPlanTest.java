package com.firstham.aethergui.vpngate;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The candidate list is the part of the relay path worth being sure about: get the order wrong and
 * every connect starts on a worse server than the one it had, and get the cap wrong and a connect
 * either gives up too early or walks forty dead machines while the screen says nothing.
 */
public final class RelayPlanTest {

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
        csv.append("jp1,1.1.1.1,900000,12,50000000,Japan,JP,10,7200000,1,1,2w,op,,").append(ok).append("\n");
        csv.append("jp2,2.2.2.2,500000,30,10000000,Japan,JP,5,3600000,1,1,2w,op,,").append(ok).append("\n");
        csv.append("us1,3.3.3.3,950000,8,90000000,United States,US,20,9000000,1,1,2w,op,,").append(ok).append("\n");
        for (int i = 0; i < 8; i++) {
            csv.append("kr").append(i).append(",9.9.9.").append(i).append(",")
                    .append(1000 + i).append(",20,1000000,Korea,KR,1,100,1,1,2w,op,,")
                    .append(ok).append("\n");
        }
        csv.append("*\n");
        return VpnGateDirectory.parse(csv.toString());
    }

    @Test public void automaticTakesTheBestRelayAnywhere() {
        List<VpnGateServer> plan = RelayPlan.candidates(feed(), null);
        assertEquals("us1", plan.get(0).hostName);
        assertEquals("jp1", plan.get(1).hostName);
    }

    @Test public void aChosenCountryNeverLeavesIt() {
        List<VpnGateServer> plan = RelayPlan.candidates(feed(), "JP");
        assertEquals(2, plan.size());
        for (VpnGateServer server : plan) assertEquals("JP", server.countryCode);
        assertEquals("jp1", plan.get(0).hostName);
    }

    @Test public void theCountryCodeIsMatchedCaseInsensitively() {
        assertEquals(2, RelayPlan.candidates(feed(), "jp").size());
        assertEquals(2, RelayPlan.candidates(feed(), " Jp ").size());
    }

    @Test public void blankIsTreatedAsAutomaticRatherThanAsACountry() {
        assertEquals(RelayPlan.MAX_ATTEMPTS, RelayPlan.candidates(feed(), "").size());
        assertEquals(RelayPlan.MAX_ATTEMPTS, RelayPlan.candidates(feed(), "   ").size());
    }

    @Test public void theListIsCappedSoAConnectCannotRunForever() {
        // Eleven usable relays in the feed, five attempts allowed.
        assertEquals(RelayPlan.MAX_ATTEMPTS, RelayPlan.candidates(feed(), null).size());
        assertEquals(2, RelayPlan.candidates(feed(), null, 2).size());
    }

    @Test public void aCountryWithNothingBehindItComesBackEmptyRatherThanFallingBackToAnother() {
        assertTrue(RelayPlan.candidates(feed(), "ZZ").isEmpty());
    }

    @Test public void survivesAnEmptyDirectory() {
        assertTrue(RelayPlan.candidates(new ArrayList<>(), null).isEmpty());
        assertTrue(RelayPlan.candidates(null, "JP").isEmpty());
        assertTrue(RelayPlan.candidates(feed(), null, 0).isEmpty());
    }
}
