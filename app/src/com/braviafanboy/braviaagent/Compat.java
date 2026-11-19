package com.braviafanboy.braviaagent;

import android.content.Intent;
import android.os.Build;
import android.os.Parcelable;

/** Calls that differ between the Android versions the agent runs on (12 and later). */
public final class Compat {
    private Compat() {
    }

    /**
     * A typed parcelable extra. {@code Intent.getParcelableExtra(String, Class)}
     * exists from Android 13 (API 33); calling it on Android 12 ends the
     * process with NoSuchMethodError, so there the untyped one is used.
     */
    @SuppressWarnings("deprecation")
    public static <T extends Parcelable> T parcelableExtra(Intent intent, String name, Class<T> type) {
        if (Build.VERSION.SDK_INT >= 33) return intent.getParcelableExtra(name, type);
        Parcelable value = intent.getParcelableExtra(name);
        return type.isInstance(value) ? type.cast(value) : null;
    }
}
