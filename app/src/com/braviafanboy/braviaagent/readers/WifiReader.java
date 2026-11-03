package com.braviafanboy.braviaagent.readers;

import android.content.Context;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;

import com.braviafanboy.braviaagent.StateStore;

import org.json.JSONObject;

/** Wi-Fi signal strength and link speed (no location permission, so no SSID). */
public final class WifiReader {
    private static final long POLL_MS = 10 * 60000;

    private final WifiManager manager;
    private final android.net.ConnectivityManager connectivity;
    private final StateStore store;
    private boolean callbackRegistered;
    private boolean receiverRegistered;
    // The platform's own RSSI-changed broadcast (still delivered on Android 14).
    private final android.content.BroadcastReceiver rssiReceiver = new android.content.BroadcastReceiver() {
        @Override public void onReceive(Context c, android.content.Intent i) { publish(); }
    };
    private Context appContext;
    private final android.net.ConnectivityManager.NetworkCallback callback = new android.net.ConnectivityManager.NetworkCallback() {
        @Override public void onAvailable(android.net.Network n) { handler.post(WifiReader.this::publish); }
        @Override public void onLost(android.net.Network n) { handler.post(WifiReader.this::publish); }
    };
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable poll = new Runnable() {
        @Override public void run() {
            publish();
            handler.postDelayed(this, POLL_MS);
        }
    };

    public WifiReader(Context context, StateStore store) {
        this.store = store;
        appContext = context.getApplicationContext();
        manager = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        connectivity = (android.net.ConnectivityManager) context.getApplicationContext().getSystemService(Context.CONNECTIVITY_SERVICE);
    }

    public void start() {
        if (!receiverRegistered) {
            try {
                appContext.registerReceiver(rssiReceiver, new android.content.IntentFilter(WifiManager.RSSI_CHANGED_ACTION));
                receiverRegistered = true;
            } catch (Exception ignored) { }
        }
        if (connectivity != null && !callbackRegistered) {
            try {
                connectivity.registerDefaultNetworkCallback(callback);
                callbackRegistered = true;
            } catch (Exception ignored) { }
        }
        handler.post(poll);
    }

    public void stop() {
        handler.removeCallbacks(poll);
        if (receiverRegistered) {
            try { appContext.unregisterReceiver(rssiReceiver); } catch (Exception ignored) { }
            receiverRegistered = false;
        }
        if (connectivity != null && callbackRegistered) {
            try { connectivity.unregisterNetworkCallback(callback); } catch (Exception ignored) { }
            callbackRegistered = false;
        }
    }

    private void publish() {
        try {
            if (manager == null || !manager.isWifiEnabled()) {
                store.update("wifi", null);
                return;
            }
            @SuppressWarnings("deprecation")
            WifiInfo info = manager.getConnectionInfo();
            if (info == null || info.getBSSID() == null || info.getLinkSpeed() <= 0) {
                store.update("wifi", null);
                return;
            }
            JSONObject o = new JSONObject();
            o.put("rssi_dbm", info.getRssi());
            o.put("link_mbps", info.getLinkSpeed());
            o.put("frequency_mhz", info.getFrequency());
            store.update("wifi", o);
        } catch (Exception ignored) {
        }
    }
}
