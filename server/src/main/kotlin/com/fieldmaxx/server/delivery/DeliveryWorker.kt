package com.fieldmaxx.server.delivery

import com.fieldmaxx.core.model.Change
import com.fieldmaxx.core.model.PhotoStatus
import com.fieldmaxx.core.model.Role
import com.fieldmaxx.core.model.User
import com.fieldmaxx.core.naming.PointKey
import com.fieldmaxx.core.retry.MAX_RETRY_DELAY_MS
import com.fieldmaxx.core.retry.nextDelayMs
import com.fieldmaxx.server.Ctx
import com.fieldmaxx.server.DeliveryKick
import com.fieldmaxx.server.HttpError
import com.fieldmaxx.server.jobs.encodeStrings
import com.fieldmaxx.server.jobs.path
import com.fieldmaxx.server.jobs.toJob
import com.fieldmaxx.server.jobs.toPoint
import com.fieldmaxx.server.photos.STUCK_AFTER_ATTEMPTS
import com.fieldmaxx.server.photos.photoInfo
import com.fieldmaxx.server.photos.renamePhotoForDelivery
import com.fieldmaxx.sharepoint.ChunkSession
import com.fieldmaxx.sharepoint.DestinationErrorCode
import com.fieldmaxx.sharepoint.DestinationException
import com.fieldmaxx.sharepoint.FolderResolution
import com.fieldmaxx.sharepoint.FolderResolver
import com.fieldmaxx.sharepoint.PhotoDestination
import io.ktor.http.HttpStatusCode
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.ResultSet
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.slf4j.LoggerFactory

/** The server delivers a phone photo itself once its server copy has waited this long without a OneDrive confirmation. */
const val TAKEOVER_AFTER_MS = 30L * 60 * 1000
const val DEFAULT_CHUNK_BYTES = 3_200_000
const val NO_LINK_PROBLEM = "Add the Field Notes link"
const val SIGN_IN_PROBLEM = "This link needs a Microsoft sign-in"
const val FORBIDDEN_PROBLEM = "The link no longer allows uploads"
private const val POLL_SECONDS = 5L
private const val RESOLVER_TTL_MS = 10L * 60 * 1000
private const val BATCH = 200
private const val MAX_RENAMES = 3
private val log = LoggerFactory.getLogger("com.fieldmaxx.server.delivery.DeliveryWorker")

/** A failure of this attempt; [jobProblem] also marks the whole job (red banner on every phone). */
private class DeliveryFailure(message: String, val jobProblem: String? = null) : Exception(message)

private enum class Outcome { CONFIRMED, FAILED, SKIPPED }

/** Where the photo's bytes come from. */
private sealed interface Source {
    class Local(val file: Path) : Source
    /** The original photo in its own job's Field Notes. */
    class Remote(val url: String, val path: String) : Source
}

private class Persisted(val uploadId: String, val offset: Long, val path: String)

/** One photo as stored right now, with its point and job. */
private class Target(rs: ResultSet) {
    val id: String = rs.getString("id")
    val jobId: String = rs.getString("job_id")
    val pointId: String = rs.getString("point_id")
    val fileName: String = rs.getString("file_name")
    val size: Long = rs.getLong("size")
    val status = PhotoStatus.valueOf(rs.getString("status"))
    val confirmed = rs.getObject("confirmed_at") != null
    val reusedFrom: String? = rs.getString("reused_from")
    val backupPath: String? = rs.getString("backup_path")?.takeIf { rs.getObject("backup_purged_at") == null }
    val session: Persisted? = rs.getString("od_upload_id")?.let { Persisted(it, rs.getLong("od_offset"), rs.getString("od_upload_path")) }
    val key = PointKey(rs.getString("layer"), rs.getString("type"), rs.getString("num"), rs.getString("label"))
    val folderPath: String? = rs.getString("folder_path")
    val url: String? = rs.getString("field_notes_url")?.takeIf { it.isNotBlank() }
    val live = rs.getObject("pt_deleted") == null && rs.getObject("job_deleted") == null
}

private const val TARGET_SELECT =
    "SELECT ph.*, pt.layer, pt.type, pt.num, pt.label, pt.folder_path, pt.deleted_at AS pt_deleted, " +
        "j.field_notes_url, j.deleted_at AS job_deleted FROM photos ph JOIN points pt ON pt.id = ph.point_id JOIN jobs j ON j.id = ph.job_id "

/**
 * Delivers to OneDrive the photos the server holds that the phones haven't: a phone photo whose server copy arrived
 * [TAKEOVER_AFTER_MS] ago without a confirmation, and every reuse copy at once. Uses the phone's rules: the point's
 * folder (found or created), the same file name, a same-name same-size file counts as delivered, a different one gets a
 * new counter. Failures retry after [nextDelayMs]; at most [concurrency] uploads run at a time. Polls every 5 s;
 * [kick] runs a round at once.
 */
class DeliveryWorker internal constructor(
    private val ctx: Ctx,
    concurrency: Int,
    private val chunkBytes: Int,
) : DeliveryKick {
    constructor(ctx: Ctx, concurrency: Int = 4) : this(ctx, concurrency, DEFAULT_CHUNK_BYTES)

    private val pool: ExecutorService = Executors.newFixedThreadPool(concurrency, daemon("delivery"))
    private val poller = Executors.newSingleThreadScheduledExecutor(daemon("delivery-poll"))
    private val kicker = Executors.newSingleThreadExecutor(daemon("delivery-kick"))
    private val kickPending = AtomicBoolean(false)
    private val started = AtomicBoolean(false)
    @Volatile private var stopped = false
    private val inFlight: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private class CachedResolver(val url: String, val resolver: FolderResolver, val createdAt: Long)
    private val resolvers = ConcurrentHashMap<String, CachedResolver>()

    fun start() {
        if (started.compareAndSet(false, true)) poller.scheduleWithFixedDelay(::safeRun, 0, POLL_SECONDS, TimeUnit.SECONDS)
    }

    fun stop() {
        stopped = true
        for (ex in listOf(poller, kicker, pool)) ex.shutdown()
        for (ex in listOf(poller, kicker, pool)) ex.awaitTermination(30, TimeUnit.SECONDS)
    }

    /** Runs a round now (coalesced: kicks during a round cause one more round). */
    override fun kick() {
        if (stopped || !kickPending.compareAndSet(false, true)) return
        try {
            kicker.execute { kickPending.set(false); safeRun() }
        } catch (_: RejectedExecutionException) {
            kickPending.set(false)
        }
    }

    private fun safeRun() {
        try {
            runOnce()
        } catch (e: Exception) {
            log.error("Delivery round failed", e)
        }
    }

    /** One round: delivers every due photo not already being delivered and waits for them. Returns how many were attempted. */
    fun runOnce(): Int {
        if (stopped) return 0
        val now = ctx.clock.millis()
        ctx.db.read { jobsWaitingForLink(now) }.forEach { setJobProblem(it, NO_LINK_PROBLEM) }
        val due = ctx.db.read { due(now) }
        val futures = due.filter { inFlight.add(it) }.mapNotNull { id ->
            try {
                pool.submit<Outcome> {
                    try {
                        if (stopped) Outcome.SKIPPED else deliver(id)
                    } finally {
                        inFlight.remove(id)
                    }
                }
            } catch (_: RejectedExecutionException) {
                inFlight.remove(id)
                null
            }
        }
        return futures.count { f -> outcome(f) != Outcome.SKIPPED }
    }

    private fun outcome(f: Future<Outcome>): Outcome = try {
        f.get()
    } catch (e: Exception) {
        log.error("Delivery task failed", e)
        Outcome.FAILED
    }

    /**
     * Photos that could be attempted now, apart from their job's link. Only photos that can actually be attempted are
     * due, so a backlog that has to wait (no link, a reuse of a photo not in OneDrive yet, a folder to pick) never
     * fills the batch and starves the rest.
     */
    private fun dueSql(select: String, linkCondition: String) =
        "SELECT $select FROM photos ph JOIN points pt ON pt.id = ph.point_id JOIN jobs j ON j.id = ph.job_id " +
            "LEFT JOIN photos o ON o.id = ph.reused_from " +
            "WHERE ph.status IN ('registered', 'stuck') AND ph.confirmed_at IS NULL AND j.deleted_at IS NULL AND pt.deleted_at IS NULL " +
            "AND (ph.next_attempt_at IS NULL OR ph.next_attempt_at <= ?) " +
            "AND ((ph.reused_from IS NULL AND ph.backup_path IS NOT NULL AND ph.backup_purged_at IS NULL AND ph.backup_received_at <= ?) " +
            // A reuse copy: its original has a server copy or is in OneDrive (or is gone, which fails visibly).
            "OR (ph.reused_from IS NOT NULL AND (o.id IS NULL OR (o.backup_path IS NOT NULL AND o.backup_purged_at IS NULL) " +
            "OR (o.confirmed_at IS NOT NULL AND o.dest_path IS NOT NULL)))) " +
            // A point with several candidate folders waits until someone picks one.
            "AND NOT (pt.folder_path IS NULL AND pt.folder_candidates_json IS NOT NULL) AND $linkCondition"

    private fun Connection.ids(sql: String, now: Long): List<String> = prepareStatement(sql).use { ps ->
        ps.setLong(1, now); ps.setLong(2, now - TAKEOVER_AFTER_MS)
        ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } }
    }

    private fun Connection.due(now: Long): List<String> = ids(
        dueSql("ph.id", "j.field_notes_url IS NOT NULL AND TRIM(j.field_notes_url) != ''") +
            " ORDER BY ph.reused_from IS NULL, COALESCE(ph.next_attempt_at, 0), ph.created_at, ph.rowid LIMIT $BATCH",
        now,
    )

    /** Jobs with photos the server would deliver now if the job had a Field Notes link. */
    private fun Connection.jobsWaitingForLink(now: Long): List<String> =
        ids(dueSql("DISTINCT ph.job_id", "(j.field_notes_url IS NULL OR TRIM(j.field_notes_url) = '')"), now)

    private fun deliver(photoId: String): Outcome {
        val t = ctx.db.read { target(photoId) } ?: return Outcome.SKIPPED
        if (t.confirmed || t.status !in setOf(PhotoStatus.registered, PhotoStatus.stuck) || !t.live || t.url == null) return Outcome.SKIPPED
        return try {
            val source = source(t) ?: return Outcome.SKIPPED
            val dest = ctx.destinations(t.url)
            val folder = folder(t, dest) ?: return Outcome.SKIPPED
            val startedNs = System.nanoTime()
            val path = send(t, dest, folder, source)
            confirm(t, path, (System.nanoTime() - startedNs) / 1_000_000)
            Outcome.CONFIRMED
        } catch (e: DestinationException) {
            val problem = when (e.code) {
                DestinationErrorCode.REQUIRES_SIGN_IN -> SIGN_IN_PROBLEM
                DestinationErrorCode.FORBIDDEN -> FORBIDDEN_PROBLEM
                else -> null
            }
            fail(t, problem ?: e.message ?: e.code.name, problem)
        } catch (e: DeliveryFailure) {
            fail(t, e.message!!, e.jobProblem)
        } catch (e: Exception) {
            log.warn("Delivering photo {} failed", t.id, e)
            fail(t, e.message ?: e.javaClass.simpleName, null)
        }
    }

    private fun Connection.target(photoId: String): Target? =
        prepareStatement(TARGET_SELECT + "WHERE ph.id = ?").use { ps ->
            ps.setString(1, photoId); ps.executeQuery().use { if (it.next()) Target(it) else null }
        }

    private fun usable(rel: String?): Path? = rel?.let(ctx::path)?.takeIf { Files.isRegularFile(it) }

    /** null: not ready yet (a reuse of a photo that isn't in OneDrive yet), try again next round without counting a failure. */
    private fun source(t: Target): Source? {
        val origId = t.reusedFrom ?: return Source.Local(usable(t.backupPath) ?: throw DeliveryFailure("The server copy of this photo is missing"))
        val orig = ctx.db.read {
            prepareStatement(TARGET_SELECT + "WHERE ph.id = ?").use { ps ->
                ps.setString(1, origId)
                ps.executeQuery().use { rs ->
                    if (!rs.next()) null
                    else Triple(Target(rs), rs.getString("dest_path"), rs.getObject("confirmed_at") != null)
                }
            }
        } ?: throw DeliveryFailure("The original photo is no longer on the server")
        val (o, destPath, confirmed) = orig
        // The original's server copy when it still has one, else the original as delivered to its own job's Field Notes.
        usable(o.backupPath)?.let { return Source.Local(it) }
        if (!confirmed || destPath == null) {
            // Listed as due because the original has a server copy, but its file is gone: fail (backoff), never spin.
            if (o.backupPath != null) throw DeliveryFailure("The original photo's server copy is missing")
            return null
        }
        val url = o.url ?: throw DeliveryFailure("The original photo's job has no Field Notes link")
        return Source.Remote(url, destPath)
    }

    private fun resolverFor(jobId: String, url: String, dest: PhotoDestination): FolderResolver {
        val now = ctx.clock.millis()
        return resolvers.compute(jobId) { _, c ->
            c?.takeIf { it.url == url && now - it.createdAt < RESOLVER_TTL_MS } ?: CachedResolver(url, FolderResolver(dest), now)
        }!!.resolver
    }

    /** The point's folder: stored, else found or created (and stored). null: several candidates, stored for someone to pick. */
    private fun folder(t: Target, dest: PhotoDestination): String? {
        t.folderPath?.let { return it }
        val resolver = resolverFor(t.jobId, t.url!!, dest)
        // One search at a time per job: the resolver's cache isn't thread-safe, and photos of one point must agree.
        synchronized(resolver) {
            ctx.db.read { pointFolder(t.pointId) }?.let { return it }
            return when (val r = resolver.resolve(t.key)) {
                is FolderResolution.Found -> storeFolder(t.pointId, r.path)
                is FolderResolution.Created -> storeFolder(t.pointId, r.path)
                is FolderResolution.Ambiguous -> {
                    storeCandidates(t.pointId, r.candidates)
                    null
                }
            }
        }
    }

    private fun Connection.pointFolder(pointId: String): String? =
        prepareStatement("SELECT folder_path FROM points WHERE id = ?").use { ps ->
            ps.setString(1, pointId); ps.executeQuery().use { if (it.next()) it.getString(1) else null }
        }

    private fun Connection.recordPoint(pointId: String): Change {
        val p = prepareStatement("SELECT * FROM points WHERE id = ?").use { ps ->
            ps.setString(1, pointId); ps.executeQuery().use { it.next(); it.toPoint() }
        }
        return ctx.changes.recordInTx(this, p.jobId, "point", p.id, point = p)
    }

    /** Stores [path] unless the point got a folder meanwhile; returns the point's folder either way. */
    private fun storeFolder(pointId: String, path: String): String {
        var change: Change? = null
        val stored = ctx.db.tx {
            val n = prepareStatement("UPDATE points SET folder_path = ?, folder_candidates_json = NULL, updated_at = ? WHERE id = ? AND folder_path IS NULL").use {
                it.setString(1, path); it.setLong(2, ctx.clock.millis()); it.setString(3, pointId); it.executeUpdate()
            }
            if (n > 0) change = recordPoint(pointId)
            pointFolder(pointId) ?: path
        }
        change?.let(ctx.changes::publish)
        return stored
    }

    private fun storeCandidates(pointId: String, candidates: List<String>) {
        val json = encodeStrings(candidates)
        var change: Change? = null
        ctx.db.tx {
            val n = prepareStatement(
                "UPDATE points SET folder_candidates_json = ?, updated_at = ? WHERE id = ? AND folder_path IS NULL " +
                    "AND (folder_candidates_json IS NULL OR folder_candidates_json != ?)",
            ).use {
                it.setString(1, json); it.setLong(2, ctx.clock.millis()); it.setString(3, pointId); it.setString(4, json); it.executeUpdate()
            }
            if (n > 0) change = recordPoint(pointId)
        }
        change?.let(ctx.changes::publish)
    }

    /**
     * Puts the photo at `<folder>/<name>` and returns that path. A same-name file of the photo's size is the photo already
     * delivered (by the phone, or an earlier attempt whose answer was lost); a different one that isn't our own partial
     * upload gets the photo a new counter. A persisted chunk session for this path is resumed.
     */
    private fun send(t: Target, dest: PhotoDestination, folder: String, source: Source): String {
        var name = t.fileName
        var local: File? = null
        var download: File? = null
        try {
            repeat(MAX_RENAMES + 1) {
                val path = "$folder/$name"
                val resume = t.session?.takeIf { it.path == path }?.let { ChunkSession(it.uploadId, it.offset) }
                val existing = dest.fileInfo(path)
                if (existing != null && existing.size == t.size) return path
                if (existing != null && resume == null) {
                    name = renamePhotoForDelivery(ctx, t.id).fileName
                    return@repeat
                }
                val file = local ?: when (source) {
                    is Source.Local -> source.file.toFile()
                    is Source.Remote -> download(t, source).also { download = it }
                }.also { local = it }
                try {
                    upload(dest, folder, path, file, resume) { persistSession(t.id, path, it) }
                } catch (e: DestinationException) {
                    if (e.code != DestinationErrorCode.EXISTS || resume != null) throw e
                    // Someone (the phone) created the name between our check and our upload.
                    if (dest.fileInfo(path)?.size == t.size) return path
                    name = renamePhotoForDelivery(ctx, t.id).fileName
                    return@repeat
                }
                val size = dest.fileInfo(path)?.size
                if (size != t.size) throw DeliveryFailure("OneDrive holds ${size ?: "no"} bytes of $path, expected ${t.size}")
                return path
            }
            throw DeliveryFailure("OneDrive keeps showing other files under this photo's name")
        } finally {
            download?.delete()
        }
    }

    /** A folder missing in OneDrive (stored on the point, then removed there) is created again; creating never overwrites. */
    private fun upload(dest: PhotoDestination, folder: String, path: String, file: File, resume: ChunkSession?, onProgress: (ChunkSession) -> Unit) {
        try {
            dest.uploadChunked(path, file, chunkBytes, resume, onProgress)
        } catch (e: DestinationException) {
            if (e.code != DestinationErrorCode.NOT_FOUND || resume != null) throw e
            dest.ensureFolder(folder)
            dest.uploadChunked(path, file, chunkBytes, null, onProgress)
        }
    }

    private fun download(t: Target, source: Source.Remote): File {
        val dir = ctx.path("delivery").also { Files.createDirectories(it) }
        val file = dir.resolve("${t.id}.download").toFile()
        try {
            try {
                file.outputStream().use { out -> ctx.destinations(source.url).download(source.path, out) }
            } catch (e: DestinationException) {
                // The original job's link failing is not this job's delivery problem.
                throw DeliveryFailure("Can't read the original photo from its job's Field Notes: ${e.message}")
            }
            if (file.length() != t.size) throw DeliveryFailure("The original photo in OneDrive is ${file.length()} bytes, expected ${t.size}")
        } catch (e: Exception) {
            file.delete()
            throw e
        }
        return file
    }

    /** Every progress report is stored before the upload goes on, so a restart resumes this file instead of meeting it as EXISTS. */
    private fun persistSession(photoId: String, path: String, s: ChunkSession) {
        ctx.db.tx {
            prepareStatement("UPDATE photos SET od_upload_id = ?, od_offset = ?, od_upload_path = ? WHERE id = ?").use {
                it.setString(1, s.uploadId); it.setLong(2, s.offset); it.setString(3, path); it.setString(4, photoId); it.executeUpdate()
            }
        }
    }

    private fun confirm(t: Target, path: String, uploadMs: Long) {
        val changes = ctx.db.tx {
            val out = mutableListOf<Change>()
            val now = ctx.clock.millis()
            // Whoever confirms first wins; a photo removed meanwhile stays removed but is remembered as delivered.
            val n = prepareStatement(
                "UPDATE photos SET status = CASE WHEN status = 'removed' THEN 'removed' ELSE 'confirmed' END, dest_path = ?, upload_ms = ?, " +
                    "confirmed_at = ?, last_error = NULL, next_attempt_at = NULL, od_upload_id = NULL, od_offset = NULL, od_upload_path = NULL " +
                    "WHERE id = ? AND confirmed_at IS NULL",
            ).use {
                it.setString(1, path); it.setLong(2, uploadMs); it.setLong(3, now); it.setString(4, t.id); it.executeUpdate()
            }
            if (n > 0) out += ctx.changes.recordInTx(this, t.jobId, "photo", t.id, photo = photoInfo(t.id))
            // Delivery works again: clear the job's banner.
            jobProblemInTx(t.jobId, null)?.let(out::add)
            out
        }
        changes.forEach(ctx.changes::publish)
    }

    private fun fail(t: Target, message: String, jobProblem: String?): Outcome {
        val changes = ctx.db.tx {
            val out = mutableListOf<Change>()
            val row = prepareStatement("SELECT status, attempts, confirmed_at FROM photos WHERE id = ?").use { ps ->
                ps.setString(1, t.id)
                ps.executeQuery().use { if (it.next()) Triple(it.getString(1), it.getInt(2), it.getObject(3) != null) else null }
            }
            if (row != null && !row.third && row.first != PhotoStatus.removed.name) {
                val attempts = row.second + 1
                val stuck = attempts >= STUCK_AFTER_ATTEMPTS
                val status = if (stuck) PhotoStatus.stuck else PhotoStatus.registered
                // A stuck photo is still retried, every 30 min.
                val wait = if (stuck) MAX_RETRY_DELAY_MS else nextDelayMs(attempts)
                prepareStatement("UPDATE photos SET status = ?, attempts = ?, last_error = ?, next_attempt_at = ? WHERE id = ?").use {
                    it.setString(1, status.name); it.setInt(2, attempts); it.setString(3, message)
                    it.setLong(4, ctx.clock.millis() + wait); it.setString(5, t.id); it.executeUpdate()
                }
                out += ctx.changes.recordInTx(this, t.jobId, "photo", t.id, photo = photoInfo(t.id))
            }
            if (jobProblem != null) jobProblemInTx(t.jobId, jobProblem)?.let(out::add)
            out
        }
        changes.forEach(ctx.changes::publish)
        return Outcome.FAILED
    }

    private fun setJobProblem(jobId: String, problem: String) {
        ctx.db.tx { jobProblemInTx(jobId, problem) }?.let(ctx.changes::publish)
    }

    /** Sets (or with null clears) the job's delivery problem; returns the change, or null when it already was so. */
    private fun Connection.jobProblemInTx(jobId: String, problem: String?): Change? {
        val n = prepareStatement("UPDATE jobs SET delivery_problem = ? WHERE id = ? AND delivery_problem IS NOT ?").use {
            it.setString(1, problem); it.setString(2, jobId); it.setString(3, problem); it.executeUpdate()
        }
        if (n == 0) return null
        val job = prepareStatement("SELECT * FROM jobs WHERE id = ?").use { ps -> ps.setString(1, jobId); ps.executeQuery().use { it.next(); it.toJob() } }
        return ctx.changes.recordInTx(this, jobId, "job", jobId, job = job)
    }
}

private fun daemon(name: String): ThreadFactory {
    val n = AtomicInteger()
    return ThreadFactory { r -> Thread(r, "$name-${n.incrementAndGet()}").apply { isDaemon = true } }
}

/** Admin "Retry now": the photo starts over (attempts 0, not stuck) and the server tries it at once if it holds it. */
fun retryPhoto(ctx: Ctx, admin: User, photoId: String) {
    if (admin.role != Role.admin) throw HttpError(HttpStatusCode.Forbidden, "Admins only")
    val change = ctx.db.tx {
        val p = photoInfo(photoId) ?: throw HttpError(HttpStatusCode.NotFound, "Photo not found")
        if (p.confirmedAt != null) throw HttpError(HttpStatusCode.Conflict, "Photo already delivered")
        if (p.status == PhotoStatus.removed) throw HttpError(HttpStatusCode.Gone, "Photo was removed")
        prepareStatement("UPDATE photos SET status = 'registered', attempts = 0, last_error = NULL, next_attempt_at = NULL WHERE id = ?").use {
            it.setString(1, photoId); it.executeUpdate()
        }
        ctx.changes.recordInTx(this, p.jobId, "photo", photoId, photo = photoInfo(photoId))
    }
    ctx.changes.publish(change)
    ctx.delivery.kick()
}
