package com.braviafanboy.braviaagent.ambient;

import java.util.Arrays;

/** Tests for the ambient brightness logic, run on a desktop JVM by app/test.sh. */
public final class AmbientTest {
    private static int passed;

    public static void main(String[] args) {
        curve();
        learning();
        floor();
        decider();
        hint();
        book();
        switches();
        memory();
        lastLook();
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

    /**
     * With learning on, a brightness set by hand is learnt and the loop
     * resumes at once. It must then leave that brightness alone for as long as
     * the room light stays the same: every light level, every choice, on the
     * curve a fresh install starts from and on two others. (It used to walk
     * the slider back, by up to 31 steps, in about a third of these cases.)
     */
    private static void learning() {
        int[][] bases = {
            AmbientCurve.fromCurrent(25).values(),
            AmbientCurve.fromCurrent(50).values(),
            {10, 12, 18, 25, 40, 50},
        };
        for (int[] base : bases) {
            AmbientCurve before = new AmbientCurve(base);
            int moved = 0;
            String first = null;
            for (int lux = 0; lux <= 500; lux++) {
                for (int chosen = 0; chosen <= 50; chosen++) {
                    AmbientCurve learnt = before.learn(lux, chosen);
                    int end = resume(learnt, lux, chosen);
                    if (end != chosen) {
                        moved++;
                        if (first == null) first = "at " + lux + " lux the viewer set " + chosen + ", learnt " + learnt + ", loop moved it to " + end;
                    }
                    int[] v = learnt.values();
                    for (int i = 1; i < v.length; i++) {
                        if (v[i] < v[i - 1]) {
                            System.err.println("FAIL: learnt curve falls: " + learnt + " (" + lux + " lux, " + chosen + ")");
                            System.exit(1);
                        }
                    }
                }
            }
            check(moved == 0, "after learning, the loop leaves the viewer's value alone on " + before
                + " (" + moved + " cases moved; first: " + first + ")");
        }
        // The case from the report: a dark room, the viewer turns it up.
        AmbientCurve learnt = AmbientCurve.fromCurrent(25).learn(1, 20);
        eq(20, learnt.target(1), "the curve passes through what the viewer chose");
    }

    /** The loop as AmbientController runs it after learning, for a minute in steady light; the slider's last value. */
    private static int resume(AmbientCurve curve, double lux, int chosen) {
        AmbientDecider d = new AmbientDecider();
        int lastWritten = chosen;
        long t = 1_000_000;
        for (int tick = 0; tick < 60; tick++, t += 1000) {
            d.update(t, lux);
            int next = d.step(t, lastWritten, curve.target(d.decidedLux()));
            if (next >= 0) lastWritten = next;
        }
        return lastWritten;
    }

    /** Home Assistant's word on HDR counts while Home Assistant is there. */
    private static void hint() {
        SignalHint h = new SignalHint();
        check(!h.hdr() && h.value() == null, "no hint, no HDR");
        long t = 1_000_000;
        h.set("HDR10", t);
        check(h.hdr(), "an HDR hint pauses the loop");
        eq("hdr10", h.value(), "lower case");
        // Home Assistant connected: the hint holds for as long as it likes.
        for (int i = 0; i < 3600; i++) h.presence(true, t += 1000);
        check(h.hdr(), "it holds while a client is connected");
        // Home Assistant goes away: a short break changes nothing...
        t += SignalHint.GRACE_MS;
        h.presence(false, t);
        check(h.hdr(), "and through a reconnect");
        // ...a longer one drops it, so SDR is not left paused for good.
        h.presence(false, t + 1);
        check(!h.hdr() && h.value() == null, "but not once the client has been gone a while");
        h.presence(true, t + 5000);
        check(!h.hdr(), "a client coming back does not bring it back; it sends a new one");
        h.set("sdr", t + 6000);
        check(!h.hdr(), "sdr is not HDR");
        eq("sdr", h.value(), "but it is a hint");
        h.set("", t + 7000);
        check(h.value() == null, "an empty hint clears it");
        h.set("dolby_vision", t + 8000);
        h.presence(false, t + 8000 + SignalHint.GRACE_MS + 1);
        check(!h.hdr(), "set while nobody is connected, it lapses the same way");
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

    /** Which curve a picture mode follows, and where a mode's first curve comes from. */
    private static void book() {
        CurveBook b = new CurveBook();
        // Nothing stored: a preview, from the brightness given or the fallback.
        eq(40, b.curveFor("cinema", -1).values()[5], "unknown brightness: the fallback, 40");
        eq(50, b.curveFor("cinema", 50).values()[5], "a preview reaches the brightness the mode has");
        eq(10, b.curveFor(null, 10).values()[5], "also before the mode is known");
        check(b.starts().isEmpty() && b.own().isEmpty() && b.shared() == null, "a preview stores nothing");

        // Each mode starts from its own brightness, once.
        check(b.start("professional", 10), "professional starts from 10");
        check(b.start("cinema", 50), "cinema from 50");
        eq(10, b.curveFor("professional", -1).values()[5], "professional reaches 10 in a bright room");
        eq(50, b.curveFor("cinema", -1).values()[5], "cinema reaches 50");
        check(!b.start("cinema", 7), "a later reading does not replace the start");
        eq(50, b.curveFor("cinema", -1).values()[5], "which is what it follows while it runs");
        eq(7, b.curveFor("cinema", 7).values()[5], "off there, it shows what switching on would start from now");
        b.restart("cinema", 30);
        eq(30, b.curveFor("cinema", -1).values()[5], "switched on again: the start is built anew");
        b.restart("cinema", 50);
        check(!b.start(null, 30) && !b.start("vivid", -1), "no mode, or no reading: no start");
        eq(40, b.curveFor("vivid", -1).values()[5], "a mode without one is still previewed");

        // Its own curve comes first; a reset goes back to the start.
        AmbientCurve learnt = b.curveFor("cinema", -1).learn(5, 20);
        b.set("cinema", learnt);
        check(b.hasOwn("cinema") && !b.hasOwn("professional"), "only cinema has its own");
        eq(learnt.toString(), b.curveFor("cinema", -1).toString(), "which it follows");
        check(!b.start("cinema", 30), "a mode with its own curve gets no new start");
        b.reset("cinema");
        eq(50, b.curveFor("cinema", -1).values()[5], "reset: back to its start, not to another mode's");

        // A shared curve (set by a client, or kept from an older agent) is
        // for every mode without its own, and comes before the starts.
        AmbientCurve shared = new AmbientCurve(new int[] {4, 4, 4, 6, 10, 10});
        b.set(null, shared);
        eq(10, b.curveFor("cinema", -1).values()[5], "the shared curve applies to cinema");
        check(!b.start("standard", 45), "and no starts are made while there is one");
        b.set("cinema", learnt);
        eq(learnt.toString(), b.curveFor("cinema", -1).toString(), "a mode's own curve still wins");
        java.util.Map<String, AmbientCurve> replaced = new java.util.LinkedHashMap<>();
        replaced.put("vivid", shared);
        b.replace(null, false, replaced);
        check(b.shared() == shared && b.hasOwn("vivid") && !b.hasOwn("cinema"), "replacing the modes keeps the shared curve");
        b.replace(null, true, null);
        check(b.shared() == null && b.hasOwn("vivid"), "clearing the shared curve keeps the modes");
        eq(50, b.curveFor("cinema", -1).values()[5], "and cinema is back on its own start");
        check(b.start("standard", 45), "starts are made again");
        b.restoreStart("photo", AmbientCurve.fromCurrent(30));
        eq(30, b.curveFor("photo", -1).values()[5], "a start read back from storage");
    }

    /** It is switched on per picture mode, and remembered. */
    private static void switches() {
        EnabledModes m = new EnabledModes();
        check(!m.any() && !m.isOn("cinema") && !m.isOn(null), "off everywhere to begin with");
        check(m.set("professional", true), "on in professional");
        check(!m.set("professional", true), "switching it on again changes nothing");
        check(m.isOn("professional") && !m.isOn("cinema"), "and only there");
        check(m.any(), "so there is a mode to run in");
        check(m.set("cinema", true) && m.isOn("cinema"), "on in cinema too");
        check(m.set("professional", false) && !m.isOn("professional"), "off in professional");
        check(m.isOn("cinema"), "which leaves cinema as it was: it is remembered per mode");
        check(!m.set("vivid", false), "off where it never was on changes nothing");
        eq("[cinema]", m.modes().toString(), "the modes it is on in");

        // Switched on without naming the mode: the one the TV turns out to be in.
        check(m.set(null, true) && m.pending(), "switched on before the mode is known");
        check(m.isOn(null), "counts as on until then");
        check(!m.isOn("standard"), "but not yet in any named mode");
        check(!m.adopt(null), "an unknown mode adopts nothing");
        check(m.adopt("standard") && !m.pending(), "the first mode seen takes it");
        check(m.isOn("standard") && !m.isOn(null), "and that is that mode's, not the next one's");
        check(!m.adopt("vivid") && !m.isOn("vivid"), "another mode gets nothing");
        // Switched off again before the mode was known.
        m.set(null, true);
        check(m.set(null, false) && !m.pending(), "a waiting switch-on can be taken back");
        check(!m.adopt("vivid"), "and then nothing is adopted");
        // The first look at the TV in a mode after it was switched on there
        // is told apart, once: that is when its brightness is the viewer's.
        EnabledModes f = new EnabledModes();
        f.set("cinema", true);
        check(!f.takeFresh("professional"), "another mode is not fresh");
        check(f.takeFresh("cinema") && !f.takeFresh("cinema"), "fresh once per switch-on");
        f.set("cinema", false);
        f.set("cinema", true);
        check(f.takeFresh("cinema"), "and again after off and on");
        f.set("vivid", true);
        f.set("vivid", false);
        check(!f.takeFresh("vivid"), "switched off before the TV was seen there: not fresh");
        f.set(null, true);
        f.adopt("standard");
        check(f.takeFresh("standard"), "a mode that took a waiting switch-on is fresh too");
        // What an agent before 0.10 kept, one switch for the TV, is such a waiting switch-on.
        EnabledModes old = new EnabledModes();
        old.set(null, true);
        check(old.any() && old.adopt("professional") && old.isOn("professional"), "it stays on in the mode in use");
    }

    /** The viewer's brightness per mode: what a start is built from, and what switching off gives back. */
    private static void memory() {
        ModeMemory m = new ModeMemory();
        eq(5, m.switchedOn("professional", 5), "switched on at 5: that is the viewer's");
        m.wrote("professional", 4);
        m.wrote("professional", 3);
        eq(3, m.left("professional"), "ambient brightness has left it at 3");
        eq(5, m.switchedOff("professional", 3), "switched off: 5 goes back");
        eq(-1, m.left("professional"), "and nothing is owed any more");
        eq(5, m.switchedOn("professional", 5), "on again: from 5, not from where ambient had it");

        // Switched off where it could not be put back (not asked: the TV was
        // in another mode, or in HDR). The slider still shows ambient's 3.
        m.wrote("professional", 3);
        eq(5, m.switchedOn("professional", 3), "on again at ambient's own 3: the start is still from 5");
        m.wrote("professional", 2);
        eq(5, m.switchedOn("professional", 2), "and again: it does not creep down");

        // As it happened on a TV: off during HDR (nothing put back), on
        // again in SDR where the target is the 3 it already shows (so nothing
        // is written), off again: the viewer's 5 must come back.
        ModeMemory tv = new ModeMemory();
        tv.switchedOn("professional", 5);
        tv.wrote("professional", 3);
        eq(5, tv.switchedOn("professional", 3), "on again where ambient left it: the start is from 5");
        eq(3, tv.left("professional"), "and the 3 on the slider is still on record as ambient's");
        eq(5, tv.switchedOff("professional", 3), "off without a write in between: 5 goes back");
        eq(5, tv.switchedOn("professional", 5), "then everything is as at the beginning");
        eq(-1, tv.left("professional"), "with nothing owed");

        // The viewer moved the slider while it was off: that is theirs.
        m.wrote("professional", 3);
        eq(8, m.switchedOn("professional", 8), "a brightness ambient did not leave is the viewer's");
        m.wrote("professional", 6);
        eq(-1, m.switchedOff("professional", 7), "moved by hand before switching off: left alone");
        eq(7, m.switchedOn("professional", 7), "and that value is the next start");

        // Nothing written, or written the very same value: nothing to put back.
        eq(-1, m.switchedOff("professional", 7), "ambient wrote nothing: nothing goes back");
        m.wrote("professional", 7);
        eq(-1, m.switchedOff("professional", 7), "it wrote the viewer's own value: nothing to do");

        // A hand change that switched it off (learning off) is the viewer's setting.
        m.switchedOn("cinema", 50);
        m.wrote("cinema", 30);
        m.viewerChose("cinema", 42);
        eq(42, m.own("cinema"), "the viewer's value is remembered");
        eq(-1, m.switchedOff("cinema", 42), "and not overwritten when it goes off");
        eq(42, m.switchedOn("cinema", 42), "the next start is from it");

        // Modes are apart; a mode never switched on has nothing.
        eq(7, m.own("professional"), "professional kept its own");
        eq(-1, m.own("vivid"), "vivid has none");
        m.wrote("vivid", 9);
        eq(-1, m.left("vivid"), "a write is only remembered where it was switched on");
        eq(-1, m.switchedOff("vivid", 9), "so there is nothing to give back");
        // Brightness 0 is a setting like any other.
        eq(0, m.switchedOn("calm", 0), "switched on at 0");
        m.restore("photo", 20, 12);
        eq(20, m.switchedOn("photo", 12), "values read back from storage count the same");
        // What an off mode's curve is previewed from follows the same rule.
        ModeMemory v = new ModeMemory();
        eq(9, v.viewers("standard", 9), "nothing remembered: what the slider shows");
        v.switchedOn("standard", 40);
        v.wrote("standard", 25);
        eq(40, v.viewers("standard", 25), "what ambient left behind is not the viewer's");
        eq(33, v.viewers("standard", 33), "another value is");
        eq(-1, v.viewers("standard", -1), "an unknown brightness stays unknown");
    }

    /** The last look at the TV before a write. */
    private static void lastLook() {
        eq("WRITE", WriteCheck.verdict("cinema", 30, "cinema", 30, false), "nothing changed: write");
        eq("MOVED", WriteCheck.verdict("cinema", 30, "cinema", 34, false), "the viewer moved the slider");
        eq("MODE_CHANGED", WriteCheck.verdict("cinema", 30, "standard", 30, false), "another picture mode, same number");
        // An HDR clip started in the seconds since the loop last read the
        // TV. Usually the slider jumps with it; it need not.
        eq("HDR", WriteCheck.verdict("cinema", 50, "cinema", 50, true), "HDR with the very same brightness: still no write");
        eq("HDR", WriteCheck.verdict("cinema", 30, "cinema", 50, true), "HDR with another brightness is HDR, not a hand change");
        eq("MODE_CHANGED", WriteCheck.verdict("cinema", 30, "standard", 30, true), "another mode comes first");
        // Whatever cannot be read is not written into.
        eq("UNREADABLE", WriteCheck.verdict("cinema", 30, "cinema", -1, false), "no brightness read");
        eq("UNREADABLE", WriteCheck.verdict("cinema", 30, null, 30, false), "no picture mode read");
        eq("UNREADABLE", WriteCheck.verdict("cinema", 30, "", 30, false), "an empty one");
        eq("UNREADABLE", WriteCheck.verdict("cinema", 30, null, -1, true), "nothing read at all");
        eq("WRITE", WriteCheck.verdict("cinema", 0, "cinema", 0, false), "zero is a value like any other");
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
