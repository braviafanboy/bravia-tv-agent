package com.braviafanboy.braviaagent.core;

/** Tests for what is public, what counts as an update, and the TV's id; run on a desktop JVM by app/test.sh. */
public final class PolicyTest {
    private static int passed;

    private static final String HASH = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    private static final String UID = "0a1b2c3d4e5f60718293a4b5c6d7e8f901234567";

    public static void main(String[] args) {
        access();
        updates();
        deviceId();
        System.out.println("ok: " + passed + " policy checks passed");
    }

    /** Three paths answer without the token, and nothing that merely looks like them. */
    private static void access() {
        for (String open : new String[] {"/v1/info", "/", "/v1/pair"}) check(Access.isPublic(open), open + " is public");
        for (String closed : new String[] {
            "/v1/state", "/v1/events", "/v1/update", "/v1/notify", "/v1/dashboard", "/v1/dashboard/token",
            "/v1/rest/psk", "/v1/ambient", "/v1/media/artwork", "/v1/setup", "/v1/picture",
            "/v1/info/", "/v1/infox", "/v1/pair/", "/v1/pair/x", "/V1/INFO", "/v1/Pair", "//v1/info",
            "/v1/info/../state", "", "/v1", "/v2/info", "v1/info"}) {
            check(!Access.isPublic(closed), "'" + closed + "' needs the token");
        }
        check(!Access.isPublic(null), "no path");
    }

    private static void updates() {
        String url = "https://github.com/x/y/releases/download/v1.2.3/app.apk";
        eq(null, UpdatePolicy.refusal(url, HASH, "1.2.3"), "an https address, a hash and a version");
        eq(null, UpdatePolicy.refusal(url, HASH.toUpperCase(), "10"), "upper-case hash, one-part version");
        eq("url_required", UpdatePolicy.refusal(null, HASH, "1.2.3"), "no address");
        eq("url_required", UpdatePolicy.refusal("http://github.com/app.apk", HASH, "1.2.3"), "plain http");
        eq("url_required", UpdatePolicy.refusal("https:///app.apk", HASH, "1.2.3"), "no host");
        eq("url_required", UpdatePolicy.refusal("https://user:pw@github.com/app.apk", HASH, "1.2.3"), "credentials in it");
        eq("url_required", UpdatePolicy.refusal("file:///sdcard/app.apk", HASH, "1.2.3"), "a file");
        eq("url_required", UpdatePolicy.refusal("https://exa mple.com/app.apk", HASH, "1.2.3"), "not a URL");
        eq("sha256_required", UpdatePolicy.refusal(url, null, "1.2.3"), "no hash");
        eq("sha256_required", UpdatePolicy.refusal(url, "", "1.2.3"), "empty hash");
        eq("sha256_required", UpdatePolicy.refusal(url, HASH.substring(1), "1.2.3"), "short hash");
        eq("sha256_required", UpdatePolicy.refusal(url, HASH.substring(1) + "g", "1.2.3"), "not hex");
        eq("version_required", UpdatePolicy.refusal(url, HASH, null), "no version");
        eq("version_required", UpdatePolicy.refusal(url, HASH, ""), "empty version");
        eq("version_required", UpdatePolicy.refusal(url, HASH, "1.2.3\nInstall me"), "text for the screen is not a version");
        eq("version_required", UpdatePolicy.refusal(url, HASH, "1.2.3.4.5"), "too many parts");
        eq(HASH, UpdatePolicy.normalHash(HASH.toUpperCase()), "stored in lower case");

        check(UpdatePolicy.isApplied("0.9.1", "0.9.1"), "the same version is running");
        check(UpdatePolicy.isApplied("0.9.0", "0.9.1"), "an older one is behind us");
        check(UpdatePolicy.isApplied("1.0", "1.0.0"), "missing parts are zero");
        check(!UpdatePolicy.isApplied("0.10.0", "0.9.1"), "a newer one is still to do");
        check(!UpdatePolicy.isApplied("", "0.9.1") && !UpdatePolicy.isApplied(null, "0.9.1"), "nothing staged");
        check(UpdatePolicy.MAX_APK_BYTES >= 8L * 1024 * 1024, "room for the app to grow");
    }

    private static void deviceId() {
        eq(UID, DeviceId.fromInstanceName("BRAVIA 8 II-" + UID), "the whole id");
        eq(UID, DeviceId.fromInstanceName("Wohn-Zimmer TV-" + UID.toUpperCase() + " (2)"), "upper case, a hyphen in the name, a renamed duplicate");
        // A long device name: the announcement is cut and the id loses its end.
        String cut = "BRAVIA Theatre Bar 7-" + UID.substring(0, 39);
        eq(60, cut.length(), "as seen: 60 characters");
        eq(UID.substring(0, 39), DeviceId.fromInstanceName(cut), "what is left of the id");
        eq(UID.substring(0, 20), DeviceId.fromInstanceName("Den-cafe0001-" + UID.substring(0, 20)), "a name that ends like an id");
        eq(null, DeviceId.fromInstanceName("A television with a name much longer than fits-" + UID.substring(0, 7)), "too little left");
        eq(null, DeviceId.fromInstanceName("Kitchen TV"), "no id");
        eq(null, DeviceId.fromInstanceName("HT-A7000"), "a model name is not an id");
        eq(null, DeviceId.fromInstanceName(null), "nothing");
    }

    private static void eq(Object expected, Object actual, String what) {
        if (!String.valueOf(expected).equals(String.valueOf(actual))) {
            System.err.println("FAIL: " + what + ": expected " + expected + ", got " + actual);
            System.exit(1);
        }
        passed++;
    }

    private static void check(boolean ok, String what) {
        if (!ok) {
            System.err.println("FAIL: " + what);
            System.exit(1);
        }
        passed++;
    }
}
