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
import com.braviafanboy.braviaagent.readers.PictureReader;
import com.braviafanboy.braviaagent.readers.SettingsReader;
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
    public static final String VERSION = "0.6.0";

    private static volatile AgentService instance;

    private StateStore store;
    private HttpServer server;
    private DeviceIdentity identity;
    private Pairing pairing;
    private RemoteFinder remoteFinder;
    private Notifier notifier;
    private Tls tls;
    private EnvironmentReader environment;
    private DisplayReader display;
    private InputsReader inputs;
    private AudioReader audio;
    private MediaReader media;
    private BluetoothReader bluetooth;
    private WifiReader wifi;
    private SettingsReader settings;
    private PictureReader picture;
    private RestClient rest;
    private RestAuth restAuth;
    private NsdManager nsd;
    private NsdManager.RegistrationListener registration;
    private String registeredId;
    private boolean registeredPaired;
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
        pairing = new Pairing(this);
        Updater staged = new Updater(this);
        if (Updater.isApplied(staged.pendingVersion(), VERSION)) staged.clearPending();  // that update (or a newer one) is running
        pairing.setListener(tokenChanged -> new android.os.Handler(getMainLooper()).post(() -> {
            registerMdns();
            // A changed token must re-authenticate open streams; the window
            // opening or closing changes nothing for a connected client.
            if (tokenChanged && server != null) server.closeStreams();
        }));
        identity.setCallback(id -> new android.os.Handler(getMainLooper()).post(this::registerMdns));
        environment = new EnvironmentReader(this, store);
        display = new DisplayReader(this, store);
        inputs = new InputsReader(this, store);
        audio = new AudioReader(this, store);
        media = new MediaReader(this, store);
        bluetooth = new BluetoothReader(this, store);
        wifi = new WifiReader(this, store);
        settings = new SettingsReader(this, store);
        rest = new RestClient();
        restAuth = new RestAuth(this);
        picture = new PictureReader(this, store, rest, restAuth);
        remoteFinder = new RemoteFinder(this, store);
        notifier = new Notifier(this);
        display.setPanelListener(this::onPanelChanged);
        display.start();
        inputs.start();
        identity.start();
        if (display.isPanelOn()) resumeReaders(); else Log.i(TAG, "panel off at start: readers paused");
        server = new HttpServer(this, PORT, store, this, media, pairing, picture);
        server.setRest(rest, restAuth);
        server.setRemoteFinder(remoteFinder);
        server.setNotifier(notifier);
        try {
            tls = Tls.load(this);
            server.start(tls.serverSocketFactory());
            Log.i(TAG, "https server on :" + PORT + " fingerprint " + tls.fingerprint().substring(0, 16) + "…");
        } catch (Exception e) {
            // Without TLS the agent would hand its token to anyone on the LAN;
            // better to serve nothing.
            Log.e(TAG, "https server failed, not serving: " + e);
            server = null;
        }
        nsd = (NsdManager) getSystemService(Context.NSD_SERVICE);
        registerMdns();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Re-register mDNS if the device id became known since we registered.
        if (!foreground) foreground = promoteToForeground();
        String id = identity.deviceUniqueId();
        if ((id != null && !id.equals(registeredId)) || pairing.isPaired() != registeredPaired) registerMdns();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        unregisterMdns();
        if (server != null) server.stop();
        pauseReaders();
        display.stop();
        inputs.stop();
        identity.stop();
        instance = null;
        super.onDestroy();
    }

    /**
     * adb-only maintenance and test commands:
     * {@code adb shell dumpsys activity service com.braviafanboy.braviaagent/.AgentService <cmd>}.
     * The system only lets callers holding DUMP (the adb shell, system) reach
     * this, so it is never exposed on the network. Secrets are never printed.
     *
     * <pre>
     *   (no args)                       status
     *   rest psk KEY | rest verify | rest forget
     *   picture refresh | picture get TARGET | picture set TARGET VALUE
     *   picture config POLL_S EVENT_REFRESH(true|false) [T1,T2,...]
     *   power_saving off|low|high
     *   pairing window | pairing forget   (development: open the 60 s window / clear the token)
     *   remote find                       (beep the remote)
     * </pre>
     */
    @Override
    protected void dump(java.io.FileDescriptor fd, java.io.PrintWriter out, String[] args) {
        // dump() runs on the main thread, where network calls are refused: run
        // the command on a worker and wait for it (bounded).
        java.io.StringWriter buf = new java.io.StringWriter();
        Thread worker = new Thread(() -> runDumpCommand(new java.io.PrintWriter(buf, true), args), "agent-dump");
        worker.start();
        try { worker.join(15000); } catch (InterruptedException ignored) { }
        out.print(buf);
        if (worker.isAlive()) out.println("timed out (still running)");
    }

    private void runDumpCommand(java.io.PrintWriter pw, String[] args) {
        String cmd = args.length > 0 ? args[0] : "";
        String sub = args.length > 1 ? args[1] : "";
        try {
            if ("rest".equals(cmd)) {
                switch (sub) {
                    case "psk":
                        if (args.length < 3) { pw.println("usage: rest psk KEY"); break; }
                        restAuth.setPsk(args[2]);
                        pw.println("verified=" + restAuth.verify(rest));
                        break;
                    case "verify": pw.println("verified=" + restAuth.verify(rest)); break;
                    case "forget": restAuth.forget(); pw.println("forgotten"); break;
                    default: break;
                }
                picture.publish();
            } else if ("picture".equals(cmd)) {
                switch (sub) {
                    case "refresh": picture.refresh(); pw.println("queued"); break;
                    case "get": {
                        Object p = store.snapshot().opt("picture");
                        org.json.JSONObject targets = p instanceof org.json.JSONObject ? ((org.json.JSONObject) p).optJSONObject("targets") : null;
                        pw.println(targets == null || args.length < 3 ? "no data" : String.valueOf(targets.opt(args[2])));
                        break;
                    }
                    case "set":
                        if (args.length < 4) { pw.println("usage: picture set TARGET VALUE"); break; }
                        try { picture.writeTarget(args[2], args[3]); pw.println("ok"); }
                        catch (PictureReader.WriteException e) { pw.println("refused: " + e.code); }
                        break;
                    case "setv":
                        // picture setv VERSION TARGET VALUE: force an API version (testing).
                        if (args.length < 5) { pw.println("usage: picture setv 1.0|1.1 TARGET VALUE"); break; }
                        try { picture.writeTarget(args[3], args[4], args[2]); pw.println("ok"); }
                        catch (PictureReader.WriteException e) { pw.println("refused: " + e.code); }
                        break;
                    case "config": {
                        Integer poll = args.length > 2 ? Integer.valueOf(args[2]) : null;
                        Boolean ev = args.length > 3 ? Boolean.valueOf(args[3]) : null;
                        java.util.List<String> watch = args.length > 4 ? Arrays.asList(args[4].split(",")) : null;
                        pw.println("poll_s=" + picture.configure(poll, ev, watch));
                        break;
                    }
                    default: break;
                }
            } else if ("media".equals(cmd)) {
                pw.println(media.dump());
            } else if ("remote".equals(cmd) && "find".equals(sub)) {
                pw.println("remote finder: " + remoteFinder.find());
            } else if ("notify".equals(cmd)) {
                String msg = args.length > 1 ? String.join(" ", Arrays.copyOfRange(args, 1, args.length)) : "Test message";
                pw.println("notify: " + notifier.show("Home Assistant", msg, 0, "message") + " (overlay=" + notifier.canOverlay() + ")");
            } else if ("pairing".equals(cmd) && "window".equals(sub)) {
                pairing.openWindow();
                pw.println("window open for " + Pairing.WINDOW_MS / 1000 + " s");
            } else if ("pairing".equals(cmd) && "forget".equals(sub)) {
                pairing.clear();
                pw.println("pairing cleared");
            } else if ("power_saving".equals(cmd) && !sub.isEmpty()) {
                try { picture.writePowerSaving(sub); pw.println("ok"); }
                catch (PictureReader.WriteException e) { pw.println("refused: " + e.code); }
            }
        } catch (Exception e) {
            pw.println("error: " + e);
        }
        JSONObject snap = store.snapshot();
        pw.println("version " + VERSION + ", paired " + pairing.isPaired());
        pw.println("rest " + snap.opt("rest"));
        pw.println("power_saving " + snap.opt("power_saving"));
        Object p = snap.opt("picture");
        if (p instanceof JSONObject && ((JSONObject) p).optJSONObject("targets") != null) {
            pw.println("picture targets " + ((JSONObject) p).optJSONObject("targets").length());
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    public StateStore store() {
        return store;
    }

    public Pairing pairing() {
        return pairing;
    }

    public boolean notificationAccessGranted() {
        return media != null && media.isGranted();
    }

    /** Notification access or a runtime permission may have changed. */
    public void onPermissionsMaybeChanged() {
        new android.os.Handler(getMainLooper()).post(() -> {
            if (media != null) media.recheckPermission();
            if (bluetooth != null) bluetooth.recheckPermission();
        });
    }

    private boolean readersRunning;

    /**
     * While the panel is off (standby, Picture Off) the TV has nothing new to
     * report and the LAN is usually gone; stop sampling sensors and polling
     * so the agent is idle, and pick everything up again on wake.
     */
    private void onPanelChanged(boolean on) {
        if (on) resumeReaders(); else pauseReaders();
    }

    private void resumeReaders() {
        if (readersRunning) return;
        readersRunning = true;
        environment.start();
        audio.start();
        wifi.start();
        settings.start();
        media.start();
        bluetooth.start();
        picture.start();
        Log.i(TAG, "readers running");
    }

    private void pauseReaders() {
        if (!readersRunning) return;
        readersRunning = false;
        environment.stop();
        audio.stop();
        wifi.stop();
        settings.stop();
        media.stop();
        bluetooth.stop();
        picture.stop();
        Log.i(TAG, "readers paused (panel off)");
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
            JSONObject t = new JSONObject();
            t.put("fingerprint_sha256", tls == null ? JSONObject.NULL : tls.fingerprint());
            o.put("tls", t);
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
            perms.put("install_packages", getPackageManager().canRequestPackageInstalls());
            perms.put("display_overlay", notifier.canOverlay());
            o.put("permissions", perms);
            JSONObject pair = new JSONObject();
            pair.put("paired", pairing.isPaired());
            pair.put("paired_with", pairing.isPaired() ? pairing.pairedName() : JSONObject.NULL);
            pair.put("window_open", pairing.windowOpen());
            pair.put("window_seconds_left", pairing.windowSecondsLeft());
            o.put("pairing", pair);
            JSONObject upd = new JSONObject();
            Updater u = new Updater(this);
            upd.put("pending_version", u.pendingUrl() == null ? JSONObject.NULL : u.pendingVersion());
            o.put("update", upd);
            o.put("endpoints", new JSONArray(Arrays.asList("/v1/state", "/v1/events", MediaReader.ARTWORK_PATH,
                HttpServer.PICTURE_REFRESH_PATH, HttpServer.PICTURE_PATH, HttpServer.PICTURE_BATCH_PATH, HttpServer.PICTURE_CONFIG_PATH,
                HttpServer.POWER_SAVING_PATH, HttpServer.REST_AUTH_PATH, HttpServer.REST_PSK_PATH,
                HttpServer.REMOTE_FIND_PATH, HttpServer.NOTIFY_PATH, HttpServer.MEDIA_COMMAND_PATH)));
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
        info.setServiceName("HA TV Agent");
        info.setServiceType(SERVICE_TYPE);
        info.setPort(PORT);
        info.setAttribute("v", String.valueOf(HttpServer.CONTRACT));
        info.setAttribute("tls", "1");
        info.setAttribute("p", pairing.isPaired() ? "1" : "0");
        String id = identity.deviceUniqueId();
        if (id != null) info.setAttribute("id", id);
        registeredId = id;
        registeredPaired = pairing.isPaired();
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
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "HA TV Agent", NotificationManager.IMPORTANCE_MIN));
        Intent open = new Intent(this, StatusActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL)
            .setContentTitle("HA TV Agent")
            .setContentText("Reporting to Home Assistant on port " + PORT)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentIntent(pi)
            .setOngoing(true)
            .build();
    }
}
