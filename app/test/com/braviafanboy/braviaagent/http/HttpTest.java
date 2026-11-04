package com.braviafanboy.braviaagent.http;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import javax.net.ServerSocketFactory;

/**
 * Tests for the agent's HTTP layer, run on a desktop JVM by app/test.sh (no
 * Android, no test framework). Exits non-zero on the first failure.
 */
public final class HttpTest {
    private static int passed;

    public static void main(String[] args) throws Exception {
        parser();
        server();
        System.out.println("ok: " + passed + " checks passed");
    }

    // ---- parser --------------------------------------------------------

    private static void parser() throws Exception {
        HttpRequest r = parse("GET /v1/pair?name=Home%20Assistant&x=a+b&x=2 HTTP/1.1\r\nHost: tv\r\nAuthorization: Bearer abc\r\n\r\n");
        eq("GET", r.method, "method");
        eq("/v1/pair", r.path, "path");
        eq("Home Assistant", r.param("name"), "percent-decoded query");
        eq("a b", r.param("x"), "plus in query is a space");
        eq(2, r.query.get("x").size(), "repeated parameter");
        eq("Bearer abc", r.header("authorization"), "header names are lower case");
        eq(true, r.keepAlive(), "HTTP/1.1 keeps alive by default");

        r = parse("POST /v1/picture HTTP/1.1\r\nHost: tv\r\nContent-Length: 34\r\nConnection: close\r\n\r\n{\"target\":\"contrast\",\"value\":\"90\"}");
        eq("{\"target\":\"contrast\",\"value\":\"90\"}", r.bodyText(), "body by Content-Length");
        eq(false, r.keepAlive(), "Connection: close");

        r = parse("GET /v1/info HTTP/1.1\nHost: tv\n\n");
        eq("/v1/info", r.path, "bare LF line endings");
        r = parse("\r\n\r\nGET /a HTTP/1.1\r\nHost: tv\r\n\r\n");
        eq("/a", r.path, "blank lines before a request");
        r = parse("GET /a HTTP/1.0\r\n\r\n");
        eq(false, r.keepAlive(), "HTTP/1.0 closes by default, no Host needed");
        eq(null, HttpParser.read(new ByteArrayInputStream(new byte[0]), null, "t"), "clean EOF is null");

        ByteArrayOutputStream cont = new ByteArrayOutputStream();
        r = HttpParser.read(in("POST /x HTTP/1.1\r\nHost: tv\r\nExpect: 100-continue\r\nContent-Length: 2\r\n\r\nhi"), cont, "t");
        eq("hi", r.bodyText(), "body after 100-continue");
        eq("HTTP/1.1 100 Continue\r\n\r\n", cont.toString("US-ASCII"), "interim 100 sent");

        bad("GET /a HTTP/1.1\r\n\r\n", 400, "missing Host");
        bad("POST /a HTTP/1.1\r\nHost: t\r\nTransfer-Encoding: chunked\r\n\r\n0\r\n\r\n", 501, "chunked upload");
        bad("POST /a HTTP/1.1\r\nHost: t\r\nContent-Length: 70000\r\n\r\n", 413, "body over the limit");
        bad("POST /a HTTP/1.1\r\nHost: t\r\nContent-Length: -1\r\n\r\n", 400, "negative length");
        bad("POST /a HTTP/1.1\r\nHost: t\r\nContent-Length: 1\r\nContent-Length: 2\r\n\r\nab", 400, "conflicting lengths");
        bad("POST /a HTTP/1.1\r\nHost: t\r\nContent-Length: 5\r\n\r\nab", 400, "truncated body");
        bad("GET /" + "a".repeat(3000) + " HTTP/1.1\r\nHost: t\r\n\r\n", 414, "request line too long");
        bad("GET /a HTTP/1.1\r\nHost: t\r\nX: " + "a".repeat(9000) + "\r\n\r\n", 431, "header too long");
        StringBuilder many = new StringBuilder("GET /a HTTP/1.1\r\nHost: t\r\n");
        for (int i = 0; i < 60; i++) many.append("X").append(i).append(": 1\r\n");
        bad(many.append("\r\n").toString(), 431, "too many headers");
        bad("GET /a HTTP/2.0\r\nHost: t\r\n\r\n", 505, "unsupported version");
        bad("GET a HTTP/1.1\r\nHost: t\r\n\r\n", 400, "target without a slash");
        bad("GET /a?x=%zz HTTP/1.1\r\nHost: t\r\n\r\n", 400, "bad percent encoding");
        bad("GET /a HTTP/1.1\r\nHost: t\rX: y\r\n\r\n", 400, "bare CR");
        bad("GET /a HTTP/1.1\r\nHost: t\u0001\r\n\r\n", 400, "control character");
        bad("GET /a HTTP/1.1\r\nHost: t\r\n folded\r\n\r\n", 400, "obsolete line folding");
        bad("GET /a HTTP/1.1\r\nBad Name: x\r\nHost: t\r\n\r\n", 400, "space in header name");
        bad("GET  /a HTTP/1.1\r\nHost: t\r\n\r\n", 400, "extra space in request line");
        bad("get /a HTTP/1.1\r\nHost: t\r\n\r\n", 400, "lower-case method");
        bad("GET /a HTTP/1.1\r\nHost: t\r\nExpect: something\r\n\r\n", 417, "unknown expectation");
        bad("GET /a HTTP/1.1\r\nHost: t", 400, "headers cut off");
    }

    private static HttpRequest parse(String raw) throws Exception {
        return HttpParser.read(in(raw), null, "test");
    }

    private static void bad(String raw, int status, String what) throws Exception {
        try {
            HttpParser.read(in(raw), null, "test");
            fail(what + ": accepted");
        } catch (HttpParser.BadRequest e) {
            eq(status, e.status, what);
        }
    }

    private static InputStream in(String s) {
        return new ByteArrayInputStream(s.getBytes(StandardCharsets.ISO_8859_1));
    }

    // ---- server --------------------------------------------------------

    private static void server() throws Exception {
        Server server = new Server(ServerSocketFactory.getDefault(), 0, req -> {
            switch (req.path) {
                case "/boom": throw new IllegalStateException("handler bug");
                case "/inject": return HttpResponse.text(200, "text/plain", "x").header("X-Evil", "a\r\nSet-Cookie: y");
                case "/stream": return HttpResponse.stream(200, "text/event-stream", out -> {
                    for (int i = 0; i < 3; i++) { out.write(("event " + i + "\n\n").getBytes(StandardCharsets.UTF_8)); out.flush(); }
                });
                default: return HttpResponse.text(200, "application/json", "{\"path\":\"" + req.path + "\",\"body\":" + req.body.length + "}");
            }
        }, 3, null, null);
        int port = server.start();
        try {
            // Keep-alive: several requests on one connection.
            try (Socket s = new Socket("127.0.0.1", port)) {
                OutputStream o = s.getOutputStream();
                InputStream i = s.getInputStream();
                for (int n = 0; n < 3; n++) {
                    o.write(("GET /k" + n + " HTTP/1.1\r\nHost: t\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                    String resp = readResponse(i);
                    contains(resp, "HTTP/1.1 200 OK", "keep-alive response " + n);
                    contains(resp, "Connection: keep-alive", "keep-alive header " + n);
                    contains(resp, "{\"path\":\"/k" + n + "\"", "keep-alive body " + n);
                }
                // Pipelined POST with a body on the same connection.
                o.write("POST /p HTTP/1.1\r\nHost: t\r\nContent-Length: 4\r\n\r\nabcd".getBytes(StandardCharsets.US_ASCII));
                contains(readResponse(i), "\"body\":4", "POST body on a kept-alive connection");
            }
            // Connection: close is honoured.
            String r = exchange(port, "GET /c HTTP/1.1\r\nHost: t\r\nConnection: close\r\n\r\n");
            contains(r, "Connection: close", "close header");
            // A malformed request gets an error and the connection closes.
            r = exchange(port, "GET /a HTTP/1.1\r\n\r\n");
            contains(r, "HTTP/1.1 400 Bad Request", "malformed request answered");
            contains(r, "host_required", "error code in body");
            // A handler bug becomes a 500, not a dead server.
            contains(exchange(port, "GET /boom HTTP/1.1\r\nHost: t\r\nConnection: close\r\n\r\n"), "HTTP/1.1 500", "handler exception");
            // Header values cannot inject header lines.
            r = exchange(port, "GET /inject HTTP/1.1\r\nHost: t\r\nConnection: close\r\n\r\n");
            eq(false, r.contains("Set-Cookie"), "CRLF in a header value is dropped");
            // Streams are chunked and close when the body ends.
            r = exchange(port, "GET /stream HTTP/1.1\r\nHost: t\r\n\r\n");
            contains(r, "Transfer-Encoding: chunked", "stream framing");
            contains(r, "9\r\nevent 0\n\n\r\n", "first chunk");
            contains(r, "9\r\nevent 2\n\n\r\n0\r\n\r\n", "last chunk and terminator");
            // Connection cap: with 3 held open, a 4th is closed at once.
            List<Socket> held = new ArrayList<>();
            for (int n = 0; n < 3; n++) held.add(new Socket("127.0.0.1", port));
            Thread.sleep(300);
            try (Socket extra = new Socket("127.0.0.1", port)) {
                extra.setSoTimeout(3000);
                eq(-1, extra.getInputStream().read(), "connection over the cap is closed");
            }
            for (Socket h : held) h.close();
            Thread.sleep(300);
            contains(exchange(port, "GET /after HTTP/1.1\r\nHost: t\r\nConnection: close\r\n\r\n"), "200 OK", "capacity returns after clients leave");
            // A client that trickles its request is cut off by the request deadline.
            long t0 = System.nanoTime();
            try (Socket slow = new Socket("127.0.0.1", port)) {
                slow.setSoTimeout(30000);
                OutputStream o = slow.getOutputStream();
                InputStream i = slow.getInputStream();
                boolean closed = false;
                for (int n = 0; n < 40 && !closed; n++) {
                    try {
                        o.write('G');
                        o.flush();
                    } catch (IOException e) {
                        closed = true;
                        break;
                    }
                    Thread.sleep(1000);
                    if (i.available() > 0 || slow.isInputShutdown()) closed = true;
                }
                long secs = (System.nanoTime() - t0) / 1_000_000_000L;
                eq(true, closed && secs <= Server.REQUEST_DEADLINE_MS / 1000 + 6, "slow request cut off (" + secs + " s)");
            }
        } finally {
            server.stop();
        }
    }

    private static String exchange(int port, String raw) throws IOException {
        try (Socket s = new Socket("127.0.0.1", port)) {
            s.setSoTimeout(5000);
            s.getOutputStream().write(raw.getBytes(StandardCharsets.US_ASCII));
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            try {
                for (int n; (n = s.getInputStream().read(buf)) > 0; ) out.write(buf, 0, n);
            } catch (java.net.SocketTimeoutException e) {
                fail("server did not close the connection");
            }
            return out.toString("UTF-8");
        }
    }

    /** One fixed-length response from a kept-alive connection. */
    private static String readResponse(InputStream in) throws IOException {
        StringBuilder head = new StringBuilder();
        while (!head.toString().endsWith("\r\n\r\n")) {
            int b = in.read();
            if (b < 0) fail("connection closed mid-response");
            head.append((char) b);
        }
        int at = head.indexOf("Content-Length: ");
        int len = Integer.parseInt(head.substring(at + 16, head.indexOf("\r\n", at)));
        byte[] body = in.readNBytes(len);
        return head + new String(body, StandardCharsets.UTF_8);
    }

    // ---- assertions ----------------------------------------------------

    private static void eq(Object expected, Object actual, String what) {
        if (expected == null ? actual != null : !expected.equals(actual)) fail(what + ": expected " + expected + ", got " + actual);
        passed++;
    }

    private static void contains(String haystack, String needle, String what) {
        if (!haystack.contains(needle)) fail(what + ": missing " + needle.replace("\r", "\\r").replace("\n", "\\n") + " in\n" + haystack);
        passed++;
    }

    private static void fail(String message) {
        System.err.println("FAIL: " + message);
        System.exit(1);
    }
}
