package com.braviafanboy.braviaagent.http;

import java.util.HashMap;
import java.util.Map;

/**
 * Counts open connections, in total and per client address, so that one
 * client cannot take every place: a client at its own limit is refused while
 * the others still get in.
 */
public final class ConnectionLimiter {
    private final int max;
    private final int maxPerAddress;
    private final Map<String, Integer> perAddress = new HashMap<>();
    private int open;

    public ConnectionLimiter(int max, int maxPerAddress) {
        if (max < 1 || maxPerAddress < 1) throw new IllegalArgumentException("limits must be positive");
        this.max = max;
        this.maxPerAddress = Math.min(max, maxPerAddress);
    }

    /** Take a place for a connection from {@code address}; false when that client or the server is at its limit. */
    public synchronized boolean tryAcquire(String address) {
        if (open >= max) return false;
        int mine = perAddress.getOrDefault(address, 0);
        if (mine >= maxPerAddress) return false;
        perAddress.put(address, mine + 1);
        open++;
        return true;
    }

    /** Give back a place taken with {@link #tryAcquire}. */
    public synchronized void release(String address) {
        Integer mine = perAddress.get(address);
        if (mine == null) return;
        if (mine <= 1) perAddress.remove(address);
        else perAddress.put(address, mine - 1);
        open--;
    }

    public synchronized int open() {
        return open;
    }
}
