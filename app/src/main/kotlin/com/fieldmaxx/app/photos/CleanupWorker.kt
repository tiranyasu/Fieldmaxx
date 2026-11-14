package com.fieldmaxx.app.photos

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.fieldmaxx.app.data.LocalPhotoEntity
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal const val CLEANUP_WORK = "photo.cleanup"
internal const val CLEANUP_EVERY_HOURS = 6L

/** A photo folder with no row and no readable meta.json is a capture that never finished once it is this old (R80). */
private const val ORPHAN_AGE_MS = 60L * 60 * 1000

/** Every 6 hours: the phone's old copies go, and the safety net runs (R55, R80). */
class CleanupWorker internal constructor(context: Context, params: WorkerParameters, private val pipeline: PhotoPipeline) :
    CoroutineWorker(context, params) {
    constructor(context: Context, params: WorkerParameters) : this(context, params, pipelineOf(context))

    override suspend fun doWork(): Result {
        pipeline.cleanUp()
        return Result.success()
    }
}

/**
 * Deletes the phone's own copies (original and stamped) and the row of each photo both confirmed in OneDrive and backed
 * up 3 days ago or more (spec §6.6 step 7; the gallery copy stays), then does what an app start does: orphan folders
 * recovered or swept, every unfinished photo lined up again (R55, R80).
 */
internal suspend fun PhotoPipeline.cleanUp() {
    for (row in photos.cleanupDue(now())) {
        deleteFiles(row)
        photos.delete(row.id)
    }
    resume()
}

/**
 * R80: each `photos/<id>/` folder that has no row. Its readable meta.json brings the row back as it was saved, and the
 * photo is started: a dead battery must never lose a photo. A folder with neither is a capture that never finished,
 * deleted once it is an hour old; a meta.json that can't be read was cut off as it was written, so it counts as none.
 * The folder of a photo deleted on this phone (its tombstone, fix round 1) is never brought back: it goes, meta.json first.
 */
internal suspend fun PhotoPipeline.sweepOrphans() {
    val folders = withContext(Dispatchers.IO) { File(filesDir, PHOTO_FOLDERS).listFiles()?.filter { it.isDirectory }.orEmpty() }
    if (folders.isEmpty()) return
    val known = photos.ids().toHashSet()
    for (folder in folders) {
        if (folder.name in known) continue
        if (tombstones.get(folder.name) != null) {
            withContext(Dispatchers.IO) {
                File(folder, META_JSON).delete()
                folder.deleteRecursively()
            }
            continue
        }
        val saved = withContext(Dispatchers.IO) { savedRow(folder) }
        if (saved != null) {
            if (photos.insertIfAbsent(saved) != -1L) start(saved.id)
        } else if (now() - withContext(Dispatchers.IO) { lastTouched(folder) } >= ORPHAN_AGE_MS) {
            withContext(Dispatchers.IO) { folder.deleteRecursively() }
        }
    }
}

/** The row [folder]'s meta.json was written for; null when there is none, or it can't be read, or it is another photo's. */
private fun savedRow(folder: File): LocalPhotoEntity? {
    val meta = File(folder, META_JSON).takeIf { it.isFile } ?: return null
    return try {
        rowFromMeta(meta).takeIf { it.id == folder.name }
    } catch (e: Exception) { // IOException, or kotlinx.serialization's SerializationException for a torn file
        null
    }
}

private fun lastTouched(folder: File): Long = (folder.listFiles().orEmpty().map { it.lastModified() } + folder.lastModified()).max()
