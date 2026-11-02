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
    private static final long POLL_MS = 30000;

    private final WifiManager manager;
    private final StateStore store;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable poll = new Runnable() {
        @Override public void run() {
            publish();
            handler.postDelayed(this, POLL_MS);
        }
    };

    public WifiReader(Context context, StateStore store) {
        this.store = store;
        manager = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
    }

    public void start() {
        handler.post(poll);
    }

    public void stop() {
        handler.removeCallbacks(poll);
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
