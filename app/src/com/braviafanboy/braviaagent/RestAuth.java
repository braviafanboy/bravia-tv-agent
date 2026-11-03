package com.braviafanboy.braviaagent;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The credential the agent presents to the TV's REST API for writes.
 *
 * <p>Two kinds, never both: a Normal-auth session cookie (registration shows a
 * PIN on the TV; the cookie lasts 14 days and is renewed by registering again
 * while it is valid), or a pre-shared key the user set on the TV. Both stay in
 * app-private storage and are never logged or served.
 */
public final class RestAuth {
    private static final String TAG = "BraviaAgent";
    private static final String PREFS = "rest";
    private static final String NICKNAME = "HA TV Agent";
    private static final long DAY_MS = 24L * 3600 * 1000;
    /** Renew once less than this is left (the cookie lasts 14 days). */
    private static final long RENEW_BEFORE_MS = 7 * DAY_MS;
    private static final Pattern MAX_AGE = Pattern.compile("(?i)max-age=(\\d+)");

    public enum Outcome { PIN_SHOWN, REGISTERED, PIN_REJECTED, NOT_SUPPORTED, UNAVAILABLE }

    private final SharedPreferences prefs;
    private long lastRenewAttempt;

    public RestAuth(Context context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (prefs.getString("client_id", null) == null) {
            prefs.edit().putString("client_id", "BraviaAgent:" + UUID.randomUUID()).apply();
        }
    }

    /** "none", "cookie" or "psk". */
    public synchronized String mode() {
        if (prefs.getString("cookie", null) != null) return "cookie";
        if (prefs.getString("psk", null) != null) return "psk";
        return "none";
    }

    /** Whether the last check (registration, verification or write) succeeded with the stored credential. */
    public synchronized boolean writeAuthorized() {
        return !"none".equals(mode()) && prefs.getBoolean("authorized", false) && !cookieExpired();
    }

    /** Cookie expiry in ms since the epoch, or 0. */
    public synchronized long cookieExpiresMs() {
        return "cookie".equals(mode()) ? prefs.getLong("cookie_expires", 0) : 0;
    }

    private boolean cookieExpired() {
        long exp = prefs.getLong("cookie_expires", 0);
        return "cookie".equals(mode()) && exp > 0 && exp < System.currentTimeMillis();
    }

    /** Headers that carry the credential; empty when there is none. */
    public synchronized Map<String, String> headers() {
        String cookie = prefs.getString("cookie", null);
        if (cookie != null) return Collections.singletonMap("Cookie", "auth=" + cookie);
        String psk = prefs.getString("psk", null);
        if (psk != null) return Collections.singletonMap("X-Auth-PSK", psk);
        return Collections.emptyMap();
    }

    public synchronized void markAuthorized(boolean ok) {
        prefs.edit().putBoolean("authorized", ok).apply();
    }

    /** Store a pre-shared key (replaces any cookie). Call {@link #verify} after. */
    public synchronized void setPsk(String psk) {
        prefs.edit().remove("cookie").remove("cookie_expires").putString("psk", psk).putBoolean("authorized", false).apply();
        Log.i(TAG, "rest auth: pre-shared key stored");
    }

    /** Drop every credential. */
    public synchronized void forget() {
        prefs.edit().remove("cookie").remove("cookie_expires").remove("psk").putBoolean("authorized", false).apply();
        Log.i(TAG, "rest auth: credentials forgotten");
    }

    /** Step 1 of Normal auth: ask the TV to show a PIN. */
    public Outcome beginRegister(RestClient rest) {
        try {
            RestClient.Reply r = rest.exchange("accessControl", "actRegister", "1.0", registerParams(), null);
            // No challenge: the TV does not require authentication at all.
            if (r.authCookie != null) { storeCookie(r.authCookie); return Outcome.REGISTERED; }
            return Outcome.NOT_SUPPORTED;
        } catch (RestClient.RestException e) {
            if (e.kind == RestClient.RestException.Kind.AUTH_REQUIRED && e.code == 401) return Outcome.PIN_SHOWN;
            if (e.kind == RestClient.RestException.Kind.UNAVAILABLE) return Outcome.UNAVAILABLE;
            Log.w(TAG, "rest auth: register begin " + e.kind + " " + e.code);
            return Outcome.NOT_SUPPORTED;
        }
    }

    /** Step 2: send the PIN the TV showed; stores the session cookie on success. */
    public Outcome completeRegister(RestClient rest, String pin) {
        String basic = Base64.encodeToString((":" + pin).getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
        try {
            RestClient.Reply r = rest.exchange("accessControl", "actRegister", "1.0", registerParams(),
                Collections.singletonMap("Authorization", "Basic " + basic));
            if (r.authCookie == null) {
                Log.w(TAG, "rest auth: registered but no cookie returned");
                return Outcome.NOT_SUPPORTED;
            }
            storeCookie(r.authCookie);
            Log.i(TAG, "rest auth: registered (Normal)");
            return Outcome.REGISTERED;
        } catch (RestClient.RestException e) {
            if (e.kind == RestClient.RestException.Kind.AUTH_REQUIRED) return Outcome.PIN_REJECTED;
            if (e.kind == RestClient.RestException.Kind.UNAVAILABLE) return Outcome.UNAVAILABLE;
            Log.w(TAG, "rest auth: register complete " + e.kind + " " + e.code);
            return Outcome.NOT_SUPPORTED;
        }
    }

    /** Re-register with the current cookie for a fresh 14 days. True on success. */
    public boolean renew(RestClient rest) {
        Map<String, String> h;
        synchronized (this) {
            if (!"cookie".equals(mode())) return false;
            lastRenewAttempt = System.currentTimeMillis();
            h = headers();
        }
        try {
            RestClient.Reply r = rest.exchange("accessControl", "actRegister", "1.0", registerParams(), h);
            if (r.authCookie != null) {
                storeCookie(r.authCookie);
                Log.i(TAG, "rest auth: cookie renewed");
                return true;
            }
            Log.w(TAG, "rest auth: renewal returned no cookie");
        } catch (RestClient.RestException e) {
            if (e.kind == RestClient.RestException.Kind.AUTH_REQUIRED) markAuthorized(false);
            Log.w(TAG, "rest auth: renewal " + e.kind + " " + e.code);
        }
        return false;
    }

    /** Renew when less than 7 days remain, at most once a day. */
    public void renewIfDue(RestClient rest) {
        synchronized (this) {
            if (!"cookie".equals(mode())) return;
            long now = System.currentTimeMillis();
            if (cookieExpiresMs() - now > RENEW_BEFORE_MS) return;
            if (now - lastRenewAttempt < DAY_MS) return;
        }
        renew(rest);
    }

    /**
     * Check the stored credential with a read that requires it
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

    private JSONArray registerParams() {
        JSONArray params = new JSONArray();
        try {
            JSONObject client = new JSONObject();
            client.put("clientid", prefs.getString("client_id", "BraviaAgent"));
            client.put("nickname", NICKNAME);
            client.put("level", "private");
            params.put(client);
            JSONArray caps = new JSONArray();
            JSONObject wol = new JSONObject();
            wol.put("value", "yes");
            wol.put("function", "WOL");
            caps.put(wol);
            params.put(caps);
        } catch (Exception ignored) {
        }
        return params;
    }

    private synchronized void storeCookie(String setCookie) {
        String value = setCookie;
        int semi = value.indexOf(';');
        if (semi >= 0) value = value.substring(0, semi);
        value = value.substring(value.indexOf('=') + 1).trim();
        long maxAge = 14 * 24 * 3600;
        Matcher m = MAX_AGE.matcher(setCookie);
        if (m.find()) {
            try { maxAge = Long.parseLong(m.group(1)); } catch (NumberFormatException ignored) { }
        }
        prefs.edit()
            .remove("psk")
            .putString("cookie", value)
            .putLong("cookie_expires", System.currentTimeMillis() + maxAge * 1000)
            .putBoolean("authorized", true)
            .apply();
    }

    /** For the status dump: no secrets. */
    public synchronized Map<String, Object> describe() {
        Map<String, Object> m = new HashMap<>();
        m.put("mode", mode());
        m.put("write_authorized", writeAuthorized());
        m.put("cookie_expires_ms", cookieExpiresMs());
        return m;
    }
}
