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
import com.braviafanboy.braviaagent.ambient.CurveBook;
import com.braviafanboy.braviaagent.ambient.EnabledModes;
import com.braviafanboy.braviaagent.ambient.LuxFloor;
import com.braviafanboy.braviaagent.ambient.SignalHint;
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
    /** What the read before a write answers when the TV has changed picture mode. */
    private static final int MODE_CHANGED = -2;
    private static final long MANUAL_SETTLE_MS = 5000;  // settle before learning or switching off
    private static final long WRITE_RETRY_MS = 10000;  // after the TV refused a write
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
    /** The picture modes it is switched on in. */
    private final EnabledModes switched = new EnabledModes();
    /**
     * The picture mode as the picture reader last saw it. While the loop is
     * not running this is all that is known of the mode, and what starts the
     * loop again when the TV is switched to a mode it is on in.
     */
    private String tvMode;
    private boolean learning;
    /** The curves: each mode's own, a shared one, and each mode's start. */
    private final CurveBook book = new CurveBook();
    private boolean panelOn;
    private final SignalHint signal = new SignalHint();
    private ScheduledFuture<?> ticking;
    private boolean listening;

    // Loop state, touched only on the ambient thread.
    private volatile long lastContextMs = Long.MIN_VALUE / 2;
    private JSONObject context;
    private String mode;
    /** Treat the next read of the TV as a new start in its mode (it was just switched on there). */
    private volatile boolean restart;
    private int lastWritten = -1;
    private long holdWritesUntil;
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
        // It runs only in a picture mode it is on in (or to find out which
        // mode the TV is in, when it was just switched on). In other modes
        // nothing runs: the picture reader says when the mode changes.
        final boolean wanted = switched.pending() || switched.isOn(ticking != null ? mode : tvMode);
        boolean run = wanted && panelOn && light != null;
        if (run && ticking == null) {
            // Whatever was known of the TV is old: begin with a fresh read.
            restart = true;
            lastContextMs = 0;
            ticking = exec.scheduleWithFixedDelay(this::safeTick, 0, TICK_MS, TimeUnit.MILLISECONDS);
        } else if (!run) {
            if (ticking != null) {
                ticking.cancel(false);
                ticking = null;
            }
            // Say why it is not running, also when it never was (a TV
            // without a light sensor used to report a bare "off").
            final boolean sensor = light != null;
            exec.execute(() -> {
                resetLoop();
                if (!wanted) setState("off", null);
                else if (!sensor) setState("unavailable", "no_light_sensor");
                else setState("paused", "screen_off");
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
    }

    // ---- settings from Home Assistant ----------------------------------------

    /**
     * Switch it on or off in a picture mode; {@code forMode} null means the
     * mode the TV is in. Which mode that is, is read from the TV when it is
     * switched on (the loop's next read), not taken from an older reading.
     */
    public synchronized void setEnabled(String forMode, boolean on) {
        boolean changed;
        // The mode the TV is in: the loop's own reading while it runs, else
        // the picture reader's, which can be a minute old.
        String current = ticking != null ? mode : tvMode;
        if (forMode != null) {
            changed = switched.set(forMode, on);
        } else if (on) {
            // Unless the loop already runs in a mode it is on in, let the
            // loop's first read say which mode this is for.
            changed = !(ticking != null && switched.isOn(mode)) && switched.set(null, true);
        } else {
            changed = switched.set(null, false) | (current != null && switched.set(current, false));
        }
        if (!changed) return;
        saveEnabled();
        Log.i(TAG, "ambient: " + (on ? "enabled" : "disabled") + " for "
            + (forMode != null ? forMode : on ? "the current picture mode" : current));
        // Start afresh in the mode: read the TV now, and take what it shows
        // as the viewer's own.
        restart = true;
        lastContextMs = 0;
        reschedule();
        exec.execute(this::publish);
    }

    /** On in the mode the TV is in, or switched on and waiting for that mode to be read. */
    private synchronized boolean enabledNow() {
        return switched.isOn(mode) || switched.pending();
    }

    /**
     * The picture reader read the TV's picture settings: its regular read,
     * or one Home Assistant asked for because it saw the mode change. While
     * the loop is stopped, this is what starts it in a mode it is on in; while
     * it runs, a change makes it look at the TV at once.
     */
    public synchronized void onPictureMode(String pictureMode) {
        boolean changed = !pictureMode.equals(tvMode);
        tvMode = pictureMode;
        if (ticking != null) {
            if (!pictureMode.equals(mode)) {
                restart = true;
                lastContextMs = 0;
            }
            return;
        }
        // Stopped. Start in a mode it is on in; otherwise there is only the
        // other mode's setting to show.
        if (switched.pending() || switched.isOn(pictureMode)) reschedule();
        else if (changed) exec.execute(this::publish);
    }

    public synchronized void setLearning(boolean on) {
        learning = on;
        prefs.edit().putBoolean("learning", on).apply();
        exec.execute(this::publish);
    }

    /** Set one mode's curve ({@code mode} null: the current picture mode, or the shared curve before one is known). */
    public synchronized void setCurve(String forMode, int[] anchors) {
        AmbientCurve c = new AmbientCurve(anchors);
        book.set(forMode != null ? forMode : mode, c);
        save();
        exec.execute(this::publish);
    }

    /** Drop a mode's own curve so it follows the shared one, or its start, again. */
    public synchronized void resetCurve(String forMode) {
        book.reset(forMode != null ? forMode : mode);
        save();
        exec.execute(this::publish);
    }

    /**
     * Replace the curves a client manages: {"default": [6] or null, "modes":
     * {"<picture mode>": [6], ...}}. A "default" of null removes the shared
     * curve, so that each mode without its own follows its start; leaving
     * "default" out keeps what there is.
     */
    public synchronized void setCurves(JSONObject curves) {
        // Read all of it before changing anything: one bad curve must not
        // leave the others half replaced.
        JSONArray d = curves.optJSONArray("default");
        AmbientCurve newDefault = d == null ? null : new AmbientCurve(ints(d));
        Map<String, AmbientCurve> newModes = null;
        JSONObject modes = curves.optJSONObject("modes");
        if (modes != null) {
            newModes = new LinkedHashMap<>();
            Iterator<String> it = modes.keys();
            while (it.hasNext()) {
                String k = it.next();
                JSONArray a = modes.optJSONArray(k);
                if (a != null) newModes.put(k, new AmbientCurve(ints(a)));
            }
        }
        book.replace(newDefault, curves.has("default") && curves.isNull("default"), newModes);
        save();
        exec.execute(this::publish);
    }

    /** The video signal class from Home Assistant ("sdr", "hdr10", "dolby_vision", ...). */
    public void setSignal(String hint) {
        signal.set(hint, SystemClock.elapsedRealtime());
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
        // Home Assistant's word on HDR counts only while it is there.
        signal.presence(store.hasListeners(), now);
        // Also when the last read failed: an unreachable TV is asked again at
        // this pace, not every second.
        if (now - lastContextMs >= CONTEXT_EVERY_MS) {
            context = picture.readTargetsNow(CONTEXT_TARGETS);
            lastContextMs = now;
            if (context != null) onFreshContext(now);
        }
        if (!enabledNow()) {
            // Off in this picture mode (or a manual change, with learning
            // off, has just switched it off): leave the brightness alone.
            synchronized (this) {
                tvMode = mode;
                reschedule();  // stops the loop, and says "off"
            }
            return;
        }
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
        if (next >= 0 && now >= holdWritesUntil) {
            // Before nudging the slider, confirm it is still where we left it.
            // Brightness is only read into `context` every 5 s, but we write up
            // to once a second, so a hand change between polls would otherwise be
            // fought (and, mid-ramp, overwritten before it is ever noticed). If
            // the slider has moved underneath us, treat it as a manual change
            // now: force a fresh context read so the normal handling runs next
            // tick, and don't write.
            int live = liveBrightness();
            if (live == MODE_CHANGED) {
                // The TV is in another picture mode than the one this value
                // was worked out for: write nothing, look again.
                context = null;
                lastContextMs = 0;
                return;
            }
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
                // Read afresh, and leave the slider alone for a while rather
                // than asking the TV again every second.
                lastContextMs = 0;
                holdWritesUntil = now + WRITE_RETRY_MS;
            }
        }
        setState("active", null);
    }

    /** Handle a fresh read of brightness / mode / switches: mode changes and manual changes. */
    private void onFreshContext(long now) {
        String newMode = value("pictureMode");
        int brightness = intValue("brightness");
        if (newMode != null && (!newMode.equals(mode) || restart)) {
            restart = false;
            lastWritten = brightness;
            manualCandidate = -1;
            decider.reset();
            synchronized (this) {
                mode = newMode;
                // Switched on "in the mode the TV is in": this is it.
                if (switched.adopt(newMode)) saveEnabled();
                // Where it is on, nothing has been written since the switch
                // to this mode, so its brightness is still the viewer's own:
                // the start of this mode's curve.
                if (switched.isOn(newMode) && book.start(newMode, brightness)) {
                    save();
                    Log.i(TAG, "ambient: " + newMode + " starts from brightness " + brightness);
                }
            }
            exec.execute(this::publish);
            return;
        }
        // In a mode it is off in, the brightness is none of its business:
        // nothing to compare with what it wrote, and no hand change to learn.
        if (!enabledNow()) return;
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
            setEnabled(mode, false);
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
                book.set(mode, after);
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
        if (signal.hdr() || available("hdrToneMapping")) return "hdr";
        if (intValue("brightness") < 0 || mode == null) return "ip_control_unreachable";
        return null;
    }

    private void resetLoop() {
        decider.reset();
        lastWritten = -1;
        holdWritesUntil = 0;
        manualCandidate = -1;
        target = -1;
    }

    // ---- state ----------------------------------------------------------------

    private synchronized AmbientCurve curveFor(String m) {
        // A mode that has no curve yet shows what it would start from. The
        // TV's brightness says that only while ambient brightness is off:
        // once it runs, the brightness is its own doing.
        return book.curveFor(m, switched.isOn(m) ? -1 : knownBrightness());
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
            // On in the picture mode the TV is in (or just switched on, and
            // waiting for that mode to be read).
            String shown = ticking != null && mode != null ? mode : tvMode != null ? tvMode : mode;
            boolean enabled = switched.isOn(shown) || switched.pending();
            o.put("enabled", enabled);
            o.put("enabled_modes", new JSONArray(switched.modes()));
            o.put("learning", learning);
            o.put("state", enabled ? state : "off");
            o.put("reason", enabled && reason != null ? reason : JSONObject.NULL);
            o.put("picture_mode", shown != null ? shown : JSONObject.NULL);
            o.put("brightness", lastWritten >= 0 ? lastWritten : JSONObject.NULL);
            o.put("target", target >= 0 ? target : JSONObject.NULL);
            double d = decider.decidedLux();
            o.put("lux", Double.isNaN(d) ? JSONObject.NULL : Math.round(d));
            o.put("own_curve", book.hasOwn(shown));
            o.put("anchors_lux", luxAxis());
            o.put("curve", new JSONArray(curveFor(shown).values()));
            o.put("curves", curvesJson());
        } catch (Exception ignored) {
        }
        return o;
    }

    private JSONObject curvesJson() throws Exception {
        JSONObject c = new JSONObject();
        AmbientCurve shared = book.shared();
        c.put("default", shared == null ? JSONObject.NULL : new JSONArray(shared.values()));
        c.put("modes", curvesOf(book.own()));
        return c;
    }

    private static JSONObject curvesOf(Map<String, AmbientCurve> curves) throws Exception {
        JSONObject m = new JSONObject();
        for (Map.Entry<String, AmbientCurve> e : curves.entrySet()) m.put(e.getKey(), new JSONArray(e.getValue().values()));
        return m;
    }

    private static JSONArray luxAxis() {
        JSONArray a = new JSONArray();
        for (double l : AmbientCurve.ANCHOR_LUX) a.put((long) l);
        return a;
    }

    private synchronized void load() {
        learning = prefs.getBoolean("learning", false);
        try {
            String modes = prefs.getString("enabled_modes", null);
            if (modes != null) {
                JSONArray a = new JSONArray(modes);
                for (int i = 0; i < a.length(); i++) switched.set(a.getString(i), true);
                if (prefs.getBoolean("enabled_pending", false)) switched.set(null, true);
            } else if (prefs.getBoolean("enabled", false)) {
                // From before 0.10, when it was one switch for the whole TV:
                // it stays on in the mode the TV is in when this first runs.
                switched.set(null, true);
            }
        } catch (Exception e) {
            Log.w(TAG, "ambient: stored switches unreadable, starting off: " + e);
        }
        try {
            String raw = prefs.getString("curves", null);
            if (raw != null) {
                JSONObject c = new JSONObject(raw);
                JSONArray d = c.optJSONArray("default");
                if (d != null) book.set(null, new AmbientCurve(ints(d)));
                JSONObject modes = c.optJSONObject("modes");
                if (modes != null) {
                    Iterator<String> it = modes.keys();
                    while (it.hasNext()) {
                        String k = it.next();
                        JSONArray a = modes.optJSONArray(k);
                        if (a != null) book.set(k, new AmbientCurve(ints(a)));
                    }
                }
            }
            String starts = prefs.getString("starts", null);
            if (starts != null) {
                JSONObject st = new JSONObject(starts);
                Iterator<String> it = st.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    JSONArray a = st.optJSONArray(k);
                    if (a != null) book.restoreStart(k, new AmbientCurve(ints(a)));
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "ambient: stored curves unreadable, starting fresh: " + e);
        }
    }

    private synchronized void saveEnabled() {
        prefs.edit().putString("enabled_modes", new JSONArray(switched.modes()).toString())
            .putBoolean("enabled_pending", switched.pending()).remove("enabled").apply();
    }

    private synchronized void save() {
        try {
            prefs.edit().putString("curves", curvesJson().toString())
                .putString("starts", curvesOf(book.starts()).toString()).apply();
        } catch (Exception e) {
            Log.w(TAG, "ambient: saving curves failed: " + e);
        }
    }

    // ---- helpers ----------------------------------------------------------------

    /** The Brightness slider as the agent last read it for its state, or -1. */
    private int knownBrightness() {
        try {
            JSONObject picture = store.snapshot().optJSONObject("picture");
            JSONObject targets = picture == null ? null : picture.optJSONObject("targets");
            JSONObject b = targets == null ? null : targets.optJSONObject("brightness");
            String v = b == null ? null : b.optString("value", null);
            return v == null || v.isEmpty() ? -1 : (int) Math.round(Double.parseDouble(v));
        } catch (Exception e) {
            return -1;
        }
    }

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
        JSONObject now = picture.readTargetsNow(Arrays.asList("brightness", "pictureMode"));
        if (now == null) return -1;
        JSONObject pm = now.optJSONObject("pictureMode");
        String liveMode = pm == null ? null : pm.optString("value", null);
        if (liveMode != null && !liveMode.isEmpty() && mode != null && !liveMode.equals(mode)) return MODE_CHANGED;
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
