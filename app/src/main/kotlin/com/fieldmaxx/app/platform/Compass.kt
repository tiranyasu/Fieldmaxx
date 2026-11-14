package com.fieldmaxx.app.platform

import android.content.Context
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.display.DisplayManager
import android.view.Display
import android.view.Surface
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * [deg] is 0..360 clockwise from north; [trueNorth] says which north (false: magnetic, because no fix is known yet, so
 * EXIF gets GPSImgDirectionRef "M" instead of "T"). [reliable] is false while the sensor reports low accuracy.
 */
data class Heading(val deg: Double, val at: Long, val reliable: Boolean, val trueNorth: Boolean)

/**
 * The compass runs only while the app is on screen, like location; [stop] clears both headings.
 * [heading] is the top of the screen (the map); [cameraHeading] is where the back camera points (stamp and EXIF).
 */
interface CompassSource {
    val heading: StateFlow<Heading?>
    val cameraHeading: StateFlow<Heading?>
    fun start()
    fun stop()
}

private fun normalize(deg: Double) = ((deg % 360.0) + 360.0) % 360.0

private fun rotationMatrix(rv: FloatArray) = FloatArray(9).also { SensorManager.getRotationMatrixFromVector(it, rv) }

/**
 * Magnetic azimuth in degrees (0..360) of the top of the screen, from a TYPE_ROTATION_VECTOR reading. The axes are
 * remapped for [displayRotation] (a [Surface] ROTATION_* constant) so the heading follows the screen, not the phone's
 * natural top. Meant for a phone held flat (the map).
 */
fun azimuthFromRotationVector(rv: FloatArray, displayRotation: Int): Double {
    val remapped = FloatArray(9)
    val (x, y) = when (displayRotation) {
        Surface.ROTATION_90 -> SensorManager.AXIS_Y to SensorManager.AXIS_MINUS_X
        Surface.ROTATION_180 -> SensorManager.AXIS_MINUS_X to SensorManager.AXIS_MINUS_Y
        Surface.ROTATION_270 -> SensorManager.AXIS_MINUS_Y to SensorManager.AXIS_X
        else -> SensorManager.AXIS_X to SensorManager.AXIS_Y
    }
    SensorManager.remapCoordinateSystem(rotationMatrix(rv), x, y, remapped)
    val orientation = FloatArray(3)
    SensorManager.getOrientation(remapped, orientation)
    return normalize(Math.toDegrees(orientation[0].toDouble()))
}

/** The camera heading, and whether it fell back to the screen-top heading because the camera points (nearly) at the ground or sky. */
class CameraAzimuth(val deg: Double, val fallback: Boolean)

private const val FALLBACK_ENTER_DEG = 15.0
private const val FALLBACK_LEAVE_DEG = 25.0

/**
 * Magnetic azimuth of the back camera's axis (device -Z) projected on the horizontal; it doesn't depend on
 * [displayRotation]. Within 15 degrees of straight down or up the projection is ill-defined, so it falls back to the
 * screen-top heading; it returns to the camera rule only past 25 degrees ([previouslyFallback] gives the hysteresis).
 */
fun cameraAzimuth(rv: FloatArray, displayRotation: Int, previouslyFallback: Boolean): CameraAzimuth {
    val r = rotationMatrix(rv) // rows: east, north, up in device axes, so the device Z axis in the world is (r[2], r[5], r[8])
    val east = -r[2].toDouble()
    val north = -r[5].toDouble()
    val up = -r[8].toDouble()
    val offVertical = Math.toDegrees(atan2(hypot(east, north), abs(up)))
    val fallback = offVertical < (if (previouslyFallback) FALLBACK_LEAVE_DEG else FALLBACK_ENTER_DEG)
    val deg = if (fallback) azimuthFromRotationVector(rv, displayRotation) else normalize(Math.toDegrees(atan2(east, north)))
    return CameraAzimuth(deg, fallback)
}

/**
 * [fix] is the latest known position: with one, both headings are true north (the magnetic declination of that place is
 * added); without, they stay magnetic.
 */
class SensorCompassSource(
    context: Context,
    private val now: () -> Long = System::currentTimeMillis,
    private val fix: () -> Fix? = { null },
) : CompassSource {
    private val app = context.applicationContext
    private val sensors = app.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val displays = app.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    private val state = MutableStateFlow<Heading?>(null)
    private val cameraState = MutableStateFlow<Heading?>(null)
    override val heading: StateFlow<Heading?> = state.asStateFlow()
    override val cameraHeading: StateFlow<Heading?> = cameraState.asStateFlow()
    private var running = false
    private var cameraFallback = false

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val rotation = displays.getDisplay(Display.DEFAULT_DISPLAY)?.rotation ?: Surface.ROTATION_0
            val at = now()
            val position = fix()
            val declination = position?.let { declination(it, at) } ?: 0.0
            val reliable = event.accuracy >= SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM // per event: the sensor's callback fires only on change
            val camera = cameraAzimuth(event.values, rotation, cameraFallback)
            cameraFallback = camera.fallback
            state.value = Heading(normalize(azimuthFromRotationVector(event.values, rotation) + declination), at, reliable, position != null)
            cameraState.value = Heading(normalize(camera.deg + declination), at, reliable, position != null)
        }

        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
    }

    private fun declination(p: Fix, at: Long): Double =
        GeomagneticField(p.lat.toFloat(), p.lon.toFloat(), (p.altitudeM ?: 0.0).toFloat(), at).declination.toDouble()

    @Synchronized
    override fun start() {
        if (running) return
        val sensor = sensors.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) ?: return // no compass on this phone: headings stay null
        cameraFallback = false
        running = sensors.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
    }

    @Synchronized
    override fun stop() {
        if (running) sensors.unregisterListener(listener)
        running = false
        state.value = null
        cameraState.value = null
    }
}
