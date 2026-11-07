package com.braviafanboy.braviaagent;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONException;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

/**
 * The single JSON document the agent serves. Readers update their own block;
 * listeners (the event stream) are told after a short coalescing delay so a
 * burst of sensor samples becomes one event.
 */
public final class StateStore {
    public interface Listener {
        void onStateChanged(JSONObject snapshot);
    }

    private static final long COALESCE_MS = 500;
    private static final String[] BLOCKS = {
        "environment", "display", "media", "inputs", "audio", "bluetooth", "wifi", "settings",
        "rest", "picture", "power_saving", "dashboard"
    };

    private final Map<String, Object> blocks = new LinkedHashMap<>();
    private final List<Listener> listeners = new ArrayList<>();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final SimpleDateFormat iso = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
    private boolean notifyPending;
    private long revision;

    public StateStore() {
        iso.setTimeZone(TimeZone.getTimeZone("UTC"));
        for (String b : BLOCKS) blocks.put(b, JSONObject.NULL);
    }

    /** Replace a block. {@code value} may be a JSONObject, JSONArray or null. */
    public synchronized void update(String block, Object value) {
        Object current = blocks.get(block);
        Object next = value == null ? JSONObject.NULL : value;
        if (current != null && current.toString().equals(next.toString())) return;
        blocks.put(block, next);
        revision++;
        scheduleNotify();
    }

    public synchronized JSONObject snapshot() {
        JSONObject doc = new JSONObject();
        try {
            doc.put("ts", iso.format(new Date()));
            doc.put("revision", revision);
            for (Map.Entry<String, Object> e : blocks.entrySet()) doc.put(e.getKey(), e.getValue());
        } catch (JSONException ignored) {
        }
        return doc;
    }

    public synchronized void addListener(Listener l) {
        listeners.add(l);
    }

    public synchronized void removeListener(Listener l) {
        listeners.remove(l);
    }

    /** Whether anyone is consuming change events (used to skip busywork). */
    public synchronized boolean hasListeners() {
        return !listeners.isEmpty();
    }

    private void scheduleNotify() {
        if (notifyPending) return;
        notifyPending = true;
        handler.postDelayed(() -> {
            List<Listener> copy;
            JSONObject snap;
            synchronized (this) {
                notifyPending = false;
                copy = new ArrayList<>(listeners);
                snap = snapshot();
            }
            for (Listener l : copy) l.onStateChanged(snap);
        }, COALESCE_MS);
    }
}
