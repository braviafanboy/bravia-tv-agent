package com.braviafanboy.braviaagent.readers;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONObject;

/** Living Decor theme list from its content provider, cached for an hour. */
public final class ThemesReader {
    private static final Uri THEMES = Uri.parse(
        "content://com.sony.dtv.livingfit/quicklauncher/contents?themeListType=all");
    private static final long CACHE_MS = 3600_000;

    private final Context context;
    private JSONArray cached;
    private long cachedAt;

    public ThemesReader(Context context) {
        this.context = context;
    }

    public synchronized JSONArray themes() {
        long now = SystemClock.elapsedRealtime();
        if (cached != null && now - cachedAt < CACHE_MS) return cached;
        JSONArray arr = new JSONArray();
        try (Cursor c = context.getContentResolver().query(THEMES, null, null, null, null)) {
            if (c != null) {
                int id = c.getColumnIndex("id"), title = c.getColumnIndex("title");
                int desc = c.getColumnIndex("description"), thumb = c.getColumnIndex("thumbnail");
                while (c.moveToNext()) {
                    JSONObject o = new JSONObject();
                    String rawId = c.getString(id);
                    // Strip the launcher's referrer query; the bare theme:// id is what select_media takes.
                    int q = rawId.indexOf('?');
                    o.put("id", q > 0 ? rawId.substring(0, q) : rawId);
                    o.put("title", title >= 0 ? c.getString(title) : JSONObject.NULL);
                    o.put("description", desc >= 0 ? c.getString(desc) : JSONObject.NULL);
                    o.put("thumbnail", thumb >= 0 ? c.getString(thumb) : JSONObject.NULL);
                    arr.put(o);
                }
            }
        } catch (Exception ignored) {
        }
        cached = arr;
        cachedAt = now;
        return arr;
    }
}
