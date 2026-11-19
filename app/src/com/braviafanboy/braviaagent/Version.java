package com.braviafanboy.braviaagent;

import android.content.Context;

/**
 * The agent's version: the manifest's {@code versionName}, read from the
 * installed package. It is written in that one place only (a second copy in
 * the code once fell behind, and a release reported the wrong version).
 */
public final class Version {
    private static volatile String cached;

    private Version() {
    }

    public static String of(Context context) {
        String v = cached;
        if (v == null) {
            try {
                v = context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName;
            } catch (Exception e) {
                v = null;
            }
            if (v == null) v = "0";
            cached = v;
        }
        return v;
    }
}
