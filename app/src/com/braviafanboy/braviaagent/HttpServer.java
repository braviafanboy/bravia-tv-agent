package com.braviafanboy.braviaagent;

import android.util.Log;

import com.braviafanboy.braviaagent.readers.MediaReader;
import com.braviafanboy.braviaagent.readers.PictureReader;

import org.json.JSONArray;
import org.json.JSONObject;

import com.braviafanboy.braviaagent.http.HttpRequest;
import com.braviafanboy.braviaagent.http.HttpResponse;
import com.braviafanboy.braviaagent.http.Server;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import javax.net.ServerSocketFactory;

/** The agent's HTTP surface: /v1/info, /v1/state, /v1/events (SSE), artwork, picture settings, pairing. */
public final class HttpServer implements Server.Handler {
    private static final String TAG = "BraviaAgent";
    public static final int CONTRACT = 1;
    public static final String PICTURE_REFRESH_PATH = "/v1/picture/refresh";
    public static final String PICTURE_PATH = "/v1/picture";
    public static final String PICTURE_CONFIG_PATH = "/v1/picture/config";
    public static final String POWER_SAVING_PATH = "/v1/power_saving";
    public static final String REST_AUTH_PATH = "/v1/rest/auth";
    public static final String REST_PSK_PATH = "/v1/rest/psk";
    public static final String REMOTE_FIND_PATH = "/v1/remote/find";
    public static final String NOTIFY_PATH = "/v1/notify";
    private static final long HEARTBEAT_MS = 30000;
    private static final int QUEUE_DEPTH = 16;
    /** Home Assistant holds the event stream plus a few short requests; this leaves ample headroom. */
    private static final int MAX_CONNECTIONS = 8;

    private final StateStore store;
    private final InfoProvider info;
    private final MediaReader media;
    private final Pairing pairing;
    private final PictureReader picture;
    private RestClient rest;
    private RestAuth restAuth;
    private RemoteFinder remoteFinder;
    private final Updater updater;
    private final int port;
    private Server server;
    private final java.util.Set<Thread> streams = java.util.Collections.synchronizedSet(new java.util.HashSet<>());

    public interface InfoProvider {
        JSONObject info();
    }

    public HttpServer(android.content.Context context, int port, StateStore store, InfoProvider info, MediaReader media, Pairing pairing, PictureReader picture) {
        this.port = port;
        this.store = store;
        this.info = info;
        this.media = media;
        this.pairing = pairing;
        this.picture = picture;
        this.updater = new Updater(context);
    }

    /** The REST client and credential store used by the registration endpoints. */
    private Notifier notifier;

    public void setNotifier(Notifier n) {
        this.notifier = n;
    }

    public void setRemoteFinder(RemoteFinder finder) {
        this.remoteFinder = finder;
    }

    public void setRest(RestClient rest, RestAuth auth) {
        this.rest = rest;
        this.restAuth = auth;
    }

    /** Start serving HTTPS with the agent's TLS identity. */
    public void start(ServerSocketFactory tlsFactory) throws IOException {
        server = new Server(tlsFactory, port, this, MAX_CONNECTIONS,
            // Request threads yield to the TV's video pipeline.
            () -> android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND),
            HttpServer::log);
        server.start();
    }

    public void stop() {
        if (server != null) server.stop();
    }

    @Override
    public HttpResponse handle(HttpRequest session) {
        String path = session.path;
        if ("/v1/pair".equals(path)) return pair(session);
        if ("/v1/info".equals(path) || "/".equals(path)) return json(info.info());
        if (!pairing.authorized(session.header("authorization"))) {
            return error(401, "unauthorized").header("WWW-Authenticate", "Bearer realm=\"bravia-agent\"");
        }
        if ("/v1/update".equals(path)) return update(session);
        if (PICTURE_REFRESH_PATH.equals(path)) return pictureRefresh(session);
        if (PICTURE_PATH.equals(path)) return pictureWrite(session);
        if (PICTURE_CONFIG_PATH.equals(path)) return pictureConfig(session);
        if (POWER_SAVING_PATH.equals(path)) return powerSaving(session);
        if (REST_AUTH_PATH.equals(path)) return restForget(session);
        if (REST_PSK_PATH.equals(path)) return restPsk(session);
        if (REMOTE_FIND_PATH.equals(path)) {
            // POST /v1/remote/find: beep the remote; waits until the first beep
            // command is written or fails (<= ~15 s). {"state": ringing | found |
            // unsupported | no_remote | no_permission | failed}.
            if (!"POST".equals(session.method)) return error(405, "method_not_allowed");
            if (remoteFinder == null) return error(404, "not_found");
            JSONObject o = new JSONObject();
            try { o.put("state", remoteFinder.find()); } catch (Exception ignored) { }
            return json(o);
        }
        if (NOTIFY_PATH.equals(path)) {
            // POST /v1/notify {"title", "message", "duration_s"}: show a message
            // on the TV screen. {"state": shown | notification | failed}.
            if (!"POST".equals(session.method)) return error(405, "method_not_allowed");
            if (notifier == null) return error(404, "not_found");
            JSONObject body = body(session);
            if (body == null) return error(400, "message_required");
            String msg = body.optString("message", "");
            String title = body.optString("title", "");
            if (msg.isEmpty() && title.isEmpty()) return error(400, "message_required");
            int dur = body.optInt("duration_s", 0);
            String icon = body.optString("icon", "");
            JSONObject o = new JSONObject();
            try {
                o.put("state", notifier.show(title, msg, dur, icon));
                o.put("overlay", notifier.canOverlay());
            } catch (Exception ignored) { }
            return json(o);
        }
        if (!"GET".equals(session.method)) return error(405, "method_not_allowed");
        switch (path) {
            case "/v1/state": return json(store.snapshot());
            case "/v1/events": return events();
            case MediaReader.ARTWORK_PATH: {
                byte[] img = media == null ? null : media.artwork();
                if (img == null) return error(404, "no_artwork");
                return HttpResponse.of(200, "image/jpeg", img).header("Cache-Control", "no-store");
            }
            default: return error(404, "not_found");
        }
    }

    /** POST /v1/pair?name=<client>: mint a token while the pairing window is open. */
    private HttpResponse pair(HttpRequest session) {
        if (!"POST".equals(session.method)) return error(405, "method_not_allowed");
        String name = param(session, "name");
        if (name == null || name.isEmpty()) name = "unknown";
        String token = pairing.pair(name);
        if (token == null) return error(403, "pairing_closed");
        JSONObject o = new JSONObject();
        try { o.put("token", token); } catch (Exception ignored) { }
        log("paired " + name + " from " + session.remoteAddress);
        return json(o);
    }

    /** POST /v1/update?url=&sha256=&version=: stage a self-update (run by UpdateActivity). */
    private HttpResponse update(HttpRequest session) {
        if (!"POST".equals(session.method)) return error(405, "method_not_allowed");
        String url = param(session, "url");
        if (url == null || !(url.startsWith("https://") || url.startsWith("http://"))) return error(400, "url_required");
        updater.setPending(url, param(session, "sha256"), param(session, "version"));
        JSONObject o = new JSONObject();
        try { o.put("pending", url); } catch (Exception ignored) { }
        log("update staged: " + url);
        return json(o);
    }

    /** POST /v1/picture/refresh: re-read the REST picture settings now (async; the change arrives as a state event). */
    private HttpResponse pictureRefresh(HttpRequest session) {
        if (!"POST".equals(session.method)) return error(405, "method_not_allowed");
        if (picture == null) return error(404, "not_found");
        log("picture: refresh requested");
        picture.refresh();
        JSONObject o = new JSONObject();
        try { o.put("queued", true); } catch (Exception ignored) { }
        return json(o);
    }

    /** POST /v1/picture {"target", "value"}: write one picture target (validated against the last read). */
    private HttpResponse pictureWrite(HttpRequest session) {
        if (!"POST".equals(session.method)) return error(405, "method_not_allowed");
        JSONObject body = body(session);
        if (picture == null) return error(404, "not_found");
        if (body == null || !body.has("target") || !body.has("value")) return error(400, "target_and_value_required");
        try {
            picture.writeTarget(body.optString("target"), body.optString("value"));
        } catch (PictureReader.WriteException e) {
            return writeError(e.code);
        }
        return ok();
    }

    /** POST /v1/power_saving?mode=off|low|high */
    private HttpResponse powerSaving(HttpRequest session) {
        if (!"POST".equals(session.method)) return error(405, "method_not_allowed");
        String mode = param(session, "mode");
        if (picture == null) return error(404, "not_found");
        if (mode == null) return error(400, "mode_required");
        try {
            picture.writePowerSaving(mode);
        } catch (PictureReader.WriteException e) {
            return writeError(e.code);
        }
        return ok();
    }

    /** POST /v1/picture/config {"poll_s", "event_refresh", "watch": [...]}: refresh settings, stored on the TV. */
    private HttpResponse pictureConfig(HttpRequest session) {
        if (!"POST".equals(session.method)) return error(405, "method_not_allowed");
        JSONObject body = body(session);
        if (picture == null) return error(404, "not_found");
        if (body == null) return error(400, "json_body_required");
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

    /** DELETE /v1/rest/auth: forget the REST credential. */
    private HttpResponse restForget(HttpRequest session) {
        if (!"DELETE".equals(session.method)) return error(405, "method_not_allowed");
        if (restAuth == null) return error(404, "not_found");
        restAuth.forget();
        if (picture != null) picture.publish();
        return ok();
    }

    /** POST /v1/rest/psk {"psk"}: store the IP-control pre-shared key and verify it. */
    private HttpResponse restPsk(HttpRequest session) {
        if (!"POST".equals(session.method)) return error(405, "method_not_allowed");
        if (rest == null || restAuth == null) return error(404, "not_found");
        JSONObject body = body(session);
        String psk = body == null ? null : body.optString("psk", null);
        if (psk == null || psk.isEmpty()) return error(400, "psk_required");
        restAuth.setPsk(psk);
        boolean ok = restAuth.verify(rest);
        if (picture != null) picture.publish();
        JSONObject o = new JSONObject();
        try { o.put("write_authorized", ok); } catch (Exception ignored) { }
        return json(o);
    }

    private HttpResponse writeError(String code) {
        switch (code) {
            case "rest_unavailable": return error(503, code);
            case "rest_unauthorized": return error(403, code);
            case "unknown_target": return error(404, code);
            case "target_unavailable": return error(409, code);
            case "invalid_value": return error(400, code);
            default: return error(500, code);
        }
    }

    private static String param(HttpRequest session, String name) {
        return session.param(name);
    }

    private static JSONObject body(HttpRequest session) {
        try {
            String raw = session.bodyText();
            return raw.isEmpty() ? null : new JSONObject(raw);
        } catch (Exception e) {
            return null;
        }
    }

    private HttpResponse ok() {
        JSONObject o = new JSONObject();
        try { o.put("ok", true); } catch (Exception ignored) { }
        return json(o);
    }

    private HttpResponse status(String s) {
        JSONObject o = new JSONObject();
        try { o.put("status", s); } catch (Exception ignored) { }
        return json(o);
    }

    private HttpResponse json(JSONObject o) {
        return HttpResponse.text(200, "application/json", o.toString()).header("Cache-Control", "no-store");
    }

    private HttpResponse error(int status, String code) {
        JSONObject o = new JSONObject();
        try { o.put("error", code); } catch (Exception ignored) { }
        return HttpResponse.text(status, "application/json", o.toString());
    }

    /**
     * Server-Sent Events: a state event on change (coalesced), heartbeats every
     * 30 s. The StateStore listener only queues; all socket writes happen on
     * this connection's own thread, so a dead client can never block or crash
     * the main thread. A failed write (client gone) or an interrupt
     * ({@link #closeStreams}) ends the stream.
     */
    private HttpResponse events() {
        final BlockingQueue<String> queue = new LinkedBlockingQueue<>(QUEUE_DEPTH);
        final StateStore.Listener listener = snapshot -> queue.offer(stateEvent(snapshot));
        return HttpResponse.stream(200, "text/event-stream", out -> {
            streams.add(Thread.currentThread());
            queue.offer(stateEvent(store.snapshot()));
            store.addListener(listener);
            if (media != null) media.onClientSubscribed();
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    String msg = queue.poll(HEARTBEAT_MS, TimeUnit.MILLISECONDS);
                    if (msg == null) msg = "event: heartbeat\ndata: {}\n\n";
                    out.write(msg.getBytes(StandardCharsets.UTF_8));
                    out.flush();
                }
            } finally {
                streams.remove(Thread.currentThread());
                store.removeListener(listener);
            }
        }).header("Cache-Control", "no-store");
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
