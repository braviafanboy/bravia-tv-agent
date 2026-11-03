package com.braviafanboy.braviaagent;

import android.util.Log;

import com.braviafanboy.braviaagent.readers.MediaReader;
import com.braviafanboy.braviaagent.readers.PictureReader;
import com.braviafanboy.braviaagent.readers.ThemesReader;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import fi.iki.elonen.NanoHTTPD;

/** The agent's HTTP surface: /v1/info, /v1/state, /v1/themes, /v1/events (SSE). */
public final class HttpServer extends NanoHTTPD {
    private static final String TAG = "BraviaAgent";
    public static final int CONTRACT = 1;
    public static final String PICTURE_REFRESH_PATH = "/v1/picture/refresh";
    public static final String PICTURE_PATH = "/v1/picture";
    public static final String PICTURE_CONFIG_PATH = "/v1/picture/config";
    public static final String POWER_SAVING_PATH = "/v1/power_saving";
    public static final String REST_REGISTER_PATH = "/v1/rest/register";
    public static final String REST_AUTH_PATH = "/v1/rest/auth";
    public static final String REST_PSK_PATH = "/v1/rest/psk";
    private static final long HEARTBEAT_MS = 30000;
    private static final int QUEUE_DEPTH = 16;

    private final StateStore store;
    private final ThemesReader themes;
    private final InfoProvider info;
    private final MediaReader media;
    private final Pairing pairing;
    private final PictureReader picture;
    private RestClient rest;
    private RestAuth restAuth;
    private final Updater updater;
    private final java.util.Set<Thread> streams = java.util.Collections.synchronizedSet(new java.util.HashSet<>());

    public interface InfoProvider {
        JSONObject info();
    }

    public HttpServer(android.content.Context context, int port, StateStore store, ThemesReader themes, InfoProvider info, MediaReader media, Pairing pairing, PictureReader picture) {
        super(port);
        this.store = store;
        this.themes = themes;
        this.info = info;
        this.media = media;
        this.pairing = pairing;
        this.picture = picture;
        this.updater = new Updater(context);
    }

    /** The REST client and credential store used by the registration endpoints. */
    public void setRest(RestClient rest, RestAuth auth) {
        this.rest = rest;
        this.restAuth = auth;
    }

    @Override
    protected boolean useGzipWhenAccepted(Response r) {
        // Never: a gzipped event stream sits in the deflater until enough
        // bytes accumulate, which turns 10 s updates into minute-long gaps.
        return false;
    }

    @Override
    public Response serve(IHTTPSession session) {
        // Request threads yield to the TV's video pipeline.
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
        String path = session.getUri();
        if ("/v1/pair".equals(path)) return pair(session);
        if ("/v1/info".equals(path) || "/".equals(path)) return json(info.info());
        if (!pairing.authorized(session.getHeaders().get("authorization"))) {
            Response r = error(Response.Status.UNAUTHORIZED, "unauthorized");
            r.addHeader("WWW-Authenticate", "Bearer realm=\"bravia-agent\"");
            return r;
        }
        if ("/v1/update".equals(path)) return update(session);
        if (PICTURE_REFRESH_PATH.equals(path)) return pictureRefresh(session);
        if (PICTURE_PATH.equals(path)) return pictureWrite(session);
        if (PICTURE_CONFIG_PATH.equals(path)) return pictureConfig(session);
        if (POWER_SAVING_PATH.equals(path)) return powerSaving(session);
        if (REST_REGISTER_PATH.equals(path)) return restRegister(session);
        if (REST_AUTH_PATH.equals(path)) return restForget(session);
        if (REST_PSK_PATH.equals(path)) return restPsk(session);
        if (session.getMethod() != Method.GET) return error(Response.Status.METHOD_NOT_ALLOWED, "method_not_allowed");
        switch (path) {
            case "/v1/state": return json(store.snapshot());
            case "/v1/themes": {
                JSONObject o = new JSONObject();
                try { o.put("themes", themes.themes()); } catch (Exception ignored) { }
                return json(o);
            }
            case "/v1/events": return events();
            case MediaReader.ARTWORK_PATH: {
                byte[] img = media == null ? null : media.artwork();
                if (img == null) return error(Response.Status.NOT_FOUND, "no_artwork");
                Response r = newFixedLengthResponse(Response.Status.OK, "image/webp", new ByteArrayInputStream(img), img.length);
                r.addHeader("Cache-Control", "no-store");
                return r;
            }
            default: return error(Response.Status.NOT_FOUND, "not_found");
        }
    }

    /** POST /v1/pair?name=<client>: mint a token while the pairing window is open. */
    private Response pair(IHTTPSession session) {
        if (session.getMethod() != Method.POST) return error(Response.Status.METHOD_NOT_ALLOWED, "method_not_allowed");
        java.util.List<String> names = session.getParameters().get("name");
        String name = names == null || names.isEmpty() ? "unknown" : names.get(0);
        String token = pairing.pair(name);
        if (token == null) return error(Response.Status.FORBIDDEN, "pairing_closed");
        JSONObject o = new JSONObject();
        try { o.put("token", token); } catch (Exception ignored) { }
        log("paired " + name + " from " + session.getRemoteIpAddress());
        return json(o);
    }

    /** POST /v1/update?url=&sha256=&version=: stage a self-update (run by UpdateActivity). */
    private Response update(IHTTPSession session) {
        if (session.getMethod() != Method.POST) return error(Response.Status.METHOD_NOT_ALLOWED, "method_not_allowed");
        java.util.Map<String, java.util.List<String>> q = session.getParameters();
        String url = q.containsKey("url") ? q.get("url").get(0) : null;
        if (url == null || !(url.startsWith("https://") || url.startsWith("http://"))) return error(Response.Status.BAD_REQUEST, "url_required");
        updater.setPending(url, q.containsKey("sha256") ? q.get("sha256").get(0) : null, q.containsKey("version") ? q.get("version").get(0) : null);
        JSONObject o = new JSONObject();
        try { o.put("pending", url); } catch (Exception ignored) { }
        log("update staged: " + url);
        return json(o);
    }

    /** POST /v1/picture/refresh: re-read the REST picture settings now (async; the change arrives as a state event). */
    private Response pictureRefresh(IHTTPSession session) {
        if (session.getMethod() != Method.POST) return error(Response.Status.METHOD_NOT_ALLOWED, "method_not_allowed");
        if (picture == null) return error(Response.Status.NOT_FOUND, "not_found");
        log("picture: refresh requested");
        picture.refresh();
        JSONObject o = new JSONObject();
        try { o.put("queued", true); } catch (Exception ignored) { }
        return json(o);
    }

    /** POST /v1/picture {"target", "value"}: write one picture target (validated against the last read). */
    private Response pictureWrite(IHTTPSession session) {
        if (session.getMethod() != Method.POST) return error(Response.Status.METHOD_NOT_ALLOWED, "method_not_allowed");
        JSONObject body = body(session);
        if (picture == null) return error(Response.Status.NOT_FOUND, "not_found");
        if (body == null || !body.has("target") || !body.has("value")) return error(Response.Status.BAD_REQUEST, "target_and_value_required");
        try {
            picture.writeTarget(body.optString("target"), body.optString("value"));
        } catch (PictureReader.WriteException e) {
            return writeError(e.code);
        }
        return ok();
    }

    /** POST /v1/power_saving?mode=off|low|high */
    private Response powerSaving(IHTTPSession session) {
        if (session.getMethod() != Method.POST) return error(Response.Status.METHOD_NOT_ALLOWED, "method_not_allowed");
        String mode = param(session, "mode");
        if (picture == null) return error(Response.Status.NOT_FOUND, "not_found");
        if (mode == null) return error(Response.Status.BAD_REQUEST, "mode_required");
        try {
            picture.writePowerSaving(mode);
        } catch (PictureReader.WriteException e) {
            return writeError(e.code);
        }
        return ok();
    }

    /** POST /v1/picture/config {"poll_s", "event_refresh", "watch": [...]}: refresh settings, stored on the TV. */
    private Response pictureConfig(IHTTPSession session) {
        if (session.getMethod() != Method.POST) return error(Response.Status.METHOD_NOT_ALLOWED, "method_not_allowed");
        JSONObject body = body(session);
        if (picture == null) return error(Response.Status.NOT_FOUND, "not_found");
        if (body == null) return error(Response.Status.BAD_REQUEST, "json_body_required");
        Integer pollS = body.has("poll_s") ? body.optInt("poll_s", PictureReader.DEFAULT_POLL_S) : null;
        Boolean eventRefresh = body.has("event_refresh") ? body.optBoolean("event_refresh", true) : null;
        java.util.List<String> watch = null;
        JSONArray w = body.optJSONArray("watch");
        if (w != null) {
            watch = new java.util.ArrayList<>();
            for (int i = 0; i < w.length(); i++) watch.add(w.optString(i));
        }
        int effective = picture.configure(pollS, eventRefresh, watch);
        JSONObject o = new JSONObject();
        try {
            o.put("poll_s", effective);
            o.put("event_refresh", picture.eventRefresh());
            o.put("watch", new JSONArray(picture.watched()));
        } catch (Exception ignored) { }
        return json(o);
    }

    /**
     * POST /v1/rest/register: ask the TV to show a registration PIN.
     * POST /v1/rest/register?pin=NNNN: complete it with the PIN the TV showed.
     */
    private Response restRegister(IHTTPSession session) {
        if (session.getMethod() != Method.POST) return error(Response.Status.METHOD_NOT_ALLOWED, "method_not_allowed");
        if (rest == null || restAuth == null) return error(Response.Status.NOT_FOUND, "not_found");
        String pin = param(session, "pin");
        RestAuth.Outcome outcome;
        if (pin == null) {
            outcome = restAuth.beginRegister(rest);
        } else {
            if (!pin.matches("\\d{1,8}")) return error(Response.Status.BAD_REQUEST, "pin_digits_required");
            outcome = restAuth.completeRegister(rest, pin);
        }
        if (picture != null) picture.publish();
        switch (outcome) {
            case PIN_SHOWN: return status("pin_shown");
            case REGISTERED: return status("registered");
            case PIN_REJECTED: return error(Response.Status.FORBIDDEN, "pin_rejected");
            case UNAVAILABLE: return error(Response.Status.SERVICE_UNAVAILABLE, "rest_unavailable");
            default: return error(Response.Status.CONFLICT, "normal_auth_not_supported");
        }
    }

    /** DELETE /v1/rest/auth: forget the REST credential. */
    private Response restForget(IHTTPSession session) {
        if (session.getMethod() != Method.DELETE) return error(Response.Status.METHOD_NOT_ALLOWED, "method_not_allowed");
        if (restAuth == null) return error(Response.Status.NOT_FOUND, "not_found");
        restAuth.forget();
        if (picture != null) picture.publish();
        return ok();
    }

    /** POST /v1/rest/psk {"psk"}: store a pre-shared key and verify it. */
    private Response restPsk(IHTTPSession session) {
        if (session.getMethod() != Method.POST) return error(Response.Status.METHOD_NOT_ALLOWED, "method_not_allowed");
        if (rest == null || restAuth == null) return error(Response.Status.NOT_FOUND, "not_found");
        JSONObject body = body(session);
        String psk = body == null ? null : body.optString("psk", null);
        if (psk == null || psk.isEmpty()) return error(Response.Status.BAD_REQUEST, "psk_required");
        restAuth.setPsk(psk);
        boolean ok = restAuth.verify(rest);
        if (picture != null) picture.publish();
        JSONObject o = new JSONObject();
        try { o.put("write_authorized", ok); } catch (Exception ignored) { }
        return json(o);
    }

    private Response writeError(String code) {
        switch (code) {
            case "rest_unavailable": return error(Response.Status.SERVICE_UNAVAILABLE, code);
            case "rest_unauthorized": return error(Response.Status.FORBIDDEN, code);
            case "unknown_target": return error(Response.Status.NOT_FOUND, code);
            case "target_unavailable": return error(Response.Status.CONFLICT, code);
            case "invalid_value": return error(Response.Status.BAD_REQUEST, code);
            default: return error(Response.Status.INTERNAL_ERROR, code);
        }
    }

    private static String param(IHTTPSession session, String name) {
        java.util.List<String> v = session.getParameters().get(name);
        return v == null || v.isEmpty() ? null : v.get(0);
    }

    private static JSONObject body(IHTTPSession session) {
        try {
            java.util.Map<String, String> files = new java.util.HashMap<>();
            session.parseBody(files);
            String raw = files.get("postData");
            return raw == null || raw.isEmpty() ? null : new JSONObject(raw);
        } catch (Exception e) {
            return null;
        }
    }

    private Response ok() {
        JSONObject o = new JSONObject();
        try { o.put("ok", true); } catch (Exception ignored) { }
        return json(o);
    }

    private Response status(String s) {
        JSONObject o = new JSONObject();
        try { o.put("status", s); } catch (Exception ignored) { }
        return json(o);
    }

    private Response json(JSONObject o) {
        Response r = newFixedLengthResponse(Response.Status.OK, "application/json", o.toString());
        r.addHeader("Cache-Control", "no-store");
        return r;
    }

    private Response error(Response.Status status, String code) {
        JSONObject o = new JSONObject();
        try { o.put("error", code); } catch (Exception ignored) { }
        return newFixedLengthResponse(status, "application/json", o.toString());
    }

    /**
     * Server-Sent Events: a state event on change (coalesced), heartbeats every
     * 30 s. All socket writes happen on the per-client writer thread; the
     * StateStore listener only queues, so a dead client can never block or
     * crash the main thread. The pipe's read end is closed by NanoHTTPD when
     * the client goes away, which makes the next write fail and ends the
     * subscription.
     */
    private Response events() {
        final PipedInputStream in = new PipedInputStream(65536);
        final PipedOutputStream out;
        try {
            out = new PipedOutputStream(in);
        } catch (IOException e) {
            return error(Response.Status.INTERNAL_ERROR, "stream_failed");
        }
        final BlockingQueue<String> queue = new LinkedBlockingQueue<>(QUEUE_DEPTH);
        final StateStore.Listener listener = snapshot -> queue.offer(stateEvent(snapshot));
        queue.offer(stateEvent(store.snapshot()));
        store.addListener(listener);
        if (media != null) media.onClientSubscribed();
        Thread t = new Thread(() -> {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
            streams.add(Thread.currentThread());
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    String msg = queue.poll(HEARTBEAT_MS, TimeUnit.MILLISECONDS);
                    if (msg == null) msg = "event: heartbeat\ndata: {}\n\n";
                    out.write(msg.getBytes(StandardCharsets.UTF_8));
                    out.flush();
                }
            } catch (IOException | InterruptedException e) {
                // Pipe closed: client disconnected.
            } finally {
                streams.remove(Thread.currentThread());
                store.removeListener(listener);
                try { out.close(); } catch (IOException ignored) { }
            }
        }, "sse-writer");
        t.setDaemon(true);
        t.start();
        Response r = newChunkedResponse(Response.Status.OK, "text/event-stream", in);
        r.addHeader("Cache-Control", "no-store");
        r.addHeader("Connection", "keep-alive");
        return r;
    }

    /** End every open event stream (e.g. the token changed: clients must re-authenticate). */
    public void closeStreams() {
        Thread[] open;
        synchronized (streams) { open = streams.toArray(new Thread[0]); }
        for (Thread t : open) t.interrupt();
    }

    private static String stateEvent(JSONObject snapshot) {
        return "event: state\ndata: " + snapshot.toString() + "\n\n";
    }

    static JSONArray strings(Iterable<String> items) {
        JSONArray a = new JSONArray();
        for (String s : items) a.put(s);
        return a;
    }

    static void log(String msg) {
        Log.i(TAG, msg);
    }
}
