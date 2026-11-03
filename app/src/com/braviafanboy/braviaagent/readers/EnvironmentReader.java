package com.braviafanboy.braviaagent.readers;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.SystemClock;

import com.braviafanboy.braviaagent.StateStore;

import org.json.JSONObject;

/** Ambient light (lux) and ambient temperature from the TV's sensors. */
public final class EnvironmentReader implements SensorEventListener {
    private static final long MIN_INTERVAL_MS = 1000;
    // Both sensors are on-change; their slowest rate is 1 Hz, so ask for
    // exactly that instead of the 5 Hz default we would only throttle.
    private static final int SAMPLING_US = 1000000;

    // The temperature sensor is on-change with 0.5 °C steps and the HAL sends
    // no initial value on subscription, so after a restart it stays unknown
    // until the room changes by half a degree; seed from the last value seen.
    private static final String PREFS = "environment";
    private static final long PERSIST_MS = 10 * 60000;
    private long lastPersisted;
    private final android.content.SharedPreferences prefs;
    private final SensorManager manager;
    private final StateStore store;
    private final Sensor light;
    private final Sensor temperature;
    private Float lux;
    private Float celsius;
    private long lastPublished;

    public EnvironmentReader(Context context, StateStore store) {
        this.store = store;
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (prefs.contains("temperature_c")) celsius = prefs.getFloat("temperature_c", 0f);
        manager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
        light = manager.getDefaultSensor(Sensor.TYPE_LIGHT);
        temperature = manager.getDefaultSensor(Sensor.TYPE_AMBIENT_TEMPERATURE);
    }

    private final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());

    public void start() {
        if (light != null) manager.registerListener(this, light, SAMPLING_US);
        if (temperature != null) {
            if (celsius == null) {
                // No value known: the HAL sends no initial event, but a rate
                // change on the subscription can make it emit the current one.
                // Ask fastest for a moment, then settle at 1 Hz.
                manager.registerListener(this, temperature, SensorManager.SENSOR_DELAY_FASTEST);
                handler.postDelayed(() -> {
                    manager.unregisterListener(this, temperature);
                    manager.registerListener(this, temperature, SAMPLING_US);
                }, 2000);
            } else {
                manager.registerListener(this, temperature, SAMPLING_US);
            }
        }
        if (light == null && temperature == null) store.update("environment", null);
        else if (celsius != null) publish();
    }

    public void stop() {
        handler.removeCallbacksAndMessages(null);
        manager.unregisterListener(this);
        persist();
    }

    private void persist() {
        if (celsius == null) return;
        lastPersisted = SystemClock.elapsedRealtime();
        prefs.edit().putFloat("temperature_c", celsius).apply();
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() == Sensor.TYPE_LIGHT) lux = event.values[0];
        else if (event.sensor.getType() == Sensor.TYPE_AMBIENT_TEMPERATURE) {
            celsius = event.values[0];
            // Persist rarely: a write per event would be constant disk I/O.
            if (SystemClock.elapsedRealtime() - lastPersisted > PERSIST_MS) persist();
        }
        long now = SystemClock.elapsedRealtime();
        if (now - lastPublished < MIN_INTERVAL_MS) return;
        lastPublished = now;
        publish();
    }

    private void publish() {
        try {
            JSONObject o = new JSONObject();
            o.put("illuminance_lx", lux == null ? JSONObject.NULL : (double) Math.round(lux * 10) / 10);
            o.put("temperature_c", celsius == null ? JSONObject.NULL : (double) Math.round(celsius * 10) / 10);
            store.update("environment", o);
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }
}
