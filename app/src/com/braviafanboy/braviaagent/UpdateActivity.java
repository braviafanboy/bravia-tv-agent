package com.braviafanboy.braviaagent;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.util.TypedValue;
import android.widget.TextView;

/**
 * Runs the staged self-update in the foreground: downloads the APK, starts the
 * installer session and shows Android's confirmation, which must come from a
 * visible activity. Started only by {@link StatusActivity}, when the TV's
 * control service launches the app while an update is staged (the
 * integration's deep link {@code braviaagent://update}, or its local app
 * launch). It takes nothing from the intent that starts it: the update is the
 * one staged with {@code POST /v1/update}.
 */
public final class UpdateActivity extends Activity {
    private TextView text;
    private Updater updater;
    private final BroadcastReceiver status = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            int st = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
            if (st == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                Intent confirm = Compat.parcelableExtra(i, Intent.EXTRA_INTENT, Intent.class);
                if (confirm != null && fromTheInstaller(i, confirm)) startActivity(confirm);
                show("Confirm the update on screen.");
            } else if (st == PackageInstaller.STATUS_SUCCESS) {
                updater.clearPending();
                show("Updated.");
                finish();
            } else {
                // Declined, or refused by Android: it is no longer staged.
                updater.clearPending();
                show("Update failed: " + i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE));
            }
        }
    };

    /**
     * Whether a status broadcast with a confirmation to show is the package
     * installer's. From Android 13 the receiver is not exported, so nothing
     * else can reach it. Android 12 has no such flag: there the broadcast must
     * name this update's installer session, and the screen it asks for must
     * belong to the system.
     */
    private boolean fromTheInstaller(Intent broadcast, Intent confirm) {
        if (Build.VERSION.SDK_INT >= 33) return true;
        int session = broadcast.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1);
        if (session < 0 || session != Updater.sessionId()) return false;
        ResolveInfo target = getPackageManager().resolveActivity(confirm, 0);
        // Not resolvable (not visible to this app): the session id has to do.
        if (target == null || target.activityInfo == null) return true;
        return (target.activityInfo.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
    }

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
        if (updater.pendingUrl() == null) {
            show("No update pending.");
            return;
        }
        final String version = updater.pendingVersion();
        show("HA TV Agent update " + version + "\nDownloading…");
        new Thread(() -> {
            try {
                updater.run(m -> runOnUiThread(() -> show("HA TV Agent update " + version + "\n" + m)));
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
