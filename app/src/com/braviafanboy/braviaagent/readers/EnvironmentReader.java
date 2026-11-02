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

    private final SensorManager manager;
    private final StateStore store;
    private final Sensor light;
    private final Sensor temperature;
    private Float lux;
    private Float celsius;
    private long lastPublished;

    public EnvironmentReader(Context context, StateStore store) {
        this.store = store;
        manager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
        light = manager.getDefaultSensor(Sensor.TYPE_LIGHT);
        temperature = manager.getDefaultSensor(Sensor.TYPE_AMBIENT_TEMPERATURE);
    }

    public void start() {
        if (light != null) manager.registerListener(this, light, SensorManager.SENSOR_DELAY_NORMAL);
        if (temperature != null) manager.registerListener(this, temperature, SensorManager.SENSOR_DELAY_NORMAL);
        if (light == null && temperature == null) store.update("environment", null);
    }

    public void stop() {
        manager.unregisterListener(this);
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() == Sensor.TYPE_LIGHT) lux = event.values[0];
        else if (event.sensor.getType() == Sensor.TYPE_AMBIENT_TEMPERATURE) celsius = event.values[0];
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
