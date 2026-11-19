package com.braviafanboy.braviaagent.core;

import java.net.URI;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * What the agent accepts as a self-update. The APK itself is checked by
 * Android (same package, same signing key, never an older version, and always
 * a confirmation on screen); these rules are about what the agent will fetch
 * and show before it gets that far.
 */
public final class UpdatePolicy {
    /** Far above any agent release (about 200 KB); a larger download is abandoned. */
    public static final long MAX_APK_BYTES = 32L * 1024 * 1024;

    private static final Pattern SHA256 = Pattern.compile("[0-9a-fA-F]{64}");
    private static final Pattern VERSION = Pattern.compile("[0-9]{1,5}(\\.[0-9]{1,5}){0,3}");

    private UpdatePolicy() {
    }

    /**
     * Why an update is refused, as the error code the API answers with, or
     * null when it is acceptable: an https address, the SHA-256 the download
     * must have, and the version it brings.
     */
    public static String refusal(String url, String sha256, String version) {
        if (!isHttpsUrl(url)) return "url_required";
        if (sha256 == null || !SHA256.matcher(sha256).matches()) return "sha256_required";
        if (version == null || !VERSION.matcher(version).matches()) return "version_required";
        return null;
    }

    /** An https URL with a host and no user or password in it. */
    public static boolean isHttpsUrl(String url) {
        if (url == null) return false;
        try {
            URI u = new URI(url);
            return "https".equalsIgnoreCase(u.getScheme()) && u.getHost() != null && !u.getHost().isEmpty()
                && u.getRawUserInfo() == null;
        } catch (Exception e) {
            return false;
        }
    }

    public static String normalHash(String sha256) {
        return sha256 == null ? "" : sha256.toLowerCase(Locale.ROOT);
    }

    /** Whether a staged version is already running: equal to, or older than, the current one. */
    public static boolean isApplied(String pending, String current) {
        if (pending == null || pending.isEmpty() || current == null) return false;
        String[] a = pending.split("\\."), b = current.split("\\.");
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            int x = i < a.length ? number(a[i]) : 0, y = i < b.length ? number(b[i]) : 0;
            if (x != y) return x < y;
        }
        return true;
    }

    private static int number(String s) {
        try {
            return Integer.parseInt(s.replaceAll("[^0-9].*$", ""));
        } catch (Exception e) {
            return 0;
        }
    }
}
