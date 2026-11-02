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
        Uri referrer = getReferrer();
        if (referrer != null) {
            // The pairing design keys off this: a launch through DeviceControlService
            // (the integration's gRPC app-launch) has a system referrer, not the launcher.
            android.util.Log.i("BraviaAgent", "launched with referrer " + referrer);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        AgentService svc = AgentService.instance();
        if (svc != null) svc.onPermissionsMaybeChanged();
    }

    /** OK / Select opens the Notification access screen for the media reader. */
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
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
        StringBuilder sb = new StringBuilder();
        sb.append("BRAVIA agent ").append(AgentService.VERSION)
          .append("  —  http://<tv>:").append(AgentService.PORT).append("/v1/state\n")
          .append("Press OK to open Notification access (needed for now-playing).\n\n");
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
