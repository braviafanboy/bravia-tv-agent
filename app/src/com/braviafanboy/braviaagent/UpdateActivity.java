package com.braviafanboy.braviaagent;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInstaller;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.util.TypedValue;
import android.widget.TextView;

/**
 * Runs a pending self-update in the foreground: downloads the APK, starts the
 * installer session and shows Android's confirmation, which must come from a
 * visible activity. Launched by the integration (deep link
 * {@code braviaagent://update}, or the local app launch when an update is
 * pending). A {@code ?url=&sha256=&version=} query sets the pending update
 * directly.
 */
public final class UpdateActivity extends Activity {
    private TextView text;
    private Updater updater;
    private final BroadcastReceiver status = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            int st = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
            if (st == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                Intent confirm = i.getParcelableExtra(Intent.EXTRA_INTENT, Intent.class);
                if (confirm != null) startActivity(confirm);
                show("Confirm the update on screen.");
            } else if (st == PackageInstaller.STATUS_SUCCESS) {
                updater.clearPending();
                show("Updated.");
                finish();
            } else {
                show("Update failed: " + i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE));
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        updater = new Updater(this);
        text = new TextView(this);
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        text.setTextColor(Color.WHITE);
        text.setBackgroundColor(Color.BLACK);
        text.setPadding(64, 48, 64, 48);
        setContentView(text);
        registerReceiver(status, new IntentFilter(Updater.ACTION_STATUS), Context.RECEIVER_NOT_EXPORTED);
        Uri data = getIntent() == null ? null : getIntent().getData();
        if (data != null && data.getQueryParameter("url") != null) {
            updater.setPending(data.getQueryParameter("url"), data.getQueryParameter("sha256"), data.getQueryParameter("version"));
        }
        if (updater.pendingUrl() == null) {
            show("No update pending.");
            return;
        }
        show("BRAVIA agent update " + updater.pendingVersion() + "\nDownloading…");
        new Thread(() -> {
            try {
                updater.run(m -> runOnUiThread(() -> show("BRAVIA agent update " + updater.pendingVersion() + "\n" + m)));
            } catch (Exception e) {
                runOnUiThread(() -> show("Update failed: " + e.getMessage()));
            }
        }, "agent-update").start();
    }

    @Override
    protected void onDestroy() {
        try { unregisterReceiver(status); } catch (Exception ignored) { }
        super.onDestroy();
    }

    private void show(String s) {
        text.setText(s);
    }
}
