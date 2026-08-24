package com.firstham.aethergui.vpngate;

/**
 * One volunteer relay from the VPN Gate directory.
 *
 * Every field here comes straight from the public CSV feed. The OpenVPN profile is carried inline
 * as base64, so a server is fully usable the moment it is parsed - there is nothing to fetch
 * per-server and no account anywhere in the flow.
 */
public final class VpnGateServer {
    public final String hostName;
    public final String ip;
    public final long score;
    public final int pingMs;
    public final long speedBps;
    public final String countryName;
    public final String countryCode;
    public final int sessions;
    public final long uptimeMs;
    public final String openVpnConfigBase64;

    VpnGateServer(String hostName, String ip, long score, int pingMs, long speedBps,
                  String countryName, String countryCode, int sessions, long uptimeMs,
                  String openVpnConfigBase64) {
        this.hostName = hostName;
        this.ip = ip;
        this.score = score;
        this.pingMs = pingMs;
        this.speedBps = speedBps;
        this.countryName = countryName;
        this.countryCode = countryCode;
        this.sessions = sessions;
        this.uptimeMs = uptimeMs;
        this.openVpnConfigBase64 = openVpnConfigBase64;
    }

    /** Megabits per second, for display. */
    public double speedMbps() {
        return speedBps / 1_000_000d;
    }

    public long uptimeHours() {
        return uptimeMs / 3_600_000L;
    }

    /** A stable identity for favourites and "last used", since IPs move between rebuilds. */
    public String key() {
        return hostName + "@" + ip;
    }

    @Override public String toString() {
        return countryCode + " " + hostName + " " + ip
                + " ping=" + pingMs + "ms speed=" + String.format(java.util.Locale.US, "%.1f", speedMbps()) + "Mbps";
    }
}
