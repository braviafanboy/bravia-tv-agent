package com.braviafanboy.braviaagent.ambient;

import java.util.Locale;

/**
 * The video's dynamic range class as Home Assistant reports it ("sdr",
 * "hdr10", "dolby_vision", ...): what tells ambient brightness to keep its
 * hands off during HDR.
 *
 * <p>Home Assistant sends it on every change and whenever it connects, and
 * never repeats it, so it is only as good as Home Assistant's presence. It
 * counts while a client holds the event stream, and for {@link #GRACE_MS}
 * after (a reconnect). Once the client has been gone longer the hint is
 * dropped and the agent's own reading of the TV decides. Otherwise a last word
 * of "hdr10", from before Home Assistant went away, kept the loop paused
 * through any amount of SDR until the agent restarted.
 */
public final class SignalHint {
    public static final long GRACE_MS = 120_000;

    private String value;
    private long lastClientMs;

    /** A hint from Home Assistant; null or empty for none. */
    public synchronized void set(String signal, long nowMs) {
        value = signal == null || signal.isEmpty() ? null : signal.toLowerCase(Locale.ROOT);
        lastClientMs = nowMs;
    }

    /** To be called regularly, with whether a client holds the event stream. */
    public synchronized void presence(boolean clientConnected, long nowMs) {
        if (clientConnected) lastClientMs = nowMs;
        else if (value != null && nowMs - lastClientMs > GRACE_MS) value = null;
    }

    /** The hint in force, or null. */
    public synchronized String value() {
        return value;
    }

    /** Whether the hint in force says the video is something other than SDR. */
    public synchronized boolean hdr() {
        return value != null && !value.equals("sdr");
    }
}
