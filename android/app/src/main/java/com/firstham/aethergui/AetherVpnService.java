package com.firstham.aethergui;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.VpnService;
import android.telephony.TelephonyManager;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.util.Log;
import android.content.Context;

import androidx.core.app.NotificationCompat;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicBoolean;

import hev.htproxy.TProxyService;

public final class AetherVpnService extends VpnService {
    public static final String ACTION_START = "com.firstham.aethergui.START";
    public static final String ACTION_STOP = "com.firstham.aethergui.STOP";
    public static final String ACTION_QUERY = "com.firstham.aethergui.QUERY";
    public static final String ACTION_STATUS = "com.firstham.aethergui.STATUS";
    public static final String ACTION_LOG = "com.firstham.aethergui.LOG";
    public static final String ACTION_STATS = "com.firstham.aethergui.STATS";
    public static final String ACTION_CLEAR_LOGS = "com.firstham.aethergui.CLEAR_LOGS";
    public static final String ACTION_REFRESH_LOCATION = "com.firstham.aethergui.REFRESH_LOCATION";
    public static final String INTERNAL_PERMISSION = "io.github.amirmahdavi2023.panther.permission.INTERNAL";
    private static final String CHANNEL_ID = "aether_vpn";
    private static final int NOTIFICATION_ID = 1819;
    private static final int SOCKS_TIMEOUT_MS = 120_000;
    private static final int SMART_PROTOCOL_TIMEOUT_MS = 35_000;
    // The Global engine sweeps for a working route on a cold start, which is slower
    // than the other core's endpoint scan.
    private static final long GLOBAL_TIMEOUT_MS = 150_000L;
    // True when an engine was asked for but could not start and we kept the carrier tunnel
    // instead. Held so the connected message tells the truth about which engine the user has,
    // along with the line to show, because Global and Stealth degrade for different reasons.
    private boolean degradedToCarrier = false;
    private volatile String degradedNotice;
    // Edge scan tuning. The sample is wide enough to survive a whole prefix being filtered, the
    // timeout short enough that a dead address costs little, and the cache long enough that
    // reconnecting a few minutes later does not sweep all over again.
    private static final int EDGE_SAMPLE_SIZE = 40;
    private static final int EDGE_PROBE_TIMEOUT_MS = 1_200;
    private static final int EDGE_PARALLELISM = 16;
    private static final long EDGE_CACHE_MS = 30 * 60 * 1000L;
    private static final String TAG = "AetherVpnService";

    private final ExecutorService worker = Executors.newCachedThreadPool();
    private final ScheduledExecutorService telemetry = Executors.newSingleThreadScheduledExecutor();
    private final AtomicLong generation = new AtomicLong();
    private final AtomicBoolean healthCheckRunning = new AtomicBoolean();
    private final AtomicLong locationLookupSequence = new AtomicLong();
    private final AtomicBoolean recoveryRestartPending = new AtomicBoolean();
    private final Object runtimeLock = new Object();
    private final Object networkLock = new Object();
    private final Object logLock = new Object();
    private final StringBuilder logHistory = new StringBuilder();
    private final StringBuilder pendingLogs = new StringBuilder();
    private volatile Process aetherProcess;
    private volatile ParcelFileDescriptor vpnInterface;
    private volatile boolean bridgeStarted;
    private volatile boolean stopping = true;
    private volatile boolean active;
    private volatile boolean killSwitch;
    private volatile boolean smartBenchmarking;
    private volatile boolean masqueH3GatewayUnavailable;
    private volatile String currentState = "disconnected";
    private volatile String currentMessage = "Ready to connect";
    private volatile String currentEndpoint = "";
    private volatile String currentLocationDetail = "";
    private volatile GlobalCore globalCore;
    private volatile StealthCore stealthCore;
    /** The pool the live Stealth engine is dialling from, kept so its history can be saved. */
    private volatile EndpointPool stealthPool;
    /**
     * The carrier's SOCKS address, kept because the request's own "socks" extra is rewritten to
     * the Stealth port once the engine is up. Background probes have to go through the carrier,
     * and sending them through Stealth itself would have them measure the tunnel they are meant
     * to find a replacement for.
     */
    private volatile String stealthCarrier;
    private volatile String currentRegion = "";
    /** Encoded by {@link GlobalRegions#encode}: what the Global engine offered on this run. */
    private volatile String currentAvailableRegions = "";
    private volatile long connectedAt;
    private volatile long lastLogPersistedAt;
    private volatile long lastHealthCheckAt;
    private volatile long lastPing = -1;
    private volatile int consecutiveHealthFailures;
    private volatile Intent activeRequest;
    private final Set<Network> availableNetworks = ConcurrentHashMap.newKeySet();
    private volatile boolean networkUnavailable;
    private volatile boolean connectionEstablished;
    private SharedPreferences stateStore;
    private ConnectivityManager connectivityManager;
    private final ConnectivityManager.NetworkCallback networkCallback = new ConnectivityManager.NetworkCallback() {
        @Override public void onAvailable(Network network) {
            boolean recovering = networkUnavailable || availableNetworks.isEmpty();
            availableNetworks.add(network);
            networkUnavailable = false;
            synchronized (networkLock) { networkLock.notifyAll(); }
            if (recovering) requestCoreRecovery(getString(R.string.service_network_restored));
        }

        @Override public void onLost(Network network) {
            availableNetworks.remove(network);
            if (!availableNetworks.isEmpty() || !active || stopping) return;
            networkUnavailable = true;
            updateState("reconnecting", getString(R.string.service_network_lost));
            updateNotification(getString(R.string.service_network_lost));
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        stateStore = getSharedPreferences("service_state", MODE_PRIVATE);
        String savedLogs = stateStore.getString("logs", "");
        if (savedLogs != null) logHistory.append(savedLogs);
        createNotificationChannel();
        connectivityManager = getSystemService(ConnectivityManager.class);
        NetworkRequest request = new NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .build();
        connectivityManager.registerNetworkCallback(request, networkCallback);
        telemetry.scheduleWithFixedDelay(this::publishStats, 1, 1, TimeUnit.SECONDS);
        telemetry.scheduleWithFixedDelay(this::flushLogs, 75, 75, TimeUnit.MILLISECONDS);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            if (stateStore.getBoolean("desiredConnected", false) && VpnService.prepare(this) == null) {
                return onStartCommand(VpnConnectionController.startIntent(this, getSharedPreferences("aether", MODE_PRIVATE)), flags, startId);
            }
            return active ? START_STICKY : START_NOT_STICKY;
        }
        String action = intent.getAction();
        if (ACTION_QUERY.equals(action)) {
            sendStatus(currentState, currentMessage);
            publishStats();
            if (!active) stopSelf(startId);
            return active ? START_STICKY : START_NOT_STICKY;
        }
        if (ACTION_REFRESH_LOCATION.equals(action)) {
            refreshLocation();
            return START_STICKY;
        }
        if (ACTION_CLEAR_LOGS.equals(action)) {
            synchronized (logLock) {
                logHistory.setLength(0);
                pendingLogs.setLength(0);
            }
            stateStore.edit().remove("logs").apply();
            if (!active) stopSelf(startId);
            return active ? START_STICKY : START_NOT_STICKY;
        }
        if (ACTION_STOP.equals(action)) {
            stateStore.edit().putBoolean("desiredConnected", false).apply();
            generation.incrementAndGet();
            stopping = true;
            updateState("disconnecting", getString(R.string.service_disconnecting));
            worker.execute(() -> stopConnection(true));
            return START_NOT_STICKY;
        }
        if (ACTION_START.equals(action)) {
            stateStore.edit().putBoolean("desiredConnected", true).apply();
            Intent request = new Intent(intent);
            long session = generation.incrementAndGet();
            stopping = true;
            updateState("starting", getString(R.string.service_preparing));
            startForegroundCompat(notification(getString(R.string.service_preparing), false));
            worker.execute(() -> {
                stopConnection(false);
                if (generation.get() != session) return;
                activeRequest = request;
                stopping = false;
                active = true;
                killSwitch = request.getBooleanExtra("killSwitch", false);
                runConnection(request, session);
            });
            return START_STICKY;
        }
        return active ? START_STICKY : START_NOT_STICKY;
    }

    private void runConnection(Intent request, long session) {
        try {
            currentEndpoint = reliableEndpoint(request);
            String connectionMode = value(request, "connectionMode", "vpn");
            if ("smart".equals(connectionMode)) {
                updateState("smart-testing", getString(R.string.service_smart_testing));
                String protocol = chooseSmartProtocol(request, session);
                request.putExtra("protocol", protocol);
                stateStore.edit().putString("smartProtocol", protocol).apply();
                sendLog("Smart Connect selected " + protocolLabel(protocol));
                currentEndpoint = reliableEndpoint(request);
            }
            updateState("starting", getString(R.string.service_launching));
            updateState("scanning", getString(R.string.service_scanning));
            degradedToCarrier = false;
            degradedNotice = null;
            String engine = value(request, "engine", "turbo");
            boolean global = "global".equals(engine);
            boolean stealth = "stealth".equals(engine);
            if (stealth) {
                // Same contract as Global: whichever engine ends up carrying the connection has
                // written its own loopback port into the socks extra by the time this block ends,
                // and everything downstream goes on reading that one extra.
                if (!startStealthCore(request, session)) {
                    // Stealth may well have brought a carrier tunnel up on its way to failing -
                    // it fetches its endpoint list through one. A working tunnel sitting right
                    // there is worth more than an error screen, so keep it. If there is no
                    // carrier either, there is genuinely nothing to fall back to.
                    Process carrier = aetherProcess;
                    if (carrier == null || !carrier.isAlive()) {
                        throw new IllegalStateException(getString(R.string.service_stealth_failed));
                    }
                    stealth = false;
                    degradedToCarrier = true;
                    degradedNotice = getString(R.string.service_stealth_degraded);
                    sendLog("Stealth did not come up; staying on the carrier tunnel instead");
                    updateState("securing", degradedNotice);
                }
            } else if (global) {
                // The Global engine picks its own loopback port, so it has to be started before
                // anything downstream reads the socks extra. Everything after this point - the TUN
                // bridge, the location lookup, the ping probe - goes on reading that one extra and
                // does not need to know which engine filled it in.
                if (!startGlobalCore(request, session)) {
                    // Global failed, but it only gets this far once the carrier tunnel is already
                    // up and serving SOCKS. Tearing that down would hand the user a dead app when
                    // a working tunnel is sitting right there. Carry on with the carrier alone:
                    // they lose the exit country, not their connection. The socks extra still
                    // points at the carrier, because only a successful Global start overwrites it.
                    global = false;
                    degradedToCarrier = true;
                    degradedNotice = getString(R.string.service_global_degraded);
                    sendLog("Global did not come up; staying on the carrier tunnel instead");
                    updateState("securing", degradedNotice);
                }
            } else if (!startAetherWithMasqueFallback(request, SOCKS_TIMEOUT_MS)) {
                throw new IllegalStateException(aetherExitMessage("Turbo did not open its SOCKS5 listener"));
            }

            connectionEstablished = true;
            if ("manual".equals(connectionMode)) {
                connectedAt = System.currentTimeMillis();
                updateState("connected", getString(R.string.service_proxy_ready));
                updateNotification(getString(R.string.service_proxy_connected));
            } else {
                // Decided from the engine that ended up carrying the tunnel, not the one that was
                // armed. A Global run that degraded to the carrier is on an engine that forwards
                // UDP perfectly well, and should keep the ordinary DNS path.
                boolean mappedDns = TunnelConfig.usesMappedDns(global ? "global" : "");
                if (mappedDns) sendLog("Global cannot forward UDP, so DNS is answered inside the tunnel");
                establishVpn(request, mappedDns);
                connectedAt = System.currentTimeMillis();
                updateState("connected", degradedToCarrier && degradedNotice != null
                        ? degradedNotice : getString(R.string.service_protected));
                updateNotification(getString("smart".equals(connectionMode) ? R.string.service_smart_protected : R.string.service_panther_protected));
            }
            scheduleLocationLookup(request, session);
            if (stealth) monitorStealth(request, session);
            else if (global) monitorGlobal(request, session);
            else monitorAether(request, session);
        } catch (Exception error) {
            if (stopping || generation.get() != session) return;
            Log.e(TAG, "Connection failed", error);
            sendLog("Error: " + safeMessage(error));
            // The cached edge answered a UDP probe once, which is not the same as being usable.
            // If the connection failed, stop trusting it and sweep again next time.
            forgetScannedPeer();
            if (killSwitch && vpnInterface != null && !stopping) {
                stopAetherOnly();
                updateState("blocked", getString(R.string.service_blocked));
                updateNotification(getString(R.string.service_blocked_notification));
            } else {
                stopRuntime();
                stateStore.edit().putBoolean("desiredConnected", false).apply();
                updateState("error", getString(R.string.status_error));
                stopForeground(STOP_FOREGROUND_REMOVE);
                active = false;
                stopSelf();
            }
        }
    }

    private void establishVpn(Intent request, boolean mappedDns) throws Exception {
        Builder builder = new Builder()
                .setSession(getString(R.string.app_name))
                .setMtu(request.getIntExtra("mtu", 1500))
                .setBlocking(false)
                .addAddress("198.18.0.1", 30)
                .addAddress("fc00::1", 126);

        String routing = value(request, "routing", "bypass-local");
        if ("bypass-local".equals(routing)) addPublicRoutes(builder);
        else builder.addRoute("0.0.0.0", 0).addRoute("::", 0);

        if (mappedDns) {
            // The default routing mode leaves private ranges to the local network, and the range
            // the bridge draws its answers from is one of them. Without this route every name
            // would resolve and nothing would connect - the quietest possible way to break.
            builder.addRoute(TunnelConfig.MAPPED_NETWORK, TunnelConfig.MAPPED_PREFIX);
        }

        if (mappedDns) {
            // Queries have to reach the bridge's own listener to be answered inside the tunnel.
            // Pointing at a public resolver instead would send them straight back out over UDP,
            // which is the thing this engine cannot do.
            builder.addDnsServer(TunnelConfig.MAPPED_DNS_ADDRESS);
        } else if (request.getBooleanExtra("dnsLeak", true)) {
            builder.addDnsServer("1.1.1.1").addDnsServer("1.0.0.1");
        }
        applySplitApps(builder, request);
        vpnInterface = builder.establish();
        if (vpnInterface == null) throw new IllegalStateException("Android could not create the VPN interface");

        File config = writeTunConfig(request, mappedDns);
        try {
            TProxyService.TProxyStartService(config.getAbsolutePath(), vpnInterface.getFd());
            bridgeStarted = true;
            sendLog("HEV Android TUN bridge started");
        } catch (UnsatisfiedLinkError error) {
            throw new IllegalStateException("The HEV Android JNI bridge could not be loaded", error);
        }
    }

    private void startAether(Intent request) throws Exception {
        File executable = new File(getApplicationInfo().nativeLibraryDir, "libaether.so");
        if (!executable.isFile()) throw new IllegalStateException("Turbo engine is missing for this device architecture");

        ProcessBuilder builder = new ProcessBuilder(executable.getAbsolutePath());
        builder.directory(getFilesDir());
        builder.redirectErrorStream(true);
        Map<String, String> env = builder.environment();
        env.put("AETHER_PROTOCOL", value(request, "protocol", ConnectionDefaults.PROTOCOL));
        env.put("AETHER_SCAN", value(request, "scan", ConnectionDefaults.SCAN));
        env.put("AETHER_IP", value(request, "ipMode", "v4"));
        env.put("AETHER_NOIZE", value(request, "obfuscation", "firewall"));
        env.put("AETHER_LOG_LEVEL", value(request, "logLevel", "info"));
        env.put("AETHER_SOCKS", value(request, "socks", "127.0.0.1:1819"));
        env.put("AETHER_CONFIG", new File(getFilesDir(), "aether.toml").getAbsolutePath());
        env.put("AETHER_QUICK_RECONNECT", request.getBooleanExtra("quickReconnect", true) ? "1" : "0");
        String protocol = value(request, "protocol", ConnectionDefaults.PROTOCOL);
        String transport = value(request, "transport", "h3");
        if ("masque".equals(protocol)) env.put("AETHER_MASQUE_HTTP2", "h2".equals(transport) ? "1" : "0");
        env.put("TMPDIR", getCacheDir().getAbsolutePath());
        String peer = request.getStringExtra("peer");
        if (peer == null || peer.trim().isEmpty()) peer = resolveScannedPeer();
        if (peer != null && !peer.trim().isEmpty()) env.put("AETHER_PEER", peer.trim());

        masqueH3GatewayUnavailable = false;

        synchronized (runtimeLock) {
            aetherProcess = builder.start();
        }
        Process process = aetherProcess;
        sendLog("Turbo engine started for " + Build.SUPPORTED_ABIS[0]);
        Thread logs = new Thread(() -> readAetherLogs(process, protocol, transport), "aether-log-reader");
        logs.setDaemon(true);
        logs.start();
    }

    /**
     * Finds an edge address for the core to dial, when the user has not pinned one themselves.
     *
     * The core ships a single anycast address; on a network that blackholes it, connecting simply
     * never works. This sweeps a sample of Cloudflare's edge in parallel and hands over whichever
     * one answered fastest. Three deliberate properties:
     *
     *  - A result is cached per network, because a sweep on every connect would add seconds to the
     *    common case where the previous winner still works.
     *  - The cache is short-lived. Which address answers is a property of the network, and networks
     *    change under you (wifi to mobile, one ISP's filtering rules to another's).
     *  - Finding nothing returns null, which leaves AETHER_PEER unset and the core on its own
     *    default. A failed scan must never be worse than not scanning at all.
     */
    private String resolveScannedPeer() {
        try {
            SharedPreferences preferences = getSharedPreferences("settings", MODE_PRIVATE);
            // 🚨 Off by default since v2.9.2. The device log shows this sweep answering nothing,
            // every single connect, while costing ~3.6s before the core is even started - and the
            // core's own cached endpoint then verifies in about half a second and works. The
            // address ranges it sweeps were never confirmed to serve WARP in the first place, so
            // this was paying a real cost for an unproven benefit. The setting stays so it can be
            // turned back on, but nothing should be waiting on it by default.
            if (!preferences.getBoolean("edgeScan", false)) return null;

            String cached = preferences.getString("scannedPeer", "");
            long cachedAt = preferences.getLong("scannedPeerAt", 0L);
            if (!cached.isEmpty() && System.currentTimeMillis() - cachedAt < EDGE_CACHE_MS) {
                sendLog("Edge scan: reusing " + cached);
                return cached;
            }

            updateState("scanning", getString(R.string.service_scanning_edges));
            long started = System.currentTimeMillis();
            String best = EndpointScanner.bestEndpoint(
                    EDGE_SAMPLE_SIZE, EDGE_PROBE_TIMEOUT_MS, EDGE_PARALLELISM,
                    EndpointScanner.UDP_PROBE, new java.util.Random());
            long elapsed = System.currentTimeMillis() - started;

            if (best == null) {
                sendLog("Edge scan: nothing answered in " + elapsed + "ms; using the core's default");
                return null;
            }
            sendLog("Edge scan: chose " + best + " in " + elapsed + "ms");
            preferences.edit().putString("scannedPeer", best).putLong("scannedPeerAt", System.currentTimeMillis()).apply();
            return best;
        } catch (Throwable failure) {
            // Never let the scan take the connection down with it.
            sendLog("Edge scan skipped: " + safeMessage(failure));
            return null;
        }
    }

    /** Drops the cached edge so the next connect sweeps again. Called when a connection fails. */
    private void forgetScannedPeer() {
        try {
            getSharedPreferences("settings", MODE_PRIVATE).edit()
                    .remove("scannedPeer").remove("scannedPeerAt").apply();
        } catch (Throwable ignored) {
            // Nothing to do; the cache expires on its own anyway.
        }
    }

    private void readAetherLogs(Process process, String protocol, String transport) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sendLog("[Turbo] " + line);
                String lower = line.toLowerCase(Locale.US);
                if (process == aetherProcess && "masque".equals(protocol) && "h3".equals(transport)
                        && lower.contains("no usable masque gateway found")) {
                    masqueH3GatewayUnavailable = true;
                }
                if (!smartBenchmarking) {
                    if (lower.contains("identity ready")) updateState("scanning", getString(R.string.service_identity_ready));
                    if (lower.contains("hunting for")) updateState("scanning", getString(R.string.service_testing_gateways));
                    if (lower.contains("validated") || lower.contains("passed handshake")) updateState("securing", getString(R.string.service_gateway_verified));
                }
            }
        } catch (Exception error) {
            if (!stopping) sendLog("Turbo log stream closed: " + safeMessage(error));
        }
    }

    /**
     * Brings the Global engine up and publishes the port it chose as the socks extra, so the rest
     * of the connection path is identical to the other engine's.
     */
    private boolean startGlobalCore(Intent request, long session) throws Exception {
        String region = value(request, "region", GlobalCore.REGION_AUTOMATIC);
        currentRegion = "";

        // Global runs on top of Turbo rather than beside it. The engine bootstraps by fetching a
        // server list over HTTPS from a host that does not answer on some of the networks this app
        // exists for, and its own servers are filtered on those same networks, so on its own it
        // never gets off the ground. Carried inside the other tunnel, both the fetch and the
        // handshake succeed. Bring the carrier up first and fail early if it will not start,
        // because a Global failure caused by the carrier is otherwise very hard to read.
        String carrier = value(request, "socks", "127.0.0.1:1819");
        updateState("scanning", getString(R.string.service_global_carrier));
        if (!startAetherWithMasqueFallback(request, SOCKS_TIMEOUT_MS)) {
            throw new IllegalStateException(aetherExitMessage(getString(R.string.service_global_carrier_failed)));
        }
        sendLog("Carrier tunnel up on " + carrier + "; starting the Global engine over it");
        updateState("securing", getString(R.string.service_global_starting));

        GlobalCore core = new GlobalCore(this, region, carrier, new GlobalCore.Listener() {
            @Override public void onState(String state, String message) {
                // The engine reports its own progress while it is still searching for a route.
                // Only surface that before we are connected; afterwards the monitor owns the state.
                if (generation.get() != session || stopping) return;
                if (!"connected".equals(state)) updateState(state, message);
            }

            @Override public void onRegion(String countryCode) {
                if (generation.get() != session || stopping) return;
                // The engine's own answer for where this tunnel comes out. Nothing else is
                // allowed to fill this in.
                currentRegion = countryCode == null ? "" : countryCode.trim().toUpperCase(Locale.US);
                sendLog("Global engine exit region: " + currentRegion);
                sendStatus(currentState, currentMessage);
            }

            @Override public void onBytes(long sent, long received) { /* the stats poll owns this */ }

            @Override public void onLog(String line) { sendLog(line); }
        });
        globalCore = core;
        if (!core.start(GLOBAL_TIMEOUT_MS)) {
            core.stop();
            globalCore = null;
            return false;
        }
        int port = core.socksPort();
        if (port <= 0) { core.stop(); globalCore = null; return false; }
        request.putExtra("socks", "127.0.0.1:" + port);
        sendLog("Global engine ready; routing the tunnel through 127.0.0.1:" + port);
        return true;
    }

    /**
     * Watches the Global engine the way monitorAether watches the other one. The library handles
     * its own retries internally, so this only has to notice that it gave up for good.
     */
    private void monitorGlobal(Intent request, long session) throws Exception {
        while (!stopping && generation.get() == session) {
            GlobalCore core = globalCore;
            if (core == null) return;
            if (!core.isConnected()) {
                throw new IllegalStateException(getString(R.string.service_global_stopped));
            }
            Process carrier = aetherProcess;
            if (carrier == null || !carrier.isAlive()) {
                // Global rides inside the carrier, so losing the carrier takes Global with it.
                throw new IllegalStateException(getString(R.string.service_global_carrier_lost));
            }
            Thread.sleep(2_000L);
        }
    }

    /**
     * Brings the Stealth engine up and publishes the port it listens on, so the rest of the
     * connection path is identical to the other two engines'.
     *
     * <p>🔑 The carrier plays a different part here than it does for Global. Global runs
     * <em>inside</em> the carrier and cannot exist without it. Stealth dials its own endpoints
     * directly and only wants a tunnel to fetch its endpoint list through, because the config
     * sources are blocked on exactly the networks this engine exists for. So a carrier that will
     * not start costs the refresh, not the connection: the pool this device scored and saved
     * earlier is enough to dial from, and being able to come up with no fetch at all is the whole
     * reason this engine is worth having.
     *
     * <p>When the carrier does come up it is left running. It costs one idle process, and it buys
     * the fallback in {@code runConnection} if the engine then fails to find a working endpoint.
     */
    private boolean startStealthCore(Intent request, long session) throws Exception {
        currentRegion = "";
        EndpointPool pool = loadStealthPool();
        stealthPool = pool;
        long savedAt = stealthPoolSavedAt();

        updateState("scanning", getString(R.string.service_stealth_carrier));
        boolean carrier = false;
        try {
            carrier = startAetherWithMasqueFallback(request, SOCKS_TIMEOUT_MS);
        } catch (Exception error) {
            sendLog("Stealth could not raise a carrier to fetch through: " + safeMessage(error));
        }
        if (generation.get() != session || stopping) return false;
        if (!carrier) {
            // Nothing half-started is left behind to hold the port or the battery.
            stopAetherOnly();
            sendLog("No carrier tunnel; Stealth falls back to the endpoints this device saved");
        }

        // 🔑 Every "that server did not answer" verdict rests on one probe host replying. If that
        // host is unreachable on this network, all four hundred servers look dead and the log says
        // so convincingly. So the probe is proved first, through the carrier - a tunnel we already
        // know works - and only then trusted to judge anything.
        if (carrier) {
            String[] parts = value(request, "socks", "127.0.0.1:1819").split(":");
            try {
                int chosen = SocksProbe.chooseTarget(parts[0], Integer.parseInt(parts[1]), 8_000);
                sendLog(chosen < 0
                        ? "🚨 Prowl probe: NO check host answered through a working carrier - every "
                          + "server will look dead regardless of whether it is"
                        : "Prowl probe: using " + SocksProbe.PROBE_HOST());
            } catch (Exception error) {
                sendLog("Prowl probe: could not be proved (" + safeMessage(error) + ")");
            }
        }

        String wantedCountry = chosenStealthRegion();
        StealthPlan.Decision decision =
                StealthPlan.decide(pool, savedAt, carrier, System.currentTimeMillis());
        sendLog("Stealth pool: " + decision.reason);
        if (decision.isStuck()) {
            throw new IllegalStateException(getString(R.string.service_stealth_no_endpoints));
        }
        // 🔑 A refresh is eight files pulled through a tunnel that is itself two hops, then a test
        // pass over two dozen endpoints. That is most of the time a Stealth connect takes, and it
        // was being paid BEFORE the endpoints this device already scored were tried even once.
        // So a stale-but-usable pool now gets dialled first and the refresh only happens if that
        // fails. A pool with nothing dialable in it still refreshes up front - there is nothing
        // else to try.
        // 🚨 The refresh is the fallback for EVERY failed dial pass, not only for a pool that was
        // already stale. Gating it on decision.refresh was the bug: a failed run re-saves the pool,
        // the pool then looks freshly verified, and a device whose saved servers had all died could
        // never reach new ones. It retried the same corpses on every connect with no way out.
        boolean refreshDeferred = true;
        if (decision.refresh
                && StealthPlan.ready(pool, System.currentTimeMillis()) < StealthPlan.ENOUGH_SAVED) {
            // Nothing worth dialling, so fetching first is the only move there is.
            refreshDeferred = false;
            refreshStealthPool(pool, request, session, wantedCountry);
            if (generation.get() != session || stopping) return false;
        } else if (decision.refresh) {
            sendLog("Prowl: dialling the saved servers first, refreshing if none hold");
        }
        if (StealthPlan.ready(pool, System.currentTimeMillis()) == 0) {
            // A carrier is up, so this is the same situation as the engine failing to dial: hand
            // the decision back and let the caller keep the working tunnel rather than fail hard.
            if (carrier) {
                sendLog("Stealth found nothing in its pool that this core can dial");
                return false;
            }
            throw new IllegalStateException(getString(R.string.service_stealth_no_endpoints));
        }

        updateState("securing", getString(R.string.service_stealth_starting));
        // 🔑 The carrier is handed to the engine, not just used for the fetch. Dialling a public
        // endpoint straight out of a filtered network is the case this engine was failing on: the
        // servers were alive, the route to them was not. Given the carrier the engine can open the
        // connection from wherever the carrier exits instead, and the exit the user ends up with
        // is still the endpoint's own country - only the first hop moves. Which way works is
        // remembered, so the next connect starts with the answer instead of finding it again.
        String carrierAddress = carrier ? value(request, "socks", "127.0.0.1:1819") : null;
        stealthCarrier = carrierAddress;
        String networkKey = networkKey();
        NetworkMemory networks =
                NetworkMemory.deserialise(stateStore.getString("stealthNetworks", ""));
        // What worked on THIS network, falling back to the app's old single flag so an upgrade
        // does not throw away what the device already knew.
        boolean preferChained =
                networks.preferChainedOn(networkKey, stateStore.getBoolean("stealthChained", false));
        StealthCore core = new StealthCore(this, pool, XrayConfig.SOCKS_PORT, carrierAddress,
                preferChained, new StealthCore.Listener() {
            @Override public void onState(String state, String message) {
                // The engine reports its own progress while it is still working through
                // candidates. Only surface that before we are connected; afterwards the monitor
                // owns the state, exactly as it does for the other engine.
                if (generation.get() != session || stopping) return;
                if (!"connected".equals(state)) updateState(state, message);
            }

            @Override public void onLog(String line) { sendLog(line); }

            @Override public void onEndpoint(ProxyConfig endpoint) {
                if (generation.get() != session || stopping) return;
                // Only the protocol and the host, never the label: a pool entry's label is
                // whatever its author typed and is often an advert or an outright lie about
                // where the server is. The location card answers that question for real.
                sendLog("Stealth is carrying traffic through " + endpoint);
            }
        });
        stealthCore = core;
        core.prefer(wantedCountry);
        boolean up = core.start();
        if (!up && refreshDeferred && !stopping && generation.get() == session) {
            // The saved list did not hold, which is exactly the case the refresh exists for. It is
            // paid now, once, on evidence - rather than up front on every connect.
            core.stop();
            refreshStealthPool(pool, request, session, wantedCountry);
            if (generation.get() != session || stopping) { stealthCore = null; return false; }
            if (StealthPlan.ready(pool, System.currentTimeMillis()) > 0) {
                sendLog("Stealth: retrying with the refreshed pool");
                core.prefer(wantedCountry);
                up = core.start(StealthCore.RETRY_BUDGET_MS);
            }
        }
        // Saved either way. A run that failed still learned which endpoints are dead, and that is
        // worth as much next time as knowing which one worked. But a failed run must not leave the
        // pool looking freshly-verified: freshness is the file's own timestamp, so re-saving after
        // a total failure is what let a device sit on a dead list indefinitely.
        saveStealthPool(pool);
        markStealthPoolStale(!up);
        if (!up) {
            core.stop();
            stealthCore = null;
            return false;
        }
        networks.remember(networkKey, core.isChained());
        stateStore.edit()
                .putBoolean("stealthChained", core.isChained())
                .putString("stealthNetworks", networks.serialise())
                .apply();
        sendLog("Stealth remembered " + (core.isChained() ? "the carrier route" : "the direct route")
                + " for this network");
        request.putExtra("socks", XrayConfig.SOCKS_LISTEN + ":" + core.socksPort());
        sendLog("Stealth engine ready; routing the tunnel through "
                + XrayConfig.SOCKS_LISTEN + ":" + core.socksPort()
                + (core.isChained() ? " (dialling out through the carrier)" : " (dialling out directly)"));
        return true;
    }

    /**
     * Fetches fresh endpoints through the carrier tunnel and tests them on this device.
     *
     * <p>Never throws. Every step of this is optional: a source that is down, a fetch that returns
     * junk or a test pass where nothing answers all leave the saved pool exactly as it was, which
     * is still something to dial. Only an empty pool is fatal, and that is decided by the caller.
     */
    private void refreshStealthPool(EndpointPool pool, Intent request, long session,
                                    String country) {
        final String carrier = value(request, "socks", "127.0.0.1:1819");
        updateState("scanning", getString(R.string.service_stealth_refreshing));
        ConfigSources.Fetcher fetcher = new ConfigSources.Fetcher() {
            @Override public String fetch(String host, String path) throws Exception {
                return socksHttpGet(carrier, host, path);
            }
        };
        ConfigSources.Refresh refresh = ConfigSources.refresh(fetcher);
        sendLog("Stealth sources: " + refresh.summary());

        // A chosen country needs its own list. Two of the general sources are themselves the
        // Netherlands and Germany lists, so filtering the merged pool by country would offer a
        // user who picked Japan almost nothing. This is one extra fetch and only when it is asked
        // for; if it fails the general pool still carries the connection.
        if (!StealthRegions.isAutomatic(country)) {
            ConfigSources.Refresh local = ConfigSources.refreshCountry(
                    fetcher, country, ConfigSources.MAX_CANDIDATES);
            sendLog("Stealth " + StealthRegions.name(country) + " list: " + local.summary());
            if (!local.isEmpty()) {
                List<ProxyConfig> both = new ArrayList<>(local.configs);
                both.addAll(refresh.configs);
                refresh = new ConfigSources.Refresh(both, local.succeeded, local.failed);
            }
        }
        if (generation.get() != session || stopping || refresh.isEmpty()) return;

        List<ProxyConfig> candidates = StealthPlan.candidates(refresh.configs);
        if (candidates.isEmpty()) {
            sendLog("Stealth: nothing in that fetch is a protocol this core can dial");
            return;
        }
        updateState("scanning", getString(R.string.service_stealth_testing, candidates.size()));
        EndpointTester.Outcome outcome = EndpointTester.test(pool, candidates,
                EndpointTester.NETWORK_PROBE, StealthPlan.TEST_TIMEOUT_MS,
                StealthPlan.TEST_PARALLELISM, StealthPlan.TEST_ENOUGH, System.currentTimeMillis());
        sendLog("Stealth tested: " + outcome.summary());
        saveStealthPool(pool);
    }

    /**
     * Watches the Stealth engine and moves it off an endpoint that has stopped working.
     *
     * <p>🚨 The check that matters is not whether the core is running. The core answers its own
     * SOCKS handshake and dials the real server lazily, so an endpoint that has been black-holed
     * looks perfectly healthy from the outside and would keep the user staring at a connected
     * screen with no internet. So the tunnel is periodically made to carry a real request.
     *
     * <p>A swap does not touch the loopback port, so the TUN interface is never rebuilt and the
     * apps on top of it do not see the network go away and come back.
     */
    /**
     * The exit country the user picked for Stealth.
     *
     * <p>Read from the app preferences rather than from the service's own state store, and
     * deliberately not from the start intent: the picker can be used while the tunnel is up, and
     * an extra captured at connect time would be the country the user wanted an hour ago. The
     * activity and the service share a process, so a change made in the sheet is visible here as
     * soon as it is written.
     */
    /**
     * Prowl has no country to honour.
     *
     * <p>It used to take one from the picker. That was the wrong shape for this engine: it exits
     * wherever the server it managed to dial happens to sit, so a chosen country was a filter over
     * a pool that changes under the user and often had nothing behind it at all. Automatic is now
     * the only mode, and the live IP is what tells the user where they came out.
     */
    private String chosenStealthRegion() {
        return StealthRegions.AUTOMATIC;
    }

    /**
     * A stable name for the network in use, for {@link NetworkMemory}.
     *
     * <p>Mobile networks are told apart by operator name, which needs no permission. Wifi networks
     * are not told apart at all: the SSID is behind a location permission this app does not ask
     * for. Anything that cannot be read leaves the key unknown rather than guessing, because a
     * wrong key would teach one network's answer to another.
     */
    private String networkKey() {
        try {
            ConnectivityManager connectivity =
                    (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            if (connectivity == null) return NetworkMemory.UNKNOWN;
            Network active = connectivity.getActiveNetwork();
            NetworkCapabilities capabilities =
                    active == null ? null : connectivity.getNetworkCapabilities(active);
            if (capabilities == null) return NetworkMemory.UNKNOWN;
            if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                return NetworkMemory.key(false, null);
            }
            TelephonyManager telephony =
                    (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
            String operator = telephony == null ? null : telephony.getNetworkOperatorName();
            return NetworkMemory.key(true, operator);
        } catch (Throwable unavailable) {
            return NetworkMemory.UNKNOWN;
        }
    }

    /**
     * Probes a few of the endpoints next in line while the tunnel is healthy, so the successor is
     * proved before it is needed rather than guessed at the moment it is.
     *
     * <p>Never throws and never touches the live connection. The probe takes the same route the
     * engine dials on this network: probing directly while the engine reaches its endpoints
     * through the carrier would fail on healthy servers and bench the pool a pass at a time, which
     * is the opposite of the point. If that route cannot be built, the pass is skipped — doing
     * nothing is always better here than learning something false.
     */
    private void runStandbyPass(StealthCore core, String country) {
        EndpointPool pool = stealthPool;
        if (pool == null || core == null) return;
        try {
            long now = System.currentTimeMillis();
            ProxyConfig live = core.current();
            List<ProxyConfig> candidates = StandbyProber.candidates(
                    pool, country, live == null ? null : live.key(), now, StandbyProber.BATCH);
            if (candidates.isEmpty()) return;

            EndpointTester.Probe probe = EndpointTester.NETWORK_PROBE;
            if (core.isChained()) {
                probe = EndpointTester.throughCarrier(stealthCarrier);
                if (probe == null) return;
            }
            EndpointTester.Outcome outcome = EndpointTester.test(pool, candidates, probe,
                    StandbyProber.TIMEOUT_MS, StandbyProber.PARALLELISM, 0, now);
            sendLog("Stealth standby: " + outcome.summary());
            saveStealthPool(pool);
        } catch (Throwable harmless) {
            // A background pass is an optimisation. Losing one costs the next swap a little time
            // and nothing else, so it must never take the connection down with it.
            sendLog("Stealth standby pass skipped: " + safeMessage(harmless));
        }
    }

    private void monitorStealth(Intent request, long session) throws Exception {
        int swaps = 0;
        int verifyFailures = 0;
        long dialledAt = System.currentTimeMillis();
        long lastVerifiedAt = dialledAt;
        long lastStandbyAt = 0;
        while (!stopping && generation.get() == session) {
            Thread.sleep(StealthPlan.MONITOR_TICK_MS);
            if (stopping || generation.get() != session) return;
            StealthCore core = stealthCore;
            if (core == null) return;

            // Keep a proved successor ready while nothing is wrong. Costs a handful of probes
            // every few minutes and only when there is something to learn.
            long tick = System.currentTimeMillis();
            int warm = StandbyProber.warmCount(stealthPool, core.country(),
                    core.current() == null ? null : core.current().key(), tick);
            if (StandbyProber.due(dialledAt, lastStandbyAt, warm, tick)) {
                lastStandbyAt = tick;
                runStandbyPass(core, core.country());
                if (stopping || generation.get() != session) return;
            }

            String failure = null;
            if (!core.isConnected()) {
                failure = "the core stopped";
            } else if (StealthPlan.shouldVerify(lastVerifiedAt, System.currentTimeMillis())) {
                if (core.verify()) {
                    lastVerifiedAt = System.currentTimeMillis();
                    verifyFailures = 0;
                } else if (++verifyFailures < StealthPlan.VERIFY_FAILURES_BEFORE_SWAP) {
                    // Not proof of anything yet. Ask again shortly rather than throwing away a
                    // tunnel that may well still be carrying the user's traffic - see the note on
                    // VERIFY_FAILURES_BEFORE_SWAP for what one failed check is actually worth.
                    lastVerifiedAt = System.currentTimeMillis()
                            - StealthPlan.VERIFY_INTERVAL_MS + StealthPlan.VERIFY_RECHECK_MS;
                    sendLog("Stealth check " + verifyFailures + " of "
                            + StealthPlan.VERIFY_FAILURES_BEFORE_SWAP
                            + " came back empty; asking again before moving");
                } else {
                    failure = "the tunnel stopped carrying traffic";
                }
            }
            if (failure == null) continue;
            if (stopping || generation.get() != session) return;

            swaps = StealthPlan.swapsAfter(swaps, System.currentTimeMillis() - dialledAt);
            if (StealthPlan.exhausted(swaps)) {
                // Burning through endpoint after endpoint means this network is not going to be
                // beaten by trying harder. Fail properly so the kill switch and the reconnect
                // rules get their say, rather than looping forever behind a connected screen.
                throw new IllegalStateException(getString(R.string.service_stealth_exhausted));
            }
            updateState("reconnecting", getString(R.string.service_stealth_swapping));
            if (!core.swap(failure)) {
                throw new IllegalStateException(getString(R.string.service_stealth_stopped));
            }
            dialledAt = System.currentTimeMillis();
            lastVerifiedAt = dialledAt;
            lastStandbyAt = 0;
            verifyFailures = 0;
            saveStealthPool(stealthPool);
            updateState("connected", getString("manual".equals(value(request, "connectionMode", "vpn"))
                    ? R.string.service_proxy_ready : R.string.service_protected));
            // The exit just changed, so the location on screen is now wrong. Ask again.
            scheduleLocationLookup(request, session);
        }
    }

    private File stealthPoolFile() {
        return new File(getFilesDir(), StealthPlan.POOL_FILE);
    }

    /** When the pool was last written, or 0 if it never has been. */
    /**
     * Backdates the pool file so the next connect treats it as stale.
     *
     * <p>Freshness is the file's modification time, and the pool is saved after every run - a
     * failed one included, because knowing which servers are dead is worth keeping. Backdating
     * separates those two facts: keep what was learned, but do not let it claim to be verified.
     */
    private void markStealthPoolStale(boolean exhausted) {
        // 🚨 This used to backdate the pool file. File.setLastModified is unreliable on Android and
        // fails silently on plenty of devices, so the flag lives somewhere that cannot refuse it.
        getSharedPreferences("service_state", MODE_PRIVATE).edit()
                .putBoolean("stealthPoolExhausted", exhausted).apply();
    }

    private long stealthPoolSavedAt() {
        // A pool the last run could not dial anything out of is stale no matter what its file says.
        if (getSharedPreferences("service_state", MODE_PRIVATE)
                .getBoolean("stealthPoolExhausted", false)) {
            return 0L;
        }
        File file = stealthPoolFile();
        return file.isFile() ? file.lastModified() : 0L;
    }

    /**
     * Reads the saved pool. A missing or unreadable file is an empty pool, never an error: this
     * runs on the path that has to keep working when everything else has failed.
     */
    private EndpointPool loadStealthPool() {
        File file = stealthPoolFile();
        if (!file.isFile()) return new EndpointPool();
        StringBuilder body = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) body.append(line).append('\n');
        } catch (Exception error) {
            sendLog("Stealth could not read its saved pool: " + safeMessage(error));
            return new EndpointPool();
        }
        EndpointPool pool = EndpointPool.deserialise(body.toString());
        sendLog("Stealth loaded " + pool.size() + " saved endpoints");
        return pool;
    }

    private void saveStealthPool(EndpointPool pool) {
        if (pool == null) return;
        try {
            pool.prune(System.currentTimeMillis());
            try (FileWriter writer = new FileWriter(stealthPoolFile())) {
                writer.write(pool.serialise());
            }
        } catch (Exception error) {
            // Losing the save costs us the next cold start, not this connection.
            sendLog("Stealth could not save its pool: " + safeMessage(error));
        }
    }

    private void stopStealthOnly() {
        StealthCore core = stealthCore;
        stealthCore = null;
        if (core != null) core.stop();
    }

    private void monitorAether(Intent request, long session) throws Exception {
        int attempts = 0;
        // Starts when the first recovery does, not when the connection did, and is reset by a core
        // that genuinely came back. Time spent waiting for the phone to have a network at all is
        // not charged against it - that is the user's train going into a tunnel, not a failure.
        long recoveryStartedAt = 0;
        while (!stopping && generation.get() == session) {
            Process process = aetherProcess;
            if (process == null) return;
            long processStartedAt = System.currentTimeMillis();
            int exitCode = process.waitFor();
            if (stopping || generation.get() != session) return;
            sendLog("Turbo exited with code " + exitCode);
            if (!request.getBooleanExtra("quickReconnect", true)) {
                throw new IllegalStateException("Turbo stopped unexpectedly (exit " + exitCode + ")");
            }
            waitForUnderlyingNetwork(session);
            if (stopping || generation.get() != session) return;
            if (ReconnectPolicy.recovered(System.currentTimeMillis() - processStartedAt)) {
                attempts = 0;
                recoveryStartedAt = 0;
            }
            if (recoveryStartedAt == 0) recoveryStartedAt = System.currentTimeMillis();
            attempts++;
            long elapsed = System.currentTimeMillis() - recoveryStartedAt;
            if (ReconnectPolicy.exhausted(attempts, elapsed)) {
                throw new IllegalStateException(getString(R.string.service_reconnect_failed, ReconnectPolicy.MAX_ATTEMPTS));
            }
            currentEndpoint = "";
            currentLocationDetail = "";
            // The edge we were using just died on us. Retrying the same address is the one thing
            // guaranteed not to help, so drop it and let the next start sweep for another one.
            forgetScannedPeer();
            // Says which attempt this is. A silent "reconnecting" for minutes on end is
            // indistinguishable from a frozen app, which is how this used to read.
            updateState("reconnecting", getString(R.string.service_reconnecting_attempt,
                    attempts, ReconnectPolicy.MAX_ATTEMPTS));
            Thread.sleep(ReconnectPolicy.backoffMs(attempts));
            if (stopping || generation.get() != session) return;
            long timeout = ReconnectPolicy.remainingTimeoutMs(System.currentTimeMillis() - recoveryStartedAt);
            if (timeout <= 0) {
                throw new IllegalStateException(getString(R.string.service_reconnect_failed, ReconnectPolicy.MAX_ATTEMPTS));
            }
            if (!startAetherWithMasqueFallback(request, timeout)) {
                sendLog(aetherExitMessage("Turbo reconnect attempt did not become ready"));
                Process retry = aetherProcess;
                if (retry != null && retry.isAlive()) retry.destroy();
                continue;
            }
            recoveryRestartPending.set(false);
            attempts = 0;
            recoveryStartedAt = 0;
            updateState("connected", getString(R.string.service_restored));
            updateNotification(getString(R.string.service_restored));
            scheduleLocationLookup(request, session);
        }
    }

    private void waitForUnderlyingNetwork(long session) throws InterruptedException {
        while (networkUnavailable && !stopping && generation.get() == session) {
            updateState("reconnecting", getString(R.string.service_network_lost));
            synchronized (networkLock) { networkLock.wait(30_000L); }
        }
    }

    private boolean waitForSocks(String address, long timeoutMs) {
        HostPort target;
        try { target = HostPort.parse(address); }
        catch (IllegalArgumentException error) { throw error; }
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (!stopping && System.currentTimeMillis() < deadline) {
            Process process = aetherProcess;
            if (process != null && !process.isAlive()) return false;
            if (masqueH3GatewayUnavailable) return false;
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(target.host, target.port), 700);
                return true;
            } catch (Exception ignored) {
                try { Thread.sleep(400); }
                catch (InterruptedException error) { Thread.currentThread().interrupt(); return false; }
            }
        }
        return false;
    }

    private boolean startAetherWithMasqueFallback(Intent request, long timeoutMs) throws Exception {
        startAether(request);
        String socks = value(request, "socks", "127.0.0.1:1819");
        if (waitForSocks(socks, timeoutMs)) return true;
        if (stopping || !"masque".equals(value(request, "protocol", ConnectionDefaults.PROTOCOL))
                || !"h3".equals(value(request, "transport", "h3")) || !masqueH3GatewayUnavailable) {
            return false;
        }
        sendLog("MASQUE HTTP/3 gateway scan failed; retrying with HTTP/2 transport");
        stopAetherOnly();
        request.putExtra("transport", "h2");
        updateState("scanning", getString(R.string.service_scanning));
        startAether(request);
        return waitForSocks(socks, timeoutMs);
    }

    private String chooseSmartProtocol(Intent request, long session) throws Exception {
        String[] protocols = {"masque", "wg", "gool"};
        SmartResult best = null;
        smartBenchmarking = true;
        try {
            for (int index = 0; index < protocols.length; index++) {
                if (stopping || generation.get() != session) throw new InterruptedException("Smart Connect was cancelled");
                String protocol = protocols[index];
                updateState("smart-testing", getString(R.string.service_testing_protocol, protocolLabel(protocol), index + 1, protocols.length));
                Intent trial = new Intent(request).putExtra("protocol", protocol).putExtra("quickReconnect", false);
                SmartResult result = benchmarkProtocol(trial, protocol);
                sendLog(result.summary());
                if (best == null || result.score > best.score) best = result;
                stopAetherOnly();
                Thread.sleep(500);
            }
        } finally {
            smartBenchmarking = false;
            stopAetherOnly();
        }
        if (best == null || !best.connected) throw new IllegalStateException("Smart Connect could not establish any available protocol");
        return best.protocol;
    }

    private SmartResult benchmarkProtocol(Intent request, String protocol) {
        long started = System.nanoTime();
        try {
            startAether(request);
            String socks = value(request, "socks", "127.0.0.1:1819");
            boolean connected = waitForSocks(socks, SMART_PROTOCOL_TIMEOUT_MS);
            long handshakeMs = elapsedMillis(started);
            if (!connected) return SmartResult.failed(protocol, handshakeMs);
            long latencyMs = socksConnectMillis(socks, "1.1.1.1", 443, 4_000);
            long dnsMs = socksConnectMillis(socks, "cloudflare.com", 443, 5_000);
            int attempts = 5;
            int stable = 0;
            long latencyTotal = 0;
            for (int i = 0; i < attempts; i++) {
                if (stopping) break;
                try {
                    long probe = socksConnectMillis(socks, "1.1.1.1", 443, 4_000);
                    latencyTotal += probe;
                    stable++;
                } catch (Exception ignored) { }
                try { Thread.sleep(600); }
                catch (InterruptedException error) { Thread.currentThread().interrupt(); break; }
            }
            if (stable > 0) latencyMs = Math.min(latencyMs, latencyTotal / stable);
            return SmartResult.success(protocol, handshakeMs, latencyMs, dnsMs, stable, attempts);
        } catch (Throwable error) {
            return SmartResult.failed(protocol, elapsedMillis(started));
        }
    }

    private Socket openSocksTunnel(String socksAddress, String host, int port, int timeoutMs) throws Exception {
        HostPort proxy = HostPort.parse(socksAddress);
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(proxy.host, proxy.port), timeoutMs);
            socket.setSoTimeout(timeoutMs);
            InputStream input = socket.getInputStream();
            OutputStream output = socket.getOutputStream();
            output.write(new byte[]{5, 1, 0});
            output.flush();
            byte[] greeting = readExact(input, 2);
            if (greeting[0] != 5 || greeting[1] != 0) throw new IllegalStateException("SOCKS5 authentication failed");
            byte[] ipv4 = parseIpv4Address(host);
            if (ipv4 != null) {
                output.write(new byte[]{5, 1, 0, 1});
                output.write(ipv4);
            } else {
                byte[] hostBytes = host.getBytes(StandardCharsets.US_ASCII);
                if (hostBytes.length > 255) throw new IllegalArgumentException("SOCKS5 host is too long");
                output.write(new byte[]{5, 1, 0, 3, (byte) hostBytes.length});
                output.write(hostBytes);
            }
            output.write(new byte[]{(byte) (port >>> 8), (byte) port});
            output.flush();
            byte[] response = readExact(input, 4);
            if (response[0] != 5 || response[1] != 0) throw new IllegalStateException("SOCKS5 connection failed");
            int addressLength;
            if (response[3] == 1) addressLength = 4;
            else if (response[3] == 4) addressLength = 16;
            else if (response[3] == 3) addressLength = readExact(input, 1)[0] & 0xff;
            else throw new IllegalStateException("Invalid SOCKS5 response");
            readExact(input, addressLength + 2);
            return socket;
        } catch (Throwable error) {
            try { socket.close(); } catch (Exception ignored) { }
            throw error;
        }
    }

    private long socksConnectMillis(String socksAddress, String host, int port, int timeoutMs) throws Exception {
        long started = System.nanoTime();
        try (Socket socket = openSocksTunnel(socksAddress, host, port, timeoutMs)) {
            return elapsedMillis(started);
        }
    }

    /**
     * Where each round of the lookup asks, in order. Every one of these reports the caller's own
     * address, so a single request answers the whole question - the old two-hop shape (ask ipify
     * who we are, then ask a geocoder about that address) doubled the chance of failing and the
     * time to first answer.
     *
     * <p>Cloudflare goes first because the core exits through Cloudflare's own network, so it is
     * the one service that will never rate-limit or refuse this traffic. The others are there for
     * the case where Cloudflare itself is unreachable.
     */
    private static final String[][] LOCATION_PROVIDERS = {
            {"speed.cloudflare.com", "/meta"},
            {"www.cloudflare.com", "/cdn-cgi/trace"},
            {"ipwho.is", "/"},
            {"ipapi.co", "/json/"},
    };

    private void scheduleLocationLookup(Intent request, long session) {
        long lookup = locationLookupSequence.incrementAndGet();
        currentEndpoint = getString(R.string.location_detecting);
        currentLocationDetail = "";
        sendStatus(currentState, currentMessage);
        worker.execute(() -> {
            String socksAddress = value(request, "socks", "127.0.0.1:1819");
            ExitLocation found = ExitLocation.EMPTY;
            for (int round = 0; round < 3 && !found.usable() && stillLooking(lookup, session); round++) {
                for (String[] provider : LOCATION_PROVIDERS) {
                    if (!stillLooking(lookup, session)) return;
                    try {
                        ExitLocation candidate = ExitLocation.parse(socksHttpGet(socksAddress, provider[0], provider[1]));
                        if (candidate.usable()) { found = candidate; break; }
                        sendLog("Location provider " + provider[0] + " answered without a country");
                    } catch (Throwable error) {
                        sendLog("Location provider " + provider[0] + " failed: " + safeMessage(error));
                    }
                }
                if (found.usable() || round == 2) break;
                // Every provider is down at once, which usually means the tunnel is still settling.
                try { Thread.sleep(4_000L * (round + 1)); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return; }
            }
            if (!stillLooking(lookup, session)) return;
            currentEndpoint = found.usable() ? found.place() : getString(R.string.connection_location_unavailable);
            currentLocationDetail = found.usable() ? found.detail() : "";
            sendStatus(currentState, currentMessage);
            publishAvailableRegions();
        });
    }

    /** Publishes the exit countries the Global engine listed, so the picker can offer them. */
    private void publishAvailableRegions() {
        GlobalCore core = globalCore;
        if (core == null) return;
        List<String> reported = core.availableRegions();
        if (reported == null || reported.isEmpty()) return;
        String encoded = GlobalRegions.encode(reported);
        if (encoded.equals(currentAvailableRegions)) return;
        currentAvailableRegions = encoded;
        sendStatus(currentState, currentMessage);
    }

    /** True while this lookup is still the current one and the tunnel it belongs to is still up. */
    private boolean stillLooking(long lookup, long session) {
        return lookup == locationLookupSequence.get() && !stopping
                && generation.get() == session && "connected".equals(currentState);
    }

    /** Re-runs the lookup for the live tunnel. Used by the refresh control on the location card. */
    private void refreshLocation() {
        Intent request = activeRequest;
        if (request == null || !"connected".equals(currentState)) return;
        scheduleLocationLookup(request, generation.get());
    }

    private String socksHttpGet(String socksAddress, String host, String path) throws Exception {
        try (Socket tunnel = openSocksTunnel(socksAddress, host, 443, 10_000)) {
            try (SSLSocket ssl = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault()).createSocket(tunnel, host, 443, true)) {
            ssl.setSoTimeout(10_000);
            ssl.startHandshake();
            OutputStream output = ssl.getOutputStream();
            output.write(("GET " + path + " HTTP/1.1\r\nHost: " + host
                    + "\r\nUser-Agent: Panther/" + BuildConfig.VERSION_NAME + " (Android)"
                    + "\r\nAccept: application/json, text/plain, */*"
                    + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            output.flush();
            BufferedReader reader = new BufferedReader(new InputStreamReader(ssl.getInputStream(), StandardCharsets.UTF_8));
            String line;
            int status = 0;
            StringBuilder body = new StringBuilder();
            boolean headers = true;
            while ((line = reader.readLine()) != null) {
                if (headers) {
                    if (line.startsWith("HTTP/")) status = Integer.parseInt(line.split(" ", 3)[1]);
                    if (line.isEmpty()) headers = false;
                } else body.append(line).append('\n');
            }
            if (status < 200 || status >= 300) throw new IllegalStateException("Location service returned HTTP " + status);
            return body.toString();
            }
        }
    }



    private static byte[] readExact(InputStream input, int length) throws Exception {
        byte[] value = new byte[length];
        int offset = 0;
        while (offset < length) {
            int read = input.read(value, offset, length - offset);
            if (read < 0) throw new IllegalStateException("SOCKS5 response ended early");
            offset += read;
        }
        return value;
    }

    private static byte[] parseIpv4Address(String host) {
        String[] parts = host.split("\\.", -1);
        if (parts.length != 4) return null;
        byte[] address = new byte[4];
        for (int i = 0; i < parts.length; i++) {
            try {
                int value = Integer.parseInt(parts[i]);
                if (value < 0 || value > 255) return null;
                address[i] = (byte) value;
            } catch (NumberFormatException error) {
                return null;
            }
        }
        return address;
    }

    private static long elapsedMillis(long startedNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
    }

    private static String protocolLabel(String protocol) {
        if ("wg".equals(protocol)) return "WireGuard";
        if ("gool".equals(protocol)) return "gool / WARP-in-WARP";
        return "MASQUE";
    }

    private File writeTunConfig(Intent request, boolean mappedDns) throws Exception {
        HostPort socks = HostPort.parse(value(request, "socks", "127.0.0.1:1819"));
        File config = new File(getCacheDir(), "hev.yml");
        try (FileWriter writer = new FileWriter(config, false)) {
            writer.write(TunnelConfig.render(socks.host, socks.port,
                    request.getIntExtra("mtu", 1500), mappedDns));
        }
        return config;
    }

    private void applySplitApps(Builder builder, Intent request) {
        String mode = value(request, "routing", "bypass-local");
        String apps = value(request, "splitApps", "");
        boolean includeOnly = "split-include".equals(mode);
        if (!includeOnly) {
            try { builder.addDisallowedApplication(getPackageName()); }
            catch (PackageManager.NameNotFoundException ignored) { }
        }
        if (apps.trim().isEmpty()) return;
        int valid = 0;
        for (String packageName : apps.split("[\\r\\n,]+")) {
            packageName = packageName.trim();
            if (packageName.isEmpty() || packageName.equals(getPackageName())) continue;
            try {
                if (includeOnly) builder.addAllowedApplication(packageName);
                else if ("split-exclude".equals(mode)) builder.addDisallowedApplication(packageName);
                valid++;
            } catch (PackageManager.NameNotFoundException error) {
                sendLog("Unknown Android package: " + packageName);
            }
        }
        if (includeOnly && valid == 0) {
            throw new IllegalArgumentException("Include selected apps requires at least one valid Android package name");
        }
    }

    private void addPublicRoutes(Builder builder) {
        List<Ipv4Range> excluded = new ArrayList<>();
        excluded.add(Ipv4Range.cidr("0.0.0.0", 8));
        excluded.add(Ipv4Range.cidr("10.0.0.0", 8));
        excluded.add(Ipv4Range.cidr("100.64.0.0", 10));
        excluded.add(Ipv4Range.cidr("127.0.0.0", 8));
        excluded.add(Ipv4Range.cidr("169.254.0.0", 16));
        excluded.add(Ipv4Range.cidr("172.16.0.0", 12));
        excluded.add(Ipv4Range.cidr("192.0.0.0", 24));
        excluded.add(Ipv4Range.cidr("192.168.0.0", 16));
        excluded.add(Ipv4Range.cidr("198.18.0.0", 15));
        excluded.add(Ipv4Range.cidr("224.0.0.0", 3));
        Collections.sort(excluded);
        long cursor = 0;
        for (Ipv4Range range : excluded) {
            if (cursor < range.start) addRangeAsRoutes(builder, cursor, range.start - 1);
            cursor = Math.max(cursor, range.end + 1);
        }
        if (cursor <= 0xffffffffL) addRangeAsRoutes(builder, cursor, 0xffffffffL);
        builder.addRoute("2000::", 3);
    }

    private void addRangeAsRoutes(Builder builder, long start, long end) {
        while (start <= end) {
            long alignment = start == 0 ? (1L << 32) : Long.lowestOneBit(start);
            long remaining = end - start + 1;
            long block = alignment;
            while (block > remaining) block >>>= 1;
            int prefix = 32 - Long.numberOfTrailingZeros(block);
            builder.addRoute(Ipv4Range.format(start), prefix);
            start += block;
        }
    }

    private void publishStats() {
        if (!active) return;
        long tx = 0;
        long rx = 0;
        if (bridgeStarted) {
            try {
                long[] stats = TProxyService.TProxyGetStats();
                if (stats != null && stats.length >= 4) { tx = stats[1]; rx = stats[3]; }
            } catch (Throwable error) {
                Log.w(TAG, "Could not read HEV stats", error);
            }
        }
        maybeCheckTunnelHealth();
        Intent intent = new Intent(ACTION_STATS).setPackage(getPackageName());
        intent.putExtra("tx", tx).putExtra("rx", rx).putExtra("ping", lastPing).putExtra("connectedAt", connectedAt);
        sendBroadcast(intent, INTERNAL_PERMISSION);
    }

    private void maybeCheckTunnelHealth() {
        if (!"connected".equals(currentState) || networkUnavailable || System.currentTimeMillis() - lastHealthCheckAt < 15_000L || !healthCheckRunning.compareAndSet(false, true)) return;
        lastHealthCheckAt = System.currentTimeMillis();
        Intent request = activeRequest;
        worker.execute(() -> {
            try {
                if (request == null || stopping || !active) return;
                lastPing = socksConnectMillis(value(request, "socks", "127.0.0.1:1819"), "1.1.1.1", 443, 4_000);
                consecutiveHealthFailures = 0;
            } catch (Throwable error) {
                lastPing = -1;
                if (++consecutiveHealthFailures >= 3) {
                    consecutiveHealthFailures = 0;
                    requestCoreRecovery(getString(R.string.service_tunnel_unresponsive));
                }
            } finally {
                healthCheckRunning.set(false);
            }
        });
    }

    private void requestCoreRecovery(String message) {
        Intent request = activeRequest;
        if (!active || stopping || request == null || !request.getBooleanExtra("quickReconnect", true)) return;
        updateState("reconnecting", message);
        updateNotification(message);
        if (!connectionEstablished || !recoveryRestartPending.compareAndSet(false, true)) return;
        worker.execute(() -> {
            synchronized (runtimeLock) {
                Process process = aetherProcess;
                if (process != null && process.isAlive()) process.destroy();
            }
        });
    }

    private void stopConnection(boolean userInitiated) {
        stopping = true;
        stopRuntime();
        active = false;
        connectedAt = 0;
        currentEndpoint = "";
        currentLocationDetail = "";
        currentRegion = "";
        GlobalCore core = globalCore;
        globalCore = null;
        if (core != null) core.stop();
        stopStealthOnly();
        stealthPool = null;
        degradedNotice = null;
        locationLookupSequence.incrementAndGet();
        activeRequest = null;
        connectionEstablished = false;
        recoveryRestartPending.set(false);
        lastPing = -1;
        consecutiveHealthFailures = 0;
        if (userInitiated) {
            updateState("disconnected", getString(R.string.service_disconnected));
        }
        if (userInitiated) {
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
        }
    }

    private void stopRuntime() {
        synchronized (runtimeLock) {
            if (bridgeStarted) {
                try { TProxyService.TProxyStopService(); }
                catch (Throwable error) { Log.w(TAG, "Could not stop HEV", error); }
                bridgeStarted = false;
            }
            try { if (vpnInterface != null) vpnInterface.close(); }
            catch (Exception ignored) { }
            vpnInterface = null;
            stopAetherOnly();
            // The engine runs as a child process holding a loopback port. Leaving it alive would
            // stop the next connection from binding that port, so it dies with the runtime.
            stopStealthOnly();
        }
    }

    private void stopAetherOnly() {
        Process process = aetherProcess;
        aetherProcess = null;
        if (process != null) {
            process.destroy();
            try {
                if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly();
            } catch (InterruptedException error) { Thread.currentThread().interrupt(); }
        }
    }

    private String aetherExitMessage(String fallback) {
        Process process = aetherProcess;
        if (process != null && !process.isAlive()) {
            try { return fallback + " (exit " + process.exitValue() + ")"; }
            catch (IllegalThreadStateException ignored) { }
        }
        return fallback;
    }

    private void updateState(String state, String message) {
        currentState = state;
        currentMessage = message == null ? "" : message;
        stateStore.edit().putString("state", currentState).putString("message", currentMessage).putString("endpoint", currentEndpoint).putString("locationDetail", currentLocationDetail).apply();
        sendStatus(currentState, currentMessage);
        AethonTileService.requestUpdate(this);
    }

    private void sendStatus(String state, String message) {
        Intent intent = new Intent(ACTION_STATUS).setPackage(getPackageName());
        intent.putExtra("state", state).putExtra("message", message).putExtra("endpoint", currentEndpoint)
                .putExtra("locationDetail", currentLocationDetail)
                .putExtra("region", currentRegion)
                .putExtra("availableRegions", currentAvailableRegions)
                // The one case where a connected tunnel is not the engine the user armed. Without
                // this the screen looks like an ordinary success and the explanation is thrown away.
                .putExtra("degraded", degradedToCarrier);
        sendBroadcast(intent, INTERNAL_PERMISSION);
    }

    private void sendLog(String line) {
        if (line == null || line.trim().isEmpty()) return;
        Log.i(TAG, line);
        synchronized (logLock) {
            if (logHistory.length() > 0) logHistory.append('\n');
            logHistory.append(line);
            trimLog(logHistory, 24_000);
            if (pendingLogs.length() > 0) pendingLogs.append('\n');
            pendingLogs.append(line);
        }
    }

    private void flushLogs() {
        String batch;
        String history;
        synchronized (logLock) {
            if (pendingLogs.length() == 0) return;
            batch = pendingLogs.toString();
            pendingLogs.setLength(0);
            history = logHistory.toString();
        }
        long now = System.currentTimeMillis();
        if (now - lastLogPersistedAt >= 500) {
            stateStore.edit().putString("logs", history).apply();
            lastLogPersistedAt = now;
        }
        Intent intent = new Intent(ACTION_LOG).setPackage(getPackageName()).putExtra("lines", batch);
        sendBroadcast(intent, INTERNAL_PERMISSION);
    }

    private static void trimLog(StringBuilder value, int maxLength) {
        if (value.length() <= maxLength) return;
        int cut = value.length() - maxLength;
        int newline = value.indexOf("\n", cut);
        value.delete(0, newline >= 0 ? newline + 1 : cut);
    }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID, getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW);
        channel.setDescription(getString(R.string.notification_channel_summary));
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    private Notification notification(String text, boolean connected) {
        Intent open = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent content = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Intent stop = new Intent(this, AetherVpnService.class).setAction(ACTION_STOP);
        PendingIntent disconnect = PendingIntent.getService(this, 1, stop, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_panther)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(text)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(content)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setPriority(NotificationCompat.PRIORITY_LOW);
        if (connected || active) builder.addAction(0, getString(R.string.disconnect), disconnect);
        return builder.build();
    }

    private void startForegroundCompat(Notification notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void updateNotification(String text) {
        getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, notification(text, true));
    }

    @Override public void onRevoke() {
        stateStore.edit().putBoolean("desiredConnected", false).apply();
        generation.incrementAndGet();
        stopping = true;
        worker.execute(() -> stopConnection(true));
        super.onRevoke();
    }

    @Override public void onDestroy() {
        stopping = true;
        stopRuntime();
        flushLogs();
        synchronized (logLock) {
            stateStore.edit().putString("logs", logHistory.toString()).apply();
        }
        if (VpnConnectionController.canDisconnect(currentState)) {
            currentState = "disconnected";
            currentMessage = getString(R.string.service_disconnected);
            stateStore.edit().putString("state", currentState).putString("message", currentMessage).putString("endpoint", "").putString("locationDetail", "").apply();
            AethonTileService.requestUpdate(this);
        }
        telemetry.shutdownNow();
        worker.shutdownNow();
        try { connectivityManager.unregisterNetworkCallback(networkCallback); }
        catch (RuntimeException error) { Log.w(TAG, "Network callback was already unregistered", error); }
        super.onDestroy();
    }

    private static String value(Intent intent, String key, String fallback) {
        String result = intent.getStringExtra(key);
        return result == null || result.trim().isEmpty() ? fallback : result.trim();
    }

    private static String safeMessage(Throwable error) {
        String message = error.getMessage();
        return message == null || message.trim().isEmpty() ? error.getClass().getSimpleName() : message;
    }

    private static String reliableEndpoint(Intent request) {
        String peer = request.getStringExtra("peer");
        return peer == null ? "" : peer.trim();
    }

    private static final class SmartResult {
        final String protocol;
        final boolean connected;
        final long handshakeMs;
        final long latencyMs;
        final long dnsMs;
        final int stableProbes;
        final int attempts;
        final double score;

        private SmartResult(String protocol, boolean connected, long handshakeMs, long latencyMs, long dnsMs, int stableProbes, int attempts, double score) {
            this.protocol = protocol;
            this.connected = connected;
            this.handshakeMs = handshakeMs;
            this.latencyMs = latencyMs;
            this.dnsMs = dnsMs;
            this.stableProbes = stableProbes;
            this.attempts = attempts;
            this.score = score;
        }

        static SmartResult failed(String protocol, long handshakeMs) {
            return new SmartResult(protocol, false, handshakeMs, -1, -1, 0, 5, 0);
        }

        static SmartResult success(String protocol, long handshakeMs, long latencyMs, long dnsMs, int stableProbes, int attempts) {
            double handshakeScore = 30.0 * clamp(1.0 - handshakeMs / (double) SMART_PROTOCOL_TIMEOUT_MS);
            double latencyScore = 15.0 * clamp(1.0 - latencyMs / 2_000.0);
            double stabilityScore = 5.0 * stableProbes / Math.max(1, attempts);
            return new SmartResult(protocol, true, handshakeMs, latencyMs, dnsMs, stableProbes, attempts, 50.0 + handshakeScore + latencyScore + stabilityScore);
        }

        String summary() {
            double loss = attempts == 0 ? 100 : 100.0 * (attempts - stableProbes) / attempts;
            return String.format(Locale.US, "Smart Connect %s: success=%s handshake=%dms latency=%dms dns=%dms loss=%.0f%% stability=%d/%d score=%.1f", protocolLabel(protocol), connected, handshakeMs, latencyMs, dnsMs, loss, stableProbes, attempts, score);
        }

        private static double clamp(double value) { return Math.max(0, Math.min(1, value)); }
    }

    private static final class HostPort {
        final String host;
        final int port;
        private HostPort(String host, int port) { this.host = host; this.port = port; }
        static HostPort parse(String value) {
            if (value == null) throw new IllegalArgumentException("SOCKS5 address is missing");
            String input = value.trim();
            String host;
            String portValue;
            if (input.startsWith("[")) {
                int end = input.indexOf(']');
                if (end < 0 || end + 2 > input.length() || input.charAt(end + 1) != ':') throw new IllegalArgumentException("Invalid SOCKS5 address");
                host = input.substring(1, end);
                portValue = input.substring(end + 2);
            } else {
                int split = input.lastIndexOf(':');
                if (split <= 0) throw new IllegalArgumentException("SOCKS5 address must use host:port");
                host = input.substring(0, split);
                portValue = input.substring(split + 1);
            }
            int port;
            try { port = Integer.parseInt(portValue); }
            catch (NumberFormatException error) { throw new IllegalArgumentException("Invalid SOCKS5 port"); }
            if (host.trim().isEmpty() || port < 1 || port > 65535) throw new IllegalArgumentException("Invalid SOCKS5 address");
            return new HostPort(host.trim(), port);
        }
    }

    private static final class Ipv4Range implements Comparable<Ipv4Range> {
        final long start;
        final long end;
        private Ipv4Range(long start, long end) { this.start = start; this.end = end; }
        static Ipv4Range cidr(String address, int prefix) {
            long value = parse(address);
            long size = 1L << (32 - prefix);
            return new Ipv4Range(value, value + size - 1);
        }
        static long parse(String address) {
            String[] parts = address.split("\\.");
            if (parts.length != 4) throw new IllegalArgumentException("Invalid IPv4 address");
            long value = 0;
            for (String part : parts) value = (value << 8) | Integer.parseInt(part);
            return value;
        }
        static String format(long value) {
            return ((value >>> 24) & 255) + "." + ((value >>> 16) & 255) + "." + ((value >>> 8) & 255) + "." + (value & 255);
        }
        @Override public int compareTo(Ipv4Range other) { return Long.compare(start, other.start); }
    }
}
