package com.braviafanboy.braviaagent.http;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** One parsed request. Header names are lower case; the body is at most {@link HttpParser#MAX_BODY} bytes. */
public final class HttpRequest {
    public final String method;
    public final String path;
    public final Map<String, List<String>> query;
    public final Map<String, String> headers;
    public final byte[] body;
    public final String version;
    public final String remoteAddress;

    HttpRequest(String method, String path, Map<String, List<String>> query, Map<String, String> headers,
                byte[] body, String version, String remoteAddress) {
        this.method = method;
        this.path = path;
        this.query = Collections.unmodifiableMap(query);
        this.headers = Collections.unmodifiableMap(headers);
        this.body = body;
        this.version = version;
        this.remoteAddress = remoteAddress;
    }

    /** The first value of a query parameter, or null. */
    public String param(String name) {
        List<String> v = query.get(name);
        return v == null || v.isEmpty() ? null : v.get(0);
    }

    /** A header by lower-case name, or null. */
    public String header(String name) {
        return headers.get(name);
    }

    public String bodyText() {
        return new String(body, StandardCharsets.UTF_8);
    }

    /** Whether the client wants the connection kept open after this request. */
    public boolean keepAlive() {
        String c = headers.get("connection");
        if ("HTTP/1.0".equals(version)) return c != null && c.equalsIgnoreCase("keep-alive");
        return c == null || !c.toLowerCase(java.util.Locale.ROOT).contains("close");
    }
}
