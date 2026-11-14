package com.fieldmaxx.app.map

import com.fieldmaxx.app.sync.SyncStatus
import com.fieldmaxx.core.geo.LatLon
import com.fieldmaxx.core.geo.distanceMeters
import com.fieldmaxx.core.geo.metersToFeet
import com.fieldmaxx.core.model.CrewPosition
import com.fieldmaxx.core.model.PhotoInfo
import com.fieldmaxx.core.model.PhotoStatus
import com.fieldmaxx.core.model.Point
import com.fieldmaxx.core.model.PointStatus
import com.fieldmaxx.core.naming.isQueryLayer
import java.net.URLEncoder
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import kotlin.math.min
import kotlin.math.roundToInt
import org.shredzone.commons.suncalc.SunTimes

// What the map screen shows (spec §6.3), worked out from the job's points, the crew, the clock and the sync state.

data class TypeProgress(val type: String, val done: Int, val total: Int)

data class Progress(val done: Int, val total: Int, val pct: Int, val byType: List<TypeProgress>)

/** The map's centre and zoom: what is remembered per user per job. */
data class CameraView(val lat: Double, val lon: Double, val zoom: Double)

enum class CrewDot { FRESH, PAUSED, FADED, HIDDEN }

/** The job list's message when the map went back because the job is gone (R42). */
const val JOB_DELETED = "This job was deleted"

/** Shown instead of the blue dot when location isn't allowed (R41). */
const val LOCATION_OFF = "Location off"

/** The map's zoom range; a saved view outside it isn't restored. */
const val MIN_ZOOM = 2.0
const val MAX_ZOOM = 22.0

private const val SUN = "☀️" // ☀️ with the emoji presentation selector, as the brief writes it
private const val AFTER_SUNSET = "🌙 After sunset"
private const val FADE_AFTER_MS = 10 * 60_000L
private const val HIDE_AFTER_MS = 60 * 60_000L
private const val METERS_PER_MILE = 1609.344

/** Closed with photos, or as "Can't shoot": both count as complete (spec §6.4), and both show grey on the map. */
fun isShot(p: Point): Boolean = p.status == PointStatus.done || p.status == PointStatus.cant_shoot

private fun live(points: List<Point>) = points.filter { it.deletedAt == null }

/** Done and "Can't shoot" are complete; deleted points don't count. Types keep the order the job lists them in. */
fun progress(points: List<Point>): Progress {
    val live = live(points)
    val done = live.count(::isShot)
    val byType = live.groupBy { it.type }.map { (type, ofType) -> TypeProgress(type, ofType.count(::isShot), ofType.size) }
    return Progress(done, live.size, percent(done, live.size), byType)
}

/** Rounded, but 100% only once nothing is left. */
private fun percent(done: Int, total: Int): Int = when {
    total == 0 -> 0
    done >= total -> 100
    else -> min(99, (done * 100.0 / total).roundToInt())
}

/** The markers to draw: one type or all ([type] null), without the shot ones when [hideShot]. */
fun visiblePoints(points: List<Point>, type: String?, hideShot: Boolean): List<Point> =
    live(points).filter { (type == null || it.type == type) && !(hideShot && isShot(it)) }

/** "✓ POLE (80/80)" once a type is complete, else "POLE (12/80)". */
fun chipLabel(t: TypeProgress): String {
    val text = "${t.type} (${t.done}/${t.total})"
    return if (t.total > 0 && t.done == t.total) "✓ $text" else text
}

/**
 * "☀️ 5h 40m until sunset" (whole minutes left; "<1m" in the last minute), or "🌙 After sunset" once today's sunset at
 * the spot has passed.
 */
fun sunsetText(nowMs: Long, lat: Double, lon: Double, zone: ZoneId): String {
    val now = Instant.ofEpochMilli(nowMs)
    val sunset = SunTimes.compute().on(now.atZone(zone)).midnight().at(lat, lon).execute().set?.toInstant()
    if (sunset == null || !now.isBefore(sunset)) return AFTER_SUNSET
    val minutes = Duration.between(now, sunset).toMinutes()
    val left = when {
        minutes >= 60 -> "${minutes / 60}h ${minutes % 60}m"
        minutes >= 1 -> "${minutes}m"
        else -> "<1m"
    }
    return "$SUN $left until sunset"
}

/** The points still to shoot, nearest first; in the job's order while your position isn't known. */
fun remainingByDistance(points: List<Point>, from: LatLon?): List<Point> {
    val left = live(points).filterNot(::isShot)
    return if (from == null) left else left.sortedBy { distanceMeters(from, LatLon(it.lat, it.lon)) }
}

/** Hidden after an hour and faded after 10 minutes, whatever the dot; until then a tech whose app is closed is grey. */
fun crewDotState(p: CrewPosition, now: Long): CrewDot {
    val age = now - p.at
    return when {
        age >= HIDE_AFTER_MS -> CrewDot.HIDDEN
        age >= FADE_AFTER_MS -> CrewDot.FADED
        p.paused -> CrewDot.PAUSED
        else -> CrewDot.FRESH
    }
}

/** "Bob Tech · 2 min ago", or "Bob Tech · app closed · 3 min ago" (spec §6.3). */
fun crewLabel(p: CrewPosition, now: Long): String {
    val minutes = ((now - p.at) / 60_000L).coerceAtLeast(0)
    val age = if (minutes < 1) "just now" else "$minutes min ago"
    return if (p.paused) "${p.displayName} · app closed · $age" else "${p.displayName} · $age"
}

/** Points whose label, number or description holds [q] (any case); exact label or number matches first. */
fun searchPoints(points: List<Point>, q: String): List<Point> {
    val query = q.trim()
    if (query.isEmpty()) return emptyList()
    val found = live(points).filter {
        it.label.contains(query, ignoreCase = true) || it.num.contains(query, ignoreCase = true) ||
            it.description.contains(query, ignoreCase = true)
    }
    return found.sortedBy { if (it.label.equals(query, ignoreCase = true) || it.num.equals(query, ignoreCase = true)) 0 else 1 }
}

/** "360 ft" (to 10 ft) under 1000 ft, else "1.2 mi". */
fun distanceText(meters: Double): String {
    val feet = metersToFeet(meters)
    if (feet < 1000) return "${((feet / 10).roundToInt() * 10).coerceAtLeast(10)} ft"
    return String.format(Locale.US, "%.1f mi", meters / METERS_PER_MILE)
}

/**
 * Points with a photo not in OneDrive yet, for the marker badge: the server's photos that are waiting or stuck (any
 * phone's), and [localWaiting], the points of this phone's photos the server may not know yet. Photos [deleted] on this
 * phone don't count, though the server may still list them (fix round 1).
 */
fun pendingPointIds(serverPhotos: List<PhotoInfo>, localWaiting: List<String>, deleted: Set<String> = emptySet()): Set<String> =
    serverPhotos.filter { (it.status == PhotoStatus.registered || it.status == PhotoStatus.stuck) && it.id !in deleted }
        .mapTo(HashSet()) { it.pointId } + localWaiting

/** The top bar's connection pill (R40); [waiting] counts this phone's photos not in OneDrive yet, across all jobs. */
fun pillText(status: SyncStatus, waiting: Int): String = when (status) {
    SyncStatus.OFFLINE -> if (waiting > 0) "Offline · ${photoCount(waiting)} waiting" else "Offline"
    SyncStatus.SYNCING -> "Syncing…"
    SyncStatus.ONLINE -> if (waiting > 0) "${photoCount(waiting)} waiting" else "Online"
}

private fun photoCount(n: Int) = if (n == 1) "1 photo" else "$n photos"

/** The red banner under the top bar while OneDrive delivery for the job fails; [reason] is the server's. */
fun deliveryBannerText(reason: String): String = "Photos can't reach OneDrive: $reason"

/**
 * A refused op in plain words (R42): the server's codes from ops/Ops.kt and photos/PhotoService.kt. Refusals come from
 * any job's queued changes, not only this map's, so the sentence names nothing on screen. By the time it shows, the
 * phone has put the server's state back.
 */
fun rejectionText(code: String?): String = "A change couldn't be saved: " + when (code) {
    "not_found" -> "the point or photo no longer exists."
    "job_not_found" -> "the job no longer exists."
    "job_deleted" -> "the job was deleted."
    "point_deleted" -> "the point was deleted."
    "needs_photo" -> "a point needs at least one photo before it can be closed."
    "needs_text" -> "\"Can't shoot\" with Other needs a reason in words."
    "photos_delivered" -> "only an admin can delete a point whose photos reached OneDrive."
    "admin_only" -> "only an admin can do that."
    "forbidden" -> "only the tech who took a photo, or an admin, can change it."
    "exists" -> "it was already saved on the server."
    else -> "the server refused it."
}

/** A tile URL template with the server's optional imagery key, sent as ArcGIS's `token` parameter. */
fun tileUrl(template: String, key: String?): String {
    val k = key?.trim().orEmpty()
    if (k.isEmpty()) return template
    return template + (if ('?' in template) "&" else "?") + "token=" + URLEncoder.encode(k, "UTF-8")
}

private val TYPE_COLORS = mapOf(
    "POLE" to "#f97316", "PED" to "#a855f7", "HH" to "#06b6d4", "MH" to "#ec4899",
    "BU" to "#facc15", "SAI" to "#84cc16", "PFP" to "#14b8a6", "MDU" to "#f43f5e",
)
private const val QUERY_COLOR = "#ef4444"
private val OTHER_COLORS = listOf("#8b5cf6", "#10b981", "#eab308", "#0ea5e9", "#d946ef", "#fb7185", "#4ade80", "#38bdf8")

/**
 * A marker's colour by type, bright enough to read on aerial imagery. Blue is left for your own position and white for
 * the crew; any other type always gets the same colour from a small palette.
 */
fun typeColor(type: String): String =
    TYPE_COLORS[type] ?: if (isQueryLayer(type)) QUERY_COLOR else OTHER_COLORS[Math.floorMod(type.hashCode(), OTHER_COLORS.size)]
