package com.fieldmaxx.app.photos

import android.net.Uri
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import com.fieldmaxx.app.data.AppDatabase
import com.fieldmaxx.app.data.LocalPhotoEntity
import com.fieldmaxx.app.platform.Fix
import com.fieldmaxx.core.FmxJson
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

/** Starts a saved photo on its way: stamp, register, upload (Task 28). Until then photos simply stay "saved" (R54). */
fun interface PhotoPipelineStarter {
    fun start(localPhotoId: String)
}

private const val PHOTOS_DIR = "photos"
private const val ORIGINAL = "original.jpg"
private const val META = "meta.json"
private const val STATE_SAVED = "saved"

/**
 * What `meta.json` keeps beside a saved original: all its row needs (R80). The rest of the row is what every photo has
 * at "saved", and its original is the `original.jpg` beside it.
 */
@Serializable
private data class SavedPhoto(val id: String, val jobId: String, val pointId: String, val meta: CaptureMeta, val createdAt: Long) {
    fun row(original: File) = LocalPhotoEntity(
        id = id, jobId = jobId, pointId = pointId, originalPath = original.absolutePath, stampedPath = null,
        metaJson = FmxJson.encodeToString(CaptureMeta.serializer(), meta), state = STATE_SAVED, fileName = null,
        oneDriveUploadId = null, oneDriveOffset = 0, createdThisFile = false, backupOffset = 0, backupDone = false,
        attempts = 0, nextAttemptAt = 0, lastError = null, stuck = false, createdAt = createdAt, confirmedAt = null,
    )
}

/**
 * The row a photo folder's `meta.json` ([metaFile]) was written for, as it was when the photo was saved: for Task 28's
 * sweep of folders a power cut left without their row (R80). A meta.json that can't be read was cut off as it was
 * written, before the row ever was; this throws for it (IOException or kotlinx.serialization's SerializationException).
 */
fun rowFromMeta(metaFile: File): LocalPhotoEntity =
    FmxJson.decodeFromString(SavedPhoto.serializer(), metaFile.readText()).row(File(metaFile.absoluteFile.parentFile, ORIGINAL))

/**
 * This phone's own photos, taken or picked (spec §6.6 step 1, "Saved"). Each step is on the disk before the next starts,
 * so a crash, a dead battery or a force-stop never loses a photo (R54, R80):
 * 1. the photo is written whole to `filesDir/photos/<id>/original.jpg` and synced;
 * 2. `meta.json` beside it, synced: enough to rebuild the row ([rowFromMeta]);
 * 3. the row, state "saved" (the database syncs each commit);
 * 4. only then [starter].
 *
 * A step that fails leaves neither a row nor a file behind. Once the photo is written, the rest finishes even if the
 * caller is cancelled meanwhile; once the row is in, the photo counts as saved whatever the starter does (R79).
 *
 * [now] gives the rows' createdAt and a gallery photo's time when it has none of its own; [media] reads picked photos;
 * [zone] is where a gallery photo's EXIF time, kept without an offset, was taken.
 */
class LocalPhotos(
    private val db: AppDatabase,
    filesDir: File,
    private val starter: PhotoPipelineStarter,
    private val now: () -> Long,
    private val media: PickedMedia,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
) {
    private val root = File(filesDir, PHOTOS_DIR)

    /** Saves the photo [write] puts in the file it is given (the camera's capture) with [meta]; returns its id. */
    suspend fun saveCaptured(jobId: String, pointId: String, meta: CaptureMeta, write: suspend (File) -> Unit): String {
        val id = newId()
        val original = writeOriginal(id, write)
        withContext(NonCancellable) { keep(SavedPhoto(id, jobId, pointId, meta, now()), original) }
        return id
    }

    /**
     * Copies the photo at [uri] into app storage and saves it with its own EXIF position and time where it has them
     * (R52a: whatever the platform hands over), else [fallback] (the phone's fix now) and the time now; its stamp's time
     * line ends "(from gallery)". [techId] is the signed-in tech (R78). Returns its id.
     */
    suspend fun saveFromGallery(
        jobId: String,
        pointId: String,
        uri: Uri,
        jobName: String,
        pointLabel: String,
        fallback: Fix?,
        techId: String? = null,
    ): String {
        val id = newId()
        val original = writeOriginal(id) { file ->
            withContext(Dispatchers.IO) { media.open(uri).use { input -> FileOutputStream(file).use { input.copyTo(it) } } }
        }
        withContext(NonCancellable) {
            val meta = try {
                withContext(Dispatchers.IO) { galleryMeta(readExif(original), jobName, pointLabel, fallback, now(), zone(), techId) }
            } catch (e: Throwable) {
                discard(id)
                throw e
            }
            keep(SavedPhoto(id, jobId, pointId, meta, now()), original)
        }
        return id
    }

    /** The photo [write] puts in `photos/<id>/original.jpg`, synced to the disk; nothing is left of a write that fails. */
    private suspend fun writeOriginal(id: String, write: suspend (File) -> Unit): File {
        val dir = File(root, id)
        val original = File(dir, ORIGINAL)
        try {
            withContext(Dispatchers.IO) { if (!dir.mkdirs() && !dir.isDirectory) throw IOException("Can't make a folder for the photo in app storage") }
            write(original)
            withContext(Dispatchers.IO) {
                if (original.length() == 0L) throw IOException("No photo was written")
                FileOutputStream(original, true).use { it.fd.sync() } // on the disk itself before anything says it's there
            }
            return original
        } catch (e: Throwable) {
            withContext(NonCancellable) { discard(id) }
            throw e
        }
    }

    /** meta.json, then the row, then the pipeline: a photo the database doesn't know isn't kept, and a row never lacks its file. */
    private suspend fun keep(saved: SavedPhoto, original: File) {
        try {
            withContext(Dispatchers.IO) {
                FileOutputStream(File(original.parentFile, META)).use { out ->
                    out.write(FmxJson.encodeToString(SavedPhoto.serializer(), saved).toByteArray())
                    out.fd.sync()
                }
            }
        } catch (e: Throwable) {
            discard(saved.id)
            throw e
        }
        try {
            db.localPhotos().upsert(saved.row(original))
        } catch (e: Exception) {
            val recorded = try {
                db.localPhotos().get(saved.id) != null
            } catch (_: Exception) {
                false
            }
            if (!recorded) discard(saved.id)
            throw e
        }
        try {
            starter.start(saved.id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The photo is saved: the pipeline picks it up from its row when it next resumes (Task 28's resumeAll).
            Log.w(TAG, "Photo ${saved.id} is saved, but its delivery didn't start", e)
        }
    }

    private suspend fun discard(id: String) = withContext(Dispatchers.IO) { File(root, id).deleteRecursively() }

    /** The copy's EXIF; a file it can't be read from counts as having none. */
    private fun readExif(file: File): ExifInterface? = try {
        ExifInterface(file)
    } catch (e: IOException) {
        null
    } catch (e: RuntimeException) {
        null
    }

    private fun newId(): String = UUID.randomUUID().toString()

    private companion object {
        const val TAG = "LocalPhotos"
    }
}
