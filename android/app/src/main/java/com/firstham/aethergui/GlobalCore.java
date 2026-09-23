package com.firstham.aethergui;

import android.content.Context;
import android.net.VpnService;
import android.os.Build;

import java.io.File;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import ca.psiphon.PsiphonTunnel;

/**
 * The Global engine: the second core, alongside Aether.
 *
 * <p>It has the same shape as Aether from the app's point of view. It brings up a tunnel and
 * publishes a SOCKS5 proxy on loopback, and the existing hev TUN bridge is pointed at that port.
 * Nothing else in the routing path changes.
 *
 * <p>Two things it can do that Aether cannot: the exit country can be asked for, and the engine
 * reports back which country it actually landed in. That reported region comes from the engine
 * itself rather than from a guess, which is why {@link #connectedRegion()} is only ever non-null
 * once the engine has said so.
 *
 * <p>Never name the underlying project in anything the user sees. Attribution lives in NOTICE.md,
 * which is where the licence requires it and where it does not confuse anyone using the app.
 */
public final class GlobalCore {

    /** How the engine reports itself. Mirrors the state strings the rest of the app already uses. */
    public interface Listener {
        void onState(String state, String message);
        void onRegion(String countryCode);
        void onBytes(long sent, long received);
        void onLog(String line);
    }

    /**
     * Every relay-capable transport the engine knows, read from the pinned library's own
     * protocol table: every supported protocol, prefixed, except the two refraction ones
     * (not relay-compatible) and FRONTED-MEEK-QUIC-OSSH (which the library keeps off).
     * The first hop is WebRTC to a volunteer's machine; the second hop, volunteer to server,
     * is the named transport.
     */
    static final String[] VOLUNTEER_PROTOCOLS = {
            "INPROXY-WEBRTC-OSSH",
            "INPROXY-WEBRTC-TLS-OSSH",
            "INPROXY-WEBRTC-SHADOWSOCKS-OSSH",
            "INPROXY-WEBRTC-QUIC-OSSH",
            "INPROXY-WEBRTC-SSH",
            "INPROXY-WEBRTC-UNFRONTED-MEEK-OSSH",
            "INPROXY-WEBRTC-UNFRONTED-MEEK-HTTPS-OSSH",
            "INPROXY-WEBRTC-UNFRONTED-MEEK-SESSION-TICKET-OSSH",
            "INPROXY-WEBRTC-FRONTED-MEEK-OSSH",
            "INPROXY-WEBRTC-FRONTED-MEEK-HTTP-OSSH",
    };

    /** Set on the config to let the engine choose. Empty means "wherever is best". */
    public static final String REGION_AUTOMATIC = "";

    private static final String PROPAGATION_CHANNEL_ID = "FFFFFFFFFFFFFFFF";
    private static final String SPONSOR_ID = "FFFFFFFFFFFFFFFF";
    private static final String SERVER_LIST_URL =
            "https://s3.amazonaws.com//psiphon/web/mjr4-p23r-puwl/server_list_compressed";
    private static final String SERVER_LIST_KEY =
            "MIICIDANBgkqhkiG9w0BAQEFAAOCAg0AMIICCAKCAgEAt7Ls+/39r+T6zNW7GiVpJfzq/xvL9SBH5rIFnk0RXYEYavax3WS6HOD35eTAqn8AniOwiH+DOkvgSKF2caqk/y1dfq47Pdymtwzp9ikpB1C5OfAysXzBiwVJlCdajBKvBZDerV1cMvRzCKvKwRmvDmHgphQQ7WfXIGbRbmmk6opMBh3roE42KcotLFtqp0RRwLtcBRNtCdsrVsjiI1Lqz/lH+T61sGjSjQ3CHMuZYSQJZo/KrvzgQXpkaCTdbObxHqb6/+i1qaVOfEsvjoiyzTxJADvSytVtcTjijhPEV6XskJVHE1Zgl+7rATr/pDQkw6DPCNBS1+Y6fy7GstZALQXwEDN/qhQI9kWkHijT8ns+i1vGg00Mk/6J75arLhqcodWsdeG/M/moWgqQAnlZAGVtJI1OgeF5fsPpXu4kctOfuZlGjVZXQNW34aOzm8r8S0eVZitPlbhcPiR4gT/aSMz/wd8lZlzZYsje/Jr8u/YtlwjjreZrGRmG8KMOzukV3lLmMppXFMvl4bxv6YFEmIuTsOhbLTwFgh7KYNjodLj/LsqRVfwz31PgWQFTEPICV7GCvgVlPRxnofqKSjgTWI4mxDhBpVcATvaoBl1L/6WLbFvBsoAUBItWwctO2xalKxF5szhGm8lccoc5MZr8kfE0uxMgsxz4er68iCID+rsCAQM=";

    /**
     * Public ed25519 key for verifying individual server entries. The engine refuses to offer a
     * server to the volunteer-relay matchmaker, or to accept servers from discovery, without it.
     * Taken from the network's own volunteer-relay client config (GPL-3.0), which carries the
     * same server-list key as {@link #SERVER_LIST_KEY} - so it is the same network's key.
     */
    private static final String SERVER_ENTRY_KEY = "sHuUVTWaRyh5pZwy4UguSgkwmBe0EHtJJkoF5WrxmvA=";

    private final VpnService host;
    private final Listener listener;
    private final String requestedRegion;
    private final String upstreamProxy;
    /** True when this run may only use volunteer relays; see {@link #VOLUNTEER_PROTOCOLS}. */
    private final boolean volunteerRoute;
    /** Why the volunteer route cannot be used on this device yet, or null. */
    private final AtomicReference<String> volunteerBlocked = new AtomicReference<>();

    private final AtomicReference<String> connectedRegion = new AtomicReference<>();
    private final AtomicReference<List<String>> availableRegions = new AtomicReference<>();
    private final AtomicInteger socksPort = new AtomicInteger(-1);
    private final AtomicBoolean connected = new AtomicBoolean();
    private final AtomicBoolean stopped = new AtomicBoolean();
    /** Counts the UDP refusals swallowed by onDiagnosticMessage; see GlobalNoise. */
    private final AtomicInteger udpRefusals = new AtomicInteger();
    /** Dial attempts the engine announced; counted instead of logged one per line. */
    private final AtomicInteger connectingServers = new AtomicInteger();
    private final CountDownLatch ready = new CountDownLatch(1);

    private volatile PsiphonTunnel tunnel;

    /**
     * @param upstreamProxy loopback {@code host:port} of a SOCKS5 proxy to dial out through, or
     *                      null to dial the network directly.
     */
    public GlobalCore(VpnService host, String requestedRegion, String upstreamProxy, Listener listener) {
        this(host, requestedRegion, upstreamProxy, false, listener);
    }

    /**
     * @param volunteerRoute when true the engine dials only through volunteer relays, and
     *                       never through {@code upstreamProxy}: the engine cannot use a
     *                       relay and an upstream proxy together, so the proxy is ignored.
     */
    public GlobalCore(VpnService host, String requestedRegion, String upstreamProxy,
                      boolean volunteerRoute, Listener listener) {
        this.volunteerRoute = volunteerRoute;
        this.host = host;
        this.listener = listener;
        this.requestedRegion = requestedRegion == null ? REGION_AUTOMATIC : requestedRegion.trim();
        this.upstreamProxy = upstreamProxy == null ? "" : upstreamProxy.trim();
    }

    /** The country the engine actually connected through, or null until it has reported one. */
    public String connectedRegion() { return connectedRegion.get(); }

    /** Countries the engine offered on this run, or null if it has not listed them yet. */
    public List<String> availableRegions() { return availableRegions.get(); }

    /** The loopback SOCKS5 port, or -1 before the engine has published one. */
    public int socksPort() { return socksPort.get(); }

    public boolean isConnected() { return connected.get() && !stopped.get(); }

    /** True when this instance was started on the volunteer relay route. */
    public boolean onVolunteerRoute() { return volunteerRoute; }

    /**
     * Why the volunteer route could not even be tried, or null. The engine needs two things it
     * only learns from an earlier ordinary connection - where the matchmaker is, and the access
     * IDs for the shared relay pool - so a fresh install reports one of these once.
     */
    public String volunteerBlocked() { return volunteerBlocked.get(); }

    /** How many dials the engine started on this run. */
    public int dialAttempts() { return connectingServers.get(); }

    /**
     * Starts the engine and waits for it to publish a working SOCKS port.
     *
     * @return true once the proxy is listening and the tunnel is up.
     */
    public boolean start(long timeoutMs) throws Exception {
        PsiphonTunnel created = PsiphonTunnel.newPsiphonTunnel(new Host());
        tunnel = created;
        // We own the TUN interface and route into the proxy ourselves, so the engine runs in VPN
        // mode and protects its own sockets through bindToDevice below.
        created.setVpnMode(true);
        created.startTunneling("");
        if (!ready.await(timeoutMs, TimeUnit.MILLISECONDS)) return false;
        return isConnected() && socksPort.get() > 0;
    }

    public void stop() {
        stopped.set(true);
        connected.set(false);
        PsiphonTunnel running = tunnel;
        tunnel = null;
        ready.countDown();
        if (running != null) {
            try { running.stop(); }
            catch (Throwable error) { listener.onLog("Global engine did not stop cleanly: " + error); }
        }
    }

    /** Where the engine keeps its own state. Kept apart from the other core's files. */
    private File dataDirectory() {
        File directory = new File(host.getFilesDir(), "global-core");
        if (!directory.isDirectory() && !directory.mkdirs()) {
            listener.onLog("Could not create the Global engine data directory");
        }
        return directory;
    }

    private String buildConfig() {
        File data = dataDirectory();
        StringBuilder json = new StringBuilder();
        json.append('{');
        append(json, "PropagationChannelId", PROPAGATION_CHANNEL_ID);
        append(json, "SponsorId", SPONSOR_ID);
        append(json, "RemoteServerListUrl", SERVER_LIST_URL);
        append(json, "RemoteServerListSignaturePublicKey", SERVER_LIST_KEY);
        append(json, "ServerEntrySignaturePublicKey", SERVER_ENTRY_KEY);
        append(json, "RemoteServerListDownloadFilename", "remote_server_list");
        append(json, "DataRootDirectory", data.getAbsolutePath());
        append(json, "MigrateDataStoreDirectory", data.getAbsolutePath());
        append(json, "ClientPlatform", "Android_" + Build.VERSION.RELEASE + "_" + host.getPackageName());
        // Empty means the engine picks; anything else is an ISO country code the user chose.
        if (!requestedRegion.isEmpty()) append(json, "EgressRegion", requestedRegion);
        // 0 asks the engine for any free loopback port, which it then reports back to us. Binding a
        // fixed port would collide with the other core when both are briefly alive during a
        // handover.
        // Dialing out through the other engine rather than straight at the network. This is not
        // an optimisation: the engine bootstraps by fetching its server list over HTTPS from a
        // host that is unreachable from some of the networks this app exists for, and its own
        // servers are filtered on those same networks. Carried inside the other tunnel, both the
        // fetch and the handshake go through. Standalone it simply never finds a route.
        if (volunteerRoute) {
            // Listing the relay variants explicitly is what turns them on (they are off unless
            // named), and naming nothing else keeps this run from quietly falling back to a
            // direct dial. No UpstreamProxyURL here: the engine refuses relays when one is set.
            json.append("\"LimitTunnelProtocols\":[");
            for (int i = 0; i < VOLUNTEER_PROTOCOLS.length; i++) {
                if (i > 0) json.append(',');
                json.append('"').append(VOLUNTEER_PROTOCOLS[i]).append('"');
            }
            json.append("],");
        } else if (!upstreamProxy.isEmpty()) {
            append(json, "UpstreamProxyURL", "socks5://" + upstreamProxy);
        }
        json.append("\"LocalSocksProxyPort\":0,");
        json.append("\"DisableLocalHTTPProxy\":true,");
        json.append("\"AllowDefaultDNSResolverWithBindToDevice\":true,");
        json.append("\"EmitDiagnosticNotices\":true");
        json.append('}');
        return json.toString();
    }

    private static void append(StringBuilder json, String key, String value) {
        json.append('"').append(key).append("\":\"").append(escape(value)).append("\",");
    }

    private static String escape(String value) {
        StringBuilder out = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"':  out.append("\\\""); break;
                case '\\': out.append("\\\\"); break;
                case '\n': out.append("\\n"); break;
                case '\r': out.append("\\r"); break;
                case '\t': out.append("\\t"); break;
                default:
                    if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
                    else out.append(c);
            }
        }
        return out.toString();
    }

    /** The engine calls back into this for its context, config, socket protection and status. */
    private final class Host implements PsiphonTunnel.HostService {

        @Override public Context getContext() { return host; }

        @Override public String getPsiphonConfig() { return buildConfig(); }

        @Override public void bindToDevice(long fileDescriptor) throws PsiphonTunnel.Exception {
            // Without this the engine's own sockets would be routed back into our TUN and loop.
            if (!host.protect((int) fileDescriptor)) {
                throw new PsiphonTunnel.Exception("Could not protect a Global engine socket");
            }
        }

        @Override public void onListeningSocksProxyPort(int port) {
            socksPort.set(port);
            listener.onLog("Global engine proxy listening on 127.0.0.1:" + port);
            releaseIfReady();
        }

        @Override public void onConnecting() {
            connected.set(false);
            listener.onState("starting", host.getString(R.string.status_connecting));
        }

        @Override public void onConnected() {
            connected.set(true);
            listener.onState("connected", host.getString(R.string.service_connected));
            releaseIfReady();
        }

        @Override public void onConnectedServerRegion(String region) {
            // The engine's own answer for where this tunnel comes out. This is the only thing the
            // app should ever show as the Global exit country.
            connectedRegion.set(region);
            listener.onRegion(region);
        }

        @Override public void onAvailableEgressRegions(List<String> regions) {
            availableRegions.set(regions);
        }

        @Override public void onBytesTransferred(long sent, long received) {
            listener.onBytes(sent, received);
        }

        @Override public void onExiting() {
            connected.set(false);
            ready.countDown();
            // A stop we asked for (a volunteer attempt being abandoned before the fallback,
            // or a disconnect) must not flash an error on screen.
            if (stopped.get()) return;
            listener.onState("error", host.getString(R.string.service_engine_stopped));
        }

        @Override public void onStartedWaitingForNetworkConnectivity() {
            listener.onState("reconnecting", host.getString(R.string.service_network_lost));
        }

        @Override public void onUpstreamProxyError(String message) { listener.onLog(message); }

        @Override public void onDiagnosticMessage(String message) {
            // The engine announces every dial attempt and dumps periodic memory/datastore/DNS
            // metrics. Measured on a real device log: those lines were 65% of a log that hit
            // its size cap, and the cap trims from the front - which is where the volunteer
            // attempt's verdict was. Count the attempts, drop the metrics.
            if (message != null && message.startsWith("ActiveTunnel:")) {
                // The only honest answer to "did a volunteer carry this?": the protocol of
                // the tunnel the engine actually kept. Relay protocols carry the prefix.
                java.util.regex.Matcher m = java.util.regex.Pattern
                        .compile("\"protocol\":\"([^\"]+)\"").matcher(message);
                if (m.find()) {
                    String protocol = m.group(1);
                    listener.onLog(protocol.startsWith("INPROXY-WEBRTC")
                            ? "Tunnel protocol " + protocol + ": riding a VOLUNTEER relay"
                            : "Tunnel protocol " + protocol + ": direct to the server, NOT through a volunteer");
                }
            }
            if (message != null) {
                if (message.startsWith("ConnectingServer:")) {
                    int tried = connectingServers.incrementAndGet();
                    if (tried == 1 || tried % 25 == 0) {
                        listener.onLog((volunteerRoute ? "Volunteer route" : "Global")
                                + ": " + tried + " dial attempt(s) so far");
                    }
                    return;
                }
                if (message.contains("Memory metrics at ") || message.contains("Datastore metrics at ")
                        || message.contains("DNS metrics at ")) {
                    return;
                }
            }
            if (volunteerRoute && message != null) {
                String reason = null;
                if (message.contains("in-proxy protocol selection failed: no broker specs")
                        || (message.contains("NewInproxyBrokerClientInstance")
                            && message.contains("no broker specs"))) {
                    reason = "no matchmaker address cached yet";
                } else if (message.contains("in-proxy protocol selection failed: no common compartment IDs")) {
                    reason = "no relay pool access IDs cached yet";
                }
                if (reason != null) {
                    // Retrying cannot fix this inside one run, so stop waiting now rather
                    // than burning the whole timeout before the fallback.
                    if (volunteerBlocked.compareAndSet(null, reason)) {
                        listener.onLog("Volunteer route: " + reason);
                        ready.countDown();
                    }
                    return;
                }
            }
            // One refusal per UDP datagram would otherwise bury everything else in the log.
            // Counted and reported in batches rather than dropped - see GlobalNoise.
            if (GlobalNoise.isRepeatedUdpRefusal(message)) {
                int total = udpRefusals.incrementAndGet();
                if (total == 1 || total % 50 == 0) {
                    String line = GlobalNoise.summary(total);
                    if (line != null) listener.onLog(line);
                }
                return;
            }
            listener.onLog(message);
        }

        @Override public void onClientRegion(String region) { /* where the user is, not the exit */ }

        private void releaseIfReady() {
            if (connected.get() && socksPort.get() > 0) ready.countDown();
        }
    }
}
