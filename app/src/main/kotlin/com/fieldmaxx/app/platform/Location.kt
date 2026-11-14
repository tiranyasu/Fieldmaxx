package com.fieldmaxx.app.platform

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Looper
import androidx.core.content.ContextCompat
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

/** The calls to the location provider, behind a seam so [FusedLocationSource]'s start/stop logic is testable. */
internal interface LocationRequester {
    /** Starts updates. [onFailure] is called, possibly later and on any thread, when the provider could not start them. */
    fun request(onFix: (Fix) -> Unit, onFailure: () -> Unit)
    fun remove()
}

/** Play services' fused provider: high accuracy, an update every 2 s. */
private class FusedRequester(context: Context) : LocationRequester {
    private val app = context.applicationContext
    private val client: FusedLocationProviderClient by lazy { LocationServices.getFusedLocationProviderClient(app) }
    private var callback: LocationCallback? = null

    @SuppressLint("MissingPermission") // FusedLocationSource checks the permission before it asks
    override fun request(onFix: (Fix) -> Unit, onFailure: () -> Unit) {
        val cb = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val l = result.lastLocation ?: return
                onFix(
                    Fix(
                        lat = l.latitude,
                        lon = l.longitude,
                        accuracyM = if (l.hasAccuracy()) l.accuracy.toDouble() else Double.MAX_VALUE,
                        altitudeM = if (l.hasAltitude()) l.altitude else null,
                        at = l.time,
                    ),
                )
            }
        }
        callback = cb
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, INTERVAL_MS).setMinUpdateIntervalMillis(INTERVAL_MS).build()
        try {
            client.requestLocationUpdates(request, cb, Looper.getMainLooper()).addOnFailureListener { onFailure() }
        } catch (_: SecurityException) {
            onFailure()
        }
    }

    override fun remove() {
        callback?.let { client.removeLocationUpdates(it) }
        callback = null
    }

    private companion object {
        const val INTERVAL_MS = 2_000L
    }
}

/**
 * Starts only with a location permission (play-services reports a missing permission later, through the request's task,
 * never to the caller), and a request that fails leaves the source stopped so the next [start] retries.
 */
class FusedLocationSource internal constructor(
    private val requester: LocationRequester,
    private val hasPermission: () -> Boolean,
) : LocationSource {
    constructor(context: Context) : this(FusedRequester(context), {
        val app = context.applicationContext
        ContextCompat.checkSelfPermission(app, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(app, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    })

    private val state = MutableStateFlow<Fix?>(null)
    override val fixes: StateFlow<Fix?> = state.asStateFlow()
    private var running = false
    private var session = 0

    @Synchronized
    override fun start() {
        if (running || !hasPermission()) return
        running = true
        val mine = ++session
        requester.request(
            onFix = { fix -> synchronized(this) { if (running && session == mine) state.value = fix } },
            onFailure = { synchronized(this) { if (session == mine) running = false } },
        )
    }

    @Synchronized
    override fun stop() {
        if (running) requester.remove()
        running = false
        session++
        state.value = null
    }
}
