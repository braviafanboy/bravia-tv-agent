package com.braviafanboy.settingsdump;

import android.app.Activity;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;

/**
 * Throwaway probe: dumps Sony's settings provider tables and the Living Decor
 * theme list to logcat (tag SettingsDump), then exits. Read with
 * {@code adb logcat -d -s SettingsDump}.
 */
public class DumpActivity extends Activity {
    private static final String TAG = "SettingsDump";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        for (String table : new String[] {"global", "system", "secure_global", "secure"}) {
            dump("content://com.sony.dtv.settingsprovider/" + table);
        }
        dump("content://com.sony.dtv.livingfit/quicklauncher/contents?themeListType=all");
        Log.i(TAG, "DONE");
        finish();
    }

    private void dump(String uri) {
        Log.i(TAG, "=== " + uri);
        try (Cursor c = getContentResolver().query(Uri.parse(uri), null, null, null, null)) {
            if (c == null) {
                Log.i(TAG, "(null cursor)");
                return;
            }
            String[] cols = c.getColumnNames();
            Log.i(TAG, "columns=" + String.join(",", cols) + " rows=" + c.getCount());
            while (c.moveToNext()) {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < cols.length; i++) {
                    if (i > 0) sb.append(" | ");
                    sb.append(cols[i]).append('=').append(c.isNull(i) ? "null" : c.getString(i));
                }
                Log.i(TAG, sb.toString());
            }
        } catch (Exception e) {
            Log.i(TAG, "ERROR " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }
}
