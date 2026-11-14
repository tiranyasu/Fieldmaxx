package com.fieldmaxx.app.platform

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import com.fieldmaxx.app.sync.LiveSocket
import com.fieldmaxx.core.geo.LatLon
import com.fieldmaxx.core.geo.distanceMeters
import com.fieldmaxx.core.model.ClientMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

private const val MIN_MOVE_M = 5.0
private const val MAX_QUIET_MS = 15_000L

/** Whether [next] goes out: the first fix, a move of more than 5 m, or 15 s since the last send ([prev] = fix and send time). */
fun shouldSend(prev: Pair<Fix, Long>?, next: Fix, now: Long): Boolean {
    if (prev == null) return true
    val (last, sentAt) = prev
    return distanceMeters(LatLon(last.lat, last.lon), LatLon(next.lat, next.lon)) > MIN_MOVE_M || now - sentAt >= MAX_QUIET_MS
}

/**
 * Owns location and compass for the whole app and is the only sender of positions. Everything runs only while
 * [lifecycle] (the process lifecycle) is STARTED: ON_START starts location and compass and sends the position; ON_STOP
 * sends [ClientMessage.Paused] once and stops both, so no request outlives the screen.
 *
 * The socket drops what it can't send, so: a Paused that found it disconnected is sent when it next connects (unless the
 * app came back first), and a (re)connect while on screen sends the latest fix at once.
 */
class ForegroundTracker(
    private val lifecycle: Lifecycle,
    private val location: LocationSource,
    private val compass: CompassSource,
    private val live: LiveSocket,
    private val now: () -> Long,
    private val scope: CoroutineScope,
) {
    private val lock = Any()
    private var onScreen = false
    private var pendingPaused = false
    private var lastSent: Pair<Fix, Long>? = null
    private var watching: Job? = null

    init {
        // Always watching the connection: a Paused dropped at ON_STOP needs the reconnect after the screen is gone.
        scope.launch { live.connected.collect { if (it) onConnected() } }
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) = this@ForegroundTracker.onStart()
            override fun onStop(owner: LifecycleOwner) = this@ForegroundTracker.onStop()
        })
    }

    /** Task 24 calls this after the permission answer: location starts if the app is on screen. */
    fun permissionChanged() {
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) location.start()
    }

    private fun onStart() = synchronized(lock) {
        onScreen = true
        pendingPaused = false // the fresh position supersedes a Paused that never got out
        location.start()
        compass.start()
        watching?.cancel()
        watching = scope.launch { location.fixes.collect { it?.let(::onFix) } }
        sendLatest()
    }

    private fun onStop() = synchronized(lock) {
        onScreen = false
        watching?.cancel()
        watching = null
        location.stop()
        compass.stop()
        lastSent = null
        if (live.connected.value) live.send(ClientMessage.Paused) else pendingPaused = true
    }

    private fun onConnected() = synchronized(lock) {
        if (pendingPaused) {
            pendingPaused = false
            live.send(ClientMessage.Paused)
        } else if (onScreen) {
            sendLatest()
        }
    }

    private fun onFix(fix: Fix) = synchronized(lock) {
        if (onScreen && shouldSend(lastSent, fix, now())) send(fix)
    }

    private fun sendLatest() {
        location.fixes.value?.let(::send)
    }

    /** Dropped while disconnected (and then not counted as sent): the reconnect sends the latest fix. */
    private fun send(fix: Fix) {
        if (!live.connected.value) return
        live.send(ClientMessage.PositionUpdate(fix.lat, fix.lon, fix.accuracyM, fix.altitudeM))
        lastSent = fix to now()
    }
}
