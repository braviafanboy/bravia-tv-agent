package com.braviafanboy.braviaagent.readers;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;

import com.braviafanboy.braviaagent.StateStore;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Bonded Bluetooth devices, the remote first (needs BLUETOOTH_CONNECT).
 * Connection state and battery level come from system broadcasts, so both
 * are null until the first event after the agent starts; the remote
 * reconnects on every wake, which fills them in quickly.
 */
public final class BluetoothReader extends BroadcastReceiver {
    private static final String ACTION_BATTERY = "android.bluetooth.device.action.BATTERY_LEVEL_CHANGED";
    private static final String EXTRA_BATTERY = "android.bluetooth.device.extra.BATTERY_LEVEL";
    private static final long RECHECK_MS = 60000;

    private final Context context;
    private final StateStore store;
    private final BluetoothAdapter adapter;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Map<String, Integer> battery = new HashMap<>();
    private final Map<String, Boolean> connected = new HashMap<>();
    private boolean registered;
    private final Runnable recheck = new Runnable() {
        @Override public void run() {
            recheckPermission();
            handler.postDelayed(this, RECHECK_MS);
        }
    };

    public BluetoothReader(Context context, StateStore store) {
        this.context = context.getApplicationContext();
        this.store = store;
        BluetoothManager m = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        adapter = m == null ? null : m.getAdapter();
    }

    public boolean isGranted() {
        return context.checkSelfPermission("android.permission.BLUETOOTH_CONNECT") == PackageManager.PERMISSION_GRANTED;
    }

    public void start() {
        handler.post(recheck);
    }

    public void stop() {
        handler.removeCallbacks(recheck);
        if (registered) {
            try { context.unregisterReceiver(this); } catch (Exception ignored) { }
            registered = false;
        }
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
        }
        publish();
    }

    @Override
    public void onReceive(Context c, Intent intent) {
        String action = intent.getAction();
        BluetoothDevice d = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice.class);
        if (d != null && action != null) {
            switch (action) {
                case ACTION_BATTERY: {
                    int level = intent.getIntExtra(EXTRA_BATTERY, -1);
                    if (level >= 0) battery.put(d.getAddress(), level); else battery.remove(d.getAddress());
                    connected.put(d.getAddress(), true);
                    break;
                }
                case BluetoothDevice.ACTION_ACL_CONNECTED: connected.put(d.getAddress(), true); break;
                case BluetoothDevice.ACTION_ACL_DISCONNECTED: connected.put(d.getAddress(), false); break;
                default: break;
            }
        }
        publish();
    }

    private void publish() {
        try {
            if (!adapter.isEnabled()) {
                JSONObject off = new JSONObject();
                off.put("remote", JSONObject.NULL);
                off.put("devices", new JSONArray());
                store.update("bluetooth", off);
                return;
            }
            Set<BluetoothDevice> bonded = adapter.getBondedDevices();
            JSONObject remote = null;
            JSONArray devices = new JSONArray();
            for (BluetoothDevice d : bonded) {
                String type = type(d);
                JSONObject o = new JSONObject();
                String name = d.getName();
                o.put("name", name == null ? JSONObject.NULL : name);
                o.put("type", type);
                Boolean con = connected.get(d.getAddress());
                o.put("connected", con == null ? JSONObject.NULL : con);
                Integer bat = battery.get(d.getAddress());
                o.put("battery_pct", bat == null ? JSONObject.NULL : bat);
                if (remote == null && "hid".equals(type)) remote = o; else devices.put(o);
            }
            JSONObject bt = new JSONObject();
            bt.put("remote", remote == null ? JSONObject.NULL : remote);
            bt.put("devices", devices);
            store.update("bluetooth", bt);
        } catch (SecurityException e) {
            store.update("bluetooth", null);
        } catch (Exception ignored) {
        }
    }

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
