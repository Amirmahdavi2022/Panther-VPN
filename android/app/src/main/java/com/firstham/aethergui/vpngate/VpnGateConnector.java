package com.firstham.aethergui.vpngate;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

import java.io.StringReader;
import java.util.List;

import de.blinkt.openvpn.VpnProfile;
import de.blinkt.openvpn.core.ConfigParser;
import de.blinkt.openvpn.core.OpenVPNService;
import de.blinkt.openvpn.core.ProfileManager;
import de.blinkt.openvpn.core.VPNLaunchHelper;

/**
 * Connects to a VPN Gate relay using the OpenVPN engine.
 *
 * This runs entirely separately from {@code AetherVpnService}: OpenVPN brings its own
 * VpnService, and two of them cannot hold the tunnel at once. Callers must stop the Aether side
 * before starting here, and vice versa - {@link #stop(Context)} exists for that.
 *
 * Relays are volunteer-run and die without notice, so {@link #connectBest} walks the ranked list
 * rather than trusting the top entry.
 */
public final class VpnGateConnector {
    private static final String TAG = "VpnGateConnector";
    private static final String[] DNS = {"1.1.1.1", "1.0.0.1"};

    /** How many relays to try before giving up on a country. */
    public static final int MAX_ATTEMPTS = 3;

    private VpnGateConnector() {
    }

    /**
     * Starts the best usable relay from {@code candidates}.
     *
     * A relay whose profile will not parse is skipped rather than surfaced - the user asked for a
     * country, not for a particular volunteer's machine.
     *
     * @return the relay that was launched, or null when none of the candidates produced a usable
     *         profile. A non-null return means the engine was asked to start, not that the tunnel
     *         came up; watch VpnStatus for that.
     */
    public static VpnGateServer connectBest(Context context, List<VpnGateServer> candidates) {
        if (context == null || candidates == null || candidates.isEmpty()) return null;

        int attempts = 0;
        for (VpnGateServer server : candidates) {
            if (attempts >= MAX_ATTEMPTS) break;
            attempts++;
            if (connect(context, server)) return server;
            Log.w(TAG, "Relay " + server.key() + " produced no usable profile; trying the next one");
        }
        return null;
    }

    /**
     * Starts one specific relay.
     *
     * @return true when the engine accepted the profile and was asked to start.
     */
    public static boolean connect(Context context, VpnGateServer server) {
        String config = VpnGateProfile.build(server, DNS);
        if (config == null) return false;

        try {
            ConfigParser parser = new ConfigParser();
            parser.parseConfig(new StringReader(config));
            VpnProfile profile = parser.convertProfile();
            if (profile == null) return false;

            // Shown in the system VPN dialog and the engine's own notification.
            profile.mName = "Panther - " + server.countryName;

            ProfileManager.setTemporaryProfile(context, profile);
            VPNLaunchHelper.startOpenVpn(profile, context, "Panther", true);
            return true;
        } catch (Exception error) {
            Log.w(TAG, "Could not start relay " + server.key(), error);
            return false;
        }
    }

    /** Tears down the OpenVPN tunnel if one is up. Safe to call when nothing is running. */
    public static void stop(Context context) {
        if (context == null) return;
        try {
            Intent intent = new Intent(context, OpenVPNService.class);
            intent.setAction(OpenVPNService.DISCONNECT_VPN);
            context.startService(intent);
            ProfileManager.setConntectedVpnProfileDisconnected(context);
        } catch (Exception error) {
            Log.w(TAG, "Could not stop the OpenVPN tunnel", error);
        }
    }
}
