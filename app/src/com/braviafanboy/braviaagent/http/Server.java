package com.braviafanboy.braviaagent.http;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.FilterInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.net.ServerSocketFactory;
import javax.net.ssl.SSLSocket;

/**
 * A small HTTP/1.1 server for the agent's API: blocking sockets, one pooled
 * thread per open connection, a hard cap on connections, keep-alive, and
 * chunked streaming for the event stream. Idle threads exit, so an idle server
 * costs one thread blocked in accept(). Pure Java (no Android APIs), so it is
 * tested on a desktop JVM.
 */
public final class Server {
    /** Answers one request; runs on the connection's thread. */
    public interface Handler {
        HttpResponse handle(HttpRequest request);
    }

    /** Runs at the start of every pooled task (the agent lowers thread priority here). */
    public interface ThreadSetup {
        void apply();
    }

    public interface Logger {
        void log(String message);
    }

    /** How long a kept-alive connection may wait for its next request. */
    public static final int IDLE_TIMEOUT_MS = 20000;
    /** Socket read timeout once a request has started. */
    public static final int READ_TIMEOUT_MS = 5000;
    /** A whole request (line, headers, body) must arrive within this. */
    public static final int REQUEST_DEADLINE_MS = 10000;
    public static final int HANDSHAKE_TIMEOUT_MS = 10000;
    public static final int MAX_REQUESTS_PER_CONNECTION = 1000;

    private final ServerSocketFactory factory;
    private final int port;
    private final Handler handler;
    private final ThreadSetup setup;
    private final Logger log;
    private final Semaphore permits;
    private final ThreadPoolExecutor pool;
    private final Set<Socket> open = Collections.synchronizedSet(new HashSet<>());
    private volatile ServerSocket serverSocket;
    private volatile boolean running;

    public Server(ServerSocketFactory factory, int port, Handler handler, int maxConnections, ThreadSetup setup, Logger log) {
        this.factory = factory;
        this.port = port;
        this.handler = handler;
        this.setup = setup;
        this.log = log;
        permits = new Semaphore(maxConnections);
        AtomicInteger n = new AtomicInteger();
        pool = new ThreadPoolExecutor(0, maxConnections, 60, TimeUnit.SECONDS, new SynchronousQueue<>(), r -> {
            Thread t = new Thread(r, "http-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
    }

    /** Bind and start accepting. Returns the bound port (useful with port 0 in tests). */
    public int start() throws IOException {
        ServerSocket ss = factory.createServerSocket();
        ss.setReuseAddress(true);
        ss.bind(new InetSocketAddress(port), 16);
        serverSocket = ss;
        running = true;
        Thread t = new Thread(this::acceptLoop, "http-accept");
        t.setDaemon(true);
        t.start();
        return ss.getLocalPort();
    }

    public void stop() {
        running = false;
        try { if (serverSocket != null) serverSocket.close(); } catch (IOException ignored) { }
        Socket[] all;
        synchronized (open) { all = open.toArray(new Socket[0]); }
        for (Socket s : all) closeQuietly(s);
        pool.shutdownNow();
    }

    private void acceptLoop() {
        while (running) {
            Socket s;
            try {
                s = serverSocket.accept();
            } catch (IOException e) {
                if (running) log("http: accept failed: " + e);
                continue;
            }
            if (!permits.tryAcquire()) {
                // At capacity. Nothing useful can be said before a TLS handshake, so just close.
                log("http: connection limit reached, refusing " + s.getInetAddress().getHostAddress());
                closeQuietly(s);
                continue;
            }
            open.add(s);
            try {
                pool.execute(() -> {
                    try {
                        if (setup != null) setup.apply();
                        serve(s);
                    } finally {
                        open.remove(s);
                        closeQuietly(s);
                        permits.release();
                        Thread.interrupted();  // a stream may have been interrupted; don't leak it into the next task
                    }
                });
            } catch (RuntimeException e) {
                open.remove(s);
                closeQuietly(s);
                permits.release();
            }
        }
    }

    private void serve(Socket s) {
        String remote = s.getInetAddress().getHostAddress();
        try {
            s.setTcpNoDelay(true);
            s.setSoTimeout(HANDSHAKE_TIMEOUT_MS);
            if (s instanceof SSLSocket) ((SSLSocket) s).startHandshake();
            DeadlineInputStream deadline = new DeadlineInputStream(s.getInputStream());
            BufferedInputStream in = new BufferedInputStream(deadline, 8192);
            OutputStream out = new BufferedOutputStream(s.getOutputStream(), 8192);
            for (int served = 0; served < MAX_REQUESTS_PER_CONNECTION; served++) {
                // Wait for the first byte of the next request at the idle timeout...
                s.setSoTimeout(IDLE_TIMEOUT_MS);
                in.mark(1);
                if (in.read() < 0) return;
                in.reset();
                // ...then the whole request must arrive promptly.
                s.setSoTimeout(READ_TIMEOUT_MS);
                deadline.arm(REQUEST_DEADLINE_MS);
                HttpRequest request;
                try {
                    request = HttpParser.read(in, out, remote);
                } catch (HttpParser.BadRequest e) {
                    writeFixed(out, error(e.status, e.code), false);
                    return;
                } finally {
                    deadline.disarm();
                }
                if (request == null) return;
                HttpResponse response;
                try {
                    response = handler.handle(request);
                } catch (RuntimeException e) {
                    log("http: handler failed for " + request.path + ": " + e);
                    response = error(500, "internal_error");
                }
                if (response.stream != null) {
                    writeStream(s, out, response);
                    return;
                }
                boolean keepAlive = request.keepAlive() && served + 1 < MAX_REQUESTS_PER_CONNECTION;
                writeFixed(out, response, keepAlive);
                if (!keepAlive) return;
            }
        } catch (SocketTimeoutException e) {
            // Idle keep-alive connection, or a client too slow to send its request.
        } catch (SocketException e) {
            // Client went away.
        } catch (IOException e) {
            log("http: connection from " + remote + " failed: " + e);
        }
    }

    private static HttpResponse error(int status, String code) {
        return HttpResponse.text(status, "application/json", "{\"error\":\"" + code + "\"}");
    }

    private static void writeHead(OutputStream out, HttpResponse r, String framing, boolean keepAlive) throws IOException {
        StringBuilder h = new StringBuilder(256);
        h.append("HTTP/1.1 ").append(r.status).append(' ').append(HttpResponse.reason(r.status)).append("\r\n");
        if (r.contentType != null) h.append("Content-Type: ").append(r.contentType).append("\r\n");
        h.append(framing).append("\r\n");
        h.append("Connection: ").append(keepAlive ? "keep-alive" : "close").append("\r\n");
        for (java.util.Map.Entry<String, String> e : r.headers.entrySet()) {
            String v = e.getValue();
            if (v.indexOf('\r') >= 0 || v.indexOf('\n') >= 0) continue;  // never let a value split the header block
            h.append(e.getKey()).append(": ").append(v).append("\r\n");
        }
        h.append("\r\n");
        out.write(h.toString().getBytes(StandardCharsets.UTF_8));
    }

    static void writeFixed(OutputStream out, HttpResponse r, boolean keepAlive) throws IOException {
        byte[] body = r.body == null ? new byte[0] : r.body;
        writeHead(out, r, "Content-Length: " + body.length, keepAlive);
        out.write(body);
        out.flush();
    }

    private void writeStream(Socket s, OutputStream out, HttpResponse r) throws IOException {
        writeHead(out, r, "Transfer-Encoding: chunked", false);
        out.flush();
        ChunkedOutputStream chunked = new ChunkedOutputStream(out);
        try {
            r.stream.write(chunked);
            chunked.finish();
        } catch (InterruptedException e) {
            // Ended by the server (closeStreams); just close.
        }
    }

    private void log(String m) {
        if (log != null) log.log(m);
    }

    private static void closeQuietly(Socket s) {
        try { s.close(); } catch (IOException ignored) { }
    }

    /** Chunked transfer coding: each flush() sends what was written as one chunk. */
    static final class ChunkedOutputStream extends FilterOutputStream {
        private final java.io.ByteArrayOutputStream pending = new java.io.ByteArrayOutputStream(1024);

        ChunkedOutputStream(OutputStream out) {
            super(out);
        }

        @Override public void write(int b) {
            pending.write(b);
        }

        @Override public void write(byte[] b, int off, int len) {
            pending.write(b, off, len);
        }

        @Override public void flush() throws IOException {
            if (pending.size() > 0) {
                out.write((Integer.toHexString(pending.size()) + "\r\n").getBytes(StandardCharsets.US_ASCII));
                pending.writeTo(out);
                out.write("\r\n".getBytes(StandardCharsets.US_ASCII));
                pending.reset();
            }
            out.flush();
        }

        void finish() throws IOException {
            flush();
            out.write("0\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            out.flush();
        }

        @Override public void close() throws IOException {
            flush();
        }
    }

    /** Fails reads once an armed deadline has passed, bounding how long a slow client can hold a request open. */
    static final class DeadlineInputStream extends FilterInputStream {
        private volatile long deadline;

        DeadlineInputStream(InputStream in) {
            super(in);
        }

        void arm(long ms) {
            deadline = System.nanoTime() + ms * 1_000_000L;
        }

        void disarm() {
            deadline = 0;
        }

        private void check() throws SocketTimeoutException {
            long d = deadline;
            if (d != 0 && System.nanoTime() - d > 0) throw new SocketTimeoutException("request deadline");
        }

        @Override public int read() throws IOException {
            check();
            return super.read();
        }

        @Override public int read(byte[] b, int off, int len) throws IOException {
            check();
            return super.read(b, off, len);
        }
    }
}
