package com.braviafanboy.braviaagent.readers;

import android.content.Context;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;

import com.braviafanboy.braviaagent.StateStore;

import org.json.JSONArray;
import org.json.JSONObject;

/** Active output route, music-stream volume and mute. Volume is polled (no callback). */
public final class AudioReader extends AudioDeviceCallback {
    private static final long POLL_MS = 5000;

    private final AudioManager manager;
    private final StateStore store;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable poll = new Runnable() {
        @Override public void run() {
            publish();
            handler.postDelayed(this, POLL_MS);
        }
    };

    public AudioReader(Context context, StateStore store) {
        this.store = store;
        manager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
    }

    public void start() {
        manager.registerAudioDeviceCallback(this, handler);
        handler.post(poll);
    }

    public void stop() {
        handler.removeCallbacks(poll);
        manager.unregisterAudioDeviceCallback(this);
    }

    private void publish() {
        try {
            JSONObject o = new JSONObject();
            JSONArray outputs = new JSONArray();
            for (AudioDeviceInfo dev : manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
                JSONObject d = new JSONObject();
                d.put("type", typeName(dev.getType()));
                d.put("name", dev.getProductName() == null ? JSONObject.NULL : dev.getProductName().toString());
                outputs.put(d);
            }
            o.put("outputs", outputs);
            JSONObject vol = new JSONObject();
            vol.put("music", manager.getStreamVolume(AudioManager.STREAM_MUSIC));
            vol.put("max", manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
            o.put("volume", vol);
            o.put("muted", manager.isStreamMute(AudioManager.STREAM_MUSIC));
            o.put("mode", manager.getMode());
            store.update("audio", o);
        } catch (Exception ignored) {
        }
    }

    private static String typeName(int type) {
        switch (type) {
            case AudioDeviceInfo.TYPE_BUILTIN_SPEAKER: return "speaker";
            case AudioDeviceInfo.TYPE_HDMI: return "hdmi";
            case AudioDeviceInfo.TYPE_HDMI_ARC: return "hdmi_arc";
            case AudioDeviceInfo.TYPE_HDMI_EARC: return "hdmi_earc";
            case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP: return "bt_a2dp";
            case AudioDeviceInfo.TYPE_WIRED_HEADPHONES:
            case AudioDeviceInfo.TYPE_WIRED_HEADSET: return "headphone";
            case AudioDeviceInfo.TYPE_LINE_DIGITAL: return "spdif";
            default: return "type_" + type;
        }
    }

    @Override public void onAudioDevicesAdded(AudioDeviceInfo[] added) { publish(); }
    @Override public void onAudioDevicesRemoved(AudioDeviceInfo[] removed) { publish(); }
}
