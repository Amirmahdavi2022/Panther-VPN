package com.firstham.aethergui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Checks for the region catalogue behind the Global exit-country picker. */
public class GlobalRegionsTest {

    @Test public void normalisesCase() {
        assertEquals("NL", GlobalRegions.normalise("nl"));
        assertEquals("DE", GlobalRegions.normalise("  de "));
    }

    @Test public void rejectsAnythingThatIsNotACountryCode() {
        assertEquals("", GlobalRegions.normalise(null));
        assertEquals("", GlobalRegions.normalise(""));
        assertEquals("", GlobalRegions.normalise("USA"));
        assertEquals("", GlobalRegions.normalise("12"));
        assertEquals("", GlobalRegions.normalise("U1"));
    }

    @Test public void namesCountries() {
        assertEquals("Netherlands", GlobalRegions.name("NL"));
        assertEquals("United Kingdom", GlobalRegions.name("gb"));
        assertEquals("", GlobalRegions.name(""));
    }

    /** A code this build has no name for must still be selectable, shown as its bare code. */
    @Test public void unknownCodeFallsBackToTheCode() {
        assertEquals("ZZ", GlobalRegions.name("ZZ"));
    }

    @Test public void mergeKeepsReportedAndBuiltInAndDropsJunk() {
        List<String> merged = GlobalRegions.merge(Arrays.asList("nl", "ZZ", "nl", null, "x"));
        assertTrue(merged.contains("ZZ"));
        assertTrue(merged.contains("US"));
        assertEquals(1, Collections.frequency(merged, "NL"));
        assertFalse(merged.contains("X"));
        assertFalse(merged.contains(""));
    }

    @Test public void mergeIsSortedByTheNameTheUserReads() {
        List<String> merged = GlobalRegions.merge(null);
        List<String> sorted = new ArrayList<>(merged);
        sorted.sort((a, b) -> GlobalRegions.name(a).compareToIgnoreCase(GlobalRegions.name(b)));
        assertEquals(sorted, merged);
    }

    @Test public void mergeSurvivesNoReportedList() {
        assertTrue(GlobalRegions.merge(null).size() >= 29);
    }

    @Test public void encodeDecodeRoundTrips() {
        List<String> codes = Arrays.asList("NL", "DE", "US");
        assertEquals("NL,DE,US", GlobalRegions.encode(codes));
        assertEquals(codes, GlobalRegions.decode(GlobalRegions.encode(codes)));
    }

    /** The stored value crosses an app update, so it is treated as untrusted input. */
    @Test public void decodeToleratesRubbish() {
        assertEquals(0, GlobalRegions.decode(null).size());
        assertEquals(0, GlobalRegions.decode("").size());
        assertEquals(Arrays.asList("NL", "DE"), GlobalRegions.decode("NL,,xyz,DE"));
        assertEquals(Arrays.asList("NL"), GlobalRegions.decode("NL,nl,NL"));
        assertEquals("", GlobalRegions.encode(null));
    }
}
