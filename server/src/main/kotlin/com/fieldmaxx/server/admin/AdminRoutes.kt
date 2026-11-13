package com.fieldmaxx.server.admin

import com.fieldmaxx.core.model.ActivityItem
import com.fieldmaxx.core.model.AdminSettings
import com.fieldmaxx.core.model.AppConfig
import com.fieldmaxx.core.model.HealthReport
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
const val DEFAULT_PHOTO_QUALITY = 85
private const val DAY_MS = 24L * 3600 * 1000
private const val ACTIVITY_LIMIT = 500

@Serializable data class Ok(val ok: Boolean = true)

private fun Ctx.setting(key: String): String? = db.read {
    prepareStatement("SELECT value FROM settings WHERE key = ?").use { ps ->
        ps.setString(1, key); ps.executeQuery().use { if (it.next()) it.getString(1) else null }
    }
}

/** What every phone needs: map tile sources and the photo/backups settings, with the defaults where nothing is set. */
fun appConfig(ctx: Ctx) = AppConfig(
    imageryUrl = ctx.setting("imageryUrl")?.ifBlank { null } ?: DEFAULT_IMAGERY_URL,
    labelsUrl = ctx.setting("labelsUrl")?.ifBlank { null } ?: DEFAULT_LABELS_URL,
    imageryKey = ctx.setting("imageryKey")?.ifBlank { null },
    photoQuality = ctx.setting("photoQuality")?.toIntOrNull() ?: DEFAULT_PHOTO_QUALITY,
    backupDays = backupDays(ctx),
)

private fun median(values: List<Long>): Long? {
    if (values.isEmpty()) return null
    val s = values.sorted()
    return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
}

/** Photos not yet confirmed (stuck first, then oldest first) and the typical delivery times of the last 24 hours. */
fun healthReport(ctx: Ctx): HealthReport {
    val since = ctx.clock.millis() - DAY_MS
    val (photos, phone, upload) = ctx.db.read {
        val photos = createStatement().use { st ->
            st.executeQuery(
                "SELECT * FROM photos WHERE confirmed_at IS NULL AND status != 'removed' " +
                    "ORDER BY CASE status WHEN 'stuck' THEN 0 ELSE 1 END, taken_at, id",
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

/** Newest first; "Can't shoot" entries carry their reason (and the tech's text) in `detail`. */
fun activity(ctx: Ctx, jobId: String?): List<ActivityItem> = ctx.db.read {
    prepareStatement(
        "SELECT e.at, e.kind, u.display_name, p.label, p.cant_reason, p.cant_text, p.status AS point_status FROM events e " +
            "LEFT JOIN users u ON u.id = e.user_id " +
            "LEFT JOIN points p ON p.id = json_extract(e.data_json, '\$.pointId') " +
            "WHERE (? IS NULL OR e.job_id = ?) ORDER BY e.at DESC, e.id DESC LIMIT $ACTIVITY_LIMIT",
    ).use { ps ->
        ps.setString(1, jobId); ps.setString(2, jobId)
        ps.executeQuery().use { rs ->
            buildList {
                while (rs.next()) {
                    val kind = rs.getString("kind")
                    val reason = rs.getString("cant_reason")
                    val detail = if (kind == "point.cantShoot" && reason != null) {
                        val text = rs.getString("cant_text")
                        if (text.isNullOrBlank()) reason else "$reason: $text"
                    } else ""
                    add(ActivityItem(rs.getLong("at"), rs.getString("display_name") ?: "", kind, rs.getString("label"), detail))
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
            get("/activity") { call.respond(activity(ctx, call.request.queryParameters["jobId"]?.ifBlank { null })) }
            put("/settings") {
                updateSettings(ctx, call.receive<AdminSettings>())
                call.respond(appConfig(ctx))
            }
        }
    }
}
