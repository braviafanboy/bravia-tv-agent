package com.braviafanboy.braviaagent.readers;

import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothHeadset;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.braviafanboy.braviaagent.StateStore;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Bonded Bluetooth devices, the remote first (needs BLUETOOTH_CONNECT).
 *
 * Connection state comes from public profile APIs: the system-wide GATT
 * connection list for LE devices (the remote) and the A2DP / Headset proxies
 * for classic audio devices. The remote's battery comes first from the
 * stack's own BATTERY_LEVEL_CHANGED broadcast (free); as a fallback the
 * standard GATT Battery Service (0x180F / 0x2A19) is read at most every six
 * hours with a short-lived client over a link the stack already holds, then
 * closed again, so the remote's own sleep behaviour is never affected.
 */
public final class BluetoothReader extends BroadcastReceiver {
    private static final String TAG = "BraviaAgent";
    private static final String ACTION_BATTERY = "android.bluetooth.device.action.BATTERY_LEVEL_CHANGED";
    private static final String EXTRA_BATTERY = "android.bluetooth.device.extra.BATTERY_LEVEL";
    private static final UUID BATTERY_SERVICE = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb");
    private static final UUID BATTERY_LEVEL = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb");
    private static final long POLL_MS = 60000;
    // Remote batteries last months: read rarely, and only over a link the stack
    // already holds, so the agent never wakes the remote or keeps it awake.
    private static final long BATTERY_READ_MS = 6 * 3600000L;

    private final Context context;
    private final StateStore store;
    private final BluetoothManager manager;
    private final BluetoothAdapter adapter;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Map<String, Integer> battery = new HashMap<>();
    private BluetoothA2dp a2dp;
    private BluetoothHeadset headset;
    private BluetoothGatt gatt;
    private String gattAddress;
    private BluetoothGattCharacteristic level;
    private boolean registered;
    private long lastBatteryRead;

    private final Runnable poll = new Runnable() {
        @Override public void run() {
            recheckPermission();
            handler.postDelayed(this, POLL_MS);
        }
    };

    public BluetoothReader(Context context, StateStore store) {
        this.context = context.getApplicationContext();
        this.store = store;
        manager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        adapter = manager == null ? null : manager.getAdapter();
    }

    public boolean isGranted() {
        return context.checkSelfPermission("android.permission.BLUETOOTH_CONNECT") == PackageManager.PERMISSION_GRANTED;
    }

    public void start() {
        handler.post(poll);
    }

    public void stop() {
        handler.removeCallbacks(poll);
        if (registered) {
            try { context.unregisterReceiver(this); } catch (Exception ignored) { }
            registered = false;
        }
        closeGatt();
        try {
            if (a2dp != null) adapter.closeProfileProxy(BluetoothProfile.A2DP, a2dp);
            if (headset != null) adapter.closeProfileProxy(BluetoothProfile.HEADSET, headset);
        } catch (Exception ignored) { }
        a2dp = null;
        headset = null;
    }

    public void recheckPermission() {
        if (adapter == null || !isGranted()) {
            store.update("bluetooth", null);
            return;
        }
        if (!registered) {
            IntentFilter f = new IntentFilter();
            f.addAction(ACTION_BATTERY);
            f.addAction(BluetoothDevice.ACTION_ACL_CONNECTED);
            f.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED);
            f.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
            f.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
            context.registerReceiver(this, f);
            registered = true;
            BluetoothProfile.ServiceListener sl = new BluetoothProfile.ServiceListener() {
                @Override public void onServiceConnected(int profile, BluetoothProfile proxy) {
                    if (profile == BluetoothProfile.A2DP) a2dp = (BluetoothA2dp) proxy;
                    if (profile == BluetoothProfile.HEADSET) headset = (BluetoothHeadset) proxy;
                    publish();
                }
                @Override public void onServiceDisconnected(int profile) {
                    if (profile == BluetoothProfile.A2DP) a2dp = null;
                    if (profile == BluetoothProfile.HEADSET) headset = null;
                }
            };
            try {
                adapter.getProfileProxy(context, sl, BluetoothProfile.A2DP);
                adapter.getProfileProxy(context, sl, BluetoothProfile.HEADSET);
            } catch (Exception e) {
                Log.w(TAG, "profile proxies: " + e);
            }
        }
        publish();
    }

    @Override
    public void onReceive(Context c, Intent intent) {
        String action = intent.getAction();
        BluetoothDevice d = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice.class);
        if (d != null && ACTION_BATTERY.equals(action)) {
            int lvl = intent.getIntExtra(EXTRA_BATTERY, -1);
            if (lvl >= 0) battery.put(d.getAddress(), lvl);
        }
        // Let the stack settle before asking it who is connected.
        handler.postDelayed(this::publish, 1500);
    }

    private boolean isConnected(BluetoothDevice d) {
        try {
            int type = d.getType();
            if (type == BluetoothDevice.DEVICE_TYPE_LE || type == BluetoothDevice.DEVICE_TYPE_DUAL) {
                if (manager.getConnectionState(d, BluetoothProfile.GATT) == BluetoothProfile.STATE_CONNECTED) return true;
            }
            if (a2dp != null && a2dp.getConnectionState(d) == BluetoothProfile.STATE_CONNECTED) return true;
            if (headset != null && headset.getConnectionState(d) == BluetoothProfile.STATE_CONNECTED) return true;
        } catch (Exception ignored) {
        }
        return false;
    }

    private void publish() {
        try {
            if (!adapter.isEnabled()) {
                JSONObject off = new JSONObject();
                off.put("remote", JSONObject.NULL);
                off.put("devices", new JSONArray());
                store.update("bluetooth", off);
                closeGatt();
                return;
            }
            Set<BluetoothDevice> bonded = adapter.getBondedDevices();
            JSONObject remote = null;
            BluetoothDevice remoteDevice = null;
            JSONArray devices = new JSONArray();
            for (BluetoothDevice d : bonded) {
                String type = type(d);
                boolean con = isConnected(d);
                JSONObject o = new JSONObject();
                String name = d.getName();
                o.put("name", name == null ? JSONObject.NULL : name);
                o.put("type", type);
                o.put("connected", con);
                Integer bat = battery.get(d.getAddress());
                o.put("battery_pct", bat == null ? JSONObject.NULL : bat);
                if (remote == null && "hid".equals(type)) { remote = o; remoteDevice = d; } else devices.put(o);
            }
            JSONObject bt = new JSONObject();
            bt.put("remote", remote == null ? JSONObject.NULL : remote);
            bt.put("devices", devices);
            store.update("bluetooth", bt);
            maintainBatteryLink(remoteDevice, remote != null && remote.getBoolean("connected"));
        } catch (SecurityException e) {
            store.update("bluetooth", null);
        } catch (Exception e) {
            Log.w(TAG, "bluetooth publish: " + e);
        }
    }

    /** Read the remote's battery over an existing link, rarely, and let go at once. */
    private void maintainBatteryLink(BluetoothDevice remote, boolean connected) {
        if (remote == null || !connected || gatt != null) return;
        if (System.currentTimeMillis() - lastBatteryRead < BATTERY_READ_MS) return;
        lastBatteryRead = System.currentTimeMillis();
        try {
            gattAddress = remote.getAddress();
            gatt = remote.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE);
        } catch (Exception e) {
            Log.w(TAG, "connectGatt: " + e);
            gatt = null;
            gattAddress = null;
        }
    }

    private void closeGatt() {
        if (gatt != null) {
            try { gatt.close(); } catch (Exception ignored) { }
        }
        gatt = null;
        gattAddress = null;
        level = null;
    }

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                try { g.discoverServices(); } catch (Exception ignored) { }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                handler.post(() -> { if (g == gatt) closeGatt(); publish(); });
            }
        }
        @Override public void onServicesDiscovered(BluetoothGatt g, int status) {
            BluetoothGattService s = g.getService(BATTERY_SERVICE);
            BluetoothGattCharacteristic c = s == null ? null : s.getCharacteristic(BATTERY_LEVEL);
            if (c == null) {
                Log.i(TAG, "remote has no battery service");
                handler.post(this::done);
                return;
            }
            level = c;
            try { g.readCharacteristic(c); } catch (Exception ignored) { handler.post(this::done); }
        }
        @Override public void onCharacteristicRead(BluetoothGatt g, BluetoothGattCharacteristic c, byte[] value, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) onLevel(g, value);
            handler.post(this::done);
        }
        private void done() {
            closeGatt();
        }
        @Override public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c, byte[] value) {
            onLevel(g, value);
        }
        private void onLevel(BluetoothGatt g, byte[] value) {
            if (value == null || value.length == 0) return;
            int pct = value[0] & 0xff;
            String addr = g.getDevice().getAddress();
            handler.post(() -> {
                Integer old = battery.get(addr);
                if (old == null || old != pct) {
                    battery.put(addr, pct);
                    publish();
                }
            });
        }
    };

    private static String type(BluetoothDevice d) {
        BluetoothClass bc = d.getBluetoothClass();
        if (bc == null) return "other";
        switch (bc.getMajorDeviceClass()) {
            case BluetoothClass.Device.Major.PERIPHERAL: return "hid";
            case BluetoothClass.Device.Major.AUDIO_VIDEO: return "audio";
            case BluetoothClass.Device.Major.PHONE: return "phone";
            case BluetoothClass.Device.Major.COMPUTER: return "computer";
            default: return "other";
        }
    }
}
