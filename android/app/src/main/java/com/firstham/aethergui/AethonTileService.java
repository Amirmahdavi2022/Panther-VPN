package com.firstham.aethergui;

import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.VpnService;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;
import android.annotation.SuppressLint;

import com.firstham.aethergui.vpngate.EngineRouter;
import com.firstham.aethergui.vpngate.RelayEngine;

public final class AethonTileService extends TileService {
    public static final String EXTRA_CONNECT_FROM_TILE = "connect_from_tile";

    @Override public void onStartListening() {
        super.onStartListening();
        updateTile();
    }

    @Override public void onStopListening() {
        super.onStopListening();
    }

    @Override public void onClick() {
        super.onClick();
        android.content.SharedPreferences preferences = getSharedPreferences("aether", MODE_PRIVATE);
        // Relay is the OpenVPN engine and writes none of the Aether service's state, so the tile
        // has to ask that engine directly. Starting Aether here because the shared state file
        // says "disconnected" would bring up a tunnel on an engine the user did not arm.
        boolean relay = EngineRouter.usesRelay(preferences);
        if (relay ? RelayEngine.get(this).live()
                : VpnConnectionController.canDisconnect(
                        getSharedPreferences("service_state", MODE_PRIVATE)
                                .getString("state", "disconnected"))) {
            if (relay) RelayEngine.get(this).stop();
            else VpnConnectionController.disconnect(this);
            return;
        }
        // Relay builds a real tunnel whatever the connection mode says, so it needs consent even
        // in Proxy mode. Same rule as the home screen, or the tile would start an engine that then
        // stalls on a prompt nobody can see from the shade.
        if ((relay || !"manual".equals(preferences.getString("mode", "vpn")))
                && VpnService.prepare(this) != null) {
            openPermissionScreen();
            return;
        }
        if (relay) RelayEngine.get(this).start(preferences);
        else VpnConnectionController.connect(this, preferences);
    }

    private void updateTile() {
        Tile tile = getQsTile();
        if (tile == null) return;
        String state = EngineRouter.usesRelay(getSharedPreferences("aether", MODE_PRIVATE))
                ? (RelayEngine.get(this).live() ? "connected" : "disconnected")
                : getSharedPreferences("service_state", MODE_PRIVATE).getString("state", "disconnected");
        if ("connected".equals(state)) {
            tile.setState(Tile.STATE_ACTIVE);
            if (Build.VERSION.SDK_INT >= 29) tile.setSubtitle(getString(R.string.tile_connected));
        } else if ("error".equals(state) || "blocked".equals(state)) {
            tile.setState(Tile.STATE_UNAVAILABLE);
            if (Build.VERSION.SDK_INT >= 29) tile.setSubtitle(getString(R.string.tile_unavailable));
        } else {
            tile.setState(Tile.STATE_INACTIVE);
            if (Build.VERSION.SDK_INT >= 29) tile.setSubtitle(getString(R.string.tile_disconnected));
        }
        tile.setLabel(getString(R.string.tile_name));
        tile.updateTile();
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    private void openPermissionScreen() {
        Intent intent = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(EXTRA_CONNECT_FROM_TILE, true);
        if (Build.VERSION.SDK_INT >= 34) {
            PendingIntent pendingIntent = PendingIntent.getActivity(this, 7, intent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            startActivityAndCollapse(pendingIntent);
        } else {
            startActivityAndCollapse(intent);
        }
    }

    /** Asks Android to re-poll the tile. Called by whichever engine just changed state. */
    public static void requestUpdate(Context context) {
        requestListeningState(context, new ComponentName(context, AethonTileService.class));
    }
}
