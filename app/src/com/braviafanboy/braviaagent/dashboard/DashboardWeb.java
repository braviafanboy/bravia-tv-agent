package com.braviafanboy.braviaagent.dashboard;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONObject;

/**
 * The WebView that shows a Home Assistant dashboard, and the "externalApp"
 * bridge the Home Assistant frontend uses to get access tokens from a native
 * app (the same interface the official Android app implements).
 *
 * <p>Safety: navigation is locked to the dashboard's Home Assistant origin;
 * the bridge answers only while the main frame is on that origin; tokens are
 * delivered by evaluating a fixed callback in the main frame (an embedded
 * third-party frame can call the bridge but never sees the reply).
 */
public final class DashboardWeb {
    private static final String TAG = "BraviaAgent";
    /** How long a page token request waits for Home Assistant to send a fresh token. */
    private static final long TOKEN_WAIT_MS = 30_000;

    private DashboardWeb() {
    }

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    public static WebView create(Context context, DashboardController controller, DashboardSpec spec) {
        return create(context, controller, spec, 0);
    }

    /**
     * {@code widthPx} > 0: the panel's width, to zoom a narrow panel out so
     * Home Assistant lays it out like a tablet instead of a phone.
     */
    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    public static WebView create(Context context, DashboardController controller, DashboardSpec spec, int widthPx) {
        // The device-default theme on a TV is dark, and the WebView reports
        // the theme to the page (prefers-color-scheme), so Home Assistant's
        // default theme renders dark; a bare service context would be light.
        WebView web = new WebView(new android.view.ContextThemeWrapper(context, android.R.style.Theme_DeviceDefault));
        int scale = DashboardSpec.initialScalePercent(widthPx, context.getResources().getDisplayMetrics().density);
        if (scale > 0) web.setInitialScale(scale);
        web.setBackgroundColor(Color.BLACK);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setSupportMultipleWindows(false);
        s.setJavaScriptCanOpenWindowsAutomatically(false);
        Bridge bridge = new Bridge(web, controller, spec);
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                // Main-frame navigation off the Home Assistant origin is refused.
                if (!request.isForMainFrame()) return false;
                boolean ok = spec.sameOrigin(request.getUrl().toString());
                if (!ok) Log.i(TAG, "dashboard: blocked navigation off the Home Assistant origin");
                return !ok;
            }

            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                bridge.pageUrl = url;
            }

            @Override
            public void doUpdateVisitedHistory(WebView view, String url, boolean isReload) {
                bridge.pageUrl = url;
            }
        });
        web.addJavascriptInterface(bridge, "externalApp");
        return web;
    }

    /**
     * Where Home Assistant's dashboard title bar ends, in CSS px (0 if not
     * found): the bottom of hui-root's ".header", found through the nested
     * shadow roots. Only read, never changed, so a future frontend that moves
     * things just means the bar stays visible.
     */
    private static final String MEASURE_HEADER_JS =
        "(function(){function f(n,d){if(!n||d>16)return null;var a=n.querySelectorAll('*');"
        + "for(var i=0;i<a.length;i++){var e=a[i];if(e.tagName==='HUI-ROOT')return e;"
        + "if(e.shadowRoot){var r=f(e.shadowRoot,d+1);if(r)return r;}}return null}"
        + "var h=f(document,0);if(!h||!h.shadowRoot)return 0;var x=h.shadowRoot.querySelector('.header');"
        + "if(!x)return 0;var b=x.getBoundingClientRect();return (b.top<=1&&b.height>0)?b.bottom:0})()";

    /**
     * Hide the title bar by moving the page up behind the top edge of its
     * frame by the bar's height (main thread; {@code done} runs either way).
     * The WebView's parent must clip its children (FrameLayout does).
     */
    public static void hideTitleBar(WebView web, Runnable done) {
        try {
            web.evaluateJavascript(MEASURE_HEADER_JS, value -> {
                try {
                    float css = Float.parseFloat(value == null ? "0" : value.replace("\"", ""));
                    int px = Math.round(css * web.getScale());
                    android.view.View parent = (android.view.View) web.getParent();
                    if (px > 0 && parent != null && parent.getHeight() > 0) {
                        android.widget.FrameLayout.LayoutParams lp = new android.widget.FrameLayout.LayoutParams(
                            android.widget.FrameLayout.LayoutParams.MATCH_PARENT, parent.getHeight() + px);
                        lp.topMargin = -px;
                        web.setLayoutParams(lp);
                        Log.i(TAG, "dashboard: title bar hidden (" + px + " px)");
                    } else {
                        Log.i(TAG, "dashboard: title bar not found; left visible");
                    }
                } catch (Exception e) {
                    Log.w(TAG, "dashboard: title bar measure failed: " + e);
                }
                if (done != null) done.run();
            });
        } catch (Exception e) {
            if (done != null) done.run();
        }
    }

    /** The frontend's external-app interface (external_auth.ts / external_messaging.ts). */
    static final class Bridge {
        private final WebView web;
        private final DashboardController controller;
        private final DashboardSpec spec;
        private final Handler main = new Handler(Looper.getMainLooper());
        volatile String pageUrl;
        private int commandId = 1000;

        Bridge(WebView web, DashboardController controller, DashboardSpec spec) {
            this.web = web;
            this.controller = controller;
            this.spec = spec;
        }

        private boolean onOrigin() {
            String u = pageUrl;
            return u != null && spec.sameOrigin(u);
        }

        /** {"callback": "externalAuthSetToken", "force"?: true} -> callback(true, {access_token, expires_in}). */
        @JavascriptInterface
        public void getExternalAuth(String payload) {
            String cb = DashboardSpec.callback(payload, DashboardSpec.CALLBACK_SET_TOKEN);
            if (cb == null) return;
            if (!onOrigin()) {
                eval("window." + cb + "(false, \"wrong_origin\");");
                return;
            }
            boolean force = DashboardSpec.force(payload);
            // Waiting for Home Assistant can take a while; never block the bridge thread.
            new Thread(() -> {
                TokenBox.Token t = controller.tokenFor(force, TOKEN_WAIT_MS);
                if (t == null) {
                    Log.w(TAG, "dashboard: no token from Home Assistant");
                    eval("window." + cb + "(false, \"no_token\");");
                    return;
                }
                JSONObject o = new JSONObject();
                try {
                    o.put("access_token", t.value);
                    o.put("expires_in", t.expiresInS(System.currentTimeMillis()));
                } catch (Exception ignored) {
                }
                eval("window." + cb + "(true, " + o + ");");
            }, "dashboard-token").start();
        }

        /** The user logged out in the frontend: forget the token and close. */
        @JavascriptInterface
        public void revokeExternalAuth(String payload) {
            String cb = DashboardSpec.callback(payload, DashboardSpec.CALLBACK_REVOKE_TOKEN);
            if (cb == null) return;
            eval("window." + cb + "(true, {});");
            controller.close();
        }

        /** Messages from the frontend. config/get must be answered, or the frontend never connects. */
        @JavascriptInterface
        public void externalBus(String message) {
            if (!onOrigin()) return;
            JSONObject msg;
            try {
                msg = new JSONObject(message);
            } catch (Exception e) {
                return;
            }
            String type = msg.optString("type", "");
            if (!type.equals("haptic")) Log.i(TAG, "dashboard: frontend " + type);
            if (type.equals("config/get")) {
                JSONObject result = new JSONObject();
                JSONObject reply = new JSONObject();
                try {
                    result.put("hasSettingsScreen", false);
                    result.put("hasSidebar", false);
                    result.put("canWriteTag", false);
                    result.put("hasExoPlayer", false);
                    result.put("canCommissionMatter", false);
                    result.put("canImportThreadCredentials", false);
                    result.put("hasAssist", false);
                    result.put("hasBarCodeScanner", 0);
                    result.put("canSetupImprov", false);
                    result.put("appVersion", "HA TV Agent " + com.braviafanboy.braviaagent.AgentService.VERSION);
                    reply.put("id", msg.optInt("id"));
                    reply.put("type", "result");
                    reply.put("success", true);
                    reply.put("result", result);
                } catch (Exception ignored) {
                }
                eval("window.externalBus(" + reply + ");");
            } else if (type.equals("frontend/loaded")) {
                controller.onFrontendLoaded(spec);
                // Always: Home Assistant's kiosk mode hides its sidebar (no
                // wandering into Map, History, Settings on the TV) and editing
                // controls. The "kiosk" option is about the title bar only.
                sendCommand("kiosk_mode/set", "{\"enable\":true}");
            }
            // Everything else (connection-status, haptic, theme-update, ...) needs no reply.
        }

        private void sendCommand(String command, String payloadJson) {
            int id;
            synchronized (this) { id = ++commandId; }
            eval("window.externalBus({\"id\":" + id + ",\"type\":\"command\",\"command\":"
                + JSONObject.quote(command) + ",\"payload\":" + payloadJson + "});");
        }

        private void eval(String js) {
            main.post(() -> {
                try {
                    web.evaluateJavascript(js, null);
                } catch (Exception ignored) {
                    // WebView already destroyed.
                }
            });
        }
    }
}
