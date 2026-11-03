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
import android.view.KeyEvent;
import android.util.TypedValue;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;

/** Shows what the agent serves, so a user can compare it with Home Assistant. */
public final class StatusActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView text;
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
            android.widget.Toast.makeText(this, "Home Assistant is pairing with the BRAVIA agent", android.widget.Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        text = new TextView(this);
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        text.setTextColor(Color.WHITE);
        text.setBackgroundColor(Color.BLACK);
        text.setPadding(48, 32, 48, 32);
        text.setTypeface(android.graphics.Typeface.MONOSPACE);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(text);
        setContentView(scroll);
        setupFlow = setupLink;
        if (checkSelfPermission("android.permission.BLUETOOTH_CONNECT") != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] {"android.permission.BLUETOOTH_CONNECT"}, 1);
        } else if (setupFlow) {
            continueSetup();
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (PairActivity.adoptPairLink(this, intent)) finish();
    }

    private boolean setupFlow;

    private boolean notificationAccessGranted() {
        android.app.NotificationManager nm = getSystemService(android.app.NotificationManager.class);
        return nm != null && nm.isNotificationListenerAccessGranted(
            new android.content.ComponentName(this, MediaListenerService.class));
    }

    /** Next missing grant in the setup flow: notification access, then installs. */
    private void continueSetup() {
        if (!notificationAccessGranted()) {
            openNotificationAccess();
        } else if (!getPackageManager().canRequestPackageInstalls()) {
            openInstallPermission();
        }
    }

    /** Settings → "Install unknown apps" for this app (needed for self-update). */
    private void openInstallPermission() {
        try {
            startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName())));
        } catch (Exception e) {
            android.util.Log.w("BraviaAgent", "install permission settings: " + e);
        }
    }

    /** Settings → Apps → Special app access → Notification access, directly. */
    private void openNotificationAccess() {
        try {
            startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
        } catch (Exception e) {
            android.util.Log.w("BraviaAgent", "notification access settings: " + e);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        AgentService svc = AgentService.instance();
        if (svc != null) svc.onPermissionsMaybeChanged();
        if (setupFlow) continueSetup();
    }

    /** OK / Select opens the Notification access screen for the media reader. */
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        AgentService svc = AgentService.instance();
        if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
            openInstallPermission();
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
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            openNotificationAccess();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.post(refresh);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(refresh);
        super.onPause();
    }

    private void render() {
        AgentService svc = AgentService.instance();

        StringBuilder sb = new StringBuilder();
        sb.append("BRAVIA agent ").append(AgentService.VERSION)
          .append("  —  https://<tv>:").append(AgentService.PORT).append("/v1/state\n")
          .append("OK: Notification access (now-playing)   DOWN: allow updates   RIGHT: pairing window   hold LEFT: unpair\n\n");
        if (svc == null) {
            sb.append("service starting…");
        } else {
            try {
                sb.append(svc.info().toString(2)).append("\n\n");
                JSONObject state = svc.store().snapshot();
                sb.append(state.toString(2));
            } catch (Exception e) {
                sb.append(e);
            }
        }
        text.setText(sb.toString());
    }
}
