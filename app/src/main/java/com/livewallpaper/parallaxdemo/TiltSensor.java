package com.livewallpaper.parallaxdemo;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;

final class TiltSensor implements SensorEventListener {
    interface Listener {
        void onTilt(float x, float y);
    }

    // A small physical tilt should already reach most of the visual range.
    private static final float MAX_ANGLE_RAD = (float) Math.toRadians(9.0);

    private final SensorManager sensorManager;
    private final Sensor sensor;
    private final Listener listener;
    private final float[] rotationMatrix = new float[9];
    private final float[] remappedMatrix = new float[9];
    private final float[] orientation = new float[3];

    private boolean calibrated;
    private float basePitch;
    private float baseRoll;

    TiltSensor(Context context, Listener listener) {
        sensorManager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
        Sensor preferred = sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR);
        sensor = preferred != null
                ? preferred
                : sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
        this.listener = listener;
    }

    void start() {
        start(null);
    }

    void start(Handler handler) {
        calibrated = false;
        if (sensor == null) {
            listener.onTilt(0f, 0f);
            return;
        }
        if (handler == null) {
            sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME);
        } else {
            sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME, handler);
        }
    }

    void stop() {
        sensorManager.unregisterListener(this);
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values);
        SensorManager.remapCoordinateSystem(
                rotationMatrix,
                SensorManager.AXIS_X,
                SensorManager.AXIS_Z,
                remappedMatrix
        );
        SensorManager.getOrientation(remappedMatrix, orientation);

        float pitch = orientation[1];
        float roll = orientation[2];
        if (!calibrated) {
            basePitch = pitch;
            baseRoll = roll;
            calibrated = true;
        }

        float x = clamp(wrapAngle(roll - baseRoll) / MAX_ANGLE_RAD);
        float y = clamp(wrapAngle(pitch - basePitch) / MAX_ANGLE_RAD);
        listener.onTilt(x, y);
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }

    private static float wrapAngle(float angle) {
        while (angle > Math.PI) angle -= (float) (Math.PI * 2.0);
        while (angle < -Math.PI) angle += (float) (Math.PI * 2.0);
        return angle;
    }

    private static float clamp(float value) {
        return Math.max(-1f, Math.min(1f, value));
    }
}
