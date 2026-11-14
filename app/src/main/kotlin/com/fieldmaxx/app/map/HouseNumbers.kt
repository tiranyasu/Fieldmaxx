package com.fieldmaxx.app.map

import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToLong
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/** The house-number overlay (spec §6.3) shows from this zoom in (R74). */
const val HOUSE_NUMBER_MIN_ZOOM = 17.0

/** The camera must rest this long before Overpass is asked (R74). */
const val HOUSE_NUMBER_DEBOUNCE_MS = 600L

const val OVERPASS_URL = "https://overpass-api.de/api/interpreter"

private const val TAG = "HouseNumbers"
private const val BACKOFF_FIRST_MS = 30_000L
private const val BACKOFF_MAX_MS = 300_000L

/** A south-west / north-east box in degrees. */
data class BBox(val s: Double, val w: Double, val n: Double, val e: Double)

/** The only thing ever sent to Overpass (R74): every address number in [b], ways as their centre point. */
fun overpassQuery(b: BBox): String {
    val box = "${b.s},${b.w},${b.n},${b.e}"
    return "[out:json][timeout:25];(node[\"addr:housenumber\"]($box);way[\"addr:housenumber\"]($box););out center;"
}

/** Overpass's answer as labels; an element without a number or without a position is skipped. Not JSON: [IOException]. */
fun parseHouseNumbers(json: String): List<HouseNumber> {
    val elements = try {
        (Json.parseToJsonElement(json).jsonObject["elements"] as? JsonArray) ?: JsonArray(emptyList())
    } catch (e: IllegalArgumentException) {
        throw IOException("Overpass sent something that isn't JSON", e)
    }
    return elements.mapNotNull { el ->
        val o = el as? JsonObject ?: return@mapNotNull null
        val text = ((o["tags"] as? JsonObject)?.get("addr:housenumber") as? JsonPrimitive)?.contentOrNull?.trim()
        if (text.isNullOrEmpty()) return@mapNotNull null
        val at = (o["center"] as? JsonObject) ?: o
        val lat = (at["lat"] as? JsonPrimitive)?.doubleOrNull
        val lon = (at["lon"] as? JsonPrimitive)?.doubleOrNull
        if (lat == null || lon == null) null else HouseNumber(lat, lon, text)
    }
}

/**
 * The box [view] shows on a screen [widthDp] by [heightDp] (MapLibre's 512 dp tiles), a little larger than the screen
 * so a short pan stays inside it.
 */
fun viewBBox(view: CameraView, widthDp: Int, heightDp: Int): BBox {
    val degPerDpLon = 360.0 / (512.0 * 2.0.pow(view.zoom))
    val degPerDpLat = degPerDpLon * cos(view.lat * PI / 180)
    val halfLon = widthDp * degPerDpLon * 0.6
    val halfLat = heightDp * degPerDpLat * 0.6
    return BBox(view.lat - halfLat, view.lon - halfLon, view.lat + halfLat, view.lon + halfLon)
}

/**
 * [b] grown outward to the 0.001 degree grid (about 110 m): south and west down, north and east up. What is asked, kept
 * and looked up is this box, so a kept answer always covers the whole view it is used for (R89).
 */
fun snapToGrid(b: BBox): BBox = BBox(gridDown(b.s), gridDown(b.w), gridUp(b.n), gridUp(b.e))

// A value already on the grid stays (39.634 * 1000 is 39634.00000000001 in floating point).
private fun gridDown(v: Double): Double = onGrid(v) ?: (floor(v * 1000) / 1000)
private fun gridUp(v: Double): Double = onGrid(v) ?: (ceil(v * 1000) / 1000)
private fun onGrid(v: Double): Double? = (v * 1000).let { if (abs(it - it.roundToLong()) < 1e-6) it.roundToLong() / 1000.0 else null }

/** The client for Overpass, built from the app's [signedIn] one: same connections, none of its interceptors (R74). */
fun plainClient(signedIn: OkHttpClient): OkHttpClient = signedIn.newBuilder().apply { interceptors().clear() }.build()

/** How long to stay away after the [failures]'th refusal in a row (0 = the first): 30 s, doubling, at most 5 min. */
fun backoffMs(failures: Int): Long = min(BACKOFF_FIRST_MS * 2.0.pow(min(failures, 20)).toLong(), BACKOFF_MAX_MS)

/**
 * Address numbers from OpenStreetMap through the public Overpass service. [client] must be a plain one: nothing of the
 * session may reach a third party (R74). Each view is snapped outward to the 0.001 degree grid and the answer kept per snapped box (R89). After HTTP 429 or 504 it
 * stays away for a while ([backoffMs]) and fails with [IOException]; so does any other failure, and the caller keeps
 * the labels it has.
 */
class HouseNumberLoader(
    private val client: OkHttpClient,
    private val userAgent: String,
    private val url: String = OVERPASS_URL,
    private val now: () -> Long = System::currentTimeMillis,
    private val log: (String) -> Unit = { Log.w(TAG, it) },
) {
    private val cache = ConcurrentHashMap<List<Long>, List<HouseNumber>>()
    private val lock = Any()
    private var failures = 0
    private var notBefore = 0L

    suspend fun load(view: BBox): List<HouseNumber> {
        val b = snapToGrid(view)
        val key = listOf(b.s, b.w, b.n, b.e).map { (it * 1000).roundToLong() }
        cache[key]?.let { return it }
        synchronized(lock) {
            if (now() < notBefore) throw IOException("Overpass asked us to slow down")
        }
        val request = Request.Builder().url(url).header("User-Agent", userAgent)
            .post(FormBody.Builder().add("data", overpassQuery(b)).build()).build()
        val body = try {
            client.newCall(request).await().use { r ->
                when {
                    r.code == 429 || r.code == 504 -> {
                        synchronized(lock) {
                            notBefore = now() + backoffMs(failures)
                            failures++
                        }
                        log("Overpass answered ${r.code}; backing off")
                        throw IOException("Overpass busy (${r.code})")
                    }
                    !r.isSuccessful -> throw IOException("Overpass answered ${r.code}")
                    else -> r.body?.string() ?: throw IOException("Overpass sent nothing")
                }
            }
        } catch (e: IOException) {
            log("No house numbers: ${e.message}")
            throw e
        }
        val numbers = parseHouseNumbers(body)
        synchronized(lock) { failures = 0 }
        cache[key] = numbers
        return numbers
    }
}

/** Runs the call without holding a thread, and cancels it when the caller gives up. */
private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (cont.isActive) cont.resumeWith(Result.failure(e))
        }

        override fun onResponse(call: Call, response: Response) {
            if (cont.isActive) cont.resumeWith(Result.success(response)) else response.close()
        }
    })
}

/** Whether the overlay is on (R75): off until the tech turns it on, then remembered in DataStore. */
class HouseNumberSetting(private val store: DataStore<Preferences>) {
    private val key = booleanPreferencesKey("house_numbers")

    val on: Flow<Boolean> = store.data.catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { it[key] ?: false }

    suspend fun set(value: Boolean) {
        try {
            store.edit { it[key] = value }
        } catch (e: IOException) {
            Log.w(TAG, "The overlay choice wasn't saved: ${e.message}")
        }
    }
}
