package com.braviafanboy.braviaagent;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.graphics.Color;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Shows a message from Home Assistant on the TV screen, as an overlay banner
 * drawn over whatever is playing. Needs the "Display over other apps" permission
 * (SYSTEM_ALERT_WINDOW). Without it, falls back to a system notification (which
 * on Google TV only reaches the notification panel) so the message is not lost.
 *
 * <p>All window work runs on the main thread. A new message replaces the one on
 * screen. The banner auto-dismisses after its duration.
 */
public final class Notifier {
    private static final String TAG = "BraviaAgent";
    private static final String CHANNEL_ID = "ha_notify";
    private static final int NOTIFICATION_ID = 42;
    private static final int DEFAULT_DURATION_S = 8;
    private static final int MAX_DURATION_S = 120;
    // Match Sony's own notification motion: 500 ms slide-by-height + crossfade,
    // default ease-in-out curve (SonyNotificationView uses the same).
    private static final long OPEN_MS = 500L;
    private static final long CLOSE_MS = 500L;
    private static final android.view.animation.Interpolator EASE =
        new android.view.animation.AccelerateDecelerateInterpolator();
    // Friendly icon keys -> native Sony SystemUI drawable names (loaded at runtime,
    // tinted white). Unknown/absent -> framework fallback; "none"/empty -> no icon.
    private static final Map<String, String> ICONS = new HashMap<>();
    static {
        ICONS.put("message", "ic_message");
        ICONS.put("alert", "ic_notifications_alert");
        ICONS.put("info", "ic_info_outline");
        ICONS.put("home", "home_icon");
        ICONS.put("cast", "ic_cast");
        ICONS.put("speaker", "ic_speaker_white");
        ICONS.put("remote", "ic_device_remote_control");
    }

    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());

    // Main thread only.
    private View current;
    private final Runnable dismiss = this::animateOut;

    public Notifier(Context context) {
        this.context = context.getApplicationContext();
    }

    /** Whether the overlay permission is granted (the banner can show on screen). */
    public boolean canOverlay() {
        return Settings.canDrawOverlays(context);
    }

    /**
     * Show a message. Returns "shown" (overlay banner), "notification" (overlay
     * not permitted, posted to the panel instead) or "failed".
     */
    public String show(String title, String message, int durationS, String icon) {
        String text = message == null ? "" : message.trim();
        String head = title == null ? "" : title.trim();
        if (text.isEmpty() && head.isEmpty()) return "failed";
        int seconds = durationS <= 0 ? DEFAULT_DURATION_S : Math.min(durationS, MAX_DURATION_S);
        if (!canOverlay()) {
            postNotification(head, text);
            return "notification";
        }
        final boolean[] ok = {false};
        runOnMainBlocking(() -> ok[0] = showOverlay(head, text, seconds, icon));
        if (ok[0]) return "shown";
        postNotification(head, text);
        return "notification";
    }

    private boolean showOverlay(String title, String message, int seconds, String icon) {
        WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        if (wm == null) return false;
        removeCurrent();
        try {
            View banner = buildBanner(title, message, icon);
            int type = Build.VERSION.SDK_INT >= 26
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_SYSTEM_ALERT;
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                // No FLAG_NOT_TOUCHABLE: Android 12+ caps a non-touchable overlay's
                // alpha to 0.80 (anti-tapjacking), which made the card translucent.
                // A TV has no touchscreen, and FLAG_NOT_FOCUSABLE keeps the remote's
                // keys flowing to the app behind, so we drop it and stay fully opaque.
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                android.graphics.PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
            lp.y = 0;
            banner.setAlpha(0f);
            wm.addView(banner, lp);
            final View shown = banner;
            current = banner;
            // Slide in from the screen edge, fully opaque (no fade): Sony's own
            // open animation only translates; its alpha stays at 1.
            shown.post(() -> {
                int h = shown.getHeight() > 0 ? shown.getHeight() : dp(120);
                shown.setTranslationY(-h);
                shown.setAlpha(1f);
                shown.animate().translationY(0f)
                    .setDuration(OPEN_MS).setInterpolator(EASE).start();
            });
            main.removeCallbacks(dismiss);
            main.postDelayed(dismiss, seconds * 1000L);
            return true;
        } catch (Exception e) {
            android.util.Log.w(TAG, "overlay failed: " + e);
            return false;
        }
    }

    private View buildBanner(String title, String message, String icon) {
        // Matches the native volume popup: solid dark grey (~36 tone, opaque so it
        // is not see-through over bright content), ~3px radius; title 14sp
        // #ffffffff, description 12sp #99ffffff, default system font. Optional
        // icon on the left, vertically centred, like the native card.
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int padH = dp(20), padV = dp(14);
        row.setPadding(padH, dp(10), padH, padV);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF2B2B2B);
        bg.setCornerRadius(dp(3));
        row.setBackground(bg);
        row.setMinimumWidth(dp(240));

        Drawable iconDr = loadIcon(icon);
        if (iconDr != null) {
            ImageView iv = new ImageView(context);
            iv.setImageDrawable(iconDr);
            iv.setColorFilter(0xFFFFFFFF);
            LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(dp(18), dp(18));
            ilp.setMarginEnd(dp(12));
            iv.setLayoutParams(ilp);
            row.addView(iv);
        }

        LinearLayout col = new LinearLayout(context);
        col.setOrientation(LinearLayout.VERTICAL);
        int maxW = dp(520);
        if (!title.isEmpty()) {
            TextView t = new TextView(context);
            t.setText(title);
            t.setTextColor(0xFFFFFFFF);
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            t.setMaxLines(2);
            t.setEllipsize(android.text.TextUtils.TruncateAt.END);
            t.setMaxWidth(maxW);
            col.addView(t);
        }
        if (!message.isEmpty()) {
            TextView m = new TextView(context);
            m.setText(message);
            m.setTextColor(0xFFE8EAED);
            m.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            m.setMaxLines(4);
            m.setEllipsize(android.text.TextUtils.TruncateAt.END);
            m.setMaxWidth(maxW);
            if (!title.isEmpty()) {
                LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                mlp.topMargin = dp(4);
                m.setLayoutParams(mlp);
            }
            col.addView(m);
        }
        row.addView(col);
        return row;
    }

    /** Load a Sony SystemUI icon by friendly key; framework fallback; null = none. */
    private Drawable loadIcon(String key) {
        if (key == null) return null;
        String k = key.trim().toLowerCase(Locale.US);
        if (k.isEmpty() || k.equals("none")) return null;
        String name = ICONS.get(k);
        if (name != null) {
            try {
                Resources r = context.getPackageManager()
                    .getResourcesForApplication("com.android.systemui");
                int id = r.getIdentifier(name, "drawable", "com.android.systemui");
                if (id != 0) {
                    Drawable d = r.getDrawable(id, null);
                    if (d != null) return d.mutate();
                }
            } catch (Exception ignored) {
            }
        }
        try {
            return context.getDrawable(android.R.drawable.ic_dialog_info);
        } catch (Exception e) {
            return null;
        }
    }

    /** Slide the banner up and fade it out, then remove it. */
    private void animateOut() {
        final View v = current;
        if (v == null) return;
        current = null;
        main.removeCallbacks(dismiss);
        try {
            int h = v.getHeight() > 0 ? v.getHeight() : dp(120);
            v.animate().translationY(-h).alpha(0f)
                .setDuration(CLOSE_MS).setInterpolator(EASE)
                .withEndAction(() -> removeView(v)).start();
        } catch (Exception e) {
            removeView(v);
        }
    }

    private void removeView(View v) {
        try {
            WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
            if (wm != null) wm.removeView(v);
        } catch (Exception ignored) {
        }
    }

    private void removeCurrent() {
        main.removeCallbacks(dismiss);
        View v = current;
        current = null;
        if (v == null) return;
        try { v.animate().cancel(); } catch (Exception ignored) { }
        removeView(v);
    }

    private void postNotification(String title, String message) {
        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, "Home Assistant messages", NotificationManager.IMPORTANCE_HIGH);
            nm.createNotificationChannel(ch);
        }
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
            ? new Notification.Builder(context, CHANNEL_ID)
            : new Notification.Builder(context);
        b.setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title.isEmpty() ? "Home Assistant" : title)
            .setContentText(message)
            .setAutoCancel(true);
        try {
            nm.notify(NOTIFICATION_ID, b.build());
        } catch (Exception ignored) {
        }
    }

    private void runOnMainBlocking(Runnable r) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            r.run();
            return;
        }
        final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        main.post(() -> {
            try {
                r.run();
            } finally {
                latch.countDown();
            }
        });
        try {
            latch.await(3, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
        }
    }

    private int dp(int v) {
        return Math.round(v * context.getResources().getDisplayMetrics().density);
    }
}
