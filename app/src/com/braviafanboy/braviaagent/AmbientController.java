package com.braviafanboy.braviaagent;

import android.content.Context;
import android.content.SharedPreferences;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import com.braviafanboy.braviaagent.ambient.AmbientCurve;
import com.braviafanboy.braviaagent.ambient.AmbientDecider;
import com.braviafanboy.braviaagent.ambient.LuxFloor;
import com.braviafanboy.braviaagent.readers.PictureReader;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Ambient brightness: sets the TV's Brightness slider from the room's light
 * level with a per-picture-mode curve (SDR only). Runs on the TV, so it keeps
 * working while Home Assistant is down; Home Assistant edits and mirrors it.
 *
 * <p>Each second: the light floor (low percentile of the sensor, see
 * {@link LuxFloor}) goes through {@link AmbientDecider} (deadbands, confirm
 * times, reversal hold), the current mode's {@link AmbientCurve} gives a
 * target, and the slider moves one step toward it with a quiet REST write.
 * Every 5 s the brightness, picture mode, the TV's own auto luminance switches
 * and the HDR state are read back. A brightness the loop didn't write means
 * someone changed it by hand: with learning off it turns ambient brightness
 * off until someone switches it back on; with learning on it learns the new
 * value as this mode's brightness for the current light, then holds for a
 * minute before resuming from the freshly learnt curve.
 *
 * <p>Off by default. It stops itself while the TV's Auto luminance level is
 * on (the two would fight), without the IP control key, while IP control is
 * unreachable, while the screen is off, and during HDR or Dolby Vision.
 */
public final class AmbientController implements SensorEventListener {
    private static final String TAG = "BraviaAgent";
    private static final String PREFS = "ambient";
    private static final long TICK_MS = 1000;
    private static final long CONTEXT_EVERY_MS = 5000;
    private static final long MANUAL_SETTLE_MS = 5000;  // settle before learning or switching off
    private static final int SAMPLING_US = 200_000;  // 5 Hz while active
    private static final List<String> CONTEXT_TARGETS = Arrays.asList(
        "brightness", "pictureMode", "sensorAutoAdjustment", "autoLuminanceLevel", "hdrToneMapping");

    private final StateStore store;
    private final PictureReader picture;
    private final SharedPreferences prefs;
    private final SensorManager sensors;
    private final Sensor light;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final LuxFloor floor = new LuxFloor();
    private volatile double latestLux = Double.NaN;
    private final AmbientDecider decider = new AmbientDecider();
    private final ScheduledExecutorService exec = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(() -> {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
            r.run();
        }, "ambient");
        t.setDaemon(true);
        return t;
    });

    // Guarded by this.
    private boolean enabled;
    private boolean learning;
    private AmbientCurve defaultCurve;
    private final Map<String, AmbientCurve> modeCurves = new LinkedHashMap<>();
    private boolean panelOn;
    private String signalHint;
    private ScheduledFuture<?> ticking;
    private boolean listening;

    // Loop state, touched only on the ambient thread.
    private long lastContextMs = Long.MIN_VALUE / 2;
    private JSONObject context;
    private String mode;
    private int lastWritten = -1;
    private int manualCandidate = -1;
    private long manualSince;
    private String state = "off";
    private String reason;
    private int target = -1;

    public AmbientController(Context context, StateStore store, PictureReader picture) {
        this.store = store;
        this.picture = picture;
        this.prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        this.sensors = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
        this.light = sensors == null ? null : sensors.getDefaultSensor(Sensor.TYPE_LIGHT);
        load();
    }

    // ---- lifecycle ----------------------------------------------------------

    /** The panel went on or off (standby, Picture Off). */
    public synchronized void onPanel(boolean on) {
        panelOn = on;
        reschedule();
    }

    public synchronized void shutdown() {
        panelOn = false;
        reschedule();
        exec.shutdownNow();
    }

    private synchronized void reschedule() {
        boolean run = enabled && panelOn && light != null;
        if (run && ticking == null) {
            ticking = exec.scheduleWithFixedDelay(this::safeTick, 0, TICK_MS, TimeUnit.MILLISECONDS);
        } else if (!run && ticking != null) {
            ticking.cancel(false);
            ticking = null;
            exec.execute(() -> {
                resetLoop();
                setState(enabled ? "paused" : "off", enabled ? (light == null ? "no_light_sensor" : "screen_off") : null);
            });
        }
        boolean listen = run;
        if (listen != listening) {
            listening = listen;
            main.post(() -> {
                if (listen) sensors.registerListener(this, light, SAMPLING_US);
                else {
                    sensors.unregisterListener(this);
                    floor.clear();
                    latestLux = Double.NaN;
                }
            });
        }
        if (!run && ticking == null) exec.execute(this::publish);
    }

    // ---- settings from Home Assistant ----------------------------------------

    public synchronized void setEnabled(boolean on) {
        if (on == enabled) return;
        enabled = on;
        prefs.edit().putBoolean("enabled", on).apply();
        Log.i(TAG, "ambient: " + (on ? "enabled" : "disabled"));
        reschedule();
    }

    public synchronized void setLearning(boolean on) {
        learning = on;
        prefs.edit().putBoolean("learning", on).apply();
        exec.execute(this::publish);
    }

    /** Set one mode's curve ({@code mode} null: the current picture mode, or the default before one is known). */
    public synchronized void setCurve(String forMode, int[] anchors) {
        AmbientCurve c = new AmbientCurve(anchors);
        String m = forMode != null ? forMode : mode;
        if (m == null) defaultCurve = c; else modeCurves.put(m, c);
        save();
        exec.execute(this::publish);
    }

    /** Drop a mode's own curve so it follows the default again. */
    public synchronized void resetCurve(String forMode) {
        String m = forMode != null ? forMode : mode;
        if (m != null) modeCurves.remove(m);
        save();
        exec.execute(this::publish);
    }

    /** Replace all curves: {"default": [6], "modes": {"<picture mode>": [6], ...}}. */
    public synchronized void setCurves(JSONObject curves) {
        JSONArray d = curves.optJSONArray("default");
        if (d != null) defaultCurve = new AmbientCurve(ints(d));
        JSONObject modes = curves.optJSONObject("modes");
        if (modes != null) {
            modeCurves.clear();
            Iterator<String> it = modes.keys();
            while (it.hasNext()) {
                String k = it.next();
                JSONArray a = modes.optJSONArray(k);
                if (a != null) modeCurves.put(k, new AmbientCurve(ints(a)));
            }
        }
        save();
        exec.execute(this::publish);
    }

    /** The video signal class from Home Assistant ("sdr", "hdr10", "dolby_vision", ...). */
    public synchronized void setSignal(String signal) {
        signalHint = signal == null || signal.isEmpty() ? null : signal.toLowerCase(java.util.Locale.ROOT);
    }

    // ---- the loop -----------------------------------------------------------

    /**
     * The light sensor is on-change: it reports only when the value changes,
     * so a steady room sends almost nothing. The latest reading is kept here
     * and sampled once a second by the loop, giving the floor a steady stream.
     */
    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() == Sensor.TYPE_LIGHT) latestLux = event.values[0];
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }

    private void safeTick() {
        try {
            tick();
        } catch (Exception e) {
            Log.w(TAG, "ambient: tick failed: " + e);
        }
    }

    private void tick() {
        long now = SystemClock.elapsedRealtime();
        double l = latestLux;
        if (Double.isNaN(l)) l = knownLux();
        if (!Double.isNaN(l)) floor.add(now, l);
        if (context == null || now - lastContextMs >= CONTEXT_EVERY_MS) {
            context = picture.readTargetsNow(CONTEXT_TARGETS);
            lastContextMs = now;
            if (context != null) onFreshContext(now);
        }
        boolean en;
        synchronized (this) { en = enabled; }
        if (!en) return;  // a manual change (learning off) may have just switched us off
        String why = blockedReason();
        if (why != null) {
            resetLoop();
            setState(why.equals("no_key") || why.equals("ip_control_unreachable") ? "unavailable" : "paused", why);
            return;
        }
        if (manualCandidate >= 0) {
            setState("paused", "manual");
            return;
        }
        double lux = floor.floor(now);
        if (Double.isNaN(lux)) {
            setState("active", "warming_up");
            return;
        }
        decider.update(now, lux);
        AmbientCurve c = curveFor(mode);
        target = c.target(decider.decidedLux());
        int next = decider.step(now, lastWritten, target);
        if (next >= 0) {
            // Before nudging the slider, confirm it is still where we left it.
            // Brightness is only read into `context` every 5 s, but we write up
            // to once a second, so a hand change between polls would otherwise be
            // fought (and, mid-ramp, overwritten before it is ever noticed). If
            // the slider has moved underneath us, treat it as a manual change
            // now: force a fresh context read so the normal handling runs next
            // tick, and don't write.
            int live = liveBrightness();
            if (live >= 0 && live != lastWritten) {
                context = null;
                lastContextMs = 0;
                setState("paused", "manual");
                return;
            }
            try {
                picture.writeTargetQuiet("brightness", String.valueOf(next));
                lastWritten = next;
            } catch (PictureReader.WriteException e) {
                Log.w(TAG, "ambient: brightness write refused: " + e.code);
                context = null;  // re-read before trying again
            }
        }
        setState("active", null);
    }

    /** Handle a fresh read of brightness / mode / switches: mode changes and manual changes. */
    private void onFreshContext(long now) {
        String newMode = value("pictureMode");
        int brightness = intValue("brightness");
        if (newMode != null && !newMode.equals(mode)) {
            synchronized (this) { mode = newMode; }
            lastWritten = brightness;
            manualCandidate = -1;
            decider.reset();
            synchronized (this) {
                if (defaultCurve == null && brightness >= 0) {
                    defaultCurve = AmbientCurve.fromCurrent(brightness);
                    save();
                }
            }
            return;
        }
        if (brightness < 0 || lastWritten < 0) {
            lastWritten = brightness;
            return;
        }
        if (brightness == lastWritten) {
            manualCandidate = -1;
            return;
        }
        // Someone else changed it. Track the value and wait for it to hold (the
        // remote steps through values; manualSince restarts on every change).
        // The loop stays paused while manualCandidate is set, so the viewer's
        // value is kept meanwhile.
        if (brightness != manualCandidate) {
            manualCandidate = brightness;
            manualSince = now;
            return;
        }
        // Act once the value has held for a short settle, so the remote's
        // step-through doesn't trip it. Same wait whether we learn or switch off.
        if (now - manualSince < MANUAL_SETTLE_MS) return;
        boolean learn;
        synchronized (this) { learn = learning; }
        double lux = floor.floor(now);
        lastWritten = brightness;
        manualCandidate = -1;
        if (!learn) {
            // Learning off: a hand change to the brightness turns ambient
            // brightness off until someone switches it back on. No auto-resume.
            Log.i(TAG, "ambient: disabled after a manual change to " + brightness);
            setEnabled(false);
            return;
        }
        // Learning on: take the new value as this mode's brightness for the
        // current light (learn it), then resume. No hold is needed: the curve
        // now matches this value at this light, so the decider sits still until
        // the room light actually changes, and then only after its confirm time
        // (8 s brightening, 20 s dimming) -- it won't nudge what the viewer set.
        if (!Double.isNaN(lux) && mode != null) {
            synchronized (this) {
                AmbientCurve before = curveFor(mode);
                AmbientCurve after = before.learn(lux, brightness);
                modeCurves.put(mode, after);
                save();
                Log.i(TAG, "ambient: learned " + brightness + " at " + Math.round(lux) + " lux for " + mode + ": " + before + " -> " + after);
            }
        }
        decider.reset();
        Log.i(TAG, "ambient: learnt a manual change to " + brightness + ", resuming");
    }

    /** Why the loop can't run now, or null. */
    private String blockedReason() {
        if (context == null || !picture.isAvailable()) return "ip_control_unreachable";
        if (!picture.writeAuthorized()) return "no_key";
        if ("on".equals(value("sensorAutoAdjustment")) && "on".equals(value("autoLuminanceLevel"))) return "tv_auto_luminance";
        String hint;
        synchronized (this) { hint = signalHint; }
        boolean hdr = (hint != null && !hint.equals("sdr")) || available("hdrToneMapping");
        if (hdr) return "hdr";
        if (intValue("brightness") < 0 || mode == null) return "ip_control_unreachable";
        return null;
    }

    private void resetLoop() {
        decider.reset();
        lastWritten = -1;
        manualCandidate = -1;
        target = -1;
    }

    // ---- state ----------------------------------------------------------------

    private synchronized AmbientCurve curveFor(String m) {
        AmbientCurve c = m == null ? null : modeCurves.get(m);
        if (c != null) return c;
        // Before the first brightness read there is no default yet: preview one
        // without storing it, so the real one is built from the user's brightness.
        return defaultCurve != null ? defaultCurve : AmbientCurve.fromCurrent(25);
    }

    private void setState(String s, String why) {
        state = s;
        reason = why;
        publish();
    }

    private void publish() {
        try {
            store.update("ambient", status());
        } catch (Exception e) {
            Log.w(TAG, "ambient: publish failed: " + e);
        }
    }

    /** The "ambient" state block (also GET /v1/ambient). */
    public synchronized JSONObject status() {
        JSONObject o = new JSONObject();
        try {
            o.put("enabled", enabled);
            o.put("learning", learning);
            o.put("state", enabled ? state : "off");
            o.put("reason", enabled && reason != null ? reason : JSONObject.NULL);
            o.put("picture_mode", mode != null ? mode : JSONObject.NULL);
            o.put("brightness", lastWritten >= 0 ? lastWritten : JSONObject.NULL);
            o.put("target", target >= 0 ? target : JSONObject.NULL);
            double d = decider.decidedLux();
            o.put("lux", Double.isNaN(d) ? JSONObject.NULL : Math.round(d));
            o.put("own_curve", mode != null && modeCurves.containsKey(mode));
            o.put("anchors_lux", luxAxis());
            o.put("curve", new JSONArray(curveFor(mode).values()));
            o.put("curves", curvesJson());
        } catch (Exception ignored) {
        }
        return o;
    }

    private JSONObject curvesJson() throws Exception {
        JSONObject c = new JSONObject();
        c.put("default", defaultCurve == null ? JSONObject.NULL : new JSONArray(defaultCurve.values()));
        JSONObject m = new JSONObject();
        for (Map.Entry<String, AmbientCurve> e : modeCurves.entrySet()) m.put(e.getKey(), new JSONArray(e.getValue().values()));
        c.put("modes", m);
        return c;
    }

    private static JSONArray luxAxis() {
        JSONArray a = new JSONArray();
        for (double l : AmbientCurve.ANCHOR_LUX) a.put((long) l);
        return a;
    }

    private synchronized void load() {
        enabled = prefs.getBoolean("enabled", false);
        learning = prefs.getBoolean("learning", false);
        try {
            String raw = prefs.getString("curves", null);
            if (raw != null) {
                JSONObject c = new JSONObject(raw);
                JSONArray d = c.optJSONArray("default");
                if (d != null) defaultCurve = new AmbientCurve(ints(d));
                JSONObject modes = c.optJSONObject("modes");
                if (modes != null) {
                    Iterator<String> it = modes.keys();
                    while (it.hasNext()) {
                        String k = it.next();
                        JSONArray a = modes.optJSONArray(k);
                        if (a != null) modeCurves.put(k, new AmbientCurve(ints(a)));
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "ambient: stored curves unreadable, starting fresh: " + e);
        }
    }

    private synchronized void save() {
        try {
            prefs.edit().putString("curves", curvesJson().toString()).apply();
        } catch (Exception e) {
            Log.w(TAG, "ambient: saving curves failed: " + e);
        }
    }

    // ---- helpers ----------------------------------------------------------------

    /** The agent's last published lux (seeds the loop before the sensor reports). */
    private double knownLux() {
        try {
            JSONObject env = store.snapshot().optJSONObject("environment");
            return env != null && env.has("illuminance_lx") ? env.getDouble("illuminance_lx") : Double.NaN;
        } catch (Exception e) {
            return Double.NaN;
        }
    }

    private String value(String target) {
        JSONObject t = context == null ? null : context.optJSONObject(target);
        return t == null ? null : t.optString("value", null);
    }

    private boolean available(String target) {
        JSONObject t = context == null ? null : context.optJSONObject(target);
        return t != null && t.optBoolean("available", false);
    }

    private int intValue(String target) {
        try {
            String v = value(target);
            return v == null ? -1 : (int) Math.round(Double.parseDouble(v));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** A fresh read of the Brightness slider straight from the TV (not the 5 s
     *  context cache), or -1. Used to catch a hand change before the loop writes
     *  so it never overwrites or fights one. */
    private int liveBrightness() {
        JSONObject now = picture.readTargetsNow(Arrays.asList("brightness"));
        if (now == null) return -1;
        JSONObject t = now.optJSONObject("brightness");
        if (t == null) return -1;
        try {
            String v = t.optString("value", null);
            return v == null ? -1 : (int) Math.round(Double.parseDouble(v));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    static int[] ints(JSONArray a) {
        int[] v = new int[a.length()];
        for (int i = 0; i < v.length; i++) v[i] = a.optInt(i);
        return v;
    }
}
