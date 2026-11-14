package com.fieldmaxx.app.sync

import androidx.room.withTransaction
import com.fieldmaxx.app.data.AppDatabase
import com.fieldmaxx.app.data.MetaEntity
import com.fieldmaxx.app.data.toEntity
import com.fieldmaxx.app.data.toJob
import com.fieldmaxx.app.data.toOp
import com.fieldmaxx.app.data.toPhoto
import com.fieldmaxx.app.data.toPoint
import com.fieldmaxx.app.data.with
import com.fieldmaxx.core.FmxJson
import com.fieldmaxx.core.model.CantShootReason
import com.fieldmaxx.core.model.Change
import com.fieldmaxx.core.model.JobSnapshot
import com.fieldmaxx.core.model.Op
import com.fieldmaxx.core.model.PhotoInfo
import com.fieldmaxx.core.model.PhotoStatus
import com.fieldmaxx.core.model.Point
import com.fieldmaxx.core.model.PointStatus
import com.fieldmaxx.core.model.User
import com.fieldmaxx.core.naming.pointLabel

/** Keys of the sync engine's [MetaEntity] rows. */
internal object MetaKeys {
    const val USER = "user"
    const val LAST_SEQ = "lastSeq"

    /** The server address the cached server state came from (it belongs to that server's history). */
    const val SERVER = "server"

    /** A job's [JobExtras]; the row also marks that the job's snapshot is stored on this phone. */
    const val EXTRAS = "extras:"
    fun extras(jobId: String) = EXTRAS + jobId
}

// Server rules mirrored here (server: Ops.kt, PhotoService.kt).
private const val STUCK_AFTER_ATTEMPTS = 6
private val PHOTO_ID = Regex("[A-Za-z0-9_-]{1,64}")
private const val DELETE_CHUNK = 500

/**
 * Writes to the local store, each call in one transaction.
 *
 * [applyLocally] shows an op at once, changing exactly the fields the server's op changes (last write wins per field).
 * Refusals the phone can see coming (missing or deleted point, close without a photo, "other" without text) change
 * nothing; those that depend on the role or photo owner are left to the server. Applying an op again is harmless: an
 * added point or reused photo that exists already is left alone.
 *
 * Server state goes through a seq gate: a change only replaces a row it is newer than, a snapshot only rows no newer than
 * it. After server state lands on a point or photo, the ops still waiting in the outbox for it are applied again, in
 * outbox order, so an edit that hasn't reached the server yet stays on screen. An op already sent but not yet answered
 * may have been taken by the server before a newer write that just landed; where applying it again changes the row,
 * the job is reported, for a fresh snapshot once the answer is in (R24).
 */
class OpApplier(private val db: AppDatabase, private val clock: () -> Long = System::currentTimeMillis) {

    suspend fun applyLocally(op: Op, userId: String, now: Long): Unit = db.withTransaction { apply(op, userId, now) }

    suspend fun applyChange(c: Change) {
        applyChanges(listOf(c))
    }

    /**
     * Job changes always apply. Point and photo changes apply only to jobs whose snapshot is stored here: the rest arrive
     * with [applySnapshot], so a point list is never partial. Returns the jobs where an op in [sent] (sent, not answered
     * yet) changed the server state it was applied again on.
     */
    suspend fun applyChanges(changes: List<Change>, sent: Set<String> = emptySet()): Set<String> {
        if (changes.isEmpty()) return emptySet()
        return db.withTransaction {
            val stored = storedJobs()
            val waiting = WaitingOps(sent)
            for (c in changes) {
                when (c.entity) {
                    "job" -> c.job?.let { job ->
                        val row = db.jobs().get(job.id)
                        if (row == null || c.seq > row.seq) db.jobs().upsert(job.toEntity(c.seq, clock()))
                    }
                    "point" -> c.point?.takeIf { it.jobId in stored }?.let { p ->
                        val row = db.points().get(p.id)
                        if (row == null || c.seq > row.seq) {
                            db.points().upsert(p.toEntity(c.seq))
                            waiting.applyFor(p.jobId, pointId = p.id)
                        }
                    }
                    "photo" -> c.photo?.takeIf { it.jobId in stored }?.let { ph ->
                        val row = db.photos().get(ph.id)
                        if (row == null || c.seq > row.seq) {
                            db.photos().upsert(ph.toEntity(c.seq))
                            waiting.applyFor(ph.jobId, photoId = ph.id)
                        }
                    }
                    // Anything else is an entity this version doesn't know.
                }
            }
            waiting.moved
        }
    }

    /**
     * Stores a job's snapshot (taken at [JobSnapshot.seq]) and its extras. The job's rows the snapshot doesn't list are
     * gone on the server and are removed here too, unless they are newer than the snapshot; ops still waiting for the
     * job are then applied again, which brings back a point this phone added that the server doesn't have yet. True when
     * an op in [sent] changed the snapshot's state that way, as [applyChanges] reports.
     */
    suspend fun applySnapshot(s: JobSnapshot, sent: Set<String> = emptySet()): Boolean = db.withTransaction {
        val jobId = s.job.id
        val pointIds = s.points.mapTo(HashSet()) { it.id }
        val photoIds = s.photos.mapTo(HashSet()) { it.id }
        // Decided before the rows change: which waiting ops are this job's.
        val again = db.outbox().all().map { it.toOp() }.filter { op ->
            jobOf(op)?.let { it == jobId }
                ?: (op.targetPoint?.let { it in pointIds } == true || op.targetPhoto?.let { it in photoIds } == true)
        }

        val jobRow = db.jobs().get(jobId)
        if (jobRow == null || jobRow.seq <= s.seq) db.jobs().upsert(s.job.toEntity(s.seq, clock()))

        val points = db.points().forJob(jobId).associateBy { it.id }
        db.points().upsertAll(s.points.filter { (points[it.id]?.seq ?: -1) <= s.seq }.map { it.toEntity(s.seq) })
        points.values.filter { it.id !in pointIds && it.seq <= s.seq }.map { it.id }.chunked(DELETE_CHUNK).forEach { db.points().delete(it) }

        val photos = db.photos().forJob(jobId).associateBy { it.id }
        db.photos().upsertAll(s.photos.filter { (photos[it.id]?.seq ?: -1) <= s.seq }.map { it.toEntity(s.seq) })
        photos.values.filter { it.id !in photoIds && it.seq <= s.seq }.map { it.id }.chunked(DELETE_CHUNK).forEach { db.photos().delete(it) }

        val extras = JobExtras(s.refImages, s.files, s.crew)
        db.meta().put(MetaEntity(MetaKeys.extras(jobId), FmxJson.encodeToString(JobExtras.serializer(), extras)))

        again.isNotEmpty() && reapply(again, savedUserId(), sent)
    }

    /**
     * Clears every row of server state (jobs, points, photo infos, extras with their stored-job marks, lastSeq and the
     * server it came from) in one transaction. The outbox, the photos taken here and the saved user stay. Returns the
     * jobs whose snapshot was stored.
     */
    suspend fun clearServerState(): Set<String> = db.withTransaction {
        val stored = storedJobs()
        db.jobs().deleteAll()
        db.points().deleteAll()
        db.photos().deleteAll()
        db.meta().deleteStartingWith(MetaKeys.EXTRAS)
        db.meta().delete(MetaKeys.LAST_SEQ)
        db.meta().delete(MetaKeys.SERVER)
        stored
    }

    /** The job an op belongs to, by the rows on this phone; null when they aren't here. */
    internal suspend fun jobOf(op: Op): String? = when (op) {
        is Op.PointAdd -> op.jobId
        is Op.PhotoReuse -> db.points().get(op.pointId)?.jobId
        else -> op.targetPoint?.let { db.points().get(it)?.jobId } ?: op.targetPhoto?.let { db.photos().get(it)?.jobId }
    }

    private suspend fun storedJobs(): Set<String> =
        db.meta().keysStartingWith(MetaKeys.EXTRAS).mapTo(HashSet()) { it.removePrefix(MetaKeys.EXTRAS) }

    /** Ops are applied again as the user who will send them (each tech has their own phone). */
    private suspend fun savedUserId(): String =
        db.meta().get(MetaKeys.USER)?.let { FmxJson.decodeFromString(User.serializer(), it).id }.orEmpty()

    /** The outbox, read once per transaction and only if needed; [moved] collects the jobs [reapply] reports. */
    private inner class WaitingOps(private val sent: Set<String>) {
        private var ops: List<Op>? = null
        private var userId: String? = null
        val moved = HashSet<String>()

        suspend fun applyFor(jobId: String, pointId: String? = null, photoId: String? = null) {
            val all = ops ?: db.outbox().all().map { it.toOp() }.also { ops = it }
            val mine = all.filter { (pointId != null && it.targetPoint == pointId) || (photoId != null && it.targetPhoto == photoId) }
            if (mine.isEmpty()) return
            val uid = userId ?: savedUserId().also { userId = it }
            if (reapply(mine, uid, sent)) moved += jobId
        }
    }

    /**
     * Applies [ops] again, in outbox order. True when one already [sent] still changed its row: the server state it
     * landed on doesn't have it, so the server took it before that state's write, or hasn't taken it yet.
     */
    private suspend fun reapply(ops: List<Op>, userId: String, sent: Set<String>): Boolean {
        var changed = false
        for (op in ops) {
            if (op.opId !in sent) {
                apply(op, userId, clock())
                continue
            }
            val before = rowOf(op)
            apply(op, userId, clock())
            if (rowOf(op) != before) changed = true
        }
        return changed
    }

    /** The row [op] writes, for comparing; a point's updatedAt is left out, as applying an op sets it to this phone's clock. */
    private suspend fun rowOf(op: Op): Any? =
        op.targetPoint?.let { id -> db.points().get(id)?.toPoint()?.copy(updatedAt = 0) }
            ?: op.targetPhoto?.let { id -> db.photos().get(id)?.toPhoto() }

    private suspend fun apply(op: Op, userId: String, now: Long) {
        fun Point.touched() = copy(updatedAt = now, updatedBy = userId)
        when (op) {
            is Op.PointUpdate -> editPoint(op.pointId) { it.copy(pole = op.pole ?: it.pole, comment = op.comment ?: it.comment).touched() }
            // The first move remembers where the point came from; later moves keep that original.
            is Op.PointMove -> editPoint(op.pointId) {
                it.copy(origLat = it.origLat ?: it.lat, origLon = it.origLon ?: it.lon, lat = op.lat, lon = op.lon).touched()
            }
            is Op.PointClose -> if (op.photoIds.isNotEmpty()) {
                editPoint(op.pointId) { it.copy(status = PointStatus.done, cantReason = null, cantText = null).touched() }
            }
            is Op.PointCantShoot -> {
                val text = op.text?.trim()?.ifEmpty { null }
                if (op.reason != CantShootReason.other || text != null) {
                    editPoint(op.pointId) { it.copy(status = PointStatus.cant_shoot, cantReason = op.reason, cantText = text).touched() }
                }
            }
            is Op.PointReopen -> editPoint(op.pointId) { it.copy(status = PointStatus.open, cantReason = null, cantText = null).touched() }
            is Op.PointAdd -> addPoint(op, userId, now)
            is Op.PointDelete -> editPoint(op.pointId, allowDeleted = true) { if (it.deletedAt != null) null else it.copy(deletedAt = now).touched() }
            is Op.PointRestore -> editPoint(op.pointId, allowDeleted = true) { if (it.deletedAt == null) null else it.copy(deletedAt = null).touched() }
            is Op.PointSetFolder -> editPoint(op.pointId) { it.copy(folderPath = op.folderPath, folderCandidates = null).touched() }
            is Op.PhotoReuse -> reusePhoto(op, userId)
            is Op.PhotoRemove -> editPhoto(op.photoId) { if (it.status == PhotoStatus.removed) null else it.copy(status = PhotoStatus.removed) }
            is Op.PhotoConfirmed -> editPhoto(op.photoId) {
                // A photo removed meanwhile stays removed, but it did reach OneDrive.
                if (it.confirmedAt != null) null
                else it.copy(
                    status = if (it.status == PhotoStatus.removed) PhotoStatus.removed else PhotoStatus.confirmed,
                    destPath = op.destPath, uploadMs = op.uploadMs, confirmedAt = now, lastError = null,
                )
            }
            is Op.PhotoFailed -> editPhoto(op.photoId) {
                if (it.confirmedAt != null || it.status == PhotoStatus.removed) null
                else it.copy(status = if (op.attempts >= STUCK_AFTER_ATTEMPTS) PhotoStatus.stuck else PhotoStatus.registered, lastError = op.error)
            }
        }
    }

    /** As the server's livePoint: the point is here, its job isn't deleted, and neither is it (unless [allowDeleted]). */
    private suspend fun editPoint(id: String, allowDeleted: Boolean = false, edit: (Point) -> Point?) {
        val row = db.points().get(id) ?: return
        val p = row.toPoint()
        if (jobDeleted(p.jobId) || (p.deletedAt != null && !allowDeleted)) return
        edit(p)?.let { db.points().upsert(row.with(it)) }
    }

    private suspend fun editPhoto(id: String, edit: (PhotoInfo) -> PhotoInfo?) {
        val row = db.photos().get(id) ?: return
        edit(row.toPhoto())?.let { db.photos().upsert(row.with(it)) }
    }

    private suspend fun jobDeleted(jobId: String) = db.jobs().get(jobId)?.toJob()?.deletedAt != null

    private suspend fun addPoint(op: Op.PointAdd, userId: String, now: Long) {
        val job = db.jobs().get(op.jobId)?.toJob() ?: return
        if (job.deletedAt != null || db.points().get(op.pointId) != null) return
        val p = Point(
            id = op.pointId, jobId = op.jobId, layer = op.layer, type = op.type, num = op.num, label = pointLabel(op.layer, op.type, op.num),
            lat = op.lat, lon = op.lon, origLat = null, origLon = null, description = "", status = PointStatus.open, cantReason = null,
            cantText = null, pole = null, comment = "", folderPath = null, folderCandidates = null, refImageIds = emptyList(),
            flags = emptyList(), addedBy = userId, updatedAt = now, updatedBy = userId, deletedAt = null,
        )
        db.points().upsert(p.toEntity(seq = 0))
    }

    private suspend fun reusePhoto(op: Op.PhotoReuse, userId: String) {
        if (!PHOTO_ID.matches(op.photoId) || db.photos().get(op.photoId) != null) return
        val target = db.points().get(op.pointId)?.toPoint() ?: return
        if (target.deletedAt != null || jobDeleted(target.jobId)) return
        // Only a source photo stored here can be copied ahead of the server; otherwise the copy comes with its change.
        val src = db.photos().get(op.fromPhotoId)?.toPhoto()?.takeIf { it.status != PhotoStatus.removed } ?: return
        val srcPoint = db.points().get(src.pointId)?.toPoint()
        val copy = PhotoInfo(
            id = op.photoId, jobId = target.jobId, pointId = target.id, userId = userId, fileName = null, size = src.size,
            status = PhotoStatus.registered, destPath = null, lastError = null, takenAt = src.takenAt,
            lat = src.lat ?: srcPoint?.lat, lon = src.lon ?: srcPoint?.lon, backedUp = false, confirmedAt = null, uploadMs = null,
            reusedFrom = src.id,
        )
        db.photos().upsert(copy.toEntity(seq = 0))
    }
}

/** The point an op writes, or null for a photo op. */
private val Op.targetPoint: String?
    get() = when (this) {
        is Op.PointUpdate -> pointId
        is Op.PointMove -> pointId
        is Op.PointClose -> pointId
        is Op.PointCantShoot -> pointId
        is Op.PointReopen -> pointId
        is Op.PointAdd -> pointId
        is Op.PointDelete -> pointId
        is Op.PointRestore -> pointId
        is Op.PointSetFolder -> pointId
        is Op.PhotoReuse, is Op.PhotoRemove, is Op.PhotoConfirmed, is Op.PhotoFailed -> null
    }

/** The photo an op writes, or null for a point op. */
private val Op.targetPhoto: String?
    get() = when (this) {
        is Op.PhotoReuse -> photoId
        is Op.PhotoRemove -> photoId
        is Op.PhotoConfirmed -> photoId
        is Op.PhotoFailed -> photoId
        else -> null
    }
