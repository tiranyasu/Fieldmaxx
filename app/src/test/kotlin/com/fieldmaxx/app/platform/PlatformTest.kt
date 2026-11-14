package com.fieldmaxx.app.platform

import android.view.Surface
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.testing.TestLifecycleOwner
import com.fieldmaxx.app.sync.LiveSocket
import com.fieldmaxx.core.model.ClientMessage
import com.fieldmaxx.core.model.ServerMessage
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

private fun fix(lat: Double = 39.636130, lon: Double = -119.691104, at: Long = 0) = Fix(lat, lon, 4.0, 1350.0, at)

/** About [m] metres north of the fixture point (1 degree of latitude is ~111,195 m). */
private fun northOf(base: Fix, m: Double, at: Long = 0) = base.copy(lat = base.lat + m / 111_195.0, at = at)

private class FakeLocation : LocationSource {
    private val state = MutableStateFlow<Fix?>(null)
    override val fixes: StateFlow<Fix?> = state
    var running = false
    var starts = 0
    override fun start() { running = true; starts++ }
    override fun stop() { running = false; state.value = null }
    fun emit(f: Fix) { if (running) state.value = f }
}

private class FakeCompass : CompassSource {
    override val heading: StateFlow<Heading?> = MutableStateFlow(null)
    override val cameraHeading: StateFlow<Heading?> = MutableStateFlow(null)
    var running = false
    override fun start() { running = true }
    override fun stop() { running = false }
}

private class RecordingSocket(connected: Boolean = true) : LiveSocket {
    override val messages: SharedFlow<ServerMessage> = MutableSharedFlow()
    val state = MutableStateFlow(connected)
    override val connected: StateFlow<Boolean> = state
    override val signInRequired: SharedFlow<Unit> = MutableSharedFlow()
    val sent = mutableListOf<ClientMessage>()
    override fun connect() {}
    override fun close() {}
    override fun send(m: ClientMessage) { if (state.value) sent += m }
}

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class PlatformTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val owner = TestLifecycleOwner(Lifecycle.State.CREATED, dispatcher)
    private val location = FakeLocation()
    private val compass = FakeCompass()
    private val socket = RecordingSocket()
    private var clock = 1_000L
    private val tracker = ForegroundTracker(owner.lifecycle, location, compass, socket, { clock }, CoroutineScope(dispatcher))

    private fun positions() = socket.sent.filterIsInstance<ClientMessage.PositionUpdate>()

    // --- shouldSend ---

    @Test fun firstFixSent() {
        assertTrue(shouldSend(null, fix(), now = 0))
    }

    @Test fun fourMetresWithin15sNotSent() {
        val a = fix()
        assertFalse(shouldSend(a to 0L, northOf(a, 4.0), now = 14_999))
    }

    @Test fun sixMetresSent() {
        val a = fix()
        assertTrue(shouldSend(a to 0L, northOf(a, 6.0), now = 1_000))
    }

    @Test fun fifteenSecondsSendsWithoutMovement() {
        val a = fix()
        assertTrue(shouldSend(a to 0L, a, now = 15_000))
    }

    // --- compass ---
    // Each fixture is a physical pose: the device's x (right), y (top) and z (out of the screen) axes as world vectors
    // (east, north, up). The expected heading comes from the pose: the camera faces -z, the screen's top is y (x in ROTATION_90).

    private fun rotationVector(x: DoubleArray, y: DoubleArray, z: DoubleArray): FloatArray {
        // Rows of the device-to-world matrix are the world axes in device coordinates.
        val m = arrayOf(doubleArrayOf(x[0], y[0], z[0]), doubleArrayOf(x[1], y[1], z[1]), doubleArrayOf(x[2], y[2], z[2]))
        val t = m[0][0] + m[1][1] + m[2][2]
        val q = when {
            t > 0 -> { val k = 2 * sqrt(t + 1); doubleArrayOf((m[2][1] - m[1][2]) / k, (m[0][2] - m[2][0]) / k, (m[1][0] - m[0][1]) / k, k / 4) }
            m[0][0] >= m[1][1] && m[0][0] >= m[2][2] -> { val k = 2 * sqrt(1 + m[0][0] - m[1][1] - m[2][2]); doubleArrayOf(k / 4, (m[0][1] + m[1][0]) / k, (m[0][2] + m[2][0]) / k, (m[2][1] - m[1][2]) / k) }
            m[1][1] >= m[2][2] -> { val k = 2 * sqrt(1 + m[1][1] - m[0][0] - m[2][2]); doubleArrayOf((m[0][1] + m[1][0]) / k, k / 4, (m[1][2] + m[2][1]) / k, (m[0][2] - m[2][0]) / k) }
            else -> { val k = 2 * sqrt(1 + m[2][2] - m[0][0] - m[1][1]); doubleArrayOf((m[0][2] + m[2][0]) / k, (m[1][2] + m[2][1]) / k, k / 4, (m[1][0] - m[0][1]) / k) }
        }
        return floatArrayOf(q[0].toFloat(), q[1].toFloat(), q[2].toFloat(), q[3].toFloat())
    }

    private fun v(e: Double, n: Double, u: Double) = doubleArrayOf(e, n, u)

    private val s45 = sin(Math.PI / 4)

    /** Compass degrees wrap: 359.99 is 0. */
    private fun assertDeg(expected: Double, actual: Double) {
        val d = abs(((actual - expected) % 360.0 + 540.0) % 360.0 - 180.0)
        assertTrue(d < 1.0, "expected $expected but was $actual")
    }

    // --- heading: the top of the screen (the map) ---

    @Test fun azimuthNorthPortrait() {
        // Flat, screen to the sky, top pointing north.
        assertDeg(0.0, azimuthFromRotationVector(rotationVector(v(1.0, 0.0, 0.0), v(0.0, 1.0, 0.0), v(0.0, 0.0, 1.0)), Surface.ROTATION_0))
        // Flat, top pointing east (x then points south).
        assertDeg(90.0, azimuthFromRotationVector(rotationVector(v(0.0, -1.0, 0.0), v(1.0, 0.0, 0.0), v(0.0, 0.0, 1.0)), Surface.ROTATION_0))
    }

    @Test fun azimuthRemappedForLandscape() {
        // Flat; the screen's top is the device's +x in ROTATION_90, -y in ROTATION_180 and -x in ROTATION_270. Each pose
        // turns that axis to north.
        assertDeg(0.0, azimuthFromRotationVector(rotationVector(v(0.0, 1.0, 0.0), v(-1.0, 0.0, 0.0), v(0.0, 0.0, 1.0)), Surface.ROTATION_90))
        assertDeg(0.0, azimuthFromRotationVector(rotationVector(v(-1.0, 0.0, 0.0), v(0.0, -1.0, 0.0), v(0.0, 0.0, 1.0)), Surface.ROTATION_180))
        assertDeg(0.0, azimuthFromRotationVector(rotationVector(v(0.0, -1.0, 0.0), v(1.0, 0.0, 0.0), v(0.0, 0.0, 1.0)), Surface.ROTATION_270))
    }

    // --- cameraHeading: where the back camera (-z) points (stamp, EXIF) ---

    private val uprightCameraSouth = rotationVector(v(-1.0, 0.0, 0.0), v(0.0, 0.0, 1.0), v(0.0, 1.0, 0.0))

    @Test fun cameraUprightPortraitSouth() {
        val r = cameraAzimuth(uprightCameraSouth, Surface.ROTATION_0, previouslyFallback = false)
        assertDeg(180.0, r.deg)
        assertFalse(r.fallback)
    }

    @Test fun cameraTippedPastVerticalStillSouth() {
        val tilt = Math.toRadians(10.0)
        val pose = rotationVector(v(-1.0, 0.0, 0.0), v(0.0, sin(tilt), cos(tilt)), v(0.0, cos(tilt), -sin(tilt)))
        assertDeg(180.0, cameraAzimuth(pose, Surface.ROTATION_0, false).deg)
    }

    @Test fun cameraUprightLandscapeWest() {
        // Upright landscape: the device's +x is up, the screen faces east, so the camera faces west.
        val pose = rotationVector(v(0.0, 0.0, 1.0), v(0.0, -1.0, 0.0), v(1.0, 0.0, 0.0))
        assertDeg(270.0, cameraAzimuth(pose, Surface.ROTATION_90, false).deg)
        // Auto-rotate locked: the display stays ROTATION_0 and the camera still faces west.
        assertDeg(270.0, cameraAzimuth(pose, Surface.ROTATION_0, false).deg)
    }

    @Test fun cameraFortyFiveDownAndRolledKeepsItsOwnDirection() {
        // Camera 45 degrees down toward the south (screen normal back toward the user and up), then rolled 20 degrees
        // about the camera axis: the camera's horizontal direction is still south, the screen's top is not.
        val c = s45
        val x = v(-1.0, 0.0, 0.0)
        val y = v(0.0, -c, c)
        val z = v(0.0, c, c)
        val roll = Math.toRadians(20.0)
        val xr = v(x[0] * cos(roll) + y[0] * sin(roll), x[1] * cos(roll) + y[1] * sin(roll), x[2] * cos(roll) + y[2] * sin(roll))
        val yr = v(-x[0] * sin(roll) + y[0] * cos(roll), -x[1] * sin(roll) + y[1] * cos(roll), -x[2] * sin(roll) + y[2] * cos(roll))
        val pose = rotationVector(xr, yr, z)
        assertDeg(180.0, cameraAzimuth(pose, Surface.ROTATION_0, false).deg)
    }

    @Test fun cameraStraightDownFallsBackToTheScreenTop() {
        // Flat, screen to the sky, top pointing east: the camera points at the ground.
        val pose = rotationVector(v(0.0, -1.0, 0.0), v(1.0, 0.0, 0.0), v(0.0, 0.0, 1.0))
        val r = cameraAzimuth(pose, Surface.ROTATION_0, previouslyFallback = false)
        assertDeg(90.0, r.deg)
        assertTrue(r.fallback)
    }

    @Test fun cameraHysteresisBetween15And25Degrees() {
        // Camera 20 degrees off straight down, pointing south; the screen's top points north.
        val a = Math.toRadians(20.0)
        val pose = rotationVector(v(1.0, 0.0, 0.0), v(0.0, cos(a), -sin(a)), v(0.0, sin(a), cos(a)))
        val fromCamera = cameraAzimuth(pose, Surface.ROTATION_0, previouslyFallback = false)
        assertDeg(180.0, fromCamera.deg)
        assertFalse(fromCamera.fallback)
        val fromFallback = cameraAzimuth(pose, Surface.ROTATION_0, previouslyFallback = true)
        assertDeg(0.0, fromFallback.deg)
        assertTrue(fromFallback.fallback)
        // 10 degrees off straight down falls back whatever came before; 30 degrees leaves fallback.
        val near = Math.toRadians(10.0)
        assertTrue(cameraAzimuth(rotationVector(v(1.0, 0.0, 0.0), v(0.0, cos(near), -sin(near)), v(0.0, sin(near), cos(near))), Surface.ROTATION_0, false).fallback)
        val far = Math.toRadians(30.0)
        assertFalse(cameraAzimuth(rotationVector(v(1.0, 0.0, 0.0), v(0.0, cos(far), -sin(far)), v(0.0, sin(far), cos(far))), Surface.ROTATION_0, true).fallback)
    }

    // --- lifecycle ---

    @Test fun onStopSendsPausedAndStopsUpdates() {
        owner.currentState = Lifecycle.State.STARTED
        assertTrue(location.running && compass.running)
        location.emit(fix())
        assertEquals(1, positions().size)

        owner.currentState = Lifecycle.State.CREATED

        assertEquals(ClientMessage.Paused, socket.sent.last())
        assertEquals(1, socket.sent.count { it == ClientMessage.Paused })
        assertFalse(location.running)
        assertFalse(compass.running)
    }

    @Test fun noUpdatesWhileStopped() {
        owner.currentState = Lifecycle.State.STARTED
        owner.currentState = Lifecycle.State.CREATED
        socket.sent.clear()

        location.running = true // a stray late callback
        location.emit(fix())
        location.emit(northOf(fix(), 50.0))

        assertEquals(emptyList(), socket.sent)
    }

    @Test fun movesAndQuietTimeDriveSendsWhileStarted() {
        owner.currentState = Lifecycle.State.STARTED
        val a = fix()
        location.emit(a)
        clock += 2_000
        location.emit(northOf(a, 3.0)) // too close, too soon
        assertEquals(1, positions().size)
        clock += 2_000
        location.emit(northOf(a, 9.0))
        assertEquals(2, positions().size)
        assertEquals(ClientMessage.PositionUpdate(northOf(a, 9.0).lat, a.lon, 4.0, 1350.0), positions().last())
        clock += 15_000 // same spot, 15 s after the last send: the heartbeat goes out
        location.emit(northOf(a, 9.0).copy(at = 5))
        assertEquals(3, positions().size)
    }

    // --- R28 ---

    @Test fun pausedIsSentAfterReconnectWhenDroppedAtStop() {
        owner.currentState = Lifecycle.State.STARTED
        socket.state.value = false
        owner.currentState = Lifecycle.State.CREATED
        assertEquals(emptyList(), socket.sent.filter { it == ClientMessage.Paused })

        socket.state.value = true

        assertEquals(listOf<ClientMessage>(ClientMessage.Paused), socket.sent.filter { it == ClientMessage.Paused })
    }

    @Test fun pausedNotSentIfStartCameFirst() {
        owner.currentState = Lifecycle.State.STARTED
        socket.state.value = false
        owner.currentState = Lifecycle.State.CREATED
        owner.currentState = Lifecycle.State.STARTED

        socket.state.value = true

        assertEquals(emptyList(), socket.sent.filter { it == ClientMessage.Paused })
    }

    @Test fun latestFixSentOnReconnect() {
        owner.currentState = Lifecycle.State.STARTED
        socket.state.value = false
        location.emit(fix(at = 7)) // dropped: the socket is down
        assertEquals(0, positions().size)

        socket.state.value = true

        assertEquals(1, positions().size)
    }

    @Test fun reconnectSendRestartsTheFifteenSecondClock() {
        owner.currentState = Lifecycle.State.STARTED
        val a = fix()
        location.emit(a)
        clock += 10_000
        socket.state.value = false
        socket.state.value = true // latest fix sent again at t = 11 s
        assertEquals(2, positions().size)
        clock += 10_000 // 10 s after the reconnect send, 20 s after the first
        location.emit(a.copy(at = 1)) // same spot
        assertEquals(2, positions().size)
    }

    // --- R29 ---

    @Test fun permissionChangedStartsLocationOnlyWhenStarted() {
        tracker.permissionChanged()
        assertFalse(location.running)

        owner.currentState = Lifecycle.State.STARTED
        location.running = false
        tracker.permissionChanged()
        assertTrue(location.running)

        owner.currentState = Lifecycle.State.CREATED
        tracker.permissionChanged()
        assertFalse(location.running)
    }
}
