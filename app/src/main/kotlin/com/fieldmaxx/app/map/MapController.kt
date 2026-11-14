package com.fieldmaxx.app.map

import com.fieldmaxx.app.platform.Fix
import com.fieldmaxx.core.geo.LatLon
import com.fieldmaxx.core.model.AppConfig
import com.fieldmaxx.core.model.CrewPosition
import com.fieldmaxx.core.model.Point
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

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

    /** Where the map looks once it has settled after a move; null until the map has been placed or moved. */
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
