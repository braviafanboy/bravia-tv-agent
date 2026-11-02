package com.braviafanboy.braviaagent.readers;

import android.content.Context;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.os.Handler;
import android.os.Looper;

import com.braviafanboy.braviaagent.StateStore;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;

/** HDMI (and other hardware) inputs with their connection state, event-driven. */
public final class InputsReader extends TvInputManager.TvInputCallback {
    private final Context context;
    private final TvInputManager manager;
    private final StateStore store;

    public InputsReader(Context context, StateStore store) {
        this.context = context;
        this.store = store;
        manager = (TvInputManager) context.getSystemService(Context.TV_INPUT_SERVICE);
    }

    public void start() {
        if (manager == null) {
            store.update("inputs", null);
            return;
        }
        manager.registerCallback(this, new Handler(Looper.getMainLooper()));
        publish();
    }

    public void stop() {
        if (manager != null) manager.unregisterCallback(this);
    }

    private void publish() {
        try {
            JSONArray arr = new JSONArray();
            List<TvInputInfo> inputs = manager.getTvInputList();
            for (TvInputInfo info : inputs) {
                if (info.getType() == TvInputInfo.TYPE_TUNER && !info.isPassthroughInput()) continue;
                JSONObject o = new JSONObject();
                String id = info.getId();
                o.put("id", id.substring(id.lastIndexOf('/') + 1));
                o.put("input_id", id);
                CharSequence label = info.loadLabel(context);
                o.put("label", label == null ? JSONObject.NULL : label.toString());
                o.put("type", typeName(info.getType()));
                int state = manager.getInputState(id);
                o.put("connected", state == TvInputManager.INPUT_STATE_CONNECTED);
                o.put("standby", state == TvInputManager.INPUT_STATE_CONNECTED_STANDBY);
                arr.put(o);
            }
            store.update("inputs", arr);
        } catch (Exception ignored) {
        }
    }

    private static String typeName(int type) {
        switch (type) {
            case TvInputInfo.TYPE_HDMI: return "hdmi";
            case TvInputInfo.TYPE_TUNER: return "tuner";
            case TvInputInfo.TYPE_COMPOSITE: return "composite";
            case TvInputInfo.TYPE_COMPONENT: return "component";
            case TvInputInfo.TYPE_DISPLAY_PORT: return "display_port";
            case TvInputInfo.TYPE_VGA: return "vga";
            default: return "other";
        }
    }

    @Override public void onInputStateChanged(String inputId, int state) { publish(); }
    @Override public void onInputAdded(String inputId) { publish(); }
    @Override public void onInputRemoved(String inputId) { publish(); }
    @Override public void onInputUpdated(String inputId) { publish(); }
}
