package com.fieldmaxx.app.platform

import android.annotation.SuppressLint
import android.content.Context
import android.os.Looper
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** One GPS fix. [at] is epoch milliseconds. */
data class Fix(val lat: Double, val lon: Double, val accuracyM: Double, val altitudeM: Double?, val at: Long)

/**
 * The phone's position, collected only while the app is on screen: [ForegroundTracker] starts it at ON_START and stops it
 * at ON_STOP. [start] is idempotent and safe without the location permission (no crash, [fixes] stays null);
 * [stop] ends every request and clears [fixes], so nothing stale is shown or sent after the next start.
 */
interface LocationSource {
    val fixes: StateFlow<Fix?>
    fun start()
    fun stop()
}

/** Play services' fused provider: high accuracy, an update every 2 s. */
class FusedLocationSource(context: Context) : LocationSource {
    private val app = context.applicationContext
    private val client: FusedLocationProviderClient by lazy { LocationServices.getFusedLocationProviderClient(app) }
    private val state = MutableStateFlow<Fix?>(null)
    override val fixes: StateFlow<Fix?> = state.asStateFlow()

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val l = result.lastLocation ?: return
            state.value = Fix(
                lat = l.latitude,
                lon = l.longitude,
                accuracyM = if (l.hasAccuracy()) l.accuracy.toDouble() else Double.MAX_VALUE,
                altitudeM = if (l.hasAltitude()) l.altitude else null,
                at = l.time,
            )
        }
    }
    private var running = false

    @SuppressLint("MissingPermission") // a missing permission is caught: no fixes until Task 24's request is granted
    @Synchronized
    override fun start() {
        if (running) return
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, INTERVAL_MS).setMinUpdateIntervalMillis(INTERVAL_MS).build()
        try {
            client.requestLocationUpdates(request, callback, Looper.getMainLooper())
            running = true
        } catch (_: SecurityException) {
            // not granted yet; ForegroundTracker.permissionChanged() starts again after the grant
        }
    }

    @Synchronized
    override fun stop() {
        if (running) client.removeLocationUpdates(callback)
        running = false
        state.value = null
    }

    private companion object {
        const val INTERVAL_MS = 2_000L
    }
}
