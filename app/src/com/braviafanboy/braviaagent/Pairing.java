package com.braviafanboy.braviaagent;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.util.Log;

import java.security.SecureRandom;

/**
 * Bearer-token pairing. Unpaired, the agent answers everyone on the LAN (like
 * the TV's own REST port). Once a token exists every request except /v1/info
 * and POST /v1/pair must carry {@code Authorization: Bearer <token>}.
 *
 * A token is obtained either through the pairing window (opened when the TV's
 * own control service launched the status screen, or manually from it) with
 * {@code POST /v1/pair}, or delivered directly by the cloud deep link
 * {@code braviaagent://pair?token=...}. Pairing again replaces the token.
 */
public final class Pairing {
    private static final String TAG = "BraviaAgent";
    private static final String PREFS = "pairing";
    private static final String KEY_TOKEN = "token";
    private static final String KEY_NAME = "name";
    private static final String KEY_AT = "paired_at";
    public static final long WINDOW_MS = 60000;

    public interface Listener {
        void onPairingChanged();
    }

    private final SharedPreferences prefs;
    private volatile String token;
    private volatile long windowUntil;
    private Listener listener;

    public Pairing(Context context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        token = prefs.getString(KEY_TOKEN, null);
    }

    public void setListener(Listener l) {
        listener = l;
    }

    public boolean isPaired() {
        return token != null;
    }

    public String pairedName() {
        return prefs.getString(KEY_NAME, null);
    }

    public long pairedAt() {
        return prefs.getLong(KEY_AT, 0);
    }

    public boolean windowOpen() {
        return SystemClock.elapsedRealtime() < windowUntil;
    }

    public long windowSecondsLeft() {
        long left = windowUntil - SystemClock.elapsedRealtime();
        return left > 0 ? (left + 999) / 1000 : 0;
    }

    public void openWindow() {
        windowUntil = SystemClock.elapsedRealtime() + WINDOW_MS;
        Log.i(TAG, "pairing window open for " + (WINDOW_MS / 1000) + " s");
        notifyChanged();
    }

    public void closeWindow() {
        windowUntil = 0;
        notifyChanged();
    }

    /** POST /v1/pair during the window: mint and store a new token. */
    public synchronized String pair(String name) {
        if (!windowOpen()) return null;
        byte[] b = new byte[32];
        new SecureRandom().nextBytes(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        store(sb.toString(), name);
        windowUntil = 0;
        return token;
    }

    /** Token delivered out of band (cloud deep link). */
    public synchronized void adopt(String newToken, String name) {
        if (newToken == null || newToken.length() < 16) return;
        store(newToken, name);
        windowUntil = 0;
    }

    public synchronized void clear() {
        token = null;
        prefs.edit().clear().apply();
        Log.i(TAG, "pairing cleared");
        notifyChanged();
    }

    /**
     * Unpaired, requests without credentials are allowed (nothing to check
     * against) but a presented token is refused: a client that believes it is
     * paired must learn that it is not, instead of being silently accepted.
     */
    public boolean authorized(String authorizationHeader) {
        String t = token;
        if (t == null) return authorizationHeader == null;
        if (authorizationHeader == null) return false;
        String h = authorizationHeader.trim();
        if (!h.regionMatches(true, 0, "Bearer ", 0, 7)) return false;
        return constantTimeEquals(h.substring(7).trim(), t);
    }

    private void store(String newToken, String name) {
        token = newToken;
        prefs.edit().putString(KEY_TOKEN, newToken).putString(KEY_NAME, name == null ? "" : name)
            .putLong(KEY_AT, System.currentTimeMillis()).apply();
        Log.i(TAG, "paired with " + name);
        notifyChanged();
    }

    private void notifyChanged() {
        Listener l = listener;
        if (l != null) l.onPairingChanged();
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a.length() != b.length()) return false;
        int r = 0;
        for (int i = 0; i < a.length(); i++) r |= a.charAt(i) ^ b.charAt(i);
        return r == 0;
    }
}
