package com.fieldmaxx.app.photos

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

/** The gallery album the copies go to (spec §6.6 step 2, R60). */
internal const val GALLERY_ALBUM = "Fieldmaxx"

private val GALLERY_COPY = booleanPreferencesKey("galleryCopy")
private val NAME_TIME = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss", Locale.US)
private val NOT_A_NAME = Regex("[^A-Za-z0-9-]+")

/**
 * This phone's photo settings (R60): [galleryCopy], a copy of each stamped photo in the gallery album "Fieldmaxx" as an
 * extra backup, on by default. Task 30's settings screen toggles it. A store that can't be read gives the default.
 */
class PhotoSettings(private val store: DataStore<Preferences>) {
    val galleryCopy: Flow<Boolean> = store.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { it[GALLERY_COPY] ?: true }

    suspend fun setGalleryCopy(on: Boolean) {
        store.edit { it[GALLERY_COPY] = on }
    }
}

/**
 * Puts a copy of a stamped photo in the phone's gallery, album "Fieldmaxx" (R60). From Android 10, MediaStore takes it with
 * no permission at all (Pictures/Fieldmaxx). Android 9 needs WRITE_EXTERNAL_STORAGE, declared for it alone and never asked
 * for: without it the copy is skipped. [save] never throws: a gallery copy must never hold a photo back.
 */
class GallerySaver internal constructor(
    private val resolver: ContentResolver,
    private val sdk: Int,
    private val legacyGranted: () -> Boolean,
    private val pictures: () -> File = ::publicPictures,
) {
    constructor(context: Context) : this(context.applicationContext, Build.VERSION.SDK_INT)

    private constructor(app: Context, sdk: Int) : this(app.contentResolver, sdk, { legacyWriteGranted(app) })

    /** True when the copy is in the gallery. */
    fun save(photo: File, displayName: String): Boolean = try {
        if (sdk >= Build.VERSION_CODES.Q) saveToMediaStore(photo, displayName) else saveToPictures(photo, displayName)
    } catch (e: Exception) {
        Log.w(TAG, "The gallery copy of ${photo.name} failed", e)
        false
    }

    @SuppressLint("InlinedApi") // RELATIVE_PATH and IS_PENDING are only used from Android 10 (sdk is Build.VERSION.SDK_INT)
    private fun saveToMediaStore(photo: File, displayName: String): Boolean {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$GALLERY_ALBUM")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return false
        try {
            val out = resolver.openOutputStream(uri) ?: throw IOException("The gallery gave no file to write")
            out.use { stream -> photo.inputStream().use { it.copyTo(stream) } }
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            return true
        } catch (e: Exception) {
            resolver.delete(uri, null, null) // no half photo left in the gallery
            throw e
        }
    }

    /** Android 9: the file itself in Pictures/Fieldmaxx, then MediaStore told where it is. */
    @Suppress("DEPRECATION") // MediaColumns.DATA is how Android 9 learns of a file
    private fun saveToPictures(photo: File, displayName: String): Boolean {
        if (!legacyGranted()) return false
        val album = File(pictures(), GALLERY_ALBUM)
        if (!album.mkdirs() && !album.isDirectory) throw IOException("Can't make the gallery album")
        val name = displayName.removeSuffix(".jpg")
        val target = generateSequence(0) { it + 1 }.map { n -> File(album, if (n == 0) "$name.jpg" else "${name}_$n.jpg") }.first { !it.exists() }
        photo.copyTo(target)
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, target.name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.DATA, target.absolutePath)
        }
        resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        return true
    }

    private companion object {
        const val TAG = "GallerySaver"
    }
}

/** "A05J9XE_POLE_1_20261105_133547.jpg": which photo it is, readable in the gallery. */
internal fun galleryName(meta: CaptureMeta, zone: ZoneId): String {
    fun part(s: String) = s.replace(NOT_A_NAME, "_").trim('_')
    return "${part(meta.jobName)}_${part(meta.pointLabel)}_${NAME_TIME.format(Instant.ofEpochMilli(meta.takenAt).atZone(zone))}.jpg"
}

private fun legacyWriteGranted(app: Context) =
    ContextCompat.checkSelfPermission(app, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

@Suppress("DEPRECATION") // the public Pictures folder, for Android 9 only
private fun publicPictures(): File = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
