package com.firstham.aethergui.vpngate;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.util.Locale;

import de.blinkt.openvpn.core.ConnectionStatus;
import de.blinkt.openvpn.core.VpnStatus;

/**
 * Makes the OpenVPN engine visible to the UI.
 *
 * The Aether core reports itself by broadcasting from its own service, so the home screen was only
 * ever listening to that one engine. In relay mode nothing broadcast anything: the tunnel could
 * start, fail, or never launch at all and the orb kept saying CONNECT, which is what made the
 * button look dead. This adapter subscribes to the engine's own status bus and translates it into
 * exactly the state strings the home screen already understands.
 *
 * Registration replays the last known state immediately, so re-entering the app while a relay is up
 * shows connected rather than a stale idle orb.
 */
public final class RelayStatus implements VpnStatus.StateListener, VpnStatus.ByteCountListener {

    public interface Listener {
        /** state is one of: connected, starting, reconnecting, error, disconnected. */
        void relayState(String state, String message);

        void relayTraffic(long tx, long rx);
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private final Context context;
    private final Listener listener;
    private boolean registered;

    public RelayStatus(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
    }

    public void register() {
        if (registered) return;
        registered = true;
        VpnStatus.addStateListener(this);
        VpnStatus.addByteCountListener(this);
    }

    public void unregister() {
        if (!registered) return;
        registered = false;
        VpnStatus.removeStateListener(this);
        VpnStatus.removeByteCountListener(this);
    }

    /** True when the engine currently holds, or is bringing up, a tunnel. */
    public static boolean live() {
        return VpnStatus.isVPNActive();
    }

    @Override
    public void updateState(String state, String logmessage, int localizedResId,
                            ConnectionStatus level, android.content.Intent intent) {
        final String mapped = map(level);
        final String message = describe(level, logmessage);
        MAIN.post(() -> listener.relayState(mapped, message));
    }

    @Override
    public void setConnectedVPN(String uuid) {
        // Not needed: the profile identity is already known to whoever started the relay.
    }

    @Override
    public void updateByteCount(long in, long out, long diffIn, long diffOut) {
        MAIN.post(() -> listener.relayTraffic(out, in));
    }

    private static String map(ConnectionStatus level) {
        if (level == null) return "disconnected";
        switch (level) {
            case LEVEL_CONNECTED:
                return "connected";
            case LEVEL_START:
            case LEVEL_CONNECTING_SERVER_REPLIED:
            case LEVEL_CONNECTING_NO_SERVER_REPLY_YET:
            case LEVEL_WAITING_FOR_USER_INPUT:
                return "starting";
            case LEVEL_VPNPAUSED:
            case LEVEL_NONETWORK:
                return "reconnecting";
            case LEVEL_AUTH_FAILED:
                return "error";
            default:
                return "disconnected";
        }
    }

    /**
     * A message worth putting on screen.
     *
     * A relay that refuses the handshake is the single most common failure here, and the engine's
     * own log line says why. Surfacing it beats a generic "error" the user cannot act on.
     */
    private String describe(ConnectionStatus level, String fallback) {
        if (level == ConnectionStatus.LEVEL_CONNECTED) return null;
        try {
            String detail = lastFailureLine();
            if (detail != null) return detail;
            String clean = VpnStatus.getLastCleanLogMessage(context);
            if (clean != null && !clean.trim().isEmpty()) return clean.trim();
        } catch (Throwable ignored) {
            // Same rule as above: never let a status message kill the activity.
        }
        return fallback;
    }

    /** The most recent engine line that reads like a cause rather than progress noise. */
    private String lastFailureLine() {
        try {
            String line = VpnStatus.getLastCleanLogMessage(context);
            if (line == null) return null;
            String lower = line.toLowerCase(Locale.US);
            if (lower.contains("auth_failed") || lower.contains("tls error")
                    || lower.contains("tls handshake failed") || lower.contains("connection refused")
                    || lower.contains("cannot resolve") || lower.contains("no route to host")
                    || lower.contains("network is unreachable") || lower.contains("cipher")
                    || lower.contains("options error") || lower.contains("fatal")) {
                return line.length() > 160 ? line.substring(0, 160) + "\u2026" : line;
            }
        } catch (Throwable ignored) {
            // A diagnostic must never be the thing that breaks the screen - and it must catch
            // Throwable, not Exception: a class the engine's own shrinker renamed away arrives as
            // NoClassDefFoundError, which an Exception catch lets straight through.
        }
        return null;
    }
}
