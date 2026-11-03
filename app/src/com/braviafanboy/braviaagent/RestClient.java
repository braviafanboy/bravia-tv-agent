package com.braviafanboy.braviaagent;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * JSON-RPC client for the TV's own REST IP-control API ({@code /sony/<service>}),
 * called on the TV itself.
 *
 * <p>Which scheme the API speaks is a TV setting whose default depends on the
 * region (EU sets serve HTTPS on 443 only and answer 403 on port 80), so the
 * client probes candidate endpoints in order and caches the first one that
 * answers JSON-RPC. The webapi's certificate is self-signed by the TV's own
 * root and regenerated when the mode changes; it is accepted without
 * verification, which is only ever done for this device's own addresses.
 *
 * <p>Calls are serialised; callers run them off the main thread.
 */
public final class RestClient {
    private static final String TAG = "BraviaAgent";
    private static final int TIMEOUT_MS = 3000;

    /** Why a call failed. */
    public static final class RestException extends Exception {
        public enum Kind { UNAVAILABLE, AUTH_REQUIRED, RPC_ERROR }
        public final Kind kind;
        public final int code;
        RestException(Kind kind, int code, String message) {
            super(message);
            this.kind = kind;
            this.code = code;
        }
    }

    private final SSLSocketFactory sslFactory;
    private final HostnameVerifier ownHostVerifier;
    private String endpoint;  // e.g. "https://127.0.0.1"; null until probed

    public RestClient() {
        SSLSocketFactory f = null;
        try {
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, new TrustManager[] { new X509TrustManager() {
                @Override public void checkClientTrusted(X509Certificate[] c, String t) { }
                @Override public void checkServerTrusted(X509Certificate[] c, String t) { }
                @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            } }, new SecureRandom());
            f = ctx.getSocketFactory();
        } catch (Exception e) {
            Log.w(TAG, "rest: TLS context failed: " + e);
        }
        sslFactory = f;
        // Only this device's own addresses are ever contacted (see candidates()).
        ownHostVerifier = (host, session) -> true;
    }

    /** The endpoint in use ("https://127.0.0.1"), or null when the API was not reachable. */
    public synchronized String endpoint() {
        return endpoint;
    }

    /** "https", "http" or null. */
    public synchronized String scheme() {
        return endpoint == null ? null : endpoint.substring(0, endpoint.indexOf(':'));
    }

    /**
     * Call {@code method} on {@code service}; returns the JSON-RPC {@code result}
     * array. Re-probes the endpoint once when the cached one stops answering.
     */
    public synchronized JSONArray call(String service, String method, String version, JSONArray params) throws RestException {
        JSONObject body = new JSONObject();
        try {
            body.put("method", method);
            body.put("params", params == null ? new JSONArray() : params);
            body.put("id", 1);
            body.put("version", version);
        } catch (JSONException e) {
            throw new RestException(RestException.Kind.RPC_ERROR, 0, "bad request");
        }
        byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
        if (endpoint != null) {
            try {
                return parse(post(endpoint, service, bytes));
            } catch (IOException e) {
                endpoint = null;  // stopped answering (mode changed, standby): probe again
            }
        }
        for (String candidate : candidates()) {
            try {
                JSONArray result = parse(post(candidate, service, bytes));
                remember(candidate);
                return result;
            } catch (IOException e) {
                // not this endpoint
            } catch (RestException e) {
                remember(candidate);  // it speaks JSON-RPC; the error is real
                throw e;
            }
        }
        throw new RestException(RestException.Kind.UNAVAILABLE, 0, "REST IP control not reachable");
    }

    private void remember(String candidate) {
        if (!candidate.equals(endpoint)) Log.i(TAG, "rest: using " + candidate);
        endpoint = candidate;
    }

    /** HTTPS first (the enforced default in some regions), loopback first, then this device's own LAN address. */
    static List<String> candidates() {
        List<String> out = new ArrayList<>();
        List<String> lan = ownIpv4();
        out.add("https://127.0.0.1");
        for (String a : lan) out.add("https://" + a);
        out.add("http://127.0.0.1");
        out.add("http://[::1]");
        for (String a : lan) out.add("http://" + a);
        return out;
    }

    private static List<String> ownIpv4() {
        List<String> out = new ArrayList<>();
        try {
            for (NetworkInterface nif : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!nif.isUp() || nif.isLoopback()) continue;
                for (InetAddress a : Collections.list(nif.getInetAddresses())) {
                    if (a instanceof Inet4Address) out.add(a.getHostAddress());
                }
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    /** One POST; returns the body when it looks like JSON-RPC, else IOException (not the API). */
    private String post(String base, String service, byte[] body) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(base + "/sony/" + service).openConnection();
        try {
            if (c instanceof HttpsURLConnection) {
                if (sslFactory == null) throw new IOException("no TLS");
                ((HttpsURLConnection) c).setSSLSocketFactory(sslFactory);
                ((HttpsURLConnection) c).setHostnameVerifier(ownHostVerifier);
            }
            c.setConnectTimeout(TIMEOUT_MS);
            c.setReadTimeout(TIMEOUT_MS);
            c.setInstanceFollowRedirects(false);
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            c.setFixedLengthStreamingMode(body.length);
            try (OutputStream os = c.getOutputStream()) { os.write(body); }
            int code = c.getResponseCode();
            InputStream is = code < 400 ? c.getInputStream() : c.getErrorStream();
            String text = is == null ? "" : new String(readAll(is), StandardCharsets.UTF_8);
            // nginx's own 403 page (plain HTTP while HTTPS is enforced) is not the API.
            if (!text.trim().startsWith("{")) throw new IOException("not JSON-RPC (HTTP " + code + ")");
            return text;
        } finally {
            c.disconnect();
        }
    }

    private static JSONArray parse(String text) throws RestException, IOException {
        JSONObject o;
        try {
            o = new JSONObject(text);
        } catch (JSONException e) {
            throw new IOException("not JSON");
        }
        JSONArray err = o.optJSONArray("error");
        if (err != null) {
            int code = err.optInt(0);
            String msg = err.optString(1);
            if (code == 401 || code == 403) throw new RestException(RestException.Kind.AUTH_REQUIRED, code, msg);
            throw new RestException(RestException.Kind.RPC_ERROR, code, msg);
        }
        JSONArray result = o.optJSONArray("result");
        if (result == null) throw new IOException("no result");
        return result;
    }

    private static byte[] readAll(InputStream is) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) > 0) out.write(buf, 0, n);
        return out.toByteArray();
    }
}
