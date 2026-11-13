package com.fieldmaxx.server.exports

import com.fieldmaxx.core.model.FinishResult
import com.fieldmaxx.core.model.User
import com.fieldmaxx.server.Ctx
import com.fieldmaxx.server.HttpError
import com.fieldmaxx.server.jobs.toJob
import com.fieldmaxx.sharepoint.DestinationErrorCode
import com.fieldmaxx.sharepoint.DestinationException
import io.ktor.http.HttpStatusCode

private val UNSAFE = Regex("""[\\/:*?"<>|\r\n]""")

/**
 * Finishing writes the pole spreadsheet into the Field Notes root, then marks the job finished. The upload happens
 * outside any transaction and never replaces a file: a taken name moves on to `_2`, `_3`...
 */
fun finishJob(ctx: Ctx, user: User, jobId: String): FinishResult {
    val job = ctx.db.read {
        prepareStatement("SELECT * FROM jobs WHERE id = ?").use { ps ->
            ps.setString(1, jobId)
            ps.executeQuery().use { if (it.next()) it.toJob() else null }
        }
    }?.takeIf { it.deletedAt == null } ?: throw HttpError(HttpStatusCode.NotFound, "Job not found")
    val url = job.fieldNotesUrl?.takeIf { it.isNotBlank() } ?: throw HttpError(HttpStatusCode.BadRequest, "Add the Field Notes link first")

    val (pointsLeft, photosPending) = ctx.db.read {
        fun count(sql: String) = prepareStatement(sql).use { ps ->
            ps.setString(1, jobId); ps.executeQuery().use { it.next(); it.getInt(1) }
        }
        count("SELECT COUNT(*) FROM points WHERE job_id = ? AND deleted_at IS NULL AND status = 'open'") to
            count("SELECT COUNT(*) FROM photos WHERE job_id = ? AND status NOT IN ('confirmed', 'removed')")
    }
    if (pointsLeft > 0 || photosPending > 0) return FinishResult(false, pointsLeft, photosPending)

    val bytes = poleDataXlsx(poleDataRows(ctx, jobId))
    val base = job.name.replace(UNSAFE, "_").trim().ifEmpty { "job" } + "_pole_data"
    try {
        val dest = ctx.destinations(url)
        var n = 1
        while (true) {
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
    val change = ctx.db.tx {
        prepareStatement("UPDATE jobs SET status = 'finished', finished_at = ? WHERE id = ?").use {
            it.setLong(1, now); it.setString(2, jobId); it.executeUpdate()
        }
        val finished = prepareStatement("SELECT * FROM jobs WHERE id = ?").use { ps ->
            ps.setString(1, jobId); ps.executeQuery().use { it.next(); it.toJob() }
        }
        ctx.changes.recordInTx(this, jobId, "job", jobId, job = finished)
    }
    ctx.changes.publish(change)
    return FinishResult(true)
}
