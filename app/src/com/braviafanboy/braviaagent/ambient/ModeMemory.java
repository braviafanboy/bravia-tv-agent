package com.braviafanboy.braviaagent.ambient;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What ambient brightness remembers of each picture mode's Brightness, so that
 * switching it on starts from the viewer's own setting and switching it off
 * gives that setting back.
 *
 * <p>Per mode, two values:
 * <ul>
 * <li><b>own</b>: the brightness the viewer had when ambient brightness was
 *     switched on in the mode. The mode's starting curve is built from it,
 *     and it is what switching off puts back.
 * <li><b>left</b>: the value ambient brightness last wrote there. It tells
 *     the two apart later: a brightness that still equals it is ambient's
 *     doing, anything else is the viewer's.
 * </ul>
 *
 * <p>That distinction is what keeps the start from creeping down. Switched
 * off where the brightness could not be put back (the TV was in another mode,
 * or showing HDR, whose values are separate), the mode is left dimmed; were
 * it then switched on again, a start built from what the slider shows would
 * be built from ambient's own last value, and lower each time. So a
 * brightness that is still the one ambient left there does not count as the
 * viewer's: the remembered one does.
 *
 * <p>Not thread-safe: the controller guards it. Pure Java, tested on a desktop
 * JVM.
 */
public final class ModeMemory {
    private final Map<String, Integer> own = new LinkedHashMap<>();
    private final Map<String, Integer> left = new LinkedHashMap<>();

    /**
     * Ambient brightness was switched on in {@code mode}, whose slider shows
     * {@code brightness}. Returns the viewer's brightness there: what the
     * slider shows, unless that is only what ambient left behind.
     */
    public int switchedOn(String mode, int brightness) {
        Integer mine = own.get(mode), last = left.get(mode);
        // Still showing what ambient left behind: that stays on record, so
        // that switching off again gives the viewer's own back even if
        // nothing is written in between (found on a TV: off during HDR, on
        // and off again in SDR, and the slider was left dimmed).
        if (mine != null && last != null && brightness == last) return mine;
        own.put(mode, brightness);
        left.remove(mode);
        return brightness;
    }

    /**
     * The viewer's brightness in {@code mode}, whose slider shows {@code
     * brightness} while ambient brightness is off there: what it shows,
     * unless that is only what ambient left behind. Negative stays negative.
     */
    public int viewers(String mode, int brightness) {
        Integer mine = own.get(mode), last = left.get(mode);
        return brightness >= 0 && mine != null && last != null && brightness == last ? mine : brightness;
    }

    /** Ambient brightness wrote {@code value} in {@code mode}. */
    public void wrote(String mode, int value) {
        if (mode != null && own.containsKey(mode)) left.put(mode, value);
    }

    /**
     * Ambient brightness was switched off in {@code mode}, whose slider shows
     * {@code brightness} now. Returns the brightness to put back, or -1 to
     * leave the slider alone: when nothing was written there, or when what it
     * shows is not what ambient wrote (the viewer has moved it since).
     */
    public int switchedOff(String mode, int brightness) {
        Integer mine = own.get(mode), last = left.get(mode);
        if (mine == null || last == null || brightness != last) {
            // Whatever the slider shows now is the viewer's.
            left.remove(mode);
            return -1;
        }
        left.remove(mode);
        return mine.equals(last) ? -1 : mine;
    }

    /** The viewer set {@code value} by hand in {@code mode}: that is their setting now. */
    public void viewerChose(String mode, int value) {
        if (mode == null) return;
        own.put(mode, value);
        left.remove(mode);
    }

    /** The viewer's remembered brightness in {@code mode}, or -1. */
    public int own(String mode) {
        Integer v = own.get(mode);
        return v == null ? -1 : v;
    }

    /** What ambient last wrote in {@code mode} and has not been accounted for, or -1. */
    public int left(String mode) {
        Integer v = left.get(mode);
        return v == null ? -1 : v;
    }

    public Map<String, Integer> ownByMode() {
        return java.util.Collections.unmodifiableMap(own);
    }

    public Map<String, Integer> leftByMode() {
        return java.util.Collections.unmodifiableMap(left);
    }

    /** Values read back from storage; negative ones are skipped. */
    public void restore(String mode, int ownValue, int leftValue) {
        if (mode == null) return;
        if (ownValue >= 0) own.put(mode, ownValue);
        if (leftValue >= 0) left.put(mode, leftValue);
    }
}
