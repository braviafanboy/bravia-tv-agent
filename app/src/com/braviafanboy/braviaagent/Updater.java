package com.braviafanboy.braviaagent;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInstaller;
import android.os.Build;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;

/**
 * Self-update: download an APK and hand it to Android's package installer,
 * which asks the user to confirm on screen. The integration stores the
 * pending update with {@code POST /v1/update} (authenticated) and then
 * launches {@link UpdateActivity}, because the confirmation can only be
 * started from a foreground activity. Pairing survives an in-place update as
 * long as the signing key is unchanged.
 */
public final class Updater {
    private static final String TAG = "BraviaAgent";
    private static final String PREFS = "update";
    static final String ACTION_STATUS = "com.braviafanboy.braviaagent.UPDATE_STATUS";

    public interface Progress {
        void onProgress(String message);
    }

    private final Context context;
    private final SharedPreferences prefs;

    public Updater(Context context) {
        this.context = context.getApplicationContext();
        prefs = this.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public void setPending(String url, String sha256, String version) {
        prefs.edit().putString("url", url).putString("sha256", sha256 == null ? "" : sha256.toLowerCase())
            .putString("version", version == null ? "" : version).apply();
    }

    public String pendingUrl() {
        return prefs.getString("url", null);
    }

    public String pendingVersion() {
        return prefs.getString("version", "");
    }

    /** Whether a staged version is already running (equal or older than current). */
    static boolean isApplied(String pending, String current) {
        if (pending == null || pending.isEmpty()) return false;
        String[] a = pending.split("\\."), b = current.split("\\.");
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            int x = i < a.length ? parse(a[i]) : 0, y = i < b.length ? parse(b[i]) : 0;
            if (x != y) return x < y;
        }
        return true;
    }

    private static int parse(String s) {
        try { return Integer.parseInt(s.replaceAll("[^0-9].*$", "")); } catch (Exception e) { return 0; }
    }

    public void clearPending() {
        prefs.edit().clear().apply();
    }

    /** Download, verify and start the install; blocks, call off the main thread. */
    public void run(Progress progress) throws Exception {
        String url = pendingUrl();
        if (url == null) throw new IllegalStateException("no pending update");
        String expected = prefs.getString("sha256", "");
        File apk = new File(context.getCacheDir(), "update.apk");
        progress.onProgress("Downloading " + url);
        download(url, apk);
        if (!expected.isEmpty()) {
            String actual = sha256(apk);
            if (!actual.equals(expected)) {
                apk.delete();
                throw new SecurityException("checksum mismatch");
            }
        }
        progress.onProgress("Installing…");
        install(apk);
    }

    private static void download(String url, File dest) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(60000);
        c.setInstanceFollowRedirects(true);
        try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(dest)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        } finally {
            c.disconnect();
        }
    }

    private static String sha256(File f) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
        }
        StringBuilder sb = new StringBuilder();
        for (byte b : md.digest()) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private void install(File apk) throws Exception {
        PackageInstaller installer = context.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params =
            new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(context.getPackageName());
        if (Build.VERSION.SDK_INT >= 31) params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED);
        int id = installer.createSession(params);
        try (PackageInstaller.Session session = installer.openSession(id);
             InputStream in = new FileInputStream(apk);
             OutputStream out = session.openWrite("bravia-agent.apk", 0, apk.length())) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            session.fsync(out);
            out.close();
            Intent status = new Intent(ACTION_STATUS).setPackage(context.getPackageName());
            android.app.PendingIntent pi = android.app.PendingIntent.getBroadcast(
                context, id, status, android.app.PendingIntent.FLAG_MUTABLE | android.app.PendingIntent.FLAG_UPDATE_CURRENT);
            session.commit(pi.getIntentSender());
            Log.i(TAG, "update session committed");
        }
    }
}
