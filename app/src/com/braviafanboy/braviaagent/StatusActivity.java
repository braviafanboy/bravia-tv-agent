package com.braviafanboy.braviaagent;

import android.app.Activity;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.provider.Settings;
import android.view.Gravity;
import android.view.KeyEvent;
import android.util.TypedValue;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * The agent's one screen: a short, plain status of what it can do, and the
 * setup flow that walks through Android's permission prompts. Opened from the
 * launcher, or by Home Assistant's Agent setup button (setup link), which
 * closes it again once there is nothing left to grant.
 */
public final class StatusActivity extends Activity {
    private static final int OK_COLOR = 0xFF7BD88F;
    private static final int TODO_COLOR = 0xFFFFC66D;
    private static final int DIM_COLOR = 0xFF9A9A9A;
    static final String DISCLAIMER =
        "Unofficial helper for the Bravia TV (gRPC) integration · not affiliated with Sony or Home Assistant";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private LinearLayout rows;
    private TextView hint;
    private TextView footer;
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            render();
            handler.postDelayed(this, 2000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        AgentService.start(this);
        Intent intent = getIntent();
        Uri referrer = getReferrer();
        Uri data = intent == null ? null : intent.getData();
        android.util.Log.i("BraviaAgent", "launched with referrer " + referrer + " data " + data);
        if (PairActivity.adoptPairLink(this, intent)) {
            // Sony's deep link lands on the main activity with the URI attached,
            // not on the VIEW handler: take the token and leave without drawing.
            finish();
            return;
        }
        String host = referrer == null ? null : referrer.getHost();
        boolean updateLink = data != null && "braviaagent".equals(data.getScheme()) && "update".equals(data.getHost());
        if (updateLink || (host != null && host.startsWith("com.sony.") && new Updater(this).pendingUrl() != null)) {
            // A self-update: hand over to the foreground updater.
            startActivity(new Intent(this, UpdateActivity.class).setData(data));
            finish();
            return;
        }
        boolean setupLink = data != null && "braviaagent".equals(data.getScheme()) && "setup".equals(data.getHost());
        if (!setupLink && host != null && host.startsWith("com.sony.")) {
            // Launched by the integration through DeviceControlService to pair
            // locally: open the window, say so, and leave without drawing.
            handler.postDelayed(() -> {
                AgentService svc = AgentService.instance();
                if (svc != null) svc.pairing().openWindow();
            }, 300);
            android.widget.Toast.makeText(this, "Home Assistant is pairing with the HA TV Agent", android.widget.Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        setupFlow = setupLink;
        if (setupFlow && allGranted()) {
            // Nothing to grant: say so and get out of the way.
            toast("HA TV Agent is set up");
            finish();
            return;
        }
        buildUi();
        if (setupFlow) advance();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (PairActivity.adoptPairLink(this, intent)) { finish(); return; }
        Uri data = intent.getData();
        if (data != null && "braviaagent".equals(data.getScheme()) && "setup".equals(data.getHost())) {
            setupFlow = true;
            asked.clear();
            advance();
        }
    }

    // ---- setup flow ---------------------------------------------------------

    private boolean setupFlow;
    /** A settings screen was opened; continue when we are back. */
    private boolean awaitingSettings;
    /** Grants already asked for in this flow, so a refusal does not loop. */
    private final Set<String> asked = new HashSet<>();

    private boolean bluetoothGranted() {
        return checkSelfPermission("android.permission.BLUETOOTH_CONNECT") == PackageManager.PERMISSION_GRANTED;
    }

    private boolean notificationAccessGranted() {
        android.app.NotificationManager nm = getSystemService(android.app.NotificationManager.class);
        return nm != null && nm.isNotificationListenerAccessGranted(
            new android.content.ComponentName(this, MediaListenerService.class));
    }

    private boolean installGranted() {
        return getPackageManager().canRequestPackageInstalls();
    }

    private boolean allGranted() {
        return bluetoothGranted() && notificationAccessGranted() && installGranted();
    }

    /** Ask for the next missing grant not yet asked in this flow; false when none is left. */
    private boolean nextGrant() {
        if (!bluetoothGranted() && asked.add("bluetooth")) {
            requestPermissions(new String[] {"android.permission.BLUETOOTH_CONNECT"}, 1);
            return true;
        }
        if (!notificationAccessGranted() && asked.add("notifications")) {
            awaitingSettings = true;
            openNotificationAccess();
            return true;
        }
        if (!installGranted() && asked.add("install")) {
            awaitingSettings = true;
            openInstallPermission();
            return true;
        }
        return false;
    }

    /** Continue the setup flow; when nothing is left to ask, close with a summary. */
    private void advance() {
        if (nextGrant()) return;
        setupFlow = false;
        toast(allGranted() ? "HA TV Agent is set up" : "HA TV Agent: some permissions were not granted");
        finish();
    }

    /** Settings → "Install unknown apps" for this app (needed for self-update). */
    private void openInstallPermission() {
        try {
            startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName())));
        } catch (Exception e) {
            android.util.Log.w("BraviaAgent", "install permission settings: " + e);
            awaitingSettings = false;
        }
    }

    /** Settings → Apps → Special app access → Notification access, directly. */
    private void openNotificationAccess() {
        try {
            startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
        } catch (Exception e) {
            android.util.Log.w("BraviaAgent", "notification access settings: " + e);
            awaitingSettings = false;
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        AgentService svc = AgentService.instance();
        if (svc != null) svc.onPermissionsMaybeChanged();
        if (setupFlow) advance();
    }

    /**
     * OK: grant whatever is missing. RIGHT: open the 60 s pairing window.
     * Hold LEFT: unpair. BACK closes.
     */
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        AgentService svc = AgentService.instance();
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            if (allGranted()) {
                toast("Nothing to set up");
            } else {
                setupFlow = true;
                asked.clear();
                advance();
            }
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && svc != null) {
            svc.pairing().openWindow();
            render();
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT && svc != null && event.getRepeatCount() == 0 && event.isLongPress()) {
            svc.pairing().clear();
            render();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (setupFlow && awaitingSettings) {
            // Back from a settings screen: pick up where the flow left off.
            awaitingSettings = false;
            AgentService svc = AgentService.instance();
            if (svc != null) svc.onPermissionsMaybeChanged();
            advance();
            if (isFinishing()) return;
        }
        handler.post(refresh);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(refresh);
        super.onPause();
    }

    // ---- the screen ----------------------------------------------------------

    private void buildUi() {
        // The heading says it all; no system title bar on top of it.
        requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        int pad = dp(56);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.BLACK);
        root.setPadding(pad, dp(40), pad, dp(32));

        TextView title = label("HA TV Agent", 30, Color.WHITE);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        root.addView(title);
        root.addView(label("Version " + AgentService.VERSION, 16, DIM_COLOR));
        root.addView(label(DISCLAIMER, 16, DIM_COLOR));

        rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        rows.setPadding(0, dp(28), 0, dp(28));
        root.addView(rows);

        hint = label("", 18, Color.WHITE);
        root.addView(hint);
        footer = label("", 14, DIM_COLOR);
        footer.setPadding(0, dp(16), 0, 0);
        root.addView(footer);
        setContentView(root);
    }

    private void render() {
        if (rows == null) return;
        AgentService svc = AgentService.instance();
        rows.removeAllViews();
        if (svc == null) {
            row("Status", "Starting…", DIM_COLOR);
        } else {
            Pairing pairing = svc.pairing();
            if (pairing.windowOpen()) {
                row("Home Assistant", "Pairing… " + pairing.windowSecondsLeft() + " s left", TODO_COLOR);
            } else if (!pairing.isPaired()) {
                row("Home Assistant", "Not paired yet", TODO_COLOR);
            } else if (svc.store().hasListeners()) {
                row("Home Assistant", "Connected", OK_COLOR);
            } else {
                row("Home Assistant", "Paired, waiting for it to connect", DIM_COLOR);
            }
        }
        grantRow("Now playing", notificationAccessGranted(), "Needs Notification access");
        grantRow("Remote battery", bluetoothGranted(), "Needs Bluetooth permission");
        grantRow("Updates", installGranted(), "Needs \"Install unknown apps\"");
        if (svc != null) {
            JSONObject rest = svc.store().snapshot().optJSONObject("rest");
            if (rest == null) {
                row("Picture settings", "Checking…", DIM_COLOR);
            } else if (!rest.optBoolean("available")) {
                row("Picture settings", "Unavailable (IP control is off)", DIM_COLOR);
            } else if (rest.optBoolean("write_authorized")) {
                row("Picture settings", "Read and change", OK_COLOR);
            } else if (!"none".equals(rest.optString("auth", "none"))) {
                row("Picture settings", "Key rejected by the TV", TODO_COLOR);
            } else {
                // Reading needs no key; changing them needs the IP control
                // pre-shared key, entered in Home Assistant.
                row("Picture settings", "Read only (no key)", OK_COLOR);
            }
        }
        StringBuilder h = new StringBuilder();
        if (!allGranted()) h.append("OK  grant the missing permissions      ");
        h.append("BACK  close");
        hint.setText(h.toString());
        String ip = ownIpv4();
        footer.setText((ip == null ? "" : ip + "  ·  ") + "→ pairing window  ·  hold ← unpair");
    }

    private void grantRow(String name, boolean granted, String missing) {
        row(name, granted ? "OK" : missing, granted ? OK_COLOR : TODO_COLOR);
    }

    private void row(String name, String value, int color) {
        LinearLayout line = new LinearLayout(this);
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setPadding(0, dp(6), 0, dp(6));
        TextView k = label(name, 20, Color.WHITE);
        k.setLayoutParams(new LinearLayout.LayoutParams(dp(260), LinearLayout.LayoutParams.WRAP_CONTENT));
        TextView v = label(value, 20, color);
        line.addView(k);
        line.addView(v);
        rows.addView(line);
    }

    private TextView label(String s, int sp, int color) {
        TextView v = new TextView(this);
        v.setText(s);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        v.setTextColor(color);
        v.setGravity(Gravity.START);
        return v;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private static String ownIpv4() {
        try {
            for (NetworkInterface nif : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!nif.isUp() || nif.isLoopback()) continue;
                for (InetAddress a : Collections.list(nif.getInetAddresses())) {
                    if (a instanceof Inet4Address) return a.getHostAddress();
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }
}
