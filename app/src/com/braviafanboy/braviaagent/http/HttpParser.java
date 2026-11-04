package com.braviafanboy.braviaagent.http;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Strict HTTP/1.1 request parser for the agent's small API. Anything outside
 * that subset is refused rather than guessed at: request bodies must carry a
 * Content-Length (no chunked uploads), lines end in CRLF or LF, and every part
 * of a request has a hard size limit.
 */
public final class HttpParser {
    public static final int MAX_REQUEST_LINE = 2048;
    public static final int MAX_HEADER_BYTES = 8192;
    public static final int MAX_HEADERS = 50;
    public static final int MAX_BODY = 65536;

    private HttpParser() { }

    /** A request the server must answer with {@code status} and then close. */
    public static final class BadRequest extends Exception {
        public final int status;
        public final String code;

        BadRequest(int status, String code) {
            super(code);
            this.status = status;
            this.code = code;
        }
    }

    /**
     * Read one request. Returns null on a clean end of stream before any byte
     * (the client closed a kept-alive connection). {@code continueOut} receives
     * the interim 100 response when the client sends {@code Expect: 100-continue}.
     */
    public static HttpRequest read(InputStream in, OutputStream continueOut, String remoteAddress)
            throws IOException, BadRequest {
        String requestLine = readLine(in, MAX_REQUEST_LINE, 414);
        if (requestLine == null) return null;
        // Tolerate blank lines between kept-alive requests (RFC 9112 §2.2).
        while (requestLine.isEmpty()) {
            requestLine = readLine(in, MAX_REQUEST_LINE, 414);
            if (requestLine == null) return null;
        }
        String[] parts = requestLine.split(" ", -1);
        if (parts.length != 3) throw new BadRequest(400, "bad_request_line");
        String method = parts[0], target = parts[1], version = parts[2];
        if (!method.matches("[A-Z]{1,10}")) throw new BadRequest(400, "bad_method");
        if (!version.equals("HTTP/1.1") && !version.equals("HTTP/1.0")) throw new BadRequest(505, "http_version_not_supported");
        if (!target.startsWith("/")) throw new BadRequest(400, "bad_target");

        Map<String, String> headers = new HashMap<>();
        int headerBytes = 0, count = 0;
        while (true) {
            String line = readLine(in, MAX_HEADER_BYTES - headerBytes, 431);
            if (line == null) throw new BadRequest(400, "truncated_headers");
            if (line.isEmpty()) break;
            headerBytes += line.length() + 2;
            if (++count > MAX_HEADERS) throw new BadRequest(431, "too_many_headers");
            int colon = line.indexOf(':');
            if (colon <= 0 || line.charAt(0) == ' ' || line.charAt(0) == '\t') throw new BadRequest(400, "bad_header");
            String name = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            if (!name.matches("[a-z0-9!#$%&'*+.^_`|~-]+")) throw new BadRequest(400, "bad_header_name");
            String value = line.substring(colon + 1).trim();
            if (name.equals("content-length") && headers.containsKey(name) && !headers.get(name).equals(value)) {
                throw new BadRequest(400, "conflicting_content_length");
            }
            headers.put(name, value);
        }
        if (version.equals("HTTP/1.1") && !headers.containsKey("host")) throw new BadRequest(400, "host_required");
        if (headers.containsKey("transfer-encoding")) throw new BadRequest(501, "transfer_encoding_not_supported");

        int length = 0;
        String cl = headers.get("content-length");
        if (cl != null) {
            if (!cl.matches("[0-9]{1,9}")) throw new BadRequest(400, "bad_content_length");
            length = Integer.parseInt(cl);
            if (length > MAX_BODY) throw new BadRequest(413, "body_too_large");
        }
        String expect = headers.get("expect");
        if (expect != null) {
            if (!expect.equalsIgnoreCase("100-continue")) throw new BadRequest(417, "expectation_failed");
            if (length > 0 && continueOut != null) {
                continueOut.write("HTTP/1.1 100 Continue\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                continueOut.flush();
            }
        }
        byte[] body = new byte[length];
        for (int off = 0; off < length; ) {
            int n = in.read(body, off, length - off);
            if (n < 0) throw new BadRequest(400, "truncated_body");
            off += n;
        }

        int q = target.indexOf('?');
        String rawPath = q < 0 ? target : target.substring(0, q);
        String path;
        Map<String, List<String>> query;
        try {
            path = URLDecoder.decode(rawPath.replace("+", "%2B"), "UTF-8");
            query = q < 0 ? new LinkedHashMap<>() : parseQuery(target.substring(q + 1));
        } catch (IllegalArgumentException e) {
            throw new BadRequest(400, "bad_encoding");
        }
        return new HttpRequest(method, path, query, headers, body, version, remoteAddress);
    }

    static Map<String, List<String>> parseQuery(String raw) throws java.io.UnsupportedEncodingException {
        Map<String, List<String>> out = new LinkedHashMap<>();
        if (raw.isEmpty()) return out;
        for (String pair : raw.split("&")) {
            if (pair.isEmpty()) continue;
            int eq = pair.indexOf('=');
            String k = URLDecoder.decode(eq < 0 ? pair : pair.substring(0, eq), "UTF-8");
            String v = eq < 0 ? "" : URLDecoder.decode(pair.substring(eq + 1), "UTF-8");
            out.computeIfAbsent(k, x -> new ArrayList<>()).add(v);
        }
        return out;
    }

    /**
     * One line without its terminator, or null at end of stream before any
     * byte. Only US-ASCII without control characters is accepted; a line
     * longer than {@code max} is refused with {@code tooLong}.
     */
    static String readLine(InputStream in, int max, int tooLong) throws IOException, BadRequest {
        ByteArrayOutputStream buf = new ByteArrayOutputStream(64);
        while (true) {
            int b = in.read();
            if (b < 0) {
                if (buf.size() == 0) return null;
                throw new BadRequest(400, "truncated_line");
            }
            if (b == '\n') break;
            if (b == '\r') {
                int next = in.read();
                if (next != '\n') throw new BadRequest(400, "bare_cr");
                break;
            }
            if (b < 0x20 && b != '\t' || b >= 0x7f) throw new BadRequest(400, "bad_character");
            if (buf.size() >= max) throw new BadRequest(tooLong, "line_too_long");
            buf.write(b);
        }
        return buf.toString("US-ASCII");
    }
}
