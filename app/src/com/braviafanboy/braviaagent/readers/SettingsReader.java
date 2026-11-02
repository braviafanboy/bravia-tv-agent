package com.braviafanboy.braviaagent.readers;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import com.braviafanboy.braviaagent.StateStore;

import org.json.JSONObject;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Selected keys from Sony's global settings provider (read permission is
 * normal-level). Only keys with a known meaning are reported; see the plan.
 */
public final class SettingsReader {
    private static final Uri GLOBAL = Uri.parse("content://com.sony.dtv.settingsprovider/global");
    private static final long POLL_MS = 60000;
    static final Set<String> KEYS = new HashSet<>(Arrays.asList(
        "power.power_saving", "power.idle_tv_standby.duration", "power.auto_shut_off",
        "network.remotestart",
        "braviasync.cec", "braviasync.autodevicesoff", "braviasync.autotvon",
        "braviasync.devicekeycontrol", "braviasync.remotecontrol",
        "led.voice_led_enabled", "led.operational_response",
        "parental.apprestriction", "parental.timerestriction",
        "parental.timerestriction.repeat", "parental.timerestriction.timeslot",
        "parental.timerestriction.usagetimelimit",
        "shop.demomode", "vendorprotocol.ssip", "vendorprotocol.sddp",
        "auto_swupdate", "timers.clock_display", "audio.bt_video_delay",
        "content_mode.hdmi_info_sync", "country", "locale.countrycode"
    ));

    private final Context context;
    private final StateStore store;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable poll = new Runnable() {
        @Override public void run() {
            publish();
            handler.postDelayed(this, POLL_MS);
        }
    };

    public SettingsReader(Context context, StateStore store) {
        this.context = context;
        this.store = store;
    }

    public void start() {
        handler.post(poll);
    }

    public void stop() {
        handler.removeCallbacks(poll);
    }

    private void publish() {
        new Thread(() -> {
            JSONObject o = new JSONObject();
            try (Cursor c = context.getContentResolver().query(GLOBAL, null, null, null, null)) {
                if (c == null) {
                    store.update("settings", null);
                    return;
                }
                int ki = c.getColumnIndex("key"), vi = c.getColumnIndex("value");
                while (c.moveToNext()) {
                    String key = c.getString(ki);
                    if (KEYS.contains(key)) o.put(key, c.isNull(vi) ? JSONObject.NULL : c.getString(vi));
                }
                store.update("settings", o);
            } catch (Exception e) {
                store.update("settings", null);
            }
        }, "settings-reader").start();
    }
}
