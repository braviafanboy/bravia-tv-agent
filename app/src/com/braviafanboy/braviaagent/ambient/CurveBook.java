package com.braviafanboy.braviaagent.ambient;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Which curve ambient brightness follows in each picture mode.
 *
 * <p>A mode's curve is, in this order:
 * <ol>
 * <li>its own: learnt from the viewer's hand changes, or set by a client;
 * <li>the shared one, if a client set a curve for every mode without its own
 *     (agents before 0.10 also made one by themselves, and keep it);
 * <li>its start: built once, from the brightness that mode had the first
 *     time ambient brightness ran in it, so that it reaches the viewer's own
 *     setting in a bright room. Picture modes differ a lot here (a TV ships
 *     with one at 40 and another at 50, and a calibrated one may sit at 10),
 *     which is why the start is the mode's and not one for all;
 * <li>a preview, kept nowhere: what the start would be for the brightness
 *     given, or for {@link #FALLBACK_BRIGHTNESS} when that is not known.
 * </ol>
 *
 * <p>A start is made once and then left alone: ambient brightness changes the
 * brightness itself, so a later reading says nothing about what the viewer
 * chose. Resetting a mode drops its own curve and falls back to the start.
 *
 * <p>Not thread-safe: the controller guards it. Pure Java, tested on a desktop
 * JVM.
 */
public final class CurveBook {
    /** The brightness a preview assumes when the TV's has never been read. */
    public static final int FALLBACK_BRIGHTNESS = 40;

    private AmbientCurve shared;
    private final Map<String, AmbientCurve> own = new LinkedHashMap<>();
    private final Map<String, AmbientCurve> starts = new LinkedHashMap<>();

    /**
     * The curve for {@code mode}. {@code brightness} is the TV's current one
     * when it says what the viewer chose (ambient brightness is not running),
     * else negative; it is only used for a preview.
     */
    public AmbientCurve curveFor(String mode, int brightness) {
        if (mode != null) {
            AmbientCurve c = own.get(mode);
            if (c != null) return c;
        }
        if (shared != null) return shared;
        if (mode != null) {
            AmbientCurve c = starts.get(mode);
            if (c != null) return c;
        }
        return AmbientCurve.fromCurrent(brightness >= 0 ? brightness : FALLBACK_BRIGHTNESS);
    }

    /**
     * Ambient brightness is about to run in {@code mode}, whose brightness is
     * still the viewer's: make the mode's start, unless it has one or a curve
     * that comes before it. Returns whether one was made (and must be saved).
     */
    public boolean start(String mode, int brightness) {
        if (mode == null || brightness < 0) return false;
        if (own.containsKey(mode) || shared != null || starts.containsKey(mode)) return false;
        starts.put(mode, AmbientCurve.fromCurrent(brightness));
        return true;
    }

    /** Set a mode's own curve; with no mode, the shared one. */
    public void set(String mode, AmbientCurve curve) {
        if (mode == null) shared = curve; else own.put(mode, curve);
    }

    /** Drop a mode's own curve. */
    public void reset(String mode) {
        if (mode != null) own.remove(mode);
    }

    /**
     * Replace what a client manages. {@code newShared} replaces the shared
     * curve, {@code clearShared} removes it, {@code newOwn} (if not null)
     * replaces every mode's own curve. Starts are the agent's and stay.
     */
    public void replace(AmbientCurve newShared, boolean clearShared, Map<String, AmbientCurve> newOwn) {
        if (clearShared) shared = null;
        if (newShared != null) shared = newShared;
        if (newOwn != null) {
            own.clear();
            own.putAll(newOwn);
        }
    }

    /** A start read back from storage. */
    public void restoreStart(String mode, AmbientCurve curve) {
        if (mode != null && curve != null) starts.put(mode, curve);
    }

    public boolean hasOwn(String mode) {
        return mode != null && own.containsKey(mode);
    }

    /** The shared curve, or null. */
    public AmbientCurve shared() {
        return shared;
    }

    public Map<String, AmbientCurve> own() {
        return Collections.unmodifiableMap(own);
    }

    public Map<String, AmbientCurve> starts() {
        return Collections.unmodifiableMap(starts);
    }
}
