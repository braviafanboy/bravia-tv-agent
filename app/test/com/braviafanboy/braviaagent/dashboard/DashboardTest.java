package com.braviafanboy.braviaagent.dashboard;

import java.util.Arrays;

/** Tests for the dashboard request logic and token box, run on a desktop JVM by app/test.sh. */
public final class DashboardTest {
    private static int passed;

    public static void main(String[] args) throws Exception {
        origins();
        parsing();
        geometry();
        payloads();
        tokens();
        System.out.println("ok: " + passed + " dashboard checks passed");
    }

    private static void origins() {
        eq("http://192.168.1.5:8123", DashboardSpec.originOf("http://192.168.1.5:8123/lovelace/0"), "ip origin");
        eq("https://ha.local", DashboardSpec.originOf("HTTPS://HA.local:443/x"), "default port dropped, lower-cased");
        eq("http://ha.local", DashboardSpec.originOf("http://ha.local:80"), "http default port dropped");
        eq(null, DashboardSpec.originOf("file:///sdcard/x.html"), "file refused");
        eq(null, DashboardSpec.originOf("javascript:alert(1)"), "javascript refused");
        eq(null, DashboardSpec.originOf("http://user:pw@ha.local/"), "userinfo refused");
        eq(null, DashboardSpec.originOf("not a url"), "garbage refused");
        eq(null, DashboardSpec.originOf(null), "null");
    }

    private static void parsing() {
        DashboardSpec s = DashboardSpec.parse("http://ha:8123/lovelace/0", null, null, 0, 0, true);
        eq(DashboardSpec.Mode.FULL, s.mode, "full by default");
        eq(0, s.durationS, "full stays until closed");
        eq("http://ha:8123/lovelace/0?external_auth=1", s.url, "external auth appended");
        check(s.sameOrigin("http://ha:8123/energy"), "same origin path");
        check(!s.sameOrigin("http://evil:8123/"), "other host");
        check(!s.sameOrigin("https://ha:8123/"), "other scheme");

        DashboardSpec o = DashboardSpec.parse("http://ha:8123/x?a=1#top", "overlay", "top_right", 30, 0, true);
        eq(DashboardSpec.DEFAULT_OVERLAY_S, o.durationS, "overlay default timeout");
        eq("http://ha:8123/x?a=1&external_auth=1#top", o.url, "query and fragment kept");
        eq(DashboardSpec.MAX_DURATION_S, DashboardSpec.parse("http://ha/", "overlay", "left", 50, 99999, true).durationS, "duration capped");
        eq("http://ha/?external_auth=1", DashboardSpec.parse("http://ha/?external_auth=1", null, null, 0, 0, true).url, "not doubled");

        refused("ftp://ha/", "full", "right", 40, "invalid_url");
        refused("http://ha/", "popup", "right", 40, "invalid_mode");
        refused("http://ha/", "overlay", "middle", 40, "invalid_position");
        refused("http://ha/", "overlay", "right", 10, "invalid_size");
        refused("http://ha/", "overlay", "right", 101, "invalid_size");
    }

    private static void refused(String url, String mode, String pos, int size, String code) {
        try {
            DashboardSpec.parse(url, mode, pos, size, 0, true);
            check(false, "expected " + code + " for " + url + " " + mode + " " + pos + " " + size);
        } catch (IllegalArgumentException e) {
            eq(code, e.getMessage(), "refusal code");
        }
    }

    private static void geometry() {
        int w = 1920, h = 1080;
        eq("[0, 0, 1920, 1080]", Arrays.toString(DashboardSpec.parse("http://ha/", "full", null, 0, 0, true).bounds(w, h)), "full screen");
        eq("[0, 0, 1920, 1080]", Arrays.toString(DashboardSpec.parse("http://ha/", "overlay", "full", 30, 0, true).bounds(w, h)), "overlay full ignores size");
        eq("[1152, 0, 768, 1080]", Arrays.toString(DashboardSpec.parse("http://ha/", "overlay", "right", 40, 0, true).bounds(w, h)), "right panel");
        eq("[0, 0, 768, 1080]", Arrays.toString(DashboardSpec.parse("http://ha/", "overlay", "left", 40, 0, true).bounds(w, h)), "left panel");
        // Corner: 30% each way, on the top/bottom edge, 3% (32 px) in from the side.
        eq("[1312, 0, 576, 324]", Arrays.toString(DashboardSpec.parse("http://ha/", "overlay", "top_right", 30, 0, true).bounds(w, h)), "top right corner");
        eq("[32, 756, 576, 324]", Arrays.toString(DashboardSpec.parse("http://ha/", "overlay", "bottom_left", 30, 0, true).bounds(w, h)), "bottom left corner");

        // Zoom: a 1920-px-wide screen at density 2 is 960 CSS px; full width needs none.
        eq(0, DashboardSpec.initialScalePercent(1920, 2f), "full width keeps the normal scale");
        eq(0, DashboardSpec.initialScalePercent(960, 2f), "exactly the minimum keeps the normal scale");
        eq(120, DashboardSpec.initialScalePercent(576, 2f), "30% corner zooms out to 480 CSS px");
        eq(160, DashboardSpec.initialScalePercent(768, 2f), "40% side panel zooms out to 480 CSS px");
        eq(0, DashboardSpec.initialScalePercent(768, 1f), "density 1: already wide enough");
        eq(0, DashboardSpec.initialScalePercent(0, 2f), "no width");
    }

    private static void payloads() {
        String set = DashboardSpec.CALLBACK_SET_TOKEN;
        eq(set, DashboardSpec.callback("{\"callback\":\"externalAuthSetToken\"}", set), "set callback");
        eq(set, DashboardSpec.callback("{\"callback\":\"externalAuthSetToken\",\"force\":true}", set), "with force");
        eq(null, DashboardSpec.callback("{\"callback\":\"alert\"}", set), "other name refused");
        eq(null, DashboardSpec.callback("{\"callback\":\"x);alert(1);//\"}", set), "injection refused");
        eq(null, DashboardSpec.callback(null, set), "null payload");
        check(DashboardSpec.force("{\"callback\":\"externalAuthSetToken\",\"force\":true}"), "force true");
        check(!DashboardSpec.force("{\"callback\":\"externalAuthSetToken\"}"), "no force");
    }

    private static void tokens() throws Exception {
        long[] now = {1_000_000};
        TokenBox.Clock clock = () -> now[0];
        TokenBox box = new TokenBox();
        eq(null, box.valid(now[0]), "empty");
        box.set("abc", 1800, now[0]);
        eq("abc", box.valid(now[0]).value, "fresh");
        eq(1800, box.valid(now[0]).expiresInS(now[0]), "expires in");
        now[0] += 1800_000 - TokenBox.MARGIN_MS + 1;
        eq(null, box.valid(now[0]), "inside the margin counts as expired");
        box.set("", 1800, now[0]);
        box.set("x", 0, now[0]);
        eq(null, box.valid(now[0]), "empty or zero-lifetime tokens ignored");

        // Waiting: times out with no new token, wakes on a new one.
        long gen = box.generation();
        eq(null, box.awaitNewer(gen, 50, System::currentTimeMillis), "times out");
        Thread t = new Thread(() -> {
            try { Thread.sleep(50); } catch (InterruptedException ignored) { }
            box.set("def", 1800, System.currentTimeMillis());
        });
        t.start();
        TokenBox.Token got = box.awaitNewer(gen, 5000, System::currentTimeMillis);
        t.join();
        eq("def", got == null ? null : got.value, "woken by a new token");
        box.clear();
        eq(null, box.valid(now[0]), "cleared");
        check(clock.nowMs() == now[0], "clock");
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
