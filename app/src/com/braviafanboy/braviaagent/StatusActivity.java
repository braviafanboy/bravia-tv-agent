package com.braviafanboy.braviaagent;

import android.app.Activity;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.provider.Settings;
import android.view.Gravity;
import android.view.KeyEvent;
import android.util.TypedValue;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.braviafanboy.braviaagent.core.PairingState;

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
 * launcher, or by Home Assistant's Agent setup button, which closes it again
 * once there is nothing left to grant.
 *
 * <p>It is also where a pairing request is answered: while one is waiting,
 * this screen asks the viewer to allow or deny it and shows the code to
 * compare (see {@link #maybePrompt}), however the screen was opened.
 *
 * <p>And it is what the TV's control service starts when Home Assistant
 * launches the app: to run a staged update, to open the setup flow, or to put
 * that pairing question before the viewer. Only a launch by that service
 * starts an update or the setup flow (see {@link #launchedByTv}).
 */
public final class StatusActivity extends Activity {
    private static final int OK_COLOR = 0xFF7BD88F;
    /** Works with a limit, or in progress. */
    private static final int TODO_COLOR = 0xFFFFC66D;
    /** Does not work until something is done. */
    private static final int BAD_COLOR = 0xFFFF6B6B;
    private static final int DIM_COLOR = 0xFF9A9A9A;
    private static final String TAG = "BraviaAgent";
    /**
     * The TV's control service: it launches apps for Sony's control channels
     * (Home Assistant's local launch, and links sent through Sony's cloud).
     */
    private static final String CONTROL_SERVICE = "com.sony.dtv.devicecontrolservice";
    static final String DISCLAIMER =
        "Unofficial helper for the Bravia TV (gRPC) integration · not affiliated with Sony or Home Assistant";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private LinearLayout rows;
    private TextView versionLine;
    private LinearLayout keys;
    /** The pairing question on screen, and the request it is about. */
    private android.app.AlertDialog pairingDialog;
    private String promptedId;
    /** The screen was opened for a pairing request: close it after the answer. */
    private boolean closeAfterPairing;
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
        boolean byTv = launchedByTv(intent);
        String link = linkOf(intent);
        android.util.Log.i(TAG, "launched" + (byTv ? " by the TV" : "") + (link == null ? "" : ", " + link + " link"));
        // Pairing links are not accepted (they once carried a token through
        // Sony's cloud), and no link counts unless the TV delivered it.
        if ("pair".equals(link)) {
            finish();
            return;
        }
        if (byTv && tvLaunch(link, true)) return;
        buildUi();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        // The screen is already open, so a launch arrives here rather than in
        // onCreate, and must do the same.
        boolean byTv = launchedByTv(intent);
        String link = linkOf(intent);
        if ("pair".equals(link)) return;
        if (byTv) tvLaunch(link, false);
    }

    /**
     * Whether this launch came from the TV's control service.
     *
     * <p>A referrer inside the intent is whatever the caller wrote there, so
     * it is taken out first: what getReferrer() then returns is the caller the
     * system recorded. That package must be the control service by name, and
     * part of the system (a name alone could be taken by any app on a device
     * that lacks the real one).
     */
    private boolean launchedByTv(Intent intent) {
        try {
            if (intent != null) {
                intent.removeExtra(Intent.EXTRA_REFERRER);
                intent.removeExtra(Intent.EXTRA_REFERRER_NAME);
            }
            Uri referrer = getReferrer();
            if (referrer == null || !"android-app".equals(referrer.getScheme())) return false;
            String caller = referrer.getHost();
            if (!CONTROL_SERVICE.equals(caller)) {
                android.util.Log.i(TAG, "launched by " + caller);
                return false;
            }
            ApplicationInfo info = getPackageManager().getApplicationInfo(caller, 0);
            return (info.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
        } catch (PackageManager.NameNotFoundException e) {
            android.util.Log.w(TAG, "the TV's control service is not visible to the agent");
            return false;
        } catch (RuntimeException e) {
            // Extras that cannot be read: not something the TV sends.
            return false;
        }
    }

    /** "pair", "update" or "setup" for a {@code braviaagent://} link, else null. */
    private static String linkOf(Intent intent) {
        Uri data = intent == null ? null : intent.getData();
        if (data == null || !"braviaagent".equals(data.getScheme())) return null;
        String what = data.getHost();
        return "pair".equals(what) || "update".equals(what) || "setup".equals(what) ? what : null;
    }

    /**
     * A launch by the TV's control service, which is how Home Assistant
     * reaches the app: with an update link or while an update is staged, run
     * the update; with a setup link or after POST /v1/setup, open the setup
     * flow; otherwise, when a pairing request is waiting, stay open so the
     * viewer can answer it. With nothing to do the screen closes again.
     *
     * <p>{@code created}: the launch created this screen (rather than reaching
     * one already open), so it closes again once there is nothing to show.
     * Returns whether the launch was dealt with.
     */
    private boolean tvLaunch(String link, boolean created) {
        if ("update".equals(link) || (link == null && new Updater(this).pendingUrl() != null)) {
            startActivity(new Intent(this, UpdateActivity.class));
            if (created) finish();
            return true;
        }
        AgentService svc = AgentService.instance();
        if ("setup".equals(link) || (link == null && svc != null && svc.takeSetupRequest())) {
            if (allGranted()) {
                // Nothing to grant: say so and get out of the way.
                toast("HA TV Agent is set up");
                if (created) finish();
                return true;
            }
            if (created) buildUi();
            setupFlow = true;
            asked.clear();
            advance();
            return true;
        }
        PairingState.Request request = svc == null ? null : svc.pairing().pending();
        if (request != null && request.code() != null && !request.accepted()) {
            // The question is asked once the screen is up (maybePrompt).
            if (created) {
                buildUi();
                closeAfterPairing = true;
            }
            return true;
        }
        android.util.Log.i(TAG, "launched by the TV with nothing to do");
        if (created) finish();
        return true;
    }

    // ---- pairing -------------------------------------------------------------

    /**
     * Ask the viewer about the pairing request that is waiting, once it has
     * its code. Called with every refresh of the screen, so a request that
     * arrives while the screen is open is asked about too, and a question
     * whose request has gone is taken down.
     *
     * <p>The answer is the viewer's alone: nothing a client sends, and no
     * launch, allows a request. Deny has the focus, and BACK denies.
     */
    private void maybePrompt(AgentService svc) {
        PairingState.Request request = svc == null ? null : svc.pairing().pending();
        boolean askable = request != null && request.code() != null && !request.accepted();
        if (pairingDialog != null) {
            if (askable && request.id.equals(promptedId)) return;
            dismissPrompt();
            if (!askable) {
                toast("The pairing request is no longer waiting");
                donePairing();
                return;
            }
        }
        if (!askable || setupFlow || isFinishing()) return;
        final String id = request.id;
        if (!svc.pairing().shown(id)) return;

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(28), dp(12), dp(28), dp(4));
        body.addView(label(who(request.name, request.peer) + " asks to pair with the HA TV Agent.", 18, Color.WHITE));
        String code = request.code();
        TextView digits = label(code.substring(0, 3) + " " + code.substring(3), 44, Color.WHITE);
        digits.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        digits.setPadding(0, dp(14), 0, dp(14));
        body.addView(digits);
        body.addView(label("Allow only if Home Assistant is showing this same code.", 16, DIM_COLOR));

        android.app.AlertDialog dialog = new android.app.AlertDialog.Builder(this,
                android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Allow pairing?")
            .setView(body)
            .setNegativeButton("Deny", (d, which) -> answered(id, false))
            .setPositiveButton("Allow", (d, which) -> answered(id, true))
            .setOnCancelListener(d -> answered(id, false))
            .create();
        dialog.setCanceledOnTouchOutside(false);
        dialog.show();
        android.widget.Button deny = dialog.getButton(android.content.DialogInterface.BUTTON_NEGATIVE);
        if (deny != null) deny.requestFocus();
        pairingDialog = dialog;
        promptedId = id;
    }

    private void answered(String id, boolean allow) {
        pairingDialog = null;
        AgentService svc = AgentService.instance();
        PairingState.Request r = svc == null ? null : allow ? svc.pairing().accept(id) : svc.pairing().deny(id);
        if (r == null) {
            toast("The pairing request is no longer waiting");
        } else {
            toast(allow ? "Pairing allowed for " + who(r.name, r.peer) : "Pairing denied");
        }
        donePairing();
    }

    /** Take the question down without an answer; the request waits on. */
    private void dismissPrompt() {
        android.app.AlertDialog dialog = pairingDialog;
        pairingDialog = null;
        promptedId = null;
        if (dialog != null) {
            try { dialog.dismiss(); } catch (RuntimeException ignored) { }
        }
    }

    private void donePairing() {
        if (closeAfterPairing) {
            closeAfterPairing = false;
            finish();
        } else {
            render();
        }
    }

    /** A client as the viewer should see it: the name it gave, and the address it came from. */
    private static String who(String name, String peer) {
        String n = name == null || name.isEmpty() ? "unknown" : name;
        return peer == null || peer.isEmpty() ? n : n + " (" + peer + ")";
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

    /**
     * From Android 13 a notification needs the viewer's consent. The agent
     * posts one only when it may not draw over other apps, so this is asked
     * for in the setup flow but is not one of the grants it waits for.
     */
    private boolean postNotificationsGranted() {
        return Build.VERSION.SDK_INT < 33
            || checkSelfPermission("android.permission.POST_NOTIFICATIONS") == PackageManager.PERMISSION_GRANTED;
    }

    private boolean notificationAccessGranted() {
        android.app.NotificationManager nm = getSystemService(android.app.NotificationManager.class);
        return nm != null && nm.isNotificationListenerAccessGranted(
            new android.content.ComponentName(this, MediaListenerService.class));
    }

    private boolean installGranted() {
        return getPackageManager().canRequestPackageInstalls();
    }

    private boolean overlayGranted() {
        return Settings.canDrawOverlays(this);
    }

    private boolean allGranted() {
        return bluetoothGranted() && notificationAccessGranted() && installGranted()
            && overlayGranted();
    }

    /** Ask for the next missing grant not yet asked in this flow; false when none is left. */
    private boolean nextGrant() {
        if (!bluetoothGranted() && asked.add("bluetooth")) {
            requestPermissions(new String[] {"android.permission.BLUETOOTH_CONNECT"}, 1);
            return true;
        }
        if (!postNotificationsGranted() && asked.add("post_notifications")) {
            requestPermissions(new String[] {"android.permission.POST_NOTIFICATIONS"}, 2);
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
        if (!overlayGranted() && asked.add("overlay")) {
            awaitingSettings = true;
            openOverlayPermission();
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

    /** Settings → Display over other apps, for on-screen notifications and dashboards. */
    private void openOverlayPermission() {
        try {
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + getPackageName())));
        } catch (Exception e) {
            android.util.Log.w("BraviaAgent", "overlay permission settings: " + e);
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
     * OK: grant whatever is missing. Hold LEFT: forget the pairing (it is
     * started again from Home Assistant). BACK closes. (Listed on screen.)
     * Nothing on this screen starts a pairing: a client asks for it, and the
     * viewer answers here.
     */
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
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
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
            // Track the press so a hold reaches onKeyLongPress. (The long-press
            // flag only ever arrives on a repeat, never on the first press.)
            if (event.getRepeatCount() == 0) event.startTracking();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    /** Holding LEFT clears the pairing. */
    @Override
    public boolean onKeyLongPress(int keyCode, KeyEvent event) {
        AgentService svc = AgentService.instance();
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT && svc != null) {
            svc.pairing().clear();
            render();
            toast("Pairing cleared");
            return true;
        }
        return super.onKeyLongPress(keyCode, event);
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
        // Nobody is looking: the question comes back with the screen.
        dismissPrompt();
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
        versionLine = label("Version " + Version.of(this), 16, DIM_COLOR);
        root.addView(versionLine);
        root.addView(label(DISCLAIMER, 16, DIM_COLOR));

        rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        rows.setPadding(0, dp(28), 0, dp(28));
        root.addView(rows);

        // Remote buttons: one per line, aligned with the status rows but
        // smaller and grey, so they read as instructions, not status.
        keys = new LinearLayout(this);
        keys.setOrientation(LinearLayout.VERTICAL);
        root.addView(keys);
        setContentView(root);
    }

    private void render() {
        if (rows == null) return;
        AgentService svc = AgentService.instance();
        rows.removeAllViews();
        if (svc == null) {
            row("Status", "Starting…", DIM_COLOR);
        } else {
            // Always with the name and address of the other side: a pairing
            // that is not Home Assistant's should be plain to see here.
            Pairing pairing = svc.pairing();
            PairingState.Request asking = pairing.pending();
            if (asking != null) {
                // The news while it lasts; on its own, so the line stays short.
                row("Home Assistant", "Pairing requested by " + who(asking.name, asking.peer), TODO_COLOR);
            } else if (!pairing.isPaired()) {
                row("Home Assistant", "Not paired", BAD_COLOR);
            } else {
                String paired = who(pairing.pairedName(), pairing.pairedPeer());
                if (svc.store().hasListeners()) {
                    row("Home Assistant", "Connected: " + paired, OK_COLOR);
                } else {
                    row("Home Assistant", "Paired with " + paired + ", waiting for it to connect", TODO_COLOR);
                }
            }
        }
        grantRow("Now playing", notificationAccessGranted(), "Needs Notification access");
        grantRow("Remote battery", bluetoothGranted(), "Needs Bluetooth permission");
        grantRow("Updates", installGranted(), "Needs \"Install unknown apps\"");
        grantRow("On-screen messages and dashboards", overlayGranted(), "Needs \"Display over other apps\"");
        if (svc != null) {
            JSONObject rest = svc.store().snapshot().optJSONObject("rest");
            if (rest == null) {
                row("Picture settings", "Checking…", DIM_COLOR);
            } else if (!rest.optBoolean("available")) {
                row("Picture settings", "Unavailable (IP control is off)", BAD_COLOR);
            } else if (rest.optBoolean("write_authorized")) {
                row("Picture settings", "Read and change", OK_COLOR);
            } else if (!"none".equals(rest.optString("auth", "none"))) {
                row("Picture settings", "Key rejected by the TV", BAD_COLOR);
            } else {
                // Reading needs no key; changing them needs the IP control
                // pre-shared key, entered in Home Assistant.
                row("Picture settings", "Read only (no key)", TODO_COLOR);
            }
        }
        String ip = ownIpv4();
        versionLine.setText("Version " + Version.of(this) + (ip == null ? "" : "  ·  " + ip));
        keys.removeAllViews();
        if (!allGranted()) keyRow("OK", "Grant missing permissions");
        keyRow("Hold LEFT", "Forget the pairing");
        keyRow("BACK", "Close");
        maybePrompt(svc);
    }

    private void keyRow(String button, String action) {
        LinearLayout line = new LinearLayout(this);
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setPadding(0, dp(3), 0, dp(3));
        TextView k = label(button, 17, DIM_COLOR);
        k.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        k.setLayoutParams(new LinearLayout.LayoutParams(dp(260), LinearLayout.LayoutParams.WRAP_CONTENT));
        line.addView(k);
        line.addView(label(action, 17, DIM_COLOR));
        keys.addView(line);
    }

    private void grantRow(String name, boolean granted, String missing) {
        row(name, granted ? "OK" : missing, granted ? OK_COLOR : BAD_COLOR);
    }

    private void row(String name, String value, int color) {
        LinearLayout line = new LinearLayout(this);
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setPadding(0, dp(6), 0, dp(6));
        TextView k = label(name, 20, Color.WHITE);
        k.setLayoutParams(new LinearLayout.LayoutParams(dp(260), LinearLayout.LayoutParams.WRAP_CONTENT));
        TextView v = label(value, 20, color);
        // One line whatever a client called itself: a value that wrapped
        // would push the rows below it off the screen.
        v.setSingleLine(true);
        v.setEllipsize(android.text.TextUtils.TruncateAt.END);
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
