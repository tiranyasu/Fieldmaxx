package com.fieldmaxx.app.photos

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.WorkerParameters
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * One photo is stamped at a time: a 16 MP photo takes 64 MB as a bitmap, and photos taken in a row would otherwise all
 * be decoded at once.
 */
private val stamping = Mutex()

/** Step 2 of spec §6.6, "saved" → "stamped". It needs no network, so it runs at once. */
class StampWorker internal constructor(context: Context, params: WorkerParameters, private val pipeline: PhotoPipeline) :
    CoroutineWorker(context, params) {
    constructor(context: Context, params: WorkerParameters) : this(context, params, pipelineOf(context))

    override suspend fun doWork(): Result {
        pipeline.stamp(inputData.photoId)
        return Result.success()
    }
}

/**
 * The upload copy, `stamped.jpg` beside the original ([Stamper], at the server's photo quality, in the overlay's time
 * zone), then the registration lined up and, with the setting on, a copy in the gallery (which never holds the photo
 * back). An original that is gone, or that no decoder can read, leaves the photo stuck with the reason (R58).
 */
internal suspend fun PhotoPipeline.stamp(id: String) = runStep(Step.STAMP, id) { row ->
    val original = File(row.originalPath)
    if (!original.isFile) return@runStep Outcome.Stuck(MISSING_FILE)
    val stamped = File(original.parentFile, STAMPED_JPG)
    val meta = row.captureMeta()
    val quality = quality()
    try {
        stamping.withLock { withContext(Dispatchers.IO) { Stamper.stamp(original, stamped, meta, zone(), quality) } }
    } catch (e: UnreadablePhotoException) {
        return@runStep Outcome.Stuck(e.message ?: "This photo can't be read")
    }
    if (photos.markStamped(id, stamped.path) == 0) return@runStep Outcome.Done // deleted meanwhile
    enqueue(Step.REGISTER, id, ExistingWorkPolicy.KEEP)
    copyToGallery(stamped, meta)
    Outcome.Done
}

private suspend fun PhotoPipeline.copyToGallery(stamped: File, meta: CaptureMeta) {
    try {
        if (galleryCopy()) withContext(Dispatchers.IO) { gallery.save(stamped, galleryName(meta, zone())) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w("StampWorker", "No gallery copy of ${stamped.parentFile?.name}", e)
    }
}
