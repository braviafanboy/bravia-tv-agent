package com.braviafanboy.braviaagent;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Makes the bundled remote beep, as the TV's own "Find remote" does
 * (SonySystemService {@code remotefinder}): a Bluetooth LE write to a Sony
 * service on the remote. Needs BLUETOOTH_CONNECT, the remote paired and the
 * TV on (it is the TV's Bluetooth that talks to the remote).
 *
 * <p>Steps: the bonded device named {@value #REMOTE_NAME}, connect over LE,
 * Sony's remote finder service; notifications on its IN characteristic, then
 * {@code 05 01 78 FF 01 00} (0x78 = 120, the beep's length in seconds as far
 * as Sony's 120 s wait suggests) to its OUT characteristic, resent up to
 * twice a second apart only until the remote confirms it started (it refuses
 * the command while already beeping). The remote reports on IN: started, found (a
 * button silenced it) or timed out.
 *
 * <p>Publishes {@code remote_finder}: {@code {"state", "reason", "at"}} with
 * state idle, connecting, ringing, found, unsupported, no_remote, no_permission,
 * failed.
 */
public final class RemoteFinder {
    private static final String TAG = "BraviaAgent";
    static final String REMOTE_NAME = "SONY TV VRC 001";
    private static final UUID SERVICE = UUID.fromString("d9362280-80a3-11eb-9439-0242ac130002");
    private static final UUID IN = UUID.fromString("d9362281-80a3-11eb-9439-0242ac130002");
    private static final UUID OUT = UUID.fromString("d9362282-80a3-11eb-9439-0242ac130002");
    private static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    private static final byte[] START_BUZZER = {5, 1, 120, -1, 1, 0};
    private static final long CONNECT_TIMEOUT_MS = 12_000;
    private static final long LISTEN_MS = 125_000;
    private static final int SENDS = 3;
    private static final long SEND_INTERVAL_MS = 1_000;

    private final Context context;
    private final StateStore store;
    private final Handler handler;
    private final SimpleDateFormat iso = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);

    // Touched on the handler thread only.
    private BluetoothGatt gatt;
    private int sent;
    private boolean started;
    private CountDownLatch firstWrite;
    private String state = "idle";
    private String reason;

    public RemoteFinder(Context context, StateStore store) {
        this.context = context;
        this.store = store;
        HandlerThread t = new HandlerThread("remote-finder");
        t.start();
        handler = new Handler(t.getLooper());
        iso.setTimeZone(TimeZone.getTimeZone("UTC"));
        handler.post(this::publish);
    }

    /**
     * Start a find. Blocks (off the main thread) until the first beep command
     * is written or fails, at most ~15 s, and returns the state then.
     */
    public String find() {
        CountDownLatch latch = new CountDownLatch(1);
        handler.post(() -> start(latch));
        try {
            latch.await(CONNECT_TIMEOUT_MS + 3000, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ignored) {
        }
        String[] out = new String[1];
        CountDownLatch read = new CountDownLatch(1);
        handler.post(() -> { out[0] = state; read.countDown(); });
        try {
            read.await(1, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
        }
        return out[0] == null ? "failed" : out[0];
    }

    public JSONObject status() {
        JSONObject o = new JSONObject();
        try {
            o.put("state", state);
            o.put("reason", reason == null ? JSONObject.NULL : reason);
            o.put("at", iso.format(new Date()));
        } catch (Exception ignored) {
        }
        return o;
    }

    private void start(CountDownLatch latch) {
        close();
        firstWrite = latch;
        sent = 0;
        started = false;
        reason = null;
        if (context.checkSelfPermission("android.permission.BLUETOOTH_CONNECT") != PackageManager.PERMISSION_GRANTED) {
            finish("no_permission");
            return;
        }
        BluetoothManager bm = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter adapter = bm == null ? null : bm.getAdapter();
        BluetoothDevice remote = null;
        try {
            if (adapter != null) {
                for (BluetoothDevice d : adapter.getBondedDevices()) {
                    if (REMOTE_NAME.equals(d.getName())) remote = d;
                }
            }
        } catch (SecurityException e) {
            finish("no_permission");
            return;
        }
        if (remote == null) {
            finish("no_remote");
            return;
        }
        setState("connecting");
        Log.i(TAG, "remote finder: connecting");
        try {
            gatt = remote.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE,
                BluetoothDevice.PHY_LE_1M_MASK, handler);
        } catch (SecurityException e) {
            finish("no_permission");
            return;
        }
        handler.postDelayed(connectTimeout, CONNECT_TIMEOUT_MS);
    }

    private final Runnable connectTimeout = () -> {
        if ("connecting".equals(state)) {
            Log.w(TAG, "remote finder: no connection within " + CONNECT_TIMEOUT_MS / 1000 + " s");
            finish("failed");
        }
    };

    private final Runnable stopListening = () -> {
        Log.i(TAG, "remote finder: done listening");
        close();
        if ("ringing".equals(state)) setState("idle");
    };

    private final BluetoothGattCallback callback = new BluetoothGattCallback() {
        @Override public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                try { g.discoverServices(); } catch (SecurityException e) { finish("no_permission"); }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED && "connecting".equals(state)) {
                Log.w(TAG, "remote finder: disconnected (status " + status + ")");
                finish("failed");
            }
        }

        @Override public void onServicesDiscovered(BluetoothGatt g, int status) {
            BluetoothGattService s = g.getService(SERVICE);
            BluetoothGattCharacteristic in = s == null ? null : s.getCharacteristic(IN);
            if (s == null || in == null || s.getCharacteristic(OUT) == null) {
                Log.i(TAG, "remote finder: the remote has no finder service");
                finish("unsupported");
                return;
            }
            try {
                g.setCharacteristicNotification(in, true);
                BluetoothGattDescriptor cccd = in.getDescriptor(CCCD);
                if (cccd == null) {
                    send(g);
                } else if (Build.VERSION.SDK_INT >= 33) {
                    g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                } else {
                    cccd.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                    g.writeDescriptor(cccd);
                }
            } catch (SecurityException e) {
                finish("no_permission");
            }
        }

        @Override public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor d, int status) {
            send(g);
        }

        @Override public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic c, int status) {
            if (!OUT.equals(c.getUuid())) return;
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(TAG, "remote finder: write failed (status " + status + ")");
                if (sent <= 1) finish("failed");
                return;
            }
            if (sent == 1) {
                handler.removeCallbacks(connectTimeout);
                Log.i(TAG, "remote finder: ringing");
                setState("ringing");
                release();
                handler.postDelayed(stopListening, LISTEN_MS);
            }
            // Resend only if the remote hasn't confirmed it started: while it is
            // already beeping it refuses the command (seen live: status 254).
            if (sent < SENDS) handler.postDelayed(() -> { if (!started && gatt == g) send(g); }, SEND_INTERVAL_MS);
        }

        @Override public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c, byte[] value) {
            onNotify(c, value);
        }

        @Override @SuppressWarnings("deprecation")
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c) {
            if (Build.VERSION.SDK_INT < 33) onNotify(c, c.getValue());
        }
    };

    /**
     * The remote's status, byte 2 as Sony reads it (UINT8 at offset 2):
     * 0 = beeping started, 1 = success (found: a button silenced it),
     * 2 = timed out. Seen live: {@code 02 01 00} right after the write.
     */
    private void onNotify(BluetoothGattCharacteristic c, byte[] value) {
        if (!IN.equals(c.getUuid())) return;
        StringBuilder hex = new StringBuilder();
        if (value != null) for (byte b : value) hex.append(String.format(Locale.US, "%02x", b));
        int status = value != null && value.length > 2 ? value[2] & 0xff : -1;
        Log.i(TAG, "remote finder: remote reported " + hex + " (status " + status + ")");
        reason = hex.toString();
        if (status == 0) {
            started = true;
            setState("ringing");
            return;
        }
        handler.removeCallbacks(stopListening);
        close();
        setState(status == 1 ? "found" : "idle");
    }

    private void send(BluetoothGatt g) {
        BluetoothGattService s = g.getService(SERVICE);
        BluetoothGattCharacteristic out = s == null ? null : s.getCharacteristic(OUT);
        if (out == null) {
            finish("unsupported");
            return;
        }
        sent++;
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                g.writeCharacteristic(out, START_BUZZER, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
            } else {
                out.setValue(START_BUZZER);
                out.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
                g.writeCharacteristic(out);
            }
        } catch (SecurityException e) {
            finish("no_permission");
        }
    }

    private void finish(String result) {
        handler.removeCallbacks(connectTimeout);
        close();
        setState(result);
        release();
    }

    private void release() {
        CountDownLatch l = firstWrite;
        firstWrite = null;
        if (l != null) l.countDown();
    }

    private void close() {
        BluetoothGatt g = gatt;
        gatt = null;
        if (g != null) {
            try { g.close(); } catch (SecurityException ignored) { }
        }
    }

    private void setState(String s) {
        state = s;
        publish();
    }

    private void publish() {
        store.update("remote_finder", status());
    }
}
