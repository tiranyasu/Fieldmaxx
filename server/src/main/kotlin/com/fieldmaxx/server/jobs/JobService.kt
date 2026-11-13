package com.fieldmaxx.server.jobs

import com.fieldmaxx.core.geo.LatLon
import com.fieldmaxx.core.geo.distanceMeters
import com.fieldmaxx.core.model.CrewMember
import com.fieldmaxx.core.model.DraftPoint
import com.fieldmaxx.core.model.ImportFlag
import com.fieldmaxx.core.model.Job
import com.fieldmaxx.core.model.JobFileInfo
import com.fieldmaxx.core.model.JobImportInput
import com.fieldmaxx.core.model.JobListItem
import com.fieldmaxx.core.model.JobSnapshot
import com.fieldmaxx.core.model.CreateJobInput
import com.fieldmaxx.core.model.Point
import com.fieldmaxx.core.model.RefImageInfo
import com.fieldmaxx.core.model.Role
import com.fieldmaxx.core.model.User
import com.fieldmaxx.server.Ctx
import com.fieldmaxx.server.HttpError
import com.fieldmaxx.server.delivery.backupMaintenance
import io.ktor.http.HttpStatusCode
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

const val RESTORE_WINDOW_MS = 30L * 24 * 3600 * 1000
const val IMPORT_TTL_MS = 24L * 3600 * 1000
private const val SAME_PLACE_M = 1.0

fun Ctx.path(rel: String): Path = config.dataDir.resolve(rel)

private fun newId() = UUID.randomUUID().toString()

private fun Connection.loadJob(jobId: String): Job? =
    prepareStatement("SELECT * FROM jobs WHERE id = ?").use { ps ->
        ps.setString(1, jobId); ps.executeQuery().use { if (it.next()) it.toJob() else null }
    }

private fun Connection.requireJob(jobId: String): Job =
    loadJob(jobId) ?: throw HttpError(HttpStatusCode.NotFound, "Job not found")

private fun Connection.activePoints(jobId: String): MutableList<Point> =
    prepareStatement("SELECT * FROM points WHERE job_id = ? AND deleted_at IS NULL").use { ps ->
        ps.setString(1, jobId)
        ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.toPoint()) }.toMutableList() }
    }

private fun checkHaveCoordinates(imports: List<JobImportInput>) {
    val missing = imports.flatMap { it.points }.filter { it.lat == null || it.lon == null }
    require(missing.isEmpty()) {
        "These points have no coordinates: ${missing.joinToString(", ") { it.label }}. Place them on the map or remove them."
    }
}

/** Creates the job with the points of every import; identical repeats are skipped and returned by label. */
fun createJob(ctx: Ctx, user: User, input: CreateJobInput): Pair<Job, List<String>> {
    val name = input.name.trim()
    require(name.isNotEmpty()) { "Job name is required" }
    checkHaveCoordinates(input.imports)
    val jobId = newId()
    val now = ctx.clock.millis()
    val url = input.fieldNotesUrl?.trim()?.ifEmpty { null }
    val (job, skipped, changes) = ctx.db.tx {
        prepareStatement(
            "INSERT INTO jobs(id, name, notes, field_notes_url, status, created_by, created_at) VALUES (?,?,?,?,'active',?,?)",
        ).use {
            it.setString(1, jobId); it.setString(2, name); it.setString(3, ""); it.setString(4, url)
            it.setString(5, user.id); it.setLong(6, now); it.executeUpdate()
        }
        val existing = mutableListOf<Point>()
        val added = mutableListOf<Point>()
        val skipped = mutableListOf<String>()
        for (imp in input.imports) {
            val (a, s) = insertImport(ctx, user, jobId, imp, existing, now)
            added += a; skipped += s
        }
        val job = requireJob(jobId)
        val changes = listOf(ctx.changes.recordInTx(this, jobId, "job", jobId, job = job)) +
            added.map { ctx.changes.recordInTx(this, jobId, "point", it.id, point = it) }
        Triple(job, skipped, changes)
    }
    changes.forEach(ctx.changes::publish)
    return job to skipped
}

/** Adds another import's points to an existing job (skipping same-label points within 1 m). */
fun addImportToJob(ctx: Ctx, user: User, jobId: String, imp: JobImportInput): Pair<List<Point>, List<String>> {
    checkHaveCoordinates(listOf(imp))
    val now = ctx.clock.millis()
    val (result, changes) = ctx.db.tx {
        val job = requireJob(jobId)
        if (job.deletedAt != null) throw HttpError(HttpStatusCode.NotFound, "Job not found")
        val before = job.notes
        val r = insertImport(ctx, user, jobId, imp, activePoints(jobId), now)
        val after = requireJob(jobId)
        val changes = buildList {
            if (after.notes != before) add(ctx.changes.recordInTx(this@tx, jobId, "job", jobId, job = after))
            r.first.forEach { add(ctx.changes.recordInTx(this@tx, jobId, "point", it.id, point = it)) }
        }
        r to changes
    }
    changes.forEach(ctx.changes::publish)
    return result
}

/** Runs inside a write transaction. [existing] is updated with every point inserted. */
private fun Connection.insertImport(
    ctx: Ctx, user: User, jobId: String, imp: JobImportInput, existing: MutableList<Point>, now: Long,
): Pair<List<Point>, List<String>> {
    val src = prepareStatement("SELECT file_name, kind, stored_path FROM imports WHERE id = ?").use { ps ->
        ps.setString(1, imp.importId)
        ps.executeQuery().use { rs -> if (rs.next()) Triple(rs.getString(1), rs.getString(2), rs.getString(3)) else null }
    } ?: throw IllegalArgumentException("That upload has expired. Upload the file again.")

    val fileId = newId()
    val rel = "jobs/$jobId/files/$fileId.${src.second}"
    val dest = ctx.path(rel)
    Files.createDirectories(dest.parent)
    Files.copy(ctx.path(src.third), dest)
    prepareStatement(
        "INSERT INTO job_files(id, job_id, kind, original_name, stored_path, layer, uploaded_by, created_at) VALUES (?,?,?,?,?,?,?,?)",
    ).use {
        it.setString(1, fileId); it.setString(2, jobId); it.setString(3, src.second); it.setString(4, src.first)
        it.setString(5, rel); it.setString(6, imp.layer); it.setString(7, user.id); it.setLong(8, now); it.executeUpdate()
    }
    prepareStatement("UPDATE ref_images SET job_id = ? WHERE import_id = ?").use {
        it.setString(1, jobId); it.setString(2, imp.importId); it.executeUpdate()
    }
    if (imp.notes.isNotBlank()) {
        prepareStatement("UPDATE jobs SET notes = CASE WHEN notes = '' THEN ? ELSE notes || char(10) || char(10) || ? END WHERE id = ?").use {
            it.setString(1, imp.notes.trim()); it.setString(2, imp.notes.trim()); it.setString(3, jobId); it.executeUpdate()
        }
    }

    val added = mutableListOf<Point>()
    val skipped = mutableListOf<String>()
    for (d in imp.points) {
        val here = LatLon(d.lat!!, d.lon!!)
        val sameLabel = existing.filter { it.label.equals(d.label, ignoreCase = true) }
        if (sameLabel.any { distanceMeters(LatLon(it.lat, it.lon), here) <= SAME_PLACE_M }) {
            skipped += d.label
            continue
        }
        val flags = if (sameLabel.isNotEmpty() && ImportFlag.duplicate_label !in d.flags) d.flags + ImportFlag.duplicate_label else d.flags
        val p = insertPoint(jobId, fileId, imp.layer.ifBlank { d.layer }, d, flags, user, now)
        existing += p; added += p
    }
    return added to skipped
}

private fun Connection.insertPoint(
    jobId: String, fileId: String, layer: String, d: DraftPoint, flags: List<ImportFlag>, user: User, now: Long,
): Point {
    val id = newId()
    prepareStatement(
        "INSERT INTO points(id, job_id, layer, type, num, label, lat, lon, description, status, ref_image_ids_json, flags_json, " +
            "source_file_id, source_page, added_by, created_at, updated_at, updated_by) VALUES (?,?,?,?,?,?,?,?,?,'open',?,?,?,?,?,?,?,?)",
    ).use {
        it.setString(1, id); it.setString(2, jobId); it.setString(3, layer); it.setString(4, d.type); it.setString(5, d.num)
        it.setString(6, d.label); it.setDouble(7, d.lat!!); it.setDouble(8, d.lon!!); it.setString(9, d.description)
        it.setString(10, encodeStrings(d.refImageIds)); it.setString(11, encodeFlags(flags)); it.setString(12, fileId)
        if (d.sourcePage == null) it.setNull(13, java.sql.Types.INTEGER) else it.setInt(13, d.sourcePage!!)
        it.setString(14, user.id); it.setLong(15, now); it.setLong(16, now); it.setString(17, user.id)
        it.executeUpdate()
    }
    return prepareStatement("SELECT * FROM points WHERE id = ?").use { ps ->
        ps.setString(1, id); ps.executeQuery().use { it.next(); it.toPoint() }
    }
}

fun listJobs(ctx: Ctx, user: User, includeFinished: Boolean, includeDeleted: Boolean): List<JobListItem> = ctx.db.read {
    val deletedToo = includeDeleted && user.role == Role.admin
    createStatement().use { st ->
        st.executeQuery(
            "SELECT j.*, " +
                "(SELECT COUNT(*) FROM points p WHERE p.job_id = j.id AND p.deleted_at IS NULL) AS total, " +
                "(SELECT COUNT(*) FROM points p WHERE p.job_id = j.id AND p.deleted_at IS NULL AND p.status IN ('done','cant_shoot')) AS done " +
                "FROM jobs j ORDER BY j.created_at DESC",
        ).use { rs ->
            buildList {
                while (rs.next()) {
                    val job = rs.toJob()
                    if (job.deletedAt != null && !deletedToo) continue
                    if (job.status == "finished" && !includeFinished) continue
                    add(JobListItem(job, rs.getInt("total"), rs.getInt("done")))
                }
            }
        }
    }
}

fun jobSnapshot(ctx: Ctx, jobId: String): JobSnapshot {
    val seq = ctx.changes.headSeq() // read first: anything recorded later is picked up by the next sync
    return ctx.db.read {
        val job = requireJob(jobId)
        fun <T> query(sql: String, map: java.sql.ResultSet.() -> T): List<T> =
            prepareStatement(sql).use { ps ->
                ps.setString(1, jobId)
                ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.map()) } }
            }
        val points = query("SELECT * FROM points WHERE job_id = ? ORDER BY created_at, rowid") { toPoint() }
        val photos = query("SELECT * FROM photos WHERE job_id = ? ORDER BY created_at, rowid") { toPhoto() }
        val refs = query("SELECT id, kind, page FROM ref_images WHERE job_id = ? ORDER BY id") {
            RefImageInfo(getString(1), getString(2), getInt(3).takeUnless { wasNull() })
        }
        val files = query("SELECT id, original_name, layer FROM job_files WHERE job_id = ? ORDER BY created_at, rowid") {
            JobFileInfo(getString(1), getString(2), getString(3) ?: "")
        }
        val crew = query(
            "SELECT u.id, u.display_name FROM users u WHERE u.id = (SELECT created_by FROM jobs WHERE id = ?1) " +
                "OR u.id IN (SELECT user_id FROM photos WHERE job_id = ?1) " +
                "OR u.id IN (SELECT added_by FROM points WHERE job_id = ?1) " +
                "OR u.id IN (SELECT updated_by FROM points WHERE job_id = ?1) ORDER BY u.display_name COLLATE NOCASE",
        ) { CrewMember(getString(1), getString(2)) }
        JobSnapshot(job, points, photos, refs, files, crew, seq)
    }
}

fun updateJob(ctx: Ctx, jobId: String, name: String?, fieldNotesUrl: String?, notes: String?): Job {
    require(name == null || name.isNotBlank()) { "Job name is required" }
    val newLink = fieldNotesUrl?.trim()?.ifEmpty { null }
    val (job, change) = ctx.db.tx {
        val j = requireJob(jobId)
        if (j.deletedAt != null) throw HttpError(HttpStatusCode.NotFound, "Job not found")
        prepareStatement(
            "UPDATE jobs SET name = COALESCE(?, name), notes = COALESCE(?, notes), " +
                "field_notes_url = CASE WHEN ? THEN ? ELSE field_notes_url END, " +
                "delivery_problem = CASE WHEN ? THEN NULL ELSE delivery_problem END WHERE id = ?",
        ).use {
            it.setString(1, name?.trim()); it.setString(2, notes)
            it.setBoolean(3, fieldNotesUrl != null); it.setString(4, newLink)
            it.setBoolean(5, newLink != null); it.setString(6, jobId)
            it.executeUpdate()
        }
        if (newLink != null) {
            // A new link may fix what made them fail: the server tries its waiting photos at once, not after their backoff.
            prepareStatement("UPDATE photos SET next_attempt_at = NULL WHERE job_id = ? AND confirmed_at IS NULL AND status IN ('registered', 'stuck')").use {
                it.setString(1, jobId); it.executeUpdate()
            }
        }
        val updated = requireJob(jobId)
        updated to ctx.changes.recordInTx(this, jobId, "job", jobId, job = updated)
    }
    ctx.changes.publish(change)
    if (newLink != null) ctx.delivery.kick()
    return job
}

fun deleteJob(ctx: Ctx, user: User, jobId: String) {
    val (_, change) = ctx.db.tx {
        val j = requireJob(jobId)
        if (j.deletedAt != null) throw HttpError(HttpStatusCode.NotFound, "Job not found")
        if (j.createdBy != user.id && user.role != Role.admin) {
            throw HttpError(HttpStatusCode.Forbidden, "Only the person who created this job, or an admin, can delete it")
        }
        prepareStatement("UPDATE jobs SET deleted_at = ?, deleted_by = ? WHERE id = ?").use {
            it.setLong(1, ctx.clock.millis()); it.setString(2, user.id); it.setString(3, jobId); it.executeUpdate()
        }
        val updated = requireJob(jobId)
        updated to ctx.changes.recordInTx(this, jobId, "job", jobId, job = updated)
    }
    ctx.changes.publish(change)
}

fun restoreJob(ctx: Ctx, user: User, jobId: String) {
    if (user.role != Role.admin) throw HttpError(HttpStatusCode.Forbidden, "Admins only")
    val (_, change) = ctx.db.tx {
        val j = requireJob(jobId)
        val deletedAt = j.deletedAt ?: throw IllegalArgumentException("That job is not deleted")
        if (ctx.clock.millis() - deletedAt > RESTORE_WINDOW_MS) {
            throw HttpError(HttpStatusCode.Gone, "This job was deleted more than 30 days ago and can't be restored")
        }
        prepareStatement("UPDATE jobs SET deleted_at = NULL, deleted_by = NULL WHERE id = ?").use {
            it.setString(1, jobId); it.executeUpdate()
        }
        val updated = requireJob(jobId)
        updated to ctx.changes.recordInTx(this, jobId, "job", jobId, job = updated)
    }
    ctx.changes.publish(change)
}

/** Permanently removes jobs deleted more than 30 days ago, with their points, photos and files. Returns how many. */
fun purgeDeletedJobs(ctx: Ctx): Int {
    val cutoff = ctx.clock.millis() - RESTORE_WINDOW_MS
    val files = mutableListOf<String>()
    val dirs = mutableListOf<String>()
    val n = ctx.db.tx {
        val thumbs = mutableSetOf<String>()
        val ids = prepareStatement("SELECT id FROM jobs WHERE deleted_at IS NOT NULL AND deleted_at < ?").use { ps ->
            ps.setLong(1, cutoff); ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } }
        }
        for (id in ids) {
            fun collect(sql: String) = prepareStatement(sql).use { ps ->
                ps.setString(1, id)
                ps.executeQuery().use { rs -> while (rs.next()) rs.getString(1)?.let(files::add) }
            }
            collect("SELECT stored_path FROM ref_images WHERE job_id = ?")
            collect("SELECT backup_path FROM photos WHERE job_id = ?")
            prepareStatement("SELECT thumb_path FROM photos WHERE job_id = ? AND thumb_path IS NOT NULL").use { ps ->
                ps.setString(1, id); ps.executeQuery().use { rs -> while (rs.next()) thumbs += rs.getString(1) }
            }
            for (table in listOf("photos", "points", "job_files", "ref_images")) {
                prepareStatement("DELETE FROM $table WHERE job_id = ?").use { it.setString(1, id); it.executeUpdate() }
            }
            prepareStatement("DELETE FROM jobs WHERE id = ?").use { it.setString(1, id); it.executeUpdate() }
            dirs += "jobs/$id"
        }
        // Reuse copies in other jobs share the original's thumbnail: keep any still in use.
        files += thumbs.filter { thumb ->
            prepareStatement("SELECT 1 FROM photos WHERE thumb_path = ? LIMIT 1").use { ps ->
                ps.setString(1, thumb); ps.executeQuery().use { !it.next() }
            }
        }
        ids.size
    }
    files.forEach { runCatching { Files.deleteIfExists(ctx.path(it)) } }
    dirs.forEach { runCatching { ctx.path(it).toFile().deleteRecursively() } }
    return n
}

/** Deletes uploads nobody turned into a job within 24 h (their loose reference images and stored file). Returns how many. */
fun sweepImports(ctx: Ctx): Int {
    val cutoff = ctx.clock.millis() - IMPORT_TTL_MS
    val files = mutableListOf<String>()
    val dirs = mutableListOf<String>()
    val n = ctx.db.tx {
        val ids = prepareStatement("SELECT id FROM imports WHERE created_at < ?").use { ps ->
            ps.setLong(1, cutoff); ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } }
        }
        for (id in ids) {
            prepareStatement("SELECT stored_path FROM ref_images WHERE import_id = ? AND job_id IS NULL").use { ps ->
                ps.setString(1, id); ps.executeQuery().use { rs -> while (rs.next()) files += rs.getString(1) }
            }
            prepareStatement("DELETE FROM ref_images WHERE import_id = ? AND job_id IS NULL").use { it.setString(1, id); it.executeUpdate() }
            prepareStatement("DELETE FROM imports WHERE id = ?").use { it.setString(1, id); it.executeUpdate() }
            dirs += "imports/$id"
        }
        ids.size
    }
    files.forEach { runCatching { Files.deleteIfExists(ctx.path(it)) } }
    dirs.forEach { runCatching { ctx.path(it).toFile().deleteRecursively() } }
    return n
}

/**
 * Hourly: sweep stale uploads, purge jobs past the 30-day restore window, and the backup upkeep (retention purge,
 * missing thumbnails, disk warning). Daemon thread; call once from main.
 */
fun startJobMaintenance(ctx: Ctx) {
    val ex = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "job-maintenance").apply { isDaemon = true } }
    ex.scheduleWithFixedDelay({
        runCatching { sweepImports(ctx); purgeDeletedJobs(ctx) }
            .onFailure { System.err.println("Job maintenance failed: ${it.message}") }
        runCatching { backupMaintenance(ctx) }
            .onFailure { System.err.println("Backup maintenance failed: ${it.message}") }
    }, 1, 60, TimeUnit.MINUTES)
}
