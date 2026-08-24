package com.firstham.aethergui;

import android.os.Build;

/**
 * The exit locations the user can pick.
 *
 * AUTO and CUSTOM run on the Aether core, which rides Cloudflare WARP. WARP endpoints are anycast,
 * so the exit country follows whichever Cloudflare datacenter the network routes to - it cannot be
 * chosen. The fixed-country options therefore run a second core (warp-plus) that chains WARP into
 * the Psiphon network, which does let the egress country be requested. Psiphon egresses from a pool
 * inside that country, so the country is fixed but the address is not.
 *
 * warp-plus ships an official Android build for arm64 only, so the fixed-country options are
 * offered only on 64-bit ARM devices.
 */
final class Locations {
    static final String AUTO = "auto";
    static final String UNITED_STATES = "us";
    static final String GERMANY = "de";
    static final String CUSTOM = "custom";

    static final String CORE_LIBRARY = "libwarpplus.so";

    private Locations() {
    }

    static String normalize(String location) {
        if (UNITED_STATES.equals(location)) return UNITED_STATES;
        if (GERMANY.equals(location)) return GERMANY;
        if (CUSTOM.equals(location)) return CUSTOM;
        return AUTO;
    }

    /** True when the option needs the warp-plus core rather than the Aether core. */
    static boolean usesPsiphon(String location) {
        String value = normalize(location);
        return UNITED_STATES.equals(value) || GERMANY.equals(value);
    }

    /** The Psiphon country code warp-plus expects. */
    static String countryCode(String location) {
        return GERMANY.equals(normalize(location)) ? "DE" : "US";
    }

    /** Dropdown order: Auto, United States, Germany, Custom. */
    static String fromIndex(int index) {
        switch (index) {
            case 1: return UNITED_STATES;
            case 2: return GERMANY;
            case 3: return CUSTOM;
            default: return AUTO;
        }
    }

    static int index(String location) {
        switch (normalize(location)) {
            case UNITED_STATES: return 1;
            case GERMANY: return 2;
            case CUSTOM: return 3;
            default: return 0;
        }
    }

    /** Only arm64 devices ship the warp-plus core. */
    static boolean fixedCountriesSupported() {
        for (String abi : Build.SUPPORTED_ABIS) {
            if ("arm64-v8a".equals(abi)) return true;
        }
        return false;
    }
}
