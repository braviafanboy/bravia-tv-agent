package com.braviafanboy.braviaagent.ambient;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Iterator;

/**
 * The room's light level, estimated as a low percentile of the light sensor's
 * readings over a rolling window. The screen adds light to what the sensor
 * sees, and how much depends on the scene; the room is the floor underneath.
 * Darker moments in between bright scenes reveal it, so a low percentile
 * follows the room while a mean would be dragged up by every bright scene.
 * Pure Java (timestamps passed in) so it is unit-tested on a desktop JVM.
 */
public final class LuxFloor {
    public static final long WINDOW_MS = 45_000;
    public static final double PERCENTILE = 0.20;
    /** Below this many samples in the window the estimate is not trusted. */
    public static final int MIN_SAMPLES = 10;

    private final ArrayDeque<long[]> samples = new ArrayDeque<>();  // {timeMs, lux*1000}

    public synchronized void add(long timeMs, double lux) {
        samples.addLast(new long[] {timeMs, Math.round(Math.max(0, lux) * 1000)});
        prune(timeMs);
    }

    /** The floor at {@code nowMs}, or NaN while there are too few recent samples. */
    public synchronized double floor(long nowMs) {
        prune(nowMs);
        if (samples.size() < MIN_SAMPLES) return Double.NaN;
        long[] v = new long[samples.size()];
        int i = 0;
        for (long[] s : samples) v[i++] = s[1];
        Arrays.sort(v);
        int idx = (int) Math.floor(PERCENTILE * (v.length - 1));
        return v[idx] / 1000.0;
    }

    public synchronized int size() {
        return samples.size();
    }

    public synchronized void clear() {
        samples.clear();
    }

    private void prune(long nowMs) {
        Iterator<long[]> it = samples.iterator();
        while (it.hasNext()) {
            if (nowMs - it.next()[0] > WINDOW_MS) it.remove(); else break;
        }
    }
}
