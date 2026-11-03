package com.braviafanboy.braviaagent;

import android.util.Log;

import com.braviafanboy.braviaagent.readers.MediaReader;
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
    private static final long HEARTBEAT_MS = 30000;
    private static final int QUEUE_DEPTH = 16;

    private final StateStore store;
    private final ThemesReader themes;
    private final InfoProvider info;
    private final MediaReader media;
    private final Pairing pairing;
    private final java.util.Set<Thread> streams = java.util.Collections.synchronizedSet(new java.util.HashSet<>());

    public interface InfoProvider {
        JSONObject info();
    }

    public HttpServer(int port, StateStore store, ThemesReader themes, InfoProvider info, MediaReader media, Pairing pairing) {
        super(port);
        this.store = store;
        this.themes = themes;
        this.info = info;
        this.media = media;
        this.pairing = pairing;
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
        if (session.getMethod() != Method.GET) return error(Response.Status.METHOD_NOT_ALLOWED, "method_not_allowed");
        if ("/v1/info".equals(path) || "/".equals(path)) return json(info.info());
        if (!pairing.authorized(session.getHeaders().get("authorization"))) {
            Response r = error(Response.Status.UNAUTHORIZED, "unauthorized");
            r.addHeader("WWW-Authenticate", "Bearer realm=\"bravia-agent\"");
            return r;
        }
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
