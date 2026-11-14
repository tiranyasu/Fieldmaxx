package com.fieldmaxx.app.platform

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.Surface
import android.view.WindowManager
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** [deg] is 0..360 clockwise from magnetic north; [reliable] is false while the sensor reports low accuracy. */
data class Heading(val deg: Double, val at: Long, val reliable: Boolean)

/** The compass runs only while the app is on screen, like location. [stop] clears [heading]. */
interface CompassSource {
    val heading: StateFlow<Heading?>
    fun start()
    fun stop()
}

/**
 * The direction the phone points, in degrees (0..360) clockwise from magnetic north, from a TYPE_ROTATION_VECTOR reading.
 * Upright (the device Z axis within 45 degrees of horizontal) that is the back camera's axis, -Z, projected on the
 * horizontal; it doesn't depend on [displayRotation]. Flat it is the top of the screen, with the axes remapped for
 * [displayRotation] (a [Surface] ROTATION_* constant). The two rules agree near the switch.
 */
fun azimuthFromRotationVector(rv: FloatArray, displayRotation: Int): Double {
    val r = FloatArray(9)
    SensorManager.getRotationMatrixFromVector(r, rv)
    // Rows of r are east, north, up in device axes: r[8] is how far the device Z axis points up.
    if (abs(r[8]) < UPRIGHT_MAX_Z_UP) return (Math.toDegrees(atan2(-r[2].toDouble(), -r[5].toDouble())) + 360.0) % 360.0
    val remapped = FloatArray(9)
    val (x, y) = when (displayRotation) {
        Surface.ROTATION_90 -> SensorManager.AXIS_Y to SensorManager.AXIS_MINUS_X
        Surface.ROTATION_180 -> SensorManager.AXIS_MINUS_X to SensorManager.AXIS_MINUS_Y
        Surface.ROTATION_270 -> SensorManager.AXIS_MINUS_Y to SensorManager.AXIS_X
        else -> SensorManager.AXIS_X to SensorManager.AXIS_Y
    }
    SensorManager.remapCoordinateSystem(r, x, y, remapped)
    val orientation = FloatArray(3)
    SensorManager.getOrientation(remapped, orientation)
    return (Math.toDegrees(orientation[0].toDouble()) + 360.0) % 360.0
}

private val UPRIGHT_MAX_Z_UP = cos(Math.PI / 4)

class SensorCompassSource(context: Context, private val now: () -> Long = System::currentTimeMillis) : CompassSource {
    private val app = context.applicationContext
    private val sensors = app.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val state = MutableStateFlow<Heading?>(null)
    override val heading: StateFlow<Heading?> = state.asStateFlow()
    private var accuracy = SensorManager.SENSOR_STATUS_UNRELIABLE
    private var running = false

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            state.value = Heading(azimuthFromRotationVector(event.values, displayRotation()), now(), accuracy >= SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM)
        }

        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
            this@SensorCompassSource.accuracy = accuracy
        }
    }

    @Suppress("DEPRECATION") // Context.display needs a visual context; the window manager's default display works from the app context
    private fun displayRotation(): Int =
        (app.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.rotation

    @Synchronized
    override fun start() {
        if (running) return
        val sensor = sensors.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) ?: return // no compass on this phone: heading stays null
        running = sensors.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
    }

    @Synchronized
    override fun stop() {
        if (running) sensors.unregisterListener(listener)
        running = false
        state.value = null
    }
}
