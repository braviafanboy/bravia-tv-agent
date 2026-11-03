package com.braviafanboy.braviaagent.readers;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.braviafanboy.braviaagent.RestAuth;
import com.braviafanboy.braviaagent.RestClient;
import com.braviafanboy.braviaagent.StateStore;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Picture-quality settings and the power-saving mode over the TV's REST
 * IP-control API: reads (no credential) and writes (with the {@link RestAuth}
 * credential).
 *
 * <p>Nothing on the TV announces a change to an ordinary app, so the reader
 * polls while the panel is on, at the interval the integration sets
 * ({@code poll_s}, default 60 s). Below 60 s a tick reads only the watched
 * targets (one call each, ~6 ms of webapi CPU) and a full read (~110 ms)
 * happens once a minute; at 60 s and above every tick is a full read. With
 * {@code event_refresh} on, an HDR change triggers a full read; the
 * integration adds refreshes for what only gRPC sees (picture mode, input,
 * content mode). The agent always re-reads after its own writes.
 *
 * <p>Publishes {@code rest} (reachability, auth, refresh settings),
 * {@code picture} (every target as the TV reports it) and {@code power_saving}.
 */
public final class PictureReader {
    private static final String TAG = "BraviaAgent";
    private static final String PREFS = "picture";
    public static final int DEFAULT_POLL_S = 60;
    public static final int MIN_POLL_S = 10;
    public static final int MAX_POLL_S = 3600;
    private static final long FULL_EVERY_MS = 60000;
    /** Shorter retry while the API is not answering (e.g. just after wake). */
    private static final long RETRY_MS = 60000;
    private static final Set<String> POWER_SAVING_MODES = new LinkedHashSet<>(Arrays.asList("off", "low", "high"));

    /** Why a write was refused. */
    public static final class WriteException extends Exception {
        public final String code;
        WriteException(String code) {
            super(code);
            this.code = code;
        }
    }

    private final StateStore store;
    private final RestClient rest;
    private final RestAuth auth;
    private final SharedPreferences prefs;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(() -> {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
            r.run();
        }, "picture-reader");
        t.setDaemon(true);
        return t;
    });
    private final AtomicBoolean fullQueued = new AtomicBoolean();
    private final SimpleDateFormat iso = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
    private boolean running;
    private String lastSummary;

    // Guarded by this.
    private JSONObject targets;  // last known targets, merged by partial reads
    private String powerSaving;
    private boolean available;
    private long lastFullMs;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            boolean full = pollSeconds() >= 60 || System.currentTimeMillis() - lastFullMs >= FULL_EVERY_MS || targets == null;
            if (full) refresh(); else enqueuePartial();
        }
    };

    public PictureReader(Context context, StateStore store, RestClient rest, RestAuth auth) {
        this.store = store;
        this.rest = rest;
        this.auth = auth;
        this.prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        iso.setTimeZone(TimeZone.getTimeZone("UTC"));
    }

    public void start() {
        running = true;
        handler.removeCallbacks(tick);
        refresh();
    }

    public void stop() {
        running = false;
        handler.removeCallbacks(tick);
    }

    // ---- settings set by the integration ---------------------------------

    public int pollSeconds() {
        return prefs.getInt("poll_s", DEFAULT_POLL_S);
    }

    public boolean eventRefresh() {
        return prefs.getBoolean("event_refresh", true);
    }

    public List<String> watched() {
        String s = prefs.getString("watch", "");
        List<String> out = new ArrayList<>();
        for (String t : s.split(",")) if (!t.trim().isEmpty()) out.add(t.trim());
        return out;
    }

    /** Store the integration's settings; returns the effective (clamped) poll interval. */
    public int configure(Integer pollS, Boolean eventRefresh, List<String> watch) {
        SharedPreferences.Editor e = prefs.edit();
        int effective = pollSeconds();
        if (pollS != null) {
            effective = Math.max(MIN_POLL_S, Math.min(MAX_POLL_S, pollS));
            e.putInt("poll_s", effective);
        }
        if (eventRefresh != null) e.putBoolean("event_refresh", eventRefresh);
        if (watch != null) {
            StringBuilder sb = new StringBuilder();
            for (String t : watch) {
                if (t == null || t.trim().isEmpty() || t.contains(",")) continue;
                if (sb.length() > 0) sb.append(',');
                sb.append(t.trim());
            }
            e.putString("watch", sb.toString());
        }
        e.apply();
        Log.i(TAG, "picture: config poll_s=" + effective + " event_refresh=" + eventRefresh() + " watch=" + watched().size());
        publish();
        // Apply the new interval from now.
        handler.post(() -> { if (running) reschedule(true); });
        return effective;
    }

    /** HDR started or stopped: the active picture setting set changes. */
    public void onHdrChanged() {
        if (eventRefresh()) refresh();
    }

    // ---- reads ------------------------------------------------------------

    /** Full read now (coalesced: one already queued covers this request). */
    public void refresh() {
        if (!fullQueued.compareAndSet(false, true)) return;
        worker.execute(() -> {
            fullQueued.set(false);
            boolean ok = readFull();
            auth.renewIfDue(rest);
            handler.post(() -> reschedule(ok));
        });
    }

    private void enqueuePartial() {
        worker.execute(() -> {
            boolean ok = readWatched();
            handler.post(() -> reschedule(ok));
        });
    }

    private void reschedule(boolean ok) {
        handler.removeCallbacks(tick);
        if (running) handler.postDelayed(tick, ok ? pollSeconds() * 1000L : RETRY_MS);
    }

    private boolean readFull() {
        JSONObject t = null;
        String ps = null;
        boolean avail = false;
        try {
            JSONArray result = rest.call("video", "getPictureQualitySettings", "1.1", new JSONArray().put(new JSONObject()));
            t = targetsOf(result.optJSONArray(0));
            avail = true;
        } catch (RestClient.RestException e) {
            if (e.kind != RestClient.RestException.Kind.UNAVAILABLE) avail = true;
            Log.w(TAG, "picture: getPictureQualitySettings " + e.kind + " " + e.code);
        }
        if (avail) {
            try {
                JSONObject o = rest.call("system", "getPowerSavingMode", "1.0", new JSONArray()).optJSONObject(0);
                if (o != null) ps = o.optString("mode", null);
            } catch (RestClient.RestException e) {
                Log.w(TAG, "picture: getPowerSavingMode " + e.kind + " " + e.code);
            }
        }
        synchronized (this) {
            available = avail;
            targets = avail ? t : null;
            powerSaving = avail ? ps : null;
            if (avail) lastFullMs = System.currentTimeMillis();
        }
        String summary = avail
            ? (t == null ? 0 : t.length()) + " targets via " + rest.endpoint() + ", power_saving=" + ps
            : "unavailable";
        if (!summary.equals(lastSummary)) {
            Log.i(TAG, "picture: " + summary);
            lastSummary = summary;
        }
        publish();
        return avail;
    }

    /** Read only the watched targets, one call each, merging into the last full read. */
    private boolean readWatched() {
        List<String> watch = watched();
        boolean ok = true;
        for (String name : watch) {
            try {
                JSONArray r = rest.call("video", "getPictureQualitySettings", "1.1",
                    new JSONArray().put(new JSONObject().put("target", name)));
                JSONObject one = targetsOf(r.optJSONArray(0));
                synchronized (this) {
                    if (targets != null && one.has(name)) targets.put(name, one.get(name));
                }
            } catch (RestClient.RestException e) {
                if (e.kind == RestClient.RestException.Kind.UNAVAILABLE) { ok = false; break; }
            } catch (Exception ignored) {
            }
        }
        if (!ok) synchronized (this) { available = false; targets = null; powerSaving = null; }
        publish();
        return ok;
    }

    // ---- writes -----------------------------------------------------------

    /** Write one picture target after validating it against the last read. */
    public void writeTarget(String name, String value) throws WriteException {
        JSONObject t;
        synchronized (this) {
            if (!available || targets == null) throw new WriteException("rest_unavailable");
            t = targets.optJSONObject(name);
        }
        if (t == null) throw new WriteException("unknown_target");
        if (!t.optBoolean("available", false)) throw new WriteException("target_unavailable");
        if (!valid(t.optJSONArray("candidates"), value)) throw new WriteException("invalid_value");
        JSONArray params = new JSONArray();
        try {
            params.put(new JSONObject().put("settings",
                new JSONArray().put(new JSONObject().put("target", name).put("value", value))));
        } catch (Exception ignored) {
        }
        authorizedCall("video", "setPictureQualitySettings", "1.0", params);
        Log.i(TAG, "picture: wrote " + name);
        refresh();
    }

    public void writePowerSaving(String mode) throws WriteException {
        if (!POWER_SAVING_MODES.contains(mode)) throw new WriteException("invalid_value");
        JSONArray params = new JSONArray();
        try { params.put(new JSONObject().put("mode", mode)); } catch (Exception ignored) { }
        authorizedCall("system", "setPowerSavingMode", "1.0", params);
        Log.i(TAG, "picture: wrote power_saving");
        refresh();
    }

    /** A credentialled call; on a rejected cookie, renew once and retry. */
    private void authorizedCall(String service, String method, String version, JSONArray params) throws WriteException {
        Map<String, String> h = auth.headers();
        if (h.isEmpty()) throw new WriteException("rest_unauthorized");
        try {
            rest.exchange(service, method, version, params, h);
            auth.markAuthorized(true);
            publish();
            return;
        } catch (RestClient.RestException e) {
            if (e.kind == RestClient.RestException.Kind.UNAVAILABLE) throw new WriteException("rest_unavailable");
            if (e.kind != RestClient.RestException.Kind.AUTH_REQUIRED) {
                Log.w(TAG, "picture: " + method + " " + e.kind + " " + e.code);
                throw new WriteException("rest_error");
            }
        }
        if ("cookie".equals(auth.mode()) && auth.renew(rest)) {
            try {
                rest.exchange(service, method, version, params, auth.headers());
                auth.markAuthorized(true);
                publish();
                return;
            } catch (RestClient.RestException e) {
                if (e.kind == RestClient.RestException.Kind.UNAVAILABLE) throw new WriteException("rest_unavailable");
            }
        }
        auth.markAuthorized(false);
        publish();
        throw new WriteException("rest_unauthorized");
    }

    /** Range candidates: integer within [min, max] on the step; value lists: one of them. */
    static boolean valid(JSONArray candidates, String value) {
        if (candidates == null || candidates.length() == 0 || value == null) return false;
        JSONObject first = candidates.optJSONObject(0);
        if (first != null && first.has("min") && first.has("max")) {
            try {
                double v = Double.parseDouble(value);
                double min = first.getDouble("min"), max = first.getDouble("max"), step = first.optDouble("step", 1);
                if (v < min || v > max) return false;
                double k = (v - min) / step;
                return Math.abs(k - Math.round(k)) < 1e-6;
            } catch (Exception e) {
                return false;
            }
        }
        for (int i = 0; i < candidates.length(); i++) {
            JSONObject c = candidates.optJSONObject(i);
            if (c != null && value.equals(c.optString("value", null))) return true;
        }
        return false;
    }

    // ---- state ------------------------------------------------------------

    /** Publish the three blocks from the current fields. */
    public void publish() {
        JSONObject restBlock = new JSONObject();
        Object picture;
        Object ps;
        synchronized (this) {
            try {
                restBlock.put("available", available);
                restBlock.put("scheme", available && rest.scheme() != null ? rest.scheme() : JSONObject.NULL);
                restBlock.put("auth", auth.mode());
                restBlock.put("write_authorized", auth.writeAuthorized());
                long exp = auth.cookieExpiresMs();
                restBlock.put("cookie_expires", exp > 0 ? iso.format(new Date(exp)) : JSONObject.NULL);
                restBlock.put("poll_s", pollSeconds());
                restBlock.put("event_refresh", eventRefresh());
                restBlock.put("watch", new JSONArray(watched()));
            } catch (Exception ignored) {
            }
            JSONObject p = null;
            if (targets != null) {
                try {
                    p = new JSONObject().put("targets", new JSONObject(targets.toString()));
                } catch (Exception ignored) {
                }
            }
            picture = p;
            ps = powerSaving;
        }
        store.update("rest", restBlock);
        store.update("picture", picture);
        store.update("power_saving", ps);
    }

    /** {name: {value, available, candidates}} as the TV reports them. */
    static JSONObject targetsOf(JSONArray settings) {
        JSONObject out = new JSONObject();
        if (settings == null) return out;
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
                out.put(name, t);
            } catch (Exception ignored) {
            }
        }
        return out;
    }
}
