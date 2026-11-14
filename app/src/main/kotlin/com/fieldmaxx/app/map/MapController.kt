package com.fieldmaxx.app.map

import com.fieldmaxx.app.platform.Fix
import com.fieldmaxx.core.geo.LatLon
import com.fieldmaxx.core.model.AppConfig
import com.fieldmaxx.core.model.CrewPosition
import com.fieldmaxx.core.model.Point
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** An OpenStreetMap address number to show at high zoom (Task 31). */
data class HouseNumber(val lat: Double, val lon: Double, val text: String)

/**
 * The map itself. MapLibre lives behind this so screens are testable (its native libraries don't load under
 * Robolectric): [MapLibreController] in the app, a fake in tests. Called on the main thread.
 *
 * Only [moveTo] and [fitAll] move the camera (R38). Everything else changes what is drawn, never where the map looks.
 */
interface MapController {
    /** The imagery and street-name tile sources. */
    fun setConfig(c: AppConfig)

    /** The markers to draw; [pendingPhotoPointIds] get a badge (photos not in OneDrive yet). */
    fun setPoints(points: List<Point>, pendingPhotoPointIds: Set<String>)

    /** The other techs' dots, aged against [now] ([crewDotState]). */
    fun setCrew(crew: List<CrewPosition>, now: Long)

    /** Your own position; null shows no dot. */
    fun setMyFix(f: Fix?)

    fun moveTo(v: CameraView)

    /** Shows every one of [points]. */
    fun fitAll(points: List<Point>)

    /**
     * Where the map looks, kept up to date as it moves ([CameraTracker]): the destination of a move asked for, at once,
     * and otherwise the map itself, while the user drags or it glides. Null until the map's first placement.
     */
    val camera: StateFlow<CameraView?>

    /** Ids of the markers the user taps. */
    val pointTaps: SharedFlow<String>

    /** The street-name overlay ("Aa"). */
    fun setLabelsVisible(v: Boolean)

    fun setHouseNumbers(items: List<HouseNumber>)

    /** Lets the user drag the point's marker; emits where it is, starting with where it was, until [endDrag]. */
    fun beginDrag(pointId: String): Flow<LatLon>

    fun endDrag()
}

/**
 * Keeps a map's [MapController.camera] from the map's own events, so it says where the map is (B1, R61). The MapLibre
 * adapter feeds it from MapLibre's listeners; the tests' fake feeds it the same events, so the screen is tested
 * against the adapter's real reporting.
 * - A move asked for ([asked]: moveTo, fitAll, a cluster tap) shows as its destination at once and stays until it
 *   [ended] or the user takes over, so a second zoom step builds on the first.
 * - Otherwise the camera follows the map as it moves ([moved], every frame) and where it comes to rest ([settled]):
 *   a zoom tap while the map still glides after a fling starts from where the map is.
 * - Nothing the map does counts before a move asked for has been carried out ([applied]): pans and pinches on the
 *   empty map a tech pokes at while the job loads neither show nor cancel the first placement (R61).
 */
class CameraTracker {
    private val view = MutableStateFlow<CameraView?>(null)
    val camera: StateFlow<CameraView?> = view.asStateFlow()
    private var placed = false
    private var heading: CameraView? = null

    /** A move to [destination] was asked for; it may wait for the map before it is [applied]. */
    @Synchronized
    fun asked(destination: CameraView) {
        heading = destination
        view.value = destination
    }

    /** The map has started the move asked for last. */
    @Synchronized
    fun applied() {
        placed = true
    }

    /** The user touched the map: once it has been placed, a move asked for gives way to the gesture. */
    @Synchronized
    fun gestureStarted() {
        if (placed) heading = null
    }

    /** The map is at [at] (a frame of a drag, a glide or an animation). */
    @Synchronized
    fun moved(at: CameraView) {
        if (placed && heading == null) view.value = at
    }

    /** The map came to rest at [at]. */
    @Synchronized
    fun settled(at: CameraView) = moved(at)

    /** The move to [destination] finished or was given up, with the map at [at]; a newer move asked for stands. */
    @Synchronized
    fun ended(destination: CameraView, at: CameraView) {
        if (heading != destination) return
        heading = null
        view.value = at
    }
}
