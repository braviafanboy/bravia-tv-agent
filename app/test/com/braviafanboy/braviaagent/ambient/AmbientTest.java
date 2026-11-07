package com.braviafanboy.braviaagent.ambient;

import java.util.Arrays;

/** Tests for the ambient brightness logic, run on a desktop JVM by app/test.sh. */
public final class AmbientTest {
    private static int passed;

    public static void main(String[] args) {
        curve();
        floor();
        decider();
        System.out.println("ok: " + passed + " ambient checks passed");
    }

    private static void curve() {
        AmbientCurve c = new AmbientCurve(new int[] {10, 12, 18, 25, 40, 50});
        eq(10, c.target(0), "dark end");
        eq(50, c.target(5000), "flat beyond the bright end");
        eq(25, c.target(60), "exactly at an anchor");
        int mid = c.target(38);
        check(mid > 18 && mid < 25, "between anchors interpolates (" + mid + ")");
        // Interpolation is in log-lux: 38 lux is past the middle of 25..60 in lux terms
        // but close to the middle in log terms.
        eq("[10, 12, 18, 25, 40, 50]", new AmbientCurve(new int[] {10, 12, 18, 25, 40, 50}).toString(), "toString");
        // Normalising: out of range and decreasing values are fixed, not rejected.
        eq("[0, 5, 5, 20, 50, 50]", new AmbientCurve(new int[] {-3, 5, 2, 20, 60, 10}).toString(), "clamped and non-decreasing");

        AmbientCurve d = AmbientCurve.fromCurrent(25);
        eq("[9, 9, 11, 15, 25, 25]", d.toString(), "default shaped like Sony's from the current brightness");
        eq(0, AmbientCurve.fromCurrent(0).target(500), "default from zero");

        // Learning passes the curve through the choice (up to rounding).
        for (double lux : new double[] {0, 4, 10, 17, 38, 100, 150, 300, 800}) {
            for (int chosen : new int[] {0, 7, 22, 31, 50}) {
                AmbientCurve l = c.learn(lux, chosen);
                int got = l.target(lux);
                check(Math.abs(got - chosen) <= 1, "learn(" + lux + ", " + chosen + ") gives " + got + " " + l);
                int[] v = l.values();
                for (int i = 1; i < v.length; i++) check(v[i] >= v[i - 1], "learned curve stays non-decreasing " + l);
            }
        }
        // Learning at an anchor moves only around it; far anchors stay unless order forces them.
        AmbientCurve l = c.learn(25, 20);
        eq(20, l.target(25), "learn at an anchor");
        eq(10, l.values()[0], "far dark anchor untouched");
        eq(50, l.values()[5], "far bright anchor untouched");
        // Choosing much darker in a bright room pulls lower anchors down with it.
        AmbientCurve dim = c.learn(500, 15);
        eq(15, dim.target(500), "dimmer choice in a bright room");
        check(Arrays.stream(dim.values()).max().getAsInt() == 15, "anchors above the choice are lowered " + dim);
    }

    private static void floor() {
        LuxFloor f = new LuxFloor();
        long t = 0;
        check(Double.isNaN(f.floor(t)), "no estimate without samples");
        // 20 lux room, 5 Hz, with bright-scene bursts to 90 lux a third of the time.
        for (int i = 0; i < 225; i++) {
            t += 200;
            f.add(t, (i % 15) < 5 ? 90 : 20);
        }
        eq(20.0, f.floor(t), "bright scenes don't lift the floor");
        // Lights switched on: the floor follows once most of the window is brighter.
        for (int i = 0; i < 225; i++) {
            t += 200;
            f.add(t, 60);
        }
        eq(60.0, f.floor(t), "a lasting change is followed");
        // Old samples leave the window.
        check(f.size() <= LuxFloor.WINDOW_MS / 200 + 1, "window pruned (" + f.size() + ")");
        check(Double.isNaN(f.floor(t + LuxFloor.WINDOW_MS + 1)), "stale window gives no estimate");
    }

    private static void decider() {
        eq(0, AmbientDecider.significant(7, 8), "1 lux at 7 is below the 2 lux minimum");
        eq(1, AmbientDecider.significant(7, 10), "7 to 10 lux counts");
        eq(0, AmbientDecider.significant(100, 110), "10% is below the ratio");
        eq(-1, AmbientDecider.significant(100, 80), "dimming counts");

        AmbientDecider d = new AmbientDecider();
        long t = 1_000_000;
        check(d.update(t, 20), "first floor is taken at once");
        // Brightening must hold for 8 s.
        check(!d.update(t + 1000, 60), "brightening not yet");
        check(!d.update(t + 7000, 60), "still within 8 s");
        check(d.update(t + 9100, 60), "brightening after 8 s");
        eq(60.0, d.decidedLux(), "decided level");
        // A blip back up and down doesn't reset the clock for a sustained change.
        // Reversal hold: dimming within 30 s of brightening is held.
        t += 9100;
        check(!d.update(t + 1000, 20), "reversal held");
        check(!d.update(t + 25_000, 20), "reversal still held at 25 s");
        check(d.update(t + 31_000, 20), "dimming after the reversal hold (and 20 s confirm)");
        // A change that disappears before it is confirmed is forgotten.
        t += 31_000;
        check(!d.update(t + 1000, 50), "brightening pending");
        check(!d.update(t + 3000, 21), "back to the old level cancels it");
        check(!d.update(t + 9500, 50), "pending clock restarted");

        // Output ramp: 2+ steps away starts at once, one step per second.
        AmbientDecider o = new AmbientDecider();
        long s = 5_000_000;
        eq(21, o.step(s, 20, 25), "first step");
        eq(-1, o.step(s + 500, 21, 25), "not within a second");
        eq(22, o.step(s + 1000, 21, 25), "next step after a second");
        eq(-1, o.step(s + 5000, 25, 25), "arrived");
        // 1 step away waits 10 s.
        AmbientDecider p = new AmbientDecider();
        eq(-1, p.step(s, 20, 21), "1 step: wait");
        eq(-1, p.step(s + 9000, 20, 21), "1 step: still waiting at 9 s");
        eq(21, p.step(s + 10_000, 20, 21), "1 step: after 10 s");
        eq(20, new AmbientDecider().step(s, 21, 19), "dims too, one step at a time");
    }

    private static void eq(Object expected, Object actual, String what) {
        if (!String.valueOf(expected).equals(String.valueOf(actual))) {
            System.err.println("FAIL: " + what + ": expected " + expected + ", got " + actual);
            System.exit(1);
        }
        passed++;
    }

    private static void check(boolean ok, String what) {
        if (!ok) {
            System.err.println("FAIL: " + what);
            System.exit(1);
        }
        passed++;
    }
}
