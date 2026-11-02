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

    public interface InfoProvider {
        JSONObject info();
    }

    public HttpServer(int port, StateStore store, ThemesReader themes, InfoProvider info, MediaReader media) {
        super(port);
        this.store = store;
        this.themes = themes;
        this.info = info;
        this.media = media;
    }

    @Override
    public Response serve(IHTTPSession session) {
        String path = session.getUri();
        if (session.getMethod() != Method.GET) return error(Response.Status.METHOD_NOT_ALLOWED, "method_not_allowed");
        switch (path) {
            case "/v1/info": return json(info.info());
            case "/v1/state": return json(store.snapshot());
            case "/v1/themes": {
                JSONObject o = new JSONObject();
                try { o.put("themes", themes.themes()); } catch (Exception ignored) { }
                return json(o);
            }
            case "/v1/events": return events();
            case MediaReader.ARTWORK_PATH: {
                byte[] png = media == null ? null : media.artwork();
                if (png == null) return error(Response.Status.NOT_FOUND, "no_artwork");
                Response r = newFixedLengthResponse(Response.Status.OK, "image/png", new ByteArrayInputStream(png), png.length);
                r.addHeader("Cache-Control", "no-store");
                return r;
            }
            case "/": return json(info.info());
            default: return error(Response.Status.NOT_FOUND, "not_found");
        }
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
        Thread t = new Thread(() -> {
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
