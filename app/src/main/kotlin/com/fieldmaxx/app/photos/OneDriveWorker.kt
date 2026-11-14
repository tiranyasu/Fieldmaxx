package com.fieldmaxx.app.photos

import android.content.Context
import androidx.room.withTransaction
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.fieldmaxx.app.data.LocalPhotoEntity
import com.fieldmaxx.app.data.toJob
import com.fieldmaxx.app.data.toPhoto
import com.fieldmaxx.app.data.toPoint
import com.fieldmaxx.app.data.with
import com.fieldmaxx.app.net.ApiException
import com.fieldmaxx.core.model.Op
import com.fieldmaxx.core.model.Point
import com.fieldmaxx.core.naming.PointKey
import com.fieldmaxx.core.retry.BUSY_RECHECK_MS
import com.fieldmaxx.core.retry.nextDelayMs
import com.fieldmaxx.sharepoint.ChunkSession
import com.fieldmaxx.sharepoint.DestinationErrorCode
import com.fieldmaxx.sharepoint.DestinationException
import com.fieldmaxx.sharepoint.FolderResolution
import com.fieldmaxx.sharepoint.FolderResolver
import com.fieldmaxx.sharepoint.PhotoDestination
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlin.math.max
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.withLock

/** OneDrive takes a photo in chunks this big (the size proven through the share link, spec §5.6; the server's too). */
internal const val ONE_DRIVE_CHUNK_BYTES = 3_200_000

/** A photo whose names all turn out to be taken by other files gives up for this attempt after this many new counters. */
private const val MAX_RENAMES = 3

/** Steps 4–5 of spec §6.6, "registered" → "confirmed": the upload to the job's Field Notes folder, then the check there. */
class OneDriveWorker internal constructor(context: Context, params: WorkerParameters, private val pipeline: PhotoPipeline) :
    CoroutineWorker(context, params) {
    constructor(context: Context, params: WorkerParameters) : this(context, params, pipelineOf(context))

    override suspend fun doWork(): Result {
        pipeline.uploadToOneDrive(inputData.photoId)
        return Result.success()
    }
}

/**
 * Uploads the stamped photo straight to the job's Field Notes folder through its share link, holding one of the 3
 * OneDrive streams ([UploadGate]):
 * - a photo the server shows delivered (its takeover) is confirmed here without an upload (R57);
 * - no Field Notes link on the job: it waits, no attempt counted, until the link is added (R58);
 * - the folder is the point's own, else found or made by [FolderResolver] and kept on the point (`Op.PointSetFolder`);
 *   several candidates and it waits for the tech to pick one on the point sheet;
 * - then [send], and once OneDrive shows the file whole, "confirmed" and its report in one transaction (R56).
 * OneDrive's refusals (sign-in needed, no upload permission, not found) and network errors are failed attempts (R58).
 */
internal suspend fun PhotoPipeline.uploadToOneDrive(id: String) = runStep(Step.ONEDRIVE, id) { row ->
    db.photos().get(id)?.toPhoto()?.takeIf { it.delivered() }?.let { info ->
        confirmedByServer(row, info, cancelUpload = false)
        return@runStep Outcome.Done
    }
    val job = db.jobs().get(row.jobId)?.toJob() ?: return@runStep Outcome.Waiting(Wait.ROWS)
    val link = job.fieldNotesUrl?.trim()?.takeIf { it.isNotEmpty() } ?: return@runStep Outcome.Waiting(Wait.LINK)
    if (db.points().get(row.pointId) == null) return@runStep Outcome.Waiting(Wait.ROWS)
    val stamped = row.stampedFile() ?: return@runStep restampOrStuck(row)
    UploadGate.withOneDrive { deliver(id, link, stamped) }
}

private suspend fun PhotoPipeline.deliver(id: String, link: String, stamped: File): Outcome {
    // Read again: it may have been confirmed by the server, renamed, removed (R86) or deleted while it waited for a stream.
    val row = photos.get(id)?.takeIf { it.state == REGISTERED } ?: return Outcome.Done
    if (discardIfRemoved(row)) return Outcome.Done
    val point = db.points().get(row.pointId)?.toPoint() ?: return Outcome.Waiting(Wait.ROWS)
    val dest = destinations(link)
    val folder = folderOf(point, dest) ?: return Outcome.Waiting(Wait.FOLDER)
    val started = System.nanoTime()
    return when (val sent = send(dest, folder, row, stamped)) {
        // R82 rule 4: renamed under it, the photo goes round again; the check under its current name decides.
        is Sent.Delivered -> if (confirm(id, sent.fileName, sent.path, (System.nanoTime() - started) / 1_000_000)) Outcome.Done else afterMove(id)
        Sent.ServerHasIt -> {
            confirmedByServer(row, null, cancelUpload = false)
            Outcome.Done
        }
        Sent.Moved -> afterMove(id)
        Sent.Busy -> Outcome.Later(BUSY_RECHECK_MS) // the server's delivery holds the file: not a failed attempt
    }
}

/** How [send] ended, short of an error. */
private sealed interface Sent {
    /** In OneDrive, whole, at [path]. */
    data class Delivered(val fileName: String, val path: String) : Sent

    /** The server refused a new counter: it has delivered the photo itself. */
    data object ServerHasIt : Sent

    /** The photo's row changed under the upload (deleted, confirmed by the server, renamed by it). */
    data object Moved : Sent

    /** R82: OneDrive holds the photo's file for another upload session, the server's delivery of it: look again later. */
    data object Busy : Sent
}

/** A progress report found the photo's row moved on: the upload stops at that chunk. */
private class Abandoned : RuntimeException("The photo changed during its upload")

/**
 * The photo to `<folder>/<fileName>`, never overwriting anything (R57, R82, spec §6.6.1). What OneDrive holds under the
 * photo's own server-given name decides:
 * - the photo's size: the photo, already delivered (by an earlier attempt whose answer was lost, or by the server);
 * - this photo's own session stored: its upload, resumed (the destination restarts it when SharePoint lost the session);
 * - nothing: a fresh upload;
 * - an empty file: this photo's delivery by the server, in progress or abandoned (SharePoint shows a chunked upload as 0
 *   bytes until its last chunk). It is filled through a fresh session of this phone's, never renamed around; BUSY while
 *   the server's session still holds it;
 * - another size: someone else's file. The server gives the photo a new counter, compare-and-set on the name it leaves,
 *   so phone and server converge on one name.
 */
private suspend fun PhotoPipeline.send(dest: PhotoDestination, folder: String, first: LocalPhotoEntity, stamped: File): Sent {
    var row = first
    repeat(MAX_RENAMES + 1) {
        val name = row.fileName ?: return Sent.Moved
        val path = "$folder/$name"
        val own = row.oneDriveUploadId?.takeIf { row.createdThisFile }?.let { ChunkSession(it, row.oneDriveOffset) }
        val there = io { dest.fileInfo(path) }?.size
        if (there == stamped.length()) return Sent.Delivered(name, path)
        val sent = when {
            own != null -> upload(dest, folder, path, stamped, own, row.id, name)
            there == null -> upload(dest, folder, path, stamped, null, row.id, name)
            there == 0L -> adopt(dest, folder, path, stamped, row.id, name)
            else -> null
        }
        if (sent != null) return sent
        // Taken by another file: a fresh counter from the server, the stored session dropped with the old name.
        val next = try {
            api.renamePhoto(row.id, from = name).fileName
        } catch (e: ApiException) {
            if (e.status == 409) return Sent.ServerHasIt // "Photo already delivered"
            throw e
        } catch (e: IOException) {
            throw IOException(CANT_REACH_SERVER, e)
        }
        row = renamedTo(row.id, name, next) ?: return Sent.Moved
    }
    throw DestinationException(DestinationErrorCode.UNEXPECTED, "OneDrive keeps showing other files under this photo's name")
}

/** The photo under the server's [next] name (its own counter, or one the watcher already took from the server); null when it moved on. */
private suspend fun PhotoPipeline.renamedTo(id: String, name: String, next: String): LocalPhotoEntity? {
    photos.rename(id, name, next)
    return photos.get(id)?.takeIf { it.state == REGISTERED && it.fileName == next }
}

/**
 * Puts [stamped] at [path]: fresh, or [resume]d. Every chunk session it reports is on the disk before the next chunk
 * goes, with "created this file" from the first one on (R57). Returns the photo delivered (it is checked in OneDrive
 * afterwards), [Sent.Moved], [Sent.Busy], or null when another file of another size took the name first.
 */
private suspend fun PhotoPipeline.upload(
    dest: PhotoDestination,
    folder: String,
    path: String,
    stamped: File,
    resume: ChunkSession?,
    id: String,
    name: String,
): Sent? {
    val size = stamped.length()
    val onProgress = { s: ChunkSession -> if (photos.saveSession(id, name, s.uploadId, s.offset) == 0) throw Abandoned() }
    try {
        try {
            io { dest.uploadChunked(path, stamped, chunkBytes, resume, onProgress) }
        } catch (e: DestinationException) {
            if (e.code != DestinationErrorCode.NOT_FOUND || resume != null) throw e
            io { dest.ensureFolder(folder) } // the point's folder is gone from OneDrive: made again (making never overwrites)
            io { dest.uploadChunked(path, stamped, chunkBytes, null, onProgress) }
        }
    } catch (e: Abandoned) {
        return Sent.Moved
    } catch (e: DestinationException) {
        if (e.code == DestinationErrorCode.BUSY) return Sent.Busy
        if (e.code == DestinationErrorCode.EXISTS && resume == null) {
            // Someone made the name between the check and the upload (the server's takeover, say): look again.
            return when (io { dest.fileInfo(path) }?.size) {
                size -> Sent.Delivered(name, path)
                0L -> adopt(dest, folder, path, stamped, id, name)
                else -> null
            }
        }
        if (e.code == DestinationErrorCode.NOT_FOUND && resume != null) photos.forgetSession(id, name) // its partial is gone: afresh next time
        throw e
    }
    val landed = io { dest.fileInfo(path) }?.size
    if (landed != size) throw DestinationException(DestinationErrorCode.UNEXPECTED, "OneDrive holds ${landed ?: "no"} bytes of $path, expected $size")
    return Sent.Delivered(name, path)
}

/**
 * R82: an empty file under the photo's name is the photo's own delivery by the server, which never finished, or is still
 * going. This phone fills it through a session of its own, kept like any other (from here on the file counts as this
 * photo's own): no second file, and nothing overwritten, since the file holds no content.
 */
private suspend fun PhotoPipeline.adopt(dest: PhotoDestination, folder: String, path: String, stamped: File, id: String, name: String): Sent? {
    val session = ChunkSession(UUID.randomUUID().toString(), 0)
    if (io { photos.saveSession(id, name, session.uploadId, 0) } == 0) return Sent.Moved
    return upload(dest, folder, path, stamped, session, id, name)
}

/** Step 5: OneDrive has it, whole. "Confirmed" and its report in one transaction (R56): a kill leaves both or neither. False: renamed or gone meanwhile. */
private suspend fun PhotoPipeline.confirm(id: String, fileName: String, path: String, uploadMs: Long): Boolean = db.withTransaction {
    val row = photos.get(id)?.takeIf { it.state == REGISTERED && it.fileName == fileName } ?: return@withTransaction false
    sync.enqueue(Op.PhotoConfirmed("photo.confirmed:${row.id}", row.id, path, uploadMs))
    photos.markConfirmed(id, now(), KEEP_ON_PHONE_MS) == 1
}

/** The row moved on during the upload: nothing more to do, or, renamed under it, one more go after a pause (not counted). */
private suspend fun PhotoPipeline.afterMove(id: String): Outcome {
    val row = photos.get(id) ?: return Outcome.Done
    return if (row.state == REGISTERED) Outcome.Later(nextDelayMs(max(1, row.attempts))) else Outcome.Done
}

/**
 * The point's OneDrive folder: kept on the point, else found or made, and kept (spec §6.6.1). null when several folders
 * could be the point's: they go on the point, so the point sheet asks which. One search per job at a time, so photos
 * of one point agree.
 */
private suspend fun PhotoPipeline.folderOf(point: Point, dest: PhotoDestination): String? {
    point.folderPath?.let { return it }
    return folderSearch(point.jobId).withLock {
        val fresh = db.points().get(point.id)?.toPoint() ?: return@withLock null
        fresh.folderPath ?: when (val found = io { FolderResolver(dest).resolve(PointKey(fresh.layer, fresh.type, fresh.num, fresh.label)) }) {
            is FolderResolution.Found -> found.path.also { sync.enqueue(Op.PointSetFolder(UUID.randomUUID().toString(), fresh.id, it)) }
            is FolderResolution.Created -> found.path.also { sync.enqueue(Op.PointSetFolder(UUID.randomUUID().toString(), fresh.id, it)) }
            is FolderResolution.Ambiguous -> {
                offerFolders(fresh.id, found.candidates)
                null
            }
        }
    }
}

/**
 * The candidates go on this phone's copy of the point, for its sheet to ask (spec §6.6.1 "Two candidates → the point
 * screen asks once"); in one transaction, so a newer server state of the point can't be written over.
 */
private suspend fun PhotoPipeline.offerFolders(pointId: String, candidates: List<String>) = db.withTransaction {
    val row = db.points().get(pointId) ?: return@withTransaction
    val point = row.toPoint()
    if (point.folderPath == null && point.folderCandidates != candidates) db.points().upsert(row.with(point.copy(folderCandidates = candidates)))
}

/** OneDrive's calls block: off the coroutine's thread, and interrupted if the worker is stopped. */
private suspend fun <T> io(block: () -> T): T = runInterruptible(Dispatchers.IO, block)
