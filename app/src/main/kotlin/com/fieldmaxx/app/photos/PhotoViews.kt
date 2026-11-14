package com.fieldmaxx.app.photos

import com.fieldmaxx.app.data.LocalPhotoEntity
import com.fieldmaxx.core.model.PhotoInfo
import com.fieldmaxx.core.model.PhotoStatus
import java.io.File
import java.net.URLEncoder
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val PHOTO_DATE = DateTimeFormatter.ofPattern("MM/dd/yy", Locale.US)

/**
 * One photo as both sides know it (R63): this phone's row while the phone holds the photo, and the server's [PhotoInfo]
 * (other techs' photos, reused ones, and this phone's own once its copies are cleaned up). The local state wins while the
 * row exists, except that a server "confirmed" or "stuck" outranks a local "registered": the server took over the
 * delivery (R57). Removed photos are left out before this.
 */
private class Merged(val id: String, val local: LocalPhotoEntity?, val server: PhotoInfo?) {
    private val meta: CaptureMeta? by lazy { local?.readMeta() }
    private val serverOutranks = server != null && (local == null || (local.state == REGISTERED && (server.delivered() || server.status == PhotoStatus.stuck)))

    val status: PhotoUiStatus = when {
        serverOutranks -> server!!.uiStatus()
        local!!.state == CONFIRMED -> PhotoUiStatus.IN_ONEDRIVE
        local.stuck -> PhotoUiStatus.STUCK
        local.state == REGISTERED -> PhotoUiStatus.UPLOADING
        else -> PhotoUiStatus.ON_PHONE // saved or stamped
    }

    /** Only a stuck photo says why, and the reason comes from the side whose status shows. */
    val error: String? = when {
        status != PhotoUiStatus.STUCK -> null
        serverOutranks -> server?.lastError ?: local?.lastError
        else -> local?.lastError ?: server?.lastError
    }

    /** This phone's photos are the tech's who took them (R78), then the server's attribution. */
    val ownerId: String get() = meta?.techId ?: server?.userId ?: ""

    /** When it was taken: the server's record once there is one (the same moment, sent at registration). */
    val takenAt: Long get() = server?.takenAt ?: meta?.takenAt ?: local?.createdAt ?: 0

    fun view(serverUrl: String) = PhotoView(
        id = id,
        // A reused copy's server thumbnail is its original's (photos/PhotoService.kt), there before the server has the copy.
        thumb = local?.thumbFile() ?: photoThumbUrl(serverUrl, server?.reusedFrom ?: id),
        status = status,
        backedUp = local?.backupDone == true || server?.backedUp == true,
        error = error,
        destPath = server?.destPath,
        ownerId = ownerId,
        fileName = local?.fileName ?: server?.fileName,
        onThisPhone = local != null,
    )
}

private fun PhotoInfo.uiStatus(): PhotoUiStatus = when {
    delivered() -> PhotoUiStatus.IN_ONEDRIVE
    status == PhotoStatus.stuck -> PhotoUiStatus.STUCK
    else -> PhotoUiStatus.UPLOADING
}

/** Both sides' photos by id, removed ones left out whatever this phone still holds of them, in the order they were taken. */
private fun merge(local: List<LocalPhotoEntity>, server: List<PhotoInfo>): List<Merged> {
    val removed = server.filter { it.status == PhotoStatus.removed }.mapTo(HashSet()) { it.id }
    val locals = local.associateBy { it.id }
    val servers = server.associateBy { it.id }
    return (locals.keys + servers.keys)
        .filter { it !in removed }
        .map { Merged(it, locals[it], servers[it]) }
        .sortedWith(compareBy<Merged> { it.takenAt }.thenBy { it.id })
}

/**
 * The photos as their thumbnails show them (spec §6.6.2), merged by id from this phone's [local] rows and the [server]'s
 * (R63). Each thumbnail is this phone's own copy (the stamped one, or the original while it waits for its stamp), else
 * the server's thumbnail at [serverUrl] (a reused photo's is its original's).
 */
fun photoViews(local: List<LocalPhotoEntity>, server: List<PhotoInfo>, serverUrl: String = ""): List<PhotoView> =
    merge(local, server).map { it.view(serverUrl) }

/** How many distinct photos aren't in OneDrive yet, on either side (R63): for a point's or a job's count. */
fun pendingCount(local: List<LocalPhotoEntity>, server: List<PhotoInfo>): Int =
    merge(local, server).count { it.status != PhotoUiStatus.IN_ONEDRIVE }

/**
 * Whether a photo that reached OneDrive was hidden since: no longer listed, it still keeps a tech from deleting its point,
 * as the server rules (`status = 'confirmed' OR confirmed_at IS NOT NULL`, ops/Ops.kt). This phone may have seen it in
 * OneDrive before the server heard.
 */
fun hiddenDelivered(local: List<LocalPhotoEntity>, server: List<PhotoInfo>): Boolean {
    val confirmedHere = local.filter { it.state == CONFIRMED }.mapTo(HashSet()) { it.id }
    return server.any { it.status == PhotoStatus.removed && (it.confirmedAt != null || it.id in confirmedHere) }
}

/** The server's thumbnail of a photo, `<server>/api/photos/<id>/thumb`, loaded through the app's signed-in Coil loader. */
fun photoThumbUrl(server: String, photoId: String): String =
    server.trimEnd('/') + "/api/photos/" + URLEncoder.encode(photoId, "UTF-8").replace("+", "%20") + "/thumb"

/** "11/05/26": the day a photo was taken, in [zone] (the stamp's). */
fun photoDate(takenAt: Long, zone: ZoneId): String = PHOTO_DATE.format(Instant.ofEpochMilli(takenAt).atZone(zone))

/** "POLE 5 · A05J9XE · 11/05/26": a photo already taken, as the reuse picker offers it and a reused photo names its source. */
fun photoSource(pointLabel: String, jobName: String, takenAt: Long, zone: ZoneId): String = "$pointLabel · $jobName · ${photoDate(takenAt, zone)}"

/** The thumbnail's mark for each status (spec §6.6.2). */
internal fun PhotoUiStatus.glyph(): String = when (this) {
    PhotoUiStatus.ON_PHONE -> "⏳"
    PhotoUiStatus.UPLOADING -> "⬆"
    PhotoUiStatus.IN_ONEDRIVE -> "✓"
    PhotoUiStatus.STUCK -> "⚠"
}

internal fun PhotoUiStatus.words(): String = when (this) {
    PhotoUiStatus.ON_PHONE -> "On this phone"
    PhotoUiStatus.UPLOADING -> "Uploading"
    PhotoUiStatus.IN_ONEDRIVE -> "In OneDrive"
    PhotoUiStatus.STUCK -> "Stuck"
}

/** The mark of a photo whose server backup is in place (spec §6.6.2: "a small shield"). */
internal const val SHIELD = "🛡"

/** The capture's record, or null for a row whose meta.json text can't be read as one. */
private fun LocalPhotoEntity.readMeta(): CaptureMeta? = try {
    captureMeta()
} catch (e: IllegalArgumentException) { // kotlinx.serialization's SerializationException
    null
}

/** This phone's copy to show: the stamped one, else the original while it waits for its stamp. */
private fun LocalPhotoEntity.thumbFile(): File? = stampedFile() ?: File(originalPath).takeIf { it.isFile }
