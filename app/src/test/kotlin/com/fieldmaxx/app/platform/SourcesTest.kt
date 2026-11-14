package com.fieldmaxx.app.platform

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorManager
import android.hardware.GeomagneticField
import androidx.test.core.app.ApplicationProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowSensor
import org.robolectric.shadows.ShadowSensorManager

private class FakeRequester : LocationRequester {
    var requests = 0
    var removes = 0
    var onFix: ((Fix) -> Unit)? = null
    var onFailure: (() -> Unit)? = null
    override fun request(onFix: (Fix) -> Unit, onFailure: () -> Unit) {
        requests++
        this.onFix = onFix
        this.onFailure = onFailure
    }
    override fun remove() { removes++ }
}

@RunWith(RobolectricTestRunner::class)
class SourcesTest {
    // --- FusedLocationSource (R29 / B1) ---

    private val requester = FakeRequester()
    private var granted = false
    private val location = FusedLocationSource(requester) { granted }

    @Test fun startWithoutPermissionRequestsNothingThenGrantStarts() {
        location.start()
        assertEquals(0, requester.requests)
        assertNull(location.fixes.value)

        granted = true
        location.start() // what ForegroundTracker.permissionChanged() does after the grant
        assertEquals(1, requester.requests)

        location.start() // idempotent while running
        assertEquals(1, requester.requests)
    }

    @Test fun asyncFailureLetsTheNextStartRetry() {
        granted = true
        location.start()
        requester.onFailure!!() // Play services refused (not available, permission revoked, ...)

        location.start()

        assertEquals(2, requester.requests)
    }

    @Test fun fixesFlowAndStopRemovesAndClears() {
        granted = true
        location.start()
        requester.onFix!!(Fix(1.0, 2.0, 3.0, null, 4))
        assertEquals(Fix(1.0, 2.0, 3.0, null, 4), location.fixes.value)

        location.stop()

        assertEquals(1, requester.removes)
        assertNull(location.fixes.value)
        location.stop()
        assertEquals(1, requester.removes)
    }

    // --- SensorCompassSource (B2 / R36) ---

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val sensors = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val shadow: ShadowSensorManager = shadowOf(sensors)
    private val sensor: Sensor = ShadowSensor.newInstance(Sensor.TYPE_ROTATION_VECTOR).also { shadow.addSensor(it) }
    private var known: Fix? = null
    private val compass = SensorCompassSource(context, { 123_456L }, { known })

    /** Phone flat, top to magnetic north: rotation vector (0, 0, 0, 1). */
    private fun deliver(accuracy: Int) {
        val e: SensorEvent = ShadowSensorManager.createSensorEvent(4)
        e.values[3] = 1f
        SensorEvent::class.java.getField("accuracy").setInt(e, accuracy)
        SensorEvent::class.java.getField("sensor").set(e, sensor)
        shadow.sendSensorEventToListeners(e, sensor)
    }

    @Test fun reliableComesFromEachEventsAccuracyAcrossStopAndStart() {
        compass.start()
        deliver(SensorManager.SENSOR_STATUS_ACCURACY_HIGH)
        assertTrue(compass.heading.value!!.reliable)

        compass.stop()
        assertNull(compass.heading.value)
        compass.start()
        deliver(SensorManager.SENSOR_STATUS_UNRELIABLE) // the new session starts at needs-calibration, with no accuracy callback

        assertFalse(compass.heading.value!!.reliable)
        assertFalse(compass.cameraHeading.value!!.reliable)
    }

    @Test fun headingsStayMagneticWithoutAFix() {
        compass.start()
        deliver(SensorManager.SENSOR_STATUS_ACCURACY_HIGH)

        val h = compass.heading.value!!
        assertEquals(0.0, h.deg, 0.01)
        assertFalse(h.trueNorth)
        assertFalse(compass.cameraHeading.value!!.trueNorth)
    }

    @Test fun headingsAreShiftedByTheDeclinationOfTheLatestFix() {
        val fix = Fix(39.636130, -119.691104, 4.0, 1350.0, at = 123_456)
        known = fix
        val declination = GeomagneticField(fix.lat.toFloat(), fix.lon.toFloat(), fix.altitudeM!!.toFloat(), 123_456L).declination.toDouble()
        assertTrue(declination > 8.0 && declination < 18.0, "Nevada's declination is about 12 E, was $declination")
        compass.start()
        deliver(SensorManager.SENSOR_STATUS_ACCURACY_HIGH)

        val h = assertNotNull(compass.heading.value)
        assertTrue(h.trueNorth)
        assertEquals((declination + 360.0) % 360.0, h.deg, 0.01)
        // flat phone: the camera points at the ground, so the camera heading falls back to the same screen-top value
        assertEquals(h.deg, compass.cameraHeading.value!!.deg, 0.01)
        assertTrue(compass.cameraHeading.value!!.trueNorth)
    }
}
