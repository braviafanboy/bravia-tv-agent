package com.braviafanboy.braviaagent.dashboard;

import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A validated request to show a Home Assistant dashboard on the TV, and the
 * pure logic around it (URL and origin handling, overlay geometry, the
 * frontend's external-auth payloads). No Android dependencies, so it is unit
 * tested on a desktop JVM.
 */
public final class DashboardSpec {
    public enum Mode { FULL, OVERLAY }

    public static final List<String> POSITIONS = Arrays.asList(
        "full", "left", "right", "top_left", "top_right", "bottom_left", "bottom_right");
    public static final int MIN_SIZE = 20;
    public static final int MAX_SIZE = 100;
    public static final int DEFAULT_SIZE = 40;
    /** An overlay can't be dismissed with the remote, so it always times out. */
    public static final int DEFAULT_OVERLAY_S = 30;
    public static final int MAX_DURATION_S = 3600;

    /** The frontend's fixed callback names (external_auth.ts: "should not be changed"). */
    public static final String CALLBACK_SET_TOKEN = "externalAuthSetToken";
    public static final String CALLBACK_REVOKE_TOKEN = "externalAuthRevokeToken";

    public final String url;
    public final String origin;
    public final Mode mode;
    public final String position;
    public final int size;
    /** Seconds on screen; 0 = until closed (full screen only). */
    public final int durationS;
    public final boolean kiosk;

    private DashboardSpec(String url, String origin, Mode mode, String position, int size, int durationS, boolean kiosk) {
        this.url = url;
        this.origin = origin;
        this.mode = mode;
        this.position = position;
        this.size = size;
        this.durationS = durationS;
        this.kiosk = kiosk;
    }

    /**
     * Validate a request. {@code mode} is "full" (default) or "overlay";
     * {@code position}/{@code size} apply to overlays; a {@code durationS} of
     * 0 or less means the default (until closed for full screen, 30 s for an
     * overlay). Throws IllegalArgumentException with a short error code.
     */
    public static DashboardSpec parse(String url, String mode, String position, int size, int durationS, boolean kiosk) {
        String origin = originOf(url);
        if (origin == null) throw new IllegalArgumentException("invalid_url");
        Mode m;
        if (mode == null || mode.isEmpty() || mode.equals("full")) m = Mode.FULL;
        else if (mode.equals("overlay")) m = Mode.OVERLAY;
        else throw new IllegalArgumentException("invalid_mode");
        String pos = position == null || position.isEmpty() ? "right" : position;
        if (!POSITIONS.contains(pos)) throw new IllegalArgumentException("invalid_position");
        int sz = size <= 0 ? DEFAULT_SIZE : size;
        if (sz < MIN_SIZE || sz > MAX_SIZE) throw new IllegalArgumentException("invalid_size");
        int dur = durationS <= 0 ? (m == Mode.OVERLAY ? DEFAULT_OVERLAY_S : 0) : Math.min(durationS, MAX_DURATION_S);
        return new DashboardSpec(withExternalAuth(url), origin, m, pos, sz, dur, kiosk);
    }

    /** scheme://host[:port] of an http(s) URL, lower-cased; null if not a plain http(s) URL. */
    public static String originOf(String url) {
        if (url == null) return null;
        try {
            URI u = new URI(url);
            String scheme = u.getScheme();
            if (scheme == null) return null;
            scheme = scheme.toLowerCase(Locale.US);
            if (!scheme.equals("http") && !scheme.equals("https")) return null;
            if (u.getRawUserInfo() != null) return null;
            String host = u.getHost();
            if (host == null || host.isEmpty()) return null;
            int port = u.getPort();
            if (port == 80 && scheme.equals("http")) port = -1;
            if (port == 443 && scheme.equals("https")) port = -1;
            return scheme + "://" + host.toLowerCase(Locale.US) + (port == -1 ? "" : ":" + port);
        } catch (Exception e) {
            return null;
        }
    }

    /** Whether {@code url} is on this dashboard's Home Assistant origin. */
    public boolean sameOrigin(String url) {
        return origin.equals(originOf(url));
    }

    /** Add external_auth=1 (before any fragment) so the frontend asks the app for tokens. */
    static String withExternalAuth(String url) {
        String base = url, frag = "";
        int hash = url.indexOf('#');
        if (hash >= 0) {
            base = url.substring(0, hash);
            frag = url.substring(hash);
        }
        if (base.contains("external_auth=1")) return url;
        return base + (base.contains("?") ? "&" : "?") + "external_auth=1" + frag;
    }

    /**
     * Overlay panel bounds {x, y, width, height} (top-left origin) on a
     * screen of the given size. Side panels span the full height; corners
     * keep the screen's aspect ratio and, like the TV's own notifications,
     * hang from the top (or sit on the bottom) edge they slide in from, a
     * small margin in from the side.
     */
    public int[] bounds(int screenW, int screenH) {
        if (mode == Mode.FULL || position.equals("full")) return new int[] {0, 0, screenW, screenH};
        int w = Math.round(screenW * size / 100f);
        switch (position) {
            case "left": return new int[] {0, 0, w, screenH};
            case "right": return new int[] {screenW - w, 0, w, screenH};
            default: {
                int h = Math.round(screenH * size / 100f);
                int margin = Math.round(screenH * 0.03f);
                int x = position.endsWith("left") ? margin : screenW - w - margin;
                int y = position.startsWith("top") ? 0 : screenH - h;
                return new int[] {Math.max(0, x), Math.max(0, y), Math.min(w, screenW), Math.min(h, screenH)};
            }
        }
    }

    /**
     * Narrowest layout width (CSS px) a panel is given. Below this Home
     * Assistant switches to its phone layout and a small corner panel shows
     * almost nothing, so small panels are zoomed out to reach it.
     */
    public static final int MIN_CSS_WIDTH = 480;

    /**
     * WebView initial scale (percent) for a panel {@code widthPx} wide on a
     * screen of {@code density}, or 0 to keep the normal scale (the panel is
     * already at least {@link #MIN_CSS_WIDTH} CSS px wide).
     */
    public static int initialScalePercent(int widthPx, float density) {
        if (widthPx <= 0 || density <= 0) return 0;
        if (widthPx / density >= MIN_CSS_WIDTH) return 0;
        return Math.max(1, Math.round(widthPx * 100f / MIN_CSS_WIDTH));
    }

    private static final Pattern CALLBACK = Pattern.compile("\"callback\"\\s*:\\s*\"([A-Za-z]+)\"");
    private static final Pattern FORCE = Pattern.compile("\"force\"\\s*:\\s*true");

    /**
     * The callback named in a getExternalAuth/revokeExternalAuth payload, if it
     * is the expected one. The name is evaluated as JavaScript, so anything
     * else is refused.
     */
    public static String callback(String payload, String expected) {
        if (payload == null) return null;
        Matcher m = CALLBACK.matcher(payload);
        return m.find() && m.group(1).equals(expected) ? expected : null;
    }

    /** Whether the frontend forced a refresh (its token was rejected). */
    public static boolean force(String payload) {
        return payload != null && FORCE.matcher(payload).find();
    }
}
