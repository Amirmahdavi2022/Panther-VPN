package com.firstham.aethergui;

import java.util.Locale;

/**
 * Where the tunnel comes out, as reported by a public lookup service.
 *
 * <p>Four different services answer this question and none of them agree on field names, so the
 * parser looks for every spelling it has seen rather than binding to one provider's schema. It also
 * reads Cloudflare's {@code /cdn-cgi/trace} format, which is plain {@code key=value} lines instead
 * of JSON. Anything it cannot find comes back empty and the caller moves on to the next provider,
 * so a service changing its output degrades to "try the next one" rather than to a crash.
 *
 * <p>Deliberately free of {@code org.json} and of every Android type: this is the part of the
 * feature that can be run and checked on a desktop JVM, so it is kept runnable there.
 */
public final class ExitLocation {

    public static final ExitLocation EMPTY = new ExitLocation("", "", "", "", "");

    public final String ip;
    public final String city;
    public final String region;
    public final String country;      // full name when the service gives one, else the code
    public final String countryCode;  // two letters, upper case, or empty
    public final String isp;

    private ExitLocation(String ip, String city, String region, String country, String countryCode) {
        this(ip, city, region, country, countryCode, "");
    }

    private ExitLocation(String ip, String city, String region, String country, String countryCode, String isp) {
        this.ip = ip;
        this.city = city;
        this.region = region;
        this.country = country;
        this.countryCode = countryCode;
        this.isp = isp;
    }

    /** True once there is enough here to be worth showing. A country alone is enough. */
    public boolean usable() {
        return !country.isEmpty() || !countryCode.isEmpty();
    }

    /** The headline line: flag, then the most specific place name available. */
    public String place() {
        String name = country.isEmpty() ? countryCode : country;
        String detail = city.isEmpty() ? region : city;
        String flag = flag(countryCode);
        String body = detail.isEmpty() ? name : detail + ", " + name;
        if (body.isEmpty()) return "";
        return flag.isEmpty() ? body : flag + "  " + body;
    }

    /** The small line under it: the public address, and who owns it when that is known. */
    public String detail() {
        if (ip.isEmpty()) return isp;
        return isp.isEmpty() ? ip : ip + "  ·  " + isp;
    }

    /**
     * Reads either a JSON object or a Cloudflare trace body. The format is detected from the
     * content, so callers do not have to tell the parser which service answered.
     */
    public static ExitLocation parse(String body) {
        if (body == null) return EMPTY;
        String trimmed = body.trim();
        if (trimmed.isEmpty()) return EMPTY;
        // A chunked response interleaves hex length markers with the payload, so the body does not
        // reliably begin with '{'. Find where the object starts instead of testing the first byte;
        // the trace format has no braces, so this stays unambiguous. Those length markers carry no
        // '=' either, which is why the trace reader skips straight past them.
        int brace = trimmed.indexOf('{');
        if (brace >= 0 && trimmed.indexOf('"', brace) > brace) return fromJson(trimmed.substring(brace));
        return fromTrace(trimmed);
    }

    /** Cloudflare's {@code /cdn-cgi/trace}: one {@code key=value} per line. */
    public static ExitLocation fromTrace(String body) {
        String ip = "";
        String code = "";
        for (String line : body.split("\\r?\\n")) {
            int split = line.indexOf('=');
            if (split <= 0) continue;
            String key = line.substring(0, split).trim();
            String value = line.substring(split + 1).trim();
            if ("ip".equals(key)) ip = value;
            else if ("loc".equals(key)) code = value;
        }
        code = normaliseCode(code);
        return new ExitLocation(ip, "", "", countryName(code), code, "");
    }

    private static ExitLocation fromJson(String body) {
        // Some services wrap the answer, and some report failure in-band with a 200. Bail on the
        // documented failure shapes rather than reporting a half-parsed location.
        if ("true".equals(value(body, "error")) || "false".equals(value(body, "success"))) return EMPTY;

        String code = normaliseCode(firstOf(body, "country_code", "countryCode", "country_code_iso3166", "cc"));
        String country = firstOf(body, "country_name", "countryName");
        if (country.isEmpty()) {
            String raw = firstOf(body, "country");
            // "country" means the full name at some services and the two-letter code at others.
            if (raw.length() == 2) { if (code.isEmpty()) code = normaliseCode(raw); }
            else country = raw;
        }
        if (country.isEmpty()) country = countryName(code);

        return new ExitLocation(
                firstOf(body, "clientIp", "client_ip", "ip", "query", "YourFuckingIPAddress"),
                firstOf(body, "city"),
                firstOf(body, "region", "region_name", "regionName", "state"),
                country,
                code,
                firstOf(body, "asOrganization", "as_organization", "isp", "org", "asn_org", "connection_isp"));
    }

    /** First of these keys that carries a non-empty value. */
    private static String firstOf(String body, String... keys) {
        for (String key : keys) {
            String found = value(body, key);
            if (!found.isEmpty()) return found;
        }
        return "";
    }

    /**
     * Pulls one value out of a JSON body by key.
     *
     * <p>These payloads are small and flat, so this scans for the quoted key rather than building a
     * tree. It skips keys that appear inside string values, unescapes what it returns, and accepts
     * strings, numbers and booleans. Nested objects are searched too, which is what makes
     * {@code connection.isp} reachable without knowing the wrapper's name.
     */
    static String value(String body, String key) {
        String needle = '"' + key + '"';
        int from = 0;
        while (true) {
            int at = body.indexOf(needle, from);
            if (at < 0) return "";
            from = at + needle.length();
            if (inString(body, at)) continue;
            int cursor = skipSpace(body, from);
            if (cursor >= body.length() || body.charAt(cursor) != ':') continue;
            cursor = skipSpace(body, cursor + 1);
            if (cursor >= body.length()) return "";
            char first = body.charAt(cursor);
            if (first == '"') {
                StringBuilder out = new StringBuilder();
                for (int i = cursor + 1; i < body.length(); i++) {
                    char c = body.charAt(i);
                    if (c == '\\' && i + 1 < body.length()) {
                        char next = body.charAt(++i);
                        switch (next) {
                            case 'n': out.append('\n'); break;
                            case 't': out.append('\t'); break;
                            case 'r': out.append('\r'); break;
                            case 'b': out.append('\b'); break;
                            case 'f': out.append('\f'); break;
                            case 'u':
                                if (i + 4 < body.length()) {
                                    try { out.append((char) Integer.parseInt(body.substring(i + 1, i + 5), 16)); }
                                    catch (NumberFormatException ignored) { /* leave it out */ }
                                    i += 4;
                                }
                                break;
                            default: out.append(next);
                        }
                    } else if (c == '"') {
                        return out.toString().trim();
                    } else {
                        out.append(c);
                    }
                }
                return "";
            }
            if (first == '{' || first == '[') continue; // a wrapper, not a value: keep looking
            int end = cursor;
            while (end < body.length() && ",}] \t\r\n".indexOf(body.charAt(end)) < 0) end++;
            String literal = body.substring(cursor, end).trim();
            return "null".equals(literal) ? "" : literal;
        }
    }

    /** True when the offset sits inside a JSON string literal, i.e. it is data and not a key. */
    private static boolean inString(String body, int offset) {
        boolean open = false;
        for (int i = 0; i < offset; i++) {
            char c = body.charAt(i);
            if (c == '\\') { i++; continue; }
            if (c == '"') open = !open;
        }
        return open;
    }

    private static int skipSpace(String body, int from) {
        int cursor = from;
        while (cursor < body.length() && Character.isWhitespace(body.charAt(cursor))) cursor++;
        return cursor;
    }

    private static String normaliseCode(String raw) {
        if (raw == null) return "";
        String code = raw.trim().toUpperCase(Locale.US);
        if (code.length() != 2) return "";
        for (int i = 0; i < 2; i++) if (code.charAt(i) < 'A' || code.charAt(i) > 'Z') return "";
        // Cloudflare answers "XX" for addresses it will not place, and "T1" for Tor.
        if ("XX".equals(code) || "T1".equals(code)) return "";
        return code;
    }

    /** Regional-indicator pair, which renders as a flag. Empty for an unusable code. */
    public static String flag(String countryCode) {
        String code = normaliseCode(countryCode);
        if (code.isEmpty()) return "";
        return new String(Character.toChars(0x1F1E6 + code.charAt(0) - 'A'))
                + new String(Character.toChars(0x1F1E6 + code.charAt(1) - 'A'));
    }

    /** Falls back to the JVM's own ISO tables so a bare code still reads as a country. */
    @SuppressWarnings("deprecation") // Locale.of() is API 36; this module ships to minSdk 26.
    static String countryName(String code) {
        String normalised = normaliseCode(code);
        if (normalised.isEmpty()) return "";
        String name = new Locale("", normalised).getDisplayCountry(Locale.US);
        return name.equals(normalised) ? "" : name;
    }
}
