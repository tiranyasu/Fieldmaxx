package com.fieldmaxx.app.photos

import com.fieldmaxx.app.data.AppDatabase
import com.fieldmaxx.app.data.DELETED_PHOTO_PREFIX
import com.fieldmaxx.app.data.MetaDao
import com.fieldmaxx.app.data.MetaEntity
import com.fieldmaxx.core.FmxJson
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable

/** How long a deleted photo's tombstone is kept when nothing settles it sooner. */
internal const val TOMBSTONE_KEEP_MS = 7L * 24 * 60 * 60 * 1000

/** [deletedAt]: when the delete was confirmed; [removeOpId]: the PhotoRemove queued for it, once the server is known to have it. */
@Serializable
internal data class PhotoTombstone(val deletedAt: Long, val removeOpId: String? = null)

/**
 * The photos deleted on this phone, one tombstone each (fix round 1): the delete's durable record, kept after the photo's
 * row. [PhotoRemover] writes it in the delete's own transaction, before the first file goes. Then:
 * - a kill partway can't bring the photo back: each pipeline step and the watcher finish the delete instead (I1);
 * - a registration that reached the server meanwhile gets its PhotoRemove once this phone hears of it (I2);
 * - every list and count of photos leaves the photo out ([deletedPhotoIds]; the SQL counts in Daos.kt).
 *
 * The pipeline clears it once the server's state shows the photo removed and any PhotoRemove of it is answered, or 7 days
 * on ([TOMBSTONE_KEEP_MS]). Kept as [MetaEntity] rows ([DELETED_PHOTO_PREFIX] + id), so the database needs no new table.
 */
internal class PhotoTombstones(private val meta: MetaDao) {
    suspend fun put(id: String, tombstone: PhotoTombstone) {
        meta.put(MetaEntity(DELETED_PHOTO_PREFIX + id, FmxJson.encodeToString(PhotoTombstone.serializer(), tombstone)))
    }

    suspend fun get(id: String): PhotoTombstone? = meta.get(DELETED_PHOTO_PREFIX + id)?.let(::decode)

    suspend fun all(): Map<String, PhotoTombstone> =
        meta.keysStartingWith(DELETED_PHOTO_PREFIX).associate { key -> key.removePrefix(DELETED_PHOTO_PREFIX) to (meta.get(key)?.let(::decode) ?: PhotoTombstone(0)) }

    suspend fun ids(): Set<String> = meta.keysStartingWith(DELETED_PHOTO_PREFIX).mapTo(HashSet()) { it.removePrefix(DELETED_PHOTO_PREFIX) }

    suspend fun clear(id: String) = meta.delete(DELETED_PHOTO_PREFIX + id)

    /** A tombstone whose text can't be read still marks the photo deleted; its time is unknown, so it is the oldest. */
    private fun decode(json: String): PhotoTombstone = try {
        FmxJson.decodeFromString(PhotoTombstone.serializer(), json)
    } catch (e: IllegalArgumentException) { // kotlinx.serialization's SerializationException
        PhotoTombstone(0)
    }
}

/** The ids of the photos deleted on this phone ([PhotoTombstones]), kept up to date: lists and counts leave them out. */
fun deletedPhotoIds(db: AppDatabase): Flow<Set<String>> = db.meta().observeKeysStartingWith(DELETED_PHOTO_PREFIX)
    .map { keys -> keys.mapTo(HashSet()) { it.removePrefix(DELETED_PHOTO_PREFIX) } }
    .distinctUntilChanged()
