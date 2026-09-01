package com.firstham.aethergui;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Whether the services people actually care about answer from inside the tunnel.
 *
 * <p>The exit country on its own does not tell anyone what they want to know. A tunnel can come out
 * in a country that is nominally fine and still be refused, because the refusal is usually about
 * the address rather than the country: an exit whose address is filed as a datacentre or a known
 * proxy gets turned away from the same place a residential address in the same city walks into.
 * So this asks the services directly, through the live tunnel, and reports what came back.
 *
 * <p>What the three outcomes mean:
 * <ul>
 *   <li>{@link #OPEN} - the service answered normally through the tunnel.
 *   <li>{@link #BLOCKED} - the service answered, and refused. This is one answer for two causes
 *       (the country is not served, or the exit address is not trusted) and the app does not claim
 *       to tell them apart, because from where the user sits they have the same fix: change exit.
 *   <li>{@link #UNREACHABLE} - nothing answered at all. The tunnel is not carrying this traffic.
 * </ul>
 *
 * <p>Deliberately free of every Android type, so the classification and the wire format can be run
 * and checked on a desktop JVM. Only the socket work lives in the service.
 */
public final class ServiceProbe {

    public static final String OPEN = "open";
    public static final String BLOCKED = "blocked";
    public static final String UNREACHABLE = "unreachable";
    /** Not asked yet on this tunnel. */
    public static final String UNKNOWN = "unknown";

    /** One thing worth asking about. */
    public static final class Target {
        public final String id;
        public final String label;
        public final String host;
        public final String path;

        Target(String id, String label, String host, String path) {
            this.id = id;
            this.label = label;
            this.host = host;
            this.path = path;
        }
    }

    /**
     * Kept short on purpose. Every entry costs a TLS handshake through the tunnel on a phone
     * radio, and a list long enough to be interesting is long enough to be slow.
     */
    private static final Target[] TARGETS = {
            new Target("chatgpt", "ChatGPT", "chatgpt.com", "/"),
            new Target("claude", "Claude", "claude.ai", "/"),
            new Target("gemini", "Gemini", "gemini.google.com", "/"),
    };

    private ServiceProbe() { }

    public static List<Target> targets() {
        List<Target> all = new ArrayList<>();
        for (Target target : TARGETS) all.add(target);
        return all;
    }

    public static String labelOf(String id) {
        for (Target target : TARGETS) if (target.id.equals(id)) return target.label;
        return id == null ? "" : id;
    }

    /**
     * Turns an HTTP status into one of the outcomes.
     *
     * <p>Only an outright refusal counts as blocked. A rate limit or a server-side error means the
     * request reached the service and was understood, which is exactly what this probe is asking
     * about - reporting those as blocked would send people hunting for a new exit country over a
     * problem that has nothing to do with their exit.
     */
    public static String classify(int status) {
        if (status <= 0) return UNREACHABLE;
        // 403 is the refusal every one of these services uses for an exit it will not serve.
        // 451 is the legal-block status, rarer but unambiguous when it does turn up.
        if (status == 403 || status == 451) return BLOCKED;
        if (status >= 200 && status < 600) return OPEN;
        return UNREACHABLE;
    }

    /** True once at least one target answered, which is what makes the row worth drawing. */
    public static boolean anyAnswered(Map<String, String> results) {
        if (results == null) return false;
        for (String value : results.values()) {
            if (OPEN.equals(value) || BLOCKED.equals(value)) return true;
        }
        return false;
    }

    /**
     * A one-word verdict for a whole run, for the places that have room for one line and not a row
     * of chips. Anything open at all counts as open, because one working service is the difference
     * between a useful exit and a useless one.
     */
    public static String verdict(Map<String, String> results) {
        if (results == null || results.isEmpty()) return UNKNOWN;
        boolean sawBlocked = false;
        boolean sawAnswer = false;
        for (String value : results.values()) {
            if (OPEN.equals(value)) return OPEN;
            if (BLOCKED.equals(value)) { sawBlocked = true; sawAnswer = true; }
            if (UNREACHABLE.equals(value)) sawAnswer = true;
        }
        if (sawBlocked) return BLOCKED;
        return sawAnswer ? UNREACHABLE : UNKNOWN;
    }

    /** Packs a result set into one string for a broadcast extra or a preference. */
    public static String encode(Map<String, String> results) {
        if (results == null || results.isEmpty()) return "";
        StringBuilder out = new StringBuilder();
        for (Map.Entry<String, String> entry : results.entrySet()) {
            String id = clean(entry.getKey());
            String value = clean(entry.getValue());
            if (id.isEmpty() || value.isEmpty()) continue;
            if (out.length() > 0) out.append(';');
            out.append(id).append('=').append(value);
        }
        return out.toString();
    }

    /**
     * Reads back what {@link #encode} wrote, in the order it was written.
     *
     * <p>Unparseable entries are dropped rather than throwing: this string crosses a process
     * boundary and can arrive from an older build after an in-place update, so it is treated as
     * untrusted input.
     */
    public static Map<String, String> decode(String stored) {
        Map<String, String> results = new LinkedHashMap<>();
        if (stored == null || stored.isEmpty()) return results;
        for (String part : stored.split(";")) {
            int split = part.indexOf('=');
            if (split <= 0) continue;
            String id = clean(part.substring(0, split));
            String value = clean(part.substring(split + 1));
            if (id.isEmpty()) continue;
            if (!OPEN.equals(value) && !BLOCKED.equals(value) && !UNREACHABLE.equals(value)) continue;
            results.put(id, value);
        }
        return results;
    }

    private static String clean(String value) {
        if (value == null) return "";
        String trimmed = value.trim().toLowerCase(Locale.US);
        // The two separators are the only characters that could corrupt the format.
        return trimmed.indexOf(';') >= 0 || trimmed.indexOf('=') >= 0 ? "" : trimmed;
    }
}
