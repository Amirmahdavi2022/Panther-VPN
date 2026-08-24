package com.firstham.aethergui.vpngate;

import org.junit.Test;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The feed is volunteer-run and frequently malformed, so these tests are mostly about what the
 * parser refuses to choke on. A refresh that throws would empty the user's server list.
 */
public final class VpnGateDirectoryTest {

    private static String config(int length) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < length; i++) builder.append('A');
        return builder.toString();
    }

    private static String feed() {
        String ok = config(200);
        return "*vpn_servers\n"
                + "#HostName,IP,Score,Ping,Speed,CountryLong,CountryShort,NumVpnSessions,Uptime,"
                + "TotalUsers,TotalTraffic,LogType,Operator,Message,OpenVPN_ConfigData_Base64\n"
                + "vpnA,1.1.1.1,900000,12,50000000,Japan,JP,10,7200000,1,1,2w,op,," + ok + "\n"
                + "vpnB,2.2.2.2,500000,30,10000000,Japan,JP,5,3600000,1,1,2w,op,," + ok + "\n"
                + "vpnC,3.3.3.3,950000,8,90000000,United States,US,20,9000000,1,1,2w,op,," + ok + "\n"
                + "vpnD,4.4.4.4,100,5,1000,Korea,KR,1,100,1,1,2w,op,," + config(20) + "\n"
                + "vpnE,5.5.5.5,100,5,1000,Korea,KR\n"
                + "vpnF,,100,5,1000,Korea,KR,1,100,1,1,2w,op,," + ok + "\n"
                + "vpnG,7.7.7.7,abc,xyz,,Japan,JP,,,1,1,2w,op,," + ok + "\n"
                + "*\n";
    }

    @Test public void dropsRowsThatCannotBeConnectedTo() {
        // Short config, truncated row and missing IP are all unusable.
        assertEquals(4, VpnGateDirectory.parse(feed()).size());
    }

    @Test public void rankingPrefersHigherScore() {
        List<VpnGateServer> ranked = VpnGateDirectory.ranked(VpnGateDirectory.parse(feed()));
        assertEquals("vpnC", ranked.get(0).hostName);
        assertEquals("vpnC", VpnGateDirectory.best(VpnGateDirectory.parse(feed())).hostName);
    }

    @Test public void countryFilterIsCaseInsensitiveAndOrdered() {
        List<VpnGateServer> japan = VpnGateDirectory.inCountry(VpnGateDirectory.parse(feed()), "jp");
        assertEquals(3, japan.size());
        assertEquals("vpnA", japan.get(0).hostName);
        // The row with unparseable numbers scores zero and must sort last, not first.
        assertEquals("vpnG", japan.get(2).hostName);
    }

    @Test public void countriesAreOrderedByTheirBestServer() {
        Map<String, Integer> countries = VpnGateDirectory.countries(VpnGateDirectory.parse(feed()));
        assertEquals(2, countries.size());
        assertEquals("US", countries.keySet().iterator().next());
        assertEquals(Integer.valueOf(3), countries.get("JP"));
    }

    @Test public void malformedInputNeverThrows() {
        assertTrue(VpnGateDirectory.parse(null).isEmpty());
        assertTrue(VpnGateDirectory.parse("").isEmpty());
        assertTrue(VpnGateDirectory.parse("not,a,feed\n\n").isEmpty());
        assertTrue(VpnGateDirectory.inCountry(VpnGateDirectory.parse(feed()), null).isEmpty());
        assertTrue(VpnGateDirectory.inCountry(VpnGateDirectory.parse(feed()), "ZZ").isEmpty());
        assertNull(VpnGateDirectory.best(VpnGateDirectory.parse("")));
    }

    @Test public void derivedValuesAreCorrect() {
        VpnGateServer server = VpnGateDirectory.inCountry(VpnGateDirectory.parse(feed()), "JP").get(0);
        assertEquals(50.0, server.speedMbps(), 0.001);
        assertEquals(2, server.uptimeHours());
        assertEquals("Japan", VpnGateDirectory.countryName(VpnGateDirectory.parse(feed()), "JP"));
        assertFalse(server.key().isEmpty());
    }
}
