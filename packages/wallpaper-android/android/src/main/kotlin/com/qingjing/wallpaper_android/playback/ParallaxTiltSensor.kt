package com.qingjing.wallpaper_android.playback

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler

/** PoC game/rotation-vector selection and first-event calibration, now with screen rotation and configured angle. */
internal class ParallaxTiltSensor(context: Context,private val maxAngleX: Float,private val maxAngleY: Float,private val rotation: () -> Int,private val listener: (Float,Float) -> Unit) : SensorEventListener {
    private val manager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val sensor = manager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR) ?: manager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val matrix = FloatArray(9); private val remapped = FloatArray(9); private val orientation = FloatArray(3)
    private var calibrated = false; private var basePitch = 0f; private var baseRoll = 0f; private var lastRotation = -1
    private var running = false
    fun start(handler: Handler): Boolean {
        calibrated = false; lastRotation = -1
        running = sensor != null && manager.registerListener(this,sensor,SensorManager.SENSOR_DELAY_GAME,handler)
        if (!running) listener(0f,0f)
        return running
    }
    fun stop() { running = false; manager.unregisterListener(this) }
    override fun onSensorChanged(event: SensorEvent) {
        if (!running || event.values.size<3 || event.values.any { !it.isFinite() }) return
        SensorManager.getRotationMatrixFromVector(matrix,event.values)
        SensorManager.remapCoordinateSystem(matrix,SensorManager.AXIS_X,SensorManager.AXIS_Z,remapped)
        SensorManager.getOrientation(remapped,orientation)
        val currentRotation = rotation()
        if (!calibrated || lastRotation != currentRotation) {
            basePitch = orientation[1]; baseRoll = orientation[2]; calibrated = true; lastRotation = currentRotation
        }
        val (x,y) = ParallaxMotion.tilt(orientation[2],orientation[1],baseRoll,basePitch,maxAngleX,maxAngleY,currentRotation)
        listener(x,y)
    }
    override fun onAccuracyChanged(sensor: Sensor?,accuracy: Int) { }
    companion object {
        fun available(context: Context): Boolean {
            val manager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager ?: return false
            return manager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR) != null || manager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) != null
        }
    }
}
