package com.firstham.aethergui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The location card is the one thing on the home screen that depends on a service we do not own,
 * and four of them are in the fallback chain. These cases pin the shapes each one actually returns,
 * so a provider that changes its output shows up here rather than as an empty card on a phone.
 */
public class ExitLocationTest {

    private static final String NL = "\uD83C\uDDF3\uD83C\uDDF1";
    private static final String DE = "\uD83C\uDDE9\uD83C\uDDEA";
    private static final String SE = "\uD83C\uDDF8\uD83C\uDDEA";
    private static final String CH = "\uD83C\uDDE8\uD83C\uDDED";

    @Test public void readsCloudflareMeta() {
        ExitLocation location = ExitLocation.parse("{\"hostname\":\"speed.cloudflare.com\","
                + "\"clientIp\":\"104.28.14.9\",\"asn\":13335,\"asOrganization\":\"Cloudflare\","
                + "\"colo\":\"AMS\",\"country\":\"NL\",\"city\":\"Amsterdam\",\"region\":\"North Holland\"}");
        assertTrue(location.usable());
        assertEquals(NL + "  Amsterdam, Netherlands", location.place());
        assertEquals("104.28.14.9  \u00b7  Cloudflare", location.detail());
    }

    @Test public void readsCloudflareTrace() {
        ExitLocation location = ExitLocation.parse("fl=1a\nh=www.cloudflare.com\nip=188.114.97.3\n"
                + "colo=FRA\nloc=DE\nwarp=on\nkex=X25519");
        assertEquals(DE + "  Germany", location.place());
        assertEquals("188.114.97.3", location.detail());
    }

    @Test public void readsNestedIspFromIpwho() {
        ExitLocation location = ExitLocation.parse("{\"ip\":\"45.83.12.9\",\"success\":true,"
                + "\"country\":\"Sweden\",\"country_code\":\"SE\",\"city\":\"Stockholm\","
                + "\"connection\":{\"asn\":9009,\"isp\":\"M247 Europe\"}}");
        assertEquals(SE + "  Stockholm, Sweden", location.place());
        assertEquals("45.83.12.9  \u00b7  M247 Europe", location.detail());
    }

    @Test public void unescapesIpapiCity() {
        ExitLocation location = ExitLocation.parse("{\"ip\":\"91.132.4.7\",\"city\":\"Z\\u00fcrich\","
                + "\"country\":\"CH\",\"country_code\":\"CH\",\"country_name\":\"Switzerland\",\"org\":\"Init7\"}");
        assertEquals(CH + "  Z\u00fcrich, Switzerland", location.place());
    }

    @Test public void rejectsInBandFailures() {
        // Both of these arrive with HTTP 200, so only the body says the lookup did not work.
        assertFalse(ExitLocation.parse("{\"error\":true,\"reason\":\"RateLimited\",\"country\":\"US\"}").usable());
        assertFalse(ExitLocation.parse("{\"success\":false,\"message\":\"Invalid IP\"}").usable());
        assertFalse(ExitLocation.parse("<html><body>502 Bad Gateway</body></html>").usable());
        assertFalse(ExitLocation.parse("").usable());
        assertFalse(ExitLocation.parse(null).usable());
    }

    @Test public void rejectsCloudflarePlaceholderCountries() {
        assertFalse(ExitLocation.parse("ip=1.1.1.1\nloc=XX").usable());
        assertFalse(ExitLocation.parse("ip=1.1.1.1\nloc=T1").usable());
        assertEquals("", ExitLocation.flag("ZZZ"));
        assertEquals("", ExitLocation.flag("1A"));
    }

    @Test public void ignoresKeysThatAreReallyStringContent() {
        assertEquals("GB", ExitLocation.parse(
                "{\"note\":\"we ignore \\\"country_code\\\": fake\",\"country_code\":\"GB\"}").countryCode);
    }

    @Test public void fallsBackThroughCityRegionCountry() {
        assertEquals("\uD83C\uDDEF\uD83C\uDDF5  Japan", ExitLocation.parse("{\"country\":\"JP\"}").place());
        assertEquals("\uD83C\uDDEB\uD83C\uDDF7  Nouvelle-Aquitaine, France",
                ExitLocation.parse("{\"country_code\":\"FR\",\"region\":\"Nouvelle-Aquitaine\"}").place());
        assertEquals("\uD83C\uDDFA\uD83C\uDDF8  United States",
                ExitLocation.parse("{\"country_code\":\"US\"}").place());
    }

    @Test public void neverLeavesAStraySeparator() {
        assertEquals("Cloudflare", ExitLocation.parse("{\"country\":\"NL\",\"asOrganization\":\"Cloudflare\"}").detail());
        assertEquals("1.2.3.4", ExitLocation.parse("{\"country\":\"NL\",\"ip\":\"1.2.3.4\"}").detail());
        assertEquals("", ExitLocation.parse("{\"country\":\"NL\"}").detail());
    }

    @Test public void survivesChunkedTransferEncoding() {
        // With Connection: close a server may still chunk, which puts hex length markers around
        // the payload. Neither format may be thrown off by them.
        assertEquals(NL + "  Amsterdam, Netherlands",
                ExitLocation.parse("6f\n{\"country\":\"NL\",\"city\":\"Amsterdam\"}\n0\n\n").place());
        assertEquals(DE + "  Germany",
                ExitLocation.parse("2a\nip=1.2.3.4\nloc=DE\nwarp=on\n0\n\n").place());
    }

    @Test public void toleratesNullsNumbersAndWhitespace() {
        assertEquals("", ExitLocation.parse("{\"country\":\"NL\",\"asn\":13335}").isp);
        assertEquals(NL + "  Netherlands", ExitLocation.parse("{\"country\":\"NL\",\"city\":null}").place());
        assertEquals("\uD83C\uDDE8\uD83C\uDDE6  Toronto, Canada",
                ExitLocation.parse("{\n  \"country_code\" : \"CA\" ,\n  \"city\" : \"Toronto\"\n}").place());
    }
}
