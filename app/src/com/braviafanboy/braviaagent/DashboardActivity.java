package com.braviafanboy.braviaagent;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.webkit.WebView;

import com.braviafanboy.braviaagent.dashboard.DashboardController;
import com.braviafanboy.braviaagent.dashboard.DashboardSpec;
import com.braviafanboy.braviaagent.dashboard.DashboardWeb;

/**
 * A Home Assistant dashboard full screen. The remote's arrows move between
 * controls (mapped to Tab / Shift+Tab, which the frontend's web components
 * follow reliably), OK presses. Back steps back inside Home Assistant first
 * (closes a card's dialog, leaves a subview: the frontend keeps these in the
 * page history); with nothing left to go back to it asks before closing
 * ("Stay" focused, so a stray OK or Back keeps the dashboard). Closes itself on the controller's timeout or when Home
 * Assistant closes it.
 */
public final class DashboardActivity extends Activity {
    private DashboardController controller;
    private WebView web;
    private DashboardSpec shown;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        controller = DashboardController.get();
        if (controller == null || controller.current() == null) {
            finishAndRemoveTask();
            return;
        }
        controller.attach(this);
        load(controller.current());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        DashboardSpec next = controller == null ? null : controller.current();
        if (next == null) {
            finishAndRemoveTask();
            return;
        }
        if (next != shown) load(next);
    }

    private void load(DashboardSpec spec) {
        if (web != null) web.destroy();
        shown = spec;
        web = DashboardWeb.create(this, controller, spec);
        web.setFocusable(true);
        web.setFocusableInTouchMode(true);
        setContentView(web);
        hideSystemBars();
        web.requestFocus(View.FOCUS_DOWN);
        web.loadUrl(spec.url);
    }

    private void hideSystemBars() {
        try {
            getWindow().getInsetsController().hide(android.view.WindowInsets.Type.systemBars());
        } catch (Exception ignored) {
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int code = e.getKeyCode();
        if (code == KeyEvent.KEYCODE_BACK) {
            if (e.getAction() == KeyEvent.ACTION_UP) {
                if (web != null && web.canGoBack()) web.goBack();
                else confirmClose();
            }
            return true;
        }
        if (web != null) {
            boolean forward = code == KeyEvent.KEYCODE_DPAD_DOWN || code == KeyEvent.KEYCODE_DPAD_RIGHT;
            boolean backward = code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_LEFT;
            if (forward || backward) {
                KeyEvent tab = new KeyEvent(e.getDownTime(), e.getEventTime(), e.getAction(),
                    KeyEvent.KEYCODE_TAB, e.getRepeatCount(), backward ? KeyEvent.META_SHIFT_ON : 0);
                return web.dispatchKeyEvent(tab);
            }
            if (code == KeyEvent.KEYCODE_DPAD_CENTER) {
                KeyEvent enter = new KeyEvent(e.getDownTime(), e.getEventTime(), e.getAction(),
                    KeyEvent.KEYCODE_ENTER, e.getRepeatCount(), 0);
                return web.dispatchKeyEvent(enter);
            }
        }
        return super.dispatchKeyEvent(e);
    }

    private android.app.AlertDialog confirm;

    /** Ask before closing; Stay is the default so an accidental press is harmless. */
    private void confirmClose() {
        if (confirm != null && confirm.isShowing()) return;
        confirm = new android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Close dashboard?")
            .setPositiveButton("Close", (d, w) -> finishAndRemoveTask())
            .setNegativeButton("Stay", null)
            .setCancelable(true)
            .create();
        confirm.setOnShowListener(d -> {
            android.widget.Button stay = confirm.getButton(android.content.DialogInterface.BUTTON_NEGATIVE);
            if (stay != null) stay.requestFocus();
        });
        confirm.show();
    }

    @Override
    protected void onStop() {
        super.onStop();
        // Home pressed, or another app came forward: the dashboard is done.
        if (!isFinishing()) finishAndRemoveTask();
    }

    @Override
    protected void onDestroy() {
        if (confirm != null) confirm.dismiss();
        if (controller != null) controller.detach(this);
        if (web != null) {
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }
}
