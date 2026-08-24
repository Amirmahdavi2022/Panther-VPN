package com.firstham.aethergui;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * The exit locations the user can pick.
 *
 * AUTO and CUSTOM run on the Aether core, which rides Cloudflare WARP. WARP endpoints are anycast,
 * so the exit country follows whichever Cloudflare datacenter the network routes to - it cannot be
 * chosen. Every named country instead runs a second core (warp-plus) that chains WARP into the
 * Psiphon network, which does let the egress country be requested. Psiphon egresses from a pool
 * inside that country, so the country is fixed but the address is not.
 *
 * The country list is the egress set warp-plus supports (psiphon/p.go upstream).
 * warp-plus is now built from source for every shipped ABI, so no architecture gate applies.
 */
final class Locations {
    static final String AUTO = "auto";
    static final String CUSTOM = "custom";
    static final String CORE_LIBRARY = "libwarpplus.so";

    /** Psiphon egress countries, in the order the dropdown shows them. */
    static final List<String> COUNTRIES = Collections.unmodifiableList(Arrays.asList(
            "AT",
            "AU",
            "BE",
            "BG",
            "CA",
            "CH",
            "CZ",
            "DE",
            "DK",
            "EE",
            "ES",
            "FI",
            "FR",
            "GB",
            "HR",
            "HU",
            "IE",
            "IN",
            "IT",
            "JP",
            "LV",
            "NL",
            "NO",
            "PL",
            "PT",
            "RO",
            "RS",
            "SE",
            "SG",
            "SK",
            "US"
    ));

    private Locations() {
    }

    static String normalize(String location) {
        if (location == null) return AUTO;
        String value = location.trim();
        if (CUSTOM.equalsIgnoreCase(value)) return CUSTOM;
        String upper = value.toUpperCase(Locale.US);
        if (COUNTRIES.contains(upper)) return upper.toLowerCase(Locale.US);
        return AUTO;
    }

    /** True when the option needs the warp-plus core rather than the Aether core. */
    static boolean usesPsiphon(String location) {
        String value = normalize(location);
        return !AUTO.equals(value) && !CUSTOM.equals(value);
    }

    /** The Psiphon country code warp-plus expects. */
    static String countryCode(String location) {
        String value = normalize(location);
        if (!usesPsiphon(value)) return "US";
        return value.toUpperCase(Locale.US);
    }

    /** Dropdown order: Auto, then every country, then Custom. */
    static String fromIndex(int index) {
        if (index >= 1 && index <= COUNTRIES.size()) {
            return COUNTRIES.get(index - 1).toLowerCase(Locale.US);
        }
        if (index == COUNTRIES.size() + 1) return CUSTOM;
        return AUTO;
    }

    static int index(String location) {
        String value = normalize(location);
        if (CUSTOM.equals(value)) return COUNTRIES.size() + 1;
        int at = COUNTRIES.indexOf(value.toUpperCase(Locale.US));
        return at < 0 ? 0 : at + 1;
    }

    /** Every shipped ABI now carries the warp-plus core. */
    static boolean fixedCountriesSupported() {
        return true;
    }
}
