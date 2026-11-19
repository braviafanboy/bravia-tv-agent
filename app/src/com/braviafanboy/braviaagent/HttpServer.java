package com.braviafanboy.braviaagent;

import android.util.Log;

import com.braviafanboy.braviaagent.core.Access;
import com.braviafanboy.braviaagent.core.PairingState;
import com.braviafanboy.braviaagent.core.UpdatePolicy;
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

/** The agent's HTTP surface: /v1/info and pairing for anyone, everything else for the holder of the token. */
public final class HttpServer implements Server.Handler {
    private static final String TAG = "BraviaAgent";
    public static final int CONTRACT = 1;
    public static final String PICTURE_REFRESH_PATH = "/v1/picture/refresh";
    public static final String PICTURE_PATH = "/v1/picture";
    public static final String PICTURE_BATCH_PATH = "/v1/picture/batch";
    public static final String PICTURE_CONFIG_PATH = "/v1/picture/config";
    public static final String POWER_SAVING_PATH = "/v1/power_saving";
    public static final String REST_AUTH_PATH = "/v1/rest/auth";
    public static final String REST_PSK_PATH = "/v1/rest/psk";
    public static final String AMBIENT_PATH = "/v1/ambient";
    public static final String REMOTE_FIND_PATH = "/v1/remote/find";
    public static final String NOTIFY_PATH = "/v1/notify";
    public static final String MEDIA_COMMAND_PATH = "/v1/media/command";
    public static final String DASHBOARD_PATH = "/v1/dashboard";
    public static final String SETUP_PATH = "/v1/setup";
    private static final long HEARTBEAT_MS = 30000;
    private static final int QUEUE_DEPTH = 16;
    /**
     * Home Assistant holds the event stream plus a few short requests, more
     * when it changes several settings at once. One address may hold eight
     * connections (what the whole server allowed before) of twenty-four, so
     * that a single client, or two, filling its share still leaves Home
     * Assistant its own.
     */
    private static final int MAX_CONNECTIONS = 24;
    private static final int MAX_CONNECTIONS_PER_ADDRESS = 8;

    private final StateStore store;
    private final InfoProvider info;
    private final MediaReader media;
    private final Pairing pairing;
    private final PictureReader picture;
    private RestClient rest;
    private RestAuth restAuth;
    private AmbientController ambient;
    private RemoteFinder remoteFinder;
    private final Updater updater;
    private final int port;
    private Server server;
    private final java.util.Set<Thread> streams = java.util.Collections.synchronizedSet(new java.util.HashSet<>());

    public interface InfoProvider {
        /** The /v1/info document; {@code full} for a caller that showed the token. */
        JSONObject info(boolean full);

        /** The next launch by the TV opens the permission screens (POST /v1/setup). */
        void armSetup();
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
    public void setAmbient(AmbientController ambient) {
        this.ambient = ambient;
    }

    private Notifier notifier;

    public void setNotifier(Notifier n) {
        this.notifier = n;
    }

    private com.braviafanboy.braviaagent.dashboard.DashboardController dashboard;

    public void setDashboard(com.braviafanboy.braviaagent.dashboard.DashboardController d) {
        this.dashboard = d;
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
        server = new Server(tlsFactory, port, this, MAX_CONNECTIONS, MAX_CONNECTIONS_PER_ADDRESS,
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
        // The one gate, before anything is looked at: without the token only
        // the public paths answer, and an agent nobody has paired with has no
        // token to show. An answer to a caller without the token also closes
        // the connection, so that such a caller cannot hold one open.
        boolean authorized = pairing.authorized(session.header("authorization"));
        if (!authorized && !Access.isPublic(path)) {
            return error(401, "unauthorized").header("WWW-Authenticate", "Bearer realm=\"bravia-agent\"").closing();
        }
        if ("/v1/pair".equals(path)) return authorized ? pair(session) : pair(session).closing();
        if ("/v1/info".equals(path) || "/".equals(path)) {
            return authorized ? json(info.info(true)) : json(info.info(false)).closing();
        }
        if ("/v1/update".equals(path)) return update(session);
        if (SETUP_PATH.equals(path)) {
            // POST /v1/setup: the next launch of the app by the TV (the
            // integration's local launch) opens the permission screens.
            if (!"POST".equals(session.method)) return error(405, "method_not_allowed");
            info.armSetup();
            return ok();
        }
        if (PICTURE_REFRESH_PATH.equals(path)) return pictureRefresh(session);
        if (PICTURE_BATCH_PATH.equals(path)) return pictureBatch(session);
        if (PICTURE_PATH.equals(path)) return pictureWrite(session);
        if (PICTURE_CONFIG_PATH.equals(path)) return pictureConfig(session);
        if (MEDIA_COMMAND_PATH.equals(path)) return mediaCommand(session);
        if (POWER_SAVING_PATH.equals(path)) return powerSaving(session);
        if (REST_AUTH_PATH.equals(path)) return restForget(session);
        if (REST_PSK_PATH.equals(path)) return restPsk(session);
        if (path.equals(AMBIENT_PATH) || path.startsWith(AMBIENT_PATH + "/")) return ambient(session, path.substring(AMBIENT_PATH.length()));
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
        if (path.equals(DASHBOARD_PATH) || path.startsWith(DASHBOARD_PATH + "/")) return dashboard(session, path.substring(DASHBOARD_PATH.length()));
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

    /**
     * Pairing (see {@link PairingState}). POST /v1/pair?name=<client>&commit=
     * <SHA-256 of the client's secret> asks: 202 {"request", "nonce",
     * "accepted": false, "expires_in"}, or 409 {"error": "pairing_busy",
     * "retry_after"} while another request is waiting. POST /v1/pair?request=
     * <id>&reveal=<the secret> gives the request its code, which the viewer
     * compares on the agent's screen before allowing it. POST /v1/pair?
     * request=<id> collects: 200 {"token"} once allowed, 202 until then, 403
     * {"error": "pairing_denied"} when the viewer said no, 403 {"error":
     * "pairing_closed"} when there is no such request (any longer).
     */
    private HttpResponse pair(HttpRequest session) {
        if (!"POST".equals(session.method)) return error(405, "method_not_allowed");
        String request = param(session, "request");
        String reveal = param(session, "reveal");
        PairingState.Reply r;
        if (request == null) {
            r = pairing.request(param(session, "name"), session.remoteAddress, param(session, "commit"));
        } else if (reveal != null) {
            r = pairing.reveal(request, reveal);
        } else {
            r = pairing.collect(request);
        }
        JSONObject o = new JSONObject();
        try {
            if (r.token != null) {
                o.put("token", r.token);
            } else if (r.request != null) {
                o.put("request", r.request);
                if (r.nonce != null) o.put("nonce", r.nonce);
                o.put("accepted", r.accepted);
                o.put("expires_in", r.seconds);
            } else {
                o.put("error", r.error);
                if (r.status == 409) o.put("retry_after", r.seconds);
            }
        } catch (Exception ignored) { }
        return HttpResponse.text(r.status, "application/json", o.toString()).header("Cache-Control", "no-store");
    }

    /**
     * POST /v1/update?url=&sha256=&version=: stage a self-update, which
     * UpdateActivity then runs. All three are required: an https address, the
     * SHA-256 the download must have, and the version it brings.
     */
    private HttpResponse update(HttpRequest session) {
        if (!"POST".equals(session.method)) return error(405, "method_not_allowed");
        String url = param(session, "url");
        String sha256 = param(session, "sha256");
        String version = param(session, "version");
        String refusal = UpdatePolicy.refusal(url, sha256, version);
        if (refusal != null) return error(400, refusal);
        updater.setPending(url, sha256, version);
        JSONObject o = new JSONObject();
        try {
            o.put("pending", url);
            o.put("version", version);
        } catch (Exception ignored) { }
        log("update staged: " + version);
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
    private HttpResponse pictureBatch(HttpRequest session) {
        if (!"POST".equals(session.method)) return error(405, "method_not_allowed");
        if (picture == null) return error(404, "not_found");
        JSONObject body = body(session);
        JSONArray settings = body == null ? null : body.optJSONArray("settings");
        if (settings == null) return error(400, "settings_required");
        return json(picture.writeBatch(settings));
    }

    /** POST /v1/media/command {"action", "position_ms"?}: send a transport
     *  command to the active media session. {"ok": true|false}. */
    private HttpResponse mediaCommand(HttpRequest session) {
        if (!"POST".equals(session.method)) return error(405, "method_not_allowed");
        if (media == null) return error(404, "not_found");
        JSONObject body = body(session);
        String action = body == null ? "" : body.optString("action", "");
        if (action.isEmpty()) return error(400, "action_required");
        long position = body.optLong("position_ms", 0);
        JSONObject o = new JSONObject();
        try {
            o.put("ok", media.command(action, position));
        } catch (Exception ignored) {
        }
        return json(o);
    }

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

    /**
     * Ambient brightness. GET /v1/ambient: status and curves. POST
     * /v1/ambient/config {"enabled", "learning", "mode"?}; /v1/ambient/curve {"mode"?,
     * "anchors": [6]}; /v1/ambient/reset {"mode"?}; /v1/ambient/curves
     * {"default"?, "modes"?}; /v1/ambient/context {"signal"}. A missing mode
     * means the current picture mode.
     */
    private HttpResponse ambient(HttpRequest session, String sub) {
        if (ambient == null) return error(404, "not_found");
        if (sub.isEmpty() || sub.equals("/")) {
            if (!"GET".equals(session.method)) return error(405, "method_not_allowed");
            return json(ambient.status());
        }
        if (!"POST".equals(session.method)) return error(405, "method_not_allowed");
        JSONObject body = body(session);
        if (body == null) return error(400, "json_body_required");
        String mode = body.optString("mode", null);
        if (mode != null && mode.isEmpty()) mode = null;
        try {
            switch (sub) {
                case "/config":
                    if (body.has("enabled")) {
                        String forMode = body.optString("mode", "");
                        ambient.setEnabled(forMode.isEmpty() ? null : forMode, body.optBoolean("enabled"));
                    }
                    if (body.has("learning")) ambient.setLearning(body.optBoolean("learning"));
                    break;
                case "/curve": {
                    JSONArray a = body.optJSONArray("anchors");
                    if (a == null || a.length() != com.braviafanboy.braviaagent.ambient.AmbientCurve.ANCHORS) {
                        return error(400, "anchors_required");
                    }
                    ambient.setCurve(mode, AmbientController.ints(a));
                    break;
                }
                case "/reset":
                    ambient.resetCurve(mode);
                    break;
                case "/curves":
                    ambient.setCurves(body);
                    break;
                case "/context":
                    ambient.setSignal(body.optString("signal", null));
                    break;
                default:
                    return error(404, "not_found");
            }
        } catch (IllegalArgumentException e) {
            return error(400, "invalid_curve");
        }
        return json(ambient.status());
    }

    /**
     * Home Assistant dashboards on the TV. POST /v1/dashboard {"url", "mode":
     * full|overlay, "position"?, "size"?, "duration_s"?, "kiosk"?, "token",
     * "expires_in"} -> {"state": shown | no_permission | failed}; POST
     * /v1/dashboard/token {"token", "expires_in"}; DELETE /v1/dashboard; GET
     * /v1/dashboard -> the state block.
     */
    private HttpResponse dashboard(HttpRequest session, String sub) {
        if (dashboard == null) return error(404, "not_found");
        if (sub.isEmpty()) {
            if ("GET".equals(session.method)) return json(dashboard.status());
            if ("DELETE".equals(session.method)) {
                dashboard.close();
                return ok();
            }
            if (!"POST".equals(session.method)) return error(405, "method_not_allowed");
            JSONObject body = body(session);
            if (body == null) return error(400, "json_body_required");
            com.braviafanboy.braviaagent.dashboard.DashboardSpec spec;
            try {
                spec = com.braviafanboy.braviaagent.dashboard.DashboardSpec.parse(
                    body.optString("url", null), body.optString("mode", null), body.optString("position", null),
                    body.optInt("size", 0), body.optInt("duration_s", 0),
                    body.has("kiosk") ? Boolean.valueOf(body.optBoolean("kiosk")) : null);
            } catch (IllegalArgumentException e) {
                return error(400, e.getMessage());
            }
            String token = body.optString("token", "");
            int expiresIn = body.optInt("expires_in", 0);
            if (token.isEmpty() || expiresIn <= 0) return error(400, "token_required");
            JSONObject o = new JSONObject();
            try { o.put("state", dashboard.open(spec, token, expiresIn)); } catch (Exception ignored) { }
            return json(o);
        }
        if ("/token".equals(sub)) {
            if (!"POST".equals(session.method)) return error(405, "method_not_allowed");
            JSONObject body = body(session);
            String token = body == null ? "" : body.optString("token", "");
            int expiresIn = body == null ? 0 : body.optInt("expires_in", 0);
            if (token.isEmpty() || expiresIn <= 0) return error(400, "token_required");
            dashboard.setToken(token, expiresIn);
            return ok();
        }
        return error(404, "not_found");
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
