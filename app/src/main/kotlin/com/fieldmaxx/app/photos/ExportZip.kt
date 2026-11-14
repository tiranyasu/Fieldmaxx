package com.fieldmaxx.app.photos

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.fieldmaxx.app.data.AppDatabase
import com.fieldmaxx.app.data.LocalPhotoEntity
import com.fieldmaxx.app.data.toJob
import com.fieldmaxx.app.data.toPhoto
import com.fieldmaxx.app.data.toPoint
import com.fieldmaxx.app.point.plannedFolder
import com.fieldmaxx.core.model.PhotoStatus
import com.fieldmaxx.core.model.Point
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

const val NOTHING_TO_EXPORT = "No photos on this phone are waiting for OneDrive."

private const val ZIP_TYPE = "application/zip"

/**
 * Characters Windows and zip tools refuse in a name, as the server's photos ZIP replaces them (exports/PhotosZip.kt).
 * The pole data and server ZIP exports name their files with it too (jobs/ExportPoleData.kt).
 */
internal val UNSAFE = Regex("""[\\/:*?"<>|\r\n]""")

/** A path's safe segments: no empty, `.` or `..` parts, nothing a zip tool or Windows would refuse. */
private fun safeSegments(path: String): List<String> =
    path.split('/', '\\').map { it.replace(UNSAFE, "_").trim() }.filter { it.isNotEmpty() && it.any { c -> c != '.' } }

/**
 * Writes this phone's [photos] not yet in OneDrive into the ZIP [out], laid out like the job's Field Notes folder (spec
 * §6.6.2's safety net): `<the point's folder, or the one that would be made>/<the server's file name, or the photo's id>.jpg`.
 * Each goes as its stamped copy, or as taken while it waits for its stamp. Photos in OneDrive, whose point isn't in
 * [points] (deleted), or whose files are gone are left out. Returns how many photos it holds. JPEGs are already
 * compressed, so entries are stored as they are.
 */
fun buildExportZip(photos: List<LocalPhotoEntity>, points: Map<String, Point>, out: File): Int {
    val used = HashSet<String>()
    var count = 0
    ZipOutputStream(FileOutputStream(out)).use { zip ->
        zip.setLevel(Deflater.NO_COMPRESSION)
        for (photo in photos) {
            if (photo.state == CONFIRMED) continue
            val point = points[photo.pointId] ?: continue
            val source = photo.stampedFile() ?: File(photo.originalPath).takeIf { it.isFile } ?: continue
            val folder = safeSegments(point.folderPath ?: plannedFolder(point)).ifEmpty { safeSegments(plannedFolder(point)) }.joinToString("/")
            val name = (safeSegments(photo.fileName?.removeSuffix(".jpg") ?: photo.id).joinToString("_").ifEmpty { photo.id }) + ".jpg"
            val entry = "$folder/$name"
            if (!used.add(entry)) continue
            zip.putNextEntry(ZipEntry(entry))
            source.inputStream().use { it.copyTo(zip) }
            zip.closeEntry()
            count++
        }
    }
    return count
}

/**
 * Menu → "Export photos to OneDrive app" (spec §6.6.2): the job's photos on this phone that aren't in OneDrive, in a
 * Field-Notes-shaped ZIP ([buildExportZip]) under `cacheDir/shared/`, handed to Android's share sheet through the app's
 * FileProvider (the OneDrive app, Drive, email…). Photos the server shows delivered (its takeover) or removed, and those
 * deleted on this phone, are left out. Returns what to tell the tech instead when there is nothing to export; null once
 * the share sheet is shown.
 * Throws [IOException] when the ZIP can't be written.
 */
suspend fun exportPhotosToOneDriveApp(context: Context, db: AppDatabase, jobId: String): String? {
    val zip = withContext(Dispatchers.IO) {
        val job = db.jobs().get(jobId)?.toJob()
        val points = db.points().forJob(jobId).map { it.toPoint() }.filter { it.deletedAt == null }.associateBy { it.id }
        val deleted = PhotoTombstones(db.meta()).ids()
        val waiting = db.localPhotos().forJob(jobId).filter { row ->
            if (row.id in deleted) return@filter false
            val server = db.photos().get(row.id)?.toPhoto()
            server == null || (!server.delivered() && server.status != PhotoStatus.removed)
        }
        val base = (job?.name ?: jobId).replace(UNSAFE, "_").trim().ifEmpty { "job" }
        val target = File(context.cacheDir, "shared/exports/${base}_photos.zip")
        if (!target.parentFile!!.isDirectory && !target.parentFile!!.mkdirs()) throw IOException("Can't make a folder for the export")
        // Written aside, then moved over the last export: an app still reading that one never sees a half-written ZIP.
        val part = File(target.parentFile, target.name + ".part")
        val count = try {
            buildExportZip(waiting, points, part)
        } catch (e: IOException) {
            part.delete()
            throw e
        }
        when {
            count == 0 -> {
                part.delete()
                null
            }
            part.renameTo(target) -> target
            else -> throw IOException("Can't write the export")
        }
    } ?: return NOTHING_TO_EXPORT

    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", zip)
    val send = Intent(Intent.ACTION_SEND)
        .setType(ZIP_TYPE)
        .putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    send.clipData = ClipData.newRawUri(zip.name, uri) // the read grant reaches whichever app the tech picks
    val chooser = Intent.createChooser(send, "Export photos to OneDrive app")
    if (context !is Activity) chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(chooser)
    return null
}
