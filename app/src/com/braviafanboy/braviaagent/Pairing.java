package com.braviafanboy.braviaagent;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.util.Log;

import com.braviafanboy.braviaagent.core.PairingState;

import java.security.SecureRandom;

/**
 * The agent's pairing with Home Assistant, kept in app-private storage.
 *
 * <p>Every request except /v1/info and POST /v1/pair must carry
 * {@code Authorization: Bearer <token>}, paired or not: an agent nobody has
 * paired with answers nothing else. A client gets the token by asking for it
 * with POST /v1/pair; the viewer then allows it on the agent's screen, after
 * comparing the code shown there with the one the client shows. See
 * {@link PairingState} for the rules. Tokens delivered by deep link are not
 * accepted. Pairing again replaces the token.
 *
 * <p>This class adds Android's clock and storage to {@link PairingState} and
 * tells the service when the token changed.
 */
public final class Pairing {
    private static final String TAG = "BraviaAgent";
    private static final String PREFS = "pairing";
    private static final String KEY_TOKEN = "token";
    private static final String KEY_NAME = "name";
    private static final String KEY_PEER = "peer";
    private static final String KEY_AT = "paired_at";

    public interface Listener {
        /** {@code tokenChanged}: paired, re-paired or cleared. */
        void onPairingChanged(boolean tokenChanged);
    }

    private final PairingState state;
    private volatile Listener listener;

    public Pairing(Context context) {
        final SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        state = new PairingState(new PairingState.Clock() {
            @Override public long elapsedMs() {
                return SystemClock.elapsedRealtime();
            }

            @Override public long wallMs() {
                return System.currentTimeMillis();
            }
        }, new PairingState.Storage() {
            @Override public PairingState.Paired load() {
                String token = prefs.getString(KEY_TOKEN, null);
                if (token == null) return null;
                return new PairingState.Paired(token, prefs.getString(KEY_NAME, ""),
                    prefs.getString(KEY_PEER, null), prefs.getLong(KEY_AT, 0));
            }

            @Override public void save(PairingState.Paired p) {
                prefs.edit().putString(KEY_TOKEN, p.token).putString(KEY_NAME, p.name)
                    .putString(KEY_PEER, p.peer).putLong(KEY_AT, p.atMs).apply();
            }

            @Override public void clear() {
                prefs.edit().clear().apply();
            }
        }, new SecureRandom());
    }

    public void setListener(Listener l) {
        listener = l;
    }

    /** The SHA-256 of the certificate the agent serves, which pairing codes are tied to. */
    public void setCertificate(String fingerprintHex) {
        state.setCertificate(fingerprintHex);
    }

    public boolean isPaired() {
        return state.isPaired();
    }

    /** What the paired client called itself, or null. */
    public String pairedName() {
        PairingState.Paired p = state.paired();
        return p == null ? null : p.name;
    }

    /** The address the paired client paired from, or null (also for pairings made before it was recorded). */
    public String pairedPeer() {
        PairingState.Paired p = state.paired();
        return p == null ? null : p.peer;
    }

    public boolean authorized(String authorizationHeader) {
        return state.authorized(authorizationHeader);
    }

    /** The request that is waiting, or null. */
    public PairingState.Request pending() {
        return state.pending();
    }

    /** POST /v1/pair?name=…&commit=…: a client asks to pair. Nothing is shown on the TV. */
    public PairingState.Reply request(String name, String peer, String commit) {
        PairingState.Reply r = state.request(name, peer, commit);
        if (r.request != null) Log.i(TAG, "pairing requested from " + peer);
        return r;
    }

    /** POST /v1/pair?request=<id>&reveal=…: the client reveals its secret, which gives the request its code. */
    public PairingState.Reply reveal(String requestId, String secret) {
        return state.reveal(requestId, secret);
    }

    /** The request is on screen; false when it is gone. */
    public boolean shown(String requestId) {
        return state.shown(requestId);
    }

    /** The viewer allowed the request; null when it is gone. */
    public PairingState.Request accept(String requestId) {
        PairingState.Request r = state.accept(requestId);
        if (r != null) Log.i(TAG, "pairing request from " + r.peer + " allowed on the TV");
        return r;
    }

    /** The viewer denied the request; null when it is gone. */
    public PairingState.Request deny(String requestId) {
        PairingState.Request r = state.deny(requestId);
        if (r != null) Log.i(TAG, "pairing request from " + r.peer + " denied on the TV");
        return r;
    }

    /** POST /v1/pair?request=<id>: the client that asked collects its token. */
    public PairingState.Reply collect(String requestId) {
        PairingState.Reply r = state.collect(requestId);
        if (r.token != null) {
            Log.i(TAG, "paired with " + pairedName() + " at " + pairedPeer());
            notifyChanged(true);
        }
        return r;
    }

    public void clear() {
        state.clear();
        Log.i(TAG, "pairing cleared");
        notifyChanged(true);
    }

    private void notifyChanged(boolean tokenChanged) {
        Listener l = listener;
        if (l != null) l.onPairingChanged(tokenChanged);
    }
}
