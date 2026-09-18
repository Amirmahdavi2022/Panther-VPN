package com.firstham.aethergui.vpngate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Which relays have actually carried a connection on this phone.
 *
 * <p>The directory's own score is a global average. It says nothing about whether a relay answers
 * from <em>your</em> network, which is the only question that matters here - the same relay that
 * is unreachable on one carrier is fine on another, and a relay can sit near the top of the feed
 * for hours after it has stopped accepting anyone. Prowl already works this way for its own pool;
 * this is the same idea with a much smaller surface, because a relay either connected or it did
 * not.
 *
 * <p>Stored as a plain {@code key=timestamp} list so it survives as one preference string, capped
 * so it cannot grow without bound, and aged out so a relay that worked once last spring does not
 * keep being tried first forever.
 */
public final class RelayMemory {

    /** How many remembered relays are kept. Oldest are dropped first. */
    public static final int MAX_ENTRIES = 24;

    /** How long a success is worth acting on. Volunteer machines do not last much longer. */
    public static final long TTL_MS = 14L * 24 * 60 * 60 * 1000;

    private RelayMemory() {
    }

    /**
     * Parses the stored list, newest first, dropping anything expired or malformed.
     *
     * @param now the current wall clock, passed in so this stays testable
     */
    public static Map<String, Long> parse(String stored, long now) {
        List<String[]> rows = new ArrayList<>();
        if (stored != null) {
            for (String entry : stored.split("\n")) {
                int split = entry.lastIndexOf('=');
                if (split <= 0) continue;
                String key = entry.substring(0, split).trim();
                if (key.isEmpty()) continue;
                long at;
                try {
                    at = Long.parseLong(entry.substring(split + 1).trim());
                } catch (NumberFormatException ignored) {
                    continue;
                }
                // A timestamp in the future is a clock that moved; treat it as now rather than
                // letting it outlive everything else.
                if (at > now) at = now;
                if (now - at > TTL_MS) continue;
                rows.add(new String[]{key, Long.toString(at)});
            }
        }
        Collections.sort(rows, (a, b) -> Long.compare(Long.parseLong(b[1]), Long.parseLong(a[1])));

        Map<String, Long> result = new LinkedHashMap<>();
        for (String[] row : rows) {
            if (result.size() >= MAX_ENTRIES) break;
            if (!result.containsKey(row[0])) result.put(row[0], Long.parseLong(row[1]));
        }
        return result;
    }

    /** Adds one success and returns the list to store, newest first and already capped. */
    public static String remember(String stored, String key, long now) {
        if (key == null || key.trim().isEmpty()) return stored == null ? "" : stored;
        Map<String, Long> known = parse(stored, now);
        Map<String, Long> updated = new LinkedHashMap<>();
        updated.put(key.trim(), now);
        for (Map.Entry<String, Long> entry : known.entrySet()) {
            if (updated.size() >= MAX_ENTRIES) break;
            if (!entry.getKey().equals(key.trim())) updated.put(entry.getKey(), entry.getValue());
        }
        return render(updated);
    }

    public static String render(Map<String, Long> entries) {
        StringBuilder out = new StringBuilder();
        for (Map.Entry<String, Long> entry : entries.entrySet()) {
            if (out.length() > 0) out.append('\n');
            out.append(entry.getKey()).append('=').append(entry.getValue());
        }
        return out.toString();
    }
}
