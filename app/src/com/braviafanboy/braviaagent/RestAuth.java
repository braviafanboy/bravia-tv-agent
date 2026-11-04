package com.braviafanboy.braviaagent;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * The credential the agent presents to the TV's REST API for writes: the
 * pre-shared key the user set on the TV (IP control → Authentication →
 * Normal and Pre-Shared Key), handed over by Home Assistant. Kept in
 * app-private storage; never logged or served.
 *
 * <p>Sony's PIN-based Normal authentication is not supported: the TV's PIN
 * service refuses every non-Japanese set, and the key works everywhere.
 */
public final class RestAuth {
    private static final String TAG = "BraviaAgent";
    private static final String PREFS = "rest";

    private final SharedPreferences prefs;

    public RestAuth(Context context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        // Agent 0.3.0 also kept a Normal-auth session cookie; drop any leftovers.
        if (prefs.contains("cookie") || prefs.contains("cookie_expires") || prefs.contains("client_id")) {
            prefs.edit().remove("cookie").remove("cookie_expires").remove("client_id").apply();
        }
    }

    /** "none" or "psk". */
    public synchronized String mode() {
        return prefs.getString("psk", null) != null ? "psk" : "none";
    }

    /** Whether the stored key last worked (verification or a write). */
    public synchronized boolean writeAuthorized() {
        return "psk".equals(mode()) && prefs.getBoolean("authorized", false);
    }

    /** Headers that carry the key; empty when there is none. */
    public synchronized Map<String, String> headers() {
        String psk = prefs.getString("psk", null);
        return psk == null ? Collections.emptyMap() : Collections.singletonMap("X-Auth-PSK", psk);
    }

    public synchronized void markAuthorized(boolean ok) {
        prefs.edit().putBoolean("authorized", ok).apply();
    }

    /** Store a pre-shared key. Call {@link #verify} after. */
    public synchronized void setPsk(String psk) {
        prefs.edit().putString("psk", psk).putBoolean("authorized", false).apply();
        Log.i(TAG, "rest auth: pre-shared key stored");
    }

    /** Drop the key. */
    public synchronized void forget() {
        prefs.edit().remove("psk").putBoolean("authorized", false).apply();
        Log.i(TAG, "rest auth: credentials forgotten");
    }

    /**
     * Check the stored key with a read that requires it
     * ({@code system.getSystemInformation}; its contents are discarded).
     */
    public boolean verify(RestClient rest) {
        Map<String, String> h = headers();
        if (h.isEmpty()) { markAuthorized(false); return false; }
        try {
            rest.exchange("system", "getSystemInformation", "1.0", new JSONArray(), h);
            markAuthorized(true);
            return true;
        } catch (RestClient.RestException e) {
            if (e.kind == RestClient.RestException.Kind.AUTH_REQUIRED) markAuthorized(false);
            return false;
        }
    }

    /** For the status dump: no secrets. */
    public synchronized Map<String, Object> describe() {
        Map<String, Object> m = new HashMap<>();
        m.put("mode", mode());
        m.put("write_authorized", writeAuthorized());
        return m;
    }
}
