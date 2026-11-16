package com.fieldmaxx.server.admin

import com.fieldmaxx.core.model.ACTIVITY_LIMIT
import com.fieldmaxx.core.model.ActivityItem
import com.fieldmaxx.core.model.AdminSettings
import com.fieldmaxx.core.model.AppConfig
import com.fieldmaxx.core.FmxJson
import com.fieldmaxx.core.model.Change
import com.fieldmaxx.core.model.HealthReport
import com.fieldmaxx.core.model.PoleData
import com.fieldmaxx.core.model.TagRename
import com.fieldmaxx.core.model.User
import com.fieldmaxx.server.ops.readTags
import com.fieldmaxx.server.ops.writeTags
import com.fieldmaxx.core.model.PhotoInfo
import com.fieldmaxx.server.Ctx
import com.fieldmaxx.server.HttpError
import com.fieldmaxx.server.auth.requireAdmin
import com.fieldmaxx.server.delivery.BACKUP_DAYS_SETTING
import com.fieldmaxx.server.delivery.MIN_BACKUP_DAYS
import com.fieldmaxx.server.delivery.backupDays
import com.fieldmaxx.server.delivery.diskUsedPct
import com.fieldmaxx.server.delivery.retryPhoto
import com.fieldmaxx.server.jobs.toPhoto
import com.fieldmaxx.server.jobs.toPoint
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.application.install
import io.ktor.server.auth.AuthenticationChecked
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable

const val DEFAULT_IMAGERY_URL = "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}"
const val DEFAULT_LABELS_URL = "https://server.arcgisonline.com/ArcGIS/rest/services/Reference/World_Transportation/MapServer/tile/{z}/{y}/{x}"

/**
 * With an ArcGIS Location Platform key set, the phones use Esri's licensed services instead: the same World Imagery, and
 * the imagery labels from the static basemap tiles service (512 px tiles). The keyless World_Transportation layer has no
 * keyed copy. Both take the key as `token`. Without a key the keyless addresses above stay.
 */
const val KEYED_IMAGERY_URL = "https://ibasemaps-api.arcgis.com/arcgis/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}"
const val KEYED_LABELS_URL =
    "https://static-map-tiles-api.arcgis.com/arcgis/rest/services/static-basemap-tiles-service/v1/arcgis/imagery/labels/static/tile/{z}/{y}/{x}"
const val DEFAULT_PHOTO_QUALITY = 85
private const val DAY_MS = 24L * 3600 * 1000

/**
 * Deliveries are logged as events too, one per photo delivered and one per failed attempt: they belong on Delivery
 * health, and would fill the activity feed's cap (I1).
 */
private const val NOT_DELIVERIES = "e.kind NOT IN ('photo.confirmed', 'photo.failed')"

/** At most this many kinds in one activity query; a list asks for one or two. */
private const val MAX_KINDS = 20

@Serializable data class Ok(val ok: Boolean = true)

private fun Ctx.setting(key: String): String? = db.read {
    prepareStatement("SELECT value FROM settings WHERE key = ?").use { ps ->
        ps.setString(1, key); ps.executeQuery().use { if (it.next()) it.getString(1) else null }
    }
}

/**
 * What every phone needs: map tile sources and the photo/backups settings, with the defaults where nothing is set. An
 * address the admin typed in always wins; otherwise a key selects Esri's keyed addresses.
 */
fun appConfig(ctx: Ctx): AppConfig {
    val key = ctx.setting("imageryKey")?.ifBlank { null }
    return AppConfig(
        imageryUrl = ctx.setting("imageryUrl")?.ifBlank { null } ?: if (key != null) KEYED_IMAGERY_URL else DEFAULT_IMAGERY_URL,
        labelsUrl = ctx.setting("labelsUrl")?.ifBlank { null } ?: if (key != null) KEYED_LABELS_URL else DEFAULT_LABELS_URL,
        imageryKey = key,
        photoQuality = ctx.setting("photoQuality")?.toIntOrNull() ?: DEFAULT_PHOTO_QUALITY,
        backupDays = backupDays(ctx),
        tags = ctx.db.read { readTags() },
    )
}

private fun median(values: List<Long>): Long? {
    if (values.isEmpty()) return null
    val s = values.sorted()
    return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
}

/**
 * Photos not yet confirmed (stuck first, then oldest first) and the typical delivery times of the last 24 hours. I2: only
 * photos of live points in live jobs, the ones someone can still deliver; a deleted point's or job's photos wait for a
 * restore, and "Retry now" can't move them.
 */
fun healthReport(ctx: Ctx): HealthReport {
    val since = ctx.clock.millis() - DAY_MS
    val (photos, phone, upload) = ctx.db.read {
        val photos = createStatement().use { st ->
            st.executeQuery(
                "SELECT ph.* FROM photos ph JOIN points pt ON pt.id = ph.point_id JOIN jobs j ON j.id = ph.job_id " +
                    "WHERE ph.confirmed_at IS NULL AND ph.status != 'removed' AND pt.deleted_at IS NULL AND j.deleted_at IS NULL " +
                    "ORDER BY CASE ph.status WHEN 'stuck' THEN 0 ELSE 1 END, ph.taken_at, ph.id",
            ).use { rs -> buildList<PhotoInfo> { while (rs.next()) add(rs.toPhoto()) } }
        }
        val phone = mutableListOf<Long>()
        val upload = mutableListOf<Long>()
        prepareStatement("SELECT taken_at, confirmed_at, upload_ms FROM photos WHERE confirmed_at >= ?").use { ps ->
            ps.setLong(1, since)
            ps.executeQuery().use { rs ->
                while (rs.next()) {
                    phone += (rs.getLong("confirmed_at") - rs.getLong("taken_at")) / 1000
                    val ms = rs.getLong("upload_ms")
                    if (!rs.wasNull()) upload += ms / 1000
                }
            }
        }
        Triple(photos, phone, upload)
    }
    return HealthReport(diskUsedPct(ctx.config.dataDir), photos, median(phone), median(upload))
}

/**
 * The newest [ACTIVITY_LIMIT] events, newest first, of every kind but deliveries, or only of [kinds] when given (a list
 * that shows only those then reaches as far back as it can, I1). "Can't shoot" entries carry their reason (and the
 * tech's text) in `detail`. A point's event names the point (R68), so a deleted one can be restored from its entry.
 */
fun activity(ctx: Ctx, jobId: String?, kinds: Set<String>? = null): List<ActivityItem> = ctx.db.read {
    val only = kinds.orEmpty().take(MAX_KINDS)
    val ofKinds = if (only.isEmpty()) "" else " AND e.kind IN (" + only.joinToString(", ") { "?" } + ")"
    prepareStatement(
        "SELECT e.at, e.kind, u.display_name, p.label, json_extract(e.data_json, '\$.pointId') AS point_id, " +
            "json_extract(e.data_json, '\$.reason') AS reason, json_extract(e.data_json, '\$.text') AS text FROM events e " +
            "LEFT JOIN users u ON u.id = e.user_id " +
            "LEFT JOIN points p ON p.id = json_extract(e.data_json, '\$.pointId') " +
            "WHERE (? IS NULL OR e.job_id = ?) AND $NOT_DELIVERIES$ofKinds ORDER BY e.at DESC, e.id DESC LIMIT $ACTIVITY_LIMIT",
    ).use { ps ->
        ps.setString(1, jobId); ps.setString(2, jobId)
        only.forEachIndexed { i, kind -> ps.setString(3 + i, kind) }
        ps.executeQuery().use { rs ->
            buildList {
                while (rs.next()) {
                    val kind = rs.getString("kind")
                    val reason = rs.getString("reason")
                    val detail = if (kind == "point.cantShoot" && reason != null) {
                        val text = rs.getString("text")
                        if (text.isNullOrBlank()) reason else "$reason: $text"
                    } else ""
                    add(ActivityItem(rs.getLong("at"), rs.getString("display_name") ?: "", kind, rs.getString("label"), detail, rs.getString("point_id")))
                }
            }
        }
    }
}

/** Validates everything first, then writes all given fields in one transaction. A blank map URL or key clears the override. */
fun updateSettings(ctx: Ctx, s: AdminSettings) {
    s.photoQuality?.let { if (it != 85 && it != 95) throw HttpError(HttpStatusCode.BadRequest, "Photo quality must be 85 or 95") }
    s.backupDays?.let { if (it < MIN_BACKUP_DAYS) throw HttpError(HttpStatusCode.BadRequest, "Backups must be kept at least $MIN_BACKUP_DAYS days") }
    val values = listOfNotNull(
        s.photoQuality?.let { "photoQuality" to it.toString() },
        s.backupDays?.let { BACKUP_DAYS_SETTING to it.toString() },
        s.imageryUrl?.let { "imageryUrl" to it.trim() },
        s.labelsUrl?.let { "labelsUrl" to it.trim() },
        s.imageryKey?.let { "imageryKey" to it.trim() },
    )
    ctx.db.tx {
        for ((k, v) in values) {
            if (v.isEmpty()) prepareStatement("DELETE FROM settings WHERE key = ?").use { it.setString(1, k); it.executeUpdate() }
            else prepareStatement("INSERT INTO settings(key, value) VALUES (?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value").use {
                it.setString(1, k); it.setString(2, v); it.executeUpdate()
            }
        }
    }
}

/** Replaces the tag list: trimmed, blanks and case-insensitive duplicates dropped. Points keep tags no longer listed. */
fun replaceTags(ctx: Ctx, tags: List<String>) {
    val seen = HashSet<String>()
    val clean = tags.map { it.trim() }.filter { it.isNotEmpty() && seen.add(it.lowercase()) }
    ctx.db.tx { writeTags(clean) }
}

/** Renames a tag in the list and on every point of a live job that has it; each changed point goes through the change log. */
fun renameTag(ctx: Ctx, user: User, r: TagRename) {
    val to = r.to.trim()
    if (to.isEmpty()) throw HttpError(HttpStatusCode.BadRequest, "The new tag name is empty")
    val changes = ctx.db.tx {
        val list = readTags()
        val idx = list.indexOfFirst { it.equals(r.from.trim(), ignoreCase = true) }
        if (idx < 0) throw HttpError(HttpStatusCode.NotFound, "No such tag")
        if (list.any { it.equals(to, ignoreCase = true) }) throw HttpError(HttpStatusCode.BadRequest, "That tag already exists")
        val from = list[idx]
        writeTags(list.toMutableList().also { it[idx] = to })
        val now = ctx.clock.millis()
        val points = prepareStatement(
            "SELECT p.* FROM points p JOIN jobs j ON j.id = p.job_id WHERE j.deleted_at IS NULL AND p.deleted_at IS NULL",
        ).use { ps -> ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.toPoint()) } } }
        val out = mutableListOf<Change>()
        for (p in points) {
            val old = p.pole ?: continue
            if (old.tags.none { it == from }) continue
            val pole = old.copy(tags = old.tags.map { if (it == from) to else it })
            prepareStatement("UPDATE points SET pole_json = ?, updated_at = ?, updated_by = ? WHERE id = ?").use {
                it.setString(1, FmxJson.encodeToString(PoleData.serializer(), pole)); it.setLong(2, now); it.setString(3, user.id)
                it.setString(4, p.id); it.executeUpdate()
            }
            val updated = prepareStatement("SELECT * FROM points WHERE id = ?").use { ps ->
                ps.setString(1, p.id); ps.executeQuery().use { rs -> rs.next(); rs.toPoint() }
            }
            out += ctx.changes.recordInTx(this, updated.jobId, "point", updated.id, point = updated)
        }
        out
    }
    changes.forEach(ctx.changes::publish)
}

/** Route-scoped check: every call under the route it is installed on must come from an admin. */
private val AdminOnly = createRouteScopedPlugin("AdminOnly") {
    // After authentication, so the principal exists; a 401 challenge has already ended the call before this runs.
    on(AuthenticationChecked) { call -> call.requireAdmin() }
}

fun Route.adminRoutes(ctx: Ctx) {
    authenticate("bearer") {
        get("/config") { call.respond(appConfig(ctx)) }
        // One guard for the whole block: a route added here is admin-only without remembering a check.
        route("/admin") {
            install(AdminOnly)
            get("/health") { call.respond(healthReport(ctx)) }
            post("/photos/{id}/retry") {
                retryPhoto(ctx, call.requireAdmin(), call.parameters["id"]!!)
                call.respond(Ok())
            }
            get("/activity") {
                val query = call.request.queryParameters
                // ?kinds=point.delete,point.restore: only those (one list's own); none or empty: every kind but deliveries
                val kinds = query["kinds"]?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet()?.ifEmpty { null }
                call.respond(activity(ctx, query["jobId"]?.ifBlank { null }, kinds))
            }
            put("/tags") {
                replaceTags(ctx, call.receive<List<String>>())
                call.respond(appConfig(ctx))
            }
            post("/tags/rename") {
                renameTag(ctx, call.requireAdmin(), call.receive<TagRename>())
                call.respond(appConfig(ctx))
            }
            put("/settings") {
                updateSettings(ctx, call.receive<AdminSettings>())
                call.respond(appConfig(ctx))
            }
        }
    }
}
