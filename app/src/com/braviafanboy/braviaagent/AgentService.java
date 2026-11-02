package com.braviafanboy.braviaagent;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import com.braviafanboy.braviaagent.readers.AudioReader;
import com.braviafanboy.braviaagent.readers.BluetoothReader;
import com.braviafanboy.braviaagent.readers.DisplayReader;
import com.braviafanboy.braviaagent.readers.EnvironmentReader;
import com.braviafanboy.braviaagent.readers.InputsReader;
import com.braviafanboy.braviaagent.readers.MediaReader;
import com.braviafanboy.braviaagent.readers.SettingsReader;
import com.braviafanboy.braviaagent.readers.ThemesReader;
import com.braviafanboy.braviaagent.readers.WifiReader;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.Arrays;

/** Foreground service: readers + HTTP server + mDNS advertisement. */
public final class AgentService extends Service implements HttpServer.InfoProvider {
    private static final String TAG = "BraviaAgent";
    private static final String CHANNEL = "agent";
    private static final int NOTIFICATION_ID = 1;
    public static final int PORT = 8585;
    public static final String SERVICE_TYPE = "_bravia-agent._tcp";
    public static final String VERSION = "0.1.0";

    private static volatile AgentService instance;

    private StateStore store;
    private HttpServer server;
    private DeviceIdentity identity;
    private EnvironmentReader environment;
    private DisplayReader display;
    private InputsReader inputs;
    private AudioReader audio;
    private MediaReader media;
    private BluetoothReader bluetooth;
    private WifiReader wifi;
    private SettingsReader settings;
    private ThemesReader themes;
    private NsdManager nsd;
    private NsdManager.RegistrationListener registration;
    private String registeredId;
    private boolean foreground;

    public static AgentService instance() {
        return instance;
    }

    public static void start(Context context) {
        Intent i = new Intent(context, AgentService.class);
        context.startForegroundService(i);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        foreground = promoteToForeground();
        store = new StateStore();
        identity = new DeviceIdentity(this);
        identity.setCallback(id -> new android.os.Handler(getMainLooper()).post(this::registerMdns));
        themes = new ThemesReader(this);
        environment = new EnvironmentReader(this, store);
        display = new DisplayReader(this, store);
        inputs = new InputsReader(this, store);
        audio = new AudioReader(this, store);
        media = new MediaReader(this, store);
        bluetooth = new BluetoothReader(this, store);
        wifi = new WifiReader(this, store);
        settings = new SettingsReader(this, store);
        environment.start();
        display.start();
        inputs.start();
        audio.start();
        media.start();
        bluetooth.start();
        wifi.start();
        settings.start();
        identity.start();
        server = new HttpServer(PORT, store, themes, this, media);
        try {
            server.start(HttpServer.SOCKET_READ_TIMEOUT, false);
            Log.i(TAG, "http server on :" + PORT);
        } catch (IOException e) {
            Log.e(TAG, "http server failed: " + e);
        }
        nsd = (NsdManager) getSystemService(Context.NSD_SERVICE);
        registerMdns();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Re-register mDNS if the device id became known since we registered.
        if (!foreground) foreground = promoteToForeground();
        String id = identity.deviceUniqueId();
        if (id != null && !id.equals(registeredId)) registerMdns();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        unregisterMdns();
        if (server != null) server.stop();
        environment.stop();
        display.stop();
        inputs.stop();
        audio.stop();
        media.stop();
        bluetooth.stop();
        wifi.stop();
        settings.stop();
        identity.stop();
        instance = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    public StateStore store() {
        return store;
    }

    /** Notification access or a runtime permission may have changed. */
    public void onPermissionsMaybeChanged() {
        new android.os.Handler(getMainLooper()).post(() -> {
            if (media != null) media.recheckPermission();
            if (bluetooth != null) bluetooth.recheckPermission();
        });
    }

    /** Document for /v1/info. */
    @Override
    public JSONObject info() {
        JSONObject o = new JSONObject();
        try {
            JSONObject agent = new JSONObject();
            agent.put("version", VERSION);
            agent.put("contract", HttpServer.CONTRACT);
            agent.put("port", PORT);
            agent.put("foreground", foreground);
            o.put("agent", agent);
            JSONObject tv = new JSONObject();
            tv.put("model", DeviceIdentity.model());
            tv.put("device_unique_id", identity.deviceUniqueId() == null ? JSONObject.NULL : identity.deviceUniqueId());
            tv.put("android_version", Build.VERSION.RELEASE);
            tv.put("build", Build.DISPLAY);
            o.put("tv", tv);
            JSONObject perms = new JSONObject();
            perms.put("notification_access", media.isGranted());
            perms.put("bluetooth_connect", bluetooth.isGranted());
            perms.put("usage_access", false);
            o.put("permissions", perms);
            o.put("endpoints", new JSONArray(Arrays.asList("/v1/state", "/v1/events", "/v1/themes", MediaReader.ARTWORK_PATH)));
        } catch (Exception ignored) {
        }
        return o;
    }

    /**
     * Android 14 refuses startForeground() when the service was (re)started
     * from the background, e.g. a START_STICKY restart after the process died.
     * The agent keeps running as a plain started service in that case; the
     * next start from an allowed context (boot, app launch by the user or by
     * the integration) promotes it again.
     */
    private boolean promoteToForeground() {
        try {
            startForeground(NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
            return true;
        } catch (android.app.ForegroundServiceStartNotAllowedException e) {
            Log.w(TAG, "foreground not allowed from this context; running in background: " + e.getMessage());
            return false;
        }
    }

    private void registerMdns() {
        if (nsd == null) return;
        unregisterMdns();
        NsdServiceInfo info = new NsdServiceInfo();
        info.setServiceName("BRAVIA agent");
        info.setServiceType(SERVICE_TYPE);
        info.setPort(PORT);
        info.setAttribute("v", String.valueOf(HttpServer.CONTRACT));
        String id = identity.deviceUniqueId();
        if (id != null) info.setAttribute("id", id);
        registeredId = id;
        registration = new NsdManager.RegistrationListener() {
            @Override public void onRegistrationFailed(NsdServiceInfo i, int e) { Log.w(TAG, "mdns registration failed " + e); }
            @Override public void onUnregistrationFailed(NsdServiceInfo i, int e) { }
            @Override public void onServiceRegistered(NsdServiceInfo i) { Log.i(TAG, "mdns registered as " + i.getServiceName()); }
            @Override public void onServiceUnregistered(NsdServiceInfo i) { }
        };
        try {
            nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, registration);
        } catch (Exception e) {
            Log.w(TAG, "registerService: " + e);
        }
    }

    private void unregisterMdns() {
        if (nsd != null && registration != null) {
            try { nsd.unregisterService(registration); } catch (Exception ignored) { }
            registration = null;
        }
    }

    private Notification buildNotification() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "BRAVIA agent", NotificationManager.IMPORTANCE_MIN));
        Intent open = new Intent(this, StatusActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL)
            .setContentTitle("BRAVIA agent")
            .setContentText("Reporting to Home Assistant on port " + PORT)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentIntent(pi)
            .setOngoing(true)
            .build();
    }
}
