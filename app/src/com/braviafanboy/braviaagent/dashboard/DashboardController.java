package com.braviafanboy.braviaagent.dashboard;

import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.WindowManager;
import android.webkit.WebView;
import android.widget.FrameLayout;

import com.braviafanboy.braviaagent.DashboardActivity;
import com.braviafanboy.braviaagent.StateStore;

import org.json.JSONObject;

/**
 * Shows Home Assistant dashboards on the TV: full screen and interactive
 * (DashboardActivity; the remote navigates, Back closes) or as a view-only
 * overlay panel drawn over whatever is playing (the remote keeps controlling
 * the app behind). One dashboard at a time; a new one replaces the old.
 *
 * <p>Publishes a "dashboard" state block: {showing, mode, loaded,
 * token_needed}. Home Assistant answers token_needed with POST
 * /v1/dashboard/token.
 */
public final class DashboardController {
    private static final String TAG = "BraviaAgent";
    private static volatile DashboardController instance;

    private final Context context;
    private final StateStore store;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final TokenBox tokens = new TokenBox();
    private final Runnable timeout = this::close;
    /** Show the dashboard even if the frontend never says it has loaded. */
    private static final long REVEAL_FALLBACK_MS = 15_000;
    private final Runnable revealFallback = this::reveal;
    /** Let the cards finish painting after the frontend says it has loaded. */
    private static final long REVEAL_SETTLE_MS = 300;
    /** Drawn but invisible over any picture while loading. */
    private static final float LOADING_ALPHA = 0.01f;

    // Guarded by this.
    private DashboardSpec current;
    private boolean loaded;
    private boolean tokenNeeded;
    // Main thread only.
    private FrameLayout overlay;
    private FrameLayout overlayPanel;
    private WebView overlayWeb;
    private DashboardActivity activity;
    private boolean revealed;
    /** Where the overlay slides from and back to (off the nearest screen edge). */
    private float hideX, hideY;

    public DashboardController(Context context, StateStore store) {
        this.context = context.getApplicationContext();
        this.store = store;
        instance = this;
        publish();
    }

    /** The running controller, for DashboardActivity. */
    public static DashboardController get() {
        return instance;
    }

    public boolean canOverlay() {
        return Settings.canDrawOverlays(context);
    }

    /** The dashboard being shown (or about to be), or null. */
    public synchronized DashboardSpec current() {
        return current;
    }

    /**
     * Show a dashboard. Returns "shown", "no_permission" (an overlay needs
     * "Display over other apps") or "failed".
     */
    public String open(DashboardSpec spec, String token, int expiresInS) {
        // An overlay needs "Display over other apps"; so does full screen,
        // because Android only lets an app in the background bring an
        // activity forward when it holds that permission.
        if (!canOverlay()) return "no_permission";
        tokens.set(token, expiresInS, System.currentTimeMillis());
        synchronized (this) {
            current = spec;
            loaded = false;
            tokenNeeded = false;
        }
        final boolean[] ok = {false};
        runOnMainBlocking(() -> {
            main.removeCallbacks(timeout);
            main.removeCallbacks(revealFallback);
            revealed = false;
            removeOverlay();
            if (spec.mode == DashboardSpec.Mode.OVERLAY) {
                if (activity != null) activity.finishAndRemoveTask();
                ok[0] = showOverlay(spec);
            } else {
                ok[0] = startActivity();
            }
            // The time on screen counts from when the dashboard has rendered
            // (an overlay stays invisible until then, so Home Assistant's
            // white loading screen never flashes over a film).
            if (ok[0]) main.postDelayed(revealFallback, REVEAL_FALLBACK_MS);
        });
        if (!ok[0]) {
            synchronized (this) {
                if (current == spec) current = null;
            }
        }
        publish();
        Log.i(TAG, "dashboard: " + (ok[0] ? "shown" : "failed") + " (" + spec.mode + ")");
        return ok[0] ? "shown" : "failed";
    }

    /** Close whatever dashboard is showing and forget the token. */
    public void close() {
        synchronized (this) {
            current = null;
            loaded = false;
            tokenNeeded = false;
        }
        tokens.clear();
        main.post(() -> {
            main.removeCallbacks(timeout);
            main.removeCallbacks(revealFallback);
            animateOutOverlay();
            if (activity != null) activity.finishAndRemoveTask();
        });
        publish();
    }

    /** A fresh token from Home Assistant. */
    public void setToken(String token, int expiresInS) {
        tokens.set(token, expiresInS, System.currentTimeMillis());
        synchronized (this) {
            tokenNeeded = false;
        }
        publish();
    }

    /**
     * A token for the frontend: the current one unless it's expiring or the
     * frontend forced a refresh; otherwise ask Home Assistant (token_needed)
     * and wait up to {@code waitMs}. Null if none arrives.
     */
    TokenBox.Token tokenFor(boolean force, long waitMs) {
        long now = System.currentTimeMillis();
        long gen = tokens.generation();
        TokenBox.Token t = force ? null : tokens.valid(now);
        if (t != null) return t;
        synchronized (this) {
            if (current == null) return null;
            tokenNeeded = true;
        }
        publish();
        try {
            return tokens.awaitNewer(gen, waitMs, System::currentTimeMillis);
        } catch (InterruptedException e) {
            return null;
        }
    }

    void onFrontendLoaded(DashboardSpec spec) {
        synchronized (this) {
            // Ignore a late message from a dashboard that was since replaced.
            if (loaded || current != spec) return;
            loaded = true;
        }
        publish();
        main.postDelayed(this::reveal, REVEAL_SETTLE_MS);
    }

    /** Main thread: fade the overlay in and start the time on screen (once per dashboard). */
    private void reveal() {
        if (revealed) return;
        DashboardSpec spec = current();
        if (spec == null) return;
        revealed = true;
        main.removeCallbacks(revealFallback);
        if (overlayPanel != null) {
            // Like Sony's notifications (and the agent's banner): slide in from
            // the screen edge, fully opaque, 500 ms ease-in-out.
            overlayPanel.setTranslationX(hideX);
            overlayPanel.setTranslationY(hideY);
            overlayPanel.setAlpha(1f);
            overlayPanel.animate().translationX(0f).translationY(0f)
                .setDuration(com.braviafanboy.braviaagent.Notifier.OPEN_MS)
                .setInterpolator(com.braviafanboy.braviaagent.Notifier.EASE).start();
        }
        if (spec.durationS > 0) main.postDelayed(timeout, spec.durationS * 1000L);
    }

    /** The screen went off: nothing to show on. */
    public void onPanel(boolean on) {
        if (!on && current() != null) close();
    }

    // --- Full screen -------------------------------------------------------

    private boolean startActivity() {
        try {
            Intent i = new Intent(context, DashboardActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            context.startActivity(i);
            return true;
        } catch (Exception e) {
            Log.w(TAG, "dashboard: activity start failed: " + e);
            return false;
        }
    }

    /** DashboardActivity is up (main thread). */
    public void attach(DashboardActivity a) {
        activity = a;
    }

    /** DashboardActivity went away (Back, Home, or closed by us; main thread). */
    public void detach(DashboardActivity a) {
        if (activity != a) return;
        activity = null;
        boolean wasFull;
        synchronized (this) {
            wasFull = current != null && current.mode == DashboardSpec.Mode.FULL;
        }
        if (wasFull) close();
    }

    // --- Overlay -----------------------------------------------------------

    private boolean showOverlay(DashboardSpec spec) {
        WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        if (wm == null) return false;
        try {
            Rect screen = wm.getCurrentWindowMetrics().getBounds();
            int sw = screen.width(), sh = screen.height();
            int[] b = spec.bounds(sw, sh);
            // The panel looks and moves like the agent's banner (Sony's own
            // notification style): dark grey, small radius, slides in from the
            // nearest screen edge. A window clips its content, so the window
            // reaches from the panel to that edge (the gap is transparent) and
            // the panel travels inside it.
            int wx = b[0], wy = b[1], ww = b[2], wh = b[3], px = 0, py = 0;
            hideX = 0;
            hideY = 0;
            switch (spec.position) {
                case "left":
                    hideX = -b[2];
                    break;
                case "right":
                    hideX = b[2];
                    break;
                case "bottom_left": case "bottom_right":
                    wh = sh - b[1];
                    hideY = wh;
                    break;
                default:  // top corners and full: from the top
                    wy = 0;
                    wh = b[1] + b[3];
                    py = b[1];
                    hideY = -wh;
                    break;
            }
            FrameLayout window = new FrameLayout(context);
            FrameLayout panel = new FrameLayout(context);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(com.braviafanboy.braviaagent.Notifier.BACKGROUND);
            if (!spec.position.equals("full")) bg.setCornerRadius(dp(com.braviafanboy.braviaagent.Notifier.CORNER_DP));
            panel.setBackground(bg);
            panel.setClipToOutline(true);
            WebView web = DashboardWeb.create(context, this, spec, b[2]);
            panel.addView(web, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
            FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(b[2], b[3]);
            plp.leftMargin = px;
            plp.topMargin = py;
            window.addView(panel, plp);
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                ww, wh,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                // Not focusable: the remote keeps controlling the app behind.
                // Touchable (no FLAG_NOT_TOUCHABLE) so Android doesn't cap the
                // overlay's alpha at 0.8; a TV has no touchscreen anyway.
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                    | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.START;
            lp.x = wx;
            lp.y = wy;
            // Practically invisible until the dashboard has rendered, but in
            // place and above alpha 0: Android skips drawing a view at alpha 0,
            // so the WebView would only paint its first frame during the slide
            // and the slide would stutter into a pop. This way it is fully
            // drawn before it moves.
            panel.setAlpha(LOADING_ALPHA);
            wm.addView(window, lp);
            overlay = window;
            overlayPanel = panel;
            overlayWeb = web;
            web.loadUrl(spec.url);
            return true;
        } catch (Exception e) {
            Log.w(TAG, "dashboard: overlay failed: " + e);
            removeOverlay();
            return false;
        }
    }

    /** Slide the panel back out the way it came, fading, then remove it (main thread). */
    private void animateOutOverlay() {
        final FrameLayout window = overlay;
        final FrameLayout panel = overlayPanel;
        final WebView web = overlayWeb;
        if (window == null) return;
        overlay = null;
        overlayPanel = null;
        overlayWeb = null;
        if (!revealed || panel == null) {
            removeViews(window, web);
            return;
        }
        try {
            panel.animate().translationX(hideX).translationY(hideY).alpha(0f)
                .setDuration(com.braviafanboy.braviaagent.Notifier.CLOSE_MS)
                .setInterpolator(com.braviafanboy.braviaagent.Notifier.EASE)
                .withEndAction(() -> removeViews(window, web)).start();
        } catch (Exception e) {
            removeViews(window, web);
        }
    }

    /** Remove the overlay at once (replaced by another dashboard; main thread). */
    private void removeOverlay() {
        FrameLayout window = overlay;
        WebView web = overlayWeb;
        overlay = null;
        overlayPanel = null;
        overlayWeb = null;
        removeViews(window, web);
    }

    private void removeViews(FrameLayout window, WebView web) {
        if (window != null) {
            try {
                WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
                if (wm != null) wm.removeView(window);
            } catch (Exception ignored) {
            }
        }
        if (web != null) {
            try {
                web.destroy();
            } catch (Exception ignored) {
            }
        }
    }

    // --- State -------------------------------------------------------------

    private void publish() {
        JSONObject o = new JSONObject();
        try {
            synchronized (this) {
                o.put("showing", current != null);
                o.put("mode", current == null ? JSONObject.NULL : current.mode == DashboardSpec.Mode.FULL ? "full" : "overlay");
                o.put("loaded", loaded);
                o.put("token_needed", tokenNeeded);
            }
        } catch (Exception ignored) {
        }
        store.update("dashboard", o);
    }

    public JSONObject status() {
        Object b = store.snapshot().opt("dashboard");
        return b instanceof JSONObject ? (JSONObject) b : new JSONObject();
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
            latch.await(5, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
        }
    }

    private int dp(int v) {
        return Math.round(v * context.getResources().getDisplayMetrics().density);
    }
}
