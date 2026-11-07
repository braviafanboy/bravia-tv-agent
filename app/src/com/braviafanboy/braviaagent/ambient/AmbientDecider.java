package com.braviafanboy.braviaagent.ambient;

/**
 * Decides when the room's light level has changed enough, for long enough, to
 * act on, and when the brightness should take a step. Pure Java (timestamps
 * passed in) so it is unit-tested on a desktop JVM.
 *
 * <ul>
 *   <li>Input deadband: a change counts only if it is both 15% and 2 lux
 *       (the sensor reports whole lux, so one step is already 15% at 7 lux).</li>
 *   <li>Brightening must hold for 8 s (lights switched on stay on; a run of
 *       bright scenes rarely lifts the floor that long); dimming for 20 s.</li>
 *   <li>Reversal hold: no change in the opposite direction within 30 s of the
 *       last one, which stops the screen-lights-the-sensor feedback loop.</li>
 *   <li>Output: one slider step per second toward the target, starting only
 *       when it is 2+ steps away, or 1 step away for 10 s.</li>
 * </ul>
 */
public final class AmbientDecider {
    public static final double RATIO = 1.15;
    public static final double MIN_LUX_DELTA = 2.0;
    public static final long BRIGHTEN_CONFIRM_MS = 8_000;
    public static final long DIM_CONFIRM_MS = 20_000;
    public static final long REVERSAL_HOLD_MS = 30_000;
    public static final long STEP_INTERVAL_MS = 1_000;
    public static final long SMALL_GAP_HOLD_MS = 10_000;

    private double decidedLux = Double.NaN;
    private int pendingDir;
    private long pendingSince;
    private int lastDir;
    private long lastDecisionMs = Long.MIN_VALUE / 2;
    private long lastStepMs = Long.MIN_VALUE / 2;
    private long smallGapSince = -1;
    private boolean moving;

    /** The light level last acted on, or NaN before the first decision. */
    public double decidedLux() {
        return decidedLux;
    }

    /** Forget the decision history (after a pause, a picture mode change, ...). */
    public void reset() {
        decidedLux = Double.NaN;
        pendingDir = 0;
        lastDir = 0;
        smallGapSince = -1;
        moving = false;
    }

    /**
     * Feed the current floor. Returns true when the decided light level changed
     * (the first valid floor is taken at once).
     */
    public boolean update(long nowMs, double floorLux) {
        if (Double.isNaN(floorLux)) return false;
        if (Double.isNaN(decidedLux)) {
            decidedLux = floorLux;
            lastDecisionMs = nowMs;
            return true;
        }
        int dir = significant(decidedLux, floorLux);
        if (dir == 0) {
            pendingDir = 0;
            return false;
        }
        if (dir != pendingDir) {
            pendingDir = dir;
            pendingSince = nowMs;
        }
        if (lastDir != 0 && dir != lastDir && nowMs - lastDecisionMs < REVERSAL_HOLD_MS) return false;
        long confirm = dir > 0 ? BRIGHTEN_CONFIRM_MS : DIM_CONFIRM_MS;
        if (nowMs - pendingSince < confirm) return false;
        decidedLux = floorLux;
        lastDir = dir;
        lastDecisionMs = nowMs;
        pendingDir = 0;
        return true;
    }

    /** +1 / -1 when {@code to} differs enough from {@code from}, else 0. */
    static int significant(double from, double to) {
        double ratio = (to + 1) / (from + 1);
        boolean big = Math.abs(to - from) >= MIN_LUX_DELTA;
        if (big && ratio >= RATIO) return 1;
        if (big && ratio <= 1 / RATIO) return -1;
        return 0;
    }

    /**
     * The next slider value to write, or -1 to leave it: one step per second
     * toward {@code target}, started only when 2+ steps away, or 1 step away
     * for 10 s; once moving, it keeps going until it arrives.
     */
    public int step(long nowMs, int current, int target) {
        int gap = target - current;
        if (gap == 0) {
            moving = false;
            smallGapSince = -1;
            return -1;
        }
        if (!moving) {
            if (Math.abs(gap) >= 2) {
                moving = true;
            } else {
                if (smallGapSince < 0) smallGapSince = nowMs;
                if (nowMs - smallGapSince < SMALL_GAP_HOLD_MS) return -1;
                moving = true;
            }
        }
        if (nowMs - lastStepMs < STEP_INTERVAL_MS) return -1;
        lastStepMs = nowMs;
        smallGapSince = -1;
        return current + Integer.signum(gap);
    }
}
