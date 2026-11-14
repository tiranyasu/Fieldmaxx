package com.fieldmaxx.app.photos

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.WorkerParameters
import com.fieldmaxx.app.data.LocalPhotoEntity
import com.fieldmaxx.app.net.ApiException
import com.fieldmaxx.core.model.PhotoRegistration
import com.fieldmaxx.core.model.PhotoStatus
import java.io.File
import java.io.IOException
import java.security.DigestInputStream
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Step 3 of spec §6.6, "stamped" → "registered". */
class RegisterWorker internal constructor(context: Context, params: WorkerParameters, private val pipeline: PhotoPipeline) :
    CoroutineWorker(context, params) {
    constructor(context: Context, params: WorkerParameters) : this(context, params, pipelineOf(context))

    override suspend fun doWork(): Result {
        pipeline.register(inputData.photoId)
        return Result.success()
    }
}

/**
 * Tells our server about the stamped photo (its size and SHA-256, for the backup to be checked against) and keeps the
 * file name it assigns. Registering again is harmless: the server answers with the same name (the photo id is the
 * server's id). Then the OneDrive upload and the server backup are lined up, side by side. The tech is the session's
 * (R78).
 */
internal suspend fun PhotoPipeline.register(id: String) = runStep(Step.REGISTER, id) { row ->
    val stamped = row.stampedFile() ?: return@runStep restampOrStuck(row)
    val meta = row.captureMeta()
    val (size, sha256) = withContext(Dispatchers.IO) { stamped.length() to sha256(stamped) }
    val registration = PhotoRegistration(
        id = id, jobId = row.jobId, pointId = row.pointId, size = size, sha256 = sha256, takenAt = meta.takenAt, lat = meta.lat,
        lon = meta.lon, accuracyM = meta.accuracyM, headingDeg = meta.headingDeg,
    )
    val registered = try {
        api.registerPhoto(registration)
    } catch (e: IOException) {
        return@runStep Outcome.Failed(CANT_REACH_SERVER)
    } catch (e: ApiException) {
        return@runStep Outcome.Failed(e.message)
    }
    if (photos.markRegistered(id, registered.fileName, registered.backupOffset) == 0) return@runStep Outcome.Done // deleted meanwhile
    if (registered.status == PhotoStatus.confirmed) {
        // The server delivered it already (an answer lost long ago): nothing left for OneDrive.
        photos.get(id)?.let { confirmedByServer(it, null, cancelUpload = false) }
    } else {
        enqueue(Step.ONEDRIVE, id, ExistingWorkPolicy.KEEP)
    }
    enqueue(Step.BACKUP, id, ExistingWorkPolicy.KEEP)
    Outcome.Done
}

/**
 * R58: the stamped copy is gone. With the original still there the photo goes back to "saved" and is stamped again (its
 * OneDrive session dropped: the new copy is uploaded whole); with the original gone too, it is stuck.
 */
internal suspend fun PhotoPipeline.restampOrStuck(row: LocalPhotoEntity): Outcome {
    if (!File(row.originalPath).isFile) return Outcome.Stuck(MISSING_FILE)
    if (photos.restamp(row.id, row.state) == 1) enqueue(Step.STAMP, row.id, ExistingWorkPolicy.KEEP)
    return Outcome.Done
}

private const val HASH_BUFFER = 64 * 1024

internal fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    DigestInputStream(file.inputStream(), digest).use { input ->
        val buffer = ByteArray(HASH_BUFFER)
        while (input.read(buffer) >= 0) Unit
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
