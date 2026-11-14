package com.fieldmaxx.app.photos

import com.fieldmaxx.app.AppGraph
import com.fieldmaxx.app.data.AppDatabase
import com.fieldmaxx.app.data.toJob
import com.fieldmaxx.app.data.toPhoto
import com.fieldmaxx.app.data.toPoint
import com.fieldmaxx.core.model.PhotoInfo
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

/** How far a photo got on its way to OneDrive, as its thumbnail shows it (spec §6.6.2: ⏳ ⬆ ✓ ⚠). */
enum class PhotoUiStatus { ON_PHONE, UPLOADING, IN_ONEDRIVE, STUCK }

/**
 * One of a point's photos as the point sheet shows it ([photoViews]). [thumb] is a `java.io.File` (the copy on this phone)
 * or a URL String (the server's thumbnail); [backedUp]: the server's backup is in place; [error]: why it is stuck;
 * [destPath]: where it is inside the job's Field Notes folder, once in OneDrive; [ownerId]: the tech who took (or reused)
 * it; [fileName]: the name the server gave it; [onThisPhone]: this phone holds it, so "Retry now" and a delete work on its
 * own copy; [reuseNote]: "reused from POLE 5 · A05J9XE · 11/05/26" on a reused photo.
 */
data class PhotoView(
    val id: String,
    val thumb: Any,
    val status: PhotoUiStatus,
    val backedUp: Boolean,
    val error: String?,
    val destPath: String?,
    val ownerId: String,
    val fileName: String? = null,
    val onThisPhone: Boolean = false,
    val reuseNote: String? = null,
)

/**
 * The point's photos (R63), kept up to date: this phone's (Room's LocalPhotoEntity) merged with the server's (the sync
 * engine's copy). The server's photos are read with removed ones included, so that one hidden on the server never shows
 * from this phone's copy either; a photo deleted on this phone ([deletedPhotoIds]) never shows from either side. [server]
 * is the server's address, for the thumbnails; [zone], the day a reused photo was taken. Done closes the point with these
 * photos (R48).
 */
fun photoViewsFor(pointId: String, db: AppDatabase, server: () -> String, zone: () -> ZoneId = ZoneId::systemDefault): Flow<List<PhotoView>> =
    combine(db.localPhotos().observeForPoint(pointId), db.photos().observeForPoint(pointId), deletedPhotoIds(db)) { local, rows, deleted ->
        local.filter { it.id !in deleted } to rows.map { it.toPhoto() }.filter { it.id !in deleted }
    }
        .map { (local, photos) -> withReuseNotes(photoViews(local, photos, server()), photos, db, zone()) }
        .distinctUntilChanged()
        .flowOn(Dispatchers.IO) // the thumbnails check this phone's files

/** The point sheet's photos: [photoViewsFor] from the app's stores. */
fun AppGraph.photoViewsFor(pointId: String): Flow<List<PhotoView>> = photoViewsFor(pointId, db, server::get)

/** Whether a photo of the point that reached OneDrive was hidden since ([hiddenDelivered]): a tech can't delete the point then. */
fun hiddenDeliveredFor(pointId: String, db: AppDatabase): Flow<Boolean> =
    combine(db.localPhotos().observeForPoint(pointId), db.photos().observeForPoint(pointId)) { local, rows ->
        hiddenDelivered(local, rows.map { it.toPhoto() })
    }.distinctUntilChanged().flowOn(Dispatchers.Default)

/** A job's photos by point ([photoViews] per point; points with none left out), and how many of each aren't in OneDrive yet ([pendingCount]). */
data class JobPhotoViews(val byPoint: Map<String, List<PhotoView>>, val pending: Map<String, Int>)

/** "Where the photos went": the job's photos, both sides merged point by point, kept up to date; deleted ones left out. */
fun jobPhotoViewsFor(jobId: String, db: AppDatabase, server: () -> String): Flow<JobPhotoViews> =
    combine(db.localPhotos().observeForJob(jobId), db.photos().observeForJob(jobId), deletedPhotoIds(db)) { local, rows, deleted ->
        val localByPoint = local.filter { it.id !in deleted }.groupBy { it.pointId }
        val serverByPoint = rows.map { it.toPhoto() }.filter { it.id !in deleted }.groupBy { it.pointId }
        val pointIds = localByPoint.keys + serverByPoint.keys
        val address = server()
        val byPoint = pointIds.associateWith { photoViews(localByPoint[it].orEmpty(), serverByPoint[it].orEmpty(), address) }.filterValues { it.isNotEmpty() }
        JobPhotoViews(byPoint, byPoint.keys.associateWith { pendingCount(localByPoint[it].orEmpty(), serverByPoint[it].orEmpty()) })
    }.distinctUntilChanged().flowOn(Dispatchers.IO)

/** Each reused photo names where it came from (R66). */
private suspend fun withReuseNotes(views: List<PhotoView>, photos: List<PhotoInfo>, db: AppDatabase, zone: ZoneId): List<PhotoView> {
    val reused = photos.filter { it.reusedFrom != null }.associateBy { it.id }
    if (reused.isEmpty()) return views
    val sources = ReuseSources(db.meta())
    return views.map { v -> reused[v.id]?.let { v.copy(reuseNote = reuseNote(it, sources, db, zone)) } ?: v }
}

/**
 * "reused from POLE 5 · A05J9XE · 11/05/26": the source as the picking phone kept it ([ReuseSources]), else from the
 * source's rows on this phone (a photo of a stored job); a source from a job this phone doesn't have stays unnamed. The
 * day is the copy's, which is the original's (reusing keeps when it was taken).
 */
private suspend fun reuseNote(copy: PhotoInfo, sources: ReuseSources, db: AppDatabase, zone: ZoneId): String {
    val source = sources.get(copy.id) ?: copy.reusedFrom?.let { storedSource(it, db) }
    return if (source != null) "reused from " + photoSource(source.pointLabel, source.jobName, copy.takenAt, zone)
    else "reused from another job · " + photoDate(copy.takenAt, zone)
}

private suspend fun storedSource(photoId: String, db: AppDatabase): ReuseSource? {
    val photo = db.photos().get(photoId)?.toPhoto() ?: return null
    val label = db.points().get(photo.pointId)?.toPoint()?.label ?: return null
    val job = db.jobs().get(photo.jobId)?.toJob()?.name ?: return null
    return ReuseSource(label, job)
}
