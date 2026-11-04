package com.braviafanboy.braviaagent.http;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/** A response: either a fixed body, or a stream written chunk by chunk on the connection's thread. */
public final class HttpResponse {
    /** Writes a streamed body. Return or throw to end the stream; the connection then closes. */
    public interface StreamBody {
        void write(OutputStream out) throws IOException, InterruptedException;
    }

    public final int status;
    public final String contentType;
    public final byte[] body;
    public final StreamBody stream;
    public final Map<String, String> headers = new LinkedHashMap<>();

    private HttpResponse(int status, String contentType, byte[] body, StreamBody stream) {
        this.status = status;
        this.contentType = contentType;
        this.body = body;
        this.stream = stream;
    }

    public static HttpResponse of(int status, String contentType, byte[] body) {
        return new HttpResponse(status, contentType, body, null);
    }

    public static HttpResponse text(int status, String contentType, String body) {
        return of(status, contentType, body.getBytes(StandardCharsets.UTF_8));
    }

    public static HttpResponse stream(int status, String contentType, StreamBody body) {
        return new HttpResponse(status, contentType, null, body);
    }

    public HttpResponse header(String name, String value) {
        headers.put(name, value);
        return this;
    }

    static String reason(int status) {
        switch (status) {
            case 200: return "OK";
            case 400: return "Bad Request";
            case 401: return "Unauthorized";
            case 403: return "Forbidden";
            case 404: return "Not Found";
            case 405: return "Method Not Allowed";
            case 408: return "Request Timeout";
            case 409: return "Conflict";
            case 413: return "Content Too Large";
            case 414: return "URI Too Long";
            case 417: return "Expectation Failed";
            case 431: return "Request Header Fields Too Large";
            case 500: return "Internal Server Error";
            case 501: return "Not Implemented";
            case 503: return "Service Unavailable";
            case 505: return "HTTP Version Not Supported";
            default: return "Status";
        }
    }
}
