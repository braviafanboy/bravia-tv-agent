package com.braviafanboy.braviaagent.readers;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.Looper;
import android.view.Display;

import com.braviafanboy.braviaagent.StateStore;

import org.json.JSONArray;
import org.json.JSONObject;

/** Panel state, active mode and HDR support of the built-in display. */
public final class DisplayReader implements DisplayManager.DisplayListener {
    public interface PanelListener {
        void onPanelChanged(boolean on);
    }

    private final DisplayManager manager;
    private final StateStore store;
    private PanelListener panelListener;
    private Boolean lastOn;

    public void setPanelListener(PanelListener l) {
        panelListener = l;
    }

    public boolean isPanelOn() {
        Display d = manager.getDisplay(Display.DEFAULT_DISPLAY);
        return d != null && d.getState() == Display.STATE_ON;
    }

    public DisplayReader(Context context, StateStore store) {
        this.store = store;
        manager = (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
    }

    public void start() {
        manager.registerDisplayListener(this, new Handler(Looper.getMainLooper()));
        publish();
    }

    public void stop() {
        manager.unregisterDisplayListener(this);
    }

    private void publish() {
        Display d = manager.getDisplay(Display.DEFAULT_DISPLAY);
        if (d == null) {
            store.update("display", null);
            return;
        }
        try {
            JSONObject o = new JSONObject();
            int state = d.getState();
            boolean on = state == Display.STATE_ON;
            o.put("on", on);
            if (lastOn == null || lastOn != on) {
                lastOn = on;
                if (panelListener != null) panelListener.onPanelChanged(on);
            }
            o.put("state", stateName(state));
            Display.Mode mode = d.getMode();
            o.put("width", mode.getPhysicalWidth());
            o.put("height", mode.getPhysicalHeight());
            o.put("refresh_hz", (double) Math.round(mode.getRefreshRate() * 100) / 100);
            JSONArray hdr = new JSONArray();
            Display.HdrCapabilities caps = d.getHdrCapabilities();
            if (caps != null) for (int t : caps.getSupportedHdrTypes()) hdr.put(hdrName(t));
            o.put("hdr_supported", hdr);
            o.put("is_hdr", d.isHdr());
            store.update("display", o);
        } catch (Exception ignored) {
        }
    }

    private static String stateName(int state) {
        switch (state) {
            case Display.STATE_OFF: return "off";
            case Display.STATE_ON: return "on";
            case Display.STATE_DOZE: return "doze";
            case Display.STATE_DOZE_SUSPEND: return "doze_suspend";
            case Display.STATE_VR: return "vr";
            case Display.STATE_ON_SUSPEND: return "on_suspend";
            default: return "unknown";
        }
    }

    private static String hdrName(int type) {
        switch (type) {
            case Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION: return "dolby_vision";
            case Display.HdrCapabilities.HDR_TYPE_HDR10: return "hdr10";
            case Display.HdrCapabilities.HDR_TYPE_HLG: return "hlg";
            case Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS: return "hdr10_plus";
            default: return "type_" + type;
        }
    }

    @Override public void onDisplayAdded(int displayId) { publish(); }
    @Override public void onDisplayRemoved(int displayId) { publish(); }
    @Override public void onDisplayChanged(int displayId) { publish(); }
}
