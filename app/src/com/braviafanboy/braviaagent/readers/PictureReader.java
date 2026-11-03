package com.braviafanboy.braviaagent.readers;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.braviafanboy.braviaagent.RestClient;
import com.braviafanboy.braviaagent.StateStore;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Picture-quality settings and the power-saving mode from the TV's REST
 * IP-control API. These reads need no credential. Nothing on the TV announces
 * a change to an ordinary app, so the reader polls while the panel is on and
 * re-reads on request (the integration asks when gRPC reports a picture mode,
 * input or content mode change, since picture values are per mode).
 *
 * <p>Publishes three blocks: {@code rest} (reachability and auth status),
 * {@code picture} (every target as the TV reports it) and
 * {@code power_saving}.
 */
public final class PictureReader {
    private static final String TAG = "BraviaAgent";
    private static final long POLL_MS = 5 * 60000;
    /** Shorter retry while the API is not answering (e.g. just after wake). */
    private static final long RETRY_MS = 60000;

    private final StateStore store;
    private final RestClient rest;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(() -> {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
            r.run();
        }, "picture-reader");
        t.setDaemon(true);
        return t;
    });
    private final AtomicBoolean queued = new AtomicBoolean();
    private boolean running;
    private String lastSummary;

    private final Runnable poll = new Runnable() {
        @Override public void run() {
            refresh();
        }
    };

    public PictureReader(StateStore store, RestClient rest) {
        this.store = store;
        this.rest = rest;
    }

    public void start() {
        running = true;
        handler.removeCallbacks(poll);
        handler.post(poll);
    }

    public void stop() {
        running = false;
        handler.removeCallbacks(poll);
    }

    /** Read now (coalesced: a read already queued covers this request). */
    public void refresh() {
        if (!queued.compareAndSet(false, true)) return;
        worker.execute(() -> {
            queued.set(false);
            boolean ok = read();
            handler.post(() -> {
                handler.removeCallbacks(poll);
                if (running) handler.postDelayed(poll, ok ? POLL_MS : RETRY_MS);
            });
        });
    }

    private boolean read() {
        JSONObject picture = null;
        String powerSaving = null;
        boolean available = false;
        try {
            JSONArray result = rest.call("video", "getPictureQualitySettings", "1.1", new JSONArray().put(new JSONObject()));
            picture = pictureBlock(result.optJSONArray(0));
            available = true;
        } catch (RestClient.RestException e) {
            if (e.kind != RestClient.RestException.Kind.UNAVAILABLE) available = true;
            Log.w(TAG, "picture: getPictureQualitySettings " + e.kind + " " + e.code);
        }
        if (available) {
            try {
                JSONArray result = rest.call("system", "getPowerSavingMode", "1.0", new JSONArray());
                JSONObject o = result.optJSONObject(0);
                if (o != null) powerSaving = o.optString("mode", null);
            } catch (RestClient.RestException e) {
                Log.w(TAG, "picture: getPowerSavingMode " + e.kind + " " + e.code);
            }
        }
        String summary = available
            ? (picture == null ? 0 : picture.optJSONObject("targets").length()) + " targets via " + rest.endpoint() + ", power_saving=" + powerSaving
            : "unavailable";
        if (!summary.equals(lastSummary)) {
            Log.i(TAG, "picture: " + summary);
            lastSummary = summary;
        }
        store.update("rest", restBlock(available));
        store.update("picture", picture);
        store.update("power_saving", powerSaving);
        return available;
    }

    private JSONObject restBlock(boolean available) {
        JSONObject o = new JSONObject();
        try {
            o.put("available", available);
            o.put("scheme", available && rest.scheme() != null ? rest.scheme() : JSONObject.NULL);
            o.put("auth", "none");
            o.put("write_authorized", false);
            o.put("cookie_expires", JSONObject.NULL);
        } catch (Exception ignored) {
        }
        return o;
    }

    /** {@code {"targets": {name: {value, available, candidates}}}}, as the TV reports them. */
    static JSONObject pictureBlock(JSONArray settings) {
        JSONObject targets = new JSONObject();
        if (settings != null) {
            for (int i = 0; i < settings.length(); i++) {
                JSONObject s = settings.optJSONObject(i);
                if (s == null) continue;
                String name = s.optString("target", "");
                if (name.isEmpty()) continue;
                try {
                    JSONObject t = new JSONObject();
                    t.put("value", s.has("currentValue") ? s.optString("currentValue") : JSONObject.NULL);
                    t.put("available", s.optBoolean("isAvailable", true));
                    JSONArray cand = s.optJSONArray("candidate");
                    t.put("candidates", cand == null ? new JSONArray() : cand);
                    targets.put(name, t);
                } catch (Exception ignored) {
                }
            }
        }
        JSONObject o = new JSONObject();
        try { o.put("targets", targets); } catch (Exception ignored) { }
        return o;
    }
}
