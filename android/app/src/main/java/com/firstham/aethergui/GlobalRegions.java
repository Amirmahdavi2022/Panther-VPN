package com.firstham.aethergui;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * The exit countries the Global engine can be asked for.
 *
 * <p>Two sources feed this. The engine itself lists the regions it is currently offering, which is
 * the only authoritative answer and changes between runs; and a built-in list, which is what the
 * picker shows before the engine has ever been up. The built-in list is a starting point, not a
 * promise - {@link #merge} is what the picker actually renders, and anything the engine reports
 * wins over anything hardcoded here.
 *
 * <p>Deliberately free of every Android type, so it runs and is checked on a desktop JVM.
 */
public final class GlobalRegions {

    /** The stored value that means "let the engine decide". Matches {@link GlobalCore#REGION_AUTOMATIC}. */
    public static final String AUTOMATIC = "";

    /**
     * What the picker offers on a cold install, before the engine has listed anything.
     *
     * <p>Kept deliberately short and to countries the engine has historically offered. A code that
     * turns out not to be on offer is not a crash: asking for an unavailable region makes the
     * engine fall back to choosing for itself.
     */
    private static final String[] FALLBACK = {
            "AT", "BE", "BG", "CA", "CH", "CZ", "DE", "DK", "EE", "ES", "FI", "FR", "GB",
            "HU", "IE", "IN", "IT", "JP", "LV", "NL", "NO", "PL", "RO", "RS", "SE", "SG",
            "SK", "UA", "US",
    };

    private static final Map<String, String> NAMES = new TreeMap<>();

    static {
        put("AT", "Austria");        put("AU", "Australia");      put("BE", "Belgium");
        put("BG", "Bulgaria");       put("BR", "Brazil");         put("CA", "Canada");
        put("CH", "Switzerland");    put("CL", "Chile");          put("CZ", "Czechia");
        put("DE", "Germany");        put("DK", "Denmark");        put("EE", "Estonia");
        put("ES", "Spain");          put("FI", "Finland");        put("FR", "France");
        put("GB", "United Kingdom"); put("GR", "Greece");         put("HK", "Hong Kong");
        put("HR", "Croatia");        put("HU", "Hungary");        put("ID", "Indonesia");
        put("IE", "Ireland");        put("IL", "Israel");         put("IN", "India");
        put("IS", "Iceland");        put("IT", "Italy");          put("JP", "Japan");
        put("KR", "South Korea");    put("LT", "Lithuania");      put("LU", "Luxembourg");
        put("LV", "Latvia");         put("MD", "Moldova");        put("MX", "Mexico");
        put("MY", "Malaysia");       put("NL", "Netherlands");    put("NO", "Norway");
        put("NZ", "New Zealand");    put("PH", "Philippines");    put("PL", "Poland");
        put("PT", "Portugal");       put("RO", "Romania");        put("RS", "Serbia");
        put("SE", "Sweden");         put("SG", "Singapore");      put("SI", "Slovenia");
        put("SK", "Slovakia");       put("TH", "Thailand");       put("TR", "Turkey");
        put("TW", "Taiwan");         put("UA", "Ukraine");        put("US", "United States");
        put("ZA", "South Africa");
    }

    private static void put(String code, String name) { NAMES.put(code, name); }

    private GlobalRegions() { }

    /** Two letters, upper case, or empty for anything that is not a country code. */
    public static String normalise(String code) {
        if (code == null) return AUTOMATIC;
        String trimmed = code.trim().toUpperCase(Locale.US);
        if (trimmed.length() != 2) return AUTOMATIC;
        for (int i = 0; i < 2; i++) {
            char c = trimmed.charAt(i);
            if (c < 'A' || c > 'Z') return AUTOMATIC;
        }
        return trimmed;
    }

    /** The English country name, or the bare code when it is one this build has no name for. */
    public static String name(String code) {
        String normalised = normalise(code);
        if (normalised.isEmpty()) return "";
        String name = NAMES.get(normalised);
        return name == null ? normalised : name;
    }

    /**
     * What the picker should list: everything the engine reported, plus the built-in list, with
     * duplicates and junk removed, sorted by the name the user will actually read.
     *
     * <p>The built-in entries are kept even when the engine has reported its own list, because a
     * region the engine did not mention on this particular run is usually still selectable on the
     * next one - dropping it would make the menu shrink and grow between connections for no reason
     * the user could see.
     */
    public static List<String> merge(List<String> reported) {
        LinkedHashSet<String> codes = new LinkedHashSet<>();
        if (reported != null) {
            for (String value : reported) {
                String code = normalise(value);
                if (!code.isEmpty()) codes.add(code);
            }
        }
        codes.addAll(Arrays.asList(FALLBACK));
        List<String> all = new ArrayList<>(codes);
        all.sort((left, right) -> {
            int byName = name(left).compareToIgnoreCase(name(right));
            return byName != 0 ? byName : left.compareTo(right);
        });
        return all;
    }

    /** Packs a region list into one preference string. */
    public static String encode(List<String> codes) {
        if (codes == null || codes.isEmpty()) return "";
        StringBuilder out = new StringBuilder();
        for (String value : codes) {
            String code = normalise(value);
            if (code.isEmpty()) continue;
            if (out.length() > 0) out.append(',');
            out.append(code);
        }
        return out.toString();
    }

    /** Reads back what {@link #encode} wrote. Junk in the stored value is dropped, not fatal. */
    public static List<String> decode(String stored) {
        List<String> codes = new ArrayList<>();
        if (stored == null || stored.isEmpty()) return codes;
        for (String part : stored.split(",")) {
            String code = normalise(part);
            if (!code.isEmpty() && !codes.contains(code)) codes.add(code);
        }
        return codes;
    }
}
