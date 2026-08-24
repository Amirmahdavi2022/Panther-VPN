package com.firstham.aethergui.vpngate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Parses and ranks the public VPN Gate directory.
 *
 * The feed is a CSV whose header row is commented with '#' and whose body is bracketed by '*'
 * marker lines. Columns are:
 *
 *   HostName,IP,Score,Ping,Speed,CountryLong,CountryShort,NumVpnSessions,Uptime,
 *   TotalUsers,TotalTraffic,LogType,Operator,Message,OpenVPN_ConfigData_Base64
 *
 * The feed is volunteer-run, so rows are frequently malformed, truncated, or missing the OpenVPN
 * profile entirely. Every such row is dropped rather than allowed to fail the whole refresh - a
 * directory that parses 90% of a live feed is useful, one that throws on the first bad row is not.
 */
public final class VpnGateDirectory {
    /** Public feed. No key, no account, no per-server request. */
    public static final String FEED_URL = "https://www.vpngate.net/api/iphone/";

    private static final int COLUMNS = 15;
    private static final int MIN_CONFIG_LENGTH = 100;

    private VpnGateDirectory() {
    }

    /** Parses the feed, dropping unusable rows. Never throws on malformed input. */
    public static List<VpnGateServer> parse(String csv) {
        List<VpnGateServer> servers = new ArrayList<>();
        if (csv == null || csv.isEmpty()) return servers;

        for (String raw : csv.split("\r?\n")) {
            String line = raw.trim();
            if (line.isEmpty() || line.charAt(0) == '*' || line.charAt(0) == '#') continue;

            String[] cells = line.split(",", -1);
            if (cells.length < COLUMNS) continue;

            String hostName = cells[0].trim();
            String ip = cells[1].trim();
            String countryName = cells[5].trim();
            String countryCode = cells[6].trim().toUpperCase(Locale.US);
            String config = cells[14].trim();

            // A row without a profile cannot be connected to, whatever else it advertises.
            if (hostName.isEmpty() || ip.isEmpty() || countryCode.length() != 2) continue;
            if (config.length() < MIN_CONFIG_LENGTH) continue;

            servers.add(new VpnGateServer(
                    hostName, ip,
                    number(cells[2]), (int) number(cells[3]), number(cells[4]),
                    countryName, countryCode,
                    (int) number(cells[7]), number(cells[8]),
                    config));
        }
        return servers;
    }

    /**
     * Best-first ordering. VPN Gate's own score already folds in throughput and stability, so it
     * leads; speed and ping only break ties. Servers reporting no ping at all sort last - an
     * unmeasured server is not a fast one.
     */
    public static Comparator<VpnGateServer> byQuality() {
        return Comparator
                .comparingLong((VpnGateServer s) -> s.score).reversed()
                .thenComparing(Comparator.comparingLong((VpnGateServer s) -> s.speedBps).reversed())
                .thenComparingInt(s -> s.pingMs <= 0 ? Integer.MAX_VALUE : s.pingMs);
    }

    public static List<VpnGateServer> ranked(List<VpnGateServer> servers) {
        List<VpnGateServer> copy = new ArrayList<>(servers);
        Collections.sort(copy, byQuality());
        return copy;
    }

    /** Every server in one country, best first. Country code is matched case-insensitively. */
    public static List<VpnGateServer> inCountry(List<VpnGateServer> servers, String countryCode) {
        String wanted = countryCode == null ? "" : countryCode.trim().toUpperCase(Locale.US);
        List<VpnGateServer> matches = new ArrayList<>();
        for (VpnGateServer server : servers) {
            if (server.countryCode.equals(wanted)) matches.add(server);
        }
        Collections.sort(matches, byQuality());
        return matches;
    }

    /** The single best server overall, or null when the directory is empty. */
    public static VpnGateServer best(List<VpnGateServer> servers) {
        VpnGateServer best = null;
        Comparator<VpnGateServer> quality = byQuality();
        for (VpnGateServer server : servers) {
            if (best == null || quality.compare(server, best) < 0) best = server;
        }
        return best;
    }

    /**
     * Countries present in the feed, ordered by how good their best server is, mapped to how many
     * servers each has. Backed by a LinkedHashMap so the ordering survives iteration.
     */
    public static Map<String, Integer> countries(List<VpnGateServer> servers) {
        Map<String, VpnGateServer> bestPerCountry = new LinkedHashMap<>();
        Map<String, Integer> counts = new LinkedHashMap<>();
        Comparator<VpnGateServer> quality = byQuality();

        for (VpnGateServer server : servers) {
            Integer seen = counts.get(server.countryCode);
            counts.put(server.countryCode, seen == null ? 1 : seen + 1);
            VpnGateServer leader = bestPerCountry.get(server.countryCode);
            if (leader == null || quality.compare(server, leader) < 0) {
                bestPerCountry.put(server.countryCode, server);
            }
        }

        List<String> order = new ArrayList<>(counts.keySet());
        Collections.sort(order, (a, b) -> quality.compare(bestPerCountry.get(a), bestPerCountry.get(b)));

        Map<String, Integer> result = new LinkedHashMap<>();
        for (String code : order) result.put(code, counts.get(code));
        return result;
    }

    /** Human-readable country name for a code, taken from the feed itself. */
    public static String countryName(List<VpnGateServer> servers, String countryCode) {
        String wanted = countryCode == null ? "" : countryCode.trim().toUpperCase(Locale.US);
        for (VpnGateServer server : servers) {
            if (server.countryCode.equals(wanted)) return server.countryName;
        }
        return wanted;
    }

    /** Tolerant number read: blank, negative and non-numeric cells all become 0. */
    private static long number(String cell) {
        if (cell == null) return 0L;
        String value = cell.trim();
        if (value.isEmpty()) return 0L;
        try {
            long parsed = Long.parseLong(value);
            return parsed < 0 ? 0L : parsed;
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }
}
