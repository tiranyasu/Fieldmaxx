package com.fieldmaxx.app.platform

import android.view.Surface
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.testing.TestLifecycleOwner
import com.fieldmaxx.app.sync.LiveSocket
import com.fieldmaxx.core.model.ClientMessage
import com.fieldmaxx.core.model.ServerMessage
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

    @Test fun azimuthNorthPortrait() {
        // Identity rotation: the phone lies flat with its top toward north.
        assertEquals(0.0, azimuthFromRotationVector(floatArrayOf(0f, 0f, 0f, 1f), Surface.ROTATION_0), 0.01)
        // Turned a quarter clockwise (seen from above), the top points east.
        val s = Math.sin(Math.PI / 4).toFloat()
        assertEquals(90.0, azimuthFromRotationVector(floatArrayOf(0f, 0f, -s, s), Surface.ROTATION_0), 0.01)
    }

    @Test fun azimuthRemappedForLandscape() {
        // The same flat phone, top to north, east on its right side. ROTATION_90 is the phone turned counter-clockwise (top
        // on the left), so the screen's up is the phone's right side: east. ROTATION_270 is the reverse: west. 180: south.
        val north = floatArrayOf(0f, 0f, 0f, 1f)
        assertEquals(90.0, azimuthFromRotationVector(north, Surface.ROTATION_90), 0.01)
        assertEquals(270.0, azimuthFromRotationVector(north, Surface.ROTATION_270), 0.01)
        assertEquals(180.0, azimuthFromRotationVector(north, Surface.ROTATION_180), 0.01)
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
