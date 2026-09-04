package com.firstham.aethergui;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Remembers which way out worked, per network rather than per device.
 *
 * <p>Stealth can reach its endpoints two ways: straight out, or through the carrier tunnel. Which
 * one works is a property of the network the phone is on, not of the phone — a SIM that needs the
 * carrier hop this morning is sitting next to a wifi connection that does not. Until now that
 * answer was kept as a single flag for the whole app, so every move between mobile data and wifi
 * started by trying the way that had worked somewhere else, failing, and trying again. The user
 * pays for that in seconds, every time, on the connect they were already waiting through.
 *
 * <p>Keeping it per network makes the cost of learning something you pay once per network instead
 * of once per switch.
 *
 * <p><b>What a network is here.</b> Mobile networks are told apart by their operator name, which
 * needs no permission to read. Wifi networks are not told apart from each other: the SSID is
 * behind a location permission this app does not ask for and is not going to start asking for. So
 * every wifi shares one entry. That is a real limitation — a home connection and a café one look
 * identical — and it is still strictly better than one flag for everything, because a wrong guess
 * only costs the first attempt, never the connection.
 *
 * <p>Nothing here identifies the user or the network beyond a name the phone already displays, and
 * none of it leaves the device.
 *
 * <p>Android-free, so the keying and the storage format are checkable on a desktop JVM.
 */
final class NetworkMemory {

    /** How many networks are remembered before the oldest is dropped. */
    static final int KEEP = 12;

    /** The key used when the phone cannot say what it is on. */
    static final String UNKNOWN = "unknown";

    /** Insertion-ordered, so the oldest entry is the first one out. */
    private final Map<String, Boolean> chainedByNetwork = new LinkedHashMap<>();

    /**
     * A stable name for the network in use.
     *
     * @param mobile   true when the active network is cellular
     * @param operator the operator name, when there is one
     */
    static String key(boolean mobile, String operator) {
        if (!mobile) return "wifi";
        String name = operator == null ? "" : operator.trim().toLowerCase(Locale.US);
        // Anything that is not a plain name is dropped rather than stored: separators would break
        // the file format, and an operator name is not worth escaping for.
        StringBuilder cleaned = new StringBuilder();
        for (int i = 0; i < name.length() && cleaned.length() < 24; i++) {
            char c = name.charAt(i);
            if (Character.isLetterOrDigit(c)) cleaned.append(c);
            else if (c == ' ' || c == '-' || c == '_') cleaned.append('-');
        }
        String trimmed = cleaned.toString().replaceAll("^-+|-+$", "");
        return trimmed.isEmpty() ? UNKNOWN : "cell:" + trimmed;
    }

    /** Whether this network is known to need the carrier hop, or null when it has never been seen. */
    Boolean chainedOn(String key) {
        return key == null ? null : chainedByNetwork.get(key);
    }

    /**
     * What to try first on this network. Falls back to the answer given, which is the caller's own
     * previous global setting, so an upgrade does not throw away what the device already knew.
     */
    boolean preferChainedOn(String key, boolean fallback) {
        Boolean known = chainedOn(key);
        return known == null ? fallback : known;
    }

    /** Records what worked. Re-inserting moves the network to the newest end of the list. */
    void remember(String key, boolean chained) {
        if (key == null || key.isEmpty()) return;
        chainedByNetwork.remove(key);
        chainedByNetwork.put(key, chained);
        while (chainedByNetwork.size() > KEEP) {
            String oldest = chainedByNetwork.keySet().iterator().next();
            chainedByNetwork.remove(oldest);
        }
    }

    int size() { return chainedByNetwork.size(); }

    /** The networks held, oldest first. */
    List<String> networks() { return new ArrayList<>(chainedByNetwork.keySet()); }

    /** One network per line: {@code key<tab>0|1}. */
    String serialise() {
        StringBuilder out = new StringBuilder();
        for (Map.Entry<String, Boolean> entry : chainedByNetwork.entrySet()) {
            out.append(entry.getKey()).append('\t')
               .append(entry.getValue() ? '1' : '0').append('\n');
        }
        return out.toString();
    }

    /**
     * Reads it back, skipping anything malformed rather than throwing. This is a convenience, not
     * a source of truth: the worst a corrupt file can cost is one wrong first attempt.
     */
    static NetworkMemory deserialise(String text) {
        NetworkMemory memory = new NetworkMemory();
        if (text == null || text.isEmpty()) return memory;
        for (String line : text.split("\n")) {
            String row = line.trim();
            if (row.isEmpty()) continue;
            int tab = row.indexOf('\t');
            if (tab <= 0 || tab == row.length() - 1) continue;
            String key = row.substring(0, tab);
            String value = row.substring(tab + 1).trim();
            if (!"0".equals(value) && !"1".equals(value)) continue;
            memory.remember(key, "1".equals(value));
        }
        return memory;
    }
}
