package com.fieldmaxx.server.photos

import com.fieldmaxx.core.geo.LatLon
import com.fieldmaxx.core.geo.distanceMeters
import com.fieldmaxx.core.model.Change
import com.fieldmaxx.core.model.Op
import com.fieldmaxx.core.model.OpResult
import com.fieldmaxx.core.model.PhotoInfo
import com.fieldmaxx.core.model.PhotoRegistration
import com.fieldmaxx.core.model.PhotoStatus
import com.fieldmaxx.core.model.RegisteredPhoto
import com.fieldmaxx.core.model.ReuseCandidate
import com.fieldmaxx.core.model.Role
import com.fieldmaxx.core.model.User
import com.fieldmaxx.core.naming.defaultFolderName
import com.fieldmaxx.core.naming.photoFileName
import com.fieldmaxx.server.Ctx
import com.fieldmaxx.server.HttpError
import com.fieldmaxx.server.jobs.path
import com.fieldmaxx.server.jobs.toJob
import com.fieldmaxx.server.jobs.toPhoto
import com.fieldmaxx.server.delivery.FORBIDDEN_PROBLEM
import com.fieldmaxx.server.delivery.SIGN_IN_PROBLEM
import com.fieldmaxx.server.ops.Applied
import com.fieldmaxx.server.ops.OpFail
import com.fieldmaxx.server.ops.applyOps
import com.fieldmaxx.server.ops.livePoint
import io.ktor.http.HttpStatusCode
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.sql.Connection
import java.util.concurrent.ConcurrentHashMap

const val MAX_BACKUP_BYTES = 40L * 1024 * 1024
const val MAX_CHUNK_BYTES = 2 * 1024 * 1024
const val STUCK_AFTER_ATTEMPTS = 6
const val DEFAULT_NEARBY_RADIUS_M = 150.0
private const val NEARBY_LIMIT = 200
private const val METERS_PER_DEGREE = 111_320.0

/** The chunk's offset is not where the server's copy ends; [current] is where to resume ([complete]: nothing left to send). */
class BackupOffsetConflict(val current: Long, val complete: Boolean) : RuntimeException("Wrong offset", null, false, false)

private val PHOTO_ID = Regex("[A-Za-z0-9_-]{1,64}")
private val chunkLocks = ConcurrentHashMap<String, Any>()

private fun checkId(id: String) {
    if (!PHOTO_ID.matches(id)) throw HttpError(HttpStatusCode.BadRequest, "Bad photo id")
}

private fun canTouch(user: User, ownerId: String) = user.role == Role.admin || user.id == ownerId

private fun Connection.exec(sql: String, vararg args: Any?) {
    prepareStatement(sql).use { ps ->
        args.forEachIndexed { i, a -> ps.setObject(i + 1, a) }
        ps.executeUpdate()
    }
}

/** Next value (starting at 1) of the server-wide counter [name]; runs on the caller's transaction. */
internal fun Connection.nextCounter(name: String): Long {
    exec("INSERT INTO counters(name, value) VALUES (?, 1) ON CONFLICT(name) DO UPDATE SET value = value + 1", name)
    return prepareStatement("SELECT value FROM counters WHERE name = ?").use { ps ->
        ps.setString(1, name); ps.executeQuery().use { it.next(); it.getLong(1) }
    }
}

fun nextCounter(ctx: Ctx, name: String): Long = ctx.db.tx { nextCounter(name) }

internal fun Connection.photoInfo(id: String): PhotoInfo? =
    prepareStatement("SELECT * FROM photos WHERE id = ?").use { ps ->
        ps.setString(1, id); ps.executeQuery().use { if (it.next()) it.toPhoto() else null }
    }

/** The stored columns the service itself needs but [PhotoInfo] does not carry. */
private class PhotoRow(
    val id: String, val userId: String, val size: Long, val sha256: String?, val status: PhotoStatus, val backupOffset: Long,
    val backupPath: String?, val backupReceivedAt: Long?, val backupPurgedAt: Long?, val thumbPath: String?, val confirmedAt: Long?,
    val pointId: String, val jobId: String, val fileName: String, val lat: Double?, val lon: Double?,
    val takenAt: Long, val accuracyM: Double?, val headingDeg: Double?,
)

private fun java.sql.ResultSet.nullableLong(c: String) = getLong(c).takeUnless { wasNull() }
private fun java.sql.ResultSet.nullableDouble(c: String) = getDouble(c).takeUnless { wasNull() }

private fun Connection.photoRow(id: String): PhotoRow? =
    prepareStatement("SELECT * FROM photos WHERE id = ?").use { ps ->
        ps.setString(1, id)
        ps.executeQuery().use {
            if (!it.next()) null
            else PhotoRow(
                it.getString("id"), it.getString("user_id"), it.getLong("size"), it.getString("sha256"),
                PhotoStatus.valueOf(it.getString("status")), it.getLong("backup_offset"), it.getString("backup_path"),
                it.nullableLong("backup_received_at"), it.nullableLong("backup_purged_at"), it.getString("thumb_path"),
                it.nullableLong("confirmed_at"), it.getString("point_id"), it.getString("job_id"), it.getString("file_name"),
                it.nullableDouble("lat"), it.nullableDouble("lon"), it.getLong("taken_at"),
                it.nullableDouble("accuracy_m"), it.nullableDouble("heading_deg"),
            )
        }
    }

private class PointRef(val jobId: String, val label: String, val lat: Double, val lon: Double)

private fun Connection.pointRef(pointId: String): PointRef? =
    prepareStatement("SELECT job_id, label, lat, lon FROM points WHERE id = ?").use { ps ->
        ps.setString(1, pointId)
        ps.executeQuery().use { if (it.next()) PointRef(it.getString(1), it.getString(2), it.getDouble(3), it.getDouble(4)) else null }
    }

private fun cadastrusName(p: PointRef, lat: Double?, lon: Double?, counter: Long) =
    photoFileName(defaultFolderName(p.label), lat ?: p.lat, lon ?: p.lon, counter)

/**
 * Registers a photo the phone just made and assigns its OneDrive file name. Registering the same id again returns the
 * stored registration unchanged (the phone retries until it hears back).
 */
fun registerPhoto(ctx: Ctx, user: User, reg: PhotoRegistration): RegisteredPhoto {
    checkId(reg.id)
    var change: Change? = null
    val result = ctx.db.tx {
        photoRow(reg.id)?.let {
            if (it.userId != user.id) throw HttpError(HttpStatusCode.Conflict, "Photo id already used")
            return@tx RegisteredPhoto(it.fileName, it.status, it.backupOffset)
        }
        val point = pointRef(reg.pointId) ?: throw HttpError(HttpStatusCode.NotFound, "Point not found")
        if (prepareStatement("SELECT deleted_at FROM points WHERE id = ?").use { ps ->
                ps.setString(1, reg.pointId); ps.executeQuery().use { it.next(); it.getObject(1) != null }
            }
        ) throw HttpError(HttpStatusCode.Gone, "Point was deleted")
        if (prepareStatement("SELECT deleted_at FROM jobs WHERE id = ?").use { ps ->
                ps.setString(1, point.jobId); ps.executeQuery().use { it.next(); it.getObject(1) != null }
            }
        ) throw HttpError(HttpStatusCode.Gone, "Job was deleted")
        if (point.jobId != reg.jobId) throw HttpError(HttpStatusCode.BadRequest, "Point belongs to another job")
        val now = ctx.clock.millis()
        val name = cadastrusName(point, reg.lat, reg.lon, nextCounter("photo"))
        exec(
            "INSERT INTO photos(id, job_id, point_id, user_id, file_name, size, sha256, taken_at, lat, lon, accuracy_m, heading_deg, " +
                "status, source_kind, created_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,'registered','phone',?)",
            reg.id, point.jobId, reg.pointId, user.id, name, reg.size, reg.sha256.lowercase(), reg.takenAt, reg.lat, reg.lon,
            reg.accuracyM, reg.headingDeg, now,
        )
        change = ctx.changes.recordInTx(this, point.jobId, "photo", reg.id, photo = photoInfo(reg.id))
        RegisteredPhoto(name, PhotoStatus.registered, 0)
    }
    change?.let(ctx.changes::publish)
    return result
}

/** The phone found a different-size file of the same name in OneDrive: give the photo a fresh counter. */
fun renamePhoto(ctx: Ctx, user: User, photoId: String): RegisteredPhoto = rename(ctx, photoId) { row ->
    if (!canTouch(user, row.userId)) throw HttpError(HttpStatusCode.Forbidden, "Not your photo")
}

/** The server's own delivery met a different file under the photo's name: a fresh counter, as [renamePhoto]. */
internal fun renamePhotoForDelivery(ctx: Ctx, photoId: String): RegisteredPhoto = rename(ctx, photoId) {}

private fun rename(ctx: Ctx, photoId: String, check: (PhotoRow) -> Unit): RegisteredPhoto {
    var change: Change? = null
    val result = ctx.db.tx {
        val row = photoRow(photoId) ?: throw HttpError(HttpStatusCode.NotFound, "Photo not found")
        check(row)
        if (row.confirmedAt != null) throw HttpError(HttpStatusCode.Conflict, "Photo already delivered")
        val point = pointRef(row.pointId) ?: throw HttpError(HttpStatusCode.NotFound, "Point not found")
        val name = cadastrusName(point, row.lat, row.lon, nextCounter("photo"))
        exec("UPDATE photos SET file_name = ? WHERE id = ?", name, photoId)
        change = ctx.changes.recordInTx(this, row.jobId, "photo", photoId, photo = photoInfo(photoId))
        RegisteredPhoto(name, row.status, row.backupOffset)
    }
    change?.let(ctx.changes::publish)
    return result
}

private fun sha256Hex(path: java.nio.file.Path): String {
    val md = MessageDigest.getInstance("SHA-256")
    Files.newInputStream(path).use { s ->
        val buf = ByteArray(64 * 1024)
        while (true) { val n = s.read(buf); if (n < 0) break; md.update(buf, 0, n) }
    }
    return md.digest().joinToString("") { "%02x".format(it) }
}

/**
 * Appends [bytes] at [offset] of the photo's server copy. Returns the new end offset and whether the copy is now complete
 * (size and SHA-256 verified, thumbnail made). A wrong offset throws [BackupOffsetConflict]; a bad checksum discards the
 * partial file (422).
 */
fun appendBackupChunk(ctx: Ctx, user: User, photoId: String, offset: Long, bytes: ByteArray): Pair<Long, Boolean> {
    checkId(photoId)
    synchronized(chunkLocks.computeIfAbsent(photoId) { Any() }) {
        val row = ctx.db.read { photoRow(photoId) } ?: throw HttpError(HttpStatusCode.NotFound, "Photo not found")
        if (!canTouch(user, row.userId)) throw HttpError(HttpStatusCode.Forbidden, "Not your photo")
        if (row.status == PhotoStatus.removed) throw HttpError(HttpStatusCode.Gone, "Photo was removed")
        if (row.backupReceivedAt != null) {
            if (offset == row.size && bytes.isEmpty()) return row.size to true
            throw BackupOffsetConflict(row.size, true)
        }
        if (bytes.size > MAX_CHUNK_BYTES) throw HttpError(HttpStatusCode.PayloadTooLarge, "Chunks are at most 2 MB")
        if (offset != row.backupOffset) throw BackupOffsetConflict(row.backupOffset, false)
        val end = offset + bytes.size
        if (end > MAX_BACKUP_BYTES || end > row.size) throw HttpError(HttpStatusCode.PayloadTooLarge, "Photo copy is larger than allowed")
        val dir = ctx.path("photos").also { Files.createDirectories(it) }
        val part = dir.resolve("$photoId.part")
        // Cut back to the committed offset first: a crash between the file write and the database update leaves extra bytes.
        RandomAccessFile(part.toFile(), "rw").use { f ->
            f.setLength(offset); f.seek(offset); f.write(bytes); f.fd.sync()
        }
        if (end < row.size) {
            ctx.db.tx { exec("UPDATE photos SET backup_offset = ? WHERE id = ?", end, photoId) }
            return end to false
        }
        if (!sha256Hex(part).equals(row.sha256, ignoreCase = true)) {
            Files.deleteIfExists(part)
            ctx.db.tx { exec("UPDATE photos SET backup_offset = 0 WHERE id = ?", photoId) }
            throw HttpError(HttpStatusCode.UnprocessableEntity, "Checksum mismatch; upload the photo again from the start")
        }
        val jpg = dir.resolve("$photoId.jpg")
        Files.move(part, jpg, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        var change: Change? = null
        ctx.db.tx {
            exec(
                "UPDATE photos SET backup_path = ?, backup_offset = ?, backup_received_at = ? WHERE id = ?",
                "photos/$photoId.jpg", row.size, ctx.clock.millis(), photoId,
            )
            change = ctx.changes.recordInTx(this, row.jobId, "photo", photoId, photo = photoInfo(photoId))
        }
        change?.let(ctx.changes::publish)
        // After the commit: a thumbnail problem can never undo or fail an upload that is already safe.
        makeThumbFor(ctx, photoId, "photos/$photoId.jpg")
        return row.size to true
    }
}

private fun makeThumbFor(ctx: Ctx, photoId: String, backupRel: String): Boolean {
    val rel = "thumbs/$photoId.jpg"
    if (!makeThumbnail(ctx.path(backupRel), ctx.path(rel))) return false
    ctx.db.tx {
        exec("UPDATE photos SET thumb_path = ? WHERE id = ?", rel, photoId)
        // Reuse copies made before this thumbnail existed share it.
        exec("UPDATE photos SET thumb_path = ? WHERE reused_from = ? AND thumb_path IS NULL", rel, photoId)
    }
    return true
}

/** Retries thumbnails for photos whose server copy exists but whose thumbnail is missing. Returns how many were made. */
fun regenerateMissingThumbs(ctx: Ctx): Int {
    val todo = ctx.db.read {
        prepareStatement("SELECT id, backup_path FROM photos WHERE backup_path IS NOT NULL AND backup_purged_at IS NULL AND thumb_path IS NULL AND status != 'removed'").use { ps ->
            ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString(1) to rs.getString(2)) } }
        }
    }
    return todo.count { (id, rel) -> makeThumbFor(ctx, id, rel) }
}

// ---- ops (each runs inside applyOps' per-op transaction) ----

fun reusePhoto(ctx: Ctx, user: User, op: Op.PhotoReuse): OpResult = applyOps(ctx, user, listOf(op)).single()
fun removePhoto(ctx: Ctx, user: User, op: Op.PhotoRemove): OpResult = applyOps(ctx, user, listOf(op)).single()
fun markConfirmed(ctx: Ctx, user: User, op: Op.PhotoConfirmed): OpResult = applyOps(ctx, user, listOf(op)).single()
fun markFailed(ctx: Ctx, user: User, op: Op.PhotoFailed): OpResult = applyOps(ctx, user, listOf(op)).single()

internal fun Connection.applyPhotoOp(ctx: Ctx, user: User, op: Op, now: Long): Applied = when (op) {
    is Op.PhotoReuse -> reuse(ctx, user, op, now)
    is Op.PhotoRemove -> remove(ctx, user, op, now)
    is Op.PhotoConfirmed -> {
        val row = ownedPhoto(op.photoId, user)
        if (row.confirmedAt != null) Applied(null, "photo.confirmed")
        else {
            // A photo removed meanwhile stays removed, but it did reach OneDrive and must be remembered as delivered.
            val status = if (row.status == PhotoStatus.removed) "removed" else "confirmed"
            exec(
                "UPDATE photos SET status = ?, dest_path = ?, upload_ms = ?, confirmed_at = ?, last_error = NULL, next_attempt_at = NULL WHERE id = ?",
                status, op.destPath, op.uploadMs, now, op.photoId,
            )
            val jobChange = clearLinkProblem(ctx, row.jobId)
            Applied(null, "photo.confirmed", photo = photoInfo(op.photoId), afterCommit = jobChange?.let { { ctx.changes.publish(it) } })
        }
    }
    is Op.PhotoFailed -> {
        val row = ownedPhoto(op.photoId, user)
        if (row.confirmedAt != null || row.status == PhotoStatus.removed) Applied(null, "photo.failed")
        else {
            val status = if (op.attempts >= STUCK_AFTER_ATTEMPTS) PhotoStatus.stuck else PhotoStatus.registered
            exec("UPDATE photos SET status = ?, last_error = ?, attempts = ? WHERE id = ?", status.name, op.error, op.attempts.toLong(), op.photoId)
            Applied(null, "photo.failed", photo = photoInfo(op.photoId))
        }
    }
    else -> error("not a photo op: $op")
}

/**
 * A photo got through to OneDrive, so the job's link works: clear a sign-in / no-uploads banner (in the caller's
 * transaction) and return the job change to publish after commit. "Add the Field Notes link" stays until one is added.
 */
private fun Connection.clearLinkProblem(ctx: Ctx, jobId: String): Change? {
    val n = prepareStatement("UPDATE jobs SET delivery_problem = NULL WHERE id = ? AND delivery_problem IN (?, ?)").use { ps ->
        ps.setString(1, jobId); ps.setString(2, SIGN_IN_PROBLEM); ps.setString(3, FORBIDDEN_PROBLEM); ps.executeUpdate()
    }
    if (n == 0) return null
    val job = prepareStatement("SELECT * FROM jobs WHERE id = ?").use { ps -> ps.setString(1, jobId); ps.executeQuery().use { it.next(); it.toJob() } }
    return ctx.changes.recordInTx(this, jobId, "job", jobId, job = job)
}

private fun Connection.ownedPhoto(photoId: String, user: User): PhotoRow {
    val row = photoRow(photoId) ?: throw OpFail("not_found")
    if (!canTouch(user, row.userId)) throw OpFail("forbidden")
    return row
}

private fun Connection.reuse(ctx: Ctx, user: User, op: Op.PhotoReuse, now: Long): Applied {
    checkIdOp(op.photoId)
    val target = livePoint(op.pointId)
    val src = photoRow(op.fromPhotoId)?.takeIf { it.status != PhotoStatus.removed } ?: throw OpFail("not_found")
    if (photoRow(op.photoId) != null) throw OpFail("exists")
    val srcPoint = pointRef(src.pointId)
    val lat = src.lat ?: srcPoint?.lat
    val lon = src.lon ?: srcPoint?.lon
    val backupKept = src.backupPath != null && src.backupPurgedAt == null && Files.exists(ctx.path(src.backupPath))
    // Thumbnails are permanent, so the copy simply points at the original's.
    val name = photoFileName(defaultFolderName(target.label), lat ?: target.lat, lon ?: target.lon, nextCounter("photo"))
    exec(
        "INSERT INTO photos(id, job_id, point_id, user_id, file_name, size, sha256, taken_at, lat, lon, accuracy_m, heading_deg, status, " +
            "thumb_path, reused_from, source_kind, created_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,'registered',?,?,?,?)",
        op.photoId, target.jobId, target.id, user.id, name, src.size, src.sha256, src.takenAt, lat, lon, src.accuracyM, src.headingDeg,
        src.thumbPath, src.id, if (backupKept) "backup" else "onedrive", now,
    )
    return Applied(null, "photo.reuse", photo = photoInfo(op.photoId), kickDelivery = true)
}

private fun checkIdOp(id: String) {
    if (!PHOTO_ID.matches(id)) throw OpFail("bad_id")
}

private fun Connection.remove(ctx: Ctx, user: User, op: Op.PhotoRemove, now: Long): Applied {
    val row = ownedPhoto(op.photoId, user)
    if (row.status == PhotoStatus.removed) return Applied(null, "photo.remove")
    exec("UPDATE photos SET status = 'removed', removed_at = ?, removed_by = ? WHERE id = ?", now, user.id, op.photoId)
    // Only a photo that never reached OneDrive loses its server copy; a delivered one is merely hidden.
    var cleanup: (() -> Unit)? = null
    if (row.confirmedAt == null && !backupStillNeeded(op.photoId)) {
        exec("UPDATE photos SET backup_path = NULL, backup_purged_at = ? WHERE id = ?", now, op.photoId)
        cleanup = {
            runCatching { Files.deleteIfExists(ctx.path("photos/${op.photoId}.jpg")) }
            runCatching { Files.deleteIfExists(ctx.path("photos/${op.photoId}.part")) }
        }
    }
    return Applied(null, "photo.remove", photo = photoInfo(op.photoId), afterCommit = cleanup)
}

/**
 * True while an unremoved, undelivered reuse copy still takes its image from this photo's server copy, so the copy must
 * be kept (also by any later purge) even when the original itself was removed.
 */
internal fun Connection.backupStillNeeded(photoId: String): Boolean =
    prepareStatement("SELECT 1 FROM photos WHERE reused_from = ? AND status != 'removed' AND confirmed_at IS NULL AND status != 'confirmed' LIMIT 1").use { ps ->
        ps.setString(1, photoId); ps.executeQuery().use { it.next() }
    }

fun backupStillNeeded(ctx: Ctx, photoId: String): Boolean = ctx.db.read { backupStillNeeded(photoId) }

// ---- reuse search ----

private fun Connection.candidates(sql: String, bind: java.sql.PreparedStatement.() -> Unit, distance: (PhotoInfo, Double, Double) -> Double?): List<ReuseCandidate> =
    prepareStatement(sql).use { ps ->
        ps.bind()
        ps.executeQuery().use { rs ->
            buildList {
                while (rs.next()) {
                    val p = rs.toPhoto()
                    val d = distance(p, rs.getDouble("pt_lat"), rs.getDouble("pt_lon"))
                    add(ReuseCandidate(p, rs.getString("pt_label"), rs.getString("job_name"), d))
                }
            }
        }
    }

private const val CANDIDATE_SELECT =
    "SELECT ph.*, pt.label AS pt_label, pt.lat AS pt_lat, pt.lon AS pt_lon, j.name AS job_name " +
        "FROM photos ph JOIN points pt ON pt.id = ph.point_id JOIN jobs j ON j.id = ph.job_id " +
        "WHERE ph.status != 'removed' AND pt.deleted_at IS NULL AND j.deleted_at IS NULL "

/** Photos of any job taken within [radiusM] of a spot, nearest first. A photo without GPS counts at its point's position. */
fun nearbyPhotos(ctx: Ctx, lat: Double, lon: Double, radiusM: Double = DEFAULT_NEARBY_RADIUS_M, excludePointId: String?): List<ReuseCandidate> {
    val dLat = radiusM / METERS_PER_DEGREE
    val dLon = radiusM / (METERS_PER_DEGREE * maxOf(0.01, Math.cos(Math.toRadians(lat))))
    val here = LatLon(lat, lon)
    return ctx.db.read {
        candidates(
            CANDIDATE_SELECT +
                "AND COALESCE(ph.lat, pt.lat) BETWEEN ? AND ? AND COALESCE(ph.lon, pt.lon) BETWEEN ? AND ? AND ph.point_id != ?",
            {
                setDouble(1, lat - dLat); setDouble(2, lat + dLat); setDouble(3, lon - dLon); setDouble(4, lon + dLon)
                setString(5, excludePointId ?: "")
            },
            { p, ptLat, ptLon -> distanceMeters(here, LatLon(p.lat ?: ptLat, p.lon ?: ptLon)) },
        )
    }.filter { it.distanceM!! <= radiusM }
        .sortedWith(compareBy<ReuseCandidate> { it.distanceM }.thenByDescending { it.photo.takenAt })
        .take(NEARBY_LIMIT)
}

/** Every photo of one job, newest first. */
fun jobPhotos(ctx: Ctx, jobId: String): List<ReuseCandidate> = ctx.db.read {
    candidates(CANDIDATE_SELECT + "AND ph.job_id = ? ORDER BY ph.taken_at DESC, ph.rowid DESC", { setString(1, jobId) }, { _, _, _ -> null })
}

fun getPhoto(ctx: Ctx, photoId: String): PhotoInfo =
    ctx.db.read { photoInfo(photoId) } ?: throw HttpError(HttpStatusCode.NotFound, "Photo not found")

/** The photo's stored thumbnail or backup bytes, or null when there is none (never made, or purged). */
fun photoFile(ctx: Ctx, photoId: String, thumb: Boolean): ByteArray? {
    val row = ctx.db.read { photoRow(photoId) } ?: throw HttpError(HttpStatusCode.NotFound, "Photo not found")
    val rel = (if (thumb) row.thumbPath else row.backupPath.takeIf { row.backupPurgedAt == null }) ?: return null
    val p = ctx.path(rel)
    return if (Files.exists(p)) Files.readAllBytes(p) else null
}
