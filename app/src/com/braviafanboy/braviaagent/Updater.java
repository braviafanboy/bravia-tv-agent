package com.braviafanboy.braviaagent;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInstaller;
import android.util.Log;

import com.braviafanboy.braviaagent.core.UpdatePolicy;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;

/**
 * Self-update: download an APK and hand it to Android's package installer,
 * which asks the user to confirm on screen. The integration stages the update
 * with {@code POST /v1/update} (authenticated; the only way to stage one) and
 * then launches {@link UpdateActivity}, because the confirmation can only be
 * started from a foreground activity. Pairing survives an in-place update as
 * long as the signing key is unchanged.
 *
 * <p>Android only installs an APK of this package, signed with the same key
 * and no older than what is installed, and always asks first. What the agent
 * adds is in {@link UpdatePolicy}: https only, a SHA-256 the download must
 * match, a size limit. An update that fails for any reason is no longer
 * staged.
 */
public final class Updater {
    private static final String TAG = "BraviaAgent";
    private static final String PREFS = "update";
    static final String ACTION_STATUS = "com.braviafanboy.braviaagent.UPDATE_STATUS";
    /**
     * How long a staged update waits to be run. The integration stages one
     * and has the TV launch the app at once; an update that was never run, or
     * whose outcome never arrived, must not sit there and take over a later
     * launch.
     */
    private static final long STAGED_FOR_MS = 15 * 60 * 1000L;

    public interface Progress {
        void onProgress(String message);
    }

    private final Context context;
    private final SharedPreferences prefs;
    /** The installer session of the update in progress, or -1. */
    private static volatile int session = -1;

    public Updater(Context context) {
        this.context = context.getApplicationContext();
        prefs = this.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Stage an update; the caller has checked it with {@link UpdatePolicy#refusal}. */
    public void setPending(String url, String sha256, String version) {
        prefs.edit().putString("url", url).putString("sha256", UpdatePolicy.normalHash(sha256))
            .putString("version", version == null ? "" : version)
            .putLong("staged_at", System.currentTimeMillis()).apply();
    }

    /** The staged update's address, or null when none is staged (any longer). */
    public String pendingUrl() {
        String url = prefs.getString("url", null);
        if (url == null) return null;
        long age = System.currentTimeMillis() - prefs.getLong("staged_at", 0);
        if (age < 0 || age > STAGED_FOR_MS) {
            clearPending();
            return null;
        }
        return url;
    }

    public String pendingVersion() {
        return prefs.getString("version", "");
    }

    public void clearPending() {
        prefs.edit().clear().apply();
        apkFile().delete();
    }

    /** The installer session started by {@link #run}, for checking its status broadcasts. */
    static int sessionId() {
        return session;
    }

    private File apkFile() {
        return new File(context.getCacheDir(), "update.apk");
    }

    /**
     * Download, verify and start the install; blocks, call off the main
     * thread. Whatever goes wrong, the update is no longer staged afterwards
     * and nothing of the download is kept.
     */
    public void run(Progress progress) throws Exception {
        String url = pendingUrl();
        if (url == null) throw new IllegalStateException("no pending update");
        String expected = prefs.getString("sha256", "");
        File apk = apkFile();
        try {
            // The same rules as at staging: an update staged by an older
            // version of the agent may not have met them.
            String refusal = UpdatePolicy.refusal(url, expected, pendingVersion());
            if (refusal != null) throw new SecurityException("not an acceptable update (" + refusal + ")");
            progress.onProgress("Downloading…");
            download(url, apk);
            if (!sha256(apk).equals(expected)) throw new SecurityException("checksum mismatch");
            progress.onProgress("Installing…");
            install(apk);
        } catch (Exception e) {
            clearPending();
            throw e;
        } finally {
            apk.delete();
        }
    }

    private static void download(String url, File dest) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(60000);
        c.setInstanceFollowRedirects(true);
        try {
            int status = c.getResponseCode();
            if (status != 200) throw new IOException("HTTP " + status);
            // Where a redirect led must be https as well.
            if (!"https".equalsIgnoreCase(c.getURL().getProtocol())) throw new SecurityException("redirected off https");
            if (c.getContentLengthLong() > UpdatePolicy.MAX_APK_BYTES) throw new IOException("too large for an update");
            try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(dest)) {
                byte[] buf = new byte[65536];
                long total = 0;
                int n;
                while ((n = in.read(buf)) > 0) {
                    total += n;
                    if (total > UpdatePolicy.MAX_APK_BYTES) throw new IOException("too large for an update");
                    out.write(buf, 0, n);
                }
            }
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
        // Only an APK of this package is accepted into the session.
        params.setAppPackageName(context.getPackageName());
        params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED);
        int id = installer.createSession(params);
        session = id;
        try (PackageInstaller.Session s = installer.openSession(id)) {
            try (InputStream in = new FileInputStream(apk);
                 OutputStream out = s.openWrite("bravia-agent.apk", 0, apk.length())) {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                s.fsync(out);
            }
            // The stream is closed: the session can be committed.
            Intent status = new Intent(ACTION_STATUS).setPackage(context.getPackageName());
            android.app.PendingIntent pi = android.app.PendingIntent.getBroadcast(
                context, id, status, android.app.PendingIntent.FLAG_MUTABLE | android.app.PendingIntent.FLAG_UPDATE_CURRENT);
            s.commit(pi.getIntentSender());
            Log.i(TAG, "update session committed");
        }
    }
}
