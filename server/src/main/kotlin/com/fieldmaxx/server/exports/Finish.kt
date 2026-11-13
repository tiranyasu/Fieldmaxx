package com.fieldmaxx.server.exports

import com.fieldmaxx.core.model.FinishResult
import com.fieldmaxx.core.model.User
import com.fieldmaxx.server.Ctx
import com.fieldmaxx.server.HttpError
import com.fieldmaxx.server.jobs.toJob
import com.fieldmaxx.sharepoint.DestinationErrorCode
import com.fieldmaxx.sharepoint.DestinationException
import io.ktor.http.HttpStatusCode

internal val UNSAFE = Regex("""[\\/:*?"<>|\r\n]""")

/**
 * Finishing writes the pole spreadsheet into the Field Notes root, then marks the job finished. The upload happens
 * outside any transaction and never replaces a file: a taken name moves on to `_2`, `_3`...
 */
fun finishJob(ctx: Ctx, @Suppress("UNUSED_PARAMETER") user: User, jobId: String): FinishResult =
    // One finish per job at a time: a second caller waits, then sees the job finished and gets 409 before any upload.
    synchronized(finishLocks.computeIfAbsent(jobId) { Any() }) { finishJobLocked(ctx, jobId) }

private val finishLocks = java.util.concurrent.ConcurrentHashMap<String, Any>()
private const val MAX_NAME_TRIES = 100

private fun alreadyFinished() = HttpError(HttpStatusCode.Conflict, "This job is already finished")

private fun java.sql.Connection.counts(jobId: String): Pair<Int, Int> {
    fun count(sql: String) = prepareStatement(sql).use { ps ->
        ps.setString(1, jobId); ps.executeQuery().use { it.next(); it.getInt(1) }
    }
    return count("SELECT COUNT(*) FROM points WHERE job_id = ? AND deleted_at IS NULL AND status = 'open'") to
        count("SELECT COUNT(*) FROM photos WHERE job_id = ? AND status NOT IN ('confirmed', 'removed')")
}

private fun finishJobLocked(ctx: Ctx, jobId: String): FinishResult {
    val job = ctx.db.read {
        prepareStatement("SELECT * FROM jobs WHERE id = ?").use { ps ->
            ps.setString(1, jobId)
            ps.executeQuery().use { if (it.next()) it.toJob() else null }
        }
    }?.takeIf { it.deletedAt == null } ?: throw HttpError(HttpStatusCode.NotFound, "Job not found")
    if (job.status == "finished") throw alreadyFinished()
    val url = job.fieldNotesUrl?.takeIf { it.isNotBlank() } ?: throw HttpError(HttpStatusCode.BadRequest, "Add the Field Notes link first")

    val (pointsLeft, photosPending) = ctx.db.read { counts(jobId) }
    if (pointsLeft > 0 || photosPending > 0) return FinishResult(false, pointsLeft, photosPending)

    val bytes = poleDataXlsx(poleDataRows(ctx, jobId))
    val base = job.name.replace(UNSAFE, "_").trim().ifEmpty { "job" } + "_pole_data"
    try {
        val dest = ctx.destinations(url)
        var n = 1
        while (true) {
            if (n > MAX_NAME_TRIES) throw HttpError(HttpStatusCode.BadGateway, "Too many existing pole data exports in Field Notes")
            val name = if (n == 1) "$base.xlsx" else "${base}_$n.xlsx"
            n++
            if (dest.fileInfo(name) != null) continue
            try {
                dest.upload(name, bytes)
                break
            } catch (e: DestinationException) {
                if (e.code != DestinationErrorCode.EXISTS) throw e
            }
        }
    } catch (e: DestinationException) {
        throw HttpError(HttpStatusCode.BadGateway, e.message ?: "Could not write to Field Notes")
    }

    val now = ctx.clock.millis()
    // Re-check inside the commit: points or photos may have changed during the upload. The spreadsheet then stays as a stale snapshot.
    val outcome = ctx.db.tx {
        val (left, pending) = counts(jobId)
        if (left > 0 || pending > 0) return@tx FinishResult(false, left, pending) to null
        val changed = prepareStatement("UPDATE jobs SET status = 'finished', finished_at = ? WHERE id = ? AND status = 'active'").use {
            it.setLong(1, now); it.setString(2, jobId); it.executeUpdate()
        }
        if (changed == 0) throw alreadyFinished()
        val finished = prepareStatement("SELECT * FROM jobs WHERE id = ?").use { ps ->
            ps.setString(1, jobId); ps.executeQuery().use { it.next(); it.toJob() }
        }
        FinishResult(true) to ctx.changes.recordInTx(this, jobId, "job", jobId, job = finished)
    }
    outcome.second?.let(ctx.changes::publish)
    return outcome.first
}
