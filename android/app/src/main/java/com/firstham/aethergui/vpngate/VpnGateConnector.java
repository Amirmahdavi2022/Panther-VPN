package com.firstham.aethergui.vpngate;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

import java.io.StringReader;
import java.util.HashSet;
import java.util.Locale;

import de.blinkt.openvpn.VpnProfile;
import de.blinkt.openvpn.core.VpnStatus;
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
 * Relays are volunteer-run and die without notice, so a single start is never the whole plan.
 * {@link RelayEngine} owns the sequence; this class only knows how to launch one of them.
 */
public final class VpnGateConnector {
    private static final String TAG = "VpnGateConnector";

    /** The resolvers used when the user has DNS leak protection on. Same pair the others use. */
    public static final String[] DNS = {"1.1.1.1", "1.0.0.1"};

    private VpnGateConnector() {
    }

    /**
     * Starts one specific relay with the settings the user chose.
     *
     * @return true when the engine accepted the profile and was asked to start.
     */
    public static boolean connect(Context context, VpnGateServer server, RelayOptions options) {
        String config = VpnGateProfile.build(server, options == null ? DNS : options.dns);
        if (config == null) return false;

        try {
            ConfigParser parser = new ConfigParser();
            parser.parseConfig(new StringReader(config));
            VpnProfile profile = parser.convertProfile();
            if (profile == null) return false;

            // Shown in the system VPN dialog and the engine's own notification.
            profile.mName = "Panther - " + server.countryName;

            // A handful of VPN Gate entries ship auth-user-pass. The project accepts any
            // credentials on those, but an empty pair makes the engine stop and wait for a
            // prompt that Panther never shows - which looks exactly like a dead button.
            if (profile.isUserPWAuth()) {
                if (profile.mUsername == null || profile.mUsername.isEmpty()) profile.mUsername = "vpn";
                if (profile.mPassword == null || profile.mPassword.isEmpty()) profile.mPassword = "vpn";
            }

            // Volunteer relays negotiate old ciphers. A freshly parsed profile carries no
            // data-ciphers list at all, and OpenVPN 2.6 then refuses anything but AES-GCM - which
            // is most of VPN Gate.
            if (profile.mDataCiphers == null || profile.mDataCiphers.isEmpty()) {
                String fallback = "AES-256-GCM:AES-128-GCM:CHACHA20-POLY1305:AES-256-CBC:AES-128-CBC";
                String cipher = profile.mCipher == null ? "" : profile.mCipher.toUpperCase(Locale.US);
                if (!cipher.isEmpty() && !fallback.contains(cipher)) fallback += ":" + cipher;
                profile.mDataCiphers = fallback;
                // Blowfish only exists behind OpenSSL's legacy provider.
                if (fallback.contains("BF-CBC")) profile.mUseLegacyProvider = true;
            }

            apply(profile, options);

            ProfileManager.setTemporaryProfile(context, profile);
            VPNLaunchHelper.startOpenVpn(profile, context, "Panther", true);
            return true;
        } catch (Exception error) {
            Log.w(TAG, "Could not start relay " + server.key(), error);
            // The engine's own log is what the status line reads back, so put the reason there
            // rather than only in logcat, which nobody on a phone can see.
            VpnStatus.logError("Panther: relay " + server.countryCode + " rejected - "
                    + error.getClass().getSimpleName()
                    + (error.getMessage() == null ? "" : ": " + error.getMessage()));
            return false;
        }
    }

    /**
     * Copies the settings screen onto the profile.
     *
     * <p>Everything here was previously left at the engine's default, which meant the settings
     * page said one thing and a relay connection did another. The values themselves are decided in
     * {@link RelayOptions}; this only writes them.
     */
    private static void apply(VpnProfile profile, RelayOptions options) {
        if (options == null) return;

        profile.mAllowedAppsVpn = new HashSet<>(options.apps);
        profile.mAllowedAppsVpnAreDisallowed = options.appsAreExcluded;
        // The other engines route local networks around the tunnel by default and never offer to
        // let apps opt out, so neither does this one.
        profile.mAllowAppVpnBypass = false;

        // The engine only writes a tun-mtu line when this differs from its own default, so an
        // untouched setting stays untouched.
        profile.mTunMtu = options.mtu;

        // persist-tun is what keeps the interface up while the engine is down, which blackholes
        // traffic instead of letting it out around the tunnel. The profile builder adds it
        // unconditionally, so the kill switch being off has to actively take it away.
        profile.mPersistTun = options.killSwitch;

        if (options.dns.length == 0) {
            // DNS leak protection off means the server's own resolvers, exactly as on the others.
            profile.mOverrideDNS = false;
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
