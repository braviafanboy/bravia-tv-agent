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
    private boolean launchedForPairing;
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
        if (PairActivity.adoptPairLink(this, getIntent())) {
            // Sony's deep link lands on the main activity with the URI attached,
            // not on the VIEW handler: take the token and leave without drawing.
            android.util.Log.i("BraviaAgent", "pair link via main activity from " + getReferrer());
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
        if (checkSelfPermission("android.permission.BLUETOOTH_CONNECT") != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] {"android.permission.BLUETOOTH_CONNECT"}, 1);
        }
        handleLaunch(getIntent(), getReferrer());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleLaunch(intent, getReferrer());
    }

    /**
     * A launch by one of Sony's control services (the integration's local gRPC
     * app launch; the referrer is then a com.sony.* package, not the launcher
     * or the shell) opens the 60 s pairing window; the screen closes itself
     * when the window ends. The cloud deep link goes to PairActivity instead.
     */
    private void handleLaunch(Intent intent, Uri referrer) {
        android.util.Log.i("BraviaAgent", "launched with referrer " + referrer + " data " + (intent == null ? null : intent.getData()));
        // Wait for the service to exist, then act.
        handler.postDelayed(() -> {
            AgentService svc = AgentService.instance();
            if (svc == null) return;
            String host = referrer == null ? null : referrer.getHost();
            if (host != null && host.startsWith("com.sony.")) {
                // Launched by the integration (through DeviceControlService): open
                // the pairing window and get out of the way once it is used.
                launchedForPairing = true;
                svc.pairing().openWindow();
            }
        }, 300);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        AgentService svc = AgentService.instance();
        if (svc != null) svc.onPermissionsMaybeChanged();
    }

    /** OK / Select opens the Notification access screen for the media reader. */
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        AgentService svc = AgentService.instance();
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
            try {
                startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
            } catch (Exception e) {
                android.util.Log.w("BraviaAgent", "notification access settings: " + e);
            }
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
        if (launchedForPairing && svc != null && !svc.pairing().windowOpen()) {
            launchedForPairing = false;
            finish();
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("BRAVIA agent ").append(AgentService.VERSION)
          .append("  —  https://<tv>:").append(AgentService.PORT).append("/v1/state\n")
          .append("OK: Notification access (now-playing)   RIGHT: open pairing window   hold LEFT: unpair\n\n");
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
