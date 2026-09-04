package com.firstham.aethergui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Checks for the Stealth exit-country catalogue and the flag-to-country derivation. */
public class StealthRegionsTest {

    private static ProxyConfig labelled(String host, String label) {
        return ProxyConfig.parse("vless://11111111-2222-3333-4444-555555555555@" + host
                + ":443?security=tls#" + label);
    }

    @Test public void normalisesCase() {
        assertEquals("NL", StealthRegions.normalise("nl"));
        assertEquals("DE", StealthRegions.normalise("  de "));
    }

    @Test public void rejectsAnythingThatIsNotACountryCode() {
        assertEquals("", StealthRegions.normalise(null));
        assertEquals("", StealthRegions.normalise(""));
        assertEquals("", StealthRegions.normalise("USA"));
        assertEquals("", StealthRegions.normalise("12"));
        assertEquals("", StealthRegions.normalise("U1"));
        assertTrue(StealthRegions.isAutomatic("USA"));
    }

    @Test public void namesCountries() {
        assertEquals("Netherlands", StealthRegions.name("NL"));
        assertEquals("United Kingdom", StealthRegions.name("gb"));
        assertEquals("", StealthRegions.name(""));
    }

    /**
     * A code with no name in this build must read as itself, never as the JVM's placeholder —
     * "Unknown Region" is what an unguarded getDisplayCountry actually returns here.
     */
    @Test public void unknownCodeFallsBackToTheCode() {
        assertEquals("ZZ", StealthRegions.name("ZZ"));
    }

    /** Every country we offer has to be nameable, or the picker shows a bare code to the user. */
    @Test public void everyOfferedCountryHasAName() {
        for (String code : StealthRegions.offered()) {
            String name = StealthRegions.name(code);
            assertFalse(code + " has no name", name.equals(code));
            assertTrue(code, name.length() > 2);
        }
    }

    @Test public void offersOnlyCountriesWithMeasuredSupply() {
        List<String> offered = StealthRegions.offered();
        assertTrue(offered.size() >= 15);
        assertTrue(offered.contains("NL"));
        assertTrue(offered.contains("US"));
        assertTrue(offered.contains("DE"));
        // Every offered code must be a real code and must be recognised by isOffered.
        for (String code : offered) {
            assertEquals(code, StealthRegions.normalise(code));
            assertTrue(code, StealthRegions.isOffered(code));
        }
        assertEquals(offered.size(), new java.util.LinkedHashSet<>(offered).size());
    }

    /** An Iranian exit is not an exit for anyone using this app. */
    @Test public void doesNotOfferIran() {
        assertFalse(StealthRegions.offered().contains("IR"));
        assertFalse(StealthRegions.isOffered("IR"));
        assertNull(StealthRegions.pathFor("IR"));
    }

    @Test public void buildsTheSourcePathForAnOfferedCountry() {
        assertEquals("/Delta-Kronecker/V2ray-Config/main/config/countries/nl.txt",
                StealthRegions.pathFor("NL"));
        assertEquals(StealthRegions.pathFor("NL"), StealthRegions.pathFor("nl"));
    }

    /** Automatic and anything unoffered have no list to fetch, and must say so rather than 404. */
    @Test public void refusesToBuildAPathWeCannotFetch() {
        assertNull(StealthRegions.pathFor(StealthRegions.AUTOMATIC));
        assertNull(StealthRegions.pathFor(null));
        assertNull(StealthRegions.pathFor("ZZ"));
    }

    @Test public void readsTheCountryOffTheFlagEmoji() {
        assertEquals("DE", StealthRegions.countryOfLabel("\uD83C\uDDE9\uD83C\uDDEA Germany-8083"));
        assertEquals("NL", StealthRegions.countryOfLabel("\uD83C\uDDF3\uD83C\uDDF1"));
        assertEquals("DE", StealthRegions.countryOfLabel(
                "DE \uD83C\uDDE9\uD83C\uDDEA | @channel | 96D132"));
    }

    /** Half these labels are in languages we have no table for; the flag is the only signal. */
    @Test public void aLabelWithNoFlagHasNoCountry() {
        assertEquals("", StealthRegions.countryOfLabel(null));
        assertEquals("", StealthRegions.countryOfLabel(""));
        assertEquals("", StealthRegions.countryOfLabel("Germany"));
        assertEquals("", StealthRegions.countryOfLabel("Global \uD83C\uDF10 | @channel"));
        // A lone regional indicator is not a country.
        assertEquals("", StealthRegions.countryOfLabel("\uD83C\uDDE9 one half"));
    }

    /** Some labels carry a route as two flags; the server's own country is the first. */
    @Test public void takesTheFirstFlagWhenThereAreSeveral() {
        assertEquals("DE", StealthRegions.countryOfLabel(
                "\uD83C\uDDE9\uD83C\uDDEA \u2192 \uD83C\uDDF3\uD83C\uDDF1"));
    }

    @Test public void derivesTheCountryOfAWholeConfig() {
        assertEquals("NL", StealthRegions.countryOf(labelled("a.example.com", "\uD83C\uDDF3\uD83C\uDDF1 NL-1")));
        assertEquals("", StealthRegions.countryOf(labelled("b.example.com", "no flag here")));
        assertEquals("", StealthRegions.countryOf(null));
    }

    @Test public void matchesOnlyTheWantedCountry() {
        ProxyConfig dutch = labelled("a.example.com", "\uD83C\uDDF3\uD83C\uDDF1 NL-1");
        ProxyConfig unknown = labelled("b.example.com", "plain");
        assertTrue(StealthRegions.matches(dutch, "NL"));
        assertFalse(StealthRegions.matches(dutch, "DE"));
        assertFalse(StealthRegions.matches(unknown, "NL"));
    }

    /** Automatic must never filter anything out, including endpoints with no country at all. */
    @Test public void automaticMatchesEverything() {
        assertTrue(StealthRegions.matches(labelled("a.example.com", "plain"),
                StealthRegions.AUTOMATIC));
        assertTrue(StealthRegions.matches(labelled("b.example.com", "\uD83C\uDDF3\uD83C\uDDF1"), null));
        assertTrue(StealthRegions.matches(labelled("c.example.com", "\uD83C\uDDF3\uD83C\uDDF1"), "rubbish"));
    }

    @Test public void listsTheCountriesAPoolActuallyCovers() {
        List<ProxyConfig> configs = new ArrayList<>(Arrays.asList(
                labelled("a.example.com", "\uD83C\uDDE9\uD83C\uDDEA one"),
                labelled("b.example.com", "\uD83C\uDDF3\uD83C\uDDF1 two"),
                labelled("c.example.com", "\uD83C\uDDF3\uD83C\uDDF1 three"),
                labelled("d.example.com", "no flag")));
        assertEquals(Arrays.asList("NL", "DE"), StealthRegions.present(configs));
        assertEquals(0, StealthRegions.present(null).size());
        assertEquals(0, StealthRegions.present(new ArrayList<ProxyConfig>()).size());
    }

    public static void main(String[] args) throws Exception {
        int failures = 0;
        StealthRegionsTest suite = new StealthRegionsTest();
        for (java.lang.reflect.Method method : StealthRegionsTest.class.getDeclaredMethods()) {
            if (method.getAnnotation(Test.class) == null) continue;
            try {
                method.invoke(suite);
                System.out.println("ok   " + method.getName());
            } catch (Exception failed) {
                failures++;
                System.out.println("FAIL " + method.getName() + ": " + failed.getCause());
            }
        }
        System.out.println(failures == 0 ? "all passed" : failures + " failed");
    }
}
