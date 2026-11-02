package com.braviafanboy.braviaagent.readers;

import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import com.braviafanboy.braviaagent.MediaListenerService;
import com.braviafanboy.braviaagent.StateStore;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Now-playing from the active media sessions (needs Notification access).
 * Publishes the most relevant session: playing first, then paused/buffering,
 * then whatever is registered. While playing, the position is refreshed every
 * 10 s so the event stream carries a usable progress value.
 */
public final class MediaReader {
    private static final String TAG = "BraviaAgent";
    private static final long TICK_MS = 10000;
    private static final long RECHECK_MS = 30000;
    private static final int ARTWORK_MAX = 512;
    public static final String ARTWORK_PATH = "/v1/media/artwork";

    private final StateStore store;
    private final MediaSessionManager manager;
    private final NotificationManager notifications;
    private final ComponentName listener;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<MediaController> controllers = new ArrayList<>();
    private final List<MediaController.Callback> callbacks = new ArrayList<>();
    private boolean attached;
    private volatile byte[] artwork;
    private String artworkKey;

    private final MediaSessionManager.OnActiveSessionsChangedListener sessions = this::attach;
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            publish();
            handler.postDelayed(this, TICK_MS);
        }
    };
    private final Runnable recheck = new Runnable() {
        @Override public void run() {
            recheckPermission();
            handler.postDelayed(this, RECHECK_MS);
        }
    };

    public MediaReader(Context context, StateStore store) {
        this.store = store;
        manager = (MediaSessionManager) context.getSystemService(Context.MEDIA_SESSION_SERVICE);
        notifications = context.getSystemService(NotificationManager.class);
        listener = new ComponentName(context, MediaListenerService.class);
    }

    public boolean isGranted() {
        return notifications != null && notifications.isNotificationListenerAccessGranted(listener);
    }

    public byte[] artwork() {
        return artwork;
    }

    public void start() {
        handler.post(recheck);
    }

    public void stop() {
        handler.removeCallbacks(recheck);
        handler.removeCallbacks(tick);
        detach();
        if (attached && manager != null) {
            try { manager.removeOnActiveSessionsChangedListener(sessions); } catch (Exception ignored) { }
            attached = false;
        }
    }

    /** Called when Notification access may have been granted or revoked. */
    public void recheckPermission() {
        boolean granted = isGranted();
        if (granted && !attached && manager != null) {
            try {
                manager.addOnActiveSessionsChangedListener(sessions, listener, handler);
                attached = true;
                attach(manager.getActiveSessions(listener));
                Log.i(TAG, "media sessions attached");
            } catch (SecurityException e) {
                Log.w(TAG, "media sessions not permitted yet: " + e.getMessage());
            }
        } else if (!granted && attached) {
            try { manager.removeOnActiveSessionsChangedListener(sessions); } catch (Exception ignored) { }
            attached = false;
            detach();
            store.update("media", null);
        } else if (!granted) {
            store.update("media", null);
        }
    }

    private void detach() {
        for (int i = 0; i < controllers.size(); i++) {
            try { controllers.get(i).unregisterCallback(callbacks.get(i)); } catch (Exception ignored) { }
        }
        controllers.clear();
        callbacks.clear();
    }

    private void attach(List<MediaController> list) {
        detach();
        if (list != null) {
            for (MediaController c : list) {
                MediaController.Callback cb = new MediaController.Callback() {
                    @Override public void onPlaybackStateChanged(PlaybackState state) { publish(); }
                    @Override public void onMetadataChanged(MediaMetadata metadata) { publish(); }
                    @Override public void onSessionDestroyed() { publish(); }
                };
                try {
                    c.registerCallback(cb, handler);
                    controllers.add(c);
                    callbacks.add(cb);
                } catch (Exception ignored) {
                }
            }
        }
        publish();
    }

    private MediaController pick() {
        MediaController best = null;
        int bestRank = -1;
        for (MediaController c : controllers) {
            PlaybackState ps = c.getPlaybackState();
            int rank = rank(ps == null ? PlaybackState.STATE_NONE : ps.getState());
            if (rank > bestRank) { best = c; bestRank = rank; }
        }
        return best;
    }

    private static int rank(int state) {
        switch (state) {
            case PlaybackState.STATE_PLAYING: return 4;
            case PlaybackState.STATE_BUFFERING: return 3;
            case PlaybackState.STATE_PAUSED: return 2;
            case PlaybackState.STATE_NONE: return 0;
            default: return 1;
        }
    }

    private void publish() {
        handler.removeCallbacks(tick);
        MediaController c = pick();
        JSONObject o = new JSONObject();
        try {
            if (c == null) {
                o.put("active", false);
                o.put("app", JSONObject.NULL);
                o.put("state", "none");
                o.put("title", JSONObject.NULL);
                o.put("artist", JSONObject.NULL);
                o.put("album", JSONObject.NULL);
                o.put("position_s", JSONObject.NULL);
                o.put("duration_s", JSONObject.NULL);
                o.put("artwork_url", JSONObject.NULL);
                artwork = null;
                artworkKey = null;
                store.update("media", o);
                return;
            }
            PlaybackState ps = c.getPlaybackState();
            MediaMetadata md = c.getMetadata();
            int state = ps == null ? PlaybackState.STATE_NONE : ps.getState();
            boolean playing = state == PlaybackState.STATE_PLAYING;
            o.put("active", playing || state == PlaybackState.STATE_PAUSED || state == PlaybackState.STATE_BUFFERING);
            o.put("app", c.getPackageName());
            o.put("state", stateName(state));
            String title = text(md, MediaMetadata.METADATA_KEY_TITLE, MediaMetadata.METADATA_KEY_DISPLAY_TITLE);
            o.put("title", title == null ? JSONObject.NULL : title);
            String artist = text(md, MediaMetadata.METADATA_KEY_ARTIST, MediaMetadata.METADATA_KEY_ALBUM_ARTIST, MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE);
            o.put("artist", artist == null ? JSONObject.NULL : artist);
            String album = text(md, MediaMetadata.METADATA_KEY_ALBUM);
            o.put("album", album == null ? JSONObject.NULL : album);
            if (ps != null && ps.getPosition() != PlaybackState.PLAYBACK_POSITION_UNKNOWN) {
                long pos = ps.getPosition();
                if (playing) pos += (long) ((SystemClock.elapsedRealtime() - ps.getLastPositionUpdateTime()) * ps.getPlaybackSpeed());
                o.put("position_s", Math.max(0, pos) / 1000.0);
            } else {
                o.put("position_s", JSONObject.NULL);
            }
            long dur = md == null ? 0 : md.getLong(MediaMetadata.METADATA_KEY_DURATION);
            o.put("duration_s", dur > 0 ? dur / 1000.0 : JSONObject.NULL);
            updateArtwork(c.getPackageName(), title, md);
            o.put("artwork_url", artwork == null ? JSONObject.NULL : ARTWORK_PATH);
            store.update("media", o);
            if (playing) handler.postDelayed(tick, TICK_MS);
        } catch (Exception e) {
            Log.w(TAG, "media publish: " + e);
        }
    }

    private void updateArtwork(String app, String title, MediaMetadata md) {
        String key = app + "|" + title;
        if (key.equals(artworkKey)) return;
        artworkKey = key;
        artwork = null;
        if (md == null) return;
        Bitmap bmp = md.getBitmap(MediaMetadata.METADATA_KEY_ART);
        if (bmp == null) bmp = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
        if (bmp == null) bmp = md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
        if (bmp == null) return;
        try {
            int w = bmp.getWidth(), h = bmp.getHeight();
            if (Math.max(w, h) > ARTWORK_MAX) {
                float s = ARTWORK_MAX / (float) Math.max(w, h);
                bmp = Bitmap.createScaledBitmap(bmp, Math.max(1, Math.round(w * s)), Math.max(1, Math.round(h * s)), true);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            bmp.compress(Bitmap.CompressFormat.PNG, 90, out);
            artwork = out.toByteArray();
        } catch (Exception ignored) {
        }
    }

    private static String text(MediaMetadata md, String... keys) {
        if (md == null) return null;
        for (String k : keys) {
            CharSequence v = md.getText(k);
            if (v != null && v.length() > 0) return v.toString();
        }
        return null;
    }

    private static String stateName(int state) {
        switch (state) {
            case PlaybackState.STATE_PLAYING: return "playing";
            case PlaybackState.STATE_PAUSED: return "paused";
            case PlaybackState.STATE_STOPPED: return "stopped";
            case PlaybackState.STATE_BUFFERING: return "buffering";
            case PlaybackState.STATE_FAST_FORWARDING: return "fast_forwarding";
            case PlaybackState.STATE_REWINDING: return "rewinding";
            case PlaybackState.STATE_ERROR: return "error";
            case PlaybackState.STATE_CONNECTING: return "connecting";
            case PlaybackState.STATE_SKIPPING_TO_NEXT:
            case PlaybackState.STATE_SKIPPING_TO_PREVIOUS:
            case PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM: return "skipping";
            case PlaybackState.STATE_NONE: return "none";
            default: return "unknown";
        }
    }
}
