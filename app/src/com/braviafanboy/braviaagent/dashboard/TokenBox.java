package com.braviafanboy.braviaagent.dashboard;

/**
 * The short-lived Home Assistant access token for the dashboard, held in
 * memory only (never written to storage). Home Assistant pushes a token when
 * it opens a dashboard and whenever the agent asks for a fresh one; the
 * frontend's token requests wait here until one arrives.
 */
public final class TokenBox {
    /** A token this close to expiry is treated as expired. */
    public static final long MARGIN_MS = 60_000;

    public static final class Token {
        public final String value;
        public final long expiresAtMs;

        Token(String value, long expiresAtMs) {
            this.value = value;
            this.expiresAtMs = expiresAtMs;
        }

        /** Whole seconds left at {@code nowMs}, at least 1. */
        public int expiresInS(long nowMs) {
            return (int) Math.max(1, (expiresAtMs - nowMs) / 1000);
        }
    }

    private Token token;
    private long generation;

    /** Store a new token; wakes anyone waiting for one. */
    public synchronized void set(String value, int expiresInS, long nowMs) {
        if (value == null || value.isEmpty() || expiresInS <= 0) return;
        token = new Token(value, nowMs + expiresInS * 1000L);
        generation++;
        notifyAll();
    }

    public synchronized void clear() {
        token = null;
        generation++;
        notifyAll();
    }

    public synchronized long generation() {
        return generation;
    }

    /** The current token if it is still good at {@code nowMs}, else null. */
    public synchronized Token valid(long nowMs) {
        return token != null && token.expiresAtMs - MARGIN_MS > nowMs ? token : null;
    }

    /**
     * Wait up to {@code timeoutMs} for a token newer than {@code afterGeneration}
     * that is still valid. Returns null on timeout (or if the new one is already
     * stale, or was cleared).
     */
    public synchronized Token awaitNewer(long afterGeneration, long timeoutMs, Clock clock) throws InterruptedException {
        long deadline = clock.nowMs() + timeoutMs;
        while (generation == afterGeneration) {
            long left = deadline - clock.nowMs();
            if (left <= 0) return null;
            wait(left);
        }
        return valid(clock.nowMs());
    }

    public interface Clock {
        long nowMs();
    }
}
