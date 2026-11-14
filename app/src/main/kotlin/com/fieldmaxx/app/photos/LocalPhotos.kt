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
import java.io.InputStream
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Starts a saved photo on its way: stamp, register, upload (Task 28). Until then photos simply stay "saved" (R54). */
fun interface PhotoPipelineStarter {
    fun start(localPhotoId: String)
}

/** A photo picked from the gallery, as the phone's storage gives it. */
interface PickedMedia {
    /** Whether the app may read the locations stored in photos (R52); without it Android hides them. */
    fun locationAllowed(): Boolean

    /** The picked photo's bytes: its original, location included, where [locationAllowed] and the uri supports that. */
    fun open(uri: Uri): InputStream
}

/**
 * This phone's own photos, taken or picked (spec §6.6 step 1, "Saved"): each one is written whole to app storage, at
 * `filesDir/photos/<id>/original.jpg` and flushed to the disk, then recorded in the database with state "saved", and only
 * then handed to [starter]. Each step persists before the next starts, so a crash, a dead battery or a force-stop never
 * loses a photo; a step that fails leaves neither a row nor a partial file behind (R54). Once the photo is written,
 * recording and starting it finish even if the caller is cancelled meanwhile.
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
        withContext(NonCancellable) { recordAndStart(id, jobId, pointId, original, meta) }
        return id
    }

    /**
     * Copies the photo at [uri] into app storage and saves it with its own EXIF position and time where it has them,
     * else [fallback] (the phone's fix now) and the time now; its stamp's time line ends "(from gallery)". Returns its id.
     */
    suspend fun saveFromGallery(jobId: String, pointId: String, uri: Uri, jobName: String, pointLabel: String, fallback: Fix?): String {
        val id = newId()
        val locationAllowed = media.locationAllowed()
        val original = writeOriginal(id) { file ->
            withContext(Dispatchers.IO) { media.open(uri).use { input -> FileOutputStream(file).use { input.copyTo(it) } } }
        }
        withContext(NonCancellable) {
            val meta = try {
                withContext(Dispatchers.IO) { galleryMeta(readExif(original), jobName, pointLabel, fallback, locationAllowed, now(), zone()) }
            } catch (e: Throwable) {
                discard(id)
                throw e
            }
            recordAndStart(id, jobId, pointId, original, meta)
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
                FileOutputStream(original, true).use { it.fd.sync() } // on the disk itself before the row says it's there
            }
            return original
        } catch (e: Throwable) {
            withContext(NonCancellable) { discard(id) }
            throw e
        }
    }

    /** The row first, then the pipeline: a photo the database doesn't know isn't kept, and a row never lacks its file. */
    private suspend fun recordAndStart(id: String, jobId: String, pointId: String, original: File, meta: CaptureMeta) {
        val row = LocalPhotoEntity(
            id = id, jobId = jobId, pointId = pointId, originalPath = original.absolutePath, stampedPath = null,
            metaJson = FmxJson.encodeToString(CaptureMeta.serializer(), meta), state = STATE_SAVED, fileName = null,
            oneDriveUploadId = null, oneDriveOffset = 0, createdThisFile = false, backupOffset = 0, backupDone = false,
            attempts = 0, nextAttemptAt = 0, lastError = null, stuck = false, createdAt = now(), confirmedAt = null,
        )
        try {
            db.localPhotos().upsert(row)
        } catch (e: Exception) {
            val recorded = try {
                db.localPhotos().get(id) != null
            } catch (_: Exception) {
                false
            }
            if (!recorded) discard(id)
            throw e
        }
        try {
            starter.start(id)
        } catch (e: RuntimeException) {
            // The photo is saved: the pipeline picks it up from its row when it next resumes (Task 28).
            Log.w(TAG, "Photo $id is saved, but its delivery didn't start", e)
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
        const val PHOTOS_DIR = "photos"
        const val ORIGINAL = "original.jpg"
        const val STATE_SAVED = "saved"
    }
}
