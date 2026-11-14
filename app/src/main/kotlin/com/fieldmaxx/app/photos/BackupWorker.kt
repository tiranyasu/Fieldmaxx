package com.fieldmaxx.app.photos

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.WorkerParameters
import com.fieldmaxx.app.data.toPhoto
import com.fieldmaxx.app.net.ApiException
import com.fieldmaxx.core.retry.nextDelayMs
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import kotlin.math.min
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/** The server takes backup chunks of up to 2 MB; smaller ones lose less to a dropped signal. */
internal const val BACKUP_CHUNK_BYTES = 1 shl 20

/** The server answers with 410 a removed photo's backup ("Photo was removed"), or a deleted job's registration. */
internal const val GONE = 410

/** Step 6 of spec §6.6: the server's backup copy. */
class BackupWorker internal constructor(context: Context, params: WorkerParameters, private val pipeline: PhotoPipeline) :
    CoroutineWorker(context, params) {
    constructor(context: Context, params: WorkerParameters) : this(context, params, pipelineOf(context))

    override suspend fun doWork(): Result {
        pipeline.backUp(inputData.photoId, inputData.getInt(KEY_ATTEMPT, 0))
        return Result.success()
    }
}

/**
 * Sends the stamped photo to `PUT /api/photos/{id}/backup` in resumable chunks, from where the server's copy ends, so a
 * photo reaches safety as soon as there is any signal, whatever becomes of its OneDrive upload (spec §6.6 step 6). One
 * backup at a time, and after OneDrive (R59). Its failures are its own: they never count toward the photo's OneDrive
 * attempts; it tries again after [nextDelayMs] of its own count ([attempt]), every 30 min at most. A photo whose job or
 * point is deleted waits as its other steps do (R96, R67), unless it is in OneDrive already: then it follows the normal
 * cleanup. A 410 is the server's "removed" (R97): a photo not in OneDrive yet is discarded as R86 has it, and its OneDrive
 * step with it; one in OneDrive already is only hidden, so its backup is simply over.
 */
internal suspend fun PhotoPipeline.backUp(id: String, attempt: Int) {
    val row = photos.get(id) ?: return
    if (finishIfDeleted(row) || discardIfRemoved(row)) return // deleted here (fix round 1), removed on the server (R86)
    if (row.backupDone || row.fileName == null) return // registering starts it
    if (row.state != CONFIRMED && jobDeleted(row)) {
        waitFor(row, Wait.JOB_DELETED)
        return
    }
    if (row.state != CONFIRMED && pointDeleted(row)) {
        waitFor(row, Wait.DELETED)
        return
    }
    val stamped = row.stampedFile()
    if (stamped == null) {
        if (row.state != CONFIRMED) restampOrStuck(row) // the delivery steps record what they find; this only makes a new copy
        return
    }
    try {
        UploadGate.backup.withPermit { sendBackup(id, stamped, row.backupOffset) }
        return
    } catch (e: CancellationException) {
        throw e
    } catch (e: ApiException) {
        if (e.status == GONE) {
            val current = photos.get(id)
            if (current != null && current.state != CONFIRMED && db.photos().get(id)?.toPhoto()?.delivered() != true) {
                withContext(NonCancellable) { discard(current) } // never delivered: meta.json, folder, row; its OneDrive step stops
            } else {
                photos.markBackedUp(id, null, now(), KEEP_ON_PHONE_MS) // in OneDrive, then hidden: no copy will ever be taken
            }
            return
        }
    } catch (e: Exception) {
        // No signal, the server busy, a checksum it refused: tried again below.
    }
    currentCoroutineContext().ensureActive() // stopped: WorkManager runs it again
    enqueue(Step.BACKUP, id, ExistingWorkPolicy.APPEND_OR_REPLACE, nextDelayMs(attempt + 1), attempt + 1)
}

/**
 * Chunks from [from] until the server's copy is complete (size and SHA-256 checked there). A wrong offset is answered
 * with the server's own position, and the upload goes on from it; a final chunk sent again after the copy is complete
 * is answered "complete" (409), which counts. Returns once nothing is left to send.
 */
private suspend fun PhotoPipeline.sendBackup(id: String, stamped: File, from: Long) = withContext(Dispatchers.IO) {
    RandomAccessFile(stamped, "r").use { file ->
        val size = file.length()
        var offset = from.coerceIn(0, size)
        var finished = false
        while (!finished) {
            UploadGate.oneDriveFirst()
            val bytes = ByteArray(min(backupChunkBytes.toLong(), size - offset).toInt())
            file.seek(offset)
            file.readFully(bytes)
            val (at, complete) = api.backupChunk(id, offset, bytes)
            when {
                complete -> {
                    photos.markBackedUp(id, at, now(), KEEP_ON_PHONE_MS)
                    finished = true
                }
                at == offset -> throw IOException("The server kept none of the chunk")
                photos.saveBackupOffset(id, at) == 0 -> finished = true // deleted meanwhile, or backed up some other way
                else -> offset = at.coerceIn(0, size)
            }
        }
    }
}
