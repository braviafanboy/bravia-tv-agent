package com.braviafanboy.braviaagent.ambient;

import java.util.Arrays;

/**
 * A brightness curve for one picture mode: the TV's Brightness slider (0..50)
 * at six fixed ambient light levels, interpolated in log-lux (perceived
 * brightness follows ratios, not differences). Anchors never decrease with
 * more light. Pure Java, so it is unit-tested on a desktop JVM.
 */
public final class AmbientCurve {
    /** Lux of each anchor. Sony's own curve stops changing at about 150 lux. */
    public static final double[] ANCHOR_LUX = {0, 10, 25, 60, 150, 500};
    public static final int ANCHORS = ANCHOR_LUX.length;
    public static final int MIN = 0;
    public static final int MAX = 50;

    /**
     * Sony's "Home" auto-luminance gain at the anchors (from SonySystemService's
     * brightness data), used to shape the first default curve from the user's
     * current brightness: about a third in the dark, full from ~150 lux.
     */
    private static final double[] DEFAULT_SHAPE = {0.35, 0.35, 0.42, 0.60, 1.0, 1.0};

    private final int[] values;

    public AmbientCurve(int[] values) {
        if (values == null || values.length != ANCHORS) throw new IllegalArgumentException("need " + ANCHORS + " anchors");
        this.values = values.clone();
        normalise(this.values, -1);
    }

    /** A first curve shaped like Sony's, reaching {@code brightness} in a bright room. */
    public static AmbientCurve fromCurrent(int brightness) {
        int b = clamp(brightness);
        int[] v = new int[ANCHORS];
        for (int i = 0; i < ANCHORS; i++) v[i] = (int) Math.round(b * DEFAULT_SHAPE[i]);
        return new AmbientCurve(v);
    }

    public int[] values() {
        return values.clone();
    }

    /** The slider value for {@code lux}, interpolated in log-lux; flat beyond the end anchors. */
    public int target(double lux) {
        return (int) Math.round(interpolate(lux));
    }

    double interpolate(double lux) {
        double x = x(lux);
        if (x <= x(ANCHOR_LUX[0])) return values[0];
        for (int i = 0; i < ANCHORS - 1; i++) {
            double x0 = x(ANCHOR_LUX[i]), x1 = x(ANCHOR_LUX[i + 1]);
            if (x <= x1) {
                double f = (x - x0) / (x1 - x0);
                return values[i] + f * (values[i + 1] - values[i]);
            }
        }
        return values[ANCHORS - 1];
    }

    /**
     * Learn from a manual choice: the user picked {@code chosen} at {@code lux}.
     * The two anchors around that light level move, the nearer one more, so
     * the curve passes through the choice there (up to rounding); the rest of
     * the curve is then kept non-decreasing around them. Returns the new curve.
     */
    public AmbientCurve learn(double lux, int chosen) {
        int[] v = values.clone();
        double x = x(lux);
        int lo, hi;
        double f;
        if (x <= x(ANCHOR_LUX[0])) {
            lo = hi = 0;
            f = 0;
        } else if (x >= x(ANCHOR_LUX[ANCHORS - 1])) {
            lo = hi = ANCHORS - 1;
            f = 0;
        } else {
            int i = 0;
            while (x > x(ANCHOR_LUX[i + 1])) i++;
            lo = i;
            hi = i + 1;
            f = (x - x(ANCHOR_LUX[lo])) / (x(ANCHOR_LUX[hi]) - x(ANCHOR_LUX[lo]));
        }
        double error = clamp(chosen) - interpolate(lux);
        if (lo == hi) {
            v[lo] = clamp((int) Math.round(v[lo] + error));
        } else {
            // The smallest change to the two anchors that makes the
            // interpolation pass through the choice at f: each moves in
            // proportion to its weight, scaled so the weighted sum is exact.
            double norm = (1 - f) * (1 - f) + f * f;
            double a = v[lo] + error * (1 - f) / norm;
            double b = v[hi] + error * f / norm;
            // If one anchor hits the 0..50 limit, the other takes up the rest so
            // the curve still passes through the choice where it can.
            if (b > MAX || b < MIN) {
                b = Math.max(MIN, Math.min(MAX, b));
                a = (clamp(chosen) - f * b) / (1 - f);
            } else if (a > MAX || a < MIN) {
                a = Math.max(MIN, Math.min(MAX, a));
                b = (clamp(chosen) - (1 - f) * a) / f;
            }
            v[lo] = clamp((int) Math.round(a));
            v[hi] = clamp((int) Math.round(b));
            if (v[lo] > v[hi]) v[lo] = v[hi];
        }
        normalise(v, lo);
        return new AmbientCurve(v);
    }

    /**
     * Clamp to 0..50 and make the curve non-decreasing. With a pinned anchor
     * (the one just learned), anchors below it are lowered to it and anchors
     * above raised to it, so the user's choice wins; without one, a forward pass.
     */
    private static void normalise(int[] v, int pinned) {
        for (int i = 0; i < v.length; i++) v[i] = clamp(v[i]);
        if (pinned < 0) {
            for (int i = 1; i < v.length; i++) if (v[i] < v[i - 1]) v[i] = v[i - 1];
            return;
        }
        for (int i = pinned - 1; i >= 0; i--) if (v[i] > v[i + 1]) v[i] = v[i + 1];
        for (int i = pinned + 1; i < v.length; i++) if (v[i] < v[i - 1]) v[i] = v[i - 1];
    }

    private static double x(double lux) {
        return Math.log10(Math.max(0, lux) + 1);
    }

    static int clamp(int v) {
        return Math.max(MIN, Math.min(MAX, v));
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof AmbientCurve && Arrays.equals(values, ((AmbientCurve) o).values);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(values);
    }

    @Override
    public String toString() {
        return Arrays.toString(values);
    }
}
