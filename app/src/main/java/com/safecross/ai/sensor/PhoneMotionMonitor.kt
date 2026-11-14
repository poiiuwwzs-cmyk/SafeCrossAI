package com.safecross.ai.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import com.safecross.ai.risk.PhoneSteadiness
import kotlin.math.sqrt

/**
 * Feeds gyroscope and linear-acceleration readings into [PhoneSteadiness].
 * Only motion sensors are used; they need no permission and nothing leaves the phone.
 */
class PhoneMotionMonitor(
    context: Context,
    private val steadiness: PhoneSteadiness
) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val gyroscope = sensorManager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val linearAcceleration = sensorManager?.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)

    /** False when the phone has neither sensor, so steadiness cannot be checked. */
    val isAvailable: Boolean get() = gyroscope != null || linearAcceleration != null

    fun start() {
        val manager = sensorManager ?: return
        gyroscope?.let { manager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        linearAcceleration?.let { manager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
    }

    fun stop() {
        sensorManager?.unregisterListener(this)
        steadiness.reset()
    }

    override fun onSensorChanged(event: SensorEvent) {
        val v = event.values
        if (v.size < 3) return
        val magnitude = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
        // Same clock as the one used when a frame is analysed.
        val now = SystemClock.elapsedRealtime()
        when (event.sensor.type) {
            Sensor.TYPE_GYROSCOPE -> steadiness.onRotation(magnitude, now)
            Sensor.TYPE_LINEAR_ACCELERATION -> steadiness.onLinearAcceleration(magnitude, now)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
