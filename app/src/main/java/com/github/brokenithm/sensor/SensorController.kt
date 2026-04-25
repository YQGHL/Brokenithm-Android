package com.github.brokenithm.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import kotlin.math.abs

/**
 * Extracted from MainActivity lines 108-153, 606-619, 405-424.
 * Manages gyroscope / accelerometer sensors and computes air height.
 *
 * Byte-for-byte compatible: produces IDENTICAL [mCurrentAirHeight] values
 * as the original inline SensorEventListener.
 */
class SensorController(
    private val onAirHeightUpdate: (Int) -> Unit
) {
    private var sensorManager: SensorManager? = null
    private var gyroLowestBound = 0.8f
    private var gyroHighestBound = 1.35f
    private var accelThreshold = 2f
    private var airSource = 3
    private var simpleAir = false
    private val numOfAirBlock = 6

    private val listener = object : SensorEventListener {
        var current = 0
        var lastAcceleration = 0f

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
            return
        }

        override fun onSensorChanged(event: SensorEvent?) {
            event ?: return
            val threshold = accelThreshold
            when (event.sensor.type) {
                Sensor.TYPE_LINEAR_ACCELERATION -> {
                    if (airSource != 2)
                        return
                    if (lastAcceleration != 0f) {
                        val accelX = (lastAcceleration - event.values[0])
                        if (accelX >= threshold && current >= 0)
                            current--
                        else if (accelX <= -threshold && current <= 0)
                            current++
                        if (current > 0)
                            onAirHeightUpdate(0)
                        else if (current < 0)
                            onAirHeightUpdate(6)
                    }
                    lastAcceleration = event.values[0]
                }
                Sensor.TYPE_ROTATION_VECTOR, Sensor.TYPE_GAME_ROTATION_VECTOR -> {
                    if (airSource != 1)
                        return
                    val rotationVector = FloatArray(9)
                    SensorManager.getRotationMatrixFromVector(rotationVector, event.values)
                    val orientation = FloatArray(3)
                    SensorManager.getOrientation(rotationVector, orientation)
                    computeAirFromGyro(orientation[2])
                    return
                }
            }
        }
    }

    /**
     * Start listening to the appropriate sensor based on [airSource].
     */
    fun start(context: Context, airSource: Int) {
        this.airSource = airSource
        if (sensorManager == null)
            sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        when (airSource) {
            1 -> {
                val gyro = sensorManager?.getDefaultSensor(
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2)
                        Sensor.TYPE_GAME_ROTATION_VECTOR
                    else
                        Sensor.TYPE_ROTATION_VECTOR
                )
                sensorManager?.registerListener(listener, gyro, 10000)
            }
            2 -> {
                val accel = sensorManager?.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
                sensorManager?.registerListener(listener, accel, 10000)
            }
        }
    }

    /**
     * Stop listening to all sensors.
     */
    fun stop() {
        sensorManager?.unregisterListener(listener)
    }

    /**
     * Update threshold settings.
     */
    fun updateSettings(
        gyroLowest: Float,
        gyroHighest: Float,
        accelThreshold: Float,
        simpleAir: Boolean
    ) {
        this.gyroLowestBound = gyroLowest
        this.gyroHighestBound = gyroHighest
        this.accelThreshold = accelThreshold
        this.simpleAir = simpleAir
    }

    /**
     * Gyro → air height computation (extracted from mSensorCallback, lines 405-424).
     */
    private fun computeAirFromGyro(value: Float) {
        val lowest = gyroLowestBound
        val highest = gyroHighestBound
        val steps = (highest - lowest) / numOfAirBlock
        val current = abs(value)
        val airHeight = if (simpleAir) {
            when {
                current <= lowest -> 6
                else -> 0
            }
        } else {
            when {
                current <= lowest -> 6
                current <= highest -> {
                    ((highest - current) / steps).toInt()
                }
                else -> 0
            }
        }
        onAirHeightUpdate(airHeight)
    }
}
