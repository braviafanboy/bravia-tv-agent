package com.braviafanboy.braviaagent;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Build;
import android.util.Log;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The TV's own identity as the integration knows it: the 40-hex
 * device_unique_id the TV advertises in its _sonysmarthome._tcp mDNS name.
 * Discovered by browsing that service type from the TV itself and matching the
 * resolved address against the local interfaces; cached across restarts.
 */
public final class DeviceIdentity {
    private static final String TAG = "BraviaAgent";
    private static final String PREFS = "identity";
    private static final String KEY_ID = "device_unique_id";
    private static final Pattern ID = Pattern.compile("-([0-9a-f]{40})$", Pattern.CASE_INSENSITIVE);

    private final Context context;
    private final NsdManager nsd;
    private NsdManager.DiscoveryListener discovery;
    private volatile String deviceUniqueId;

    public DeviceIdentity(Context context) {
        this.context = context;
        nsd = (NsdManager) context.getSystemService(Context.NSD_SERVICE);
        deviceUniqueId = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_ID, null);
    }

    public String deviceUniqueId() {
        return deviceUniqueId;
    }

    public static String model() {
        return Build.MODEL;
    }

    public void start() {
        if (nsd == null) return;
        discovery = new NsdManager.DiscoveryListener() {
            @Override public void onStartDiscoveryFailed(String t, int e) { Log.w(TAG, "sonysmarthome discovery failed " + e); }
            @Override public void onStopDiscoveryFailed(String t, int e) { }
            @Override public void onDiscoveryStarted(String t) { }
            @Override public void onDiscoveryStopped(String t) { }
            @Override public void onServiceLost(NsdServiceInfo s) { }
            @Override public void onServiceFound(NsdServiceInfo s) {
                Matcher m = ID.matcher(s.getServiceName());
                if (!m.find()) return;
                final String candidate = m.group(1).toLowerCase();
                nsd.resolveService(s, new NsdManager.ResolveListener() {
                    @Override public void onResolveFailed(NsdServiceInfo i, int e) { }
                    @Override public void onServiceResolved(NsdServiceInfo i) {
                        InetAddress host = i.getHost();
                        if (host != null && localAddresses().contains(host.getHostAddress())) {
                            remember(candidate);
                        }
                    }
                });
            }
        };
        try {
            nsd.discoverServices("_sonysmarthome._tcp", NsdManager.PROTOCOL_DNS_SD, discovery);
        } catch (Exception e) {
            Log.w(TAG, "discoverServices: " + e);
        }
    }

    public void stop() {
        if (nsd != null && discovery != null) {
            try { nsd.stopServiceDiscovery(discovery); } catch (Exception ignored) { }
            discovery = null;
        }
    }

    private void remember(String id) {
        if (id.equals(deviceUniqueId)) return;
        deviceUniqueId = id;
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_ID, id).apply();
        Log.i(TAG, "device_unique_id resolved");
        stop();
    }

    static Set<String> localAddresses() {
        Set<String> out = new HashSet<>();
        try {
            List<NetworkInterface> ifs = Collections.list(NetworkInterface.getNetworkInterfaces());
            for (NetworkInterface nif : ifs) {
                for (InetAddress a : Collections.list(nif.getInetAddresses())) out.add(a.getHostAddress());
            }
        } catch (Exception ignored) {
        }
        return out;
    }
}
